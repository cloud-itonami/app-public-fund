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
;;
;; v2 (2026-09-03): fetch-status admission gate, required event-level
;; provenance chains, and strict readback (unknown filter keys rejected,
;; consideration-kind filters match exactly). Negative fixtures construct
;; violating inputs and assert the derivation REFUSES them.

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
            "TARGET K.K. liquidation filing recorded")
   ;; v2: a receipt whose fetch FAILED. Recorded verbatim, but it refuses
   ;; admission — it can never back a derived observation.
   (assoc (receipt "r-3" "https://example-board.test/exit/announce" :official-board-record "en"
                   "TARGET K.K. exit announcement (fetch failed)")
          :fetch-status :error)])

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
    :source-receipt-id "r-1" :provenance-chain ["r-1"]}
   {:event-id "ev-2" :event-type :liquidation-filed :entity-id "e-1"
    :announced-at "2026-08-28" :asserted-at "2026-09-01"
    ;; consideration-not-stated: the receipt states the filing, no figure.
    :consideration {:kind :unstated :as-stated-by-source? true}
    :valuation {:kind :none}
    :source-receipt-id "r-2" :provenance-chain ["r-2"]}
   ;; v2: this event's ONLY receipt refused admission — deriving an
   ;; observation from it must be refused, with a refusal record.
   {:event-id "ev-3" :event-type :exit-announced :entity-id "e-1"
    :announced-at "2026-08-25" :asserted-at "2026-09-01"
    :consideration {:kind :announced-consideration
                    :currency "JPY" :as-stated-by-source? true}
    :valuation {:kind :none}
    :source-receipt-id "r-3" :provenance-chain ["r-3"]}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

;; ── v2 derivation model (deterministic, mirrors the contract) ───────
(defn admitted? [contract receipts r]
  (contains? (get-in contract [:source-receipt :admission :admitted])
             (:fetch-status r)))

