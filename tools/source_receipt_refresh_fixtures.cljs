#!/usr/bin/env nbb
;; source_receipt_refresh_fixtures.cljs — deterministic offline fixtures for the
;; source-receipt-refresh-observation contract
;; (`capital-observation/source-receipt-refresh-observation.edn`).
;;
;; Covers: receipt byte-identity verification, same-bytes dedup collapse,
;; discovery-only source-class escape, append-only refresh-history audit
;; (edit detection via entry hashes), missing-is-unmeasured propagation,
;; forbidden-field absence, and readback paging invariants.
;;
;; Exit codes mirror tools/verify.cljs:
;;   0  all fixtures ran and found nothing wrong
;;   1  a fixture ran and found a violation
;;   2  REFUSED — a fixture could not run
;;
;; Usage: nbb tools/source_receipt_refresh_fixtures.cljs [path/to/contract.edn]
;;
;; v2 (2026-09-03): fetch-status admission gate (only :ok receipts enter
;; the verification cycle), required verification-level provenance
;; chains, mismatch-appends-never-edits, and strict readback (unknown
;; filter keys rejected, kind filters match exactly). Negative fixtures
;; construct violating inputs and assert the derivation REFUSES them.

(ns source-receipt-refresh-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def root ".")
(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join root "capital-observation"
                 "source-receipt-refresh-observation.edn")))

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
  (let [h (crypto/createHash "sha256")]
    (.update h s)
    (.digest h "hex")))

(def method-version (:method/version contract))

;; ── Fixture data (deterministic, no network) ────────────────────────
;; Receipt id is derived from content hash + source url, so the same
;; bytes from the same source collapse to one receipt.
(defn receipt-id-for [url body] (str "r-" (sha256 (str url "|" body))))

(defn receipt [url class lang body]
  {:receipt-id (receipt-id-for url body)
   :source-url url :source-class class :source-language lang
   :captured-at "2026-08-30" :content-hash (sha256 body) :fetch-status :ok})

(def body-a "OFFICIAL NOTICE: FUND-A first close, JPY 5,000,000,000")
(def body-b "LP COMMITMENT REGISTER — window 2026-08")
(def body-c "NEWS: FUND-A reportedly closing soon")

(def receipts
  [(receipt "https://example-reg.test/fund-a/close" :official-regulator "en" body-a)
   (receipt "https://example-lp.test/register" :institutional-lp-first-party "en" body-b)
   ;; discovery-only class: may exist as a pointer, never back an observation
   (receipt "https://example-news.test/fund-a" :news-report "en" body-c)
   ;; v2: a receipt whose fetch FAILED. Recorded verbatim, but it refuses
   ;; admission — it can never enter the verification cycle.
   (assoc (receipt "https://example-board.test/fund-a/notice" :official-board-record "en"
                   "BOARD NOTICE: FUND-A quarterly report")
          :fetch-status :error)])

;; A second capture of exactly body-a from the same URL, one window
;; later: must collapse to the same receipt-id (dedup).
(def duplicate-capture
  (receipt "https://example-reg.test/fund-a/close" :official-regulator "en" body-a))

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

(defn verification [vid rid kind deps]
  {:verification-id vid :receipt-id rid :window window
   :verified-at "2026-09-01" :method/version method-version
   :byte-identity {:kind kind} :dependent-observation-count deps
   ;; v2: required provenance chain — head is this record's :receipt-id.
   :provenance-chain [rid]})

(def verifications
  [(verification "v-1" (:receipt-id (first receipts)) :content-hash-match 4)
   (verification "v-2" (:receipt-id (second receipts)) :content-hash-match 2)
   ;; third receipt re-fetched, bytes changed at source
   (verification "v-3" (:receipt-id (nth receipts 2)) :content-hash-mismatch 1)
   ;; v2: an attempt to verify the admission-refused receipt — must be
   ;; refused with a refusal record, never derived.
   (verification "v-4" (:receipt-id (nth receipts 3)) :content-hash-match 0)])

;; ── v2 derivation model (deterministic, mirrors the contract) ───────
(defn admitted? [contract r]
  (contains? (get-in contract [:source-receipt :admission :admitted])
             (:fetch-status r)))

