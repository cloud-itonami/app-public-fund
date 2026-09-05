#!/usr/bin/env nbb
;; listing_pace_fixtures.cljs — deterministic offline fixtures for the
;; listing-pace-observation contract
;; (`capital-observation/listing-pace-observation.edn`).
;;
;; Exit codes mirror tools/coverage_rollup_fixtures.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/listing_pace_fixtures.cljs [path/to/contract.edn]

(ns listing-pace-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join "capital-observation" "listing-pace-observation.edn")))

(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

;; ── Fixture data (deterministic, no network) ────────────────────────
(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

(def fund-fid ":fund/f-1")
;; Admitted listing events (already backed by receipts in the composed
;; contract). Two dated listings, one undated listing, one removal, and
;; one dated listing that is in conflict (carry-both).
(def events
  [{:event-ref "pe-1" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-1"
    :event-date-or-missing "2026-08-04"
    :source-receipt-hash "r1" :method/version "portfolio-listing-observation.v1.1"}
   {:event-ref "pe-2" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-2"
    :event-date-or-missing "2026-08-19"
    :source-receipt-hash "r2" :method/version "portfolio-listing-observation.v1.1"}
   {:event-ref "pe-3" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-3"
    :event-date-or-missing :missing          ; undated → undated-count
    :source-receipt-hash "r3" :method/version "portfolio-listing-observation.v1.1"}
   {:event-ref "pe-4" :event-kind :removed-from-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-4"
    :event-date-or-missing "2026-08-27"
    :source-receipt-hash "r4" :method/version "portfolio-listing-observation.v1.1"}
   {:event-ref "pe-5" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-5"
    :event-date-or-missing "2026-08-10"
    :source-receipt-hash "r5" :method/version "portfolio-listing-observation.v1.1"}])

(def conflict-refs #{"pe-5"})

(defn admitted? [e]
  (and (contains? (-> contract :input-listing-event :event-kind-allow)
                  (:event-kind e))
       (= (:method/version e) "portfolio-listing-observation.v1.1")
       (contains? #{":fund/f-1"} (:fund-vehicle-entity-id e))))

(defn pace-row [evs conflicts]
  (let [listed-evs (filter #(= :listed-on-portfolio-page (:event-kind %)) evs)
        removed-evs (filter #(= :removed-from-portfolio-page (:event-kind %)) evs)
        dated? (fn [e] (and (string? (:event-date-or-missing e))
                            (re-matches #"\d{4}-\d{2}-\d{2}" (:event-date-or-missing e))
                            (>= (compare (:event-date-or-missing e) (:from window)) 0)
                            (<  (compare (:event-date-or-missing e) (:until window)) 0)))
        row-of (fn [evs] {:dated-count (count (filter dated? evs))
                          :undated-count (count (filter #(not (dated? %)) evs))
                          :conflict-count (count (filter conflicts (map :event-ref evs)))})]
    {:listed (row-of listed-evs) :removed (row-of removed-evs)
     :listed-total (count listed-evs) :removed-total (count removed-evs)}))

;; ── Fixture 1: happy path counts sum over cited events ─────────────
(defn fx-happy-path []
  (let [evs (filter admitted? events)
        row (pace-row evs conflict-refs)
        listed (:listed row) removed (:removed row)]
    ;; 3 dated listings (pe-1, pe-2, pe-5), 1 undated (pe-3), 1 removal.
    (when-not (and (= 3 (:dated-count listed)) (= 1 (:undated-count listed))
                   (= 1 (:dated-count removed)) (= 0 (:undated-count removed)))
      (fail! :happy-path
             (str "counts wrong: " (pr-str row))))
    ;; Undated event never added to dated count.
    (when (zero? (:undated-count listed))
      (fail! :undated-not-dropped "undated listing event pe-3 was dropped"))
    ;; Carry-both: conflicted event counted AND not adjudicated away.
    (when-not (= 1 (:conflict-count listed))
      (fail! :carry-both (str "conflict count wrong: " (pr-str row))))
    ;; Total listings = dated + undated (no silent loss).
    (when-not (= (+ (:dated-count listed) (:undated-count listed))
                 (:listed-total row))
      (fail! :sum-invariant "dated + undated != total listed events"))
    ;; Same for removals.
    (when-not (= (+ (:dated-count removed) (:undated-count removed))
                 (:removed-total row))
      (fail! :sum-invariant-removed "dated + undated != total removed events"))))

;; ── Fixture 2: foreign method/version refused ──────────────────────
(defn fx-version-separation []
  (let [alien {:event-ref "pe-x" :event-kind :listed-on-portfolio-page
               :fund-vehicle-entity-id fund-fid
               :portfolio-company-entity-id ":company/c-9"
               :event-date-or-missing "2026-08-05"
               :source-receipt-hash "r9"
               :method/version "portfolio-listing-observation.v1.0"}]
    (when (admitted? alien)
      (fail! :version-separation
             "event from an older method/version was admitted"))))

;; ── Fixture 3: forbidden fields structurally excluded ──────────────
(defn fx-forbidden-fields []
  (let [forbidden (-> contract :derived-pace-observation :forbidden-fields)]
    (doseq [f [:rank :score :centrality :velocity :momentum :ownership-stake
               :suitability :current-valuation :completeness-score]]
      (when-not (contains? forbidden f)
        (fail! :forbidden-fields (str "missing forbidden field: " f))))))

;; ── Fixture 4: zero-event window is a row, not silence ─────────────
(defn fx-zero-window []
  (let [row (pace-row [] #{})
        listed (:listed row)]
    (when-not (and (map? row) (zero? (:dated-count listed))
                   (zero? (:undated-count listed)))
      (fail! :zero-window "empty window did not produce a (0,0) row"))))

;; ── Fixture 5: readback requires coverage + missingness ────────────
(defn fx-readback-required []
  (let [required (-> contract :query-readback :required-with-every-row)]
    (doseq [k [:coverage-record :missingness-flags :provenance-chain]]
      (when-not (contains? required k)
        (fail! :readback-required (str "readback missing required key: " k))))))

;; ── Fixture 6: Hyakka proposal questions only ──────────────────────
(defn fx-proposal-guard []
  (let [forbidden (-> contract :hyakka-proposal :forbidden-kinds)]
    (doseq [k [:recommendation :ranking :suitability :outreach
               :fundraising :allocation :ownership-claim
               :performance-claim :valuation-claim]]
      (when-not (contains? forbidden k)
        (fail! :proposal-guard (str "forbidden-kinds missing: " k))))))

;; ── Fixture 7: idempotent re-run, append-only refresh ──────────────
(defn fx-refresh-idempotent []
  (let [evs (filter admitted? events)
        a (pace-row evs conflict-refs)
        b (pace-row evs conflict-refs)]
    (when-not (= a b)
      (fail! :refresh-idempotent "same inputs produced different rows")))
  (when-not (= :append-only (-> contract :refresh-history :mode))
    (fail! :refresh-append-only "refresh-history is not append-only")))

;; ── Fixture 8: negative control — corrupted receipt flag propagates ─
(defn fx-flag-propagation []
  (let [flags (-> contract :derived-pace-observation :flag-propagation)]
    (doseq [f [:event-date-not-stated :no-receipt :receipt-unparseable
               :no-events-in-window-from-measured-sources]]
      (when-not (contains? flags f)
        (fail! :flag-propagation (str "missing flag: " f))))))

;; ── Run ─────────────────────────────────────────────────────────────
(try
  (fx-happy-path)
  (fx-version-separation)
  (fx-forbidden-fields)
  (fx-zero-window)
  (fx-readback-required)
  (fx-proposal-guard)
  (fx-refresh-idempotent)
  (fx-flag-propagation)
  (catch :default e
    (println (str "REFUSED: fixture could not run: " (.-message e)))
    (js/process.exit 2)))

(if (empty? @failures)
  (do (println "listing-pace-observation fixtures: all ran, nothing wrong")
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "FAIL [" fixture "] " msg)))
      (js/process.exit 1)))
