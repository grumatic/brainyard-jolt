;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.workflow-test
  "/workflow (RFC-076): a project's workflows listed, shown, added, changed
  and removed on the person's yes; and run, stage by stage, judged by the
  master, on a bd stand-in and the mock model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.channel :as channel]
            [brainyard.deps :as deps]
            [brainyard.effect :as fx]
            [brainyard.flow :as flow]
            [brainyard.json :as json]
            [brainyard.tasks :as tasks]
            [brainyard.workflow :as workflow]))

(defn- fresh-dir [name]
  (let [d (io/file "target/workflow-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(def spec
  {:schema-version 1
   :roles {:ci {:title "CI" :does "Supervises runs."}
           :design {:title "Design" :does "Decides scope and design."}
           :implement {:title "Implement" :does "Implements it and commits."}
           :review {:title "Review" :does "Reviews the branch."}}
   :workflows {:feature {:title "Feature" :does "A feature from design to review." :master :ci
                         :stages {:design {} :implement {} :review {}}
                         :links [{:from :design :to :implement
                                  :gives [{:kind :file :paths ["docs/decisions/*.md"]} {:kind :note}]}
                                 {:from :implement :to :review :gives [{:kind :note}]}]}
               :fix {:title "Fix" :master :ci
                     :stages {:implement {} :review {}}
                     :links [{:from :implement :to :review :gives [{:kind :branch} {:kind :note}]}]}}})

(defn- project [name & [m]]
  (let [dir (fresh-dir name)]
    (when-not (= :none m)
      (let [f (deps/spec-file dir)]
        (io/make-parents f)
        (spit f (pr-str (or m spec)))))
    dir))

(defn- read-back [dir] (edn/read-string (slurp (deps/spec-file dir))))

(defn- person
  "A permission-fn that answers `yes?` and keeps what it was asked."
  [asked yes?]
  (fn [req] (swap! asked conj req) (if yes? {:allowed true} {:denied true :reason "the person said no"})))

(deftest stages-are-worked-in-link-order
  (is (= [:design :implement :review] (workflow/order (get-in spec [:workflows :feature]))))
  (is (= [:b :c :a] (workflow/order {:stages {:a {} :b {} :c {}} :links [{:from :c :to :a}]}))
      "each after what links to it, ties by name"))

(deftest workflows-are-listed-and-shown
  (let [dir (project "list")
        cfg {:project-dir dir}
        text (:text (workflow/list-workflows cfg {}))]
    (is (str/includes? text "feature — Feature: A feature from design to review.\n  design → implement → review; master ci"))
    (is (str/includes? text "fix — Fix\n  implement → review; master ci"))
    (is (str/includes? text "Roles: ci (CI) — Supervises runs.; design (Design)"))
    (let [{:keys [text found?]} (workflow/show-workflow cfg {:id "feature"})]
      (is found?)
      (is (str/includes? text "  stage design: role design (Design) — Decides scope and design."))
      (is (str/includes? text "  link design → implement: gives files docs/decisions/*.md, a note: the stage's answer"))
      (is (str/includes? text "  master: ci (CI) — Supervises runs.")))
    (is (str/includes? (:text (workflow/show-workflow cfg {:id "nope"})) "No workflow nope in .brainyard/deps.edn. It has: feature, fix."))
    (testing "a project without one is told how to add one"
      (is (str/includes? (:text (workflow/list-workflows {:project-dir (project "list-none" :none)} {}))
                         "This project has no workflows")))))

(deftest a-workflow-is-added-on-the-persons-yes
  (let [dir (project "add")
        asked (atom [])
        r (workflow/save-workflow {:project-dir dir} {:permission-fn (person asked true)}
                                  {:id "hotfix" :title "Hotfix" :master "ci"
                                   :stages "{:implement {} :check {:role :verify}}"
                                   :links "[{\"from\": \"implement\", \"to\": \"check\", \"gives\": [{\"kind\": \"branch\"}]}]"
                                   :roles "{:verify {:title \"Verify\" :does \"Runs the tests on the branch.\"}}"})
        after (read-back dir)]
    (is (:written? r))
    (is (= 1 (count @asked)))
    (is (= "workflow$save" (:tool (first @asked))))
    (is (str/includes? (:display (first @asked)) "/workflow: add workflow hotfix to .brainyard/deps.edn?"))
    (is (str/includes? (:display (first @asked)) "  runs: implement → check"))
    (is (str/includes? (:display (first @asked)) "  new role verify (Verify) — Runs the tests on the branch."))
    (is (= {:title "Hotfix" :master :ci :stages {:implement {} :check {:role :verify}}
            :links [{:from :implement :to :check :gives [{:kind :branch}]}]}
           (get-in after [:workflows :hotfix])))
    (is (= {:title "Verify" :does "Runs the tests on the branch."} (get-in after [:roles :verify])))
    (is (= (:feature (:workflows spec)) (get-in after [:workflows :feature])) "the others are kept")
    (testing "a role a stage names that the project has not is added bare"
      (workflow/save-workflow {:project-dir dir} {:permission-fn (person (atom []) true)}
                              {:id "docs" :stages {:write {}}})
      (is (= {} (get-in (read-back dir) [:roles :write]))))))

(deftest a-workflow-is-changed-and-removed-on-the-persons-yes
  (let [dir (project "change")
        asked (atom [])
        yes {:permission-fn (person asked true)}]
    (testing "only what is given changes, shown as what goes and what comes"
      (is (:written? (workflow/save-workflow {:project-dir dir} yes {:id "fix" :title "Small fix" :master "none"})))
      (is (str/includes? (:display (last @asked)) "/workflow: change workflow fix in .brainyard/deps.edn?"))
      (is (str/includes? (:display (last @asked)) "- fix — Fix\n- master: ci (CI) — Supervises runs.\n+ fix — Small fix\n+ master: none"))
      (let [fix (get-in (read-back dir) [:workflows :fix])]
        (is (= "Small fix" (:title fix)))
        (is (nil? (:master fix)))
        (is (= {:implement {} :review {}} (:stages fix)))))
    (testing "the person's no writes nothing"
      (let [before (slurp (deps/spec-file dir))
            r (workflow/save-workflow {:project-dir dir} {:permission-fn (person (atom []) false)} {:id "fix" :title "Other"})]
        (is (not (:written? r)))
        (is (str/includes? (:text r) "Nothing was written (the person said no)"))
        (is (= before (slurp (deps/spec-file dir))))))
    (testing "with no person to ask, nothing is written"
      (is (not (:written? (workflow/save-workflow {:project-dir dir} {} {:id "fix" :title "Other"})))))
    (testing "what byj deps write would refuse is refused before the person is asked"
      (let [n (count @asked)]
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes
                                                          {:id "fix" :links [{:from :review :to :implement} {:from :implement :to :review}]}))
                           "workflow fix's links go round"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "fix" :links "[{:from :review :to :nowhere}]"}))
                           "names nowhere, which is not a stage"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id ".x" :stages {:a {}}})) "is not an id"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "empty"})) "needs at least one stage"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "fix" :title "Small fix"}))
                           "what was asked is what it says already"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "fix" :links "design →"}))
                           "The workflow was not saved"))
        (is (= n (count @asked)) "none of them was put to the person")))
    (testing "removing one leaves its roles"
      (is (:written? (workflow/remove-workflow {:project-dir dir} yes {:id "feature"})))
      (is (str/includes? (:display (last @asked)) "/workflow: remove workflow feature from .brainyard/deps.edn? Its runs are kept."))
      (is (= #{:fix} (set (keys (:workflows (read-back dir))))))
      (is (contains? (:roles (read-back dir)) :design))
      (is (str/includes? (:text (workflow/remove-workflow {:project-dir dir} yes {:id "feature"})) "No workflow feature")))))

