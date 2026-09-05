#!/usr/bin/env nbb
;; co_investment_fixtures.cljs — deterministic offline fixtures for the
;; co-investment-observation contract
;; (capital-observation/co-investment-observation.edn).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/co_investment_fixtures.cljs [path/to/contract.edn]
;;
;; v2 (2026-09-04): fetch-status admission gate, required event-level
;; provenance chains, and strict readback (unknown filter keys rejected,
;; listing-kind filters match exactly). Negative fixtures construct
;; violating inputs and assert the derivation REFUSES them.

(ns co-investment-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "co-investment-observation.edn")))

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
;; A fund first-party page lists participants of round R-ALPHA; a manager
;; first-party page lists a DIFFERENT participant set for the same round
;; (receipt disagreement — carried, never merged). A news report naming
;; the same pair is discovery-only and backs nothing. Two entities share
;; the brand "OMEGA" (a company and a fund vehicle) — separation fixture.
;; v2: r-6 fetch FAILED — recorded verbatim, refuses admission.
(def receipts
  [(receipt "r-1" "https://omega-fund.example.test/rounds" :fund-first-party "en"
            "Round R-ALPHA participants: OMEGA K.K., Fund vehicle BETA-2")
   (receipt "r-2" "https://example-manager.test/syndicates" :manager-first-party "en"
            "Round R-ALPHA participants: OMEGA K.K., Fund vehicle BETA-2, LP GAMMA-P")
   ;; single-participant listing: produces no edge.
   (receipt "r-3" "https://delta-fund.example.test/rounds" :fund-first-party "en"
            "Round R-DELTA participants: OMEGA K.K.")
   (receipt "r-4" "https://news.example.test/omega-beta" :news-report "en"
            "OMEGA and BETA co-invested in R-ALPHA")
   (receipt "r-5" "https://omega-registry.example.test/entry" :official-company-registry "en"
            "OMEGA K.K. — registry record")
   ;; v2: fetch failed — refuses admission, never backs an edge.
   (assoc (receipt "r-6" "https://epsilon-fund.example.test/rounds" :fund-first-party "en"
                   "Round R-EPSILON participants: OMEGA K.K., Fund vehicle BETA-2")
          :fetch-status :error)])

