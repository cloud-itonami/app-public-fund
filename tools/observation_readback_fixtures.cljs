#!/usr/bin/env nbb
;; observation_readback_fixtures.cljs — deterministic offline fixtures for
;; the observation-query-readback contract
;; (`capital-observation/observation-query-readback.edn`).
;;
;; Exit codes:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Fixtures cover: deterministic pagination ordering, cursor stability
;; across identical requests, empty-page coverage carry, unknown-key
;; rejection, one-method-version-per-page separation, forbidden-field
;; absence in page responses, and unavailable-source → :unmeasured (no
;; cache rebuild).

(ns observation-readback-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join "." "capital-observation" "observation-query-readback.edn")))

(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

;; ── Deterministic in-memory readback engine (the shape under test) ──
;; Sorted rows keyed by observation-id; each row carries its own
;; method/version. The engine implements exactly what the contract
;; prescribes; fixtures assert the prescribed properties hold.

(def rows
  [{:observation-id "obs-001" :method/version "fund-close-observation.v1"
    :window "2026-07" :kind :fund-close-in-window}
   {:observation-id "obs-002" :method/version "fund-close-observation.v1"
    :window "2026-07" :kind :fund-close-in-window}
   {:observation-id "obs-003" :method/version "fund-close-observation.v2"
    :window "2026-07" :kind :fund-close-in-window}
   {:observation-id "obs-005" :method/version "fund-close-observation.v1"
    :window "2026-07" :kind :fund-close-in-window}])

(def coverage {:coverage-unit :jurisdiction :unit-key "JP"
               :observed-count 5 :unmeasured-count 1
               :window "2026-07" :method/version "fund-close-observation.v1"})
(def missingness #{:no-receipt})

(defn sort-key [r] (:observation-id r))

(defn page-of [rows request]
  (let [ps (get-in request [:page :page-size] 50)
        after (get-in request [:page :cursor])
        ordered (sort-by sort-key rows)
        eligible (cond->> ordered
                   true (filter #(= (:method/version %) (:method/version request)))
                   true (filter #(= (:window %) (:window request)))
                   true (filter #(= (:kind %) (:observation-kind request)))
                   after (filter #(pos? (compare (:observation-id %) after))))
        page (take ps eligible)
        more? (> (count eligible) ps)
        next-cursor (when more? (:observation-id (last page)))]
    {:query-id (:query-id request)
     :status :ok
     :page {:cursor-next next-cursor
            :page-size (count page)
            :has-more more?}
     :observations (mapv :observation-id page)
     :coverage-record-ref coverage
     :missingness-flags missingness
     :empty-reason nil}))

;; ── Fixtures ────────────────────────────────────────────────────────

;; 1. Deterministic ordering: page-1 then page-2 partitions all rows in
;;    observation-id order, no row skipped or duplicated.
(def request-1 {:query-id "q-fixture-1" :observation-kind :fund-close-in-window
                :method/version "fund-close-observation.v1" :window "2026-07"
                :page {:page-size 2}})
(def p1 (page-of rows request-1))
(when (not= (:observations p1) ["obs-001" "obs-002"])
  (fail! :pagination-ordering (str "page 1 wrong: " (:observations p1))))
(def p2 (page-of rows (assoc-in request-1 [:page :cursor]
                                (get-in p1 [:page :cursor-next]))))
(when (not= (:observations p2) ["obs-005"])
  (fail! :cursor-carry-over
         (str "page 2 must continue v1 ordering and exclude v2 row obs-003: "
              (:observations p2))))
(when (get-in p2 [:page :has-more])
  (fail! :has-more-false-at-end "cursor-next page must be terminal"))

;; 2. Cursor stability: two identical requests return identical pages.
(def p1-again (page-of rows request-1))
(when (not= p1 p1-again)
  (fail! :identical-request-twice "same request returned different pages"))

;; 3. Version separation: one page never mixes method versions.
(def mixed (page-of (assoc rows 0 (assoc (rows 0) :method/version "fund-close-observation.v9"))
                    request-1))
(when (some #(= "obs-001" %) (:observations mixed))
  (fail! :version-mixing "a different version's row must not enter the page"))

;; 4. Unknown keys rejected, not ignored.
(def unknown-key-request (assoc request-1 :sort-by :rank))
(when-not (contains? (set (:schema (:page-request contract))) :page)
  (fail! :contract-shape "page-request missing"))
(defn reject-unknown-keys? [request schema]
  ;; unknown top-level key outside the declared schema → :bad-request
  (let [allowed #{:query-id :observation-kind :method/version :window :page :filter}]
    (if (every? allowed (keys request)) false true)))
(when-not (reject-unknown-keys? unknown-key-request nil)
  (fail! :unknown-key-rejection "unknown key :sort-by must be rejected"))

;; 5. Every response carries coverage + missingness (incl. empty).
(def empty-request {:query-id "q-fixture-5" :observation-kind :fund-closed-out
                    :method/version "fund-close-observation.v1" :window "2026-07"
                    :page {:page-size 50}})
(def empty-page (page-of rows empty-request))
(when-not (and (contains? empty-page :coverage-record-ref)
               (contains? empty-page :missingness-flags))
  (fail! :empty-page-coverage "empty page must still carry coverage + missingness"))

;; 6. Forbidden fields structurally absent from the response schema.
(def forbidden (:forbidden-fields (:page-response contract)))
(when-not (and (contains? forbidden :rank) (contains? forbidden :score)
               (contains? forbidden :centrality) (contains? forbidden :ownership-stake)
               (contains? forbidden :suitability) (contains? forbidden :current-valuation))
  (fail! :forbidden-fields "forbidden-field set incomplete"))
(def page-keys (set (keys p1)))
(when (some page-keys forbidden)
  (fail! :forbidden-field-present "a forbidden field appeared in a page response"))

;; 7. Unavailable source → :unmeasured, never a cache rebuild.
(when-not (and (= :serving-projection-only (get-in contract [:retention :rule]))
               (false? (get-in contract [:retention :rebuild-from-cache?]))
               (= :unmeasured (get-in contract [:retention :unavailable-source-response])))
  (fail! :no-cache-rebuild "retention rule must forbid cache rebuild"))

;; ── Report ──────────────────────────────────────────────────────────
(if (seq @failures)
  (do
    (doseq [{:keys [fixture msg]} @failures]
      (println (str "FAIL " fixture ": " msg)))
    (println (str (count @failures) " violation(s) found."))
    (js/process.exit 1))
  (do
    (println "OK: 7 readback fixtures ran clean (pagination, cursor stability,")
    (println "version separation, unknown-key rejection, empty-page coverage,")
    (println "forbidden-field absence, no-cache-rebuild).")
    (js/process.exit 0)))
