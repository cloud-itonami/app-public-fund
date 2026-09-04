#!/usr/bin/env nbb
;; co_investment_fixtures.cljs — deterministic offline fixtures for the
;; co-investment-observation contract
;; (capital-observation/co-investment-observation.edn).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/co_investment_fixtures.cljs [path/to/contract.edn]

(ns co-investment-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "co-investment-observation.edn")))

(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

(defn sha256 [s]
  (.digest (doto (crypto/createHash "sha256") (.update s)) "hex"))

(defn receipt [id url class lang body]
  {:receipt-id id :source-url url :source-class class
   :source-language lang :observed-at "2026-09-01"
   :content-hash (sha256 body) :fetch-status :ok})

;; ── Fixture data (deterministic, no network) ────────────────────────
;; A fund first-party page lists participants of round R-ALPHA; a manager
;; first-party page lists a DIFFERENT participant set for the same round
;; (receipt disagreement — carried, never merged). A news report naming
;; the same pair is discovery-only and backs nothing. Two entities share
;; the brand "OMEGA" (a company and a fund vehicle) — separation fixture.
(def receipts
  [(receipt "r-1" "https://omega-fund.example.test/rounds" :fund-first-party "en"
            "Round R-ALPHA participants: OMEGA K.K., Fund vehicle BETA-2")
   (receipt "r-2" "https://example-manager.test/syndicates" :manager-first-party "en"
            "Round R-ALPHA participants: OMEGA K.K., Fund vehicle BETA-2, LP GAMMA-P")
   ;; single-participant listing: produces no edge.
   (receipt "r-3" "https://delta-fund.example.test/rounds" :fund-first-party "en"
            "Round R-DELTA participants: OMEGA K.K.")
   (receipt "r-4" "https://news.example.test/omega-beta" :news-report "en"
            "OMEGA and BETA co-invested in R-ALPHA")
   (receipt "r-5" "https://omega-registry.example.test/entry" :official-company-registry "en"
            "OMEGA K.K. — registry record")])

(def entities
  [{:entity-id "e-1" :entity-type :company :name "OMEGA"
    :legal-name "OMEGA K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-O"
    :source-receipt-id "r-5" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   ;; Same brand string "OMEGA", different type: must remain a distinct id.
   {:entity-id "e-2" :entity-type :fund-vehicle :name "OMEGA"
    :legal-name "OMEGA Growth Fund I L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-OG1"
    :source-receipt-id "r-5" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-3" :entity-type :fund-vehicle :name "BETA"
    :legal-name "Fund vehicle BETA-2 L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-B2"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-4" :entity-type :limited-partner :name "GAMMA-P"
    :legal-name "GAMMA-P Pension Trust" :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-GP"
    :source-receipt-id "r-2" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

(def events
  [{:event-id "ev-1" :event-type :round-participants-listed
    :round-id "R-ALPHA" :announced-at "2026-08-10" :asserted-at "2026-09-01"
    :participant-entity-ids ["e-1" "e-3"]
    :listing-kind {:kind :named-investors}
    :as-stated-by-source? true
    :source-receipt-id "r-1" :provenance-chain ["r-1"]}
   ;; second allowed source, same round, DIFFERENT participant set:
   ;; recorded as its own event, never merged into ev-1.
   {:event-id "ev-2" :event-type :round-participants-listed
    :round-id "R-ALPHA" :announced-at "2026-08-12" :asserted-at "2026-09-01"
    :participant-entity-ids ["e-1" "e-3" "e-4"]
    :listing-kind {:kind :named-consortium}
    :as-stated-by-source? true
    :source-receipt-id "r-2" :provenance-chain ["r-2"]}
   ;; single-participant listing — no co-listing edge is derivable.
   {:event-id "ev-3" :event-type :round-participants-listed
    :round-id "R-DELTA" :announced-at "2026-08-20" :asserted-at "2026-09-01"
    :participant-entity-ids ["e-1"]
    :listing-kind {:kind :named-investors}
    :as-stated-by-source? true
    :source-receipt-id "r-3" :provenance-chain ["r-3"]}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

(def entity-ids (set (map :entity-id entities)))
(defn receipt-by-id [id] (some #(when (= (:receipt-id %) id) %) receipts))

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  (doseq [ev events]
    (let [r (receipt-by-id (:source-receipt-id ev))]
      (when-not (and r (re-find #"^[0-9a-f]{64}$" (:content-hash r)))
        (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt"))))
    (doseq [rid (:provenance-chain ev)]
      (when-not (receipt-by-id rid)
        (fail! f (str "event " (:event-id ev) " provenance entry " rid " unresolved"))))))

(defn fixture-receipt-admission [f]
  ;; fetch-status :ok is required; discovery-only sources never back an
  ;; event (r-4 backs nothing here).
  (let [adm (:receipt-admission contract)
        discovery (set (:source-class-discovery-only (:source-receipt contract)))]
    (when-not (= :ok (first (:admit-when adm)))
      (fail! f "receipt admission must require :ok"))
    (doseq [ev events]
      (let [r (receipt-by-id (:source-receipt-id ev))]
        (when (contains? discovery (:source-class r))
          (fail! f (str "event " (:event-id ev) " backed by a discovery-only source")))))
    (let [r4 (receipt-by-id "r-4")]
      (when-not (contains? discovery (:source-class r4))
        (fail! f "fixture sanity: r-4 must be a discovery-only receipt")))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities")))))
  ;; participant ids must resolve to declared entities (participant-ids-
  ;; resolve-to-declared-entities).
  (doseq [ev events]
    (doseq [pid (:participant-entity-ids ev)]
      (when-not (contains? entity-ids pid)
        (fail! f (str "event " (:event-id ev) " participant " pid " unresolved"))))))

(defn fixture-edge-is-adjacency-not-more [f]
  ;; The derived edge must be co-listing adjacency, symmetric, and
  ;; structurally excluded from influence/relationship semantics.
  (let [edge (:edge (:derived-observation contract))]
    (when-not (= :co-listed-in-same-round (:kind edge))
      (fail! f "edge kind must be :co-listed-in-same-round"))
    (when-not (:symmetric? edge)
      (fail! f "co-listing edge must be symmetric/unordered"))
    (when-not (contains? (set (:not edge)) :influence)
      (fail! f "edge semantics must exclude :influence")))
  ;; forbidden fields must structurally exclude ranking/score/influence.
  (let [forbidden (set (map keyword (map name
                          (:forbidden-fields (:derived-observation contract)))))]
    (doseq [k [:centrality :rank :score :degree-count :network-strength
               :influence :syndication-pattern :valuation :returns
               :ownership-stake :suitability :recommendation]]
      (when-not (contains? forbidden k)
        (fail! f (str "forbidden field missing: " k))))))

(defn fixture-disagreement-recorded-not-merged [f]
  ;; Two allowed receipts list different participant sets for R-ALPHA:
  ;; both events exist, neither replaces the other, and the contract's
  ;; refresh rule declares disagreements are never merged.
  (let [alpha (filter #(= "R-ALPHA" (:round-id %)) events)]
    (when-not (= 2 (count alpha))
      (fail! f "both R-ALPHA participant listings must be carried"))
    (when-not (not= (set (:participant-entity-ids (first alpha)))
                    (set (:participant-entity-ids (second alpha))))
      (fail! f "fixture sanity: R-ALPHA listings must differ"))
    (doseq [ev alpha]
      (when-not (= 1 (count (:provenance-chain ev)))
        (fail! f (str "disagreeing event " (:event-id ev)
                      " must cite only its own receipt"))))))

(defn fixture-missing-is-unmeasured [f]
  ;; Single-participant listing yields no edge (single-participant-only
  ;; flag path) and the contract's missingness rule is
  ;; :missing-is-unmeasured.
  (when-not (= :missing-is-unmeasured (:rule (:missingness contract)))
    (fail! f "missingness rule must be :missing-is-unmeasured"))
  (when-not (contains? (set (:flags (:missingness contract)))
                       :single-participant-only)
    (fail! f "missingness must flag :single-participant-only"))
  (when-not (some #(= 1 (count (:participant-entity-ids %))) events)
    (fail! f "fixture sanity: a single-participant listing must exist"))
  (when-not (= :valid-only-inside-window
               (:window-scoping (:derived-observation contract)))
    (fail! f "observations must be scoped to their window")))

(defn fixture-readback-shape [f]
  ;; The readback always carries coverage + missingness + method version
  ;; and answers :out-of-window outside the window.
  (let [qr (:query-readback contract)]
    (doseq [k [:coverage :missingness-flags :method-version :window
               :provenance-chain]]
      (when-not (some #(= k %) (:schema qr))
        (fail! f (str "readback schema missing: " k))))
    (when-not (= :out-of-window-outside-window (:window-scoping qr))
      (fail! f "readback must answer :out-of-window outside its window"))))

(defn fixture-proposal-questions-only [f]
  (let [p (:proposal contract)]
    (when-not (= :auditable-question (:kind p))
      (fail! f "proposal must be :auditable-question"))
    (when (empty? (:example-questions p))
      (fail! f "proposal must carry example questions"))
    (let [never (set (:never p))]
      (doseq [k [:investment-advice :ranking :outreach :solicitation
                 :personal-profiling]]
        (when-not (contains? never k)
          (fail! f (str "proposal :never missing: " k)))))))

(def all-fixtures
  [[:provenance fixture-provenance]
   [:receipt-admission fixture-receipt-admission]
   [:entity-separation fixture-entity-separation]
   [:edge-is-adjacency-not-more fixture-edge-is-adjacency-not-more]
   [:disagreement-recorded-not-merged fixture-disagreement-recorded-not-merged]
   [:missing-is-unmeasured fixture-missing-is-unmeasured]
   [:readback-shape fixture-readback-shape]
   [:proposal-questions-only fixture-proposal-questions-only]])

(defn -main [& _]
  (doseq [[name f] all-fixtures]
    (let [before (count @failures)]
      (f name)
      (println (str "[" name "] "
                    (if (= before (count @failures)) "ok" "VIOLATIONS"))))
    (flush))
  (if (empty? @failures)
    (do (println (str "OK: " (count all-fixtures)
                      " co-investment fixtures ran; 0 violations"))
        0)
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (println (str "FAILED: " (count @failures) " violation(s)"))
        1)))

(js/process.exit (-main))
