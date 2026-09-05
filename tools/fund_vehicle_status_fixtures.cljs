#!/usr/bin/env nbb
;; fund_vehicle_status_fixtures.cljs — deterministic offline fixture
;; runner for the fund-vehicle-status-observation.v1 contract
;; (capital-observation/fund-vehicle-status-observation.edn). No network.
;;
;; Exit codes mirror the other capital-observation fixture runners:
;;   0  all fixtures ran clean
;;   1  a violation was found
;;   2  REFUSED — the contract could not be read
;;
;; Fixtures exercise specifically:
;;   * status kinds carried, never collapsed (unmapped source word is
;;     carried verbatim as :source-word-unmapped)
;;   * fetch-status admission: a non-:ok receipt backs nothing, produces
;;     a refusal record, never retro-invalidates
;;   * provenance chain required on every event and entity record
;;   * cross-source disagreement recorded, never resolved
;;   * out-of-window readback is :out-of-window, not "false"
;;   * strict readback: unknown filter key → :rejected-filter;
;;     :stated-status filter matches the carried kind exactly
;;   * forbidden fields absent from the derived-observation shape
;;   * append-only refresh history
;;
;; Run: nbb tools/fund_vehicle_status_fixtures.cljs

(ns fund-vehicle-status-fixtures
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]))

(defonce failures (atom []))

(defn chk [ctx msg ok?]
  (when-not ok?
    (swap! failures conj {:fixture (:fixture ctx) :msg msg}))
  ok?)

