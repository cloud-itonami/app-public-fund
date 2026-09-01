#!/usr/bin/env nbb
;; exit_observation_fixtures.cljs — deterministic offline fixtures for the
;; exit-observation contract (`capital-observation/exit-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/exit_observation_fixtures.cljs [path/to/contract.edn]

(ns exit-observation-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "exit-observation.edn")))

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
;; Two entities sharing a brand name — entity separation fixture.
(def receipts
  [(receipt "r-1" "https://example-exchange.test/ipo/listing" :official-stock-exchange-filing "en"
            "TARGET K.K. listed on the exchange")
   (receipt "r-2" "https://example-reg.test/liquidation/filing" :official-regulator "en"
            "TARGET K.K. liquidation filing recorded")])

(def entities
  [{:entity-id "e-1" :entity-type :target :name "TARGET"
    :legal-name "TARGET K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-1"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   ;; Same brand string, different type: must remain a distinct entity id.
   {:entity-id "e-2" :entity-type :fund-vehicle :name "TARGET"
    :legal-name "TARGET Fund I L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-2"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

(def events
  [{:event-id "ev-1" :event-type :ipo-listed :entity-id "e-1"
    :announced-at "2026-08-20" :asserted-at "2026-09-01"
    :consideration {:kind :unstated :as-stated-by-source? true}
    ;; reported valuation is an ESTIMATE carried as estimated — never verified
    :valuation {:kind :estimated-valuation}
    :source-receipt-id "r-1"}
   {:event-id "ev-2" :event-type :liquidation-filed :entity-id "e-1"
    :announced-at "2026-08-28" :asserted-at "2026-09-01"
    ;; consideration-not-stated: the receipt states the filing, no figure.
    :consideration {:kind :unstated :as-stated-by-source? true}
    :valuation {:kind :none}
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

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities"))))))

(defn fixture-announced-vs-completed [f]
  ;; event kinds must survive distinct — announced exit never collapsed
  ;; into a completed exit, and both kinds exist in the contract.
  (let [ets (set (:event-types contract))]
    (when-not (and (contains? ets :exit-announced)
                   (contains? ets :acquisition-completed)
                   (contains? ets :ipo-listed)
                   (contains? ets :liquidation-filed))
      (fail! f "exit event kinds incomplete"))
    (let [kinds (set (map :event-type events))]
      (when-not (contains? kinds :ipo-listed)
        (fail! f "event kind lost")))))

(defn fixture-valuation-kinds [f]
  ;; estimated-valuation-is-not-verified-valuation: an estimated
  ;; valuation is carried with kind :estimated-valuation only; the
  ;; contract's forbidden fields include current-valuation so a verified
  ;; valuation field cannot exist.
  (when-not (some #(and (= (:event-id %) "ev-1")
                        (= (-> % :valuation :kind) :estimated-valuation))
                  events)
    (fail! f "estimated valuation must be carried as estimated"))
  (let [forbidden (set (map keyword (map name (:forbidden-fields (:derived-observation contract)))))]
    (when-not (contains? forbidden :current-valuation)
      (fail! f "current-valuation must be structurally forbidden"))))

(defn fixture-window [f]
  ;; [from, until) bounds: an event on until-1 is in; on until is out.
  (let [in? (fn [d] (and (>= (compare d (:from window)) 0)
                         (< (compare d (:until window)) 0)))]
    (when-not (in? "2026-08-28") (fail! f "2026-08-28 must be inside window"))
    (when (in? "2026-09-01") (fail! f "until is exclusive; 2026-09-01 must be out"))))

(defn fixture-derived-observation [f]
  ;; No forbidden field may appear in a derived observation record.
  (let [{:keys [forbidden-fields]} (:derived-observation contract)
        obs {:observation-id "o-1" :method/version (:method/version contract)
             :window window :observation-kind :ipo-listed-in-window
             :entity-id "e-1" :event-id "ev-1"
             :value {:kind :exit-event-count :basis #{:receipt-only}}
             :missingness-flags #{}
             :provenance-chain ["r-1"]
             :asserted-at "2026-09-01"}
        keys' (set (map keyword (keys obs)))]
    (when (some #(contains? keys' (keyword (name %))) forbidden-fields)
      (fail! f "derived observation carries a forbidden field"))))

(defn fixture-refresh-history [f]
  (let [h {:history-id "h-1" :observation-id "o-1"
           :re-observed-at "2026-10-01"
           :reason {:kind :event-reclassified} :changed-fields [:event-type]}]
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
  ;; A coverage record must separate observed from unmeasured, and a
  ;; missing consideration must flag, not vanish.
  (let [cov {:coverage-unit :jurisdiction :unit-key "JP" :observed-count 2
             :unmeasured-count 1 :window window
             :method/version (:method/version contract)}]
    (when-not (and (pos? (:observed-count cov))
                   (pos? (:unmeasured-count cov)))
      (fail! f "coverage must report unmeasured alongside observed")))
  (when-not (contains? (set (:flags (:missingness contract)))
                       :consideration-not-stated)
    (fail! f "missing consideration must flag :consideration-not-stated, not vanish")))

(def fixtures
  {"provenance" fixture-provenance
   "entity-separation" fixture-entity-separation
   "announced-vs-completed" fixture-announced-vs-completed
   "valuation-kinds" fixture-valuation-kinds
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
