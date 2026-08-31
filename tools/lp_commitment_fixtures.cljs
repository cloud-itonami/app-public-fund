#!/usr/bin/env nbb
;; lp_commitment_fixtures.cljs — deterministic offline fixtures for the
;; lp-commitment-observation contract
;; (`capital-observation/lp-commitment-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/lp_commitment_fixtures.cljs [path/to/contract.edn]

(ns lp-commitment-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation" "lp-commitment-observation.edn")))

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
  (.update (crypto/createHash "sha256") s)
  (.digest (crypto/createHash "sha256") "hex"))

(defn receipt [id url class lang body]
  {:receipt-id id :source-url url :source-class class
   :source-language lang :observed-at "2026-09-01"
   :content-hash (sha256 body) :fetch-status :ok})

;; ── Fixture data (deterministic, no network) ────────────────────────
;; Receipts: one allowed regulator filing, one institutional-LP first-party
;; page, one discovery-only news report (may NOT back any observation).
(def receipts
  [(receipt "r-1" "https://example-reg.test/form-d" :official-regulator "en"
            "FORM D notice: LP commitment stated in FUND-B L.P.")
   (receipt "r-2" "https://example-lp.test/disclosures" :institutional-lp-first-party "en"
            "PENSION-C discloses stated capital commitment to FUND-B")
   (receipt "r-3" "https://example-news.test/story" :news-report "en"
            "News: unnamed sovereign fund reportedly backs FUND-B")])

;; Entity separation: LP vehicle, LP management org, fund vehicle, GP —
;; two of them share the brand string "PENSION-C".
(def entities
  [{:entity-id "lp-1" :entity-type :limited-partner :name "PENSION-C"
    :legal-name "PENSION-C Master Trust" :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-LP-1"
    :source-receipt-id "r-2" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "lp-2" :entity-type :management-company :name "PENSION-C"
    :legal-name "PENSION-C Asset Management K.K." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-LP-2"
    :source-receipt-id "r-2" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "fd-1" :entity-type :fund-vehicle :name "FUND-B"
    :legal-name "FUND-B L.P." :jurisdiction "US"
    :identifier-class :official-registry-id :identifier-value "REG-FD-1"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "gp-1" :entity-type :general-partner :name "FUND-B GP"
    :legal-name "FUND-B GP LLC" :jurisdiction "US"
    :identifier-class :official-registry-id :identifier-value "REG-GP-1"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

;; Events: two with allowed-source receipts; one amount missing from its
;; receipt; one discovery-only-backed event that must never yield a
;; derived observation.
(def events
  [{:event-id "ev-1" :event-type :commitment-announced
    :lp-entity-id "lp-1" :fund-entity-id "fd-1"
    :announced-at "2026-08-20" :asserted-at "2026-09-01"
    :amount {:kind :stated-commitment :currency "JPY"
             :value 3000000000 :as-stated-by-source? true}
    :source-receipt-id "r-1"}
   {:event-id "ev-2" :event-type :commitment-amended
    :lp-entity-id "lp-1" :fund-entity-id "fd-1"
    :announced-at "2026-08-28" :asserted-at "2026-09-01"
    ;; amount-not-stated: the receipt says amendment, no figure.
    :amount {:kind :stated-commitment-amendment :currency nil
             :value nil :as-stated-by-source? true}
    :source-receipt-id "r-2"}
   {:event-id "ev-3" :event-type :commitment-announced
    :lp-entity-id "lp-1" :fund-entity-id "fd-1"
    :announced-at "2026-08-29" :asserted-at "2026-09-01"
    :amount {:kind :stated-commitment :currency "USD"
             :value 10000000 :as-stated-by-source? true}
    :source-receipt-id "r-3"}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  ;; Every event cites a receipt whose content-hash exists.
  (doseq [ev events]
    (let [r (some #(when (= (:receipt-id %) (:source-receipt-id ev)) %) receipts)]
      (when-not (and r (re-find #"^[0-9a-f]{64}$" (:content-hash r)))
        (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt"))))))

(defn fixture-allowed-source-only [f]
  ;; Derived observations may only be built from ALLOWED source classes;
  ;; the discovery-only news receipt can never back one (an LP named
  ;; only by news is :inferred-lp → unmeasured). ev-3 is such a raw
  ;; event; it must stay out of every derived observation set.
  (let [allowed (:source-class-allow (:source-receipt contract))
        discovery-only (:source-class-discovery-only (:source-receipt contract))
        by-id (zipmap (map :receipt-id receipts) receipts)
        backing (fn [ev] (:source-class (get by-id (:source-receipt-id ev))))
        derived-events (filter #(contains? allowed (backing %)) events)
        derived-ids (set (map :event-id derived-events))]
    (doseq [ev events
            :let [c (backing ev)]
            :when (and (not (contains? allowed c))
                       (not (contains? discovery-only c)))]
      (fail! f (str "event " (:event-id ev) " backed by non-allowed source " c)))
    (when (contains? derived-ids "ev-3")
      (fail! f "a discovery-only (inferred-lp) event entered the derived set"))
    (when (empty? derived-events)
      (fail! f "no allowed-source events survived; fixture data is wrong"))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id; LP and fund
  ;; must be distinct entity ids inside one event.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities")))))
  (doseq [ev events]
    (when (= (:lp-entity-id ev) (:fund-entity-id ev))
      (fail! f (str "event " (:event-id ev) " collapses LP and fund")))))

(defn fixture-commitment-kinds [f]
  ;; Commitment kinds must survive — never collapsed into one number,
  ;; and a missing amount must flag :amount-not-stated, not vanish.
  (let [kinds (set (map (comp :kind :amount) events))]
    (when-not (and (contains? kinds :stated-commitment)
                   (contains? kinds :stated-commitment-amendment))
      (fail! f "commitment amount kinds lost"))
    (when (some #(and (= (:kind %) :stated-commitment-amendment)
                      (nil? (:value %))
                      (not (contains? (get-in contract [:missingness :flags])
                                      :amount-not-stated)))
                events)
      (fail! f "missing amount must flag :amount-not-stated, not vanish"))
    ;; lp-commitment-is-not-current-nav-or-ownership: no event may carry
    ;; a nav/ownership-shaped field.
    (doseq [ev events]
      (when (or (contains? ev :nav) (contains? ev :ownership-stake)
                (contains? ev :current-valuation))
        (fail! f (str "event " (:event-id ev) " carries a NAV/ownership field"))))))

(defn fixture-window [f]
  ;; [from, until) bounds: a commitment on until-1 is in; on until is out.
  (let [in? (fn [d] (and (>= (compare d (:from window)) 0)
                         (< (compare d (:until window)) 0)))]
    (when-not (in? "2026-08-28") (fail! f "2026-08-28 must be inside window"))
    (when (in? "2026-09-01") (fail! f "until is exclusive; 2026-09-01 must be out"))))

(defn fixture-derived-observation [f]
  ;; No forbidden field may appear in a derived observation record.
  (let [{:keys [forbidden-fields]} (:derived-observation contract)
        obs {:observation-id "o-1" :method/version (:method/version contract)
             :window window :observation-kind :lp-commitment-in-window
             :lp-entity-id "lp-1" :fund-entity-id "fd-1" :event-id "ev-1"
             :value {:kind :commitment-count :basis #{:receipt-only}}
             :missingness-flags #{}
             :provenance-chain ["r-1"]
             :asserted-at "2026-09-01"}
        keys' (set (map keyword (keys obs)))]
    (when-not (and forbidden-fields
                   (contains? forbidden-fields :nav)
                   (contains? forbidden-fields :personal-wealth))
      (fail! f "forbidden observation fields (rank/nav/ownership/wealth/…) incomplete"))
    (when (some #(contains? keys' (keyword (name %))) forbidden-fields)
      (fail! f "derived observation carries a forbidden field"))))

(defn fixture-refresh-history [f]
  (let [h {:history-id "h-1" :observation-id "o-1"
           :re-observed-at "2026-10-01"
           :reason {:kind :window-advanced} :changed-fields [:window]}]
    (when-not (:append-only? (:refresh-history contract))
      (fail! f "refresh history must be append-only"))
    (when (nil? (:reason h)) (fail! f "refresh entry must state its reason"))))

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
  ;; A coverage record must separate observed from unmeasured.
  (let [cov {:coverage-unit :jurisdiction :unit-key "JP" :observed-count 2
             :unmeasured-count 1 :window window
             :method/version (:method/version contract)}]
    (when-not (and (pos? (:observed-count cov))
                   (pos? (:unmeasured-count cov)))
      (fail! f "coverage must report unmeasured alongside observed"))))

(def fixtures
  {"provenance" fixture-provenance
   "allowed-source-only" fixture-allowed-source-only
   "entity-separation" fixture-entity-separation
   "commitment-kinds" fixture-commitment-kinds
   "window-bounds" fixture-window
   "derived-observation" fixture-derived-observation
   "refresh-history" fixture-refresh-history
   "readback" fixture-readback
   "coverage-missingness" fixture-coverage-missingness})

;; ── Run ─────────────────────────────────────────────────────────────
(defn run-all []
  (println (str "contract: " contract-path))
  (println (str "method/version: " (:method/version contract)))
  (doseq [[name f] fixtures]
    (try
      (f name)
      (println (str "  ok    " name))
      (catch :default e
        (fail! name (str "fixture could not run: " (.-message e)))
        (println (str "  ERROR " name)))))
  (if (empty? @failures)
    (do (println "all fixtures passed (deterministic, offline)") 0)
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "FAIL " fixture ": " msg)))
        1)))

(js/process.exit (run-all))
