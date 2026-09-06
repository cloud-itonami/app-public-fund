#!/usr/bin/env nbb
;; professional_role_observation_fixtures.cljs — deterministic offline
;; fixture runner for the professional-role-observation.v2 contract
;; (capital-observation/professional-role-observation.edn). No network.
;;
;; Exit codes mirror the other capital-observation fixture runners:
;;   0  all fixtures ran clean
;;   1  a violation was found
;;   2  REFUSED — the contract could not be read
;;
;; Fixtures exercise specifically:
;;   * the role is a source statement, never a person profile
;;     (never-a-person-identity; a professional is referenced only by a
;;     public registration id; a person name is a forbidden field)
;;   * fetch-status admission: a non-:ok receipt backs nothing,
;;     produces a refusal record, never retro-invalidates
;;   * provenance chain required on every event
;;   * cross-source disagreement recorded, never resolved; a conflict
;;     never hardens into a role or a fitness claim
;;   * out-of-window readback is :unmeasured/:out-of-window, not false
;;   * strict readback: :rejected-filter is a first-class status-value;
;;     :role-unstated never returned under a role-kind filter
;;   * forbidden personal fields absent from the derived-observation shape
;;   * append-only refresh history; role change appends, never overwrites
;;   * coverage record and Hyakka questions-only proposal present
;;
;; Run: nbb tools/professional_role_observation_fixtures.cljs

(ns professional-role-observation-fixtures
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defonce failures (atom []))

(defn chk [ctx msg ok?]
  (when-not ok?
    (swap! failures conj {:fixture (:fixture ctx) :msg msg}))
  ok?)

