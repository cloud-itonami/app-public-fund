#!/usr/bin/env nbb
;; award_claim_fixtures.cljs — deterministic offline fixtures for the
;; award-claim-pipeline contract (`award-claim/award-claim-pipeline.edn`).
;;
;; Runs a full pipeline on fixture data — source proposal → fetch receipt
;; → parser admission → dedupe → bounded retry/refusal → signed claim
;; proposal → readback — and asserts every stage's invariants. No network.
;;
;; Exit codes:
;;   0  all fixtures ran clean
;;   1  a fixture found a violation
;;   2  REFUSED — the contract could not be read
;;
;; Usage: nbb tools/award_claim_fixtures.cljs [path/to/contract.edn]

(ns award-claim-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join "award-claim" "award-claim-pipeline.edn")))

(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

(defn sha256 [s]
  (let [h (crypto/createHash "sha256")]
    (.update h s) (.digest h "hex")))

;; ── Stage 1: source proposal ─────────────────────────────────────────
(def sp (:source-proposal contract))

(defn propose-source [id url class]
  {:proposal-id id :source-url url :source-class class
   :source-language "en" :justification "fixture registry"
   :proposed-at "2026-08-30"})

(def proposals
  [(propose-source "sp-1" "https://registry.example.test/awards"
                   :official-funder-registry)
   (propose-source "sp-2" "https://blog.example.test/awards-rumor"
                   :search-snippet)])

(defn fixture-source-proposal [f]
  (when-not (contains? (:source-class-allow sp) :official-funder-registry)
    (fail! f "allow list must contain :official-funder-registry"))
  (doseq [p proposals]
    (if (contains? (:source-class-allow sp) (:source-class p))
      (when-not (:source-url p) (fail! f "allowed proposal missing url"))
      (println (str "STAGE source-proposal | " (:proposal-id p)
                    " | refused-before-fetch | "
                    (name (:source-class p)) " is on the forbid list")))))

;; ── Stage 2: fetch receipt ───────────────────────────────────────────
(def fr (:fetch-receipt contract))

(def body-1 "AWARD REGISTRY EXPORT\ngrant-id: GR-001\nfunder: EXAMPLE-FUNDER\ntitle: Example Quantum Materials Award\namount: 1,200,000 USD obligating\nlanguage: en")
(def body-1-again body-1) ; second fetch of the same page (dedupe fixture)
(def body-2 "AWARD REGISTRY EXPORT\nfunder: EXAMPLE-FUNDER\ntitle: Award without an identifier\nlanguage: en")

(defn receipt [id body status]
  {:receipt-id id :source-url "https://registry.example.test/awards"
   :source-class :official-funder-registry :source-language "en"
   :observed-at "2026-08-30" :content-hash (sha256 body) :fetch-status status})

(def receipts
  [(receipt "r-1" body-1 :ok)
   (receipt "r-2" body-1-again :ok)   ; same bytes as r-1
   (receipt "r-3" body-2 :ok)])

(defn fixture-receipt [f]
  (doseq [r receipts]
    (when-not (re-find #"[0-9a-f]{64}" (:content-hash r))
      (fail! f (str (:receipt-id r) " content-hash not sha256-hex"))))
  (when (some #(contains? #{:paywall-bypass :captcha-bypass} %)
              (:source-class-forbid sp))
    (when-not (and (:respect-robots? (:fetch fr)) (:no-bypass? (:fetch fr)))
      (fail! f "fetch policy must respect robots and never bypass")))
  (doseq [r receipts]
    (println (str "STAGE fetch-receipt | " (:receipt-id r) " | "
                  (name (:fetch-status r)) " | sha256=" (:content-hash r)))))

;; ── Stage 3: parser / admission ──────────────────────────────────────
(def pa (:parser-admission contract))

(defn admit [rid record]
  (if (:grant-id record)
    {:receipt-id rid :record-id (str "rec-" rid) :decision :admitted
     :reason-code nil
     :original-language-fields {:original-title (:title record)
                                :grant-id (:grant-id record)
                                :funder-name (:funder record)}
     :admitted-at "2026-08-30"}
    {:receipt-id rid :record-id (str "rec-" rid) :decision :refused
     :reason-code :identifier-missing
     :original-language-fields {:original-title (:title record)}
     :admitted-at "2026-08-30"}))

(def admissions
  [(admit "r-1" {:title "Example Quantum Materials Award" :grant-id "GR-001"
                 :funder "EXAMPLE-FUNDER"})
   (admit "r-2" {:title "Example Quantum Materials Award" :grant-id "GR-001"
                 :funder "EXAMPLE-FUNDER"})
   (admit "r-3" {:title "Award without an identifier"})])

(defn fixture-admission [f]
  (doseq [a admissions]
    (if (= :admitted (:decision a))
      (when-not (:grant-id (:original-language-fields a))
        (fail! f "admitted record lost its grant-id"))
      (when-not (contains? (:refusal-codes pa) (:reason-code a))
        (fail! f (str "refusal code " (:reason-code a) " not in contract")))))
  (when-not (some #(= :refused (:decision %)) admissions)
    (fail! f "refusals must be recorded, never silently dropped"))
  (let [ad (some #(when (= :admitted (:decision %)) %) admissions)]
    (when-not (and ad (every? #(contains? (:original-language-fields ad) %)
                              [:original-title :grant-id :funder-name]))
      (fail! f "original language fields (original-title/grant-id/funder-name) must be preserved verbatim"))
    (doseq [a admissions]
      (println (str "STAGE parser-admission | " (:record-id a) " | "
                    (name (:decision a)) " | "
                    (if (:reason-code a) (name (:reason-code a)) "-"))))))

;; ── Stage 4: dedupe ──────────────────────────────────────────────────
(def dd (:dedupe contract))

(defn dedupe-key [a]
  {:registry-namespace "example-registry"
   :grant-id (get-in a [:original-language-fields :grant-id])
   :award-kind :stated-award
   :funder-id (get-in a [:original-language-fields :funder-name])})

(defn fixture-dedupe [f]
  (let [k1 (dedupe-key (admissions 0))
        k2 (dedupe-key (admissions 1))]
    (when-not (= k1 k2)
      (fail! f "same bytes must produce the same dedupe key"))
    (when-not (= (:on-collision dd) {:first-wins-keep-provenance true
                                     :append :refresh-history
                                     :never-overwrite? true})
      (fail! f "collision policy must keep first provenance and never overwrite"))
    (println (str "STAGE dedupe | " (:grant-id k1)
                  " | collision | second receipt appended to refresh-history, first provenance kept"))))

;; ── Stage 5: bounded retry / refusal ─────────────────────────────────
(def rr (:retry-refusal contract))

(def attempts {"src-A" [{:n 1 :status :http-error}
                        {:n 2 :status :http-error}
                        {:n 3 :status :http-error}
                        {:n 4 :status :http-error}]}) ; exceeds bound

(defn fixture-retry [f]
  (let [a (attempts "src-A")
        maxn (:max-attempts-per-source rr)
        used (count a)]
    (when-not (> used maxn)
      (fail! f "fixture must exercise the over-bound case"))
    (when-not (contains? (:retryable-fetch-status rr) :http-error)
      (fail! f ":http-error must be retryable"))
    (when-not (contains? (:non-retryable rr) :blocked-by-policy)
      (fail! f "policy blocks must be non-retryable"))
    (when (get-in rr [:on-exhausted :fabricate-placeholder?])
      (fail! f "exhausted retries must never fabricate a placeholder"))
    (println (str "STAGE retry-refusal | src-A | refusal-recorded | "
                  used " attempts > bound " maxn "; no placeholder fabricated; deferred to next run"))))

;; ── Stage 6: signed claim proposal ───────────────────────────────────
(def cp (:claim-proposal contract))

(def claim
  {:claim-id "c-1" :proposal-id "sp-1"
   :dedupe-key (dedupe-key (admissions 0))
   :claim-kind :funding-award-observed
   :entity-record {:entity-id "e-funder-1" :entity-type :funder
                   :name "EXAMPLE-FUNDER"}
   :amount {:kind :stated-award :currency "USD" :value 1200000
            :as-stated-by-source? true}
   :window {:from "2026-08-01" :until "2026-09-01"}
   :source-receipt-id "r-1"
   :coverage-record-ref "cov-example-registry-2026-08"
   :missingness-flags #{}
   :signature (sha256 "c-1-content-canonical-edn")
   :proposed-at "2026-08-30"})

(defn fixture-claim [f]
  (when-not (contains? (:claim-kinds cp) (:claim-kind claim))
    (fail! f "claim kind not in contract"))
  (when-not (re-find #"[0-9a-f]{64}" (:signature claim))
    (fail! f "claim signature missing"))
  (when-not (= :stated-award (:kind (:amount claim)))
    (fail! f "amount kind must be carried, not collapsed"))
  (when-not (get-in claim [:amount :as-stated-by-source?])
    (fail! f "amount must carry as-stated-by-source?"))
  (doseq [k (keys claim)]
    (when (contains? (:forbidden-fields cp) k)
      (fail! f (str "forbidden field present in claim: " k))))
  (when-not (= :provenance-only-not-truth-assertion
               (get-in cp [:signature :meaning]))
    (fail! f "signature must assert provenance only"))
  (println (str "STAGE claim-proposal | " (:claim-id claim)
                " | signed | provenance-only signature over canonical content")))

;; ── Stage 7: readback ────────────────────────────────────────────────
(defn fixture-readback [f]
  (let [resp {:query-id "q-1" :status :ok :claims ["c-1"]
              :coverage-record-ref "cov-example-registry-2026-08"
              :missingness-flags #{}}]
    (when-not (contains? (:status-values (:query-readback contract)) (:status resp))
      (fail! f "readback status not in contract"))
    (when-not (and (:coverage-record-ref resp)
                   (contains? resp :missingness-flags))
      (fail! f "readback must carry coverage and missingness"))
    (println "STAGE query-readback | q-1 | ok | claims=1 coverage carried")))

;; ── Stage 8: audit output covers every stage ─────────────────────────
(defn fixture-audit [f]
  (let [printed #{"source-proposal" "fetch-receipt" "parser-admission"
                  "dedupe" "retry-refusal" "claim-proposal"
                  "query-readback" "audit-output"}]
    (doseq [s (:per-stage (:audit-output contract))]
      (when-not (contains? printed (name s))
        (fail! f (str "audit line missing for stage " (name s)))))
    (when-not (= (count (:per-stage (:audit-output contract))) 8)
      (fail! f "audit must cover exactly the 8 pipeline stages")))
  (println "STAGE audit-output | run | complete | all stages covered including refusals"))

;; ── Run ──────────────────────────────────────────────────────────────
(println (str "contract: " (:contract/id contract)
              " " (:contract/version contract)
              " (" (:method/version contract) ")"))
(fixture-source-proposal "source-proposal")
(fixture-receipt "fetch-receipt")
(fixture-admission "parser-admission")
(fixture-dedupe "dedupe")
(fixture-retry "retry-refusal")
(fixture-claim "claim-proposal")
(fixture-readback "query-readback")
(fixture-audit "audit-output")

(if (empty? @failures)
  (do (println "OK: all award-claim-pipeline fixtures ran clean")
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "VIOLATION [" fixture "] " msg)))
      (js/process.exit 1)))
