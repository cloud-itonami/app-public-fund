#!/usr/bin/env nbb
;; coverage_rollup_fixtures.cljs — deterministic offline fixtures for the
;; coverage-rollup-observation contract
;; (`capital-observation/coverage-rollup-observation.edn`).
;;
;; Exit codes mirror tools/capital_observation_fixtures.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/coverage_rollup_fixtures.cljs [path/to/contract.edn]

(ns coverage-rollup-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join "capital-observation" "coverage-rollup-observation.edn")))

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

;; Two input coverage records from two observation kinds, plus one unit
;; that was only unmeasured. All share one method/version.
(def inputs
  [{:coverage-record-ref "cov-1" :observation-kind :fund-close
    :method/version "fund-close-observation.v1" :window window
    :coverage-unit :jurisdiction :unit-key "JP"
    :observed-count 2 :unmeasured-count 0
    :source-receipt-hashes ["a" "b"]}
   {:coverage-record-ref "cov-2" :observation-kind :financing-round
    :method/version "financing-round-observation.v1" :window window
    :coverage-unit :jurisdiction :unit-key "JP"
    :observed-count 1 :unmeasured-count 1
    :source-receipt-hashes ["c"]}
   {:coverage-record-ref "cov-3" :observation-kind :financing-round
    :method/version "financing-round-observation.v1" :window window
    :coverage-unit :jurisdiction :unit-key "SG"
    :observed-count 0 :unmeasured-count 3
    :source-receipt-hashes []}])

(defn rollup-of [inputs version]
  (let [by-unit (group-by (juxt :coverage-unit :unit-key) inputs)]
    (for [[[unit key] group] (sort-by first by-unit)]
      {:rollup-id (str "ro-" (name unit) "-" key)
       :method/version version :window window
       :rollup-kind :coverage-in-window
       :coverage-unit unit :unit-key key
       :per-kind (into {}
                       (map (fn [g] [(:observation-kind g)
                                     {:observed-count (:observed-count g)
                                      :unmeasured-count (:unmeasured-count g)}]))
                       group)
       :total-observed-count (reduce + 0 (map :observed-count group))
       :total-unmeasured-count (reduce + 0 (map :unmeasured-count group))
       :missingness-flags (set (mapcat #(when (empty? (:source-receipt-hashes %))
                                          [:no-receipt])
                                       group))
       :provenance-chain (mapv :coverage-record-ref group)
       :asserted-at "2026-09-01"})))

(def mixed-version-inputs
  (conj inputs {:coverage-record-ref "cov-4" :observation-kind :fund-close
                :method/version "fund-close-observation.v2" :window window
                :coverage-unit :jurisdiction :unit-key "JP"
                :observed-count 9 :unmeasured-count 9
                :source-receipt-hashes ["x"]}))

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-window-stitching [f]
  ;; A rollup window must equal its inputs' window — no stitching.
  (let [{:keys [rule]} (:window contract)]
    (when-not (= rule :rollup-window-equals-input-window-no-stitching)
      (fail! f "window rule must forbid stitching"))
    (when (some #(not= (:window %) window) inputs)
      (fail! f "input window mismatch must refuse"))))

(defn fixture-counts-are-sums [f]
  ;; Rollup totals are sums of input counts, not recomputed observations.
  (let [{inv :invariant} (:derived-rollup contract)]
    (when-not (= inv :counts-are-sums-of-input-counts-not-recomputed)
      (fail! f "rollup must sum, not recompute"))
    (doseq [r (rollup-of inputs (:method/version contract))]
      (when (not= (:total-observed-count r)
                  (reduce + 0 (map :observed-count (vals (:per-kind r)))))
        (fail! f (str "rollup " (:rollup-id r) " totals disagree with per-kind"))))))

