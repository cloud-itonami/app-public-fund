;; fund_close_observation_fixtures.cljs — deterministic offline fixture
;; runner for the fund-close-observation.v2 contract. No network.
;; Exit 0 = clean, 1 = violation found, 2 = contract unreadable.
;;
;; The fixtures exercise the v2 additions specifically:
;;   * :provenance-chain required on EVERY event (not just derived obs)
;;   * identifier invariants (one identifier value per entity type;
;;     one identifier per entity id; missing = :identifier-unstated)
;;   * :receipt-disagreement is RECORDED, never resolved
;;   * :fetch-status-non-ok receipt backs no derived observation
;;
;; Run: nbb tools/fund_close_observation_fixtures.cljs

(ns fund-close-fixtures
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
(def contract-path "capital-observation/fund-close-observation.edn")
(def contract
  (try
    (edn/read-string (.readFileSync fs contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

;; ── Fixture world (all synthetic, no real fund/company/manager) ─────
(def fixture-window {:from "2026-01-01" :until "2026-07-01"
                     :declared-at "2026-09-01" :timezone "UTC"})

(def fixture-receipts
  [{:receipt-id "rcpt-f1" :source-url "https://manager.example/fund-1"
    :source-class :manager-first-party :source-language "en"
    :observed-at "2026-02-01T00:00:00Z"
    :content-hash (sha256-hex "manager fund page bytes v1")
    :fetch-status 200}
   {:receipt-id "rcpt-f2" :source-url "https://regulator.example/filing-1"
    :source-class :official-regulator :source-language "en"
    :observed-at "2026-02-02T00:00:00Z"
    :content-hash (sha256-hex "regulator filing bytes v1")
    :fetch-status 200}
   ;; a receipt whose fetch did NOT fully succeed — must back nothing.
   {:receipt-id "rcpt-f3"
    :source-url "https://manager.example/fund-1?mirror"
    :source-class :manager-first-party :source-language "en"
    :observed-at "2026-02-03T00:00:00Z"
    :content-hash (sha256-hex "mirror page bytes, partial fetch")
    :fetch-status 503}])

(def fixture-entities
  [{:entity-id "fund-1" :entity-type :fund-vehicle
    :name "Fund One (fixture)" :legal-name "Fund One LP (fixture)"
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value "FIX-F001" :source-receipt-id "rcpt-f1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   {:entity-id "mgmt-1" :entity-type :management-company
    :name "Fund One (fixture)" :legal-name "Fund One Management LLC (fixture)"
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value "FIX-M001" :source-receipt-id "rcpt-f1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}
   ;; entity separation under one brand: fund vehicle vs management company
   ;; are distinct entities; identifier invariants hold (each carries exactly
   ;; one identifier value; one value never denotes two entity types).
   {:entity-id "gp-1" :entity-type :general-partner
    :name "Fund One GP (fixture)" :legal-name nil
    :jurisdiction :delaware :identifier-class :official-registry-id
    :identifier-value nil                     ; missing → :identifier-unstated
    :source-receipt-id "rcpt-f1"
    :asserted-at "2026-01-15" :observed-at "2026-02-01T00:00:00Z"}])

(def fixture-events
  [{:event-id "evt-f1" :event-type :fund-announced
    :entity-id "fund-1"
    :announced-at "2026-01-20"
    :amount {:kind :stated-target :value 100000000
             :currency "USD" :as-stated-by-source? true}
    :source-receipt-id "rcpt-f1"                       ; v2: every event
    :provenance-chain ["rcpt-f1"]}
   {:event-id "evt-f2" :event-type :fund-first-close
    :entity-id "fund-1"
    :announced-at "2026-03-05"
    :amount {:kind :stated-first-close :value 40000000
             :currency "USD" :as-stated-by-source? true}
    :source-receipt-id "rcpt-f2"
    :provenance-chain ["rcpt-f2"]}])

(def fixture-derived
  [{:observation-id "obs-f1"
    :method/version "fund-close-observation.v2"
    :window fixture-window
    :observation-kind :fund-close-in-window
    :entity-id "fund-1" :event-id "evt-f2"
    :value {:kind :stated-amount-observation :basis #{:receipt-only}}
    :missingness-flags #{} :provenance-chain ["rcpt-f2"]
    :asserted-at "2026-03-05"}])

(def fixture-coverage
  {:coverage-unit :jurisdiction :unit-key :delaware :observed-count 1
   :unmeasured-count 0 :window fixture-window
   :method/version "fund-close-observation.v2"})

(def fixture-disagreement
  {:observation-id "obs-f2"
   :method/version "fund-close-observation.v2"
   :window fixture-window
   :observation-kind :stated-amount-in-window
   :entity-id "fund-1" :event-id "evt-f2"
   :value {:kind :stated-amount-observation :basis #{:receipt-only}
           :value :unmeasured}
   :missingness-flags #{:receipt-disagreement}
   :provenance-chain ["rcpt-f1" "rcpt-f2"]   ; ALL conflicting receipt ids
   :asserted-at "2026-03-05"})

(def fixture-readback-result
  {:query-id "q-f1" :status :ok :observations ["obs-f1"]
   :coverage-record-ref fixture-coverage :missingness-flags #{}})

(def fixture-readback-unmeasured
  {:query-id "q-f2" :status :unmeasured :observations []
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
  (let [shared "Fund One (fixture)"
        is (filter #(= shared (:name %)) fixture-entities)
        by-id (into {} (map (juxt :entity-id identity) fixture-entities))]
    (chk ctx "brand string appears on two distinct entities" (= 2 (count is)))
    (chk ctx "same brand never merges entity ids"
         (apply not= (map :entity-id is)))
    (chk ctx "fund vehicle and management company differ in legal-name"
         (apply not= (map :legal-name is)))
    (chk ctx "brand string never auto-creates a legal entity (note present)"
         (str/includes? (str/lower-case (str (:note (:entity-record contract))))
                        "never auto-creates"))
    (doseq [e fixture-events]
      (chk ctx (str (:event-id e) " entity exists and is typed")
           (contains? by-id (:entity-id e))))))

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
    (chk ctx "stated-target amount kind carried" (contains? kinds :stated-target))
    (chk ctx "stated-first-close amount kind carried" (contains? kinds :stated-first-close))
    (chk ctx "invariant amount-kind-is-carried-not-collapsed present"
         (contains? (set (:invariants (:event-record contract)))
                    :amount-kind-is-carried-not-collapsed))
    (chk ctx "event schema amount kinds include disbursed-credits"
         (contains? amount-kinds :disbursed-credits))
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
    ;; fixture world: rcpt-f3 (fetch-status 503) backs nothing.
    (chk ctx "the non-ok fixture receipt carries a non-200 status"
         (not= 200 (:fetch-status (first (filter #(= "rcpt-f3" (:receipt-id %))
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
         (= ["rcpt-f1" "rcpt-f2"] (:provenance-chain fixture-disagreement)))
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
       (= "fund-close-observation.v2" (:method/version contract)))
  (doseq [o (concat fixture-derived [fixture-disagreement])]
    (chk ctx (str (:observation-id o) " pins the method version")
         (= (:method/version contract) (:method/version o)))))

(def fixtures
  {"window-bounds" fixture-window
   "provenance" fixture-provenance
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
    (do (println "PASS: all fund-close fixtures found nothing wrong")
        (js/process.exit 0))
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (js/process.exit 1))))

(run-all)
