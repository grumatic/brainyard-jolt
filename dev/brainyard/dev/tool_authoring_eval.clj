(ns brainyard.dev.tool-authoring-eval
  "The Tool Lisp authoring eval (RFC-010 'Authoring evidence') against the REAL
  implementation: the unmodified ReAct loop with its default registry, so the
  model sees the `tool$*` commands and `user$tool$<name>` ids, and
  tools are persisted and reloaded the production way.

      REPS=1 jolt -A:dev -m brainyard.dev.tool-authoring-eval haiku sonnet
      TASKS=shout,tail jolt -A:dev -m brainyard.dev.tool-authoring-eval sonnet

  byj-gly.258 runs it on dev/brainyard/dev/tool_lisp_ext_tasks.edn, once per
  language (`EXT` is the extension set, comma-separated; `LABEL` names the
  condition in the results):

      TASKS_FILE=dev/brainyard/dev/tool_lisp_ext_tasks.edn LABEL=base jolt -A:dev -m brainyard.dev.tool-authoring-eval haiku sonnet opus
      TASKS_FILE=… LABEL=ext EXT=vocabulary,forms,control jolt -A:dev -m brainyard.dev.tool-authoring-eval haiku sonnet opus

  byj-gly.278 runs it on dev/brainyard/dev/tool_lisp_reach_tasks.edn (tasks that
  want tier D: state across calls, the clock, many independent reads) and on
  the ext tasks, with and without `reach`:

      TASKS_FILE=dev/brainyard/dev/tool_lisp_reach_tasks.edn LABEL=reach-off EXT=vocabulary,forms,control jolt -A:dev -m brainyard.dev.tool-authoring-eval haiku sonnet
      TASKS_FILE=… LABEL=reach-on EXT=vocabulary,forms,control,reach jolt -A:dev -m brainyard.dev.tool-authoring-eval haiku sonnet

  byj-gly.279 runs the ext tasks with and without `reach`, haiku twice and
  sonnet once, to see what tier D costs the tasks that do not want it:

      TASKS_FILE=dev/brainyard/dev/tool_lisp_ext_tasks.edn LABEL=ext-reach-off EXT=vocabulary,forms,control REPS=2 jolt -A:dev -m brainyard.dev.tool-authoring-eval haiku
      TASKS_FILE=… LABEL=ext-reach-on EXT=vocabulary,forms,control,reach REPS=2 jolt -A:dev -m brainyard.dev.tool-authoring-eval haiku

  and the same with REPS=1 for sonnet.

  A case may say `:matches {:k "regex"}` for a value no run can predict (a
  time). `:reach-used` is what of tier D the kept body names.

  The spike that established the premise is brainyard.dev.tool-lisp-spike; this
  re-measures it on what shipped. Results: target/tool-authoring-eval/."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.llm :as llm]
            [brainyard.store :as store]
            [brainyard.tool-lisp :as tl]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]))

(def tasks-resource "brainyard/dev/tool_authoring_tasks.edn")

(defn- setup-fixtures! [dir fixtures pre]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (.mkdirs (io/file dir))
  (doseq [[path content] fixtures]
    (let [f (io/file dir path)]
      (.mkdirs (.getParentFile f))
      (spit f content)))
  ;; prerequisite user tools, persisted the way tool$create persists them
  (let [reg (toolbox/registry tools/builtin-tools)
        create (:fn (first (toolbox/authoring-tools reg (toolbox/tools-dir dir))))]
    (doseq [p pre]
      (let [r (create p {:config {:working-dir dir}})]
        (when (:error r) (throw (ex-info (str "prerequisite tool failed: " (:error r)) {:pre p})))))))

(def ^:private refusal-markers
  ["is not allowed in Tool Lisp" "unknown symbol" "Java interop is not allowed" "named fns are not allowed"
   "body does not read" "delimiters do not balance"])