(defn chain-valid?
  "v2 provenance-chain rule: non-empty, all ids exist, first element is
  the event's :source-receipt-id."
  [receipt-ids ev]
  (let [ch (:provenance-chain ev)]
    (and (vector? ch) (seq ch)
         (every? #(contains? receipt-ids %) ch)
         (= (first ch) (:source-receipt-id ev)))))

(defn derive-observations
  "Returns {:observations [...] :refusals [...]} under the v2 rules:
  an event with no admitted receipt in its chain is refused with a
  refusal record; a chain-invalid event is refused as a provenance
  violation. No third outcome exists."
  [contract receipts events]
  (let [by-id (into {} (map (juxt :receipt-id identity) receipts))
        receipt-ids (set (keys by-id))]
    (reduce
      (fn [acc ev]
        (cond
          (not (chain-valid? receipt-ids ev))
          (update acc :refusals conj {:event-id (:event-id ev)
                                      :reason :provenance-chain-invalid})
          (not (some #(admitted? contract receipts (get by-id %))
                     (:provenance-chain ev)))
          (update acc :refusals conj
                  {:receipt-id (:source-receipt-id ev)
                   :fetch-status (:fetch-status (get by-id (:source-receipt-id ev)))
                   :admission-refused-at "2026-09-01"
                   :method/version (:method/version contract)})
          :else
          (update acc :observations conj
                  {:observation-id (str "o-" (:event-id ev))
                   :method/version (:method/version contract)
                   :window window
                   :observation-kind (get {:ipo-listed :ipo-listed-in-window
                                           :liquidation-filed :liquidation-filed-in-window
                                           :exit-announced :exit-announced-in-window
                                           :acquisition-completed :acquisition-completed-in-window}
                                          (:event-type ev))
                   :entity-id (:entity-id ev) :event-id (:event-id ev)
                   :value {:kind :exit-event-count :basis #{:receipt-only}}
                   :missingness-flags (if (= :unstated (-> ev :consideration :kind))
                                        #{:consideration-not-stated} #{})
                   :provenance-chain (:provenance-chain ev)
                   :asserted-at "2026-09-01"})))
      {:observations [] :refusals []}
      events)))

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
  ;; No forbidden field may appear in a derived observation record, and
  ;; (v2) the observation carries its event's provenance-chain exactly.
  (let [{:keys [forbidden-fields]} (:derived-observation contract)
        {:keys [observations refusals]} (derive-observations contract receipts events)
        obs (some #(when (= (:event-id %) "ev-1") %) observations)]
    (when (nil? obs) (fail! f "ev-1 must derive an observation"))
    (let [keys' (set (map keyword (keys obs)))]
      (when (some #(contains? keys' (keyword (name %))) forbidden-fields)
        (fail! f "derived observation carries a forbidden field")))
    (when-not (= (:provenance-chain obs) ["r-1"])
      (fail! f "observation must carry its event's provenance-chain exactly"))
    (when-not (contains? (set (map :receipt-id refusals)) "r-3")
      (fail! f "ev-3 (admission-refused receipt r-3) must not derive an observation"))))

(defn fixture-fetch-admission [f]
  ;; v2: only :ok receipts are admitted. The refusal must produce a
  ;; refusal RECORD (not silence), and the refused receipt never backs
  ;; an observation. A re-fetch appends; it does not retro-invalidate.
  (let [{:keys [observations refusals]}
        (derive-observations contract receipts events)
        refused (some #(when (= (:receipt-id %) "r-3") %) refusals)]
    (when-not (and (= (count observations) 2)
                   (every? #(not= (:event-id %) "ev-3") observations))
      (fail! f "an admission-refused receipt backed an observation"))
    (when (nil? refused)
      (fail! f "admission refusal must produce a refusal record, not silence"))
    (when-not (and (= (:fetch-status refused) :error)
                   (= (:method/version refused) (:method/version contract)))
      (fail! f "refusal record must carry fetch-status and method/version"))
    (when-not (contains? (:status-values (:query-readback contract))
                         :admission-refused)
      (fail! f "readback must be able to answer :admission-refused"))
    (let [statuses (:fetch-status-values (:source-receipt contract))]
      (when-not (and (contains? statuses :ok) (contains? statuses :error))
        (fail! f "contract must declare the fetch-status vocabulary")))))

(defn fixture-provenance-chain [f]
  ;; v2 negative fixtures: a chain that invents a receipt id, trims the
  ;; head, or is empty must be REFUSED, never silently derived.
  (let [bad-events
        [{:event-id "ev-bad-1" :event-type :ipo-listed :entity-id "e-1"
          :announced-at "2026-08-20" :asserted-at "2026-09-01"
          :consideration {:kind :unstated :as-stated-by-source? true}
          :valuation {:kind :none}
          :source-receipt-id "r-1" :provenance-chain ["r-invented"]}
         {:event-id "ev-bad-2" :event-type :ipo-listed :entity-id "e-1"
          :announced-at "2026-08-20" :asserted-at "2026-09-01"
          :consideration {:kind :unstated :as-stated-by-source? true}
          :valuation {:kind :none}
          :source-receipt-id "r-1" :provenance-chain []}
         {:event-id "ev-bad-3" :event-type :ipo-listed :entity-id "e-1"
          :announced-at "2026-08-20" :asserted-at "2026-09-01"
          :consideration {:kind :unstated :as-stated-by-source? true}
          :valuation {:kind :none}
          :source-receipt-id "r-2" :provenance-chain ["r-1" "r-2"]}]]
    ;; ev-bad-3: head is NOT the source-receipt-id -> invalid.
    (doseq [ev bad-events]
      (let [{:keys [observations refusals]}
            (derive-observations contract receipts [ev])]
        (when-not (and (empty? observations)
                       (= 1 (count refusals))
                       (= :provenance-chain-invalid (:reason (first refusals))))
          (fail! f (str "invalid chain for " (:event-id ev)
                        " was not refused")))))
    (when-not (contains? (:invariants (:event-record contract))
                         :provenance-chain-is-required-and-verified)
      (fail! f "contract must declare the provenance-chain invariant"))))

(defn fixture-consideration-kind-readback [f]
  ;; v2: a :consideration-kind filter matches the CARRIED kind exactly —
  ;; an announced consideration is never returned under a completed
  ;; filter (announced vs completed never collapse in readback).
  (let [ev-3 (some #(when (= (:event-id %) "ev-3") %) events)
        completed? (fn [ev] (= :completed-consideration (-> ev :consideration :kind)))
        filtered (filter completed? [ev-3])]
    (when-not (empty? filtered)
      (fail! f "consideration-kind filter collapsed announced into completed"))
    (when-not (= :filter-matches-carried-kind-exactly
                 (get-in contract [:query-readback :strictness
                                   :consideration-kind-filter-rule]))
      (fail! f "contract must declare exact consideration-kind filtering"))))

(defn fixture-unknown-filter-key [f]
  ;; v2: an unknown filter key is REJECTED (:rejected-filter), never
  ;; silently ignored (which would quietly widen the query).
  (let [request {:query-id "q-2" :observation-kind :ipo-listed-in-window
                 :window window
                 :filter {:returns-greater-than 100}}
        known #{:jurisdiction :entity-type :event-type :consideration-kind}
        unknown-keys (remove #(contains? known %) (keys (:filter request)))]
    (when-not (empty? unknown-keys)
      (when-not (contains? (:status-values (:query-readback contract))
                           :rejected-filter)
        (fail! f "unknown filter key could not be rejected: no status"))
      (when-not (= :unknown-filter-key-rejected-not-ignored
                   (get-in contract [:query-readback :strictness :rule]))
        (fail! f "contract must declare unknown-filter-key rejection")))))

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
   "fetch-admission" fixture-fetch-admission
   "provenance-chain" fixture-provenance-chain
   "consideration-kind-readback" fixture-consideration-kind-readback
   "unknown-filter-key" fixture-unknown-filter-key
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
