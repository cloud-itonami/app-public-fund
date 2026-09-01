;; manager_affiliation_fixtures.cljs — deterministic offline fixture
;; runner for the fund-manager-affiliation-observation.v1 contract.
;; No network. Exit 0 = clean, 1 = violation found, 2 = contract
;; unreadable.
;;
;; Run: nbb tools/manager_affiliation_fixtures.cljs

(ns manager-affiliation-fixtures
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            ["fs" :as fs]))

(def node-crypto (js/require "node:crypto"))

(defonce failures (atom []))

(defn- sha256-hex [s]
  (.. node-crypto (createHash "sha256") (update s) (digest "hex")))

(defn chk [ctx msg ok?]
  (when-not ok?
    (swap! failures conj {:fixture (:fixture ctx) :msg msg}))
  ok?)

;; ── Load the contract ───────────────────────────────────────────────
(def contract-path "capital-observation/fund-manager-affiliation-observation.edn")
(def contract
  (try
    (edn/read-string (.readFileSync fs contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

;; ── Fixture world (all synthetic, no real fund/manager/company) ─────
(def fixture-window {:from "2026-01-01" :until "2026-07-01"
                     :declared-at "2026-09-01" :timezone "UTC"})

(def fixture-receipts
  [{:receipt-id "rcpt-1" :source-url "https://fund.example/fund-1"
    :source-class :fund-first-party :source-language "en"
    :observed-at "2026-02-01T00:00:00Z"
    :content-hash (sha256-hex "fund-1 page bytes v1")
    :fetch-status 200}
   {:receipt-id "rcpt-2"
    :source-url "https://registry.example/records/fund-1"
    :source-class :official-company-registry :source-language "en"
    :observed-at "2026-02-02T00:00:00Z"
    :content-hash (sha256-hex "registry record bytes v1")
    :fetch-status 200}])

(def fixture-entities
  [{:entity-id "fund-1" :entity-type :fund-vehicle
    :name "Fund One (fixture)" :legal-name "Fund One L.P. (fixture)"
    :jurisdiction :delaware :identifier-class :registration-number
    :identifier-value "FIX-0001" :source-receipt-id "rcpt-1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   {:entity-id "mgmt-1" :entity-type :management-company
    :name "Manager One (fixture)" :legal-name "Manager One LLC (fixture)"
    :jurisdiction :delaware :identifier-class :registration-number
    :identifier-value "FIX-0002" :source-receipt-id "rcpt-2"
    :asserted-at "2026-01-15" :observed-at "2026-02-02T00:00:00Z"}
   ;; same brand string, DIFFERENT legal entity — must stay distinct.
   {:entity-id "mgmt-2" :entity-type :management-company
    :name "Manager One (fixture)" :legal-name "Manager One GK (fixture)"
    :jurisdiction :japan :identifier-class :registration-number
    :identifier-value "FIX-0003" :source-receipt-id "rcpt-1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   {:entity-id "gp-1" :entity-type :general-partner
    :name "GP One (fixture)" :legal-name "GP One LLC (fixture)"
    :jurisdiction :delaware :identifier-class :registration-number
    :identifier-value "FIX-0004" :source-receipt-id "rcpt-2"
    :asserted-at "2026-01-15" :observed-at "2026-02-02T00:00:00Z"}])

(def fixture-events
  [{:event-id "evt-1" :event-type :manager-named
    :fund-vehicle-entity-id "fund-1" :affiliated-entity-id "mgmt-1"
    :role {:kind :manager :as-stated-by-source? true}
    :observed-at "2026-02-01T00:00:00Z" :asserted-at "2026-01-20"
    :source-receipt-id "rcpt-1"}
   {:event-id "evt-2" :event-type :general-partner-named
    :fund-vehicle-entity-id "fund-1" :affiliated-entity-id "gp-1"
    :role {:kind :general-partner :as-stated-by-source? true}
    :observed-at "2026-02-02T00:00:00Z" :asserted-at "2026-01-20"
    :source-receipt-id "rcpt-2"}])

(def fixture-derived
  [{:observation-id "obs-1"
    :method/version "fund-manager-affiliation-observation.v1"
    :window fixture-window
    :observation-kind :manager-named-in-window
    :fund-vehicle-entity-id "fund-1" :affiliated-entity-id "mgmt-1"
    :event-id "evt-1"
    :value {:kind :affiliation-observation :basis #{:receipt-only}}
    :missingness-flags #{} :provenance-chain ["rcpt-1"]
    :asserted-at "2026-01-20"}
   {:observation-id "obs-2"
    :method/version "fund-manager-affiliation-observation.v1"
    :window fixture-window
    :observation-kind :general-partner-named-in-window
    :fund-vehicle-entity-id "fund-1" :affiliated-entity-id "gp-1"
    :event-id "evt-2"
    :value {:kind :affiliation-observation :basis #{:receipt-only}}
    :missingness-flags #{} :provenance-chain ["rcpt-2"]
    :asserted-at "2026-01-20"}])

(def fixture-coverage
  {:coverage-unit :jurisdiction :unit-key :delaware :observed-count 2
   :unmeasured-count 0 :window fixture-window
   :method/version "fund-manager-affiliation-observation.v1"})

(def fixture-conflict
  {:conflict-id "conflict-1" :window fixture-window
   :fund-vehicle-entity-id "fund-1" :role-kind :manager
   :competing-source-receipt-ids ["rcpt-1" "rcpt-2"]
   :conflict-kind :management-company-disagreement
   :observed-at "2026-02-02T00:00:00Z"
   :resolution :carry-both-never-resolve})

(def fixture-readback-result
  {:query-id "q-1" :status :ok :observations ["obs-1" "obs-2"]
   :coverage-record-ref fixture-coverage :missingness-flags #{}})

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-window [ctx]
  (let [w (:window contract)
        name (:fixture ctx)]
    (chk ctx "window must be half-open [from, until)" (false? (:closed? w)))
    (chk ctx "window format pinned" (= "YYYY-MM-DD" (:format w)))
    (chk ctx "window has a rule" (contains? w :rule))))

(defn fixture-provenance [ctx]
  (let [sr (:source-receipt contract)]
    (chk ctx "content-hash algo sha256" (= "sha256" (get-in sr [:content-hash :algo])))
    (chk ctx "content-hash of response-body-bytes"
         (= "response-body-bytes" (get-in sr [:content-hash :of])))
    (chk ctx "fetch respects robots" (true? (:respect-robots? (:fetch sr))))
    (chk ctx "fetch respects auth/waf/captcha"
         (true? (:respect-auth-waf-captcha? (:fetch sr))))
    (doseq [r fixture-receipts]
      (chk ctx (str "receipt " (:receipt-id r) " content-hash is sha256 hex")
           (re-find #"^[0-9a-f]{64}$" (:content-hash r))))))

(defn fixture-source-classes [ctx]
  (let [sr (:source-receipt contract)
        allow (set (:source-class-allow sr))
        forbid (set (:source-class-forbid sr))
        disco (set (:source-class-discovery-only sr))]
    (chk ctx "allow and forbid sets must not overlap"
         (empty? (set/intersection allow forbid)))
    (chk ctx "discovery-only classes can never back observations"
         (empty? (set/intersection allow disco)))))

(defn fixture-entity-separation [ctx]
  (let [shared "Manager One (fixture)"
        ms (filter #(= shared (:name %)) fixture-entities)
        by-id (into {} (map (juxt :entity-id identity) fixture-entities))]
    (chk ctx "brand string appears on two distinct entities" (= 2 (count ms)))
    (chk ctx "same brand never merges entity ids"
         (apply not= (map :entity-id ms)))
    (chk ctx "same brand entities differ in legal-name"
         (apply not= (map :legal-name ms)))
    (doseq [e fixture-events]
      (chk ctx (str (:event-id e) " fund-vehicle entity typed correctly")
           (= :fund-vehicle (:entity-type (get by-id (:fund-vehicle-entity-id e))))))))

(defn fixture-roles-carried [ctx]
  (let [kinds (set (map #(get-in % [:role :kind]) fixture-events))]
    (chk ctx "manager role carried" (contains? kinds :manager))
    (chk ctx "general-partner role carried" (contains? kinds :general-partner))
    (chk ctx "no owner role exists" (nil? (some #{:owner} kinds)))
    (doseq [e fixture-events]
      (chk ctx (str (:event-id e) " role is as-stated-by-source")
           (true? (get-in e [:role :as-stated-by-source?]))))
    (chk ctx "invariant roles-carried-not-collapsed present"
         (contains? (:invariants (:event-record contract))
                    :roles-carried-not-collapsed))))

(defn fixture-not-ownership [ctx]
  (let [forbidden (:forbidden-fields (:derived-observation contract))]
    (doseq [f #{:rank :score :centrality :returns :irr :moic
                :ownership-stake :suitability :recommendation
                :current-valuation}]
      (chk ctx (str "forbidden field present in shape: " f)
           (contains? forbidden f)))
    (chk ctx "invariant manager-naming-is-not-ownership-or-control"
         (contains? (:invariants (:event-record contract))
                    :manager-naming-is-not-ownership-or-control))
    (doseq [o fixture-derived f forbidden]
      (chk ctx (str "derived observation " (:observation-id o)
                    " carries no forbidden field " f)
           (not (contains? o f))))))

(defn fixture-derived-observation [ctx]
  (let [kinds (:observation-kinds (:derived-observation contract))
        rcpt-ids (set (map :receipt-id fixture-receipts))]
    (doseq [o fixture-derived]
      (chk ctx (str (:observation-id o) " kind is in contract")
           (contains? kinds (:observation-kind o)))
      (chk ctx (str (:observation-id o) " basis is receipt-only")
           (= #{:receipt-only} (:basis (:value o))))
      (chk ctx (str (:observation-id o) " provenance chain non-empty")
           (seq (:provenance-chain o)))
      (doseq [p (:provenance-chain o)]
        (chk ctx (str "provenance id " p " has a receipt")
             (contains? rcpt-ids p))))))

(defn fixture-missingness [ctx]
  (let [m (:missingness contract)]
    (chk ctx "missing-is-unmeasured rule" (= :missing-is-unmeasured (:rule m)))
    (chk ctx ":no-receipt flag" (contains? (:flags m) :no-receipt))
    (chk ctx ":receipt-unparseable flag" (contains? (:flags m) :receipt-unparseable))
    (chk ctx ":role-unstated flag" (contains? (:flags m) :role-unstated))
    (chk ctx ":first-party-source-conflict flag"
         (contains? (:flags m) :first-party-source-conflict))
    (chk ctx "coverage units non-empty" (seq (:coverage-unit m)))
    (chk ctx "coverage record schema carries unmeasured-count"
         (contains? (set (get-in m [:coverage-record :schema])) :unmeasured-count))
    (chk ctx "fixture coverage record has unmeasured-count key"
         (contains? fixture-coverage :unmeasured-count))))

(defn fixture-conflict-carried [ctx]
  (let [c (:conflict-observation contract)]
    (chk ctx "carry-both-never-resolve" (= :carry-both-never-resolve (:resolution c)))
    (chk ctx "both sides of the conflict are carried"
         (= 2 (count (:competing-source-receipt-ids fixture-conflict))))
    (chk ctx "management-company-disagreement kind"
         (contains? (:conflict-kinds c) :management-company-disagreement))
    (chk ctx "conflict-is-observation-not-adjudication"
         (contains? (:invariants c) :conflict-is-observation-not-adjudication))
    (chk ctx "conflict-never-resolves-into-ownership-or-control"
         (contains? (:invariants c)
                    :conflict-never-resolves-into-ownership-or-control))))

(defn fixture-refresh-history [ctx]
  (let [h (:refresh-history contract)
        reason-kinds (set (rest (get-in h [:schema 4 :kind])))]
    (chk ctx "append-only" (true? (:append-only? h)))
    (chk ctx "reclassification appends, does not overwrite"
         (= :reclassification-appends-does-not-overwrite (:rule h)))
    (chk ctx "method-version-bumped is a recorded reason"
         (contains? reason-kinds :method-version-bumped))
    (chk ctx "window-advanced is a recorded reason"
         (contains? reason-kinds :window-advanced))))

(defn fixture-readback [ctx]
  (let [q (:query-readback contract)
        resp-keys (set (:response-schema q))
        filter-keys (set (get-in q [:request-schema 4 :keys]))]
    (chk ctx "readback carries missingness-flags" (contains? resp-keys :missingness-flags))
    (chk ctx "readback always carries coverage" (contains? resp-keys :coverage-record-ref))
    (chk ctx ":unmeasured status exists" (contains? (:status-values q) :unmeasured))
    (chk ctx "fund-vehicle filter key" (contains? filter-keys :fund-vehicle-entity-id))
    (chk ctx "role-kind filter key" (contains? filter-keys :role-kind))
    (chk ctx "rank is not an addressable filter key" (not (contains? filter-keys :rank)))
    (chk ctx "fixture readback ok" (= :ok (:status fixture-readback-result)))))

(defn fixture-hyakka-proposal [ctx]
  (let [d (str/lower-case (get-in contract [:hyakka-proposal :disclaimer]))]
    (chk ctx "disclaimer carries no-advice language" (str/includes? d "no investment advice"))
    (chk ctx "disclaimer disclaims ownership verification" (str/includes? d "not verified ownership"))))

(defn fixture-method-version [ctx]
  (chk ctx "method version pinned"
       (str/starts-with? (:method/version contract)
                         "fund-manager-affiliation-observation.v1"))
  (doseq [o fixture-derived]
    (chk ctx (str (:observation-id o) " pins the method version")
         (= (:method/version contract) (:method/version o)))))

(def fixtures
  {"window-bounds" fixture-window
   "provenance" fixture-provenance
   "source-classes" fixture-source-classes
   "entity-separation" fixture-entity-separation
   "roles-carried-not-collapsed" fixture-roles-carried
   "not-ownership" fixture-not-ownership
   "derived-observation" fixture-derived-observation
   "missingness-coverage" fixture-missingness
   "conflict-carried" fixture-conflict-carried
   "refresh-history" fixture-refresh-history
   "readback" fixture-readback
   "hyakka-proposal" fixture-hyakka-proposal
   "method-version" fixture-method-version})

;; ── Run ─────────────────────────────────────────────────────────────
(defn run-all []
  (println (str "contract: " contract-path))
  (println (str "method/version: " (:method/version contract)))
  (doseq [[name f] fixtures]
    (try
      (f {:fixture name})
      (println (str "  ok    " name))
      (catch :default e
        (swap! failures conj {:fixture name :msg (str "fixture threw: " (.-message e))})
        (println (str "  threw " name)))))
  (if (empty? @failures)
    (do (println "PASS: all manager-affiliation fixtures found nothing wrong")
        (js/process.exit 0))
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (js/process.exit 1))))

(run-all)
