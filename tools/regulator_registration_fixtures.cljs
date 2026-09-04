#!/usr/bin/env nbb
;; regulator_registration_fixtures.cljs — deterministic offline fixtures for the
;; regulator-registration-observation contract
;; (`capital-observation/regulator-registration-observation.edn`).
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/regulator_registration_fixtures.cljs [path/to/contract.edn]

(ns regulator-registration-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation"
                 "regulator-registration-observation.edn")))

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
;; All receipts are official-regulator class: for registrations, only
;; the regulator backs a derived observation.
(def receipts
  [(receipt "r-1" "https://example-regulator.test/registry/adviser/REG-1"
            :official-regulator "en"
            "MANAGER LLC registration effective; CRD 123456")
   (receipt "r-2" "https://example-regulator.test/registry/adviser/REG-2"
            :official-regulator "ja"
            "MANAGER合同会社 登録相当記録; REG-2")
   (receipt "r-3" "https://example-registry.test/company/REG-2"
            :official-company-registry "ja"
            "MANAGER Fund I 合同組合 登記記録")])

;; Two entities share the brand MANAGER — the registered adviser (a
;; management company) and a fund vehicle it manages must stay distinct.
(def entities
  [{:entity-id "e-1" :entity-type :management-company :name "MANAGER"
    :legal-name "MANAGER LLC" :jurisdiction "US"
    :identifier-class :crd :identifier-value "123456"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-2" :entity-type :fund-vehicle :name "MANAGER"
    :legal-name "MANAGER Fund I L.P." :jurisdiction "JP"
    :identifier-class :official-registry-id :identifier-value "REG-2"
    :source-receipt-id "r-3" :asserted-at "2026-09-01" :observed-at "2026-09-01"}
   {:entity-id "e-reg-1" :entity-type :regulator :name "EXAMPLE SEC"
    :legal-name "Example Securities Regulator" :jurisdiction "US"
    :identifier-class :official-registry-id :identifier-value "REGULATOR-1"
    :source-receipt-id "r-1" :asserted-at "2026-09-01" :observed-at "2026-09-01"}])

