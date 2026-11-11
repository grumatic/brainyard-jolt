(ns brainyard.dev.paren-repair-spike
  "byj-gly.260: does rebuilding a body's closers from its indentation
  (`tool-lisp/repair-body`) recover the unbalanced bodies models actually
  write — and recover them to what the model MEANT?

      jolt -A:dev -m brainyard.dev.paren-repair-spike

  The corpus is every tool$create body that did not balance in byj-gly.258's
  eval (dev/brainyard/dev/unbalanced_bodies.edn, with the task each was
  written for). For each body: is it repaired; does the repair validate as
  Tool Lisp; and, created as a tool over its task's fixtures, does it pass the
  task's held-out cases? A repaired body can still be wrong in its logic, so
  the last column is a floor on meaning-preserving repairs, not a ceiling —
  but a repair that re-parented code would show up there as a failure."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.tool-lisp :as tl]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]))

(def corpus-file "dev/brainyard/dev/unbalanced_bodies.edn")
(def tasks-file "dev/brainyard/dev/tool_lisp_ext_tasks.edn")

(defn- fixtures! [dir fixtures]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (.mkdirs (io/file dir))
  (doseq [[path content] fixtures]
    (let [f (io/file dir path)]
      (.mkdirs (.getParentFile f))
      (spit f content))))

(defn check
  "One corpus entry → what the repair made of it."
  [{:keys [task args]} tasks]
  (let [{:keys [fixtures cases tool-id]} (get tasks task)
        r (tl/repair-body (:body args))
        dir (str "target/paren-repair-spike/" (name task))]
    (if-not r
      {:repaired false}
      (let [_ (fixtures! dir fixtures)
            reg (toolbox/registry tools/builtin-tools)
            create (:fn (first (toolbox/authoring-tools reg (toolbox/tools-dir dir))))
            ctx {:config {:working-dir dir :project-dir dir}}
            made (create (assoc args :body (:body r)) ctx)
            t (get @reg tool-id)
            passed (when t
                     (count (filter (fn [{:keys [args expect]}]
                                      (let [got (tools/call-tool t args ctx)]
                                        (= expect (if (map? got) (select-keys got (keys expect)) got))))
                                    cases)))]
        {:repaired true :valid (some? t) :error (:error made)
         :lines (count (:lines r)) :passed passed :cases (count cases)}))))

(defn -main [& _]
  (tl/set-extensions! [:vocabulary :forms :control])
  (let [corpus (edn/read-string (slurp corpus-file))
        tasks (into {} (map (juxt :id identity)) (edn/read-string (slurp tasks-file)))
        rows (mapv (fn [c] (merge (select-keys c [:label :model :task :problem]) (check c tasks))) corpus)]
    (doseq [{:keys [label model task repaired valid passed cases lines error problem]} rows]
      (println (format "%-8s %-6s %-14s repaired=%-5s valid=%-5s cases=%s lines=%s  %s"
                       label model (name task) repaired (boolean valid)
                       (if passed (str passed "/" cases) "-") (or lines "-")
                       (or (some-> error (subs 0 (min 90 (count error))) (str/replace "\n" " ")) problem))))
    (println)
    (println (count rows) "bodies:"
             (count (filter :repaired rows)) "repaired,"
             (count (filter :valid rows)) "valid Tool Lisp once repaired,"
             (count (filter #(and (:passed %) (= (:passed %) (:cases %))) rows)) "pass every case of their task")))