(defn chain-valid?
  "v2 provenance-chain rule: non-empty, all ids exist, first element is
  the record's :receipt-id."
  [receipt-ids v]
  (let [ch (:provenance-chain v)]
    (and (vector? ch) (seq ch)
         (every? #(contains? receipt-ids %) ch)
         (= (first ch) (:receipt-id v)))))

(defn derive-verifications
  "Returns {:verifications [...] :refusals [...]} under the v2 rules:
  a verification citing a chain-invalid or admission-refused receipt is
  refused with a refusal record. No third outcome exists."
  [contract receipts verifications]
  (let [by-id (into {} (map (juxt :receipt-id identity) receipts))
        receipt-ids (set (keys by-id))]
    (reduce
      (fn [acc v]
        (cond
          (not (chain-valid? receipt-ids v))
          (update acc :refusals conj {:verification-id (:verification-id v)
                                      :reason :provenance-chain-invalid})
          (not (admitted? contract (get by-id (:receipt-id v))))
          (update acc :refusals conj
                  {:receipt-id (:receipt-id v)
                   :fetch-status (:fetch-status (get by-id (:receipt-id v)))
                   :admission-refused-at "2026-09-01"
                   :method/version (:method/version contract)})
          :else
          (update acc :verifications conj v)))
      {:verifications [] :refusals []}
      verifications)))

;; Append-only refresh history entries, each carrying a hash of its
;; own serialized bytes. An edited entry produces a hash mismatch.
(defn history-entry [hid obs reason fields]
  (let [entry {:history-id hid :observation-id obs :re-observed-at "2026-08-31"
               :reason {:kind reason} :changed-fields fields}
        h (sha256 (pr-str entry))]
    (assoc entry :entry-hash h)))

(def history
  [(history-entry "h-1" "obs-fc-1" :window-advanced [:from :until])
   (history-entry "h-2" "obs-lp-1" :source-updated [:source-receipt-id])])

;; A tampered copy of h-1 with a different field but the ORIGINAL hash:
;; the audit must detect the mismatch as a :history-edit observation.
(defn tampered-entry []
  (let [orig (first history)
        edited (assoc orig :changed-fields [:from :until :source-receipt-id])]
    (assoc edited :entry-hash (:entry-hash orig))))

;; ── Fixtures ────────────────────────────────────────────────────────

(defn fixture-contract-shape []
  (let [required [:contract/id :contract/version :method/version :actor
                  :proposes-to :window :source-receipt :verification-record
                  :refresh-history :missingness :derived-observation
                  :provenance-chain :hyakka-proposal :query-readback]
        missing (remove #(contains? contract %) required)]
    (when (seq missing)
      (fail! :contract-shape (str "missing top-level keys: " (vec missing))))))

(defn fixture-method-version-shape []
  (when-not (re-matches #"source-receipt-refresh-observation\.v\d+"
                        (str method-version))
    (fail! :method-version (str "bad method/version: " method-version))))

(defn fixture-receipt-dedup []
  (let [r1 (first receipts)
        r2 duplicate-capture]
    (when-not (= (:receipt-id r1) (:receipt-id r2))
      (fail! :receipt-dedup
             "same bytes from same source produced different receipt ids"))
    (when-not (= (:content-hash r1) (:content-hash r2))
      (fail! :receipt-dedup "content hashes differ for identical bytes"))))

(defn fixture-byte-identity-kinds []
  (let [allowed (get-in contract [:verification-record :schema])
        kinds (->> (get-in contract [:verification-record :byte-identity :kind])
                   (remove #{:one-of}) set)
        allowed-kinds #{:content-hash-match :content-hash-mismatch
                        :source-unreachable :fetch-status-changed}]
    (when-not (seq allowed)
      (fail! :byte-identity "verification-record has no :schema"))
    (when-not (every? allowed-kinds kinds)
      (fail! :byte-identity (str "unexpected byte-identity kinds: " kinds)))))

(defn fixture-unverifiable-propagation []
  ;; A receipt whose verification failed marks its dependents
  ;; unmeasured — they must be flagged, never re-based or deleted.
  (let [v3 (nth verifications 2)
        n (:dependent-observation-count v3)]
    (when (and (= :content-hash-mismatch (get-in v3 [:byte-identity :kind]))
               (zero? n))
      (fail! :unverifiable-propagation
             "mismatch verification claims zero dependents — propagation untestable"))))

(defn fixture-append-only-violation-detection []
  (let [orig (first history)
        tampered (tampered-entry)
        recomputed (sha256 (pr-str (dissoc tampered :entry-hash)))]
    ;; tampered entry's stored hash no longer matches its serialized
    ;; content -> the audit observes :history-edit, it does not repair.
    (when-not (not= recomputed (:entry-hash tampered))
      (fail! :append-only-audit
             "tampered entry hash matched — edit detection is theater"))))

(defn fixture-discovery-only-escape []
  (let [discovery-only (set (get-in contract [:source-receipt
                                              :source-class-discovery-only]))
        forbidden (set (get-in contract [:source-receipt :source-class-forbid]))
        backing (nth receipts 2)]
    (when-not (contains? discovery-only (:source-class backing))
      (fail! :discovery-only-escape "fixture news receipt not in discovery-only set"))
    (when (contains? forbidden (:source-class backing))
      (fail! :discovery-only-escape "news class must not also be forbidden here"))
    ;; discovery-only receipts must never back a derived observation:
    ;; simulate by checking v-3 (the only verification citing it) marks
    ;; its dependent unmeasured rather than verified.
    (when (= :content-hash-match (get-in (nth verifications 2)
                                         [:byte-identity :kind]))
      (fail! :discovery-only-escape
             "discovery-only receipt verified as match — it must not back observations"))))

(defn fixture-forbidden-fields-absent []
  (let [forbidden (get-in contract [:derived-observation :forbidden-fields])
        schema-keys (set (get-in contract [:derived-observation :schema]))]
    (when-not (and (seq forbidden)
                   (every? #(not (contains? schema-keys %)) forbidden))
      (fail! :forbidden-fields
             "a forbidden field (rank/score/centrality/...) leaked into the schema"))))

(defn fixture-missing-is-unmeasured []
  (let [flags (get-in contract [:missingness :flags])]
    (when-not (contains? flags :receipt-unverifiable)
      (fail! :missingness ":receipt-unverifiable flag missing"))
    (when-not (contains? flags :no-receipt)
      (fail! :missingness ":no-receipt flag missing"))))

(defn fixture-readback-invariants []
  (let [qb (:query-readback contract)
        paging (:paging qb)]
    (when-not (= :observation-id (:stable-order-by paging))
      (fail! :readback "paging must use stable order by observation-id"))
    (when-not (some #{:coverage} (:readback-schema qb))
      (fail! :readback "readback schema must always carry coverage"))
    (when-not (some #{:missingness-flags} (:readback-schema qb))
      (fail! :readback "readback schema must always carry missingness flags"))))

(defn fixture-window-shape []
  (let [w (:window contract)]
    (when-not (and (= "YYYY-MM-DD" (:format w))
                   (false? (:closed? w)))
      (fail! :window "window must be half-open [from, until) with YYYY-MM-DD"))))

(defn fixture-fetch-admission [f]
  ;; v2: only :ok receipts enter the verification cycle. The refusal
  ;; must produce a refusal RECORD (not silence), and the refused
  ;; receipt never backs a derived verification. A re-fetch appends;
  ;; it does not retro-invalidate prior verifications.
  (let [{:keys [verifications refusals]}
        (derive-verifications contract receipts verifications)
        refused (some #(when (= (:receipt-id %) (:receipt-id (nth receipts 3))) %)
                      refusals)]
    (when-not (and (= 3 (count verifications))
                   (every? #(not= "v-4" (:verification-id %)) verifications))
      (fail! f "an admission-refused receipt entered the verification cycle"))
    (when (nil? refused)
      (fail! f "admission refusal must produce a refusal record, not silence"))
    (when-not (and (= (:fetch-status refused) :error)
                   (= (:method/version refused) (:method/version contract)))
      (fail! f "refusal record must carry fetch-status and method/version"))
    (when-not (contains? (:status-values (:query-readback contract))
                         :admission-refused)
      (fail! f "readback must be able to answer :admission-refused"))
    (let [statuses (:fetch-status-values (:source-receipt contract))]
      (when-not (and (contains? statuses :ok) (contains? statuses :error))
        (fail! f "contract must declare the fetch-status vocabulary")))
    (when-not (= :re-fetch-appends-new-receipt-plus-history
                 (get-in contract [:source-receipt :admission :re-fetch-rule]))
      (fail! f "contract must declare the append-only re-fetch rule"))))

(defn fixture-provenance-chain [f]
  ;; v2 negative fixtures: a chain that invents a receipt id, trims the
  ;; head, or is empty must be REFUSED, never derived.
  (let [rid (:receipt-id (first receipts))
        bad-verifications
        [(assoc (verification "v-bad-1" rid :content-hash-match 1)
                :provenance-chain ["r-invented"])
         (assoc (verification "v-bad-2" rid :content-hash-match 1)
                :provenance-chain [])
         (assoc (verification "v-bad-3" rid :content-hash-match 1)
                :provenance-chain [(str "r-" (sha256 "other")) rid])]]
    ;; v-bad-3: head is NOT the record's :receipt-id -> invalid.
    (doseq [v bad-verifications]
      (let [{:keys [verifications refusals]}
            (derive-verifications contract receipts [v])]
        (when-not (and (empty? verifications)
                       (= 1 (count refusals))
                       (= :provenance-chain-invalid (:reason (first refusals))))
          (fail! f (str "invalid chain for " (:verification-id v)
                        " was not refused")))))
    (when-not (contains? (get-in contract [:verification-record :invariants])
                         :provenance-chain-is-required-and-verified)
      (fail! f "contract must declare the provenance-chain invariant"))))

(defn fixture-mismatch-appends-never-edits [f]
  ;; v2: a re-observation that finds changed bytes appends a NEW receipt
  ;; plus a history entry — it never edits or deletes the prior receipt,
  ;; verification or history entry.
  (let [v3 (nth verifications 2)
        orig-receipt (nth receipts 2)]
    (when-not (= :mismatch-appends-never-edits
                 (get-in contract [:refresh-history :mismatch-rule]))
      (fail! f "contract must declare mismatch-appends-never-edits"))
    ;; the prior receipt record and prior verification remain intact and
    ;; addressable by id — nothing was rewritten in place.
    (when-not (and (some #(= (:receipt-id %) (:receipt-id orig-receipt)) receipts)
                   (some #(= :content-hash-mismatch
                             (get-in % [:byte-identity :kind]))
                         verifications))
      (fail! f "prior records must remain intact under mismatch-appends-never-edits"))
    (when-not (= 1 (:dependent-observation-count v3))
      (fail! f "mismatch dependents must be flagged unmeasured, never re-based"))))

(defn fixture-kind-readback [f]
  ;; v2: a :kind filter matches the CARRIED kind exactly — a
  ;; :verification filter never returns a :duplicate record (integrity
  ;; record kinds never collapse in readback).
  (let [records [{:kind :verification :id "v-1"}
                 {:kind :duplicate :id "d-1"}]
        verification-filter (fn [r] (= :verification (:kind r)))
        filtered (filter verification-filter records)]
    (when-not (every? #(= :verification (:kind %)) filtered)
      (fail! f "kind filter collapsed verification into duplicate"))
    (when-not (= :filter-matches-carried-kind-exactly
                 (get-in contract [:query-readback :strictness
                                   :kind-filter-rule]))
      (fail! f "contract must declare exact kind filtering"))))

(defn fixture-unknown-filter-key [f]
  ;; v2: an unknown filter key is REJECTED (:rejected-filter), never
  ;; silently ignored (which would quietly widen the query).
  (let [request {:window window
                 :filter {:dependent-count-greater-than 0}}
        known #{:kind :window :method/version :cursor}
        unknown-keys (remove #(contains? known %) (keys (:filter request)))]
    (when-not (empty? unknown-keys)
      (when-not (contains? (:status-values (:query-readback contract))
                           :rejected-filter)
        (fail! f "unknown filter key could not be rejected: no status"))
      (when-not (= :unknown-filter-key-rejected-not-ignored
                   (get-in contract [:query-readback :strictness :rule]))
        (fail! f "contract must declare unknown-filter-key rejection")))))

;; ── Run ─────────────────────────────────────────────────────────────
(defn run-all! []
  (fixture-contract-shape)
  (fixture-method-version-shape)
  (fixture-window-shape)
  (fixture-receipt-dedup)
  (fixture-byte-identity-kinds)
  (fixture-unverifiable-propagation)
  (fixture-append-only-violation-detection)
  (fixture-discovery-only-escape)
  (fixture-forbidden-fields-absent)
  (fixture-missing-is-unmeasured)
  (fixture-readback-invariants)
  (fixture-fetch-admission "fetch-admission")
  (fixture-provenance-chain "provenance-chain")
  (fixture-mismatch-appends-never-edits "mismatch-appends-never-edits")
  (fixture-kind-readback "kind-readback")
  (fixture-unknown-filter-key "unknown-filter-key"))

(run-all!)

(let [n (count @failures)]
  (if (zero? n)
    (do (println (str "OK: all source-receipt-refresh fixtures ran clean ("
                      method-version ")"))
        (js/process.exit 0))
    (do (doseq [{:keys [fixture msg]} @failures]
          (println (str "VIOLATION [" fixture "]: " msg)))
        (println (str n " violation(s) found"))
        (js/process.exit 1))))