;; --- runs (byj-edw.2) ------------------------------------------------------------

(defn- fake-bd
  "A bd in `bin` keeping beads as files under `bin`/b/<id> (status, type,
  title, a line each): show, update --claim, unclaim and close, as a run
  calls them, every call logged to `bin`/bd.log."
  [bin]
  (let [f (io/file bin "bd")]
    (spit f (str "#!/bin/bash\nd='" bin "'; echo \"$*\" >> \"$d/bd.log\"\n"
                 "field() { sed -n \"${2}p\" \"$d/b/$1\"; }\n"
                 "case \"$1\" in\n"
                 "  update) sed -i.bak '1s/.*/in_progress/' \"$d/b/$2\";;\n"
                 "  unclaim) sed -i.bak '1s/.*/open/' \"$d/b/$2\";;\n"
                 "  close) sed -i.bak '1s/.*/closed/' \"$d/b/$2\";;\n"
                 "  show) [ -f \"$d/b/$2\" ] || exit 1; printf '[{\"id\":\"%s\",\"status\":\"%s\",\"issue_type\":\"%s\",\"title\":\"%s\",\"description\":\"what it asks\"}]\\n' \"$2\" \"$(field $2 1)\" \"$(field $2 2)\" \"$(field $2 3)\";;\n"
                 "esac\n"))
    (.setExecutable f true)
    (.mkdirs (io/file bin "b"))))

