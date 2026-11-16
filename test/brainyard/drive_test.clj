;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.drive-test
  "/drive (RFC-073 \"The drive loop\"): the next bead or why to stop, the
  check of what a bead's turn left, settling it as the profile says, and the
  loop over a project's goal, on a bd stand-in and the mock model."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.drive :as drive]
            [brainyard.effect :as fx]
            [brainyard.flow :as flow]
            [brainyard.json :as json]))

(defn- fresh-dir [name]
  (let [d (io/file "target/drive-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- fake-bd
  "A bd in `bin` keeping beads as files under `bin`/b/<id> (status, type,
  title, parent, a line each; labels and comments in <id>.labels and
  <id>.comments): ready (--parent, --exclude-label), update --claim,
  unclaim, label add, comments add, show, close and list, as /drive calls
  them, every call logged to `bin`/bd.log."
  [bin]
  (let [f (io/file bin "bd")]
    (spit f (str "#!/bin/bash\nd='" bin "'; echo \"$*\" >> \"$d/bd.log\"\n"
                 "field() { sed -n \"${2}p\" \"$d/b/$1\"; }\n"
                 "row() { printf '{\"id\":\"%s\",\"status\":\"%s\",\"issue_type\":\"%s\",\"title\":\"%s\",\"description\":\"\"}' \"$1\" \"$(field $1 1)\" \"$(field $1 2)\" \"$(field $1 3)\"; }\n"
                 "rows() { printf '['; sep=''; for p in $(ls \"$d/b\" | grep -v '[.]' | sort); do if eval \"$1\"; then printf '%s' \"$sep\"; row $p; sep=','; fi; done; echo ']'; }\n"
                 "parent=''; skip=''; prev=''; for a in \"$@\"; do [ \"$prev\" = --parent ] && parent=$a; [ \"$prev\" = --exclude-label ] && skip=$a; prev=$a; done\n"
                 "labelled() { [ -n \"$skip\" ] && grep -qx \"$skip\" \"$d/b/$1.labels\" 2>/dev/null; }\n"
                 "case \"$1\" in\n"
                 "  ready) rows '[ \"$(field $p 1)\" = open ] && ! labelled $p && { [ -z \"$parent\" ] || [ \"$(field $p 4)\" = \"$parent\" ]; }';;\n"
                 "  update) sed -i.bak '1s/.*/in_progress/' \"$d/b/$2\";;\n"
                 "  unclaim) sed -i.bak '1s/.*/open/' \"$d/b/$2\";;\n"
                 "  label) echo \"$4\" >> \"$d/b/$3.labels\";;\n"
                 "  comments) printf '%s\\n' \"$4\" >> \"$d/b/$3.comments\";;\n"
                 "  close) sed -i.bak '1s/.*/closed/' \"$d/b/$2\";;\n"
                 "  show) printf '['; row $2; echo ']';;\n"
                 "  list) if [ -n \"$parent\" ]; then rows '[ \"$(field $p 4)\" = \"$parent\" ] && [ \"$(field $p 1)\" != closed ]'; else echo '[]'; fi;;\n"
                 "esac\n"))
    (.setExecutable f true)
    (.mkdirs (io/file bin "b"))))

(defn- bead! [bin id status type title parent]
  (spit (io/file bin "b" id) (str status "\n" type "\n" title "\n" parent "\n")))

(defn- calls [bin] (let [f (io/file bin "bd.log")] (if (.isFile f) (str/split-lines (slurp f)) [])))

(defn- project
  "A git repository with beads (`.beads/`) and, given `profile`, a profile."
  [name profile]
  (let [dir (fresh-dir name)]
    (fx/sh {:dir dir :out :string :err :string} "git" "init" "-q")
    (spit (io/file dir "README.md") "x\n")
    (fx/sh {:dir dir :out :string :err :string} "git" "add" "-A")
    (fx/sh {:dir dir :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-qm" "start")
    (.mkdirs (io/file dir ".beads"))
    (when profile
      (let [f (io/file dir ".brainyard" "profile.edn")]
        (io/make-parents f)
        (spit f (pr-str (merge {:profile/version 1} profile)))))
    dir))

(defn- setup [name profile]
  (let [bin (fresh-dir (str name "-bin"))
        dir (project name profile)]
    (fake-bd bin)
    (bead! bin "t-1" "open" "epic" "Mini playground" "")
    (bead! bin "t-2" "open" "task" "server shell" "t-1")
    (bead! bin "t-3" "open" "task" "terminal" "t-1")
    {:bin bin :dir dir :cfg {:project-dir dir :working-dir dir :bd-path bin}}))

(deftest the-next-bead-or-why-the-run-stops
  (testing "no profile, or no beads: nothing to drive"
    (let [{:keys [dir cfg]} (setup "no-profile" nil)]
      (is (str/includes? (:stopped (drive/next-bead cfg {} {})) "no profile")))
    (let [dir (fresh-dir "no-beads")
          f (io/file dir ".brainyard" "profile.edn")]
      (io/make-parents f)
      (spit f (pr-str {:profile/version 1}))
      (is (str/includes? (:stopped (drive/next-bead {:project-dir dir :bd-path (fresh-dir "no-beads-bin")} {} {})) "beads"))))
  (testing "collaborator: the first ready bead under the goal's epic, claimed, with its brief"
    (let [{:keys [bin cfg]} (setup "next" {:preset :collaborator :goal "A workspace." :goal-epic "t-1"})
          r (drive/next-bead cfg {} {})]
      (is (false? (:stop r)))
      (is (= "t-2" (:bead r)))
      (is (str/starts-with? (:brief r) "Work on bead t-2: server shell"))
      (is (str/includes? (:brief r) "toward this project's goal (A workspace.)"))
      (is (some #(= "update t-2 --claim" %) (calls bin)))
      (is (some #(str/includes? % "ready --json --limit 20 --exclude-label human --parent t-1") (calls bin)) "under the goal's epic, a bead set aside passed over")
      (is (re-find #"^\d{4}-" (:since r)) "the run's start, for counting beads filed")
      (is (re-matches #"[0-9a-f]{40}" (:head r)))
      (testing "the next one, once that is claimed; then nothing ready"
        (is (= "t-3" (:bead (drive/next-bead cfg {} {}))))
        (is (str/includes? (:stopped (drive/next-bead cfg {} {})) "Nothing is ready under t-1")))))
  (testing "conservative: the person picks the work"
    (let [{:keys [cfg]} (setup "ask-work" {:preset :conservative :goal-epic "t-1"})
          asked (atom nil)]
      (is (str/includes? (:stopped (drive/next-bead cfg {:permission-fn (fn [r] (reset! asked r) {:denied true})} {}))
                         "You did not want t-2 worked on next"))
      (is (= "/drive: work on t-2 (server shell) next?" (:display @asked)))
      (is (= "t-2" (:bead (drive/next-bead cfg {:permission-fn (fn [_] {:allowed true})} {}))))
      (is (str/includes? (:stopped (drive/next-bead cfg {} {})) "You did not want t-3") "nobody to ask: not worked")))
  (testing "the profile's limits"
    (let [{:keys [cfg]} (setup "limits" {:preset :collaborator :goal-epic "t-1" :stop {:failures 2 :usd 1.5}})]
      (is (str/includes? (:stopped (drive/next-bead cfg {} {:failures 2})) "2 attempts in a row"))
      (is (str/includes? (:stopped (drive/next-bead cfg {} {:usage {:cost 1.6}})) "$1.5 spent"))
      (is (false? (:stop (drive/next-bead cfg {} {:failures 1 :usage {:cost 0.2}}))))))
  (testing "a retry works the same bead again, saying what it lacked"
    (let [{:keys [cfg]} (setup "retry" {:preset :collaborator :goal-epic "t-1"})
          r (drive/next-bead cfg {} {:retry "t-3" :retry-note "the work is not committed"})]
      (is (= "t-3" (:bead r)))
      (is (str/starts-with? (:brief r) "Bead t-3 (terminal) is not finished: the work is not committed")))))

;; byj-dw0: four turns each on two beads, every one starting over from the
;; brief, each running out of iterations where the last had
(deftest a-retry-goes-on-from-where-the-last-turn-got-to
  (let [{:keys [bin cfg]} (setup "retry-progress" {:preset :collaborator :goal-epic "t-1"})
        r (drive/next-bead cfg {} {:retry "t-3" :retry-note "the browser check is not finished" :attempts 1
                                   :retry-progress "The reject flow is checked; cleaning up the throwaway session."})]
    (is (str/includes? (:brief r) "This is turn 2 of the 3 /drive gives a bead."))
    (is (str/includes? (:brief r) "The last turn ended saying:\n\nThe reject flow is checked; cleaning up the throwaway session."))
    (is (str/includes? (:brief r) "rather than doing it again"))
    (is (str/includes? (:brief (drive/next-bead (assoc cfg :drive-bead-attempts 5) {} {:retry "t-3" :attempts 3}))
                       "This is turn 4 of the 5 /drive gives a bead. The last turn ended saying:\n\n(nothing)"))
    (testing "a bead set aside for the person is passed over"
      (spit (io/file bin "b" "t-2.labels") "human\n")
      (is (= "t-3" (:bead (drive/next-bead cfg {} {}))))
      (is (some #(str/includes? % "ready --json --limit 20 --exclude-label human --parent t-1") (calls bin))))))

(deftest the-check-says-what-the-turn-left
  (let [{:keys [dir cfg]} (setup "check" {:preset :collaborator :goal-epic "t-1"})
        head (str/trim (:out (fx/sh {:dir dir :out :string :err :string} "git" "rev-parse" "HEAD")))
        git #(apply fx/sh {:dir dir :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid" %&)]
    (spit (io/file dir "server.js") "x\n")
    (let [c (drive/check-bead cfg {:bead "t-2" :head head})]
      (is (false? (:clean? c)))
      (is (false? (:committed? c)))
      (is (= "open" (:status c)))
      (is (str/includes? (:text c) "Uncommitted: server.js"))
      (is (str/includes? (:text c) "No commit since the step began names t-2"))
      (is (str/includes? (:text c) "Expected: the profile has an agent commit its own work")))
    (git "add" "-A")
    (git "commit" "-qm" "Server shell (t-2)")
    (let [c (drive/check-bead cfg {:bead "t-2" :head head})]
      (is (:clean? c))
      (is (:committed? c))
      (is (str/includes? (:text c) "Server shell (t-2)")))))

;; byj-ctw: under conservative the judge read "uncommitted work is expected"
;; and called three beads done with their work uncommitted
(deftest under-conservative-a-finished-bead-is-still-committed-with-the-persons-yes
  (let [{:keys [dir cfg]} (setup "check-ask" {:preset :conservative :goal-epic "t-1"})
        head (str/trim (:out (fx/sh {:dir dir :out :string :err :string} "git" "rev-parse" "HEAD")))]
    (spit (io/file dir "server.js") "x\n")
    (let [t (:text (drive/check-bead cfg {:bead "t-2" :head head}))]
      (is (str/includes? t "Expected: the profile has the person decide each commit: a finished bead's turn runs git commit"))
      (is (str/includes? t "left uncommitted only when the person said no"))
      (is (not (str/includes? t "uncommitted work is expected"))))))

;; byj-3py: a conservative run ended with node_modules/ untracked, and the
;; judge called the beads done
(deftest installed-dependencies-are-said-as-such
  (let [{:keys [dir cfg]} (setup "deps" {:preset :collaborator :goal-epic "t-1"})
        head (str/trim (:out (fx/sh {:dir dir :out :string :err :string} "git" "rev-parse" "HEAD")))]
    (.mkdirs (io/file dir "node_modules" "ws"))
    (spit (io/file dir "node_modules" "ws" "index.js") "x")
    (spit (io/file dir "server.js") "x\n")
    (let [c (drive/check-bead cfg {:bead "t-2" :head head})]
      (is (= ["node_modules"] (:dependencies c)))
      (is (str/includes? (:text c) "Installed dependencies, uncommitted: node_modules — a project lists these in .gitignore")))
    (spit (io/file dir ".gitignore") "node_modules/\n")
    (is (empty? (:dependencies (drive/check-bead cfg {:bead "t-2" :head head}))) "listed in .gitignore: nothing to say")))

(deftest settling-a-bead-as-the-profile-says
  (testing "collaborator: done closes it, on its own"
    (let [{:keys [bin cfg]} (setup "settle" {:preset :collaborator :goal-epic "t-1"})
          r (drive/settle cfg {} {:bead "t-2" :title "server shell" :status "done" :summary "built and tested"
                                  :failures 2 :log "" :work-usage {:cost 0.25}})]
      (is (some #(str/starts-with? % "close t-2 --reason /drive: built and tested") (calls bin)))
      (is (= 0 (:failures r)))
      (is (= "✓ t-2 server shell — built and tested (closed)" (:log r)))
      (is (= 0.25 (:usage-cost-usd r)) "the bead's turn's spend, for the run's usage")
      (is (str/includes? (:log (drive/settle cfg {} {:bead "t-3" :title "terminal" :status "done" :summary "s"
                                                     :bead-status "closed" :log "a"}))
                         "a\n✓ t-3 terminal — s (closed in its turn)"))))
  (testing "conservative: the person is asked before it closes"
    (let [{:keys [bin cfg]} (setup "settle-ask" {:preset :conservative :goal-epic "t-1"})
          r (drive/settle cfg {:permission-fn (fn [_] {:denied true :reason "not yet"})}
                          {:bead "t-2" :title "server shell" :status "done" :summary "s"})]
      (is (not-any? #(str/starts-with? % "close") (calls bin)))
      (is (str/includes? (:log r) "(left open: not yet)"))))
  (testing "retry counts a failure; ask and stop end the run"
    (let [{:keys [cfg]} (setup "settle-more" {:preset :collaborator})]
      (is (= {:failures 2 :retry "t-2" :attempts 1 :progress "Two of five tests fail."}
             (select-keys (drive/settle cfg {} {:bead "t-2" :status "retry" :summary "tests fail" :failures 1
                                                :work "  Two of five tests fail.  "})
                          [:failures :retry :attempts :progress])))
      (let [r (drive/settle cfg {} {:bead "t-2" :status "ask" :question "Add auth to the scope?" :summary "needs auth"})]
        (is (true? (:stop r)))
        (is (= "t-2 needs you: Add auth to the scope?" (:stopped r))))
      (is (str/includes? (:stopped (drive/settle cfg {} {:bead "t-2" :status "stop" :summary "node is missing"}))
                         "t-2 cannot go on: node is missing")))))

(deftest a-bead-whose-turns-are-spent-is-set-aside-for-the-person
  (let [{:keys [bin cfg]} (setup "set-aside" {:preset :collaborator :goal-epic "t-1"})
        _ (drive/next-bead cfg {} {})
        args {:bead "t-2" :title "server shell" :status "retry" :summary "the browser check ran out of steps"
              :failures 4 :log "a" :work "Checked two of three; stopping the dev server."}]
    (is (= 3 (drive/bead-attempts {})) "three turns unless config says otherwise")
    (is (= "t-2" (:retry (drive/settle cfg {} (assoc args :attempts 1)))) "its second turn: another")
    (let [r (drive/settle cfg {} (assoc args :attempts 2))]
      (is (= "" (:retry r)) "its third: no other")
      (is (= {:failures 5 :attempts 0 :progress "" :stop false} (select-keys r [:failures :attempts :progress :stop]))
          "a failure still, and the run goes on")
      (is (= (str "a\n⏸ t-2 server shell — the browser check ran out of steps; set aside after 3 turns, for you"
                  " (bd human list; bd label remove t-2 human to drive it again)")
             (:log r)))
      (is (some #(= "unclaim t-2" %) (calls bin)) "its claim released, not left for the next run to pass over")
      (is (some #(= "label add t-2 human" %) (calls bin)))
      (let [note (slurp (io/file bin "b" "t-2.comments"))]
        (is (str/starts-with? note "/drive set this bead aside after 3 turns that did not finish it. The last was judged: the browser check ran out of steps"))
        (is (str/includes? note "Checked two of three; stopping the dev server."))
        (is (str/includes? note "bd label remove t-2 human")))
      (is (= "t-3" (:bead (drive/next-bead cfg {} {}))) "the next ready bead"))
    (testing "one turn a bead, by config"
      (is (= "" (:retry (drive/settle (assoc cfg :drive-bead-attempts 1) {} (assoc args :bead "t-3" :attempts 0))))))))

(deftest the-drive-loop-is-sound
  (is (nil? (:problems (flow/check-tree-name "brainyard/loops/drive.edn"
                                             (flow/ctx-opts (agent/make-ctx {:config {:drive-tools? true}})))))))

(deftest drive-works-the-goals-beads-until-nothing-is-ready
  ;; each bead a turn of the ReAct loop (the mock answers at once), judged
  ;; done, closed as collaborator allows, until nothing is ready
  (let [{:keys [bin dir]} (setup "loop" {:preset :collaborator :goal-epic "t-1"})
        judged (atom 0)
        r (agent/ask "Work through this project's goal."
                     {:lm {:provider :mock :format :mock
                           :responses (fn [messages]
                                        (let [sys (str (:content (first messages)))]
                                          (if (str/includes? sys "A coding agent just took one turn on a bead")
                                            (do (swap! judged inc)
                                                (json/write-str {:reasoning "r" :status "done" :question "" :summary "done and checked"}))
                                            (json/write-str {:reasoning "r" :tool-calls [] :answer "Done: built it and ran the tests."}))))}
                      :tree (agent/load-tree "brainyard/loops/drive.edn")
                      :config {:project-dir dir :working-dir dir :drive-tools? true :bd-path bin}})]
    (is (= 2 @judged) "one judgement a bead")
    (is (some #(str/starts-with? % "close t-2") (calls bin)))
    (is (some #(str/starts-with? % "close t-3") (calls bin)))
    (is (str/includes? (:answer r) "/drive under t-1:\n✓ t-2 server shell — done and checked (closed)\n✓ t-3 terminal — done and checked (closed)"))
    (is (str/includes? (:answer r) "Stopped: Nothing is ready under t-1"))
    (is (str/includes? (:answer r) "Still open under t-1: nothing"))))

(deftest drive-sets-aside-a-bead-it-cannot-finish-and-goes-on
  ;; t-2's turns never finish: three turns, each brief after the first
  ;; carrying what the last ended saying, then set aside; t-3 is worked
  (let [{:keys [bin dir]} (setup "loop-aside" {:preset :collaborator :goal-epic "t-1"})
        briefs (atom [])
        r (agent/ask "Work through this project's goal."
                     {:lm {:provider :mock :format :mock
                           :responses (fn [messages]
                                        (let [sys (str (:content (first messages)))
                                              all (str/join "\n" (map (comp str :content) messages))]
                                          (if (str/includes? sys "A coding agent just took one turn on a bead")
                                            (json/write-str {:reasoning "r" :question ""
                                                             :status (if (str/includes? all "t-2") "retry" "done")
                                                             :summary (if (str/includes? all "t-2") "ran out of steps" "done and checked")})
                                            (let [user (str (:content (second messages)))]
                                              (swap! briefs conj user)
                                              (json/write-str {:reasoning "r" :tool-calls []
                                                               :answer (str "Turn " (count @briefs) " got to step " (count @briefs) ".")})))))}
                      :tree (agent/load-tree "brainyard/loops/drive.edn")
                      :config {:project-dir dir :working-dir dir :drive-tools? true :bd-path bin}})
        t2 (filterv #(str/includes? % "t-2") @briefs)]
    (is (= 3 (count t2)) "three turns on t-2, no more")
    (is (str/includes? (nth t2 1) "This is turn 2 of the 3") )
    (is (str/includes? (nth t2 1) "The last turn ended saying:\n\nTurn 1 got to step 1."))
    (is (str/includes? (nth t2 2) "Turn 2 got to step 2."))
    (is (some #(= "label add t-2 human" %) (calls bin)))
    (is (some #(str/starts-with? % "close t-3") (calls bin)))
    (is (str/includes? (:answer r) "⏸ t-2 server shell — ran out of steps; set aside after 3 turns"))
    (is (str/includes? (:answer r) "✓ t-3 terminal — done and checked (closed)"))
    (is (str/includes? (:answer r) "Stopped: Nothing is ready under t-1"))
    (is (str/includes? (:answer r) "Still open under t-1: t-2 server shell"))))
