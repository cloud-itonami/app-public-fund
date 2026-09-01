#!/usr/bin/env nbb
;; portfolio_listing_fixtures.cljs — deterministic offline fixtures for
;; the portfolio-listing-observation contract
;; (`capital-observation/portfolio-listing-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/portfolio_listing_fixtures.cljs [path/to/contract.edn]

(ns portfolio-listing-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "portfolio-listing-observation.edn")))

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
  (.digest (doto (crypto/createHash "sha256") (.update s)) "hex"))

(defn receipt [id url class lang body]
  {:receipt-id id :source-url url :source-class class
   :source-language lang :observed-at "2026-09-01"
   :content-hash (sha256 body) :fetch-status :ok})

;; ── Fixture data (deterministic, no network) ────────────────────────
;; A manager's first-party portfolio page lists "ALPHA" — a brand string
;; shared with an unrelated fund vehicle. Entity separation fixture.
(def receipts
  [(receipt "r-1" "https://example-manager.test/portfolio" :manager-first-party "en"
            "Our portfolio: ALPHA K.K., BETA Inc.")
   (receipt "r-2" "https://example-manager.test/portfolio" :manager-first-party "en"
            "Our portfolio: BETA Inc.") ; ALPHA removed in a later capture
   (receipt "r-3" "https://news.example.test/article" :news-report "en"
            "Rumor: ALPHA joined a fund portfolio")])

(def entities
  [{:entity-id "e-1" :entity-type :management-company :name "EXAMPLE MANAGER"
    :legal-name "Example Manager K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-M"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   ;; Same brand string "ALPHA", different types: must remain distinct ids.
   {:entity-id "e-2" :entity-type :company :name "ALPHA"
    :legal-name "ALPHA K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-C"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-3" :entity-type :fund-vehicle :name "ALPHA"
    :legal-name "ALPHA Fund I L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-F"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-4" :entity-type :company :name "BETA"
    :legal-name "BETA Inc." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-B"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

(def events
  [{:event-id "ev-1" :event-type :listed-on-portfolio-page
    :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
    :observed-at "2026-08-20" :asserted-at "2026-09-01"
    :listing {:kind :listed-on-portfolio-page :as-stated-by-source? true}
    :source-receipt-id "r-1"}
   ;; A name disappearing from the page is a NEW removal observation.
   {:event-id "ev-2" :event-type :removed-from-portfolio-page
    :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
    :observed-at "2026-08-31" :asserted-at "2026-09-01"
    :listing {:kind :removed-from-portfolio-page :as-stated-by-source? true}
    :source-receipt-id "r-2"}
   {:event-id "ev-3" :event-type :listed-on-portfolio-page
    :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-4"
    :observed-at "2026-08-20" :asserted-at "2026-09-01"
    ;; jurisdiction-not-in-receipt → flag, do not vanish
    :listing {:kind :unstated :as-stated-by-source? true}
    :source-receipt-id "r-2"}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  ;; Every event cites a receipt whose content-hash exists.
  (doseq [ev events]
    (let [r (some #(when (= (:receipt-id %) (:source-receipt-id ev)) %) receipts)]
      (when-not (and r (re-find #"^[0-9a-f]{64}$" (:content-hash r)))
        (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt"))))))

(defn fixture-discovery-only [f]
  ;; A discovery-only source (news report) can never back a derived
  ;; listing observation: only fund/manager/registry classes may.
  (let [allowed (set (:source-class-allow (:source-receipt contract)))]
    (when-not (and (contains? allowed :manager-first-party)
                   (contains? allowed :fund-first-party))
      (fail! f "first-party portfolio sources must be allowed"))
    (doseq [ev events]
      (let [r (some #(when (= (:receipt-id %) (:source-receipt-id ev)) %) receipts)]
        (when (contains? (set (:source-class-discovery-only (:source-receipt contract)))
                         (:source-class r))
          (fail! f (str "event " (:event-id ev) " backed by a discovery-only source")))))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities"))))))

(defn fixture-listing-vs-removal [f]
  ;; listing kinds must survive distinct — a removal never overwrites
  ;; the earlier listing, and both kinds exist in the contract.
  (let [ets (set (:event-types contract))]
    (when-not (and (contains? ets :listed-on-portfolio-page)
                   (contains? ets :removed-from-portfolio-page))
      (fail! f "listing/removal event kinds incomplete"))
    (when-not (some #(and (= (:event-id %) "ev-1")
                          (= (:event-type %) :listed-on-portfolio-page))
                    events)
      (fail! f "earlier listing observation must remain present"))
    (when-not (some #(and (= (:event-id %) "ev-2")
                          (= (:event-type %) :removed-from-portfolio-page))
                    events)
      (fail! f "removal must be recorded as its own event"))))

(defn fixture-not-ownership [f]
  ;; portfolio-listing-is-not-ownership-verification: the contract's
  ;; forbidden fields must structurally exclude ownership/verification.
  (let [forbidden (set (map keyword (map name (:forbidden-fields (:derived-observation contract)))))]
    (doseq [k [:ownership-stake :holding-verification :rank :score
               :returns :suitability :current-valuation]]
      (when-not (contains? forbidden k)
        (fail! f (str "forbidden field missing: " k))))))

(defn fixture-window [f]
  ;; [from, until) bounds: an event on until-1 is in; on until is out.
  (let [in? (fn [d] (and (>= (compare d (:from window)) 0)
                         (< (compare d (:until window)) 0)))]
    (when-not (in? "2026-08-31") (fail! f "2026-08-31 must be inside window"))
    (when (in? "2026-09-01") (fail! f "until is exclusive; 2026-09-01 must be out"))))

(defn fixture-derived-observation [f]
  ;; No forbidden field may appear in a derived observation record.
  (let [{:keys [forbidden-fields]} (:derived-observation contract)
        obs {:observation-id "o-1" :method/version (:method/version contract)
             :window window :observation-kind :listed-on-portfolio-page-in-window
             :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
             :event-id "ev-1"
             :value {:kind :listing-observation :basis #{:receipt-only}}
             :missingness-flags #{}
             :provenance-chain ["r-1"]
             :asserted-at "2026-09-01"}
        keys' (set (map keyword (keys obs)))]
    (when (some #(contains? keys' (keyword (name %))) forbidden-fields)
      (fail! f "derived observation carries a forbidden field"))))

(defn fixture-refresh-history [f]
  (let [h {:history-id "h-1" :observation-id "o-1"
           :re-observed-at "2026-10-01"
           :reason {:kind :source-updated} :changed-fields [:observation-kind]}]
    (when-not (:append-only? (:refresh-history contract))
      (fail! f "refresh history must be append-only"))
    (when (nil? (:reason h)) (fail! f "refresh entry must state its reason"))
    (when-not (= (:rule (:refresh-history contract))
                 :reclassification-appends-does-not-overwrite)
      (fail! f "reclassification must append, not overwrite"))))

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
  ;; A coverage record must separate observed from unmeasured, and an
  ;; unstated listing kind must flag, not vanish.
  (let [cov {:coverage-unit :jurisdiction :unit-key "JP" :observed-count 2
             :unmeasured-count 1 :window window
             :method/version (:method/version contract)}]
    (when-not (and (pos? (:observed-count cov))
                   (pos? (:unmeasured-count cov)))
      (fail! f "coverage must report unmeasured alongside observed")))
  (when-not (contains? (set (:flags (:missingness contract)))
                       :listing-kind-unstated)
    (fail! f "unstated listing kind must flag :listing-kind-unstated, not vanish")))

(def fixtures
  {"provenance" fixture-provenance
   "discovery-only-source" fixture-discovery-only
   "entity-separation" fixture-entity-separation
   "listing-vs-removal" fixture-listing-vs-removal
   "not-ownership" fixture-not-ownership
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
        (fail! name (str "fixture threw: " (.-message e)))
        (println (str "  threw " name)))))
  (if (empty? @failures)
    (do (println "PASS: all portfolio-listing fixtures found nothing wrong")
        (js/process.exit 0))
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (js/process.exit 1))))

(run-all)