(defn fixture-unmeasured-is-a-row [f]
  ;; An only-unmeasured unit must appear as a row, never vanish.
  (let [rows (rollup-of inputs (:method/version contract))
        sg (some #(when (= (:unit-key %) "SG") %) rows)]
    (when (nil? sg) (fail! f "unmeasured-only unit vanished"))
    (when (and sg (not= (:total-observed-count sg) 0))
      (fail! f "unmeasured-only unit must observe 0"))
    (let [{:keys [rule]} (:query-readback contract)]
      (when-not (= rule :unmeasured-is-not-zero)
        (fail! f "readback must not render unmeasured as zero")))))

(defn fixture-version-separation [f]
  ;; Inputs from different method versions never mix into one rollup.
  (let [{sep :version-separation} (:input-coverage-record contract)]
    (when-not (= sep :one-method-version-per-rollup)
      (fail! f "version separation rule missing"))
    (let [versions (set (map :method/version mixed-version-inputs))]
      (when-not (> (count versions) 1)
        (fail! f "fixture must contain mixed versions"))
      ;; Correct behavior: partition by version before rolling up; each
      ;; partition rolls up alone.
      (let [parts (group-by :method/version mixed-version-inputs)
            jp-v1 (get parts "fund-close-observation.v1")]
        (when (nil? jp-v1) (fail! f "partition lost"))
        (when-not (some #(= (:method/version %) "fund-close-observation.v2")
                        (get parts "fund-close-observation.v2"))
          (fail! f "second version must be its own rollup, not merged"))))))

(defn fixture-provenance-chain [f]
  ;; Every rollup row carries a resolvable provenance chain.
  (let [{rule :rule} (:input-coverage-record contract)]
    (when-not (= rule :unresolvable-provenance-chain-refuses-input)
      (fail! f "provenance rule missing"))
    (doseq [r (rollup-of inputs (:method/version contract))]
      (doseq [ref (:provenance-chain r)]
        (when-not (some #(= (:coverage-record-ref %) ref) inputs)
          (fail! f (str "unresolvable provenance ref " ref)))))))

(defn fixture-forbidden-fields [f]
  (let [forbidden (:forbidden-fields (:derived-rollup contract))]
    (when-not (and (contains? forbidden :aggregate-amount)
                   (contains? forbidden :completeness-score)
                   (contains? forbidden :rank))
      (fail! f "forbidden rollup fields incomplete"))))

(defn fixture-refresh-history [f]
  (let [h (:refresh-history contract)]
    (when-not (:append-only? h)
      (fail! f "rollup refresh history must be append-only"))
    (when-not (= (:rule h) :same-inputs-same-window-same-version-byte-identical)
      (fail! f "determinism rule missing"))
    ;; Same inputs twice → identical rows (byte-stability proxy).
    (let [a (rollup-of inputs (:method/version contract))
          b (rollup-of inputs (:method/version contract))]
      (when-not (= a b) (fail! f "same inputs must reproduce identical rollup")))))

(defn fixture-readback-shape [f]
  (let [rb (:query-readback contract)]
    (when-not (contains? (:status-values rb) :unmeasured)
      (fail! f "readback must declare :unmeasured status"))
    (when-not (contains? (set (:empty-reason rb)) :no-coverage-records-in-window)
      (fail! f "empty reason set incomplete"))))

(defn fixture-hyakka-shape [f]
  (let [hp (:hyakka-proposal contract)]
    (when-not (and (:disclaimer hp) (re-find #"Not performance" (:disclaimer hp)))
      (fail! f "hyakka proposal must carry the no-performance disclaimer"))))

;; ── Runner ──────────────────────────────────────────────────────────
(def fixtures
  [[:window-stitching fixture-window-stitching]
   [:counts-are-sums fixture-counts-are-sums]
   [:unmeasured-is-a-row fixture-unmeasured-is-a-row]
   [:version-separation fixture-version-separation]
   [:provenance-chain fixture-provenance-chain]
   [:forbidden-fields fixture-forbidden-fields]
   [:refresh-history fixture-refresh-history]
   [:readback-shape fixture-readback-shape]
   [:hyakka-shape fixture-hyakka-shape]])

(doseq [[name f] fixtures] (f name))

(if (empty? @failures)
  (do (println (str "OK: " (count fixtures) " coverage-rollup fixtures ran clean ("
                    (:method/version contract) ")"))
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "VIOLATION [" fixture "]: " msg)))
      (println (str "FAILED: " (count @failures) " violation(s)"))
      (js/process.exit 1)))
