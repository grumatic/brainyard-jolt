(ns brainyard.project-overview-test
  "The left rail's Project section (byj-gly.384): what a project defines,
  read from a project of the test's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.project-overview :as po]))

(defn- fresh-dir [name]
  (let [d (io/file "target/project-overview-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- put! [dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)
    (.getCanonicalPath f)))

(defn- project []
  (let [dir (fresh-dir "one-of-each")]
    (put! dir "AGENTS.md" "# rules")
    (put! dir ".brainyard/config.edn" (pr-str {:agent {:config {:mcp-servers {"stubby" {:command "true"}}}}}))
    (put! dir ".brainyard/agents/reviewer/agent.edn" (pr-str {:description "Reviews a change." :instruction "Review it."}))
    (put! dir ".brainyard/skills/release-notes/SKILL.md" "---\nname: release-notes\ndescription: Write release notes.\n---\nDo it.")
    (put! dir ".claude/skills/changelog/SKILL.md" "---\nname: changelog\ndescription: Keep a changelog.\n---\nDo it.")
    (put! dir ".brainyard/tools/count-lines.edn" (pr-str {:name "count-lines"}))
    (put! dir ".brainyard/tools/count-lines.clj" "(fn [_] 1)")
    (put! dir ".brainyard/commands/review.edn" (pr-str {:command "/review" :purpose "review"}))
    (put! dir ".brainyard/hooks/notify.edn" (pr-str {:event :session.turn/end}))
    (put! dir ".brainyard/programs/react/think-act.edn" (pr-str {:demos []}))
    (put! dir ".brainyard/programs/eval/segment/datasets/gold.edn" "[]")
    (put! dir ".brainyard/predictors/user/triage.edn" (pr-str {:id "user/triage"}))
    dir))

(deftest a-project-is-what-it-defines-and-nothing-else
  (let [dir (project)
        groups (po/read-overview {:project-dir dir :working-dir dir})
        by (into {} (map (juxt :kind #(mapv :name (:items %))) groups))]
    (is (= {:instructions ["AGENTS.md" ".brainyard/config.edn"]
            :agents ["reviewer"]
            :skills ["release-notes" "changelog"]
            :tools ["count-lines"]
            :commands ["/review"]
            :hooks ["notify.edn"]
            :mcp ["stubby"]
            :programs ["eval/segment" "react/think-act"]
            :predictors ["user/triage"]}
           by)
        "the project's own, not the built-in agents nor the machine's skills")
    (is (= (str dir "/.brainyard/agents/reviewer/agent.edn")
           (:path (first (:items (first (filter #(= :agents (:kind %)) groups))))))
        "an item opens its file")
    (is (= (str dir "/.brainyard/programs/eval/segment")
           (:path (first (:items (first (filter #(= :programs (:kind %)) groups))))))
        "a program with no params opens its folder")))

(deftest kinds-are-folded-and-an-empty-one-is-not-shown
  (let [groups [{:kind :agents :title "Agents" :items [{:name "a" :path "/p/a"}]}
                {:kind :tools :title "Tools" :items []}]]
    (is (= [{:label "▸ Agents" :hint "1" :group :agents}] (po/rows groups #{})))
    (is (= ["▾ Agents" "  a"] (mapv :label (po/rows groups #{:agents}))))))

(deftest the-view-folds-opens-and-mentions
  (let [dir (project)
        opened (atom nil)
        {:keys [view refresh! state]} (po/views {:project-dir dir :working-dir dir} {:open #(reset! opened %)})]
    (refresh!)
    (loop [n 0] (when (and (nil? (:groups @state)) (< n 100)) (Thread/sleep 50) (recur (inc n))))
    (let [agents-row (first (filter #(= :agents (:group %)) ((:items view))))]
      ((:act view) agents-row)
      (let [item (first (filter #(= "  reviewer" (:label %)) ((:items view))))]
        (is item "Enter on a kind unfolds it")
        ((:act view) item)
        (is (str/ends-with? @opened "/.brainyard/agents/reviewer/agent.edn") "Enter on an item opens it")
        (is (= "@.brainyard/agents/reviewer/agent.edn" ((:put view) item)) "@ mentions it from the project")))))