(def events
  [{:event-id "ev-1" :event-type :registration-effective :entity-id "e-1"
    :regulator-entity-id "e-reg-1"
    :announced-at "2026-08-20" :effective-at "2026-08-21"
    :asserted-at "2026-09-01"
    :as-stated {:kind :stated :fields #{:aum-as-stated}
                :aum-as-stated "USD 100,000,000" :as-stated-by-source? true}
    :source-receipt-id "r-1"}
   {:event-id "ev-2" :event-type :registration-amended :entity-id "e-1"
    :regulator-entity-id "e-reg-1"
    :announced-at "2026-08-28" :effective-at nil
    :asserted-at "2026-09-01"
    ;; effective-date-unstated flag fixture
    :as-stated {:kind :unstated :as-stated-by-source? true}
    :source-receipt-id "r-1"}])

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-provenance [f]
  ;; Every event cites a receipt whose content-hash exists and every
  ;; entity cites a receipt; receipt ids resolve.
  (let [rmap (into {} (map (juxt :receipt-id identity) receipts))]
    (doseq [ev events]
      (let [r (get rmap (:source-receipt-id ev))]
        (when-not (and r (re-find #"^[0-9a-f]{64}$" (:content-hash r)))
          (fail! f (str "event " (:event-id ev) " lacks a hash-backed receipt")))))
    (doseq [e entities]
      (when-not (get rmap (:source-receipt-id e))
        (fail! f (str "entity " (:entity-id e) " lacks a resolvable receipt"))))))

(defn fixture-source-class [f]
  ;; Only allow-listed source classes back observations; first-party and
  ;; news are discovery-only here.
  (let [allow (:source-class-allow (:source-receipt contract))]
    (doseq [r receipts]
      (when-not (contains? allow (:source-class r))
        (fail! f (str "receipt " (:receipt-id r) " uses non-allow class "
                      (:source-class r)))))))

(defn fixture-entity-separation [f]
  ;; Same brand name must not collapse into one entity id, and the
  ;; management company is not the fund vehicle.
  (let [by-name (group-by :name entities)]
    (doseq [[name group] by-name]
      (when (and (> (count group) 1)
                 (not= (count (set (map :entity-id group)))
                       (count group)))
        (fail! f (str "brand " name " collapsed distinct entities"))))
    (let [types (set (map (juxt :entity-id :entity-type) entities))]
      (when-not (= (count types) (count entities))
        (fail! f "one entity id maps to more than one entity type")))))

(defn fixture-stated-figures [f]
  ;; stated-figures-are-carried-not-promoted: AUM-like figures appear
  ;; only under :as-stated; the contract forbids a verified-aum field.
  (when-not (contains? (:forbidden-fields (:derived-observation contract))
                       :verified-aum)
    (fail! f "contract must forbid :verified-aum"))
  (doseq [ev events]
    (when (contains? ev :aum)
      (fail! f (str "event " (:event-id ev) " carries a top-level aum field")))))

(defn fixture-registration-kinds [f]
  ;; registration-is-not-endorsement: no suitability/endorsement field
  ;; in the contract; event kinds survive distinct (withdrawal is not
  ;; rejection; announced-effective is not effective).
  (let [ff (set (:forbidden-fields (:derived-observation contract)))]
    (doseq [k [:suitability :recommendation :performance-history]]
      (when-not (contains? ff k)
        (fail! f (str "forbidden-fields missing " k))))
    (let [ets (set (:event-types contract))]
      (doseq [k [:registration-filed :registration-effective
                 :registration-amended :registration-withdrawn
                 :registration-rejected]]
        (when-not (contains? ets k)
          (fail! f (str "event-types missing " k)))))))

(defn fixture-missingness [f]
  ;; missing-is-unmeasured: ev-2 has an unstated effective date and
  ;; must be flagged, not silently filled.
  (let [flags (:flags (:missingness contract))]
    (when-not (contains? flags :effective-date-unstated)
      (fail! f "missingness flags lack :effective-date-unstated"))
    (when (and (nil? (:effective-at (second events)))
               (not= :unstated (-> (second events) :as-stated :kind)))
      (fail! f "unstated effective date not carried as unstated"))))

(defn fixture-window-half-open [f]
  ;; Half-open [from, until): until == 2026-09-01 must be EXCLUDED.
  (let [in-window? (fn [d] (and (>= (compare d (:from window)) 0)
                                (< (compare d (:until window)) 0)))]
    (when-not (in-window? "2026-08-31")
      (fail! f "last day before until excluded"))
    (when (in-window? "2026-09-01")
      (fail! f "until date must be excluded in half-open window"))))

(defn fixture-refresh-append-only [f]
  (when-not (:append-only? (:refresh-history contract))
    (fail! f "refresh history must be append-only"))
  (when-not (= :reclassification-appends-does-not-overwrite
               (:rule (:refresh-history contract)))
    (fail! f "refresh history rule must be append-not-overwrite")))

(defn fixture-readback-shape [f]
  ;; Readback status values exist and the readback rule requires
  ;; coverage + missingness.
  (let [qr (:query-readback contract)]
    (when-not (contains? (:status-values qr) :unmeasured)
      (fail! f "readback must support :unmeasured"))
    (when-not (contains? (set (:request-schema qr)) :query-id)
      (fail! f "readback request lacks :query-id"))
    (when-not (= :readback-must-carry-coverage-and-missingness (:rule qr))
      (fail! f "readback rule missing"))))

(defn fixture-hyakka-proposal [f]
  (let [hp (:hyakka-proposal contract)]
    (when-not (contains? (set (:schema hp)) :disclaimer)
      (fail! f "hyakka proposal lacks :disclaimer"))
    (when-not (re-find #"No\s+investment\s+advice"
                       (str/replace (:disclaimer hp) #"\s+" " "))
      (fail! f "disclaimer must say no investment advice"))))

(defn fixture-content-hash-determinism [f]
  ;; Same bytes -> same hash, different bytes -> different hash.
  (let [a (sha256 "identical body") b (sha256 "identical body")
        c (sha256 "different body")]
    (when-not (and (= a b) (not= a c))
      (fail! f "sha256 receipt hashing not deterministic"))))

(def fixtures
  [["provenance" fixture-provenance]
   ["source-class" fixture-source-class]
   ["entity-separation" fixture-entity-separation]
   ["stated-figures" fixture-stated-figures]
   ["registration-kinds" fixture-registration-kinds]
   ["missingness" fixture-missingness]
   ["window-half-open" fixture-window-half-open]
   ["refresh-append-only" fixture-refresh-append-only]
   ["readback-shape" fixture-readback-shape]
   ["hyakka-proposal" fixture-hyakka-proposal]
   ["content-hash-determinism" fixture-content-hash-determinism]])

(doseq [[fname f] fixtures]
  (try
    (f fname)
    (println (str "ok   " fname))
    (catch :default e
      (fail! fname (str "fixture threw: " (.-message e)))
      (println (str "threw " fname)))))

(if (empty? @failures)
  (do (println "ALL REGULATOR-REGISTRATION FIXTURES GREEN")
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "FAIL " fixture ": " msg)))
      (js/process.exit 1)))
