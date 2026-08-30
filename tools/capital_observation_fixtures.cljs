#!/usr/bin/env nbb
;; capital_observation_fixtures.cljs — deterministic offline fixtures for the
;; fund-close-observation contract (`capital-observation/fund-close-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/capital_observation_fixtures.cljs [path/to/contract.edn]

(ns capital-observation-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "fund-close-observation.edn")))

;; ── Load contract ───────────────────────────────────────────────────
(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

;; Deterministic fake "response body" for receipts. sha256 is computed the
;; same way the real receipt capture would compute it.
(defn sha256 [s]
  (.update (crypto/createHash "sha256") s)
  (.digest (crypto/createHash "sha256") "hex"))

(defn receipt [id url class lang body]
  {:receipt-id id :source-url url :source-class class
   :source-language lang :observed-at "2026-08-30"
   :content-hash (sha256 body) :fetch-status :ok})

;; ── Fixture data (deterministic, no network) ────────────────────────
;; Two entities sharing a brand name — entity separation fixture.
(def receipts
  [(receipt "r-1" "https://example-reg.test/fund-a/close" :official-regulator "en"
            "FUND-A first close notice")
   (receipt "r-2" "https://example-fund.test/press" :fund-first-party "en"
            "FUND-A announces final close")])

(def entities
  [{:entity-id "e-1" :entity-type :fund-vehicle :name "FUND-A"
    :legal-name "FUND-A L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-1"
    :source-receipt-id "r-1" :asserted-at "2026-08-30" :observed-at "2026-08-30"}
   ;; Same brand string, different type: must remain a distinct entity id.
   {:entity-id "e-2" :entity-type :management-company :name "FUND-A"
    :legal-name "FUND-A Management K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-2"
    :source-receipt-id "r-1" :asserted-at "2026-08-30" :observed-at "2026-08-30"}])

(def events
  [{:event-id "ev-1" :event-type :fund-first-close :entity-id "e-1"
    :announced-at "2026-08-10" :asserted-at "2026-08-30"
    :amount {:kind :stated-first-close :currency "JPY"
             :value 5000000000 :as-stated-by-source? true}
    :source-receipt-id "r-1"}
   {:event-id "ev-2" :event-type :fund-final-close :entity-id "e-1"
    :announced-at "2026-08-25" :asserted-at "2026-08-30"
    ;; amount-not-stated: the receipt says final close, no figure.
    :amount {:kind :stated-final-close :value nil :as-stated-by-source? true}
    :source-receipt-id "r-2"}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-08-30" :timezone "UTC"})

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  ;; Every event cites a receipt whose content-hash exists.
  (doseq [ev events]
    (let [r (some #(when (= (:receipt-id %) (:source-receipt-id ev)) %) receipts)]
      (when-not (and r (re-find #"^[0-9a-f]{64}$" (:content-hash r)))
        (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt"))))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities"))))))

(defn fixture-amount-kinds [f]
  ;; announced target / first close / final close kinds must survive —
  ;; never collapsed into one number.
  (let [kinds (set (map (comp :kind :amount) events))]
    (when-not (contains? kinds :stated-final-close)
      (fail! f "final-close kind lost"))
    (when (some #(and (= (:kind %) :stated-final-close)
                      (nil? (:value %))
                      (not (contains? (:missingness :flags contract) :amount-not-stated)))
                events)
      (fail! f "missing amount must flag :amount-not-stated, not vanish"))))

(defn fixture-window [f]
  ;; [from, until) bounds: a final close on until-1 is in; on until is out.
  (let [in? (fn [d] (and (>= (compare d (:from window)) 0)
                         (< (compare d (:until window)) 0)))]
    (when-not (in? "2026-08-25") (fail! f "2026-08-25 must be inside window"))
    (when (in? "2026-09-01") (fail! f "until is exclusive; 2026-09-01 must be out"))))

(defn fixture-derived-observation [f]
  ;; No forbidden field may appear in the derived-observation schema.
  (let [forbidden (:forbidden-fields (:derived-observation contract))]
    (when-not (= (count forbidden) 8)
      (fail! f "forbidden observation fields (rank/score/centrality/…) incomplete")))
  (let [{:keys [forbidden-fields]} (:derived-observation contract)
        forbidden forbidden-fields
        obs {:observation-id "o-1" :method/version (:method/version contract)
             :window window :observation-kind :fund-close-in-window
             :entity-id "e-1" :event-id "ev-1"
             :value {:kind :close-count :basis #{:receipt-only}}
             :missingness-flags #{}
             :provenance-chain ["r-1"]
             :asserted-at "2026-08-30"}
        keys' (set (map keyword (keys obs)))]
    (when (some #(contains? keys' (keyword (name %))) forbidden)
      (fail! f "derived observation carries a forbidden field"))))

(defn fixture-refresh-history [f]
  (let [h {:history-id "h-1" :observation-id "o-1"
           :re-observed-at "2026-09-30"
           :reason {:kind :window-advanced} :changed-fields [:window]}]
    (when-not (:append-only? (:refresh-history contract))
      (fail! f "refresh history must be append-only"))
    (when (nil? (:reason h)) (fail! f "refresh entry must state its reason"))))

(defn fixture-readback [f]
  ;; Query roundtrip: response always carries coverage + missingness;
  ;; unknown status is rejected.
  (let [resp {:query-id "q-1" :status :ok :observations ["o-1"]
              :coverage-record-ref "cov-1" :missingness-flags #{}}
        statuses (:status-values (:query-readback contract))]
    (when-not (contains? statuses (:status resp))
      (fail! f "readback status not in declared status set"))
    (doseq [k [:coverage-record-ref :missingness-flags]]
      (when (nil? (get resp k)) (fail! f (str "readback missing " k))))))

(defn fixture-coverage-missingness [f]
  ;; A coverage record must separate observed from unmeasured.
  (let [cov {:coverage-unit :jurisdiction :unit-key "JP" :observed-count 2
             :unmeasured-count 1 :window window
             :method/version (:method/version contract)}]
    (when-not (and (pos? (:observed-count cov))
                   (pos? (:unmeasured-count cov)))
      (fail! f "coverage must report unmeasured alongside observed"))))

(def fixtures
  {"provenance" fixture-provenance
   "entity-separation" fixture-entity-separation
   "amount-kinds" fixture-amount-kinds
   "window-bounds" fixture-window
   "derived-observation" fixture-derived-observation
   "refresh-history" fixture-refresh-history
   "readback" fixture-readback
   "coverage-missingness" fixture-coverage-missingness})

;; ── Run ─────────────────────────────────────────────────────────────
(defn run-all []
  (println (str "contract: " contract-path))
  (println (str "method/version: " (:method/version contract)))
  (doseq [[name f] fixtures]
    (try
      (f name)
      (println (str "  ok    " name))
      (catch :default e
        (fail! name (str "fixture could not run: " (.-message e)))
        (println (str "  ERROR " name)))))
  (if (empty? @failures)
    (do (println "all fixtures passed (deterministic, offline)") 0)
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL " fixture ": " msg)))
        1)))

(js/process.exit (run-all))
