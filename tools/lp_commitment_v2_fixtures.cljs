;; lp_commitment_v2_fixtures.cljs — deterministic offline fixture
;; runner for the lp-commitment-observation.v2 contract. No network.
;; Exit 0 = clean, 1 = violation found, 2 = contract unreadable.
;;
;; The fixtures exercise the v2 additions specifically:
;;   * :provenance-chain required on EVERY event AND entity record
;;     (not just derived observations)
;;   * identifier invariants (one identifier value per entity type;
;;     one identifier per entity id; missing = :identifier-unstated)
;;   * :receipt-disagreement is RECORDED, never resolved
;;   * :fetch-status-non-ok receipt backs no derived observation
;;   * discovery-only source classes appear in NO provenance chain
;;
;; Run: nbb tools/lp_commitment_v2_fixtures.cljs

(ns lp-commitment-v2-fixtures
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
(def contract-path "capital-observation/lp-commitment-observation.edn")
(def contract
  (try
    (edn/read-string (.readFileSync fs contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

;; ── Fixture world (all synthetic, no real fund/company/manager/LP) ──
(def fixture-window {:from "2026-01-01" :until "2026-07-01"
                     :declared-at "2026-09-02" :timezone "UTC"})

(def fixture-receipts
  [{:receipt-id "rcpt-l1" :source-url "https://lp.example/commitment-1"
    :source-class :institutional-lp-first-party :source-language "en"
    :observed-at "2026-02-01T00:00:00Z"
    :content-hash (sha256-hex "institutional LP disclosure bytes v1")
    :fetch-status 200}
   {:receipt-id "rcpt-l2" :source-url "https://regulator.example/filing-2"
    :source-class :official-regulator :source-language "en"
    :observed-at "2026-02-02T00:00:00Z"
    :content-hash (sha256-hex "regulator filing bytes v2")
    :fetch-status 200}
   ;; a receipt whose fetch did NOT fully succeed — must back nothing.
   {:receipt-id "rcpt-l3"
    :source-url "https://lp.example/commitment-1?mirror"
    :source-class :institutional-lp-first-party :source-language "en"
    :observed-at "2026-02-03T00:00:00Z"
    :content-hash (sha256-hex "mirror page bytes, partial fetch")
    :fetch-status 503}
   ;; a discovery-only receipt — must appear in no provenance chain.
   {:receipt-id "rcpt-l4" :source-url "https://news.example/article-7"
    :source-class :news-report :source-language "en"
    :observed-at "2026-02-04T00:00:00Z"
    :content-hash (sha256-hex "news article bytes, discovery only")
    :fetch-status 200}])

(def fixture-entities
  [{:entity-id "lp-1" :entity-type :limited-partner
    :name "Pension A (fixture)" :legal-name "Pension A Capital Vehicle LP (fixture)"
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value "FIX-L001" :source-receipt-id "rcpt-l1"
    :provenance-chain ["rcpt-l1"]
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   {:entity-id "lp-mgmt-1" :entity-type :management-company
    :name "Pension A (fixture)" :legal-name "Pension A Management LLC (fixture)"
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value "FIX-M001" :source-receipt-id "rcpt-l1"
    :provenance-chain ["rcpt-l1"]
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   ;; entity separation under one brand: limited partner vs management
   ;; company are distinct entities; identifier invariants hold (each
   ;; carries exactly one identifier value; one value never denotes two
   ;; entity types).
   {:entity-id "fund-1" :entity-type :fund-vehicle
    :name "Fund One (fixture)" :legal-name "Fund One LP (fixture)"
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value "FIX-F001" :source-receipt-id "rcpt-l2"
    :provenance-chain ["rcpt-l2"]
    :asserted-at "2026-01-15" :observed-at "2026-02-02T00:00:00Z"}
   ;; missing identifier → :identifier-unstated, never guessed.
   {:entity-id "gp-1" :entity-type :general-partner
    :name "Fund One GP (fixture)" :legal-name nil
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value nil
    :source-receipt-id "rcpt-l2"
    :provenance-chain ["rcpt-l2"]
    :asserted-at "2026-01-15" :observed-at "2026-02-02T00:00:00Z"}])

(def fixture-events
  [{:event-id "evt-l1" :event-type :commitment-announced
    :lp-entity-id "lp-1" :fund-entity-id "fund-1"
    :announced-at "2026-01-20"
    :amount {:kind :stated-commitment :value 25000000
             :currency "USD" :as-stated-by-source? true}
    :source-receipt-id "rcpt-l1"
    :provenance-chain ["rcpt-l1"]}
   {:event-id "evt-l2" :event-type :commitment-amended
    :lp-entity-id "lp-1" :fund-entity-id "fund-1"
    :announced-at "2026-03-05"
    :amount {:kind :stated-commitment-amendment :value 30000000
             :currency "USD" :as-stated-by-source? true}
    :source-receipt-id "rcpt-l2"
    :provenance-chain ["rcpt-l2"]}])

(def fixture-derived
  [{:observation-id "obs-l1"
    :method/version "lp-commitment-observation.v2"
    :window fixture-window
    :observation-kind :lp-commitment-in-window
    :lp-entity-id "lp-1" :fund-entity-id "fund-1" :event-id "evt-l1"
    :value {:kind :stated-amount-observation :basis #{:receipt-only}}
    :missingness-flags #{} :provenance-chain ["rcpt-l1"]
    :asserted-at "2026-01-20"}])

;; two allow-class receipts stating different commitment amounts for the
;; same fund → disagreement is recorded, never resolved.
(def fixture-disagreement
  {:observation-id "obs-l2"
   :method/version "lp-commitment-observation.v2"
   :window fixture-window
   :observation-kind :stated-amount-in-window
   :lp-entity-id "lp-1" :fund-entity-id "fund-1" :event-id "evt-l2"
   :value {:kind :stated-amount-observation :basis #{:receipt-only}
           :value :unmeasured}
   :missingness-flags #{:receipt-disagreement}
   :provenance-chain ["rcpt-l1" "rcpt-l2"]   ; ALL conflicting receipt ids
   :asserted-at "2026-03-05"})

(def fixture-coverage
  {:coverage-unit :jurisdiction :unit-key :delaware :observed-count 1
   :unmeasured-count 0 :window fixture-window
   :method/version "lp-commitment-observation.v2"})

(def fixture-readback-result
  {:query-id "q-l1" :status :ok :observations ["obs-l1"]
   :coverage-record-ref fixture-coverage :missingness-flags #{}})

(def fixture-readback-unmeasured
  {:query-id "q-l2" :status :unmeasured :observations []
   :coverage-record-ref fixture-coverage
   :missingness-flags #{:receipt-disagreement}})

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
           (re-find #"^[0-9a-f]{64}$" (:content-hash r))))
    ;; v2: provenance-chain required on EVERY event, not only derived obs.
    (chk ctx "invariant provenance-chain-required-on-every-event present"
         (contains? (set (:invariants (:event-record contract)))
                    :provenance-chain-required-on-every-event))
    (doseq [e fixture-events]
      (chk ctx (str "event " (:event-id e) " carries a provenance chain")
           (seq (:provenance-chain e)))
      (chk ctx (str "event " (:event-id e) " chain cites its own receipt")
           (contains? (set (:provenance-chain e)) (:source-receipt-id e))))))

(defn fixture-entity-provenance [ctx]
  ;; v2: every entity record also carries a provenance chain citing its
  ;; own receipt.
  (chk ctx "invariant-rule provenance-chain-required-on-every-entity-record present"
       (= :provenance-chain-required-on-every-entity-record
          (:invariant-rule (:entity-record contract))))
  (doseq [x fixture-entities]
    (chk ctx (str "entity " (:entity-id x) " carries a provenance chain")
         (seq (:provenance-chain x)))
    (chk ctx (str "entity " (:entity-id x) " chain cites its own receipt")
         (contains? (set (:provenance-chain x)) (:source-receipt-id x)))))

(defn fixture-source-classes [ctx]
  (let [sr (:source-receipt contract)
        allow (set (:source-class-allow sr))
        forbid (set (:source-class-forbid sr))
        disco (set (:source-class-discovery-only sr))]
    (chk ctx "allow and forbid sets must not overlap"
         (empty? (set/intersection allow forbid)))
    (chk ctx "discovery-only classes can never back observations"
         (empty? (set/intersection allow disco)))
    (chk ctx "discovery-only rule pins no-provenance-chain"
         (= :discovery-only-appears-in-no-provenance-chain
            (:discovery-only-rule sr)))
    ;; fixture world: rcpt-l4 is discovery-only → appears in no chain.
    (let [chains (concat (map :provenance-chain fixture-events)
                         (map :provenance-chain fixture-entities)
                         (map :provenance-chain fixture-derived))]
      (chk ctx "discovery-only receipt appears in no provenance chain"
           (not (some #(contains? (set %) "rcpt-l4") chains))))))

(defn fixture-entity-separation [ctx]
  (let [shared "Pension A (fixture)"
        is (filter #(= shared (:name %)) fixture-entities)
        by-id (into {} (map (juxt :entity-id identity) fixture-entities))]
    (chk ctx "brand string appears on two distinct entities" (= 2 (count is)))
    (chk ctx "same brand never merges entity ids"
         (apply not= (map :entity-id is)))
    (chk ctx "limited partner and management company differ in legal-name"
         (apply not= (map :legal-name is)))
    (chk ctx "brand string never auto-creates a legal entity (note present)"
         (str/includes? (str/lower-case (str (:note (:entity-record contract))))
                        "never auto-creates"))
    (doseq [e fixture-events]
      (chk ctx (str "event " (:event-id e) " lp and fund entities exist and are typed")
           (and (contains? by-id (:lp-entity-id e))
                (contains? by-id (:fund-entity-id e))))
      (chk ctx (str "event " (:event-id e) " lp and fund are distinct entity ids")
           (not= (:lp-entity-id e) (:fund-entity-id e))))
    (chk ctx "invariant lp-and-fund-are-distinct-entity-ids present"
         (contains? (set (:invariants (:event-record contract)))
                    :lp-and-fund-are-distinct-entity-ids))))

(defn fixture-identifier-invariants [ctx]
  (let [iv (set (:identifier-invariant (:entity-record contract)))
        id-vals (remove nil? (map :identifier-value fixture-entities))]
    (chk ctx "identifier-value-unique-across-entity-types invariant present"
         (contains? iv :identifier-value-unique-across-entity-types))
    (chk ctx "one-identifier-per-entity-id invariant present"
         (contains? iv :one-identifier-per-entity-id))
    (chk ctx "no identifier value denotes two entity types"
         (= (count id-vals) (count (distinct id-vals))))
    (chk ctx "a missing identifier is unstated, never guessed"
         (nil? (:identifier-value (first (filter #(= "gp-1" (:entity-id %))
                                                 fixture-entities)))))
    (chk ctx ":identifier-unstated missingness flag exists"
         (contains? (:flags (:missingness contract)) :identifier-unstated))))

(defn fixture-amounts-carried [ctx]
  (let [kinds (set (map #(get-in % [:amount :kind]) fixture-events))
        amount-kind-map (second (drop-while #(not= :amount %)
                                            (:schema (:event-record contract))))
        amount-kinds (set (rest (:kind amount-kind-map)))]
    (chk ctx "stated-commitment amount kind carried" (contains? kinds :stated-commitment))
    (chk ctx "stated-commitment-amendment amount kind carried"
         (contains? kinds :stated-commitment-amendment))
    (chk ctx "invariant commitment-kind-is-carried-not-collapsed present"
         (contains? (set (:invariants (:event-record contract)))
                    :commitment-kind-is-carried-not-collapsed))
    (doseq [e fixture-events]
      (chk ctx (str (:event-id e) " amount is as-stated-by-source")
           (true? (get-in e [:amount :as-stated-by-source?]))))))

(defn fixture-receipt-admission [ctx]
  (let [ra (:receipt-admission contract)]
    (chk ctx "admission rule pins fetch-status-ok-required"
         (= :fetch-status-ok-required (:rule ra)))
    (chk ctx "ok is the only admitting status"
         (= #{:ok} (set (:admit-when ra))))
    (chk ctx "non-ok receipt flags :fetch-status-non-ok"
         (contains? (:else ra) :missingness-flag))
    (chk ctx "non-ok receipt backs no observation"
         (false? (:backs-observation? (:else ra))))
    ;; fixture world: rcpt-l3 (fetch-status 503) backs nothing.
    (chk ctx "the non-ok fixture receipt carries a non-200 status"
         (not= 200 (:fetch-status (first (filter #(= "rcpt-l3" (:receipt-id %))
                                                 fixture-receipts)))))
    (doseq [o (concat fixture-derived)]
      (doseq [p (:provenance-chain o)]
        (let [r (first (filter #(= p (:receipt-id %)) fixture-receipts))]
          (chk ctx (str "observation " (:observation-id o)
                        " cites no non-ok receipt (" p ")")
               (= 200 (:fetch-status r))))))))

(defn fixture-disagreement-recorded [ctx]
  (let [rd (:receipt-disagreement contract)]
    (chk ctx "rule is record-never-resolve" (= :record-never-resolve (:rule rd)))
    (chk ctx "disagreement result value is :unmeasured"
         (= :unmeasured (get-in rd [:result :value])))
    (chk ctx "disagreement carries ALL conflicting receipt ids"
         (= ["rcpt-l1" "rcpt-l2"] (:provenance-chain fixture-disagreement)))
    (chk ctx "disagreement flagged on the observation"
         (contains? (:missingness-flags fixture-disagreement)
                    :receipt-disagreement))
    (chk ctx ":receipt-disagreement missingness flag exists"
         (contains? (:flags (:missingness contract)) :receipt-disagreement))
    (chk ctx "no resolved \"truth\" value is present"
         (not (contains? fixture-disagreement :resolved-value)))))

(defn fixture-not-score-or-advice [ctx]
  (let [forbidden (:forbidden-fields (:derived-observation contract))]
    (doseq [f #{:rank :score :centrality :nav :ownership-stake
                :suitability :recommendation :current-valuation}]
      (chk ctx (str "forbidden field present in shape: " f)
           (contains? forbidden f)))
    (doseq [o (concat fixture-derived fixture-disagreement)
            f forbidden]
      (chk ctx (str "observation " (:observation-id o)
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
  (let [m (:missingness contract)
        flags (:flags m)]
    (chk ctx "missing-is-unmeasured rule" (= :missing-is-unmeasured (:rule m)))
    (chk ctx ":no-receipt flag" (contains? flags :no-receipt))
    (chk ctx ":fetch-status-non-ok flag" (contains? flags :fetch-status-non-ok))
    (chk ctx ":provenance-chain-incomplete flag"
         (contains? flags :provenance-chain-incomplete))
    (chk ctx "coverage units non-empty" (seq (:coverage-unit m)))
    (chk ctx "coverage record schema carries unmeasured-count"
         (contains? (set (get-in m [:coverage-record :schema])) :unmeasured-count))
    (chk ctx "fixture coverage record has unmeasured-count key"
         (contains? fixture-coverage :unmeasured-count))))

(defn fixture-refresh-history [ctx]
  (let [h (:refresh-history contract)
        reason-kinds (set (rest (get-in h [:schema 4 :kind])))]
    (chk ctx "append-only" (true? (:append-only? h)))
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
    (chk ctx "amount-kind filter key" (contains? filter-keys :amount-kind))
    (chk ctx "source-class filter key (v2)" (contains? filter-keys :source-class))
    (chk ctx "nav is not an addressable filter key" (not (contains? filter-keys :nav)))
    (chk ctx "fixture readback ok" (= :ok (:status fixture-readback-result)))
    (chk ctx "fixture readback can return :unmeasured with flags"
         (and (= :unmeasured (:status fixture-readback-unmeasured))
              (seq (:missingness-flags fixture-readback-unmeasured))))))

(defn fixture-hyakka-proposal [ctx]
  (let [d (str/lower-case (get-in contract [:hyakka-proposal :disclaimer]))]
    (chk ctx "disclaimer carries no-advice language" (str/includes? d "no investment advice"))
    (chk ctx "disclaimer disclaims endorsement" (str/includes? d "endorsement"))))

(defn fixture-method-version [ctx]
  (chk ctx "method version pinned to v2"
       (= "lp-commitment-observation.v2" (:method/version contract)))
  (doseq [o (concat fixture-derived [fixture-disagreement])]
    (chk ctx (str (:observation-id o) " pins the method version")
         (= (:method/version contract) (:method/version o)))))

(def fixtures
  {"window-bounds" fixture-window
   "provenance" fixture-provenance
   "entity-provenance" fixture-entity-provenance
   "source-classes" fixture-source-classes
   "entity-separation" fixture-entity-separation
   "identifier-invariants" fixture-identifier-invariants
   "amounts-carried-not-collapsed" fixture-amounts-carried
   "receipt-admission" fixture-receipt-admission
   "disagreement-recorded" fixture-disagreement-recorded
   "not-score-or-advice" fixture-not-score-or-advice
   "derived-observation" fixture-derived-observation
   "missingness-coverage" fixture-missingness
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
    (do (println "PASS: all lp-commitment v2 fixtures found nothing wrong")
        (js/process.exit 0))
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (js/process.exit 1))))

(run-all)
