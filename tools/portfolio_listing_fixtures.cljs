#!/usr/bin/env nbb
;; portfolio_listing_fixtures.cljs — deterministic offline fixtures for
;; the portfolio-listing-observation contract
;; (`capital-observation/portfolio-listing-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/portfolio_listing_fixtures.cljs [path/to/contract.edn]
;;
;; v2 (2026-09-04): fetch-status admission gate, required event-level
;; provenance chains, and strict readback (unknown filter keys rejected,
;; listing-kind filters match exactly). Negative fixtures construct
;; violating inputs and assert the derivation REFUSES them.

(ns portfolio-listing-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "portfolio-listing-observation.edn")))

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
;; A manager's first-party portfolio page lists "ALPHA" — a brand string
;; shared with an unrelated fund vehicle. Entity separation fixture.
(def receipts
  [(receipt "r-1" "https://example-manager.test/portfolio" :manager-first-party "en"
            "Our portfolio: ALPHA K.K., BETA Inc.")
   (receipt "r-2" "https://example-manager.test/portfolio" :manager-first-party "en"
            "Our portfolio: BETA Inc.") ; ALPHA removed in a later capture
   (receipt "r-3" "https://news.example.test/article" :news-report "en"
            "Rumor: ALPHA joined a fund portfolio")
   ;; v1.1 cross-source conflict: the official registry record for the
   ;; same jurisdiction does NOT show ALPHA as listed — a first-party
   ;; disagreement with r-1 about the same pair.
   (receipt "r-4" "https://registry.example.test/REG-F" :official-company-registry "ja"
            "REG-F: no portfolio-company listings recorded")
   ;; v2: a receipt whose fetch FAILED. Recorded verbatim, but it refuses
   ;; admission — it can never back a derived observation.
   (assoc (receipt "r-5" "https://example-fund.test/portfolio" :fund-first-party "en"
                   "ALPHA K.K. listed on Fund I portfolio (fetch failed)")
          :fetch-status :error)])

(def entities
  [{:entity-id "e-1" :entity-type :management-company :name "EXAMPLE MANAGER"
    :legal-name "Example Manager K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-M"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   ;; Same brand string "ALPHA", different types: must remain distinct ids.
   {:entity-id "e-2" :entity-type :company :name "ALPHA"
    :legal-name "ALPHA K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-C"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-3" :entity-type :fund-vehicle :name "ALPHA"
    :legal-name "ALPHA Fund I L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-F"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-4" :entity-type :company :name "BETA"
    :legal-name "BETA Inc." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-B"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

(def events
  [{:event-id "ev-1" :event-type :listed-on-portfolio-page
    :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
    :observed-at "2026-08-20" :asserted-at "2026-09-01"
    :listing {:kind :listed-on-portfolio-page :as-stated-by-source? true}
    :source-receipt-id "r-1" :provenance-chain ["r-1"]}
   ;; A name disappearing from the page is a NEW removal observation.
   {:event-id "ev-2" :event-type :removed-from-portfolio-page
    :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
    :observed-at "2026-08-31" :asserted-at "2026-09-01"
    :listing {:kind :removed-from-portfolio-page :as-stated-by-source? true}
    :source-receipt-id "r-2" :provenance-chain ["r-2"]}
   {:event-id "ev-3" :event-type :listed-on-portfolio-page
    :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-4"
    :observed-at "2026-08-20" :asserted-at "2026-09-01"
    ;; jurisdiction-not-in-receipt → flag, do not vanish
    :listing {:kind :unstated :as-stated-by-source? true}
    :source-receipt-id "r-2" :provenance-chain ["r-2"]}
   ;; v2: this event's ONLY receipt refused admission — deriving an
   ;; observation from it must be refused, with a refusal record.
   {:event-id "ev-4" :event-type :listed-on-portfolio-page
    :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
    :observed-at "2026-08-25" :asserted-at "2026-09-01"
    :listing {:kind :listed-on-portfolio-page :as-stated-by-source? true}
    :source-receipt-id "r-5" :provenance-chain ["r-5"]}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

;; ── v2 derivation model (deterministic, mirrors the contract) ───────
(defn admitted? [contract r]
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
          (not (some #(admitted? contract (get by-id %))
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
                   :observation-kind (get {:listed-on-portfolio-page
                                           :listed-on-portfolio-page-in-window
                                           :removed-from-portfolio-page
                                           :removed-from-portfolio-page-in-window}
                                          (:event-type ev))
                   :fund-vehicle-entity-id (:fund-vehicle-entity-id ev)
                   :portfolio-company-entity-id (:portfolio-company-entity-id ev)
                   :event-id (:event-id ev)
                   :value {:kind :listing-observation :basis #{:receipt-only}}
                   :missingness-flags (if (= :unstated (-> ev :listing :kind))
                                        #{:listing-kind-unstated} #{})
                   :provenance-chain (:provenance-chain ev)
                   :asserted-at "2026-09-01"})))
      {:observations [] :refusals []}
      events)))

;; v1.1: two first-party sources disagree about the same
;; (fund-vehicle, portfolio-company) pair in the same window. Both
;; receipts are carried; the conflict is never adjudicated.
(def conflict
  {:conflict-id "cf-1" :window window
   :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
   :competing-source-receipt-ids ["r-1" "r-4"]
   :conflict-kind :listed-vs-absent :observed-at "2026-09-01"
   :resolution :carry-both-never-resolve})

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  ;; Every event cites a receipt whose content-hash exists.
  (doseq [ev events]
    (let [r (some #(when (= (:receipt-id %) (:source-receipt-id ev)) %) receipts)]
      (when-not (and r (re-find #"^[0-9a-f]{64}$" (:content-hash r)))
        (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt"))))))

(defn fixture-discovery-only [f]
  ;; A discovery-only source (news report) can never back a derived
  ;; listing observation: only fund/manager/registry classes may.
  (let [allowed (set (:source-class-allow (:source-receipt contract)))]
    (when-not (and (contains? allowed :manager-first-party)
                   (contains? allowed :fund-first-party))
      (fail! f "first-party portfolio sources must be allowed"))
    (doseq [ev events]
      (let [r (some #(when (= (:receipt-id %) (:source-receipt-id ev)) %) receipts)]
        (when (contains? (set (:source-class-discovery-only (:source-receipt contract)))
                         (:source-class r))
          (fail! f (str "event " (:event-id ev) " backed by a discovery-only source")))))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities"))))))

(defn fixture-listing-vs-removal [f]
  ;; listing kinds must survive distinct — a removal never overwrites
  ;; the earlier listing, and both kinds exist in the contract.
  (let [ets (set (:event-types contract))]
    (when-not (and (contains? ets :listed-on-portfolio-page)
                   (contains? ets :removed-from-portfolio-page))
      (fail! f "listing/removal event kinds incomplete"))
    (when-not (some #(and (= (:event-id %) "ev-1")
                          (= (:event-type %) :listed-on-portfolio-page))
                    events)
      (fail! f "earlier listing observation must remain present"))
    (when-not (some #(and (= (:event-id %) "ev-2")
                          (= (:event-type %) :removed-from-portfolio-page))
                    events)
      (fail! f "removal must be recorded as its own event"))))

(defn fixture-not-ownership [f]
  ;; portfolio-listing-is-not-ownership-verification: the contract's
  ;; forbidden fields must structurally exclude ownership/verification.
  (let [forbidden (set (map keyword (map name (:forbidden-fields (:derived-observation contract)))))]
    (doseq [k [:ownership-stake :holding-verification :rank :score
               :returns :suitability :current-valuation]]
      (when-not (contains? forbidden k)
        (fail! f (str "forbidden field missing: " k))))))

(defn fixture-window [f]
  ;; [from, until) bounds: an event on until-1 is in; on until is out.
  (let [in? (fn [d] (and (>= (compare d (:from window)) 0)
                         (< (compare d (:until window)) 0)))]
    (when-not (in? "2026-08-31") (fail! f "2026-08-31 must be inside window"))
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
    (when-not (contains? (set (map :receipt-id refusals)) "r-5")
      (fail! f "ev-4 (admission-refused receipt r-5) must not derive an observation"))))

(defn fixture-fetch-admission [f]
  ;; v2: only :ok receipts are admitted. The refusal must produce a
  ;; refusal RECORD (not silence), and the refused receipt never backs
  ;; an observation. A re-fetch appends; it does not retro-invalidate.
  (let [{:keys [observations refusals]}
        (derive-observations contract receipts events)
        refused (some #(when (= (:receipt-id %) "r-5") %) refusals)]
    (when-not (and (= (count observations) 3)
                   (every? #(not= (:event-id %) "ev-4") observations))
      (fail! f "an admission-refused receipt backed an observation"))
    (when (nil? refused)
      (fail! f "admission refusal must produce a refusal record, not silence"))
    (when-not (and (= (:fetch-status refused) :error)
                   (= (:method/version refused) (:method/version contract)))
      (fail! f "refusal record must carry fetch-status and method/version"))
    (when-not (= :re-fetch-appends-new-receipt-plus-history
                 (get-in contract [:source-receipt :admission :re-fetch-rule]))
      (fail! f "re-fetch must append, never retro-invalidate"))
    (when-not (contains? (:status-values (:query-readback contract))
                         :admission-refused)
      (fail! f "readback must be able to answer :admission-refused"))
    (let [statuses (:fetch-status-values (:source-receipt contract))]
      (when-not (and (contains? statuses :ok) (contains? statuses :error))
        (fail! f "contract must declare the fetch-status vocabulary")))))

(defn fixture-provenance-chain [f]
  ;; v2 negative fixtures: a chain that invents a receipt id, trims the
  ;; head, or is empty must be REFUSED, never silently derived.
  (let [base {:event-type :listed-on-portfolio-page
              :fund-vehicle-entity-id "e-3" :portfolio-company-entity-id "e-2"
              :observed-at "2026-08-20" :asserted-at "2026-09-01"
              :listing {:kind :listed-on-portfolio-page :as-stated-by-source? true}}
        bad-events
        [(assoc base :event-id "ev-bad-1" :source-receipt-id "r-1"
                :provenance-chain ["r-invented"])
         (assoc base :event-id "ev-bad-2" :source-receipt-id "r-1"
                :provenance-chain [])
         (assoc base :event-id "ev-bad-3" :source-receipt-id "r-2"
                :provenance-chain ["r-1" "r-2"])]]
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

(defn fixture-listing-kind-readback [f]
  ;; v2: a :listing-kind filter matches the CARRIED kind exactly — a
  ;; removal observation is never returned under a listed filter
  ;; (listed vs removed never collapse in readback).
  (let [ev-2 (some #(when (= (:event-id %) "ev-2") %) events)
        listed? (fn [ev] (= :listed-on-portfolio-page (-> ev :listing :kind)))
        filtered (filter listed? [ev-2])]
    (when-not (empty? filtered)
      (fail! f "listing-kind filter collapsed removed into listed"))
    (when-not (= :filter-matches-carried-kind-exactly
                 (get-in contract [:query-readback :strictness
                                   :listing-kind-filter-rule]))
      (fail! f "contract must declare exact listing-kind filtering"))))

(defn fixture-unknown-filter-key [f]
  ;; v2: an unknown filter key is REJECTED (:rejected-filter), never
  ;; silently ignored (which would quietly widen the query).
  (let [request {:query-id "q-2" :observation-kind :listed-on-portfolio-page-in-window
                 :window window
                 :filter {:ownership-stake-greater-than 5}}
        known #{:jurisdiction :fund-vehicle-entity-id
                :portfolio-company-entity-id :listing-kind}
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
           :reason {:kind :source-updated} :changed-fields [:observation-kind]}]
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
  ;; A coverage record must separate observed from unmeasured, and an
  ;; unstated listing kind must flag, not vanish.
  (let [cov {:coverage-unit :jurisdiction :unit-key "JP" :observed-count 2
             :unmeasured-count 1 :window window
             :method/version (:method/version contract)}]
    (when-not (and (pos? (:observed-count cov))
                   (pos? (:unmeasured-count cov)))
      (fail! f "coverage must report unmeasured alongside observed")))
  (when-not (contains? (set (:flags (:missingness contract)))
                       :listing-kind-unstated)
    (fail! f "unstated listing kind must flag :listing-kind-unstated, not vanish"))
  ;; v1.1: a cross-source first-party conflict must flag, not silently
  ;; pick a side.
  (when-not (contains? (set (:flags (:missingness contract)))
                       :first-party-source-conflict)
    (fail! f "cross-source conflict must flag :first-party-source-conflict")))

(defn fixture-conflict-carried [f]
  ;; v1.1: every competing side backs to its own receipt by id, both
  ;; sides come from allowed first-party classes, and the declared
  ;; resolution is carry-both-never-resolve.
  (let [{:keys [schema conflict-kinds resolution]} (:conflict-observation contract)
        allowed (set (:source-class-allow (:source-receipt contract)))
        by-id (into {} (map (juxt :receipt-id identity) receipts))
        schema-keys (set schema)]
    (when-not (contains? schema-keys :competing-source-receipt-ids)
      (fail! f "conflict schema must carry competing-source-receipt-ids"))
    (when-not (contains? conflict-kinds (:conflict-kind conflict))
      (fail! f "conflict kind not in declared set"))
    (when-not (= resolution (:resolution conflict))
      (fail! f "conflict must carry-both-never-resolve"))
    (when-not (>= (count (:competing-source-receipt-ids conflict)) 2)
      (fail! f "a conflict needs at least two competing receipts"))
    (doseq [rid (:competing-source-receipt-ids conflict)]
      (let [r (get by-id rid)]
        (when-not r (fail! f (str "conflict receipt missing: " rid)))
        (when (and r (not (contains? allowed (:source-class r))))
          (fail! f (str "conflict receipt not from an allowed first-party class: " rid)))))))

(defn fixture-conflict-not-adjudicated [f]
  ;; The conflict record carries no winner/adjudication field, and the
  ;; contract's invariants state the conflict never resolves into an
  ;; ownership or current-holding claim.
  (let [co (:conflict-observation contract)]
    (when-not (= :carry-both-never-resolve (:resolution co))
      (fail! f "resolution must be :carry-both-never-resolve"))
    (when-not (contains? (set (:invariants co))
                         :conflict-is-observation-not-adjudication)
      (fail! f "missing invariant: conflict-is-observation-not-adjudication"))
    (when-not (contains? (set (:invariants co))
                         :conflict-never-resolves-into-ownership-or-current-holding)
      (fail! f "missing invariant: conflict-never-resolves-into-ownership-or-current-holding"))
    (doseq [banned [:winner :adjudicated :resolved-claim :verified-ownership]]
      (when (some #(= (keyword (name %)) banned) (:schema co))
        (fail! f (str "conflict schema carries an adjudication field: " banned))))))

(def fixtures
  {"provenance" fixture-provenance
   "discovery-only-source" fixture-discovery-only
   "entity-separation" fixture-entity-separation
   "listing-vs-removal" fixture-listing-vs-removal
   "not-ownership" fixture-not-ownership
   "window-bounds" fixture-window
   "derived-observation" fixture-derived-observation
   "fetch-admission" fixture-fetch-admission
   "provenance-chain" fixture-provenance-chain
   "listing-kind-readback" fixture-listing-kind-readback
   "unknown-filter-key" fixture-unknown-filter-key
   "refresh-history" fixture-refresh-history
   "readback" fixture-readback
   "coverage-missingness" fixture-coverage-missingness
   "conflict-carried" fixture-conflict-carried
   "conflict-not-adjudicated" fixture-conflict-not-adjudicated})

;; ── Run ─────────────────────────────────────────────────────────────
(defn run-all []
  (println (str "contract: " contract-path))
  (println (str "method/version: " (:method/version contract)))
  (doseq [[name f] fixtures]
    (try
      (f name)
      (println (str "  ok    " name))
      (catch :default e
        (fail! name (str "fixture threw: " (.-message e)))
        (println (str "  threw " name)))))
  (if (empty? @failures)
    (do (println "PASS: all portfolio-listing fixtures found nothing wrong")
        (js/process.exit 0))
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (js/process.exit 1))))

(run-all)
