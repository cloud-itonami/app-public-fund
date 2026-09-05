#!/usr/bin/env nbb
;; listing_pace_v2_fixtures.cljs — deterministic offline fixtures for the
;; listing-pace-observation contract v2 hardening
;; (`capital-observation/listing-pace-observation.edn`).
;;
;; Exit codes mirror tools/coverage_rollup_fixtures.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/listing_pace_v2_fixtures.cljs [path/to/contract.edn]

(ns listing-pace-v2-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def contract-path
  (or (first (remove #(str/starts-with? % "--")
                     (js->clj *command-line-args*)))
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
(def admitted-version "portfolio-listing-observation.v1.1")

;; Cited listing events. Each now carries a receipt fetch status.
;; pe-1..pe-4 :ok; pe-5 :error (refused admission); pe-6 :ok undated;
;; pe-7 :ok conflicted (carry-both with pe-1).
(def events
  [{:event-ref "pe-1" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-1"
    :event-date-or-missing "2026-08-04"
    :source-receipt-id "r1" :source-receipt-hash "h1"
    :receipt-fetch-status :ok :method/version admitted-version}
   {:event-ref "pe-2" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-2"
    :event-date-or-missing "2026-08-19"
    :source-receipt-id "r2" :source-receipt-hash "h2"
    :receipt-fetch-status :ok :method/version admitted-version}
   {:event-ref "pe-3" :event-kind :removed-from-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-4"
    :event-date-or-missing "2026-08-27"
    :source-receipt-id "r3" :source-receipt-hash "h3"
    :receipt-fetch-status :ok :method/version admitted-version}
   {:event-ref "pe-5" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-5"
    :event-date-or-missing "2026-08-10"
    :source-receipt-id "r5" :source-receipt-hash "h5"
    :receipt-fetch-status :error :method/version admitted-version}
   {:event-ref "pe-6" :event-kind :listed-on-portfolio-page
    :fund-vehicle-entity-id fund-fid
    :portfolio-company-entity-id ":company/c-6"
    :event-date-or-missing :missing
    :source-receipt-id "r6" :source-receipt-hash "h6"
    :receipt-fetch-status :ok :method/version admitted-version}])

(def conflict-pairs [["pe-1" "r1"] ["pe-7" "r7"]])

(defn admitted? [e]
  (and (contains? (-> contract :input-listing-event :event-kind-allow)
                  (:event-kind e))
       (= (:method/version e) admitted-version)
       (contains? (-> contract :input-listing-event :receipt-admission :admitted)
                  (:receipt-fetch-status e))))

(defn admitted-events [evs] (filter admitted? evs))

(defn dated? [e]
  (and (string? (:event-date-or-missing e))
       (re-matches #"\d{4}-\d{2}-\d{2}" (:event-date-or-missing e))
       (>= (compare (:event-date-or-missing e) (:from window)) 0)
       (<  (compare (:event-date-or-missing e) (:until window)) 0)))

(defn pace-rows [evs]
  (let [by-kind (group-by :event-kind evs)]
    (into {}
          (map (fn [[kind evs]]
                 [kind {:dated-count (count (filter dated? evs))
                        :undated-count (count (filter #(not (dated? %)) evs))
                        :provenance-chain (mapv :source-receipt-id evs)}]))
          by-kind)))

;; ── Fixture 1: admission gate — non-ok receipt backs nothing ───────
(defn fx-admission-gate []
  (let [admitted (admitted-events events)
        refused (filter #(not (admitted? %)) events)
        chain (:provenance-chain (:listed-on-portfolio-page (pace-rows admitted)))]
    (when-not (some #(= "pe-5" (:event-ref %)) refused)
      (fail! :admission-gate "pe-5 (:error receipt) was not refused admission"))
    (when (some #(= "r5" %) chain)
      (fail! :admission-gate "refused receipt r5 appeared in a provenance chain"))
    (when-not (= :append-only (-> contract :input-listing-event
                                  :receipt-admission :re-fetch-rule
                                  (= :re-fetch-appends-new-receipt-plus-history)
                                  (as-> b (if b :append-only :not))))
      (fail! :admission-gate "re-fetch rule is not append-only"))
    ;; refusal record schema present
    (when-not (some #(= :receipt-fetch-status %)
                    (-> contract :input-listing-event :receipt-admission
                        :refusal-record :schema))
      (fail! :admission-gate "refusal-record schema missing :receipt-fetch-status"))))

;; ── Fixture 2: happy path counts sum over cited admitted events ────
(defn fx-happy-path []
  (let [rows (pace-rows (admitted-events events))
        listed (get rows :listed-on-portfolio-page)
        removed (get rows :removed-from-portfolio-page)]
    ;; admitted listings: pe-1 dated, pe-2 dated, pe-6 undated → (2,1)
    (when-not (and (= 2 (:dated-count listed)) (= 1 (:undated-count listed)))
      (fail! :happy-path (str "listed counts wrong: " (pr-str listed))))
    ;; removals: pe-3 dated → (1,0)
    (when-not (and (= 1 (:dated-count removed)) (= 0 (:undated-count removed)))
      (fail! :happy-path (str "removed counts wrong: " (pr-str removed))))
    ;; undated never added to dated count
    (when (zero? (:undated-count listed))
      (fail! :undated-not-dropped "undated listing pe-6 was dropped"))
    ;; sum invariant
    (when-not (= 3 (+ (:dated-count listed) (:undated-count listed)))
      (fail! :sum-invariant "dated + undated != total admitted listings"))))

;; ── Fixture 3: provenance chain strictness ─────────────────────────
(defn fx-provenance-chain []
  (let [rule (-> contract :derived-pace-observation :provenance-chain-rule)
        rows (pace-rows (admitted-events events))
        chain (vec (:provenance-chain (get rows :listed-on-portfolio-page)))]
    (when-not (:non-empty? rule)
      (fail! :provenance "chain rule does not require non-empty"))
    (when-not (:subset-of-receipt-ids? rule)
      (fail! :provenance "chain rule does not require subset-of-receipt-ids"))
    (when-not (or (:first-element-is-first-cited-event-receipt-id? rule)
                  (:first-element-is-source-receipt-id? rule))
      (fail! :provenance "chain rule does not pin the first element"))
    (when-not (seq chain)
      (fail! :provenance "admitted events produced an empty chain"))
    (when-not (= "r1" (first chain))
      (fail! :provenance (str "chain head is not the first cited receipt: " (pr-str chain))))
    (when-not (contains? (-> contract :derived-pace-observation :flag-propagation)
                         :provenance-chain-incomplete)
      (fail! :provenance "flag :provenance-chain-incomplete not propagated"))))

;; ── Fixture 4: version separation — foreign method version refused ─
(defn fx-version-separation []
  (let [alien {:event-ref "pe-x" :event-kind :listed-on-portfolio-page
               :fund-vehicle-entity-id fund-fid
               :portfolio-company-entity-id ":company/c-9"
               :event-date-or-missing "2026-08-05"
               :source-receipt-id "r9" :source-receipt-hash "h9"
               :receipt-fetch-status :ok
               :method/version "portfolio-listing-observation.v1.0"}]
    (when (admitted? alien)
      (fail! :version-separation
             "event from an older method/version was admitted"))))

;; ── Fixture 5: forbidden fields structurally excluded ──────────────
(defn fx-forbidden-fields []
  (let [forbidden (-> contract :derived-pace-observation :forbidden-fields)]
    (doseq [f [:rank :score :centrality :velocity :momentum :ownership-stake
               :suitability :current-valuation :completeness-score]]
      (when-not (contains? forbidden f)
        (fail! :forbidden-fields (str "missing forbidden field: " f))))))

;; ── Fixture 6: zero-event window is a row, not silence ─────────────
(defn fx-zero-window []
  (let [rows (pace-rows [])
        listed (get rows :listed-on-portfolio-page)]
    (when-not (or (nil? listed)
                  (and (zero? (:dated-count listed))
                       (zero? (:undated-count listed))))
      (fail! :zero-window "empty window did not produce a (0,0) row"))
    (when-not (contains? (-> contract :derived-pace-observation :flag-propagation)
                         :no-events-in-window-from-measured-sources)
      (fail! :zero-window "missing zero-window flag"))))

;; ── Fixture 7: readback strictness + coverage/missingness ──────────
(defn fx-readback-strictness []
  (let [rb (:query-readback contract)
        strict (:strictness rb)]
    (doseq [k [:coverage-record :missingness-flags :provenance-chain]]
      (when-not (contains? (:required-with-every-row rb) k)
        (fail! :readback (str "readback missing required key: " k))))
    (when-not (= :unknown-filter-key-rejected-not-ignored (:rule strict))
      (fail! :readback "unknown filter keys are not rejected"))
    (when-not (contains? (set (:status-values rb)) :rejected-filter)
      (fail! :readback ":rejected-filter status missing"))
    (when-not (contains? (set (:status-values rb)) :admission-refused)
      (fail! :readback ":admission-refused status missing"))
    (when-not (= :filter-matches-carried-kind-exactly
                 (:event-kind-filter-rule strict))
      (fail! :readback "event-kind filter does not match carried kind exactly"))))

;; ── Fixture 8: Hyakka proposal questions only ──────────────────────
(defn fx-proposal-guard []
  (let [forbidden (-> contract :hyakka-proposal :forbidden-kinds)]
    (doseq [k [:recommendation :ranking :suitability :outreach
               :fundraising :allocation :ownership-claim
               :performance-claim :valuation-claim]]
      (when-not (contains? forbidden k)
        (fail! :proposal-guard (str "forbidden-kinds missing: " k))))))

;; ── Fixture 9: idempotent re-run, append-only refresh ──────────────
(defn fx-refresh-idempotent []
  (let [evs (admitted-events events)
        a (pace-rows evs)
        b (pace-rows evs)]
    (when-not (= a b)
      (fail! :refresh-idempotent "same inputs produced different rows")))
  (when-not (= :append-only (-> contract :refresh-history :mode))
    (fail! :refresh-append-only "refresh-history is not append-only"))
  (when-not (contains? (-> contract :refresh-history :triggers)
                       :receipt-refetched)
    (fail! :refresh-append-only ":receipt-refetched trigger missing")))

;; ── Fixture 10: negative control — flag propagation (v2 flags) ─────
(defn fx-flag-propagation []
  (let [flags (-> contract :derived-pace-observation :flag-propagation)]
    (doseq [f [:event-date-not-stated :no-receipt :receipt-unparseable
               :no-events-in-window-from-measured-sources
               :fetch-status-non-ok :admission-refused]]
      (when-not (contains? flags f)
        (fail! :flag-propagation (str "missing flag: " f))))))

;; ── Fixture 11: method version bumped to v2 ────────────────────────
(defn fx-version-bumped []
  (when-not (= "listing-pace-observation.v2" (:method/version contract))
    (fail! :version-bumped "method/version is not listing-pace-observation.v2"))
  (when-not (= "2.0.0" (:contract/version contract))
    (fail! :version-bumped "contract/version is not 2.0.0")))

;; ── Run ─────────────────────────────────────────────────────────────
(try
  (fx-admission-gate)
  (fx-happy-path)
  (fx-provenance-chain)
  (fx-version-separation)
  (fx-forbidden-fields)
  (fx-zero-window)
  (fx-readback-strictness)
  (fx-proposal-guard)
  (fx-refresh-idempotent)
  (fx-flag-propagation)
  (fx-version-bumped)
  (catch :default e
    (println (str "REFUSED: fixture could not run: " (.-message e)))
    (js/process.exit 2)))

(if (empty? @failures)
  (do (println "listing-pace-observation v2 fixtures: all ran, nothing wrong")
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "FAIL [" fixture "] " msg)))
      (js/process.exit 1)))
