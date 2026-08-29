#!/usr/bin/env nbb
;; verify.cljs — offline checks that this repo's entry documents still describe
;; the repo that is actually here.
;;
;; This repo was EXTRACTED out of the `etzhayyim/root` monorepo (see
;; `migration.edn`, `:status :extracted`). The extraction moved the files but did
;; not rewrite the paths inside them, so the documents kept pointing at
;; `60-apps/…` and `90-docs/…` — directories that exist in the monorepo and not
;; here. That class of defect is invisible to a reader who never types the
;; command, so it is checked here instead of trusted.
;;
;; Usage:
;;   nbb tools/verify.cljs              # offline checks only (deterministic)
;;   nbb tools/verify.cljs --preflight  # also probe the hosts the docs name
;;
;; Exit codes are three-valued on purpose. A check that could not run must not
;; return the same value as a check that ran and found nothing:
;;   0  every check ran and found nothing
;;   1  a check ran and found something
;;   2  REFUSED — a check could not run, so no verdict is reported at all

(ns verify
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def preflight? (some #(= % "--preflight") *command-line-args*))

;; ── References that point outside this repo and could not be resolved to a
;; fleet repo. They are listed, not hidden: an allowlist that is never printed
;; becomes a place where findings go to die. Each entry records what was
;; actually checked, so the next reader can re-check rather than re-trust.
(def unresolved-references
  {"60-apps/CLAUDE.md"
   "Shared rules file of the pre-extraction etzhayyim/root monorepo. No equivalent exists in this repo."
   "70-tools/CLAUDE.md"
   "Shared rules file of the pre-extraction etzhayyim/root monorepo. No equivalent exists in this repo."
   "60-apps/etzhayyim-project-well-becoming/90-docs/260315-child-capability-agents-design.md"
   (str "Cross-project design doc from the monorepo. Searched for a successor on 2026-08-29 and did not find one: "
        "checked cloud-itonami/app-society6, kotoba-lang/open-wellbecoming, etzhayyim/actor-joucho "
        "(0 files matching child-capability|260315 in each). UNRESOLVED, not absent.")})

(defn- sh [cmd]
  (try (str/trim (str (cp/execSync cmd #js{:cwd root :encoding "utf8" :stdio #js["pipe" "pipe" "pipe"]})))
       (catch :default _ nil)))

(defn- slurp* [rel]
  (try (str (fs/readFileSync (path/join root rel) "utf8")) (catch :default _ nil)))

(defn- tracked-files []
  (when-let [out (sh "git ls-files")]
    (vec (remove str/blank? (str/split-lines out)))))

;; ── reference extraction ──────────────────────────────────────────────────────
;; Deliberately tight: a token counts as a repo-relative path only if it
;; contains a "/" AND ends in a known extension. Loose matching would flag
;; `credits.etzhayyim.com` and `com.etzhayyim.apps.publicFund.fundProgram`,
;; and a checker that invents findings gets muted, which costs more than it saves.

(def path-ext #"\.(?:md|ts|edn|jsonld|json|proto|go|cljs|cljc)$")

(defn- path-like? [s]
  (and (string? s)
       (str/includes? s "/")
       (re-find path-ext s)
       (not (re-find #"^(?:https?:|did:|@)" s))
       (not (str/starts-with? s "/"))))

(defn- refs-in [text]
  ;; Backticks hold both bare paths (`docs/x.md`) and whole commands
  ;; (`nbb tools/verify.cljs`), so every whitespace-separated token is tested
  ;; rather than the span as a whole. Testing the span would miss the path
  ;; inside a command — which is exactly the shape the stale seed command had.
  (into #{}
        (comp (mapcat #(str/split % #"\s+")) (filter path-like?))
        (concat (map second (re-seq #"`([^`\n]+)`" text))          ; `path` or `cmd path`
                (map second (re-seq #"\]\(([^)\s]+)\)" text))      ; [t](path)
                (map second (re-seq #"npx\s+tsx\s+(\S+)" text))))) ; runnable command

;; ── checks ────────────────────────────────────────────────────────────────────

(defn check-doc-paths [files]
  (let [md (filterv #(str/ends-with? % ".md") files)
        scanned (keep (fn [f] (when-let [t (slurp* f)] [f (refs-in t)])) md)
        total-refs (reduce + 0 (map #(count (second %)) scanned))]
    (cond
      (zero? (count md))
      {:refused "no .md files are tracked — nothing to check, and a pass here would mean nothing"}

      (not= (count scanned) (count md))
      {:refused (str "could not read " (- (count md) (count scanned)) " of " (count md) " tracked .md files")}

      (zero? total-refs)
      {:refused (str "scanned " (count md) " .md files and extracted 0 path references — "
                     "either the docs stopped naming paths or the extractor broke; both need a human, not a green tick")}

      :else
      {:scanned {:files (count md) :refs total-refs}
       :findings (vec (for [[f refs] scanned
                            r (sort refs)
                            :when (and (not (fs/existsSync (path/join root r)))
                                       (not (contains? unresolved-references r)))]
                        {:check :doc-path-resolves :file f :ref r
                         :msg (str "names " r " which does not exist in this repo")}))
       :allowed (vec (for [[f refs] scanned
                           r (sort refs)
                           :when (contains? unresolved-references r)]
                       {:file f :ref r :why (get unresolved-references r)}))})))

(defn check-seed-contract [files]
  (let [seed-rel "seed.ts"
        seed (slurp* seed-rel)
        readme (slurp* "README.md")]
    (cond
      (not (some #(= % seed-rel) files)) {:refused (str seed-rel " is not tracked")}
      (nil? seed) {:refused (str "could not read " seed-rel)}
      (nil? readme) {:refused "could not read README.md"}
      :else
      (let [needs (into #{} (map second) (re-seq #"process\.env\.([A-Za-z_][A-Za-z0-9_]*)" seed))
            documented (into #{} (map second) (re-seq #"(?m)^\s*export\s+([A-Za-z_][A-Za-z0-9_]*)=" readme))
            cmd-paths (into #{} (map second) (re-seq #"npx\s+tsx\s+(\S+)" readme))]
        (cond
          (empty? needs)
          {:refused (str seed-rel " reads no process.env.* variable — the extractor found nothing to compare, "
                         "so 'the README documents the right variable' is unmeasured, not true")}

          (empty? cmd-paths)
          {:refused "README.md documents no `npx tsx <path>` command — nothing to check the seed path against"}

          :else
          {:scanned {:env-required (count needs) :env-documented (count documented) :commands (count cmd-paths)}
           :findings
           (vec (concat
                  (for [v (sort needs) :when (not (contains? documented v))]
                    {:check :seed-env-var-documented :file "README.md" :ref v
                     :msg (str seed-rel " requires env var " v
                               " but README.md documents " (if (seq documented) (str/join ", " (sort documented)) "none")
                               " — an operator following the README gets the script's own 'env var required' error")})
                  (for [p (sort cmd-paths) :when (not (fs/existsSync (path/join root p)))]
                    {:check :seed-command-path :file "README.md" :ref p
                     :msg (str "documents `npx tsx " p "` but that path does not exist in this repo")})))})))))

;; ── seed record graph ─────────────────────────────────────────────────────────
;; The seed writes 8 collections that reference each other by id. Nothing here
;; validates them before they reach a PDS, so a dangling id would be discovered
;; as a half-written dataset on a live server.

(def seed-arrays
  ;; array-name -> [id-key, [[foreign-key -> array-name-it-points-at] ...]]
  {"FUND_PROGRAMS"       [:programId []]
   "FUND_CAMPAIGNS"      [:campaignId [[:programId "FUND_PROGRAMS"]]]
   "PLEDGES"             [:pledgeId [[:campaignId "FUND_CAMPAIGNS"]]]
   "ROUTED_ALLOCATIONS"  [:allocationId []]
   "ELIGIBILITY_POLICIES" [:policyId [[:programId "FUND_PROGRAMS"]]]
   "APPLICATIONS"        [:applicationId [[:programId "FUND_PROGRAMS"]]]
   "DECISIONS"           [:decisionId [[:applicationId "APPLICATIONS"]]]
   "DISBURSEMENTS"       [:disbursementId [[:applicationId "APPLICATIONS"] [:decisionId "DECISIONS"]]]})

(defn- array-body [seed nm]
  (let [start (str/index-of seed (str "const " nm " = ["))]
    (when start
      (let [open (str/index-of seed "[" start)]
        (loop [i open depth 0]
          (cond
            (>= i (count seed)) nil
            (= \[ (nth seed i)) (recur (inc i) (inc depth))
            (= \] (nth seed i)) (if (= 1 depth)
                                  (subs seed open (inc i))
                                  (recur (inc i) (dec depth)))
            :else (recur (inc i) depth)))))))

(defn- values-of [body k]
  (vec (map second (re-seq (re-pattern (str "\\b" (name k) ":\\s*'([^']*)'")) body))))

(defn check-seed-graph [files]
  (let [seed (slurp* "seed.ts")]
    (cond
      (not (some #(= % "seed.ts") files)) {:refused "seed.ts is not tracked"}
      (nil? seed) {:refused "could not read seed.ts"}
      :else
      (let [bodies (into {} (keep (fn [nm] (when-let [b (array-body seed nm)] [nm b])) (keys seed-arrays)))
            missing-arrays (remove #(contains? bodies %) (keys seed-arrays))
            ids (into {} (for [[nm b] bodies] [nm (values-of b (first (get seed-arrays nm)))]))
            empty-arrays (for [[nm v] ids :when (empty? v)] nm)]
        (cond
          (seq missing-arrays)
          {:refused (str "could not locate " (count missing-arrays) " seed array(s) in seed.ts ("
                         (str/join ", " (sort missing-arrays)) ") — the parser no longer matches the file, "
                         "so integrity is unmeasured")}

          (seq empty-arrays)
          {:refused (str "parsed 0 records from " (str/join ", " (sort empty-arrays))
                         " — an empty parse trivially satisfies every reference check")}

          :else
          {:scanned {:arrays (count ids) :records (reduce + 0 (map count (vals ids)))}
           :findings
           (vec (concat
                  ;; duplicate ids within a collection
                  (for [[nm v] ids
                        [dup n] (frequencies v)
                        :when (> n 1)]
                    {:check :seed-duplicate-id :file "seed.ts" :ref dup
                     :msg (str nm " declares id " dup " " n " times")})
                  ;; foreign keys that point at nothing
                  (for [[nm [_ fks]] seed-arrays
                        [fk target] fks
                        :let [body (get bodies nm)
                              known (set (get ids target))]
                        v (values-of body fk)
                        :when (not (contains? known v))]
                    {:check :seed-dangling-reference :file "seed.ts" :ref v
                     :msg (str nm "." (name fk) " = " v " but no record in " target " declares it")})))})))))

;; ── preflight (network; opt-in) ───────────────────────────────────────────────

(defn- host-state [h]
  (let [dns (sh (str "dig +short " h " @1.1.1.1"))]
    (cond
      (nil? dns) [:unmeasured "dig unavailable — this is not the same as 'down'"]
      (str/blank? dns) [:no-dns "does not resolve (NXDOMAIN)"]
      :else
      (let [code (sh (str "curl -sS -o /dev/null -w '%{http_code}' --max-time 12 https://" h "/"))]
        (cond
          (nil? code) [:unmeasured "curl unavailable"]
          (= code "000") [:unreachable "resolves but no HTTP response"]
          (re-find #"^[45]" code) [:error (str "HTTP " code)]
          :else [:ok (str "HTTP " code)])))))

(def declared-hosts
  ;; Probed every run. A scrape alone is not enough: the two hosts that are
  ;; actually dead appear in the docs as bare backticked names, not URLs, so a
  ;; URL-only scrape measured exactly one host and printed a tidy result that
  ;; said nothing about the other two. Silence looked like health.
  {"atproto.etzhayyim.com" "PDS that seed.ts writes 23 records to"
   "pb.etzhayyim.com"      "public domain of the app, per README"
   "credits.etzhayyim.com" "credits ledger the design treats as the single balance ledger"
   "murakumo.etzhayyim.com" "inference host the 4 fund agents use, per docs/260315-education-family-fund-agents-design.md"})

(def ^:private not-a-host-ext
  #{"md" "ts" "edn" "json" "jsonld" "cljs" "cljc" "go" "proto" "yml" "yaml" "html"})

(defn- strip-fences
  "Drops ``` fenced blocks. Illustrative commands are not dependency declarations.

   Uses [\\s\\S] rather than (?s). JavaScript has no inline DOTALL group, so
   #\"(?s)```.*?```\" compiles to a regex that matches nothing across newlines and
   returns the input unchanged — a strip that silently does nothing looks exactly
   like a document with no fenced blocks in it."
  [text]
  (str/replace text #"```[\s\S]*?```" ""))

(def ^:private hostname-shape #"^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}$")

(defn- host-candidates
  "Surfaces hosts the declared list does not cover, so an incomplete list is
   visible rather than silently narrowing what gets probed.

   Restricted to the two forms a host actually takes in these documents: a URL,
   or a backticked bare hostname (which is how three of the four declared hosts
   are written). Scanning every dotted token instead produced 39 candidates —
   `console.log`, `res.ok`, NSIDs, DID-derived actor names — and a check that
   noisy gets ignored, which costs more than the coverage it buys.

   Whole backtick spans are matched, not prefixes inside them: anchoring on the
   opening backtick pulled `actor.create` out of `com.etzhayyim.actor.create`,
   so the `com.` exclusion never saw the string it exists to exclude."
  [text]
  (into (sorted-set)
        (remove (fn [h]
                  (or (contains? declared-hosts h)
                      ;; NSIDs are reverse-DNS: they lead with the TLD label.
                      (str/starts-with? h "com.")
                      (contains? not-a-host-ext (last (str/split h #"\."))))))
        (concat
          ;; A URL names a host wherever it appears, including inside an example.
          (map second (re-seq #"https?://([a-z0-9.-]+)" text))
          ;; A backticked bare name only counts outside fenced blocks. Fenced
          ;; blocks hold commands, and the quickstart's own negative control
          ;; contains a literal `nonexistent-host.example.com` — scanning it
          ;; made that control report a find whether or not the step had run,
          ;; which is the control passing for the wrong reason.
          (filter #(re-find hostname-shape %)
                  (map second (re-seq #"`([^`\n]+)`" (strip-fences text)))))))

(defn run-preflight [files]
  (let [text (str (str/join "\n" (keep slurp* (filter #(str/ends-with? % ".md") files)))
                  "\n" (or (slurp* "seed.ts") ""))
        undeclared (host-candidates text)]
    (println "\n── preflight: hosts this repo depends on ──")
    (doseq [[h why] (sort declared-hosts)]
      (let [[state msg] (host-state h)]
        (println (str "  " (str/upper-case (name state))
                      (apply str (repeat (max 1 (- 13 (count (name state)))) " "))
                      h "  " msg))
        (println (str "                " why))))
    (println (str "  probed " (count declared-hosts) " declared host(s)"))
    (if (empty? undeclared)
      (println "  no undeclared host-like tokens found in docs or seed")
      (do (println (str "  UNDECLARED host-like token(s) — not probed, add or exclude them:"))
          (doseq [h undeclared] (println (str "      " h)))))
    (println "  note: a host being down is reported, not counted as a finding — this repo")
    (println "        does not own those deployments. But UNMEASURED is never printed as OK.")))

;; ── main ──────────────────────────────────────────────────────────────────────

(defn -main []
  (let [files (tracked-files)]
    (when (or (nil? files) (empty? files))
      (println "REFUSED: `git ls-files` returned nothing — not a git checkout, or git is unavailable.")
      (println "         Refusing to report a pass on a tree this script could not enumerate.")
      (js/process.exit 2))
    (println (str "verify.cljs — " (count files) " tracked file(s) under " (path/resolve root)))
    (let [results {:doc-paths (check-doc-paths files)
                   :seed-contract (check-seed-contract files)
                   :seed-graph (check-seed-graph files)}
          refusals (for [[k v] results :when (:refused v)] [k (:refused v)])
          findings (mapcat #(:findings (second %) []) results)]
      (doseq [[k v] results]
        (println (str "\n[" (name k) "]"))
        (if-let [r (:refused v)]
          (println (str "  REFUSED: " r))
          (do (println (str "  scanned: " (pr-str (:scanned v))))
              (doseq [a (:allowed v)]
                (println (str "  allowed-unresolved: " (:ref a)))
                (println (str "      " (:why a))))
              (if (empty? (:findings v))
                (println "  ok")
                (doseq [f (:findings v)]
                  (println (str "  FINDING " (name (:check f)) " — " (:file f) ": " (:msg f))))))))
      (when preflight? (run-preflight files))
      (println)
      (cond
        (seq refusals)
        (do (println (str "REFUSED (" (count refusals) " check(s) could not run). No verdict reported."))
            (js/process.exit 2))
        (seq findings)
        (do (println (str "FINDINGS " (count findings)))
            (js/process.exit 1))
        :else
        (do (println "CLEAN — every check ran and found nothing.")
            (js/process.exit 0))))))

(-main)
