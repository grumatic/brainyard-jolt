;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.tool-reuse-eval
  "Is a distilled tool used once it is accepted? (byj-gly.249.4)

      LABEL=before jolt -A:dev -m brainyard.dev.tool-reuse-eval sonnet haiku
      REPS=2 TASKS=line-counts LABEL=after jolt -A:dev -m brainyard.dev.tool-reuse-eval sonnet

  One session per task pair, through the unmodified session loop with tool
  distillation on and `bash` not bound (with it, the shell is the composition
  path and tool$run is rarely written — RFC-010):

    turn A  the job on one input, asked to use tool$run — its body is the
            candidate, and the judge drafts, proves and stages a tool from it;
    accept  `/proposals accept` the staged tool, as a person would;
    turn B  the SAME job on another input, asked plainly.

  `STAGE=fixed` (byj-gly.251) takes the judge out: distillation is off, and
  between the turns the pair's `:tool` — one the judge staged in an earlier run,
  name and description as it wrote them — is written as a proposal and
  accepted. After one run the judge stages a tool about half the time, so the
  judged mode tests turn B in only half its sessions. The fixed mode tests it
  in every one. `CONFIG` is an EDN map merged into the session's config, to
  measure a variant:

      STAGE=fixed REPS=2 LABEL=base jolt -A:dev -m brainyard.dev.tool-reuse-eval haiku
      STAGE=fixed CONFIG='{:some-key true}' LABEL=variant jolt -A:dev -m brainyard.dev.tool-reuse-eval haiku

  `THOUGHT` (byj-gly.280.2) sets what think-act's `reasoning` asks for, as a
  params record in the session's project: `brainyard` for brainyard's words,
  or a resource path — the shipped wording is
  `brainyard/prompts/think-act-thought.md`. Each arm names its wording, whichever one ships; score
  the trajectories with brainyard.dev.thought-spike (`CONFIG='{:persist-session? true}'`).

  Per session: what was staged (name, proof), whether it was accepted, and for
  turn B: did it call the accepted `user$tool$…`, was it right, its
  iterations, tool calls and prompt characters (the judge's own call left
  out). Results: target/tool-reuse-eval/results-<LABEL>.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.llm :as llm]
            [brainyard.proposals :as proposals]
            [brainyard.session :as session]
            [brainyard.signature :as sig]
            [brainyard.tools :as tools]))