(def entities
  [{:entity-id "e-1" :entity-type :company :name "OMEGA"
    :legal-name "OMEGA K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-O"
    :source-receipt-id "r-5" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   ;; Same brand string "OMEGA", different type: must remain a distinct id.
   {:entity-id "e-2" :entity-type :fund-vehicle :name "OMEGA"
    :legal-name "OMEGA Growth Fund I L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-OG1"
    :source-receipt-id "r-5" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-3" :entity-type :fund-vehicle :name "BETA"
    :legal-name "Fund vehicle BETA-2 L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-B2"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-4" :entity-type :limited-partner :name "GAMMA-P"
    :legal-name "GAMMA-P Pension Trust" :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-GP"
    :source-receipt-id "r-2" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

(def events
  [{:event-id "ev-1" :event-type :round-participants-listed
    :round-id "R-ALPHA" :announced-at "2026-08-10" :asserted-at "2026-09-01"
    :participant-entity-ids ["e-1" "e-3"]
    :listing-kind {:kind :named-investors}
    :as-stated-by-source? true
    :source-receipt-id "r-1" :provenance-chain ["r-1"]}
   ;; second allowed source, same round, DIFFERENT participant set:
   ;; recorded as its own event, never merged into ev-1.
   {:event-id "ev-2" :event-type :round-participants-listed
    :round-id "R-ALPHA" :announced-at "2026-08-12" :asserted-at "2026-09-01"
    :participant-entity-ids ["e-1" "e-3" "e-4"]
    :listing-kind {:kind :named-consortium}
    :as-stated-by-source? true
    :source-receipt-id "r-2" :provenance-chain ["r-2"]}
   ;; single-participant listing — no co-listing edge is derivable.
   {:event-id "ev-3" :event-type :round-participants-listed
    :round-id "R-DELTA" :announced-at "2026-08-20" :asserted-at "2026-09-01"
    :participant-entity-ids ["e-1"]
    :listing-kind {:kind :named-investors}
    :as-stated-by-source? true
    :source-receipt-id "r-3" :provenance-chain ["r-3"]}
   ;; v2: this event's ONLY receipt refused admission (r-6 :error) —
   ;; deriving an edge from it must be REFUSED with a refusal record.
   {:event-id "ev-4" :event-type :round-participants-listed
    :round-id "R-EPSILON" :announced-at "2026-08-25" :asserted-at "2026-09-01"
    :participant-entity-ids ["e-1" "e-3"]
    :listing-kind {:kind :named-investors}
    :as-stated-by-source? true
    :source-receipt-id "r-6" :provenance-chain ["r-6"]}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

(def entity-ids (set (map :entity-id entities)))
(defn receipt-by-id [id] (some #(when (= (:receipt-id %) id) %) receipts))

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
  an event with an invalid provenance chain is refused with
  :provenance-chain-invalid; an event whose receipt refuses admission is
  refused with a refusal record. Co-listing edges are derived pairwise
  from admitted, chain-valid events with >= 2 participants (unordered
  pairs, sorted canonically so the edge is symmetric). No third outcome."
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
          (let [ids (sort (:participant-entity-ids ev))
                pairs (when (>= (count ids) 2)
                        (for [i (range (count ids))
                              j (range (inc i) (count ids))]
                          [(nth ids i) (nth ids j)]))]
            (if (empty? pairs)
              (update acc :observations conj
                      {:observation-id (str "o-" (:event-id ev))
                       :method/version (:method/version contract)
                       :window window
                       :round-id (:round-id ev)
                       :edge nil
                       :missingness-flags #{:single-participant-only}
                       :provenance-chain (:provenance-chain ev)
                       :asserted-at "2026-09-01"})
              (reduce (fn [acc' [a b]]
                        (update acc' :observations conj
                                {:observation-id (str "o-" (:event-id ev) "-" a "-" b)
                                 :method/version (:method/version contract)
                                 :window window
                                 :round-id (:round-id ev)
                                 :edge {:kind :co-listed-in-same-round
                                        :entity-a a :entity-b b
                                        :symmetric? true}
                                 :listing-kind (:listing-kind ev)
                                 :source-receipt-id (:source-receipt-id ev)
                                 :missingness-flags (cond-> #{}
                                                      (nil? (-> ev :listing-kind :kind))
                                                      (conj :listing-kind-unstated))
                                 :provenance-chain (:provenance-chain ev)
                                 :asserted-at "2026-09-01"}))
                      acc pairs))))
        )
      {:observations [] :refusals []}
      events)))

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  (doseq [ev events]
    (let [r (receipt-by-id (:source-receipt-id ev))]
      (when-not (and r (re-find (re-pattern "^[0-9a-f]{64}$") (:content-hash r)))
        (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt"))))))

(defn fixture-receipt-admission [f]
  ;; v2: only :ok receipts are admitted. The refusal must produce a
  ;; refusal RECORD (not silence), and the refused receipt never backs
  ;; an edge. A re-fetch appends; it does not retro-invalidate.
  (let [{:keys [observations refusals]}
        (derive-observations contract receipts events)
        refused (some #(when (= (:receipt-id %) "r-6") %) refusals)]
    (when-not (every? #(not= (:round-id %) "R-EPSILON") observations)
      (fail! f "an admission-refused receipt backed an edge observation"))
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
    (let [statuses (:fetch-status-values (:source-receipt contract))]
      (when-not (and (contains? statuses :ok) (contains? statuses :error))
        (fail! f "contract must declare the fetch-status vocabulary")))
    ;; discovery-only sources never back an event (r-4 backs nothing here).
    (let [discovery (set (:source-class-discovery-only (:source-receipt contract)))
          r4 (receipt-by-id "r-4")]
      (when-not (contains? discovery (:source-class r4))
        (fail! f "fixture sanity: r-4 must be a discovery-only receipt"))
      (doseq [ev events]
        (when (contains? discovery (:source-class (receipt-by-id (:source-receipt-id ev))))
          (fail! f (str "event " (:event-id ev) " backed by a discovery-only source")))))))

(defn fixture-provenance-chain [f]
  ;; v2 negative fixtures: a chain that invents a receipt id, trims the
  ;; head, or is empty must be REFUSED, never silently derived.
  (let [base {:event-type :round-participants-listed
              :round-id "R-BAD" :announced-at "2026-08-10" :asserted-at "2026-09-01"
              :participant-entity-ids ["e-1" "e-3"]
              :listing-kind {:kind :named-investors}
              :as-stated-by-source? true}
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
    (when-not (contains? (set (:invariants (:event-record contract)))
                         :provenance-chain-is-required-and-verified)
      (fail! f "contract must declare the provenance-chain invariant"))
    ;; a valid event's derived observation carries its chain exactly.
    (let [{:keys [observations]} (derive-observations contract receipts events)
          o (some #(when (= (:round-id %) "R-ALPHA") %) observations)]
      (when-not (contains? (set (map :provenance-chain observations)) ["r-1"])
        (fail! f "observation must carry its event's provenance-chain exactly"))
      (when (nil? o) (fail! f "R-ALPHA must derive an observation")))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities")))))
  ;; participant ids must resolve to declared entities (participant-ids-
  ;; resolve-to-declared-entities).
  (doseq [ev events]
    (doseq [pid (:participant-entity-ids ev)]
      (when-not (contains? entity-ids pid)
        (fail! f (str "event " (:event-id ev) " participant " pid " unresolved"))))))

(defn fixture-edge-is-adjacency-not-more [f]
  ;; The derived edge must be co-listing adjacency, symmetric, and
  ;; structurally excluded from influence/relationship semantics.
  (let [edge (:edge (:derived-observation contract))]
    (when-not (= :co-listed-in-same-round (:kind edge))
      (fail! f "edge kind must be :co-listed-in-same-round"))
    (when-not (:symmetric? edge)
      (fail! f "co-listing edge must be symmetric/unordered"))
    (when-not (contains? (set (:not edge)) :influence)
      (fail! f "edge semantics must exclude :influence")))
  ;; forbidden fields must structurally exclude ranking/score/influence.
  (let [forbidden (set (map keyword (map name
                          (:forbidden-fields (:derived-observation contract)))))]
    (doseq [k [:centrality :rank :score :degree-count :network-strength
               :influence :syndication-pattern :valuation :returns
               :ownership-stake :suitability :recommendation]]
      (when-not (contains? forbidden k)
        (fail! f (str "forbidden field missing: " k)))))
  ;; the derived edges are canonical unordered pairs — (a,b) with a<=b.
  (let [{:keys [observations]} (derive-observations contract receipts events)
        bad (filter (fn [o] (let [e (:edge o)]
                              (and e (pos? (compare (:entity-a e) (:entity-b e))))))
                    observations)]
    (when-not (empty? bad)
      (fail! f "derived edge is not a canonical unordered pair"))))

(defn fixture-disagreement-recorded-not-merged [f]
  ;; Two allowed receipts list different participant sets for R-ALPHA:
  ;; both events exist, neither replaces the other, and the contract's
  ;; refresh rule declares disagreements are never merged.
  (let [alpha (filter #(= "R-ALPHA" (:round-id %)) events)]
    (when-not (= 2 (count alpha))
      (fail! f "both R-ALPHA participant listings must be carried"))
    (when-not (not= (set (:participant-entity-ids (first alpha)))
                    (set (:participant-entity-ids (second alpha))))
      (fail! f "fixture sanity: R-ALPHA listings must differ"))
    (doseq [ev alpha]
      (when-not (= 1 (count (:provenance-chain ev)))
        (fail! f (str "disagreeing event " (:event-id ev)
                      " must cite only its own receipt"))))))

(defn fixture-missing-is-unmeasured [f]
  ;; Single-participant listing yields no edge (single-participant-only
  ;; flag path) and the contract's missingness rule is
  ;; :missing-is-unmeasured.
  (when-not (= :missing-is-unmeasured (:rule (:missingness contract)))
    (fail! f "missingness rule must be :missing-is-unmeasured"))
  (when-not (contains? (set (:flags (:missingness contract)))
                       :single-participant-only)
    (fail! f "missingness must flag :single-participant-only"))
  (when-not (some #(= 1 (count (:participant-entity-ids %))) events)
    (fail! f "fixture sanity: a single-participant listing must exist"))
  (when-not (= :valid-only-inside-window
               (:window-scoping (:derived-observation contract)))
    (fail! f "observations must be scoped to their window"))
  ;; the derivation flags the single-participant event, it does not vanish.
  (let [{:keys [observations]} (derive-observations contract receipts events)]
    (when-not (some #(and (= (:round-id %) "R-DELTA")
                          (contains? (:missingness-flags %) :single-participant-only))
                    observations)
      (fail! f "single-participant listing must flag :single-participant-only"))))

(defn fixture-window-bounds [f]
  ;; [from, until) bounds: an event on until-1 is in; on until is out.
  (let [in? (fn [d] (and (>= (compare d (:from window)) 0)
                         (< (compare d (:until window)) 0)))]
    (when-not (in? "2026-08-31") (fail! f "2026-08-31 must be inside window"))
    (when (in? "2026-09-01") (fail! f "until is exclusive; 2026-09-01 must be out"))))

(defn fixture-listing-kind-readback [f]
  ;; v2: a :listing-kind filter matches the CARRIED kind exactly — a
  ;; :named-consortium observation is never returned under a
  ;; :named-investors filter (kinds never collapse in readback).
  (let [{:keys [observations]} (derive-observations contract receipts events)
        ev-2 (some #(when (= (:event-id %) "ev-2") %) events)
        ;; filter as the readback would: exact match on the carried kind.
        investors (filter #(= :named-investors (-> % :listing-kind :kind))
                          observations)
        consortium (filter #(= :named-consortium (-> % :listing-kind :kind))
                           observations)]
    ;; the exact filter never leaks a :named-consortium edge into the
    ;; :named-investors result set (and vice versa).
    (when (some #(= :named-consortium (-> % :listing-kind :kind)) investors)
      (fail! f "listing-kind filter collapsed distinct kinds"))
    (when (some #(= :named-investors (-> % :listing-kind :kind)) consortium)
      (fail! f "listing-kind filter collapsed distinct kinds"))
    ;; both filtered sets are non-empty and disjoint (fixture sanity).
    (when-not (and (seq investors) (seq consortium))
      (fail! f "fixture sanity: both listing kinds must exist in observations"))
    (when-not (= :filter-matches-carried-kind-exactly
                 (get-in contract [:query-readback :strictness
                                   :listing-kind-filter-rule]))
      (fail! f "contract must declare exact listing-kind filtering"))))

(defn fixture-unknown-filter-key [f]
  ;; v2: an unknown filter key is REJECTED (:rejected-filter), never
  ;; silently ignored (which would quietly widen the query).
  (let [request {:question-id "q-2"
                 :question :participants-together-in-round
                 :window window
                 :filter {:network-strength-above 5}}
        known #{:round-id :entity-id :listing-kind}
        unknown-keys (remove #(contains? known %) (keys (:filter request)))]
    (when-not (empty? unknown-keys)
      (when-not (contains? (:status-values (:query-readback contract))
                           :rejected-filter)
        (fail! f "unknown filter key could not be rejected: no status"))
      (when-not (= :unknown-filter-key-rejected-not-ignored
                   (get-in contract [:query-readback :strictness :rule]))
        (fail! f "contract must declare unknown-filter-key rejection")))))

(defn fixture-refresh-history [f]
  ;; Append-only. Re-observation is a new receipt + a new history entry;
  ;; an earlier observation is never deleted or reinterpreted.
  (when-not (= :append-only-never-rewrite
               (:rule (:refresh/history contract)))
    (fail! f "refresh history must be append-only-never-rewrite"))
  (when-not (= :re-fetch-appends-new-receipt-plus-history
               (get-in contract [:receipt-admission :re-fetch-rule]))
    (fail! f "re-fetch must append a new receipt plus history entry")))

(defn fixture-readback-shape [f]
  ;; The readback always carries coverage + missingness + method version
  ;; and answers :out-of-window outside the window.
  (let [qr (:query-readback contract)]
    (doseq [k [:coverage :missingness-flags :method-version :window
               :provenance-chain]]
      (when-not (some #(= k %) (:schema qr))
        (fail! f (str "readback schema missing: " k))))
    (when-not (= :out-of-window-outside-window (:window-scoping qr))
      (fail! f "readback must answer :out-of-window outside its window"))))

(defn fixture-proposal-questions-only [f]
  (let [p (:proposal contract)]
    (when-not (= :auditable-question (:kind p))
      (fail! f "proposal must be :auditable-question"))
    (when (empty? (:example-questions p))
      (fail! f "proposal must carry example questions"))
    (let [never (set (:never p))]
      (doseq [k [:investment-advice :ranking :outreach :solicitation
                 :personal-profiling]]
        (when-not (contains? never k)
          (fail! f (str "proposal :never missing: " k)))))))

(def all-fixtures
  [[:provenance fixture-provenance]
   [:receipt-admission fixture-receipt-admission]
   [:provenance-chain fixture-provenance-chain]
   [:entity-separation fixture-entity-separation]
   [:edge-is-adjacency-not-more fixture-edge-is-adjacency-not-more]
   [:disagreement-recorded-not-merged fixture-disagreement-recorded-not-merged]
   [:missing-is-unmeasured fixture-missing-is-unmeasured]
   [:window-bounds fixture-window-bounds]
   [:listing-kind-readback fixture-listing-kind-readback]
   [:unknown-filter-key fixture-unknown-filter-key]
   [:refresh-history fixture-refresh-history]
   [:readback-shape fixture-readback-shape]
   [:proposal-questions-only fixture-proposal-questions-only]])

(defn -main [& _]
  (doseq [[name f] all-fixtures]
    (let [before (count @failures)]
      (try
        (f name)
        (println (str "[" name "] "
                      (if (= before (count @failures)) "ok" "VIOLATIONS")))
        (catch :default e
          (fail! name (str "fixture threw: " (.-message e)))
          (println (str "[" name "] THREW"))))
      (flush)))
  (if (empty? @failures)
    (do (println (str "OK: " (count all-fixtures)
                      " co-investment fixtures ran; 0 violations"))
        0)
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL [" fixture "] " msg)))
        (println (str "FAILED: " (count @failures) " violation(s)"))
        1)))

(js/process.exit (-main))
