#!/usr/bin/env nbb
;; fund_service_provider_fixtures.cljs — deterministic offline fixtures for the
;; fund-service-provider-observation contract
;; (`capital-observation/fund-service-provider-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/fund_service_provider_fixtures.cljs [path/to/contract.edn]
;;
;; v2 (2026-09-05): mirrors the co-investment v2 hardening — fetch-status
;; admission gate (only :ok backs a naming; refusal is a RECORD, never
;; silence), required event-level provenance chains (invented/empty/
;; re-headed chains are refused), and strict readback (unknown filter
;; keys rejected; role and listing-kind filters match carried values
;; exactly). Negative fixtures construct violating inputs and assert
;; the derivation REFUSES them.

(ns fund-service-provider-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation"
                 "fund-service-provider-observation.edn")))

;; ── Load contract ───────────────────────────────────────────────────
(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

(defn sha256 [s]
  (.digest (doto (crypto/createHash "sha256") (.update s)) "hex"))

(defn receipt [id url class lang body]
  {:receipt-id id :source-url url :source-class class
   :source-language lang :observed-at "2026-09-01"
   :content-hash (sha256 body) :fetch-status :ok})

;; ── Fixture data (deterministic, no network) ────────────────────────
;; Receipts are official / first-party class only: fund documents and
;; regulator filings name the providers; news is discovery-only here.
(def receipts
  [(receipt "r-1" "https://example-fund.test/fund-i/annual-report"
            :fund-first-party "en"
            "MANAGER Fund I L.P. annual report: auditor ACME LLC; custodian CUSTCO")
   (receipt "r-2" "https://example-regulator.test/form-adv/FUND-MGR"
            :official-regulator "en"
            "FORM ADV: administrator ADMINCO LP; auditor ACME LLC")
   (receipt "r-3" "https://example-registry.test/company/ADMINCO"
            :official-company-registry "ja"
            "ADMINCO 合同会社 登記記録")
   (receipt "r-4" "https://example-news.test/articles/fund-auditor"
            :news-report "en"
            "News: some fund reportedly changed auditor")])

;; Same brand MANAGER spans a fund vehicle and its management company —
;; distinct entities. ACME, ADMINCO and CUSTCO are provider entities.
(def entities
  [{:entity-id "e-fund" :entity-type :fund-vehicle :name "MANAGER"
    :legal-name "MANAGER Fund I L.P." :jurisdiction "US"
    :identifier-class :official-registry-id :identifier-value "FUND-I-1"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-mgr" :entity-type :management-company :name "MANAGER"
    :legal-name "MANAGER LLC" :jurisdiction "US"
    :identifier-class :crd :identifier-value "123456"
    :source-receipt-id "r-2" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-aud" :entity-type :service-provider :name "ACME"
    :legal-name "ACME LLC" :jurisdiction "US"
    :identifier-class :crd :identifier-value "654321"
    :source-receipt-id "r-2" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-adm" :entity-type :service-provider :name "ADMINCO"
    :legal-name "ADMINCO LP" :jurisdiction "JP"
    :identifier-class :company-registration-number :identifier-value "JP-0901-1"
    :source-receipt-id "r-3" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-cus" :entity-type :service-provider :name "CUSTCO"
    :legal-name "CUSTCO Trust Co." :jurisdiction "US"
    :identifier-class :official-registry-id :identifier-value "CUST-1"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

;; v2: receipts extended with a fetch-failed receipt (r-6). It is
;; recorded verbatim and refuses admission — it can never back a naming.
(def receipts
  (conj receipts
    (assoc (receipt "r-6" "https://zeta-fund.example.test/audit-report"
                    :fund-first-party "en"
                    "Fund vehicle ZETA-1: auditor — OMEGA AUDIT LLP")
           :fetch-status :error)))

(def events
  [{:event-id "ev-1" :event-type :provider-named
    :fund-vehicle-entity-id "e-fund" :provider-entity-id "e-aud"
    :provider-role :auditor
    :announced-at "2026-08-20" :asserted-at "2026-09-01"
    :listing-kind {:kind :stated-in-fund-document}
    :as-stated-by-source? true
    :source-receipt-id "r-1"
    :provenance-chain ["r-1"]}
   {:event-id "ev-2" :event-type :provider-named
    :fund-vehicle-entity-id "e-fund" :provider-entity-id "e-cus"
    :provider-role :custodian
    :announced-at "2026-08-21" :asserted-at "2026-09-01"
    :listing-kind {:kind :stated-in-fund-document}
    :as-stated-by-source? true
    :source-receipt-id "r-1"
    :provenance-chain ["r-1"]}
   {:event-id "ev-3" :event-type :provider-named
    :fund-vehicle-entity-id "e-fund" :provider-entity-id "e-adm"
    ;; role-unstated fixture: receipt names ADMINCO but carries no role
    :provider-role :unstated
    :announced-at "2026-08-25" :asserted-at "2026-09-01"
    :listing-kind {:kind :stated-in-regulatory-filing}
    :as-stated-by-source? true
    :source-receipt-id "r-2"
    :provenance-chain ["r-2"]}
   ;; v2: this event's ONLY receipt refused admission (r-6 :error) —
   ;; deriving a naming from it must be REFUSED with a refusal record.
   {:event-id "ev-4" :event-type :provider-named
    :fund-vehicle-entity-id "e-fund" :provider-entity-id "e-aud"
    :provider-role :auditor
    :announced-at "2026-08-28" :asserted-at "2026-09-01"
    :listing-kind {:kind :stated-in-audit-report}
    :as-stated-by-source? true
    :source-receipt-id "r-6"
    :provenance-chain ["r-6"]}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

;; ── v2 derivation model (deterministic, mirrors the contract) ───────
(defn admitted? [r]
  (contains? (get-in contract [:source-receipt :admission :admitted])
             (:fetch-status r)))

(defn chain-valid?
  "v2 provenance-chain rule: non-empty, all ids exist, last element is
  the event's :source-receipt-id (head of the chain in readback)."
  [receipt-ids ev]
  (let [ch (:provenance-chain ev)]
    (and (vector? ch) (seq ch)
         (every? #(contains? receipt-ids %) ch)
         (= (last ch) (:source-receipt-id ev)))))

(defn derive-observations
  "Returns {:observations [...] :refusals [...]} under the v2 rules:
  an event with an invalid provenance chain is refused with
  :provenance-chain-invalid; an event whose receipt refuses admission
  is refused with a refusal record. A naming observation is derived
  only from admitted, chain-valid events. No third outcome."
  [contract receipts events]
  (let [by-id (into {} (map (juxt :receipt-id identity) receipts))
        receipt-ids (set (keys by-id))]
    (reduce
      (fn [acc ev]
        (cond
          (not (chain-valid? receipt-ids ev))
          (update acc :refusals conj {:event-id (:event-id ev)
                                      :reason :provenance-chain-invalid})
          (not (admitted? (get by-id (:source-receipt-id ev))))
          (update acc :refusals conj
                  {:receipt-id (:source-receipt-id ev)
                   :fetch-status (:fetch-status (get by-id (:source-receipt-id ev)))
                   :admission-refused-at (:asserted-at ev)
                   :method/version (:method/version contract)})
          :else
          (update acc :observations conj
                  {:observation-id (str "o-" (:event-id ev))
                   :method/version (:method/version contract)
                   :window window
                   :observation-kind (:event-type ev)
                   :fund-vehicle-entity-id (:fund-vehicle-entity-id ev)
                   :provider-entity-id (:provider-entity-id ev)
                   :provider-role (:provider-role ev)
                   :missingness-flags (cond-> #{}
                                        (= :unstated (:provider-role ev))
                                        (conj :role-unstated))
                   :provenance-chain (:provenance-chain ev)
                   :asserted-at (:asserted-at ev)})))
      {:observations [] :refusals []}
      events)))

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  ;; Every event cites a receipt whose content-hash exists and every
  ;; entity cites a receipt; receipt ids resolve; chains are complete.
  (let [rmap (into {} (map (juxt :receipt-id identity) receipts))]
    (doseq [ev events]
      (let [r (get rmap (:source-receipt-id ev))]
        (when-not (and r (re-find #"^[0-9a-f]{64}$" (:content-hash r)))
          (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt")))
        (when-not (and (seq (:provenance-chain ev))
                       (every? rmap (:provenance-chain ev))
                       (= (last (:provenance-chain ev)) (:source-receipt-id ev)))
          (fail! f (str "event " (:event-id ev)
                        " has an incomplete or non-head chain")))))
    (doseq [e entities]
      (when-not (get rmap (:source-receipt-id e))
        (fail! f (str "entity " (:entity-id e) " lacks a resolvable receipt"))))))

(defn fixture-source-class [f]
  ;; Only allow-listed source classes back observations; news and
  ;; directories are discovery-only here.
  (let [allow (:source-class-allow (:source-receipt contract))
        disc  (:source-class-discovery-only (:source-receipt contract))]
    (doseq [r receipts]
      (when (contains? (:source-class-forbid (:source-receipt contract))
                       (:source-class r))
        (fail! f (str "receipt " (:receipt-id r)
                      " uses a forbidden class " (:source-class r)))))
    (when-not (contains? disc :news-report)
      (fail! f "news-report must be discovery-only in this contract"))
    (doseq [ev events]
      (let [r (get (into {} (map (juxt :receipt-id identity) receipts))
                   (:source-receipt-id ev))]
        (when-not (contains? allow (:source-class r))
          (fail! f (str "event " (:event-id ev)
                        " is backed by a non-allow class")))))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id; the fund
  ;; vehicle is not the management company; each provider is its own
  ;; entity id.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities"))))
    (let [types (set (map (juxt :entity-id :entity-type) entities))]
      (when-not (= (count types) (count entities))
        (fail! f "one entity id maps to more than one entity type")))
    (let [event-ids (set (mapcat (fn [ev]
                                   [(:fund-vehicle-entity-id ev)
                                    (:provider-entity-id ev)])
                                 events))]
      (doseq [id event-ids]
        (when-not (some #(= (:entity-id %) id) entities)
          (fail! f (str "event entity id " id " does not resolve")))))))

(defn fixture-role-carried [f]
  ;; roles-carried-not-collapsed: roles survive as separate values, an
  ;; unstated role is carried as :unstated (never guessed), and the
  ;; contract allows no "engagement-verified" field.
  (let [roles (set (map :provider-role events))
        ff    (set (:forbidden-fields (:derived-observation contract)))]
    (when-not (contains? roles :unstated)
      (fail! f "unstated role not carried as :unstated"))
    (doseq [k [:auditor :administrator :custodian]]
      (when-not (contains? (set (:provider-role-allow (:event-record contract))) k)
        (fail! f (str "provider-role-allow missing " k))))
    (doseq [k [:engagement-verified :fee-arrangement :diligence-opinion]]
      (when-not (contains? ff k)
        (fail! f (str "forbidden-fields missing " k))))))

(defn fixture-receipt-admission [f]
  ;; fetch-status-ok-required: a non-:ok receipt backs no observation.
  (let [adm (:receipt-admission contract)]
    (when-not (= :fetch-status-ok-required (:rule adm))
      (fail! f "receipt admission rule must be fetch-status-ok-required"))
    (when-not (= #{:ok} (set (:admit-when adm)))
      (fail! f "receipt admission must admit only :ok"))
    (let [bad (assoc (receipt "r-9" "https://x.test/robots-blocked"
                              :fund-first-party "en" "blocked body")
                     :fetch-status :robots-disallowed)
          admitted? (contains? (set (:admit-when adm)) (:fetch-status bad))]
      (when admitted?
        (fail! f "non-ok receipt would be admitted")))))

(defn fixture-missingness [f]
  ;; missing-is-unmeasured: ev-3 has an unstated role and must be
  ;; flagged as such, not silently filled.
  (let [flags (:flags (:missingness contract))]
    (doseq [k [:provider-unstated :role-unstated :provenance-chain-incomplete
               :fetch-status-non-ok]]
      (when-not (contains? flags k)
        (fail! f (str "missingness flags lack " k))))
    (when-not (= :unstated (:provider-role (nth events 2)))
      (fail! f "unstated role not carried as :unstated in ev-3"))
    (when-not (= :missing-is-unmeasured (:rule (:missingness contract)))
      (fail! f "missingness rule must be missing-is-unmeasured"))))

(defn fixture-window-half-open [f]
  ;; Half-open [from, until): until == 2026-09-01 must be EXCLUDED.
  (let [in-window? (fn [d] (and (>= (compare d (:from window)) 0)
                                (< (compare d (:until window)) 0)))]
    (when-not (in-window? "2026-08-31")
      (fail! f "last day before until excluded"))
    (when (in-window? "2026-09-01")
      (fail! f "until date must be excluded in half-open window"))))

(defn fixture-conflict-carry-both [f]
  ;; Cross-source disagreement is carried, never resolved: two receipts
  ;; naming different providers for the same (fund, role) produce a
  ;; conflict record with BOTH ids and :unmeasured derived value.
  (let [c (:conflict contract)]
    (when-not (= :carry-both-never-resolve (:rule c))
      (fail! f "conflict rule must be carry-both-never-resolve"))
    (when-not (:no-winner-mechanism c)
      (fail! f "contract must declare no winner-picking mechanism"))
    (when-not (= :disagreement-never-hardens-into-a-naming
                 (:invariant c))
      (fail! f "conflict invariant missing"))
    (let [cr (:conflict-record c)]
      (when-not (some #{:competing-source-receipt-ids} (:schema cr))
        (fail! f "conflict record schema must carry competing receipt ids")))))

(defn fixture-refresh-append-only [f]
  (let [rh (:refresh/history contract)]
    (when-not (:append-only? rh)
      (fail! f "refresh history must be append-only"))
    (when-not (= :append-only-never-rewrite (:rule rh))
      (fail! f "refresh history rule must be append-not-rewrite"))
    (when-not (= :reclassification-appends-does-not-overwrite
                 (:retraction-rule rh))
      (fail! f "retraction must append, never overwrite the naming"))))

(defn fixture-readback-shape [f]
  ;; Readback: status values exist, unknown keys are rejected, a role
  ;; filter matches the carried role exactly, and the rule requires
  ;; coverage + missingness.
  (let [qr (:query-readback contract)]
    (doseq [k [:ok :unmeasured :out-of-window :rejected-filter]]
      (when-not (contains? (:status-values qr) k)
        (fail! f (str "readback status-values missing " k))))
    (when-not (contains? (set (:request-schema qr)) :query-id)
      (fail! f "readback request lacks :query-id"))
    (let [filter-spec (some #(when (and (map? %) (contains? % :keys)) %)
                            (:request-schema qr))]
      (when-not (and filter-spec
                     (contains? (set (:keys filter-spec)) :provider-role))
        (fail! f "readback filter lacks :provider-role")))
    (when-not (= :readback-always-carries-coverage-and-missingness (:rule qr))
      (fail! f "readback rule missing"))))

(defn fixture-hyakka-proposal [f]
  (let [hp (:proposal contract)]
    (when-not (= :auditable-question (:kind hp))
      (fail! f "proposal kind must be auditable-question"))
    (when-not (contains? (:never hp) :investment-advice)
      (fail! f "proposal :never must include :investment-advice"))
    (when (empty? (:example-questions hp))
      (fail! f "proposal lacks example questions"))))

(defn fixture-forbidden-fields [f]
  ;; No rank/score/returns/ownership/suitability/personal field may
  ;; exist in the derived-observation shape.
  (let [ff (set (:forbidden-fields (:derived-observation contract)))]
    (doseq [k [:rank :score :centrality :returns :ownership-stake
               :suitability :recommendation :personal-wealth :aum]]
      (when-not (contains? ff k)
        (fail! f (str "forbidden-fields missing " k))))))

(defn fixture-content-hash-determinism [f]
  ;; Same bytes -> same hash, different bytes -> different hash.
  (let [a (sha256 "identical body") b (sha256 "identical body")
        c (sha256 "different body")]
    (when-not (and (= a b) (not= a c))
      (fail! f "sha256 receipt hashing not deterministic"))))

(defn fixture-refusal-record [f]
  ;; v2: ev-4's receipt (r-6, :error) refuses admission. The refusal is
  ;; a RECORD (never silence) carrying fetch-status + method/version,
  ;; and no naming observation is derived from it. Re-fetch appends.
  (let [{:keys [observations refusals]}
        (derive-observations contract receipts events)
        refused (some #(when (= (:receipt-id %) "r-6") %) refusals)]
    (when-not (every? #(not= (:observation-kind %) :provider-named-from-refused) observations)
      (fail! f "sanity"))
    (when (some (fn [o] (and (= (:provider-entity-id o) "e-aud")
                             (some #(= "r-6" %) (:provenance-chain o))))
                observations)
      (fail! f "an admission-refused receipt backed a naming observation"))
    (when (nil? refused)
      (fail! f "admission refusal must produce a refusal record, not silence"))
    (when-not (and (= (:fetch-status refused) :error)
                   (= (:method/version refused) (:method/version contract)))
      (fail! f "refusal record must carry fetch-status and method/version"))
    (when-not (= :re-fetch-appends-new-receipt-plus-history
                 (get-in contract [:receipt-admission :re-fetch-rule]))
      (fail! f "re-fetch must append, never retro-invalidate"))
    (when-not (contains? (:status-values (:query-readback contract))
                         :admission-refused)
      (fail! f "readback must be able to answer :admission-refused"))
    (let [statuses (set (:fetch-status-values (:source-receipt contract)))]
      (doseq [k [:ok :error :redirected :not-modified :robots-disallowed
                 :auth-required]]
        (when-not (contains? statuses k)
          (fail! f (str "fetch-status vocabulary missing " k)))))))

(defn fixture-provenance-chain [f]
  ;; v2 negative fixtures: a chain that invents a receipt id, trims the
  ;; tail, or is empty must be REFUSED, never silently derived.
  (let [base {:event-type :provider-named
              :fund-vehicle-entity-id "e-fund" :provider-entity-id "e-aud"
              :provider-role :auditor
              :announced-at "2026-08-30" :asserted-at "2026-09-01"
              :listing-kind {:kind :stated-in-fund-document}
              :as-stated-by-source? true}
        bad-events
        [(assoc base :event-id "ev-bad-1" :source-receipt-id "r-1"
                :provenance-chain ["r-invented"])
         (assoc base :event-id "ev-bad-2" :source-receipt-id "r-1"
                :provenance-chain [])
         (assoc base :event-id "ev-bad-3" :source-receipt-id "r-2"
                :provenance-chain ["r-2" "r-1"])]]
    ;; ev-bad-3: last element is NOT the source-receipt-id -> invalid.
    (doseq [ev bad-events]
      (let [{:keys [observations refusals]}
            (derive-observations contract receipts [ev])]
        (when-not (and (empty? observations)
                       (= 1 (count refusals))
                       (= :provenance-chain-invalid (:reason (first refusals))))
          (fail! f (str "invalid chain for " (:event-id ev)
                        " was not refused")))))
    (when-not (contains? (set (:invariants (:event-record contract)))
                         :provenance-chain-is-required-and-verified)
      (fail! f "contract must declare the provenance-chain invariant"))
    ;; a valid event's derived observation carries its chain exactly.
    (let [{:keys [observations]} (derive-observations contract receipts events)
          o (some #(when (= (:observation-id %) "o-ev-1") %) observations)]
      (when-not (contains? (set (map :provenance-chain observations)) ["r-1"])
        (fail! f "observation must carry its event's provenance-chain exactly"))
      (when (nil? o) (fail! f "ev-1 must derive a naming observation")))))

(defn fixture-unknown-filter-key [f]
  ;; v2: an unknown filter key is REJECTED (:rejected-filter), never
  ;; silently ignored (which would quietly widen the query).
  (let [known (set (:filter-keys-known [:provider-role :jurisdiction
                                        :listing-kind
                                        :fund-vehicle-entity-id
                                        :provider-entity-id]))
        request {:query-id "q-2" :observation-kind :provider-named-in-window
                 :window window
                 :filter {:engagement-verified-above 5}}
        unknown-keys (remove #(contains? known %) (keys (:filter request)))]
    (when-not (empty? unknown-keys)
      (when-not (contains? (:status-values (:query-readback contract))
                           :rejected-filter)
        (fail! f "unknown filter key could not be rejected: no status"))
      (when-not (= :unknown-filter-key-rejected-not-ignored
                   (get-in contract [:query-readback :strictness :rule]))
        (fail! f "contract must declare unknown-filter-key rejection")))))

(defn fixture-role-exact-filtering [f]
  ;; v2: a :provider-role filter matches the carried role exactly — an
  ;; :unstated role is never returned under a specific-role filter.
  (let [{:keys [observations]} (derive-observations contract receipts events)
        auditor-only (filter #(= :auditor (:provider-role %)) observations)
        unstated     (filter #(= :unstated (:provider-role %)) observations)]
    (when (empty? auditor-only)
      (fail! f "fixture sanity: no auditor namings derived"))
    (when (empty? unstated)
      (fail! f "fixture sanity: no unstated-role namings derived"))
    (when-not (= :filter-matches-carried-role-exactly
                 (get-in contract [:query-readback :strictness
                                   :provider-role-filter-rule]))
      (fail! f "contract must declare exact role filtering"))
    (when-not (= :filter-matches-carried-kind-exactly
                 (get-in contract [:query-readback :strictness
                                   :listing-kind-filter-rule]))
      (fail! f "contract must declare exact listing-kind filtering"))))

(def fixtures
  [["provenance" fixture-provenance]
   ["source-class" fixture-source-class]
   ["entity-separation" fixture-entity-separation]
   ["role-carried" fixture-role-carried]
   ["receipt-admission" fixture-receipt-admission]
   ["refusal-record" fixture-refusal-record]
   ["provenance-chain" fixture-provenance-chain]
   ["unknown-filter-key" fixture-unknown-filter-key]
   ["role-exact-filtering" fixture-role-exact-filtering]
   ["missingness" fixture-missingness]
   ["window-half-open" fixture-window-half-open]
   ["conflict-carry-both" fixture-conflict-carry-both]
   ["refresh-append-only" fixture-refresh-append-only]
   ["readback-shape" fixture-readback-shape]
   ["hyakka-proposal" fixture-hyakka-proposal]
   ["forbidden-fields" fixture-forbidden-fields]
   ["content-hash-determinism" fixture-content-hash-determinism]])

(doseq [[fname f] fixtures]
  (try
    (f fname)
    (println (str "ok   " fname))
    (catch :default e
      (fail! fname (str "fixture threw: " (.-message e)))
      (println (str "threw " fname)))))

(if (empty? @failures)
  (do (println "ALL FUND-SERVICE-PROVIDER FIXTURES GREEN")
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "FAIL " fixture ": " msg)))
      (js/process.exit 1)))
