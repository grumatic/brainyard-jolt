;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.tool-run-eval
  "Does one-off composition pay? (byj-gly.11) — the same multi-step tasks run
  through the unmodified loop with `tool$run` bound and without it. The prompts
  never mention tool$run; the model finds it in the tool listing or not at all.

      REPS=1 jolt -A:dev -m brainyard.dev.tool-run-eval sonnet haiku
      TASKS=json-sum jolt -A:dev -m brainyard.dev.tool-run-eval sonnet
      NO_BASH=1 jolt -A:dev -m brainyard.dev.tool-run-eval sonnet haiku
      ONLY_WITH=1 LABEL=before jolt -A:dev -m brainyard.dev.tool-run-eval sonnet haiku

  SCALE=N multiplies the fixtures' size — N times the lines, N times the
  padding — leaving every expected answer as it was: at 1 a file costs a model
  little to read, so keeping reads out of the prompt cannot show (byj-gly.243).

  ONLY_WITH=1 runs only the cells with tool$run bound — a change to how the
  prompts guide the model to it cannot move the others (byj-gly.242). LABEL
  names the results file, so two runs compare side by side.

  NO_BASH=1 unbinds `bash`: with it, the models compose in the shell, so
  without it is what measures composition over the other tools (as when a
  permission gate denies bash).

  Per turn: correctness (the expected text in the answer), iterations, LLM
  calls, prompt chars sent (the sum over every request — what composition should
  shrink, independent of provider token accounting), provider tokens, tool calls
  and tool$run calls, wall time. Results: target/tool-run-eval/results.edn."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.llm :as llm]
            [brainyard.tools :as tools]))

(defn- lines [n f] (str/join "\n" (map f (range n))))

(def scale (or (some-> (System/getenv "SCALE") parse-long) 1))

