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
   (receipt "https://example-news.test/fund-a" :news-report "en" body-c)])

;; A second capture of exactly body-a from the same URL, one window
;; later: must collapse to the same receipt-id (dedup).
(def duplicate-capture
  (receipt "https://example-reg.test/fund-a/close" :official-regulator "en" body-a))

(def window {:from "2026-08-01" :until "2026-09-01"
             :declared-at "2026-09-01" :timezone "UTC"})

(defn verification [vid rid kind deps]
  {:verification-id vid :receipt-id rid :window window
   :verified-at "2026-09-01" :method/version method-version
   :byte-identity {:kind kind} :dependent-observation-count deps})

(def verifications
  [(verification "v-1" (:receipt-id (first receipts)) :content-hash-match 4)
   (verification "v-2" (:receipt-id (second receipts)) :content-hash-match 2)
   ;; third receipt re-fetched, bytes changed at source
   (verification "v-3" (:receipt-id (nth receipts 2)) :content-hash-mismatch 1)])

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
  (fixture-readback-invariants))

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