(defn- bd-calls [bin] (let [f (io/file bin "bd.log")] (if (.isFile f) (str/split-lines (slurp f)) [])))

(defn- git! [dir & args] (apply fx/sh {:dir dir :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid" args))

(def run-spec
  (-> spec
      (assoc-in [:workflows :fix :links] [{:from :implement :to :review :gives [{:kind :note}]}])
      (assoc-in [:workflows :docs] {:title "Docs" :stages {:write {:role :design}}})))

(defn- run-setup
  "A git repository with beads, the workflows above and, given `profile`, a
  profile; a bd stand-in holding bead t-2."
  [name profile]
  (let [bin (fresh-dir (str name "-bin"))
        dir (project name run-spec)]
    (spit (io/file dir "README.md") "x\n")
    (spit (io/file dir ".gitignore") ".brainyard/workflow-runs/\n")
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-qm" "start")
    (.mkdirs (io/file dir ".beads"))
    (when profile
      (spit (io/file dir ".brainyard" "profile.edn")
            (pr-str (merge {:profile/version 1 :goal "A parser that parses." :scope {:in ["the parser"] :out ["the UI"]}} profile))))
    (fake-bd bin)
    (spit (io/file bin "b" "t-2") "open\ntask\nthe parser\n")
    {:bin bin :dir dir :cfg {:project-dir dir :working-dir dir :bd-path bin :gh-path bin}}))

(deftest the-workflow-loop-is-sound
  (is (nil? (:problems (flow/check-tree-name "brainyard/loops/workflow.edn"
                                             (flow/ctx-opts (agent/make-ctx {:config {:workflow-tools? true}})))))))

(deftest a-run-opens-or-says-why-not
  (let [{:keys [dir cfg]} (run-setup "start" {:preset :collaborator :issues [{:tracker :beads}]})]
    (is (str/includes? (:stopped (workflow/start-run (assoc cfg :project-dir (:dir (run-setup "start-np" nil))) {} {:workflow "fix" :work "x"}))
                       "This project has no profile"))
    (is (= "No workflow nope in .brainyard/deps.edn; it has docs, feature, fix." (:stopped (workflow/start-run cfg {} {:workflow "nope" :work "x"}))))
    (is (str/includes? (:stopped (workflow/start-run cfg {} {:workflow "fix" :work " "})) "Say what the run is to work on"))
    (let [r (workflow/start-run cfg {} {:workflow "fix" :work "make the parser fast"})
          kept (workflow/read-run dir (:run r))]
      (workflow/start-run (assoc cfg :session-id "s-1") {} {:workflow "docs" :work "the first run of s-1"})
      (is (false? (:stop r)))
      (is (= 1 (:run r)))
      (is (str/includes? (:brief r) "Run #1 of workflow fix (Fix): make the parser fast"))
      (is (str/includes? (:brief r) "This project's goal: A parser that parses.\nIn scope: the parser\nOut of scope: the UI"))
      (is (str/includes? (:brief r) "The run goes implement → review"))
      (is (str/includes? (:brief r) "You play stage implement now, as implement (Implement) — Implements it and commits."))
      (is (str/includes? (:brief r) "When your part is done, it is handed to stage review with a note: the stage's answer."))
      (is (= "ci (CI) — Supervises runs." (:master r)))
      (is (= :running (:status kept)))
      (is (= [:working :pending] (map :status (:stages kept))))
      (testing "the last stage is told it is"
        (is (str/includes? (:brief (workflow/start-run cfg {} {:workflow "docs" :work "the README"}))
                           "This is the run's last stage: nothing is handed on.")))
      (testing "a run left running by this session is stopped at the next start; another live one's is left"
        (workflow/start-run (assoc cfg :session-id "s-1") {} {:workflow "docs" :work "again"})
        (is (= :running (:status (workflow/read-run dir 1))))
        (is (= :stopped (:status (workflow/read-run dir 2))))
        (is (str/includes? (:stopped (workflow/read-run dir 2)) "ended without its report"))))))

(deftest the-check-says-what-a-stage-hands-on
  (let [{:keys [dir cfg]} (run-setup "check" {:preset :collaborator :issues [{:tracker :beads}]})
        _ (workflow/save-workflow cfg {:permission-fn (fn [_] {:allowed true})}
                                  {:id "fix" :links [{:from :implement :to :review
                                                      :gives [{:kind :file :paths ["src/**"]} {:kind :branch} {:kind :note}]}]})
        _ (git! dir "add" "-A") _ (git! dir "commit" "-qm" "the new link")
        {:keys [run]} (workflow/start-run cfg {} {:workflow "fix" :work "x"})
        before (:text (workflow/check-stage cfg {:run run :answer ""}))]
    (is (str/includes? before "Stage implement (implement) of run #1, turn 1 of 3."))
    (is (str/includes? before "Hands to stage review: files src/** — not ready (no file matching src/** changed in this stage); the branch's commits — not ready (no commit in this stage); a note: the stage's answer — not ready (the stage gave no answer)"))
    (is (str/includes? before "Expected: the profile has an agent commit its own work"))
    (.mkdirs (io/file dir "src"))
    (spit (io/file dir "src" "p.clj") "(ns p)\n")
    (git! dir "add" "-A") (git! dir "commit" "-qm" "the parser")
    (let [after (:text (workflow/check-stage cfg {:run run :answer "Done."}))]
      (is (str/includes? after "files src/** — ready (changed in this stage: src/p.clj)"))
      (is (str/includes? after "the branch's commits — ready (1 commit(s) in this stage)"))
      (is (str/includes? after "a note: the stage's answer — ready"))
      (is (true? (get-in (workflow/read-run dir run) [:stages 0 :gives 0 :ready])) "kept with the stage"))))

(defn- ask-loop
  "The workflow loop on the mock model: route answers `route`, the judge
  `judge` (a fn of the messages), a stage's turn `answer` (a fn of its
  brief); every stage brief kept in `briefs`."
  [cfg route judge answer briefs]
  (agent/ask "Run the fix workflow on t-2."
             {:lm {:provider :mock :format :mock
                   :responses (fn [messages]
                                (let [sys (str (:content (first messages)))
                                      user (str (:content (second messages)))]
                                  (cond
                                    (str/includes? sys "A person asked /workflow for something")
                                    (json/write-str (merge {:reasoning "r" :intent "manage" :workflow "" :work ""} route))
                                    (str/includes? sys "You supervise a run of a workflow")
                                    (json/write-str (merge {:reasoning "r" :question ""} (judge messages)))
                                    :else (do (swap! briefs conj user)
                                              (json/write-str {:reasoning "r" :tool-calls [] :answer (answer user)})))))}
              :tree (agent/load-tree "brainyard/loops/workflow.edn")
              :config (assoc cfg :workflow-tools? true)}))

(deftest a-run-works-each-stage-and-hands-on
  (let [{:keys [bin dir cfg]} (run-setup "loop" {:preset :collaborator :issues [{:tracker :beads}]})
        briefs (atom [])
        r (ask-loop cfg {:intent "run" :workflow "fix" :work "t-2 keep it small"}
                    (fn [_] {:status "done" :summary "did its part"})
                    (fn [b] (if (str/includes? b "stage implement now") "Implemented the parser; tests pass. Review the parser." "Reviewed: it passes."))
                    briefs)
        kept (workflow/read-run dir 1)]
    (is (= 2 (count @briefs)) "a turn a stage")
    (is (str/includes? (first @briefs) "Run #1 of workflow fix (Fix): Bead t-2: the parser\n\nwhat it asks\n\nkeep it small"))
    (is (str/includes? (second @briefs) "Handed to you from stage implement (implement): a note: the stage's answer. It ended saying:\n\nImplemented the parser; tests pass. Review the parser."))
    (is (str/includes? (second @briefs) "This is the run's last stage"))
    (is (some #(= "update t-2 --claim" %) (bd-calls bin)) "the bead is claimed")
    (is (some #(str/starts-with? % "close t-2 --reason /workflow run #1 of fix: did its part") (bd-calls bin)) "and closed, as collaborator allows")
    (is (str/includes? (:answer r) "/workflow run #1 — fix (Fix): t-2 keep it small\n✓ implement — did its part\n✓ review — did its part\n✓ t-2 closed\n\nEnded: Every stage is done."))
    (is (= :done (:status kept)))
    (is (= [:done :done] (map :status (:stages kept))))
    (testing "the run is listed, and the person's verdict kept with it"
      (is (str/includes? (:text (workflow/list-runs cfg {})) "#1 fix — done — t-2 keep it small"))
      (is (= "Kept with run #1: pass — fine." (:text (workflow/record-verdict cfg {:run "1" :verdict "pass" :note "fine"}))))
      (is (str/includes? (:text (workflow/list-workflows cfg {})) "last run #1 fix — done, pass — t-2 keep it small"))
      (is (= "A verdict is pass, changes or fail." (:text (workflow/record-verdict cfg {:run 1 :verdict "meh"})))))))

(deftest a-stage-goes-again-until-its-turns-are-spent
  (let [{:keys [bin dir cfg]} (run-setup "loop-retry" {:preset :collaborator :issues [{:tracker :beads}]})
        briefs (atom [])
        r (ask-loop cfg {:intent "run" :workflow "fix" :work "t-2"}
                    (fn [_] {:status "retry" :summary "tests still fail"})
                    (fn [_] (str "Turn " (count @briefs) " got to step " (count @briefs) "."))
                    briefs)]
    (is (= 3 (count @briefs)) "three turns on implement, none on review")
    (is (str/includes? (second @briefs) "Stage implement of run #1 is not finished: tests still fail\n\nThis is turn 2 of the 3 a stage gets. The last turn ended saying:\n\nTurn 1 got to step 1."))
    (is (str/includes? (second @briefs) "The stage, as it was given:\n\nRun #1 of workflow fix"))
    (is (str/includes? (:answer r) "↻ implement — tests still fail\n↻ implement — tests still fail\n↻ implement — tests still fail\n↺ t-2 released: the run did not finish it"))
    (is (str/includes? (:answer r) "Ended: Stage implement was not finished in 3 turns: tests still fail"))
    (is (some #(= "unclaim t-2" %) (bd-calls bin)))
    (is (= :stopped (:status (workflow/read-run dir 1))))))

(deftest a-stage-that-needs-the-person-ends-the-run-with-the-question
  (let [{:keys [dir cfg]} (run-setup "loop-ask" {:preset :collaborator :issues [{:tracker :beads}]})
        r (ask-loop cfg {:intent "run" :workflow "fix" :work "make it fast"}
                    (fn [_] {:status "ask" :question "May the parser drop Python 2?" :summary "needs a decision"})
                    (fn [_] "Should we drop Python 2?")
                    (atom []))]
    (is (str/includes? (:answer r) "? implement — needs a decision"))
    (is (str/includes? (:answer r) "Ended: Stage implement needs you: May the parser drop Python 2?"))
    (is (= "May the parser drop Python 2?" (get-in (workflow/read-run dir 1) [:stages 0 :question])))))

(deftest any-other-request-is-a-turn-over-the-workflow-commands
  (let [{:keys [dir cfg]} (run-setup "loop-manage" {:preset :collaborator})
        briefs (atom [])
        r (ask-loop cfg {:intent "manage"} (fn [_] {}) (fn [_] "There are three workflows.") briefs)]
    (is (= 1 (count @briefs)) "one turn")
    (is (str/starts-with? (first @briefs) "question: Run the fix workflow on t-2.\n") "asked the request, as asked")
    (is (= "There are three workflows." (:answer r)))
    (is (empty? (workflow/runs dir)))))

(deftest the-profiles-limits-end-a-run
  (let [{:keys [dir cfg]} (run-setup "limits" {:preset :collaborator :stop {:usd 1 :failures 2}})]
    (testing "spend: a stage's turn is counted as the run's, and the limit stops it before the next stage"
      (let [{:keys [run]} (workflow/start-run cfg {} {:workflow "fix" :work "x"})
            r (workflow/settle-stage cfg {} {:run run :status "done" :summary "s" :answer "a"
                                             :usage {:cost 0.6} :stage-usage {:cost 0.5}})]
        (is (true? (:stop r)))
        (is (= "$1 spent: the profile's limit." (:stopped r)))
        (is (= 0.5 (:usage-cost-usd r)) "said as the tool's spend")
        (is (= [:done :pending] (map :status (:stages (workflow/read-run dir run)))))))
    (testing "failures in a row"
      (let [{:keys [run]} (workflow/start-run cfg {} {:workflow "fix" :work "y"})
            r1 (workflow/settle-stage cfg {} {:run run :status "retry" :summary "not yet"})
            r2 (workflow/settle-stage cfg {} {:run run :status "retry" :summary "not yet"})]
        (is (false? (:stop r1)))
        (is (= "2 turns in a row did not finish a stage: the profile's limit." (:stopped r2)))))))

;; --- where a stage works (byj-edw.3) ------------------------------------------------

(deftest a-stage-works-where-its-role-says-and-is-bound-to-it
  (let [{:keys [dir cfg]} (run-setup "places" {:preset :collaborator :issues [{:tracker :beads}]})
        cfg (assoc cfg :session-id "1790000000300-wfhere01")
        yes {:permission-fn (fn [_] {:allowed true})}]
    (workflow/save-workflow cfg yes {:id "fix" :roles {:review {:session :own :worktree true}}})
    (let [{:keys [run elsewhere]} (workflow/start-run cfg {} {:workflow "fix" :work "x"})]
      (is (false? elsewhere) "implement is :upstream, and the run is not in a worktree: this turn")
      (is (= {:key :here} (get-in (workflow/read-run dir run) [:stages 0 :place])))
      (is (= {:implement ["1790000000300-wfhere01"]} (:bindings (:value (deps/read-local dir))))
          "bound to its role, as byj deps reads it")
      (let [r (workflow/settle-stage cfg {} {:run run :status "done" :summary "s" :answer "a"})
            place (get-in (workflow/read-run dir run) [:stages 1 :place])]
        (is (true? (:elsewhere r)) "review's role is :own")
        (is (= :review (:key place)))
        (is (= "workflow/fix-1-review" (:branch place)) "in a worktree of its own, made when its turn begins")
        (is (= dir (:from place)) "branched from where implement worked")))
    (testing "a run in a worktree works there, every stage in its session"
      (let [{:keys [run elsewhere stopped]} (workflow/start-run cfg {} {:workflow "docs" :work "y" :worktree "yes"})
            place (get-in (workflow/read-run dir run) [:stages 0 :place])]
        (is (true? elsewhere) stopped)
        (is (= :root (:key place)))
        (is (.isDirectory (io/file (:dir place))))
        (is (str/includes? (:out (fx/sh {:dir dir :out :string} "git" "branch" "--list" "workflow/*")) (str "workflow/docs-" run)))))))

(deftest a-stage-in-a-session-of-its-own-is-asked-there
  ;; a real `jolt run:byj --serve` on the mock model, as teams-test's members
  (let [{:keys [dir cfg]} (run-setup "delegate" {:preset :collaborator :issues [{:tracker :beads}]})
        cfg (assoc cfg :session-id "1790000000400-wfleader" :ask-timeout-ms 60000 :team-spawn-timeout-ms 90000
                   :workflow-stage-timeout-ms 60000)
        ctx {:config cfg :task-registry (tasks/registry) :lm {:provider :mock}}]
    (try
      (let [{:keys [run elsewhere]} (workflow/start-run cfg ctx {:workflow "docs" :work "the README" :worktree "yes"})
            r (workflow/delegate-stage cfg ctx {:run run :brief "count the files"})
            kept (workflow/read-run dir run)
            sid (get-in kept [:sessions :root :session-id])]
        (is elsewhere)
        (is (str/includes? (:answer r) "count the files") (pr-str r))
        (is (re-matches #"\d{13}-[0-9a-f]{8}" (str sid)) (pr-str (:sessions kept)))
        (is (= "workflow-1/root" (get-in kept [:sessions :root :label])))
        (is (= [sid] (get-in (:value (deps/read-local dir)) [:bindings :design])) "the session is bound to the stage's role")
        (testing "asked again, the same session answers"
          (workflow/delegate-stage cfg ctx {:run run :brief "and again"})
          (is (= sid (get-in (workflow/read-run dir run) [:sessions :root :session-id]))))
        (testing "the report closes the run's sessions and says where its work is"
          (let [text (:text (workflow/report-run cfg ctx {:run run :stopped "done here"}))]
            (is (str/includes? text "Worktrees, kept for you to merge or remove:"))
            (is (str/includes? text (str "(workflow/docs-" run ")")))
            (is (str/includes? text "workflow-1/root — closed")))))
      (finally
        (tasks/cancel-all! (:task-registry ctx))))))