(def tasks
  "Fixtures are generated so the expected answers are known exactly."
  (let [doc-sizes (mapv #(* scale %) [12 31 7 44 19 38 25 3])
        amounts [120 75 310 42 88 205 64 150 99 17]
        urgent-files #{1 4 5 9}
        log-lines (fn [i] (lines (* scale 60) #(str "2026-09-17T10:" (format "%02d" %) " service-" i " ok request " (* % i))))]
    [{:id :largest-doc
      :prompt "Which file under docs/ has the most lines? Answer with its file name."
      :fixtures (into {} (map-indexed (fn [i n] [(str "docs/chapter-" (inc i) ".md") (lines n #(str "line " %))]) doc-sizes))
      :expect "chapter-4.md"}
     {:id :json-sum
      :prompt "Each JSON file under data/ has an `amount` field. What is the total amount across all of them? Answer with the number."
      :fixtures (into {} (map-indexed (fn [i a] [(str "data/order-" (inc i) ".json")
                                                 (str "{\"id\": " (inc i) ", \"customer\": \"c" i "\", \"amount\": " a ", \"note\": \"" (apply str (repeat (* scale 40) "x")) "\"}")])
                                      amounts))
      :expect (str (reduce + amounts))}
     {:id :urgent-notes
      :prompt "How many files under notes/ mention the word \"urgent\", in any letter case? Answer with the number."
      :fixtures (into {} (for [i (range 12)]
                           [(str "notes/note-" i ".txt")
                            (lines (* scale 20) #(cond (and (urgent-files i) (= % 7)) (if (odd? i) "This is URGENT, please review." "an Urgent item")
                                             :else (str "routine entry " %)))]))
      :expect (str (count urgent-files))}
     {:id :longest-line
      :prompt "What is the longest line, by number of characters, in any file under logs/? Answer with that line exactly."
      :fixtures (assoc (into {} (for [i (range 1 7)] [(str "logs/service-" i ".log") (log-lines i)]))
                       "logs/service-3.log" (str (log-lines 3) "\n2026-09-17T11:00 service-3 WARN slow request took 1234567 ms"))
      :expect "2026-09-17T11:00 service-3 WARN slow request took 1234567 ms"}]))

(defn- setup! [dir fixtures]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (doseq [[path content] fixtures]
    (let [f (io/file dir path)]
      (.mkdirs (.getParentFile f))
      (spit f content))))

(def no-bash? (= "1" (System/getenv "NO_BASH")))

(defn run-task [model tool-run? {:keys [id prompt fixtures expect]}]
  (let [dir (str "target/tool-run-eval/" (when (> scale 1) (str "x" scale "/")) (when no-bash? "no-bash/") model "/" (if tool-run? "with" "without") "/" (name id))
        _ (setup! dir fixtures)
        prompt-chars (atom 0)
        tool-calls (atom [])
        ;; the two costs of not knowing the language: a look at the
        ;; description first, and a body refused (byj-gly.244)
        run-lookups (atom 0)
        run-invalid (atom 0)
        t0 (System/currentTimeMillis)
        r (try
            (agent/ask prompt {:lm (llm/create-lm :claude-code #(System/getenv %) {:model model})
                               :tools (cond->> tools/builtin-tools no-bash? (remove #(= "bash" (:name %))))
                               :config {:working-dir dir :project-dir dir :max-iterations 10 :tool-run? tool-run?}
                               :on-event (fn [e d]
                                           (case e
                                             :agent.llm/request (swap! prompt-chars + (reduce + (map (comp count :content) (:messages d))))
                                             :agent.tools/calls (do (swap! tool-calls into (map :tool-name (:tool-calls d)))
                                                                    (swap! run-lookups + (count (filter #(and (= "get-tool-info" (:tool-name %))
                                                                                                              (= "tool$run" (str (get-in % [:tool-args :tool-id]))))
                                                                                                        (:tool-calls d)))))
                                             :agent.tools/results (swap! run-invalid + (count (filter #(and (= "tool$run" (:tool-name %))
                                                                                                             (str/includes? (str (:tool-result %)) "Invalid Tool Lisp"))
                                                                                                       (:results d))))
                                             nil))})
            (catch Throwable e {:error (ex-message e)}))]
    {:model model :task id :tool-run? tool-run? :bash? (not no-bash?) :scale scale
     :correct (boolean (some-> (:answer r) (str/includes? expect)))
     :iterations (:iterations r) :llm-calls (get-in r [:usage :llm-calls])
     :prompt-chars @prompt-chars
     :input-tokens (get-in r [:usage :input-tokens]) :output-tokens (get-in r [:usage :output-tokens])
     :tool-calls (count @tool-calls) :tool-run-calls (count (filter #{"tool$run"} @tool-calls))
     :tool-run-lookups @run-lookups :tool-run-invalid @run-invalid
     :tools-used (frequencies @tool-calls)
     :terminated-by (:terminated-by r) :error (:error r)
     :answer (:answer r)
     :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn -main [& models]
  (let [out (io/file (str "target/tool-run-eval/results" (some->> (System/getenv "LABEL") (str "-"))
                          (when no-bash? "-no-bash") ".edn"))
        with-only? (= "1" (System/getenv "ONLY_WITH"))
        only (some-> (System/getenv "TASKS") (str/split #",") set)
        reps (or (some-> (System/getenv "REPS") parse-long) 1)]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["sonnet"])
            rep (range reps)
            task tasks
            :when (or (nil? only) (contains? only (name (:id task))))
            tool-run? (if with-only? [true] [false true])]
      (let [r (assoc (run-task model tool-run? task) :rep rep)]
        (spit out (str (pr-str (dissoc r :answer)) "\n") :append true)
        (println (format "r%d %-6s %-13s %-8s correct=%-5s iters=%s calls=%s prompt-chars=%d tools=%d run=%d lookup=%d invalid=%d %.0fs%s"
                         rep model (name (:task r)) (if tool-run? "with" "without") (:correct r)
                         (:iterations r) (:llm-calls r) (:prompt-chars r) (:tool-calls r) (:tool-run-calls r)
                         (:tool-run-lookups r) (:tool-run-invalid r)
                         (/ (:elapsed-ms r) 1000.0) (if (:error r) (str " ERROR " (:error r)) "")))
        (flush)))
    (System/exit 0)))