(defn- lines [n] (str/join "\n" (map #(str "line " %) (range n))))

(def pairs
  "Fixtures are generated so turn B's answer is known exactly."
  [{:id :line-counts
    :fixtures {"docs/a.md" (lines 3) "docs/b.md" (lines 6) "src/x.clj" (lines 5) "src/y.clj" (lines 11) "src/z.clj" (lines 2)}
    :a "Use exactly one tool$run call to return a map of each file name under docs/ to its line count. Then answer."
    :b "What is the line count of each file under src/? Answer with each file name and its count."
    :expect ["x.clj" "5" "y.clj" "11" "z.clj" "2"]
    :tool {:name "files-line-counts"
           :description "Map of each file name under a directory to its line count"
           :input-schema [:map [:path [:string {:desc "Directory path to analyze"}]]]
           :output-schema [:map-of :string :int]
           :body "(fn [args]\n  (->> (:entries (list-dir {:path (:path args)}))\n       (remove #(clojure.string/ends-with? % \"/\"))\n       (map (fn [f] [f (:total-lines (read-file {:path (str (:path args) \"/\" f)}))]))\n       (into {})))"}}
   {:id :json-sum
    :fixtures (merge (into {} (for [[i a] (map-indexed vector [120 75 310])]
                                [(str "data/d" i ".json") (str "{\"id\": " i ", \"amount\": " a "}")]))
                     (into {} (for [[i a] (map-indexed vector [40 17 99 8])]
                                [(str "orders/o" i ".json") (str "{\"id\": " i ", \"amount\": " a "}")])))
    :a "Use exactly one tool$run call to sum the `amount` field over every JSON file under data/. Then answer with the total."
    :b "What is the total `amount` across the JSON files under orders/? Answer with the number."
    :expect ["164"]
    :tool {:name "sum-json-field"
           :description "Sums a specified numeric field across all JSON files in a directory, defaulting to 0 for missing fields"
           :input-schema [:map [:dir [:string {:desc "Directory path containing JSON files"}]]
                          [:field [:string {:desc "Field name to sum"}]]]
           :output-schema :number
           :body "(fn [args]\n  (let [dir (:dir args)\n        field-kw (keyword (:field args))\n        json-files (filter #(clojure.string/ends-with? % \".json\") (:entries (list-dir {:path dir})))]\n    (reduce + 0 (map (fn [f] (or (get (json/read (:content (read-file {:path (str dir \"/\" f)}))) field-kw) 0)) json-files))))"}}
   {:id :largest
    :fixtures {"docs/a.md" (lines 12) "docs/b.md" (lines 40) "docs/c.md" (lines 7)
               "logs/one.log" (lines 25) "logs/two.log" (lines 90) "logs/three.log" (lines 60)}
    :a "Use exactly one tool$run call to find which file under docs/ has the most lines. Then answer with its name."
    :b "Which file under logs/ has the most lines? Answer with its name."
    :expect ["two.log"]
    :tool {:name "largest-file-by-lines"
           :description "Returns the [filename line-count] pair for the file with the most lines directly under a given directory."
           :input-schema [:map [:dir [:string {:desc "Directory to scan for the file with the most lines"}]]]
           :output-schema [:tuple :string :int]
           :body "(fn [args] (->> (:entries (list-dir {:path (:dir args)})) (remove #(clojure.string/ends-with? % \"/\")) (map (fn [f] [f (:total-lines (read-file {:path (str (:dir args) \"/\" f)}))])) (apply max-key second)))"}}
   {:id :urgent
    :fixtures (merge (into {} (for [i (range 5)] [(str "notes/n" i ".txt") (if (#{1 3} i) "please, URGENT review" "routine")]))
                     (into {} (for [i (range 6)] [(str "mail/m" i ".txt") (if (#{0 2 5} i) "an Urgent matter" "fyi")])))
    :a "Use exactly one tool$run call to count how many files under notes/ mention the word \"urgent\" in any letter case. Then answer with the number."
    :b "How many files under mail/ mention the word \"urgent\", in any letter case? Answer with the number."
    :expect ["3"]
    :tool {:name "count-files-containing"
           :description "Returns the count of files in a directory containing a given word or pattern"
           :input-schema [:map [:path [:string {:desc "Directory to search"}]]
                          [:pattern [:string {:desc "Word or pattern to search for"}]]
                          [:case-sensitive {:optional true} :boolean]]
           :output-schema :int
           :body "(fn [args]\n  (let [path (:path args)\n        cs (get args :case-sensitive false)\n        pattern (if cs (:pattern args) (clojure.string/lower-case (:pattern args)))\n        files (remove #(clojure.string/ends-with? % \"/\") (:entries (list-dir {:path path})))]\n    (count (filter (fn [f] (let [c (:content (read-file {:path (str path \"/\" f)}))]\n                             (clojure.string/includes? (if cs c (clojure.string/lower-case c)) pattern)))\n                   files))))"}}])

(defn- setup! [dir fixtures]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (doseq [[path content] fixtures]
    (let [f (io/file dir path)] (.mkdirs (.getParentFile f)) (spit f content))))

(defn- judge-request? [d]
  (some #(str/includes? (str (:content %)) "hold ONE reusable tool") (:messages d)))

(defn- stage-fixed!
  "Write `tool` as the judge would have staged it: a proposal under the
  project's tools dir, for `/proposals accept` to take."
  [dir {:keys [name body] :as tool}]
  (let [p (io/file dir ".brainyard" "tools" "proposals" name)]
    (.mkdirs p)
    (spit (io/file p "tool.edn") (with-out-str (pp/pprint (dissoc tool :body))))
    (spit (io/file p "body.clj") body)
    (spit (io/file p "proposal.edn") (pr-str {:score 0.7 :rationale "fixed for the eval (byj-gly.251)"
                                              :proof {:status :verified :runs []} :created-ts (System/currentTimeMillis)}))))

(defn run-pair [model rep {:keys [id fixtures a b expect tool]}]
  (let [dir (.getCanonicalPath (io/file "target/tool-reuse-eval" (or (System/getenv "LABEL") "run") (name model) (str "r" rep) (name id)))
        _ (setup! dir fixtures)
        _ (when-let [t (System/getenv "THOUGHT")]
            (let [desc (if (= "brainyard" t) sig/reasoning-field-desc (str/trim (slurp (io/resource t))))
                  f (io/file dir ".brainyard" "programs" "react" "think-act.edn")]
              (.mkdirs (.getParentFile f))
              (spit f (pr-str {:field-descs {:reasoning desc}}))))
        fixed? (= "fixed" (System/getenv "STAGE"))
        config (merge {:working-dir dir :project-dir dir :enable-tool-distillation (not fixed?)
                       :max-iterations 10 :persist-session? false :recap? false}
                      (some-> (System/getenv "CONFIG") edn/read-string))
        phase (atom :a)
        accepted (atom nil)
        ;; the accept's own word that the tool is in force: once, it failed
        ;; after writing the tool, and the eval counted it accepted (provenance P-23)
        live? (atom false)
        b-stats (atom {:iterations 0 :prompt-chars 0 :tools []})
        answers (atom [])
        ;; NO_A: turn B alone, in a session that never ran turn A — whether
        ;; the earlier turn's own tool$run is what turn B copies (byj-gly.251)
        inputs (atom [(if (System/getenv "NO_A") (constantly "/proposals") (constantly a))
                      (fn []
                        (reset! phase :accept)
                        (when fixed? (stage-fixed! dir tool))
                        (if-let [p (first (filter #(= :tool (:kind %)) (proposals/list-all config)))]
                          (do (reset! accepted (:id p)) (str "/proposals accept " (:id p)))
                          "/proposals"))
                      (fn [] (reset! phase :b) b)])
        staged (atom [])
        t0 (System/currentTimeMillis)
        r (try
            (session/run {:lm (llm/create-lm :claude-code #(System/getenv %) {:model model})
                          :tools (remove #(= "bash" (:name %)) tools/builtin-tools)
                          :config config
                          :read-input (fn [_] (when-let [f (first @inputs)] (swap! inputs rest) (f)))
                          :emit (fn [kind text]
                                  (when (= :answer kind) (swap! answers conj [@phase text]))
                                  (when (and (= :accept @phase) (= :info kind) (str/includes? (str text) "created user tool"))
                                    (reset! live? true)))
                          :on-event (fn [e d]
                                      (case e
                                        :session.tool/judged (swap! staged conj (select-keys d [:outcome :name :proof :trigger]))
                                        :agent.iteration/start (when (= :b @phase) (swap! b-stats update :iterations inc))
                                        :agent.llm/request (when (and (= :b @phase) (not (judge-request? d)))
                                                             (swap! b-stats update :prompt-chars + (reduce + (map (comp count :content) (:messages d)))))
                                        :agent.tools/calls (when (= :b @phase)
                                                             (swap! b-stats update :tools into (map :tool-name (:tool-calls d))))
                                        nil))})
            (catch Throwable e {:error (ex-message e)}))
        b-answer (some (fn [[ph t]] (when (= :b ph) t)) (reverse @answers))
        tool-id (some-> @accepted (str/replace #"^tool/" "user$tool$"))]
    {:model model :rep rep :pair id
     :staged @staged :accepted @accepted :live @live?
     :b-used-tool (boolean (and tool-id (some #{tool-id} (:tools @b-stats))))
     :b-tools (frequencies (:tools @b-stats))
     :b-correct (boolean (and b-answer (every? #(str/includes? b-answer %) expect)))
     :b-iterations (:iterations @b-stats) :b-prompt-chars (:prompt-chars @b-stats)
     :error (:error r) :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn -main [& models]
  (let [label (or (System/getenv "LABEL") "run")
        out (io/file (str "target/tool-reuse-eval/results-" label ".edn"))
        only (some-> (System/getenv "TASKS") (str/split #",") set)
        reps (or (some-> (System/getenv "REPS") parse-long) 1)]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["sonnet"])
            rep (range reps)
            pair pairs
            :when (or (nil? only) (contains? only (name (:id pair))))]
      (let [r (run-pair model rep pair)]
        (spit out (str (pr-str r) "\n") :append true)
        (println (format "r%d %-6s %-12s staged=%s accepted=%s live=%s b-used-tool=%-5s b-correct=%-5s b-iters=%d b-prompt=%d b-tools=%s %.0fs%s"
                         rep model (name (:pair r)) (pr-str (map (juxt :outcome :proof) (:staged r))) (:accepted r) (:live r)
                         (:b-used-tool r) (:b-correct r) (:b-iterations r) (:b-prompt-chars r) (pr-str (:b-tools r))
                         (/ (:elapsed-ms r) 1000.0) (if (:error r) (str " ERROR " (:error r)) "")))
        (flush)))
    (System/exit 0)))
