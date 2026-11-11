(ns brainyard.issues-test
  "The left rail's Issues section (byj-gly.402): which tracker a project has,
  what bd and gh say parsed, and the rows — epics folded, each one's issues a
  dependency graph in lanes with status and priority. No test runs bd or gh:
  a read is stubbed, so the suite never reads this repository's own issues."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.issues :as issues]
            [ebb.core :as m]))

(defn- fresh-dir
  "A directory outside this repository: one under `target/` is inside it."
  [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-issues-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(deftest the-tracker-is-what-the-project-directory-holds
  (let [beads (fresh-dir "beads") gh (fresh-dir "gh") plain (fresh-dir "plain")]
    (.mkdirs (io/file beads ".beads"))
    (.mkdirs (io/file gh ".git"))
    (spit (io/file gh ".git" "config") "[remote \"origin\"]\n\turl = git@github.com:acme/widgets.git\n")
    (is (= :beads (issues/tracker beads)))
    (is (= :github (issues/tracker gh)))
    (is (nil? (issues/tracker plain)))
    (let [sub (io/file beads "sub")]
      (.mkdirs sub)
      (is (nil? (issues/tracker sub)) "only the project directory itself, never a parent's"))))

(deftest bd-finds-beads-as-bd-does
  ;; byj-gly.458: what the Memory section asks before running bd
  (let [root (fresh-dir "found")
        sub (doto (io/file root "a" "b") .mkdirs)
        plain (fresh-dir "not-found")]
    (.mkdirs (io/file root ".beads"))
    (is (issues/beads-found? root {:beads-dir nil}))
    (is (issues/beads-found? sub {:beads-dir nil}) "from a directory under the one that has them")
    (is (not (issues/beads-found? plain {:beads-dir nil})) "where none is, none")
    (is (issues/beads-found? plain {:beads-dir "/somewhere/.beads"}) "BEADS_DIR names one")))

(deftest what-the-trackers-say-is-parsed
  (testing "bd list --json: blocks dependencies, not a parent or a related one"
    (is (= [{:id "x-1.2" :title "Pick a model" :status :in-progress :priority 2 :type :task :parent "x-1"
             :blocked-by ["x-1.1"]}]
           (issues/parse-beads
            "[{\"id\":\"x-1.2\",\"title\":\"Pick a model\",\"status\":\"in_progress\",\"priority\":2,\"issue_type\":\"task\",\"parent\":\"x-1\",
               \"dependencies\":[{\"depends_on_id\":\"x-1\",\"type\":\"parent-child\"},{\"depends_on_id\":\"x-1.1\",\"type\":\"blocks\"},
                                 {\"depends_on_id\":\"x-9\",\"type\":\"related\"}]}]"))))
  (testing "gh issue list --json: a priority from its labels"
    (is (= [{:id "#12" :number 12 :title "Crash on start" :status :open :priority 1 :blocked-by []}
            {:id "#13" :number 13 :title "Docs" :status :open :priority nil :blocked-by []}]
           (issues/parse-github
            "[{\"number\":12,\"title\":\"Crash on start\",\"labels\":[{\"name\":\"bug\"},{\"name\":\"priority: high\"}]},
              {\"number\":13,\"title\":\"Docs\",\"labels\":[]}]"))))
  (is (= [0 3 2 nil] (map issues/label-priority [["P0"] ["priority/low"] ["bug" "priority-2"] ["bug"]]))))

(defn- i [id & {:as m}] (merge {:id id :title id :status :open :priority 2 :blocked-by []} m))

(defn- drawn [rows] (mapv #(str/trimr (:label %)) rows))

(deftest an-epics-issues-are-a-graph-in-lanes
  (testing "an issue comes before what blocks it, as a commit before its parents"
    (is (= ["c" "b" "a"] (map :id (issues/graph-order [(i "a") (i "b" :blocked-by ["a"]) (i "c" :blocked-by ["b"])])))))
  (testing "otherwise in progress first, then blocked, then by priority"
    (is (= ["p" "q" "r" "s"] (map :id (issues/graph-order [(i "s" :priority 3) (i "r" :priority 1)
                                                            (i "q" :status :blocked) (i "p" :status :in-progress)])))))
  (testing "two issues waiting on one: blocked, their lanes joining where it is, ready"
    (is (= ["  ● P1 b"
            "  │ ● P2 a"
            "  ○─╯ P1 base"]
           (drawn (issues/group-rows [(i "base" :priority 1) (i "a" :blocked-by ["base"]) (i "b" :priority 1 :blocked-by ["base"])]
                                     #{"base" "a" "b"})))))
  (testing "status and priority: ◐ in progress, ● blocked, and the badge's colour"
    (let [[row] (issues/group-rows [(i "w" :status :in-progress :priority 0)] #{"w"})]
      (is (= "  ◐ P0 w" (:label row)))
      (is (some #(= ["P0" {:color :red :bold true}] %) (:segments row)))))
  (testing "a blocker in another epic is named, and the issue is blocked"
    (let [[row] (issues/group-rows [(i "a" :blocked-by ["elsewhere"])] #{"a" "elsewhere"})]
      (is (str/starts-with? (:label row) "  ●"))
      (is (= "⊣ elsewhere" (:hint row)))
      (is (= {:color :red} (:hint-style row)) "red, as the blocked node is")))
  (testing "a blocker already closed blocks nothing"
    (is (= "  ○ P2 a" (:label (first (issues/group-rows [(i "a" :blocked-by ["done"])] #{"a"})))))))

(deftest epics-are-folded-headings
  (let [issues [(i "e" :title "Share M3" :type :epic :priority 1)
                (i "e.1" :parent "e" :blocked-by ["e.2"])
                (i "e.2" :parent "e" :priority 1)
                (i "lone" :priority 3)
                (i "f" :title "Releases" :type :epic :priority 2)
                (i "f.1" :parent "f")]]
    (is (= ["▸ P1 Share M3" "▸ P2 Releases" "▸ no epic"] (drawn (issues/rows issues #{}))))
    (is (= ["2" "1" "1"] (map :hint (issues/rows issues #{}))) "each counting its issues")
    (is (= ["▾ P1 Share M3" "  ● P2 e.1" "  ○ P1 e.2" "▸ P2 Releases" "▸ no epic"] (drawn (issues/rows issues #{"e"}))))
    (testing "GitHub's issues, with no epics, are one graph with no heading"
      (is (= ["  ○ P1 #1" "  ○ #2"]
             (drawn (issues/rows [(i "#2" :priority nil) (i "#1" :priority 1)] #{})))))))

(deftest the-view-reads-folds-and-mentions
  (let [dir (fresh-dir "view")]
    (testing "no tracker: the section says beads are not available and /init sets them up (byj-gly.458)"
      (let [{:keys [view refresh! state]} (issues/views dir)]
        (is (= "Issues · beads" (:title view)))
        (refresh!)
        (loop [n 0] (when (and (not (:unavailable @state)) (< n 100)) (Thread/sleep 20) (recur (inc n))))
        (is (= ["  beads not available" "  /init sets it up"] (drawn ((:items view)))))
        (testing "and reads them once /init has made them, with no restart"
          (.mkdirs (io/file dir ".beads"))
          (with-redefs [issues/read-task (fn [_ kind] (m/sp [(i "x" :title (name kind))]))]
            (reset! state {})
            (refresh!)
            (loop [n 0] (when (and (nil? (:issues @state)) (< n 100)) (Thread/sleep 20) (recur (inc n))))
            (is (= [(i "x" :title "beads")] (:issues @state))))
          (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath (io/file dir ".beads"))))))
    (with-redefs [issues/read-task (fn [_ _] (m/sp [(i "e" :type :epic :title "Epic") (i "e.1" :parent "e")]))]
      (let [{:keys [view refresh! state]} (issues/views dir {:kind :beads})]
        (is (= "Issues · beads" (:title view)))
        (is (= ["  reading…"] (drawn ((:items view)))))
        (refresh!)
        (loop [n 0] (when (and (nil? (:issues @state)) (< n 100)) (Thread/sleep 20) (recur (inc n))))
        (is (= ["▸ P2 Epic"] (drawn ((:items view)))))
        ((:act view) (first ((:items view))))
        (is (= ["▾ P2 Epic" "  ○ P2 e.1"] (drawn ((:items view)))) "Enter unfolds an epic")
        (is (= "e.1" ((:put view) (second ((:items view))))) "@ mentions an issue by its id")))
    (testing "a read that fails says why in the section"
      (with-redefs [issues/read-task (fn [_ _] (m/sp (throw (ex-info "bd: no database" {}))))]
        (let [{:keys [view refresh! state]} (issues/views dir {:kind :beads})]
          (refresh!)
          (loop [n 0] (when (and (nil? (:error @state)) (< n 100)) (Thread/sleep 20) (recur (inc n))))
          (is (= ["  could not read: bd: no database"] (drawn ((:items view))))))))))