;; ── Load the contract ───────────────────────────────────────────────
(def contract-path "capital-observation/professional-role-observation.edn")
(def contract
  (try
    (edn/read-string (.readFileSync fs contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

;; ── Fixture world (all synthetic) ───────────────────────────────────
(def fixture-window {:from "2026-01-01" :until "2026-07-01"
                     :declared-at "2026-09-06" :timezone "UTC"})
(def later-window {:from "2026-07-01" :until "2027-01-01"
                   :declared-at "2026-09-06" :timezone "UTC"})

(def fixture-receipts
  [{:receipt-id "rcpt-pr1"
    :source-url "https://registry.example/professional/reg-1"
    :source-class :official-regulator :source-language "en"
    :observed-at "2026-02-01T00:00:00Z"
    :content-hash "aa71" :fetch-status :ok}
   {:receipt-id "rcpt-pr2"
    :source-url "https://fund.example/fund-9"
    :source-class :fund-first-party :source-language "en"
    :observed-at "2026-02-02T00:00:00Z"
    :content-hash "bb82" :fetch-status :ok}
   ;; non-ok fetch: recorded, backs nothing
   {:receipt-id "rcpt-pr3"
    :source-url "https://registry.example/professional/reg-1?mirror"
    :source-class :official-regulator :source-language "en"
    :observed-at "2026-02-03T00:00:00Z"
    :content-hash "cc93" :fetch-status :error}
   ;; second source for the disagreement fixture
   {:receipt-id "rcpt-pr4"
    :source-url "https://manager.example/fund-9"
    :source-class :manager-first-party :source-language "en"
    :observed-at "2026-02-04T00:00:00Z"
    :content-hash "dd14" :fetch-status :ok}
   {; professional's own first-party registration statement
    :receipt-id "rcpt-pr5"
    :source-url "https://professional.example/reg-1"
    :source-class :professional-first-party :source-language "en"
    :observed-at "2026-02-05T00:00:00Z"
    :content-hash "ee25" :fetch-status :ok}])

(def fixture-entities
  [{:entity-id "role-fm-1" :entity-type :professional-role
    :name "fund-manager role (fixture)" :legal-name nil
    :jurisdiction :united-states :identifier-class :professional-role-id
    :identifier-value "ROLE-FM-1" :source-receipt-id "rcpt-pr1"
    :asserted-at "2026-01-05" :observed-at "2026-02-01T00:00:00Z"
    :provenance-chain ["rcpt-pr1"]}
   {:entity-id "org-fund9" :entity-type :organization
    :name "Fund Nine (fixture)" :legal-name "Fund Nine GP LLC (fixture)"
    :jurisdiction :united-states :identifier-class :official-registry-id
    :identifier-value "FIX-O009" :source-receipt-id "rcpt-pr1"
    :asserted-at "2026-01-05" :observed-at "2026-02-01T00:00:00Z"
    :provenance-chain ["rcpt-pr1"]}
   ;; the professional is referenced only by a public registration id
   {:entity-id "prof-1" :entity-type :professional
    :name nil :legal-name nil
    :jurisdiction :united-states :identifier-class :crd
    :identifier-value "FIX-CRD-00001" :source-receipt-id "rcpt-pr1"
    :asserted-at "2026-01-05" :observed-at "2026-02-01T00:00:00Z"
    :provenance-chain ["rcpt-pr1"]}])

(def fixture-events
  [{:event-id "ev-pr1" :event-type :role-named
    :professional-role-entity-id "role-fm-1"
    :organization-entity-id "org-fund9"
    :role {:kind :fund-manager :as-stated-by-source? true}
    :role-holder-registration-id "FIX-CRD-00001"
    :asserted-at "2026-02-01" :observed-at "2026-02-01T00:00:00Z"
    :source-receipt-id "rcpt-pr1" :provenance-chain ["rcpt-pr1"]}
   ;; a role-unstated naming (carried as such, never guessed)
   {:event-id "ev-pr2" :event-type :role-named
    :professional-role-entity-id "role-fm-1"
    :organization-entity-id "org-fund9"
    :role {:kind :role-unstated :as-stated-by-source? true}
    :role-holder-registration-id nil
    :asserted-at "2026-03-01" :observed-at "2026-03-01T00:00:00Z"
    :source-receipt-id "rcpt-pr2" :provenance-chain ["rcpt-pr2"]}
   ;; event backed by a non-ok receipt — must produce a refusal record
   {:event-id "ev-pr3" :event-type :role-named
    :professional-role-entity-id "role-fm-1"
    :organization-entity-id "org-fund9"
    :role {:kind :fund-manager :as-stated-by-source? true}
    :role-holder-registration-id "FIX-CRD-00001"
    :asserted-at "2026-03-02" :observed-at "2026-03-02T00:00:00Z"
    :source-receipt-id "rcpt-pr3" :provenance-chain ["rcpt-pr3"]}
   ;; differing role in the same window (disagreement fixture)
   {:event-id "ev-pr4" :event-type :role-named
    :professional-role-entity-id "role-fm-1"
    :organization-entity-id "org-fund9"
    :role {:kind :general-partner :as-stated-by-source? true}
    :role-holder-registration-id "FIX-CRD-00001"
    :asserted-at "2026-02-05" :observed-at "2026-02-05T00:00:00Z"
    :source-receipt-id "rcpt-pr4" :provenance-chain ["rcpt-pr4"]}
   ;; professional first-party registration reference
   {:event-id "ev-pr5" :event-type :role-holder-registration-referenced
    :professional-role-entity-id "role-fm-1"
    :organization-entity-id "org-fund9"
    :role {:kind :fund-manager :as-stated-by-source? true}
    :role-holder-registration-id "FIX-CRD-00001"
    :asserted-at "2026-02-06" :observed-at "2026-02-06T00:00:00Z"
    :source-receipt-id "rcpt-pr5" :provenance-chain ["rcpt-pr5"]}])

;; ── Contract logic under test ───────────────────────────────────────
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
     :observation-kind (if (= :role-holder-registration-referenced
                              (:event-type e))
                         :registration-referenced-in-window
                         :professional-role-stated-in-window)
     :professional-role-entity-id (:professional-role-entity-id e)
     :organization-entity-id (:organization-entity-id e)
     :event-id (:event-id e)
     :value {:kind :role-stated-in-window :basis :receipt-only}
     :missingness-flags (if (= :role-unstated (get-in e [:role :kind]))
                          #{:role-unstated}
                          #{})
     :provenance-chain (:provenance-chain e)
     :asserted-at (:asserted-at e)}))

(defn readback [observations events window filter-map]
  (let [known-keys (get-in contract [:query-readback :request-schema 4
                                     :keys])]
    (if-let [unknown (seq (remove known-keys (keys (or filter-map {}))))]
      {:status :rejected-filter :rejected-keys (vec unknown)}
      (let [rows (filter (fn [o]
                           (and (= (:window o) window)
                                (or (nil? (:role-kind filter-map))
                                    (let [e (some (fn [ev]
                                                    (when (= (:event-id ev)
                                                             (:event-id o))
                                                      ev))
                                                  events)
                                          k (get-in e [:role :kind])]
                                      ;; exact carried-role match; unstated
                                      ;; never matches a specific role filter
                                      (and (= k (:role-kind filter-map))
                                           (not= :role-unstated k))))))
                         observations)]
        (if (empty? rows)
          {:status :unmeasured :observations []
           :missingness-flags #{:missing-is-unmeasured}}
          {:status :ok :observations (map :observation-id rows)})))))

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-role-is-not-a-person [f]
  (chk f "never-a-person-identity invariant declared"
       (some #(= :never-a-person-identity %)
             (get-in contract [:event-record :invariants])))
  (chk f "public-professional-data-only invariant declared"
       (some #(= :public-professional-data-only %)
             (get-in contract [:event-record :invariants])))
  (chk f "privacy boundary declared"
       (contains? (get-in contract [:professional-role-epistemics :rules])
                  :role-holder-referenced-by-public-registration-only))
  (doseq [k [:person-name :person-identity :contact-data :home-address
             :personal-wealth :family-data :sensitive-trait-inference
             :reputation-rank]]
    (chk f (str "forbidden personal field declared: " (name k))
         (contains? (get-in contract [:derived-observation :forbidden-fields])
                    k)))
  (chk f "fixture professional entity carries no person name"
       (nil? (:name (some #(when (= "prof-1" (:entity-id %)) %)
                          fixture-entities))))
  (chk f "fixture professional referenced by a public crd id"
       (= "FIX-CRD-00001"
          (get-in (some #(when (= "prof-1" (:entity-id %)) %)
                        fixture-entities)
                  [:identifier-value])))
  (chk f "crd is the fixture professional's identifier class"
       (= :crd
          (get-in (some #(when (= "prof-1" (:entity-id %)) %)
                        fixture-entities)
                  [:identifier-class]))))

(defn fixture-role-is-not-personal-profiling [f]
  (doseq [k [:role-naming-is-not-personal-profiling
             :role-is-not-fitness-or-suitability
             :role-is-not-employment-fact
             :professional-role-organization-and-vehicle-are-distinct]]
    (chk f (str "event invariant declared: " (name k))
         (some #(= k %) (get-in contract [:event-record :invariants]))))
  (doseq [k [:stamped-role-is-not-fitness-or-suitability
             :never-a-person-identity
             :stamped-role-is-not-employment-fact]]
    (chk f (str "epistemic rule declared: " (name k))
         (contains? (get-in contract [:professional-role-epistemics :rules]) k)))
  (let [ds (str (:disclaimer (get contract :hyakka-proposal)))]
    (chk f "hyakka proposal carries a no-investment-advice disclaimer"
         (and (str/includes? ds "No")
              (str/includes? ds "investment advice")))))

(defn fixture-fetch-status-admission [f]
  (let [ra (get contract :receipt-admission)]
    (chk f "admission rule must be :fetch-status-ok-required"
         (= :fetch-status-ok-required (:rule ra)))
    (chk f "only :ok is admitted via canonical :admit-when"
         (= #{:ok} (:admit-when ra)))
    (chk f "refusal record required, never silence"
         (get-in ra [:else :refusal-record-required?]))
    (chk f "no retro-invalidation"
         (false? (get-in ra [:else :retro-invalidation?]))))
  (let [refusal (refusal-record
                 (some #(when (= "ev-pr3" (:event-id %)) %) fixture-events)
                 fixture-receipts)]
    (chk f "non-ok receipt produces a refusal record"
         (and refusal (= :fetch-status-non-ok (:missingness-flag refusal))))
    (let [obs (derived-observations fixture-events fixture-receipts
                                    fixture-window)
          backed? (some #(when (= "obs-ev-pr3" (:observation-id %)) %) obs)]
      (chk f "non-ok-backed event must produce no derived observation"
           (nil? backed?)))))

(defn fixture-provenance-chain-required [f]
  (chk f "event-level provenance required by contract"
       (true? (get-in contract [:event-provenance :required?])))
  (chk f "entity record carries provenance chain in event shape"
       (str/includes? (str (get-in contract [:event-record :schema]))
                      ":provenance-chain"))
  (chk f "every fixture event carries a non-empty chain"
       (every? (fn [e] (seq (:provenance-chain e))) fixture-events))
  (chk f "chain head equals the event's receipt id"
       (every? (fn [e] (= (last (:provenance-chain e))
                          (:source-receipt-id e)))
               fixture-events)))

(defn fixture-unstated-role-carried-not-collapsed [f]
  (chk f "role-unstated is a declared role kind"
       (str/includes? (str (get-in contract [:event-record :schema]))
                      ":role-unstated"))
  (let [unstated (some #(when (= "ev-pr2" (:event-id %)) %) fixture-events)]
    (chk f "unstated role carried verbatim, never collapsed"
         (and unstated (= :role-unstated (get-in unstated [:role :kind]))))))

(defn fixture-disagreement-recorded-never-resolved [f]
  (let [co (get contract :conflict-observation)]
    (chk f "resolution is carry-both-never-resolve"
         (= :carry-both-never-resolve (:resolution co)))
    (chk f "no winner mechanism"
         (true? (get co :no-winner-mechanism)))
    (chk f "derived value is unmeasured"
         (= :unmeasured (:derived-value co)))
    (chk f "conflict never hardens into a role"
         (some #(= :disagreement-never-hardens-into-a-role %)
               (:invariants co))))
  (let [a (some #(when (= "ev-pr1" (:event-id %)) %) fixture-events)
        b (some #(when (= "ev-pr4" (:event-id %)) %) fixture-events)]
    (chk f "fixture has two differing role kinds for one role/org"
         (and a b
              (not= (get-in a [:role :kind]) (get-in b [:role :kind]))
              (= (:professional-role-entity-id a)
                 (:professional-role-entity-id b))
              (= (:organization-entity-id a)
                 (:organization-entity-id b))))))

(defn fixture-out-of-window-is-not-false [f]
  (chk f "missing-role-is-unmeasured-not-absence rule declared"
       (contains? (get-in contract [:professional-role-epistemics :rules])
                  :missing-role-is-unmeasured-not-absence))
  (let [obs (derived-observations fixture-events fixture-receipts
                                    fixture-window)
        rb (readback obs fixture-events later-window nil)]
    (chk f "empty window reads :unmeasured, not zero"
         (and (= :unmeasured (:status rb)) (empty? (:observations rb))))))

(defn fixture-strict-readback [f]
  (let [obs (derived-observations fixture-events fixture-receipts
                                  fixture-window)
        rejected (readback obs fixture-events fixture-window {:bogus-key "x"})]
    (chk f "unknown filter key is rejected, not ignored"
         (= :rejected-filter (:status rejected))))
  (let [obs (derived-observations fixture-events fixture-receipts
                                  fixture-window)
        fm (readback obs fixture-events fixture-window {:role-kind :fund-manager})
        gp (readback obs fixture-events fixture-window {:role-kind :general-partner})
        unstated (readback obs fixture-events fixture-window
                           {:role-kind :role-unstated})]
    (chk f "role-kind filter matches the carried role exactly"
         (and (= :ok (:status fm))
              (some #{"obs-ev-pr1"} (:observations fm))
              (= :ok (:status gp))
              (some #{"obs-ev-pr4"} (:observations gp))))
    (chk f "unstated role never returned under a role-kind filter"
         (and (= :unmeasured (:status unstated))
              (empty? (:observations unstated)))))
  (let [rules-set (set (get-in contract [:query-readback :rules]))
        status-values (get-in contract [:query-readback :status-values])]
    (chk f ":rejected-filter is a first-class readback status-value"
         (contains? status-values :rejected-filter))
    (chk f "readback declares unknown-filter-key rejection rule"
         (contains? rules-set :unknown-filter-key-is-rejected-not-ignored))
    (chk f "readback declares exact role-kind matching"
         (contains? rules-set :role-kind-filter-matches-carried-role-exactly))
    (chk f "readback declares unstated-role rule"
         (contains? rules-set :unstated-role-never-returned-under-a-role-filter))
    (chk f "readback declares unmeasured-is-not-zero"
         (contains? rules-set :unmeasured-is-not-zero))
    (chk f "readback always carries coverage and missingness"
         (contains? rules-set :readback-must-carry-coverage-and-missingness))))

(defn fixture-forbidden-fields [f]
  (let [forbidden (get-in contract [:derived-observation :forbidden-fields])]
    (doseq [k [:rank :score :centrality :returns :irr :moic :ownership-stake
               :control-percentage :recommendation :current-valuation
               :fitness :suitability :competence-score :employment-status
               :tenure :compensation]]
      (chk f (str "forbidden field declared: " (name k))
           (contains? forbidden k)))))

(defn fixture-refresh-history-append-only [f]
  (let [rh (get contract :refresh-history)]
    (chk f "refresh history is append-only" (true? (:append-only? rh)))
    (chk f "role change is a history reason"
         (str/includes? (str (:schema rh)) ":event-reclassified"))
    (chk f "registration-refetched is a history reason"
         (str/includes? (str (:schema rh)) ":registration-refetched"))))

(defn fixture-hyakka-questions-only [f]
  (let [hp (get contract :hyakka-proposal)]
    (chk f "proposal carries a coverage ref and missingness"
         (and (str/includes? (str (:schema hp)) "coverage-record-ref")
              (str/includes? (str (:schema hp)) "missingness-flags")))))

(defn fixture-coverage-record [f]
  (let [m (get contract :missingness)]
    (chk f "missing-is-unmeasured" (= :missing-is-unmeasured (:rule m)))
    (chk f "role-unstated is a flag"
         (contains? (:flags m) :role-unstated))
    (chk f "identifier-unstated is a flag"
         (contains? (:flags m) :identifier-unstated))
    (chk f "coverage-record schema exists"
         (seq (get-in m [:coverage-record :schema])))))

;; ── Runner ──────────────────────────────────────────────────────────
(def fixtures
  [[:role-is-not-a-person fixture-role-is-not-a-person]
   [:role-is-not-personal-profiling fixture-role-is-not-personal-profiling]
   [:fetch-status-admission fixture-fetch-status-admission]
   [:provenance-chain-required fixture-provenance-chain-required]
   [:unstated-role-carried-not-collapsed fixture-unstated-role-carried-not-collapsed]
   [:disagreement-recorded-never-resolved fixture-disagreement-recorded-never-resolved]
   [:out-of-window-is-not-false fixture-out-of-window-is-not-false]
   [:strict-readback fixture-strict-readback]
   [:forbidden-fields fixture-forbidden-fields]
   [:refresh-history-append-only fixture-refresh-history-append-only]
   [:hyakka-questions-only fixture-hyakka-questions-only]
   [:coverage-record fixture-coverage-record]])

(doseq [[name f] fixtures]
  (f {:fixture name}))

(if (empty? @failures)
  (do (println (str "OK: " (count fixtures)
                    " professional-role fixtures ran clean ("
                    (:method/version contract) ")"))
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "VIOLATION [" fixture "]: " msg)))
      (println (str "FAILED: " (count @failures) " violation(s)"))
      (js/process.exit 1)))