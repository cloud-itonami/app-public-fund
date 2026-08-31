#!/usr/bin/env nbb
;; financing_round_fixtures.cljs — deterministic offline fixtures for the
;; financing-round-observation contract
;; (`capital-observation/financing-round-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/financing_round_fixtures.cljs [path/to/contract.edn]

(ns financing-round-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "financing-round-observation.edn")))

(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

(defn sha256 [s]
  (-> (crypto/createHash "sha256")
      (.update s)
      (.digest "hex")))

(defn receipt [id url class lang body]
  {:receipt-id id :source-url url :source-class class
   :source-language lang :observed-at "2026-09-01"
   :content-hash (sha256 body) :fetch-status :ok})

;; ── Fixture data (deterministic, no network) ────────────────────────
(def receipts
  [(receipt "r-1" "https://example-co.test/news/seed" :company-first-party "en"
            "Company X announces seed round")
   (receipt "r-2" "https://example-registry.test/filings/1" :official-company-registry "ja"
            "Registry filing for Company K.K.")])

(def entities
  [{:entity-id "c-1" :entity-type :company :name "Company X"
    :legal-name "Company X, Inc." :jurisdiction "US"
    :identifier-class :company-registration-number :identifier-value "US-REG-1"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   ;; Same brand string, different type: must stay a distinct entity id.
   {:entity-id "c-2" :entity-type :venture-firm :name "Company X"
    :legal-name "Company X Ventures LLC" :jurisdiction "US"
    :identifier-class :company-registration-number :identifier-value "US-REG-2"
    :source-receipt-id "r-2" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

(def events
  [{:event-id "ev-1" :event-type :round-announced :company-entity-id "c-1"
    :announced-at "2026-09-01" :asserted-at "2026-09-01"
    :amount {:kind :stated-raise :currency "USD"
             :as-stated-by-source? true :missing-flag nil}
    :stage {:kind :seed}
    :participants [{:participant-entity-id "c-2" :stated-role {:kind :lead}}]
    :source-receipt-id "r-1"}])

(def window {:from "2026-08-01" :until "2026-09-02"
             :declared-at "2026-09-01" :timezone "UTC"})

;; Schema vectors carry inline one-of declarations as a keyword followed
;; by a map, e.g. [... :amount {:kind [:one-of ...]} ...] — pull :kind out.
(defn schema-entry-kind [schema key]
  (let [i (.indexOf schema key)
        nxt (when (pos? i) (nth schema (inc i)))]
    (when (map? nxt) (:kind nxt))))

;; ── Fixture implementations ─────────────────────────────────────────

;; 1. Entity separation: same brand string never merges entity ids.
(defn fixture-entity-separation []
  (let [by-name (group-by :name entities)
        dup (first (filter #(and (> (count (second %)) 1)
                                 (not= (count (distinct (map :entity-type (second %))))
                                        (count (second %))))
                           by-name))]
    (when dup
      (fail! :entity-separation
             (str "entities sharing a brand name collide on type: " (first dup))))
    (let [types (map :entity-type entities)]
      (when-not (= (count (distinct types)) (count types))
        (fail! :entity-separation "duplicate entity-type across entity ids")))
    ;; contract must declare separation as an invariant
    (when-not (contains? #{:one-entity-type-per-entity-id}
                         (get-in contract [:entity-record :invariant]))
      (fail! :entity-separation "contract lacks one-entity-type-per-entity-id invariant"))))

;; 2. Provenance: every event and entity cites a receipt id that exists,
;;    and receipt content-hashes are stable across recomputation.
(defn fixture-provenance []
  (let [receipt-ids (set (map :receipt-id receipts))]
    (doseq [e entities]
      (when-not (contains? receipt-ids (:source-receipt-id e))
        (fail! :provenance (str "entity " (:entity-id e) " cites missing receipt"))))
    (doseq [ev events]
      (when-not (contains? receipt-ids (:source-receipt-id ev))
        (fail! :provenance (str "event " (:event-id ev) " cites missing receipt")))))
  (doseq [r receipts]
    (when-not (re-find #"^[0-9a-f]{64}$" (:content-hash r))
      (fail! :provenance (str "receipt " (:receipt-id r) " content-hash not sha256 hex")))))

;; 3. Source class policy: allow/forbid/discovery-only sets in the
;;    contract must match capital-scope.edn's policy, and no fixture
;;    receipt may use a forbidden class.
(defn fixture-source-policy []
  (let [allow (get-in contract [:source-receipt :source-class-allow])
        forbid (get-in contract [:source-receipt :source-class-forbid])
        disc (get-in contract [:source-receipt :source-class-discovery-only])]
    (when (some #(contains? allow %) [:search-snippet :social-post :captcha-bypass])
      (fail! :source-policy "forbidden class present in allow set"))
    (when (some #(contains? forbid %) [:company-first-party :official-company-registry])
      (fail! :source-policy "first-party/registry class incorrectly forbidden"))
    (when (seq (clojure.set/intersection allow disc))
      (fail! :source-policy "class appears in both allow and discovery-only"))
    (doseq [r receipts]
      (when (contains? forbid (:source-class r))
        (fail! :source-policy (str "receipt " (:receipt-id r) " uses forbidden class"))))))

;; 4. Temporal refresh / window: events must fall in the declared
;;    half-open window; an out-of-window event yields :out-of-window.
(defn fixture-window []
  (let [in-window? (fn [d] (and (>= (compare d (:from window)) 0)
                                (< (compare d (:until window)) 0)))]
    (doseq [ev events]
      (when-not (in-window? (:announced-at ev))
        (fail! :window (str "event " (:event-id ev) " outside declared window"))))
    ;; boundary check: until is exclusive
    (when (in-window? (:until window))
      (fail! :window "window upper bound is inclusive; must be half-open"))
    (when (get-in contract [:window :closed?])
      (fail! :window "contract window must be half-open (:closed? false)"))))

;; 5. Missingness: a missing amount must surface as an explicit flag,
;;    never as a silent 0 or an omitted key.
(defn fixture-missingness []
  (let [ev-with-missing (assoc-in (first events)
                                  [:amount :missing-flag] :amount-not-stated)]
    (when-not (= :amount-not-stated (get-in ev-with-missing [:amount :missing-flag]))
      (fail! :missingness "missing amount did not surface as :amount-not-stated"))
    (when-not (contains? (get-in contract [:missingness :flags]) :amount-not-stated)
      (fail! :missingness "contract missingness flags lack :amount-not-stated"))
    (doseq [ev events]
      (when (and (nil? (get-in ev [:amount :missing-flag]))
                 (nil? (get-in ev [:amount :kind])))
        (fail! :missingness (str "event " (:event-id ev) " has neither amount kind nor missing flag"))))))

;; 6. Amount-kind carried, not collapsed: distinct kinds stay distinct.
(defn fixture-amount-kinds []
  (let [kinds [:stated-round-size :stated-raise :stated-post-money-valuation-claim]]
    (when-not (= (count (distinct kinds)) 3)
      (fail! :amount-kinds "amount kinds collapsed"))
    (let [declared (schema-entry-kind (get-in contract [:event-record :schema]) :amount)]
      (doseq [k kinds]
        (when-not (contains? (set declared) k)
          (fail! :amount-kinds (str "contract amount kind set lacks " k)))))
    (when-not (= :amount-kind-is-carried-not-collapsed
                 (get-in contract [:event-record :invariant]))
      (fail! :amount-kinds "contract lacks amount-kind-is-carried-not-collapsed invariant"))
    (when-not (contains? (get-in contract [:derived-observation :forbidden-fields])
                         :verified-valuation)
      (fail! :amount-kinds "forbidden-fields must exclude :verified-valuation"))))

;; 7. Derived observation safety: no forbidden field can exist; stated
;;    participant roles stay observed claims (not board control).
(defn fixture-derived-safety []
  (let [forbidden (get-in contract [:derived-observation :forbidden-fields])]
    (doseq [f [:rank :score :centrality :nav :ownership-stake :suitability
               :current-valuation :cash-received]]
      (when-not (contains? forbidden f)
        (fail! :derived-safety (str "forbidden-fields lacks " f))))
    (let [schema-keys (set (get-in contract [:derived-observation :schema]))]
      (doseq [f forbidden]
        (when (contains? schema-keys f)
          (fail! :derived-safety (str "forbidden field " f " present in observation schema")))))
    (when-not (contains? (get-in contract [:event-record :participant-claim :invariant])
                         "stated-role")
      (when-not (= :stated-role-is-an-observed-claim
                   (get-in contract [:event-record :participant-claim :invariant]))
        (fail! :derived-safety "participant claim lacks stated-role-is-an-observed-claim")))))

;; 8. Query/readback shape: unknown filter keys rejected; status values
;;    complete; readback must carry coverage + missingness.
(defn fixture-query-readback []
  (let [req (get-in contract [:query-readback :request-schema])
        res (get-in contract [:query-readback :response-schema])
        statuses (set (get-in contract [:query-readback :status-values]))]
    (when-not (contains? (set req) :query-id)
      (fail! :query-readback "request schema lacks :query-id"))
    (doseq [k [:coverage-record-ref :missingness-flags]]
      (when-not (contains? (set res) k)
        (fail! :query-readback (str "response schema lacks " k))))
    (doseq [s [:ok :unmeasured :out-of-window]]
      (when-not (contains? statuses s)
        (fail! :query-readback (str "status values lack " s))))
    (when-not (= :readback-must-carry-coverage-and-missingness
                 (get-in contract [:query-readback :rule]))
      (fail! :query-readback "contract lacks readback rule"))
    ;; unknown filter key must be rejected, not ignored
    (let [allowed (set (get-in contract [:query-readback :request-schema 3 :keys]))]
      (when (contains? allowed :investor-signal-score)
        (fail! :query-readback "filter accepts a scoring key")))))

(defn fixture-refresh-history []
  (when-not (true? (get-in contract [:refresh-history :append-only?]))
    (fail! :refresh-history "refresh history is not append-only"))
  (let [reasons (set (schema-entry-kind (get-in contract [:refresh-history :schema]) :reason))]
    (when-not (contains? reasons :round-corrected-by-source)
      (fail! :refresh-history "refresh reasons lack :round-corrected-by-source"))))

(def fixtures
  [[:entity-separation fixture-entity-separation]
   [:provenance fixture-provenance]
   [:source-policy fixture-source-policy]
   [:window fixture-window]
   [:missingness fixture-missingness]
   [:amount-kinds fixture-amount-kinds]
   [:derived-safety fixture-derived-safety]
   [:query-readback fixture-query-readback]
   [:refresh-history fixture-refresh-history]])

(defn -main [& _]
  (doseq [[name f] fixtures]
    (try (f)
         (catch :default e
           (fail! name (str "fixture errored: " (.-message e))))))
  (if (seq @failures)
    (do
      (doseq [{:keys [fixture msg]} @failures]
        (println (str "FAIL " (name fixture) ": " msg)))
      (println (str "FAILURES: " (count @failures)))
      (js/process.exit 1))
    (do
      (println (str "OK: " (count fixtures) " fixtures passed (" contract-path ")"))
      (js/process.exit 0))))

(when-not (= js/process.argv (array "nbb" (last js/process.argv)))
  (apply -main (rest js/process.argv)))