;; ── Load the contract ───────────────────────────────────────────────
(def contract-path "capital-observation/fund-vehicle-status-observation.edn")
(def contract
  (try
    (edn/read-string (.readFileSync fs contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

;; ── Fixture world (all synthetic, no real fund/company/manager) ─────
(def fixture-window {:from "2026-01-01" :until "2026-07-01"
                     :declared-at "2026-09-06" :timezone "UTC"})
(def later-window {:from "2026-07-01" :until "2027-01-01"
                   :declared-at "2026-09-06" :timezone "UTC"})

(def fixture-receipts
  [{:receipt-id "rcpt-s1" :source-url "https://manager.example/fund-9"
    :source-class :manager-first-party :source-language "en"
    :observed-at "2026-02-01T00:00:00Z"
    :content-hash "aa11" :fetch-status :ok}
   {:receipt-id "rcpt-s2" :source-url "https://regulator.example/filing-9"
    :source-class :official-regulator :source-language "en"
    :observed-at "2026-02-02T00:00:00Z"
    :content-hash "bb22" :fetch-status :ok}
   ;; non-ok fetch: recorded, backs nothing
   {:receipt-id "rcpt-s3"
    :source-url "https://manager.example/fund-9?mirror"
    :source-class :manager-first-party :source-language "en"
    :observed-at "2026-02-03T00:00:00Z"
    :content-hash "cc33" :fetch-status :error}
   ;; second source for the disagreement fixture
   {:receipt-id "rcpt-s4" :source-url "https://fund.example/fund-9"
    :source-class :fund-first-party :source-language "en"
    :observed-at "2026-02-04T00:00:00Z"
    :content-hash "dd44" :fetch-status :ok}])

(def fixture-entities
  [{:entity-id "fv-9" :entity-type :fund-vehicle
    :name "Fund Nine (fixture)" :legal-name "Fund Nine LP (fixture)"
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value "FIX-F009" :source-receipt-id "rcpt-s1"
    :asserted-at "2026-01-10" :observed-at "2026-02-01T00:00:00Z"
    :provenance-chain ["rcpt-s1"]}
   {:entity-id "mgmt-9" :entity-type :management-company
    :name "Fund Nine (fixture)" :legal-name "Fund Nine Management LLC (fixture)"
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value "FIX-M009" :source-receipt-id "rcpt-s1"
    :asserted-at "2026-01-10" :observed-at "2026-02-01T00:00:00Z"
    :provenance-chain ["rcpt-s1"]}])

(def fixture-events
  [{:event-id "ev-s1" :event-type :status-named :entity-id "fv-9"
    :asserted-at "2026-02-01" :observed-at "2026-02-01T00:00:00Z"
    :stated-status {:kind :closed-to-new-investment
                    :source-word "closed to new investment"}
    :source-receipt-id "rcpt-s1" :provenance-chain ["rcpt-s1"]}
   ;; a source word that does not map to any known kind
   {:event-id "ev-s2" :event-type :status-named :entity-id "fv-9"
    :asserted-at "2026-03-01" :observed-at "2026-03-01T00:00:00Z"
    :stated-status {:kind :source-word-unmapped
                    :source-word "harvesting phase"}
    :source-receipt-id "rcpt-s4" :provenance-chain ["rcpt-s4"]}
   ;; event backed by a non-ok receipt — must produce a refusal record
   {:event-id "ev-s3" :event-type :status-named :entity-id "fv-9"
    :asserted-at "2026-03-02" :observed-at "2026-03-02T00:00:00Z"
    :stated-status {:kind :investing :source-word "investing"}
    :source-receipt-id "rcpt-s3" :provenance-chain ["rcpt-s3"]}
   ;; a second, differing naming of the same entity in the same window
   ;; (disagreement fixture)
   {:event-id "ev-s4" :event-type :status-named :entity-id "fv-9"
    :asserted-at "2026-02-05" :observed-at "2026-02-05T00:00:00Z"
    :stated-status {:kind :investing :source-word "actively investing"}
    :source-receipt-id "rcpt-s4" :provenance-chain ["rcpt-s4"]}])

;; ── Contract logic under test (mirrors the contract's declared rules) ──
(defn admitted? [receipt]
  (= :ok (:fetch-status receipt)))

(defn refusal-record [event receipts]
  (let [r (some #(when (= (:receipt-id %) (:source-receipt-id event)) %)
                receipts)]
    (when (and r (not (admitted? r)))
      {:refused-event-id (:event-id event)
       :receipt-id (:receipt-id r)
       :fetch-status (:fetch-status r)
       :missingness-flag :fetch-status-non-ok
       :backs-observation? false})))

(defn derived-observations [events receipts window]
  (for [e events
        :let [r (some #(when (= (:receipt-id %) (:source-receipt-id e)) %)
                      receipts)]
        :when (and (admitted? r)
                   (>= (compare (:asserted-at e) (:from window)) 0)
                   (< (compare (:asserted-at e) (:until window)) 0))]
    {:observation-id (str "obs-" (:event-id e))
     :method/version (:method/version contract)
     :window window
     :observation-kind :fund-vehicle-status-named-in-window
     :entity-id (:entity-id e)
     :event-id (:event-id e)
     :value {:kind :status-naming-in-window :basis :receipt-only}
     :missingness-flags (if (= :source-word-unmapped
                               (get-in e [:stated-status :kind]))
                          #{:source-word-unmapped} #{})
     :provenance-chain (:provenance-chain e)
     :asserted-at (:asserted-at e)}))

(defn readback [observations events window filter-map]
  (let [known-keys (get-in contract [:query-readback :request-schema 4
                                   :keys])]
    (if-let [unknown (seq (remove known-keys (keys (or filter-map {}))))]
      {:status :rejected-filter :rejected-keys (vec unknown)}
      (let [rows (filter (fn [o]
                           (and (= (:window o) window)
                                (or (nil? (:stated-status filter-map))
                                    (let [e (some (fn [ev]
                                                    (when (= (:event-id ev)
                                                            (:event-id o))
                                                      ev))
                                                  events)]
                                      (= (:stated-status filter-map)
                                         (get-in e [:stated-status :kind]))))))
                         observations)]
        (if (empty? rows)
          {:status :unmeasured :observations []
           :missingness-flags #{:missing-is-unmeasured}}
          {:status :ok :observations (map :observation-id rows)})))))

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-status-kinds-carried-not-collapsed [f]
  (chk f "status-kind vocabulary exists"
       (contains? contract :event-record))
  (let [unmapped (some #(when (= :source-word-unmapped
                                  (get-in % [:stated-status :kind])) %)
                       fixture-events)]
    (chk f "unmapped source word must be carried verbatim"
         (and unmapped (= "harvesting phase"
                          (get-in unmapped [:stated-status :source-word])))))
  (chk f "source word must be a required schema field"
       (some #(str/includes? (str %) ":source-word")
             (get-in contract [:event-record :schema]))))

(defn fixture-fetch-status-admission [f]
  (let [ra (get contract :receipt-admission)]
    (chk f "admission rule must be :fetch-status-ok-required"
         (= (:rule ra) :fetch-status-ok-required))
    (chk f "only :ok is admitted" (= #{:ok} (:admit-when ra)))
    (chk f "refusal record required, never silence"
         (get-in ra [:else :refusal-record-required?]))
    (chk f "no retro-invalidation"
         (false? (get-in ra [:else :retro-invalidation?]))))
  (let [refusal (refusal-record
                 (some #(when (= "ev-s3" (:event-id %)) %) fixture-events)
                 fixture-receipts)]
    (chk f "non-ok receipt produces a refusal record"
         (and refusal (= :fetch-status-non-ok (:missingness-flag refusal))))
    (let [obs (derived-observations fixture-events fixture-receipts
                                    fixture-window)
          backed? (some #(when (= "obs-ev-s3" (:observation-id %)) %) obs)]
      (chk f "non-ok-backed event must produce no derived observation"
           (nil? backed?)))))

(defn fixture-provenance-chain-required [f]
  (chk f "entity record schema requires provenance-chain"
       (get-in contract [:entity-record :provenance-chain-required?]))
  (chk f "event invariants require provenance chain"
       (some #(= :provenance-chain-required-on-every-event %)
             (get-in contract [:event-record :invariants])))
  (chk f "every fixture event carries a non-empty chain"
       (every? (fn [e] (seq (:provenance-chain e))) fixture-events))
  (chk f "chain head equals the event's receipt id"
       (every? (fn [e] (= (last (:provenance-chain e))
                          (:source-receipt-id e)))
               fixture-events)))

(defn fixture-disagreement-recorded-never-resolved [f]
  (let [sd (get contract :status-disagreement)]
    (chk f "disagreement rule is record-never-resolve"
         (= (:rule sd) :record-never-resolve))
    (chk f "no winner mechanism"
         (get-in sd [:result :no-winner-mechanism]))
    (chk f "disagreement value is :unmeasured"
         (= :unmeasured (get-in sd [:result :value]))))
  ;; two allowed sources naming different statuses in the same window:
  (let [a (some #(when (= "ev-s1" (:event-id %)) %) fixture-events)
        b (some #(when (= "ev-s4" (:event-id %)) %) fixture-events)]
    (chk f "fixture really has two differing statuses for one entity"
         (and a b
              (not= (get-in a [:stated-status :kind])
                    (get-in b [:stated-status :kind]))
              (= (:entity-id a) (:entity-id b))))
    (chk f "epistemics forbid hardening a disagreement into a status"
         (or (some #(= :disagreement-never-hardens-into-a-status %)
                   (get-in contract [:status-epistemics :rules]))
             (= :disagreement-never-hardens-into-a-status
                (get-in contract [:status-disagreement :result
                                  :hardening-rule]))))))

(defn fixture-out-of-window-is-not-false [f]
  (chk f "out-of-window rule declared"
       (some #(= :status-outside-window-is-out-of-window-not-false %)
             (get-in contract [:status-epistemics :rules])))
  (let [obs (derived-observations fixture-events fixture-receipts
                                  fixture-window)
        ;; a query against a window in which nothing was observed:
        rb (readback obs fixture-events later-window nil)]
    (chk f "empty window reads :unmeasured, not zero"
         (and (= :unmeasured (:status rb)) (empty? (:observations rb))))))

(defn fixture-strict-readback [f]
  (let [obs (derived-observations fixture-events fixture-receipts
                                  fixture-window)
        rejected (readback obs fixture-events fixture-window {:bogus-key "x"})]
    (chk f "unknown filter key is rejected, not ignored"
         (= :rejected-filter (:status rejected))))
  (let [rb (get-in contract [:query-readback :rules])]
    (chk f "readback declares exact-kind filter matching"
         (some #(= :stated-status-filter-matches-carried-kind-exactly %) rb))
    (chk f "readback declares unmeasured-is-not-zero"
         (some #(= :unmeasured-is-not-zero %) rb))
    (chk f "readback always carries coverage and missingness"
         (some #(= :readback-must-carry-coverage-and-missingness %) rb))))

(defn fixture-forbidden-fields [f]
  (let [forbidden (get-in contract [:derived-observation :forbidden-fields])]
    (doseq [k [:rank :score :dry-powder :deployment-pace :tvpi :dpi
               :fund-health :actual-status :current-valuation]]
      (chk f (str "forbidden field declared: " (name k))
           (contains? forbidden k)))))

(defn fixture-refresh-history-append-only [f]
  (let [rh (get contract :refresh-history)]
    (chk f "refresh history is append-only" (get rh :append-only?))
    (chk f "amendment and retraction are history reasons"
         (and (str/includes? (str (:schema rh)) "status-amendment")
              (str/includes? (str (:schema rh)) "status-retraction")
              (str/includes? (str (:schema rh)) "receipt-refetched")))))

(defn fixture-hyakka-questions-only [f]
  (let [hp (get contract :hyakka-proposal)]
    (chk f "proposal carries a disclaimer"
         (str/includes? (str (:disclaimer hp)) "No investment advice"))
    (chk f "proposal schema carries coverage ref and missingness"
         (and (str/includes? (str (:schema hp)) "coverage-record-ref")
              (str/includes? (str (:schema hp)) "missingness-flags")))))

(defn fixture-entity-separation [f]
  (let [fv (some #(when (= :fund-vehicle (:entity-type %)) %) fixture-entities)
        mc (some #(when (= :management-company (:entity-type %)) %)
                 fixture-entities)]
    (chk f "fund vehicle and management company stay distinct under one brand"
         (and fv mc
              (not= (:entity-id fv) (:entity-id mc))
              (= (:name fv) (:name mc))
              (not= (:identifier-value fv) (:identifier-value mc))))))

(defn fixture-coverage-record [f]
  (let [m (get contract :missingness)]
    (chk f "missing-is-unmeasured" (= :missing-is-unmeasured (:rule m)))
    (chk f "status-not-stated is a flag"
         (contains? (:flags m) :status-not-stated))
    (chk f "status-disagreement is a flag"
         (contains? (:flags m) :status-disagreement))
    (chk f "coverage-record schema exists"
         (seq (get-in m [:coverage-record :schema])))))

;; ── Runner ──────────────────────────────────────────────────────────
(def fixtures
  [[:status-kinds-carried-not-collapsed fixture-status-kinds-carried-not-collapsed]
   [:fetch-status-admission fixture-fetch-status-admission]
   [:provenance-chain-required fixture-provenance-chain-required]
   [:disagreement-recorded-never-resolved fixture-disagreement-recorded-never-resolved]
   [:out-of-window-is-not-false fixture-out-of-window-is-not-false]
   [:strict-readback fixture-strict-readback]
   [:forbidden-fields fixture-forbidden-fields]
   [:refresh-history-append-only fixture-refresh-history-append-only]
   [:hyakka-questions-only fixture-hyakka-questions-only]
   [:entity-separation fixture-entity-separation]
   [:coverage-record fixture-coverage-record]])

(doseq [[name f] fixtures] (f {:fixture name}))

(if (empty? @failures)
  (do (println (str "OK: " (count fixtures)
                    " fund-vehicle-status fixtures ran clean ("
                    (:method/version contract) ")"))
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "VIOLATION [" fixture "]: " msg)))
      (println (str "FAILED: " (count @failures) " violation(s)"))
      (js/process.exit 1)))
