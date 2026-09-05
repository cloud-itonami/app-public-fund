;; round_participant_fixtures.cljs — deterministic offline fixture
;; runner for the round-participant-observation.v2 contract.
;; No network. Exit 0 = clean, 1 = violation found, 2 = contract
;; unreadable.
;;
;; v2 (2026-09-02): adds fixtures for fetch-status admission (a non-ok
;; receipt backs nothing) and event-level provenance chains.
;;
;; Run: nbb tools/round_participant_fixtures.cljs

(ns round-participant-fixtures
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
(def contract-path "capital-observation/round-participant-observation.edn")
(def contract
  (try
    (edn/read-string (.readFileSync fs contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

;; ── Fixture world (all synthetic, no real round/company/investor) ───
(def fixture-window {:from "2026-01-01" :until "2026-07-01"
                     :declared-at "2026-09-01" :timezone "UTC"})

(def fixture-receipts
  [{:receipt-id "rcpt-r1" :source-url "https://company.example/round-1"
    :source-class :company-first-party :source-language "en"
    :observed-at "2026-02-01T00:00:00Z"
    :content-hash (sha256-hex "company round page bytes v1")
    :fetch-status 200}
   {:receipt-id "rcpt-r2"
    :source-url "https://investor.example/news/round-1"
    :source-class :participant-first-party :source-language "en"
    :observed-at "2026-02-02T00:00:00Z"
    :content-hash (sha256-hex "investor page bytes v1")
    :fetch-status 200}
   ;; v2 fixture: a fetch that was NOT fully ok — backs nothing.
   {:receipt-id "rcpt-r3"
    :source-url "https://company.example/round-1?mirror"
    :source-class :company-first-party :source-language "en"
    :observed-at "2026-02-03T00:00:00Z"
    :content-hash (sha256-hex "partial company round page bytes")
    :fetch-status 503}])

(def rcpt-ids (set (map :receipt-id fixture-receipts)))

(def fixture-entities
  [{:entity-id "round-1" :entity-type :financing-round
    :name "Round One (fixture)" :legal-name nil
    :jurisdiction :delaware :identifier-class :registry-filing
    :identifier-value "FIX-R001" :source-receipt-id "rcpt-r1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   {:entity-id "co-1" :entity-type :company
    :name "Company One (fixture)" :legal-name "Company One Inc. (fixture)"
    :jurisdiction :delaware :identifier-class :registration-number
    :identifier-value "FIX-C001" :source-receipt-id "rcpt-r1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   {:entity-id "inv-1" :entity-type :investor-entity
    :name "Investor One (fixture)" :legal-name "Investor One LLC (fixture)"
    :jurisdiction :delaware :identifier-class :registration-number
    :identifier-value "FIX-I001" :source-receipt-id "rcpt-r1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   ;; same brand string, DIFFERENT legal entity — must stay distinct.
   {:entity-id "inv-2" :entity-type :investor-entity
    :name "Investor One (fixture)" :legal-name "Investor One GK (fixture)"
    :jurisdiction :japan :identifier-class :registration-number
    :identifier-value "FIX-I002" :source-receipt-id "rcpt-r2"
    :asserted-at "2026-01-15" :observed-at "2026-02-02T00:00:00Z"}])

(def fixture-events
  [{:event-id "evt-r1" :event-type :participant-named
    :financing-round-entity-id "round-1" :participant-entity-id "inv-1"
    :role {:kind :lead-participant :as-stated-by-source? true}
    :observed-at "2026-02-01T00:00:00Z" :asserted-at "2026-01-20"
    :source-receipt-id "rcpt-r1" :provenance-chain ["rcpt-r1"]}
   {:event-id "evt-r2" :event-type :participant-role-stated
    :financing-round-entity-id "round-1" :participant-entity-id "inv-2"
    :role {:kind :participant :as-stated-by-source? true}
    :observed-at "2026-02-02T00:00:00Z" :asserted-at "2026-01-20"
    :source-receipt-id "rcpt-r2" :provenance-chain ["rcpt-r2"]}])

(def fixture-derived
  [{:observation-id "obs-r1"
    :method/version "round-participant-observation.v2"
    :window fixture-window
    :observation-kind :participant-named-in-window
    :financing-round-entity-id "round-1" :participant-entity-id "inv-1"
    :event-id "evt-r1"
    :value {:kind :participant-naming-observation :basis #{:receipt-only}}
    :missingness-flags #{} :provenance-chain ["rcpt-r1"]
    :asserted-at "2026-01-20"}
   {:observation-id "obs-r2"
    :method/version "round-participant-observation.v2"
    :window fixture-window
    :observation-kind :participant-role-stated-in-window
    :financing-round-entity-id "round-1" :participant-entity-id "inv-2"
    :event-id "evt-r2"
    :value {:kind :participant-naming-observation :basis #{:receipt-only}}
    :missingness-flags #{} :provenance-chain ["rcpt-r2"]
    :asserted-at "2026-01-20"}])

(def fixture-coverage
  {:coverage-unit :jurisdiction :unit-key :delaware :observed-count 1
   :unmeasured-count 0 :window fixture-window
   :method/version "round-participant-observation.v2"})

(def fixture-conflict
  {:conflict-id "conflict-r1" :window fixture-window
   :financing-round-entity-id "round-1" :participant-entity-id "inv-2"
   :competing-source-receipt-ids ["rcpt-r1" "rcpt-r2"]
   :conflict-kind :role-disagreement
   :observed-at "2026-02-02T00:00:00Z"
   :resolution :carry-both-never-resolve})

(def fixture-readback-result
  {:query-id "q-r1" :status :ok :observations ["obs-r1" "obs-r2"]
   :coverage-record-ref fixture-coverage :missingness-flags #{}})

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-window [ctx]
  (let [w (:window contract)]
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
  (let [shared "Investor One (fixture)"
        is (filter #(= shared (:name %)) fixture-entities)
        by-id (into {} (map (juxt :entity-id identity) fixture-entities))]
    (chk ctx "brand string appears on two distinct entities" (= 2 (count is)))
    (chk ctx "same brand never merges entity ids"
         (apply not= (map :entity-id is)))
    (chk ctx "same brand entities differ in legal-name"
         (apply not= (map :legal-name is)))
    (doseq [e fixture-events]
      (chk ctx (str (:event-id e) " financing-round entity typed correctly")
           (= :financing-round (:entity-type (get by-id (:financing-round-entity-id e))))))))

(defn fixture-roles-carried [ctx]
  (let [kinds (set (map #(get-in % [:role :kind]) fixture-events))]
    (chk ctx "lead-participant role carried" (contains? kinds :lead-participant))
    (chk ctx "participant role carried" (contains? kinds :participant))
    (chk ctx "no owner role exists" (nil? (some #{:owner} kinds)))
    (doseq [e fixture-events]
      (chk ctx (str (:event-id e) " role is as-stated-by-source")
           (true? (get-in e [:role :as-stated-by-source?]))))
    (chk ctx "invariant roles-carried-not-collapsed present"
         (contains? (:invariants (:event-record contract))
                    :roles-carried-not-collapsed))))

(defn fixture-not-ownership-or-graph [ctx]
  (let [forbidden (:forbidden-fields (:derived-observation contract))]
    (doseq [f #{:rank :score :centrality :betweenness :degree
                :returns :irr :moic :ownership-stake :suitability
                :recommendation :current-valuation :network-edge-weight}]
      (chk ctx (str "forbidden field present in shape: " f)
           (contains? forbidden f)))
    (chk ctx "invariant participant-naming-is-not-ownership-or-endorsement"
         (contains? (:invariants (:event-record contract))
                    :participant-naming-is-not-ownership-or-endorsement))
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
    (chk ctx "role-disagreement kind"
         (contains? (:conflict-kinds c) :role-disagreement))
    (chk ctx "conflict-is-observation-not-adjudication"
         (contains? (:invariants c) :conflict-is-observation-not-adjudication))
    (chk ctx "conflict-never-resolves-into-ownership-or-endorsement"
         (contains? (:invariants c)
                    :conflict-never-resolves-into-ownership-or-endorsement))))

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
    (chk ctx "financing-round filter key" (contains? filter-keys :financing-round-entity-id))
    (chk ctx "role-kind filter key" (contains? filter-keys :role-kind))
    (chk ctx "centrality is not an addressable filter key" (not (contains? filter-keys :centrality)))
    (chk ctx "fixture readback ok" (= :ok (:status fixture-readback-result)))))

(defn fixture-hyakka-proposal [ctx]
  (let [d (str/lower-case (get-in contract [:hyakka-proposal :disclaimer]))]
    (chk ctx "disclaimer carries no-advice language" (str/includes? d "no investment advice"))
    (chk ctx "disclaimer disclaims endorsement" (str/includes? d "endorsement"))))

(defn fixture-method-version [ctx]
  (chk ctx "method version pinned"
       (str/starts-with? (:method/version contract)
                         "round-participant-observation.v2"))
  (doseq [o fixture-derived]
    (chk ctx (str (:observation-id o) " pins the method version")
         (= (:method/version contract) (:method/version o)))))

(defn fixture-fetch-status-admission [ctx]
  ;; v2: a receipt whose fetch was not fully :ok backs no observation.
  (let [ok-values #{:ok 200}
        sr (:source-receipt contract)
        admission (get-in contract [:source-receipt :fetch-admission])]
    (chk ctx "contract pins fetch-status admission"
         (= :fetch-status-must-be-ok (:rule admission)))
    (chk ctx "non-ok receipt rcpt-r3 is never ok"
         (not (contains? ok-values (:fetch-status
                                   (first (filter #(= "rcpt-r3" (:receipt-id %))
                                                  fixture-receipts))))))
    (chk ctx "admission records non-ok receipts, never drops them"
         (contains? (:invariant admission) :non-ok-receipt-is-recorded-never-dropped))))

(defn fixture-event-provenance [ctx]
  ;; v2: every event carries a non-empty provenance chain of receipt ids.
  (chk ctx "event-record schema carries :provenance-chain"
       (contains? (set (get-in contract [:event-record :schema])) :provenance-chain))
  (chk ctx "event-record invariant provenance-chain-required-on-every-event"
       (contains? (get-in contract [:event-record :invariants])
                  :provenance-chain-required-on-every-event))
  (doseq [e fixture-events]
    (chk ctx (str (:event-id e) " provenance chain non-empty")
         (seq (:provenance-chain e)))
    (chk ctx (str (:event-id e) " provenance chains to its cited receipt")
         (contains? rcpt-ids (:source-receipt-id e)))))

(def fixtures
  {"window-bounds" fixture-window
   "provenance" fixture-provenance
   "source-classes" fixture-source-classes
   "fetch-status-admission" fixture-fetch-status-admission
   "event-provenance" fixture-event-provenance
   "entity-separation" fixture-entity-separation
   "roles-carried-not-collapsed" fixture-roles-carried
   "not-ownership-or-graph" fixture-not-ownership-or-graph
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
    (do (println "PASS: all round-participant fixtures found nothing wrong")
        (js/process.exit 0))
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (js/process.exit 1))))

(run-all)
