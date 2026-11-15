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
  title, parent, a line each): ready, update --claim, show, close and list,
  as /drive calls them, every call logged to `bin`/bd.log."
  [bin]
  (let [f (io/file bin "bd")]
    (spit f (str "#!/bin/bash\nd='" bin "'; echo \"$*\" >> \"$d/bd.log\"\n"
                 "field() { sed -n \"${2}p\" \"$d/b/$1\"; }\n"
                 "row() { printf '{\"id\":\"%s\",\"status\":\"%s\",\"issue_type\":\"%s\",\"title\":\"%s\",\"description\":\"\"}' \"$1\" \"$(field $1 1)\" \"$(field $1 2)\" \"$(field $1 3)\"; }\n"
                 "rows() { printf '['; sep=''; for p in $(ls \"$d/b\" | grep -v '[.]' | sort); do if eval \"$1\"; then printf '%s' \"$sep\"; row $p; sep=','; fi; done; echo ']'; }\n"
                 "parent=''; prev=''; for a in \"$@\"; do [ \"$prev\" = --parent ] && parent=$a; prev=$a; done\n"
                 "case \"$1\" in\n"
                 "  ready) rows '[ \"$(field $p 1)\" = open ] && { [ -z \"$parent\" ] || [ \"$(field $p 4)\" = \"$parent\" ]; }';;\n"
                 "  update) sed -i.bak '1s/.*/in_progress/' \"$d/b/$2\";;\n"
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
      (is (some #(str/includes? % "ready --json --limit 20 --parent t-1") (calls bin)) "under the goal's epic")
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
      (is (= {:failures 2 :retry "t-2"}
             (select-keys (drive/settle cfg {} {:bead "t-2" :status "retry" :summary "tests fail" :failures 1}) [:failures :retry])))
      (let [r (drive/settle cfg {} {:bead "t-2" :status "ask" :question "Add auth to the scope?" :summary "needs auth"})]
        (is (true? (:stop r)))
        (is (= "t-2 needs you: Add auth to the scope?" (:stopped r))))
      (is (str/includes? (:stopped (drive/settle cfg {} {:bead "t-2" :status "stop" :summary "node is missing"}))
                         "t-2 cannot go on: node is missing")))))

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