(defn run-task [model {:keys [id tool-id prompt fixtures pre cases]} & [{:keys [ext label]}]]
  (let [dir (str "target/tool-authoring-eval/" (or label "default") "/" model "/" (name id))
        refused (atom 0)
        _ (setup-fixtures! dir fixtures pre)
        creates (atom [])
        tests (atom 0)
        recovered (atom 0)
        t0 (System/currentTimeMillis)
        result (try
                 (agent/ask prompt {:lm (llm/create-lm :claude-code #(System/getenv %) {:model model})
                                    ;; its own project: otherwise the project is this
                                    ;; repository, and the tool, its sessions and its
                                    ;; trajectories land in its .brainyard (byj-gly.258)
                                    :config {:working-dir dir :project-dir dir :max-iterations 8
                                             :tool-lisp-extensions (vec ext)}
                                    :on-event (fn [e d]
                                                (when (= e :agent.tools/recovered) (swap! recovered inc))
                                                (when (= e :agent.tools/results)
                                                  (doseq [{:keys [tool-name tool-result]} (:results d)]
                                                    ;; a body refused, by whichever command took it
                                                    (when (and (str/starts-with? (str tool-name) "tool$")
                                                               (some #(str/includes? (str tool-result) %) refusal-markers))
                                                      (swap! refused inc))
                                                    (case tool-name
                                                      "tool$create" (swap! creates conj tool-result)
                                                      "tool$test" (swap! tests inc)
                                                      nil))))})
                 (catch Throwable e {:error (ex-message e)}))
        ;; reload from disk into a fresh registry: what the next session would
        ;; see — in the language the run spoke, with tier D's store bound when
        ;; it was (byj-gly.278)
        _ (tl/set-extensions! ext)
        reg (toolbox/registry (cond-> tools/builtin-tools
                                (contains? (set ext) :reach) (concat (store/commands {}))))
        {:keys [loaded skipped]} (toolbox/load-user-tools! reg (toolbox/tools-dir dir))
        defined? (contains? (set loaded) tool-id)
        ;; run the cases against a CLEAN copy of the fixtures: the task's
        ;; expectations are about the fixture files, and the persisted tool
        ;; under .brainyard/tools would otherwise be found by a grep over "."
        check-dir (str dir "-check")
        _ (setup-fixtures! check-dir fixtures [])
        ctx {:config {:working-dir check-dir :project-dir check-dir}}
        checks (when defined?
                 (vec (for [{:keys [args expect matches]} cases
                            :let [got (tools/call-tool (get @reg tool-id) args ctx)]]
                        {:args args :expect expect :got got
                         :pass (and (= expect (if (map? got) (select-keys got (keys expect)) got))
                                    (every? (fn [[k re]] (and (map? got) (string? (get got k))
                                                              (re-matches (re-pattern re) (get got k))))
                                            matches))})))]
    {:model model :task id :tool-id tool-id :label label :ext (vec ext)
     :refused-bodies @refused
     :defined defined?
     :create-attempts (count @creates)
     :first-attempt-valid (boolean (some-> (first @creates) (str/includes? ":persisted")))
     :errors (vec (filter #(str/includes? % "Invalid tool definition") @creates))
     :reload-skipped skipped
     :self-tests @tests
     :recovered-iterations @recovered
     :cases-passed (count (filter :pass checks)) :cases (count cases)
     :checks checks
     :body (some-> (get @reg tool-id) :body)
     :reach-used (let [b (str (some-> (get @reg tool-id) :body))]
                   (vec (for [[k re] [[:pmap #"\(pmap\b"] [:clock #"\((now|now-ms)\)"] [:store #"store\$"]]
                              :when (re-find re b)]
                          k)))
     :answer (:answer result)
     :terminated-by (:terminated-by result) :iterations (:iterations result)
     :llm-calls (get-in result [:usage :llm-calls])
     :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn -main [& models]
  (let [tasks (edn/read-string (slurp (if-let [f (System/getenv "TASKS_FILE")] (io/file f) (io/resource tasks-resource))))
        ext (some->> (System/getenv "EXT") (#(str/split % #",")) (remove str/blank?) (map keyword) set)
        label (or (System/getenv "LABEL") (if (seq ext) "ext" "base"))
        out (io/file (str "target/tool-authoring-eval/results-" label ".edn"))
        only (some-> (System/getenv "TASKS") (str/split #",") set)
        reps (or (some-> (System/getenv "REPS") parse-long) 1)]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["haiku"])
            rep (range reps)
            task tasks
            :when (or (nil? only) (contains? only (name (:id task))))]
      (let [r (assoc (run-task model task {:ext ext :label label}) :rep rep)]
        (spit out (str (pr-str r) "\n") :append true)
        (println (format "%s r%d %-7s %-18s defined=%-5s attempts=%d first-valid=%-5s refused=%d cases=%d/%d tests=%d recovered=%d calls=%s reach=%s %.0fs"
                         label rep model (name (:task r)) (:defined r) (:create-attempts r) (:first-attempt-valid r) (:refused-bodies r)
                         (:cases-passed r) (:cases r) (:self-tests r) (:recovered-iterations r) (:llm-calls r)
                         (str/join "," (map name (:reach-used r))) (/ (:elapsed-ms r) 1000.0)))
        (flush)))
    (System/exit 0)))
