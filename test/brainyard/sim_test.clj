;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.sim-test
  "The simulation's runner (RFC-041, byj-gly.315.2), on the mock provider and a
  tmux server of the test's own: a person's lines typed into a real inline
  session, a permission prompt answered by the plan and recorded, each stage
  in a process resuming the same session, its checks, and the report."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.bt :as bt]
            [brainyard.config]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.sim :as sim]))

(def ^:private poll-ms
  "The mock model answers at once: the screen read five times as often as
  against a live one (byj-gly.442)."
  200)

(defn- tmux? [] (zero? (:exit (fx/sh {:out :string :err :string} "sh" "-c" "command -v tmux"))))

(deftest the-screen-says-where-the-session-stands
  (is (= {:state :ready :prompts 2} (sim/screen-state "x\nyou> hello\nan answer\nyou> ")))
  (is (= {:state :busy :prompts 1} (sim/screen-state "you> hello\nworking…")))
  (is (= {:state :asking :prompts 1 :asking-for "pkill -f x"}
         (sim/screen-state "you> go\npermission: pkill -f x\n[y]es once / [n]o / [a]lways / [d]eny always > ")))
  (is (= :exited (:state (sim/screen-state "you> \n[sim: exited 0]")))))

(deftest a-plan-is-played-stage-by-stage-and-reported
  (when (tmux?)
    (let [label (str "byj-sim-test-" (random-uuid))
          dir (.getCanonicalPath (io/file "target" "sim-test" (str (System/nanoTime))))]
      (binding [sim/*tmux* ["tmux" "-L" label] sim/*poll-ms* poll-ms]
        (try
          (let [r (sim/run-plan! (sim/load-plan "test/fixtures/sim/plan.edn") {:dir dir})
                [long hello ask] (:stages r)]
            (testing "a line past 1024 characters is taken, not lost at the prompt (byj-gly.315.12)"
              (is (= [:answered] (map :ended (:turns long))))
              (is (str/includes? (slurp (io/file dir ".sim" "long.screen.txt")) "(mock) long word")))
            (testing "each stage's checks decide its verdict; {{plan}} reaches the plan's own scripts"
              (is (= [:hello :pass] [(:id hello) (:verdict hello)]))
              (is (= ["true" "script"] (map :name (:checks hello))))
              (is (= [:ask :fail] [(:id ask) (:verdict ask)])))
            (testing "each stage's findings: here the prompt, and nothing else"
              (is (= [] (:findings hello)))
              (is (= [:permission-prompt] (map :rule (:findings ask)))))
            (testing "a permission prompt is answered by the plan and recorded — it never takes a person's line"
              (is (empty? (:prompts hello)))
              (is (= [{:asking-for "pkill -f byj-sim-nothing-here" :answer "n"}]
                     (map #(select-keys % [:asking-for :answer]) (:prompts ask)))))
            (testing "each stage a process of its own, resuming the one session"
              (is (some? (:session hello)))
              (is (= (:session long) (:session hello) (:session ask))))
            (testing "the turn of each stage is counted from the trajectory"
              (is (= 1 (get-in hello [:metrics :turns]))))
            (testing "the report and each stage's screen, kept out of the project's git"
              (is (= (:stages r) (:stages (edn/read-string (slurp (io/file dir ".sim" "report.edn"))))))
              (is (str/includes? (slurp (io/file dir ".sim" "report.md")) "| ask | fail |"))
              (is (str/includes? (slurp (io/file dir ".sim" "hello.screen.txt")) "you> hello"))
              (is (not (str/includes? (:out (fx/sh {:dir dir :out :string :err :string} "git" "status" "--short")) ".sim")))))
          (testing "a project that is not empty is refused: a simulation starts from nothing"
            (is (thrown-with-msg? Exception #"is not empty" (sim/prepare-project! "test/fixtures/sim"))))
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

;; --- findings and comparing runs (byj-gly.315.3) ----------------------------------------

(defn- hits [ctx] (set (map :rule (sim/findings (sim/rules-for {}) ctx))))

(defn- call [n args result] {:name n :args args :result result})

(deftest each-rule-finds-what-it-names
  (let [dir (.getCanonicalPath (doto (io/file "target" "sim-rules" (str (System/nanoTime))) .mkdirs))
        quiet {:stage {:verdict :pass :checks [] :turns [] :prompts [] :metrics {:cost-usd 0.1}}
               :records [] :answer "Done." :dir dir :t0 (System/currentTimeMillis) :plan {}}
        with-calls (fn [cs & [rec]] (assoc quiet :records [(merge {:agent "react-agent" :iterations [{:tools cs}]} rec)]))]
    (testing "a quiet stage has no findings"
      (is (empty? (hits quiet))))
    (testing "a failed check under an answer that claims success"
      (is (contains? (hits (assoc quiet :stage (assoc (:stage quiet) :verdict :fail :checks [{:name "health" :pass? false}])
                                  :answer "Done: the server is built and the tests pass."))
                     :claimed-but-failed))
      (is (not (contains? (hits (assoc quiet :stage (assoc (:stage quiet) :verdict :fail) :answer "I could not get the tests to pass?"))
                          :claimed-but-failed))
          "an answer that says it did not finish is not a claim"))
    (testing "a stuck turn, a turn out of iterations"
      (is (contains? (hits (assoc-in quiet [:stage :turns] [{:ended :not-submitted :line "x"}])) :stuck-turn))
      (is (contains? (hits (with-calls [] {:terminated-by :max-iterations-exhausted})) :iteration-limit)))
    (testing "calls: repeated, refused, failed"
      (let [c (call "bash" {:command "npm test"} "{:status \"completed\", :exit-code 0}")]
        (is (contains? (hits (with-calls [c c c])) :repeated-call))
        (is (not (contains? (hits (with-calls [c c])) :repeated-call))))
      (let [r (call "bash" {:command "bd prime | head"} "{:error \"Tool call refused (command): bash.\"}")]
        (is (contains? (hits (with-calls [r r])) :refused-calls)))
      (let [ok (call "agent$claude" {:question "x"} "{:answer \"Done. The gate refused nothing.\"}")]
        (is (not (contains? (hits (with-calls [ok ok])) :refused-calls)) "the word in a good answer is no refusal"))
      (let [f (call "bash" {:command "false"} "{:status \"failed\", :exit-code 1}")]
        (is (contains? (hits (with-calls [f f f])) :failed-calls))
        (is (not (contains? (hits (with-calls [f f])) :failed-calls)))))
    (testing "a prompt, a path outside the project, a sweeping git add"
      (is (contains? (hits (assoc-in quiet [:stage :prompts] [{:asking-for "pkill x" :answer "n"}])) :permission-prompt))
      (is (contains? (hits (with-calls [(call "write-file" {:path "/etc/hosts"} "{}")])) :path-outside))
      (is (not (contains? (hits (with-calls [(call "write-file" {:path (str dir "/a.txt")} "{}")
                                             (call "read-file" {:path "/tmp/x"} "{}")]))
                          :path-outside))
          "the project and the temp dirs are fine")
      (is (contains? (hits (with-calls [(call "bash" {:command "git add -A && git commit -m x"} "{}")])) :sweeping-add))
      (is (not (contains? (hits (with-calls [(call "bash" {:command "git add server.js test/a.js"} "{}")])) :sweeping-add))))
    (testing "an answer that ends asking the person; a stage past its cap"
      (is (contains? (hits (assoc quiet :answer "Which port should it use?")) :asks-the-person))
      (is (contains? (hits (assoc-in quiet [:stage :metrics :cost-usd] 2.5)) :stage-cost))
      (is (not (contains? (hits (assoc quiet :plan {:stage-cap-usd 3.0} :stage (assoc-in (:stage quiet) [:metrics :cost-usd] 2.5))) :stage-cost))))
    (testing "a commit in the stage that carries an attribution"
      (fx/sh {:dir dir :out :string :err :string} "git" "init" "-q")
      (fx/sh {:dir dir :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid"
             "commit" "-q" "--allow-empty" "-m" "fix" "-m" "Co-Authored-By: Claude <noreply@anthropic.com>")
      (is (contains? (hits (assoc quiet :t0 (- (System/currentTimeMillis) 60000))) :attribution)))
    (testing "a server left listening in the project is found — and left for the session's end to stop"
      (let [p (fx/start {:dir dir :out :string :err :string} "python3" "-m" "http.server" "0" "--bind" "127.0.0.1")]
        ;; until it listens, rather than a fixed second and a half (byj-gly.444)
        (loop [n 0] (when (and (< n 50) (empty? (sim/listening-in dir))) (Thread/sleep 100) (recur (inc n))))
        (let [fs (sim/findings (sim/rules-for {}) quiet)
              f (first (filter #(= :server-left-running (:rule %)) fs))]
          (is (some? f))
          (is (not (str/includes? (str (:detail f)) "stopped"))))
        (is (seq (sim/listening-in dir)) "still listening: whether it outlives the session is outlived-session's to say")
        (fx/sh {:out :string :err :string} "pkill" "-f" "http.server 0 --bind 127.0.0.1")))
    (testing "a plan's rule replaces the default of its id"
      (is (= 5 (:at-least (first (filter #(= :repeated-call (:id %)) (sim/rules-for {:rules [{:id :repeated-call :detector :repeated-call :at-least 5}]})))))))))

(deftest what-claude-code-spent-is-counted
  (let [dir (.getCanonicalPath (doto (io/file "target" "sim-cost" (str (System/nanoTime))) .mkdirs))
        recs (doto (io/file dir ".brainyard" "claude-agents") .mkdirs)]
    (spit (io/file recs "b.edn") (pr-str {:session-id "b" :started-at 500 :cost-usd 9.0}))
    (spit (io/file recs "c.edn") (pr-str {:session-id "c" :started-at 600 :cost-usd 0.20}))
    (let [before (sim/delegation-costs dir)]
      (spit (io/file recs "a.edn") (pr-str {:session-id "a" :started-at 2000 :cost-usd 0.45}))
      (spit (io/file recs "c.edn") (pr-str {:session-id "c" :started-at 600 :cost-usd 0.85}))
      (is (< (Math/abs (- 1.10 (sim/delegated-cost before (sim/delegation-costs dir)))) 1e-9)
          "a delegation started in the stage, and what one started before spent in it (byj-gly.319); not one idle"))))

(deftest a-plan-sets-the-projects-config
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "sim-cfg-" (System/nanoTime))) .mkdirs))]
    (sim/write-project-config! dir {:claude-model "sonnet"})
    (is (= "sonnet" (:claude-model (:config (brainyard.config/load-config {:working-dir dir :project-dir dir}))))
        "as the project's config, which a session reads")))

(deftest a-stage-played-again-keeps-its-failed-attempt
  ;; byj-gly.319: --continue played a failed stage again, and the failed
  ;; attempt's cost left the report
  (let [earlier {:id :ui :verdict :fail :seconds 300
                 :metrics {:cost-usd 0.79 :brainyard-cost-usd 0.14 :claude-code-cost-usd 0.65}
                 :checks [{:name "ui" :pass? false}]}
        again {:id :ui :verdict :pass :seconds 280
               :metrics {:cost-usd 0.20 :brainyard-cost-usd 0.20 :claude-code-cost-usd 0.0}}
        r (sim/with-attempt again earlier)]
    (is (= :pass (:verdict r)) "the verdict is the last attempt's")
    (is (= [:fail] (map :verdict (:attempts r))))
    (is (= 580 (:seconds r)))
    (is (< (Math/abs (- 0.99 (get-in r [:metrics :cost-usd]))) 1e-9))
    (is (< (Math/abs (- 0.65 (get-in r [:metrics :claude-code-cost-usd]))) 1e-9))
    (testing "a third attempt keeps both before it"
      (is (= 2 (count (:attempts (sim/with-attempt again (assoc r :verdict :fail)))))))))

(deftest a-run-is-compared-with-the-last
  (let [stage (fn [id verdict calls failed cost & [defects]]
                {:id id :verdict verdict :seconds 10
                 :metrics {:calls calls :failed-calls failed :cost-usd cost}
                 :findings (mapv #(hash-map :rule % :severity :defect) defects)})
        prev {:dir "/runs/a" :stages [(stage :init :pass 10 1 0.30) (stage :server :pass 3 0 0.20)]}
        cur {:stages [(stage :init :pass 16 1 0.31) (stage :server :fail 5 2 0.40 [:claimed-but-failed]) (stage :term :pass 1 0 0.1)]}
        c (sim/compare-runs prev cur)
        by (into {} (map (juxt :id identity)) (:stages c))]
    (is (= "/runs/a" (:against c)))
    (is (empty? (:regressions (by :init))) "a little more calls, the same failures, the same cost")
    (is (= ["pass → fail" "failed calls 0 → 2" "cost $0.20 → $0.40" "new defects: claimed-but-failed"]
           (:regressions (by :server))))
    (is (:new? (by :term)))))

(deftest the-last-run-is-one-of-the-same-plan
  (let [root (io/file (System/getProperty "java.io.tmpdir") (str "sim-prev-" (System/nanoTime)))
        run! (fn [dir plan-id t]
               (let [f (io/file root dir ".sim" "report.edn")]
                 (io/make-parents f)
                 (spit f (pr-str {:dir dir :plan {:id plan-id}}))
                 (.setLastModified f t)))]
    (run! "p2-1000" "p2" 1000)
    (run! "p2-claude-2000" "p2-claude" 2000)
    (run! "p2-3000" "p2" 3000)
    (is (= "p2-1000" (:dir (sim/previous-report (str root) "p2" (str (io/file root "p2-3000")))))
        "not p2-claude's, though its directory starts with p2-")))

(deftest a-parked-task-keeps-the-stage-open
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "sim-parked-" (System/nanoTime)))
        log (io/file dir ".brainyard" "logs" "events.log")
        ev #(spit log (str (pr-str {:ts 1 :event %1 :level :trace :data %2 :session "s"}) "\n") :append true)]
    (io/make-parents log)
    (is (= {:tasks #{} :open-turns 0} (sim/parked-pending dir)) "no log: nothing pending")
    (ev :session.turn/start {})
    (ev :task/created {:id "t-1" :name "tool: agent$claude"})
    (ev :task/parked {:task-id "t-1" :note "auto-parked"})
    (ev :session.turn/end {})
    (is (= {:tasks #{"t-1"} :open-turns 0} (sim/parked-pending dir))
        "the turn has ended, the task it parked has not")
    (ev :task/completed {:id "t-1" :status :completed})
    (ev :session.turn/start {})
    (is (= {:tasks #{} :open-turns 1} (sim/parked-pending dir)) "the task ended; its wake turn runs")
    (ev :session.turn/end {})
    (is (= {:tasks #{} :open-turns 0} (sim/parked-pending dir)))))

(deftest two-modes-are-compared-stage-by-stage
  (let [stage (fn [id verdict checks cost & [cc]]
                {:id id :verdict verdict :seconds 10
                 :checks (mapv #(hash-map :name (str %) :pass? %) checks)
                 :metrics (cond-> {:calls 4 :failed-calls 1 :cost-usd (+ cost (or cc 0))}
                            cc (assoc :brainyard-cost-usd cost :claude-code-cost-usd cc))
                 :findings [{:rule :stage-cost :severity :warn}]})
        a {:dir "/runs/a" :plan {:id "pa"}
           :stages [(stage :init :pass [true true] 0.30) (stage :chat :fail [true false] 0.50)]}
        b {:dir "/runs/b" :plan {:id "pb"}
           :stages [(stage :init :pass [true true] 0.30) (stage :chat :pass [true true] 0.20 1.10)]}
        c (sim/compare-modes a b)
        md (sim/compare-modes-md c)]
    (is (= [:init :chat] (map :id (:stages c))))
    (is (= [1 2] (get-in c [:stages 1 :a :checks])))
    (is (= 1.1 (get-in c [:stages 1 :b :claude-code-cost])))
    (is (= {:stages 2 :passed 1 :checks [3 4] :calls 8 :failed 2 :findings [0 2 0] :seconds 20
            :cost 0.8 :brainyard-cost 0.8 :claude-code-cost 0.0}
           (get-in c [:totals :a])))
    (is (< (Math/abs (- 1.6 (get-in c [:totals :b :cost]))) 1e-9) "Claude Code's cost counted in B's")
    (is (str/includes? md "$1.30 ($0.20 + CC $1.10)"))
    (is (not (str/includes? md "## Scores")) "a run is not scored here (RFC-070)")))

(deftest the-second-run-of-a-plan-is-compared-with-the-first
  (when (tmux?)
    (let [label (str "byj-sim-cmp-" (random-uuid))
          base (io/file "target" "sim-cmp" (str (System/nanoTime)))
          plan (sim/load-plan "test/fixtures/sim/plan.edn")]
      (binding [sim/*tmux* ["tmux" "-L" label] sim/*poll-ms* poll-ms]
        (try
          (let [first-run (sim/run-plan! plan {:dir (str base "/mock-sim-a") :stages ["hello"]})
                _ (Thread/sleep 1100)
                second-run (sim/run-plan! plan {:dir (str base "/mock-sim-b") :stages ["hello"]})]
            (is (nil? (:comparison first-run)) "nothing to compare the first with")
            (is (= (:dir first-run) (get-in second-run [:comparison :against])))
            (is (= [:hello] (map :id (get-in second-run [:comparison :stages]))))
            (is (str/includes? (slurp (io/file (:dir second-run) ".sim" "report.md")) "## Compared with the last run")))
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(deftest a-stopped-run-is-continued
  ;; byj-gly.315.17: a run stopped at a failed stage goes on where it stopped
  (when (tmux?)
    (let [label (str "byj-sim-cont-" (random-uuid))
          dir (.getCanonicalPath (io/file "target" "sim-cont" (str (System/nanoTime))))
          plan (sim/load-plan "test/fixtures/sim/plan.edn")]
      (binding [sim/*tmux* ["tmux" "-L" label] sim/*poll-ms* poll-ms]
        (try
          (let [first-run (sim/run-plan! plan {:dir dir})]
            (is (= [:pass :pass :fail] (map :verdict (:stages first-run))))
            (is (= :stage-failed (:stopped first-run)))
            ;; what the failed stage's check wanted, put right since
            (spit (io/file dir "nope.txt") "now there")
            (let [r (sim/run-plan! plan {:continue dir})
                  [long hello ask] (:stages r)]
              (testing "the stages that passed are kept as they were"
                (is (= (select-keys (first (:stages first-run)) [:id :verdict :seconds])
                       (select-keys long [:id :verdict :seconds]))))
              (testing "the failed stage's checks pass now: re-checked, not played again"
                (is (= [:ask :pass true] [(:id ask) (:verdict ask) (:rechecked ask)]))
                (is (= (select-keys (last (:stages first-run)) [:turns :seconds :metrics])
                       (select-keys ask [:turns :seconds :metrics]))
                    "with what the failed attempt did and cost: that was the work"))
              (testing "and the report says it was continued, in the same project"
                (is (= dir (:dir r)))
                (is (some? (:continued r)))
                (is (nil? (:stopped r))))))
          (testing "a directory with no run is refused"
            (is (thrown-with-msg? Exception #"has no run to continue" (sim/run-plan! plan {:continue "test/fixtures"}))))
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(deftest what-a-stage-starts-does-not-outlive-its-session
  ;; byj-gly.315.16, end to end: the agent leaving a server running is
  ;; recorded; the session's end stopping it is checked once it has quit
  (when (tmux?)
    (let [label (str "byj-sim-serve-" (random-uuid))
          dir (.getCanonicalPath (io/file "target" "sim-serve" (str (System/nanoTime))))]
      (binding [sim/*tmux* ["tmux" "-L" label] sim/*poll-ms* poll-ms]
        (try
          (let [r (sim/run-plan! (sim/load-plan "test/fixtures/sim/plan-serve.edn") {:dir dir})
                rules (set (map :rule (:findings (first (:stages r)))))]
            (is (contains? rules :server-left-running) "left running when the turn ended")
            (is (not (contains? rules :outlived-session)) "and stopped when the session ended")
            (is (empty? (sim/listening-in dir))))
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

;; --- a loop of the person's choosing (byj-ng7.18) --------------------------------------------

(deftest a-loop-byj-would-not-run-is-refused-before-the-project-is-made
  (let [dir (.getCanonicalPath (io/file "target" "sim-loop-refused" (str (System/nanoTime))))
        bad (io/file "target" "sim-loop-refused-not-a-tree.edn")]
    (io/make-parents bad)
    (spit bad "{:not \"a tree\"}")
    (is (thrown-with-msg? Exception #"not a loop byj would run" (sim/run-plan! (sim/load-plan "test/fixtures/sim/plan-loop.edn") {:dir dir :loop (.getPath bad)})))
    (is (thrown-with-msg? Exception #"no loop file" (sim/loop-candidate "target/nowhere.edn" dir)))
    (is (not (.exists (io/file dir))) "nothing made")
    (is (= "react" (:name (sim/loop-candidate "test/fixtures/sim/loops/react.edn" dir))) "named as byj loops eval names it")
    (is (thrown-with-msg? Exception #"keeps the loop it began with"
                          (sim/run-plan! (sim/load-plan "test/fixtures/sim/plan-loop.edn") {:continue dir :loop "test/fixtures/sim/loops/react.edn"})))))

(deftest two-runs-on-different-loops-say-so-and-a-regression-fails-the-comparison
  (let [stage (fn [verdict failed] {:id :hello :verdict verdict :seconds 5 :metrics {:calls 2 :failed-calls failed :cost-usd 0.1}})
        a {:dir "/runs/a" :plan {:id "p"} :loop {:name "react" :from :bundled :hash "aaaaaaaaaaaa"} :stages [(stage :pass 0)]}
        b {:dir "/runs/b" :plan {:id "p"} :loop {:name "react" :from :project :hash "bbbbbbbbbbbb" :file "/c/react.edn"} :stages [(stage :pass 2)]}
        c (sim/compare-runs a b)]
    (is (= [(:loop a) (:loop b)] (:loops c) (:loops (sim/compare-modes a b))))
    (is (nil? (:loops (sim/compare-runs a (assoc a :dir "/runs/c")))) "the same loop says nothing")
    (is (nil? (:loops (sim/compare-runs (dissoc a :loop) b))) "a run from before runs recorded their loop says nothing")
    (is (= ["failed calls 0 → 2"] (:regressions (first (:stages c)))))
    (let [md (sim/comparison-md c "B against A")]
      (is (str/includes? md "Loops: was react, bundled aaaaaaaaaaaa; now react, project bbbbbbbbbbbb (/c/react.edn)"))
      (is (str/includes? md "failed calls 0 → 2")))
    (is (str/includes? (sim/compare-modes-md (sim/compare-modes a b)) "- B's loop: react, project bbbbbbbbbbbb"))))

(deftest a-run-on-a-chosen-loop-runs-it-and-says-so
  (when (tmux?)
    (let [label (str "byj-sim-loop-" (random-uuid))
          base (io/file "target" "sim-loop" (str (System/nanoTime)))
          plan (sim/load-plan "test/fixtures/sim/plan-loop.edn")]
      (binding [sim/*tmux* ["tmux" "-L" label] sim/*poll-ms* poll-ms]
        (try
          (let [bundled (sim/run-plan! plan {:dir (str base "/mock-sim-loop-a")})
                _ (Thread/sleep 1100)
                chosen (sim/run-plan! plan {:dir (str base "/mock-sim-loop-b") :loop "test/fixtures/sim/loops/react.edn"})
                dir (:dir chosen)]
            (testing "on the bundled loop the mock writes nothing; on the chosen one the stage passes"
              (is (= :fail (:verdict (first (:stages bundled)))))
              (is (= :pass (:verdict (first (:stages chosen))))))
            (testing "each report says which loop ran"
              (is (= {:name "react" :from :bundled} (select-keys (:loop bundled) [:name :from])))
              (is (= {:name "react" :from :project} (select-keys (:loop chosen) [:name :from])))
              (is (str/ends-with? (get-in chosen [:loop :file]) "test/fixtures/sim/loops/react.edn"))
              (is (str/includes? (slurp (io/file dir ".sim" "report.md")) "- Loop: react, project")))
            (testing "made the project's loop as a person's accept makes one: its history says so"
              (is (= [:accept] (map :action (edn/read-string (str "[" (slurp (io/file dir ".brainyard" "loops" "history.edn")) "]"))))))
            (testing "and the comparison with the run before names both loops"
              (is (= (:dir bundled) (get-in chosen [:comparison :against])))
              (is (= [:bundled :project] (map :from (get-in chosen [:comparison :loops]))))))
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(deftest a-plans-attachments-are-checked-installed-and-their-notices-counted
  ;; RFC-072: dev/sim/extensions/plan.edn fills the ReAct loop's two slots
  (let [plan (sim/load-plan "dev/sim/extensions/plan.edn")
        dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "sim-attach-" (random-uuid))) .mkdirs))]
    (is (= [[:react/ext-after-tools "lint"] [:react/ext-answer-check "tests"]]
           (map (juxt :slot :name) (sim/plan-attachments plan dir))))
    (is (= [:react/ext-after-tools :react/ext-answer-check]
           (map :slot (sim/install-attachments! dir (sim/plan-attachments plan dir)))))
    (is (= {:react/ext-after-tools ["lint"] :react/ext-answer-check ["tests"]}
           ((requiring-resolve 'brainyard.project-loops/attached) dir)))
    (is (thrown-with-msg? Exception #"cannot be attached to :react/nowhere"
                          (sim/plan-attachments (assoc plan :attachments {:react/nowhere ["lint"]}) dir))))
  (is (= {"LINT" 2 "ANSWER REFUSED" 1}
         (sim/notice-counts [{:iterations [{:notices "LINT: x"} {:notices "ANSWER REFUSED: y\n\nLINT: z"} {}]}]
                            ["LINT" "ANSWER REFUSED"]))))

(deftest the-pressure-plans-differ-only-in-what-is-attached
  ;; dev/sim/extensions/pressure*.edn against their baseline: the same stages,
  ;; so a comparison of two runs measures the attachments and nothing else
  (let [base (sim/load-plan "dev/sim/extensions/pressure-baseline.edn")
        own-checks #{"lint-ran" "tests-ran" "work-ran"}
        stages (fn [p] (mapv (fn [s] (update s :checks #(vec (remove (comp own-checks :name) %)))) (:stages p)))
        dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "sim-pressure-" (random-uuid))) .mkdirs))]
    (is (nil? (:attachments base)))
    (doseq [[file attached] [["pressure.edn" {:react/ext-after-tools ["lint"] :react/ext-answer-check ["tests"]}]
                             ["pressure-work.edn" {:react/ext-answer-check ["work"]}]]
            :let [ext (sim/load-plan (str "dev/sim/extensions/" file))]]
      (is (= (stages ext) (stages base)) file)
      (is (= (dissoc ext :id :attachments :stages :plan-file) (dissoc base :id :stages :plan-file)) file)
      (is (= attached (:attachments ext)) file)
      (is (= (count (mapcat val attached)) (count (sim/plan-attachments ext dir))) "each can be attached"))))

(deftest the-work-check-reads-what-the-turn-changed-and-says-what-is-off
  ;; dev/sim/extensions/ext/work.edn's command, made by its own seed and join
  ;; from a :turn-start, and run in a project as its attachment would: what
  ;; git shows decides it (bd is not asked here)
  (let [[_ _ seed join] (edn/read-string (slurp "dev/sim/extensions/ext/work.edn"))
        command (fn [start]
                  (let [m (atom {:turn-start start})]
                    (bt/tick {:st-memory m :registry agent/registry} [:sequence {} seed join])
                    (:work/command @m)))
        dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "sim-work-" (random-uuid))) .mkdirs)
        git (fn [& args] (:out (apply fx/sh {:dir (str dir) :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@t" args)))
        run #(fx/sh {:dir (str dir) :out :string :err :string} "bash" "-c" (command %))
        snapshot (fn []
                   ;; the work tree as a commit, as changes/begin-turn! makes it
                   (let [idx (str (io/file dir ".git" "sim-index"))
                         sh #(:out (fx/sh {:dir (str dir) :out :string :err :string :env (assoc (into {} (System/getenv)) "GIT_INDEX_FILE" idx)} "bash" "-c" %))]
                     (str/trim (sh "git add -A && git -c user.name=t -c user.email=t@t commit-tree -m start $(git write-tree)"))))]
    (when (zero? (:exit (fx/sh {:out :string :err :string} "bash" "-c" "command -v node")))
      (is (str/starts-with? (command "abc123") "export START=abc123;node -e"))
      (is (str/starts-with? (command nil) "export START=;node -e") "no snapshot: no sha")
      (is (zero? (:exit (run nil))) "no git: nothing to ask")
      (git "init" "-q")
      (spit (io/file dir "server.js") "1")
      (let [r (run nil)]
        (is (= 1 (:exit r)))
        (is (str/includes? (:out r) "WORK (git and bd"))
        (is (str/includes? (:out r) "?? server.js")))
      (git "add" "-A")
      (git "commit" "-qm" "x")
      (is (zero? (:exit (run nil))) "committed: nothing off")
      (testing "with the turn's start, what the turn found is not its to answer for"
        (spit (io/file dir "found.txt") "left by /init")
        (let [start (snapshot)]
          (is (re-matches #"[0-9a-f]{40}" start))
          (is (= 1 (:exit (run nil))) "without it, found.txt is held against the turn")
          (is (zero? (:exit (run start))))
          (spit (io/file dir "made.js") "2")
          (let [r (run start)]
            (is (= 1 (:exit r)))
            (is (str/includes? (:out r) "changes this turn made that no commit holds"))
            (is (str/includes? (:out r) "?? made.js"))
            (is (not (str/includes? (:out r) "found.txt")))
            (is (not (str/includes? (:out r) "installed dependencies"))))
          (testing "installed dependencies are named as what .gitignore is for"
            (spit (doto (io/file dir "node_modules" "ws" "index.js") io/make-parents) "x")
            (is (str/includes? (:out (run start)) "node_modules holds installed dependencies")))
          (testing "an open bead a commit of the turn names is to be closed"
            ;; bd stood in for: rules-1 open, rules-1.1 open and not named
            (let [bin (doto (io/file dir ".bin") .mkdirs)
                  bd (io/file bin "bd")
                  run-bd #(fx/sh {:dir (str dir) :out :string :err :string
                                  :env (assoc (into {} (System/getenv)) "PATH" (str bin ":" (System/getenv "PATH")))}
                                 "bash" "-c" (command %))]
              (spit bd (str "#!/bin/sh\ncase \"$*\" in *open*) echo '[{\"id\":\"rules-1\",\"title\":\"server\"},{\"id\":\"rules-1.1\",\"title\":\"sub\"}]';; *) echo '[]';; esac\n"))
              (.setExecutable bd true)
              (spit (io/file dir ".gitignore") "node_modules/\n.bin/\n")
              (git "add" "-A")
              (git "commit" "-qm" "The server shell (rules-1).")
              (let [r (run-bd start)]
                (is (= 1 (:exit r)))
                (is (str/includes? (:out r) "rules-1 (server) is named by a commit this turn made and is still open"))
                (is (not (str/includes? (:out r) "rules-1.1")))
                (is (not (str/includes? (:out r) "no commit holds"))))
              (is (zero? (:exit (run start))) "no bead open: nothing off"))))))))

(deftest the-question-plans-carry-a-tool-and-a-predictor-their-attachments-call
  ;; dev/sim/extensions/question*.edn (byj-10y): the same stages, the
  ;; project's own tool and predictor put in place before the first stage,
  ;; and the tool finding the lines that name the question's words
  (let [ext (sim/load-plan "dev/sim/extensions/question.edn")
        base (sim/load-plan "dev/sim/extensions/question-baseline.edn")
        dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "sim-question-" (random-uuid))) .mkdirs))]
    (is (= (:stages ext) (:stages base)))
    (is (= (dissoc ext :id :attachments :plan-file) (dissoc base :id :plan-file)))
    (is (= {:react/ext-question-check ["find" "refine"]} (:attachments ext)))
    (is (= 2 (count (sim/plan-attachments ext dir))))
    (sim/write-project-files! dir ext)
    (is (thrown-with-msg? Exception #"no project file" (sim/write-project-files! dir (assoc ext :project-files {"x" "nowhere.edn"}))))
    (io/make-parents (io/file dir "src" "server.js"))
    (spit (io/file dir "src" "server.js") "// GET /api/health answers ok\nconst x = 1;\n")
    (let [skipped (atom [])
          ctx (agent/make-ctx {:lm {:provider :mock :format :mock :responses (constantly "{}")}
                               :config {:working-dir dir :project-dir dir :user-tools? true}
                               :on-event (fn [e d] (when (= :tools/user-tool-skipped e) (swap! skipped conj d)))})
          tool (get @(:tools ctx) "user$tool$find-related")]
      (is (some? tool) (str "the user tool compiles: " (pr-str @skipped)))
      (let [r ((requiring-resolve 'brainyard.tools/call-tool) tool {:question "health needs to say more"} ctx)]
        (is (= ["health"] (:words r)))
        (is (some #(str/includes? % "src/server.js:1:// GET /api/health") (:hits r)) (pr-str r))))
    (is (some? ((requiring-resolve 'brainyard.predictor/load-predictor) "user/refine-question"
                [(str (io/file dir ".brainyard" "predictors"))])))))

(deftest the-rules-plans-differ-only-in-what-is-attached-and-start-committed
  ;; dev/sim/extensions/rules*.edn (byj-93l): the same stages and rule
  ;; checks, the project's decision records and CHANGELOG.md committed before
  ;; the first stage, so what is uncommitted later is the turns'
  (let [ext (sim/load-plan "dev/sim/extensions/rules.edn")
        base (sim/load-plan "dev/sim/extensions/rules-baseline.edn")
        dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "sim-rules-" (random-uuid))) .mkdirs))]
    (is (= (:stages ext) (:stages base)))
    (is (= (dissoc ext :id :attachments :plan-file) (dissoc base :id :plan-file)))
    (is (= 4 (count (sim/plan-attachments ext dir))))
    (is (every? (fn [s] (= #{"rule-committed" "rule-beads-closed" "rule-changelog" "rule-errors-json" "rule-no-framework"}
                           (set (filter #(str/starts-with? % "rule-") (map :name (:checks s))))))
                (rest (:stages ext))))
    (fx/sh {:dir dir} "git" "init" "-q")
    (sim/write-project-files! dir ext)
    (is (= "" (str/trim (:out (fx/sh {:dir dir :out :string :err :string} "git" "status" "--porcelain")))))
    (is (str/includes? (:out (fx/sh {:dir dir :out :string :err :string} "git" "ls-files"))
                       "docs/decisions/0002-errors-are-json.md"))))

(deftest a-call-the-loops-tree-made-is-counted-apart-from-the-models
  ;; byj-8gc: an attachment's lint after every write was 3x a repeated-call
  ;; warn and a third of a stage's calls
  (let [lint {:name "bash" :args {:command "lint"} :result "" :node :lint/run :slot :react/ext-after-tools}
        records [{:iterations [{:tools [{:name "bash" :args {:command "npm test"} :result "ok"}]}
                               {:tools [lint]} {:tools [lint]} {:tools [(assoc lint :result "{:exit-code 1}")]}]}]
        m (sim/stage-metrics records)]
    (is (= [1 3 {":react/ext-after-tools" 3} 0] ((juxt :calls :tree-calls :tree-calls-by :failed-calls) m)))
    (is (not-any? #(= :repeated-call (:rule %))
                  (sim/findings (sim/rules-for {}) {:stage {:prompts []} :records records :answer "a" :dir "." :t0 0 :plan {}})))))
