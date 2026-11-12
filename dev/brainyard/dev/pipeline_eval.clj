;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.pipeline-eval
  "Does plan → act → eval beat one plain turn? (byj-gly.17, RFC-023)

      REPS=2 jolt -A:dev -m brainyard.dev.pipeline-eval sonnet
      TASKS=rename jolt -A:dev -m brainyard.dev.pipeline-eval sonnet

  The same task, the same tools, the same model, run twice: once through the
  ReAct loop and once through the pipeline. The pipeline is checked and it runs
  and it resumes — none of which is evidence that it produces better work, which
  is the only claim that would justify what it costs.

  **The tasks are chosen where planning should help if it ever does**: several
  files, an order that matters, and an outcome a script can check. A task one
  turn can finish in one edit would measure nothing.

  Scored per criterion, and cost is reported beside the score, because a
  pipeline that wins on quality and costs four times the calls is a different
  answer from one that wins for free."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.evaluate :as ev]
            [brainyard.llm :as llm]
            [brainyard.tools :as tools]))

;; --- fixtures ----------------------------------------------------------------------

(defn- lines [& ls] (str (str/join "\n" ls) "\n"))

(def tasks
  [{:id :rename
    :prompt (str "The function `calc-total` is defined in src/billing.clj and called from "
                 "src/orders.clj and src/report.clj. Rename it to `order-total` everywhere, "
                 "so that no file mentions the old name.")
    :files {"src/billing.clj" (lines "(ns billing)" ""
                                     "(defn calc-total [items]"
                                     "  (reduce + (map :amount items)))")
            "src/orders.clj" (lines "(ns orders (:require [billing]))" ""
                                    "(defn summary [o]"
                                    "  {:total (billing/calc-total (:items o))})")
            "src/report.clj" (lines "(ns report (:require [billing]))" ""
                                    "(defn render [o]"
                                    "  (str \"Total: \" (billing/calc-total (:items o))))")}
    :criteria (fn [dir]
                (let [r #(slurp (io/file dir %))
                      all (str (r "src/billing.clj") (r "src/orders.clj") (r "src/report.clj"))]
                  [#(str/includes? (r "src/billing.clj") "defn order-total")
                   #(str/includes? (r "src/orders.clj") "billing/order-total")
                   #(str/includes? (r "src/report.clj") "billing/order-total")
                   #(not (str/includes? all "calc-total"))]))}

   {:id :three-bugs
    :prompt (str "Each file under src/ has one bug: a function that should add its two "
                 "arguments but does something else. Fix all three so each adds.")
    :files {"src/a.clj" (lines "(ns a)" "" "(defn add [x y] (- x y))")
            "src/b.clj" (lines "(ns b)" "" "(defn add [x y] (* x y))")
            "src/c.clj" (lines "(ns c)" "" "(defn add [x y] x)")}
    :criteria (fn [dir]
                (let [r #(slurp (io/file dir %))]
                  [#(str/includes? (r "src/a.clj") "(+ x y)")
                   #(str/includes? (r "src/b.clj") "(+ x y)")
                   #(str/includes? (r "src/c.clj") "(+ x y)")
                   #(not (re-find #"\(- x y\)|\(\* x y\)" (str (r "src/a.clj") (r "src/b.clj") (r "src/c.clj"))))]))}

   {:id :add-and-wire
    :prompt (str "Add a function `slugify` to src/text.clj that lower-cases a string and "
                 "replaces every run of non-alphanumeric characters with a single dash. "
                 "Then use it in src/post.clj and src/page.clj, each of which has a TODO "
                 "marking where a slug is needed.")
    :files {"src/text.clj" (lines "(ns text)" "" "(defn titlecase [s] (clojure.string/capitalize s))")
            "src/post.clj" (lines "(ns post (:require [text]))" ""
                                  "(defn url [p]"
                                  "  ;; TODO: slug of (:title p)"
                                  "  (str \"/posts/\" (:title p)))")
            "src/page.clj" (lines "(ns page (:require [text]))" ""
                                  "(defn url [p]"
                                  "  ;; TODO: slug of (:name p)"
                                  "  (str \"/pages/\" (:name p)))")}
    :criteria (fn [dir]
                (let [r #(slurp (io/file dir %))]
                  [#(str/includes? (r "src/text.clj") "defn slugify")
                   #(re-find #"lower-case" (r "src/text.clj"))
                   #(str/includes? (r "src/post.clj") "text/slugify")
                   #(str/includes? (r "src/page.clj") "text/slugify")]))}])

;; --- running ----------------------------------------------------------------------

(defn- setup! [dir files]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (.mkdirs (io/file dir))
  (doseq [[path content] files]
    (let [f (io/file dir path)]
      (.mkdirs (.getParentFile f))
      (spit f content))))

(defn- fraction [criteria]
  (/ (count (filter #(try (boolean (%)) (catch Throwable _ false)) criteria))
     (double (count criteria))))

(defn run-task [model pipeline? {:keys [id prompt files criteria]}]
  (let [dir (str "target/pipeline-eval/" model "/" (if pipeline? "pipeline" "react") "/" (name id))
        _ (setup! dir files)
        t0 (System/currentTimeMillis)
        chars (atom 0)
        r (try
            (agent/ask prompt
                       {:lm (llm/create-lm :claude-code #(System/getenv %) {:model model})
                        :tools tools/builtin-tools
                        :tree (agent/load-tree (if pipeline?
                                                 "brainyard/loops/plan-act-eval.edn"
                                                 "brainyard/loops/react.edn"))
                        :config {:working-dir dir :project-dir dir
                                 :max-iterations 15 :persist-session? false}
                        :on-event (fn [e d]
                                    (when (= :agent.llm/request e)
                                      (swap! chars + (reduce + (map (comp count :content) (:messages d))))))})
            (catch Throwable e {:error (ex-message e)}))
        score (try (fraction (criteria dir)) (catch Throwable _ 0.0))]
    {:model model :task id :pipeline? pipeline? :score score
     :llm-calls (get-in r [:usage :llm-calls]) :prompt-chars @chars
     :iterations (:iterations r) :terminated-by (:terminated-by r) :error (:error r)
     :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn- stat [rows]
  (let [ms (->> (group-by :rep rows) (sort-by key)
                (mapv (fn [[_ rs]] (/ (reduce + (map :score rs)) (double (count rs))))))
        k (count ms)
        mean (if (zero? k) 0.0 (/ (reduce + ms) (double k)))
        sd (when (> k 1) (Math/sqrt (/ (reduce + (map #(let [d (- % mean)] (* d d)) ms)) (dec k))))]
    {:score mean :completed-repeats k :repeat-scores ms
     :stddev sd :stderr (when sd (/ sd (Math/sqrt k)))}))

(defn -main [& models]
  (let [out (io/file "target/pipeline-eval/results.edn")
        only (some-> (System/getenv "TASKS") (str/split #",") set)
        reps (or (some-> (System/getenv "REPS") parse-long) 1)
        rows (atom [])]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["sonnet"])
            rep (range reps)
            task tasks
            :when (or (nil? only) (contains? only (name (:id task))))
            pipeline? [false true]]
      (let [r (assoc (run-task model pipeline? task) :rep rep)]
        (swap! rows conj r)
        (spit out (str (pr-str r) "\n") :append true)
        (println (format "r%d %-7s %-13s %-9s score=%-5s calls=%-4s chars=%-8d %.0fs%s"
                         rep model (name (:task r)) (if pipeline? "pipeline" "react")
                         (format "%.2f" (:score r)) (:llm-calls r) (:prompt-chars r)
                         (/ (:elapsed-ms r) 1000.0)
                         (if (:error r) (str " ERROR " (:error r)) "")))
        (flush)))
    (let [rs @rows
          pipe (stat (filter :pipeline? rs))
          react (stat (remove :pipeline? rs))
          calls (fn [xs] (let [cs (keep :llm-calls xs)]
                           (if (empty? cs) 0 (/ (reduce + cs) (double (count cs))))))]
      (println)
      (println (format "pipeline %.3f  %s   %.1f calls/task" (:score pipe) (pr-str (:repeat-scores pipe))
                       (calls (filter :pipeline? rs))))
      (println (format "react    %.3f  %s   %.1f calls/task" (:score react) (pr-str (:repeat-scores react))
                       (calls (remove :pipeline? rs))))
      (let [c (ev/compare-scores pipe react)]
        (println (format "diff %+.3f, margin %s, tested? %s → %s"
                         (:diff c) (if-let [m (:margin c)] (format "%.3f" m) "—") (:tested? c)
                         (if (:better? c) "the pipeline is better" "NO detectable difference"))))
      (spit out (str (pr-str {:summary {:pipeline pipe :react react}}) "\n") :append true))
    (System/exit 0)))
