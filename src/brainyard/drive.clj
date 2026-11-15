;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.drive
  "/drive (RFC-073 \"The drive loop\", byj-71w.6): the `drive$*` commands
  agent$drive's loop (`brainyard/loops/drive.edn`) calls to work through a
  project's goal one bead at a time, as far as its profile allows.

  Mechanism only. The order — pick, work, check, judge, settle, again — is
  the tree's; the bead's work is a ReAct turn the tree includes; whether a
  turn finished its bead is the predictor `drive/judge`'s; every word a model
  reads is `resources/brainyard/drive/words.edn`'s. What is here reads beads
  and the work tree, asks the person where the profile says to, closes a
  bead, and keeps the run's tally.

  brainyard has no counterpart: its exec-agent drove a todo list, and was
  not ported (RFC-023)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.init :as init]
            [brainyard.json :as json]
            [brainyard.profile :as profile]))

(def words-resource "brainyard/drive/words.edn")

(defn words [] (edn/read-string (slurp (io/resource words-resource))))

(defn- fill [s vars]
  (reduce-kv (fn [s k v] (str/replace s (str "{" (name k) "}") (str v))) (str s) vars))

(defn- dir-of [config args]
  (or (not-empty (str (:dir args))) (:project-dir config) (:working-dir config) "."))

(def bd-timeout-ms 60000)

(defn- run [dir & argv]
  (let [r (fx/run!! (apply fx/process-task {:dir (str dir) :out :string :err :string} argv) bd-timeout-ms)
        ok (:ok r)]
    {:ok? (boolean (and ok (zero? (:exit ok))))
     :out (str (:out ok))
     :said (str/trim (str (:out ok) " " (:err ok) (when (:timeout r) " no answer in time")))}))

(defn- json-out [{:keys [ok? out]}]
  (when ok? (try (json/read-str (str/trim out)) (catch Throwable _ nil))))

(defn- ask-person
  "Ask the person through the turn's permission channel (RFC-012), as
  init$install does: nil when they allow it, else why not."
  [ctx request]
  (if-let [pf (:permission-fn ctx)]
    (let [r (try (pf request) (catch Throwable e {:denied true :reason (ex-message e)}))]
      (when-not (:allowed r) (or (not-empty (str (:reason r))) "declined")))
    "there is no person to ask in this run"))

(defn- num-of [x] (cond (number? x) x (string? x) (parse-long (str/trim x)) :else nil))

(defn- stop [why] {:stop true :stopped why :bead "" :brief ""})

(defn- bd-path [config args]
  (init/on-path "bd" (or (:path args) (:bd-path config) (System/getenv "PATH"))))

(defn next-bead
  "The next step (`drive$next`): stop with why when the project has no
  profile, beads is not there, the profile's limits are reached — failures
  in a row, spend, beads filed since the run began — or nothing is ready
  under the goal's epic; else the bead to work on, claimed, with its brief.
  A bead the last step left unfinished (`:retry`) is worked again, with
  what was missing. Where the profile has the person choose the work
  (`:issue/work :ask`), the person is asked first. `{:stop :stopped :bead
  :title :brief :head :since}`; `:since` is when the run began, kept by the
  tree."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        p (profile/read-profile dir)
        bd (bd-path config args)
        limits (:stop p)
        failures (or (num-of (:failures args)) 0)
        spent (or (some-> (:usage args) :cost) 0)
        since (or (not-empty (str (:since args)))
                  (str (java.time.Instant/ofEpochMilli (System/currentTimeMillis))))
        epic (:goal-epic p)
        retry (not-empty (str (:retry args)))
        head (str/trim (:out (run dir "git" "rev-parse" "HEAD")))]
    (cond
      (nil? p) (stop (get-in w [:stops :no-profile]))
      (or (nil? bd) (not (.isDirectory (io/file dir ".beads")))) (stop (get-in w [:stops :no-bd]))
      (and (:failures limits) (>= failures (:failures limits)))
      (stop (fill (get-in w [:stops :failures]) {:n failures}))
      (and (:usd limits) (>= spent (:usd limits)))
      (stop (fill (get-in w [:stops :usd]) {:n (:usd limits)}))
      (and (:issues-per-run limits)
           (>= (count (json-out (run dir bd "list" "--all" "--json" "--created-after" since)))
               (:issues-per-run limits)))
      (stop (fill (get-in w [:stops :issues]) {:n (:issues-per-run limits)}))
      retry
      (let [b (first (json-out (run dir bd "show" retry "--json")))]
        {:stop false :stopped "" :bead retry :title (str (:title b)) :head head :since since
         :brief (fill (:retry w) {:id retry :title (:title b) :why (:retry-note args)})})
      :else
      (let [ready (json-out (apply run dir bd "ready" "--json" "--limit" "20" (when epic ["--parent" epic])))
            b (first (remove #(= "epic" (:issue_type %)) ready))]
        (cond
          (nil? b) (stop (fill (get-in w [:stops :nothing-ready]) {:under (if epic (str " under " epic) "")}))
          (= :never (profile/level p :issue/work)) (stop (get-in w [:stops :work-never]))
          :else
          (let [no (when (= :ask (profile/level p :issue/work))
                     (ask-person ctx {:type :tool-use :action :call :tool "drive$next" :pattern "drive$next"
                                      :display (fill (:ask-work w) {:id (:id b) :title (:title b)})
                                      :args {:bead (:id b)}}))]
            (if no
              (stop (fill (get-in w [:stops :work-declined]) {:id (:id b)}))
              (do (run dir bd "update" (:id b) "--claim")
                  {:stop false :stopped "" :bead (:id b) :title (str (:title b)) :head head :since since
                   :brief (fill (:brief w) {:id (:id b) :title (:title b)
                                            :description (str/trim (str (:description b)))
                                            :goal (if (:goal p) (str " (" (str/trim (:goal p)) ")") "")})}))))))))

(defn check-bead
  "What a bead's turn left (`drive$check`): the work tree's uncommitted
  files (beads', brainyard's and Claude Code's state aside), the commits
  since the step began and whether one names the bead, the bead's status,
  and what the profile expects of a finished bead. Reads only. `{:text
  :clean? :committed? :status}`."
  [config args]
  (let [dir (dir-of config args)
        w (words)
        p (profile/read-profile dir)
        bead (str (:bead args))
        head (not-empty (str (:head args)))
        dirty (->> (str/split-lines (:out (run dir "git" "status" "--porcelain" "--" "." ":!.beads" ":!.brainyard" ":!.claude")))
                   (remove str/blank?) vec)
        log (when head (str/trim (:out (run dir "git" "log" (str head "..HEAD") "--format=%h %s%n%b"))))
        committed? (boolean (and (not (str/blank? log)) (str/includes? log bead)))
        bd (bd-path config args)
        status (some-> (first (json-out (run dir bd "show" bead "--json"))) :status)
        expect (get-in w [:expect :git/commit (or (profile/level p :git/commit) :ask)])]
    {:clean? (empty? dirty)
     :committed? committed?
     :status (str status)
     :text (str "Bead " bead ": " (or status "status unknown") "\n"
                "Uncommitted: " (if (seq dirty) (str/join ", " (map #(subs % (min 3 (count %))) dirty)) "nothing") "\n"
                "Commits since the step began: " (if (str/blank? log) "none" (str/replace log #"\n+" "; "))
                (when-not committed? (str "\nNo commit since the step began names " bead "."))
                "\nExpected: " expect)}))

(defn settle
  "Act on drive/judge's word on a bead (`drive$settle`): `done` closes the
  bead as the profile's `:issue/close` says — on its own at `:act`, after the
  person's yes at `:ask`, never at `:never` — and clears the failures;
  `retry` counts a failure and has the next step work the bead again;
  `ask` and `stop` end the run with the question or why. Every step is a
  line of the run's log. `{:failures :retry :retry-note :stop :stopped :log}`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        p (profile/read-profile dir)
        bead (str (:bead args))
        title (str (:title args))
        status (keyword (str/lower-case (str/trim (str (:status args)))))
        summary (str/trim (str (:summary args)))
        failures (or (num-of (:failures args)) 0)
        log (str (:log args))
        line #(str log (when-not (str/blank? log) "\n") %)
        bd (bd-path config args)
        ;; the bead's turn spent this in a scope of its own: said as a
        ;; tool's spend, the run's usage counts it (agent/account-tool-cost!)
        cost (let [c (some-> (:work-usage args) :cost)] (when (and (number? c) (pos? c)) c))]
    (cond-> (case status
      :done
      (let [closed? (= "closed" (str/trim (str (:bead-status args))))
            lvl (profile/level p :issue/close)
            no (when-not closed?
                 (case lvl
                   :act nil
                   :never "the profile has no agent close beads"
                   (ask-person ctx {:type :tool-use :action :call :tool "drive$settle" :pattern "drive$settle"
                                    :display (fill (:ask-close w) {:id bead :title title :summary summary})
                                    :args {:bead bead}})))
            r (when (and (not closed?) (nil? no) bd)
                (run dir bd "close" bead "--reason" (str "/drive: " summary)))]
        {:failures 0 :retry "" :retry-note "" :stop false :stopped ""
         :log (line (str "✓ " bead " " title " — " summary
                         (cond closed? " (closed in its turn)"
                               no (str " (left open: " no ")")
                               (and r (not (:ok? r))) (str " (bd close failed: " (:said r) ")")
                               :else " (closed)")))})
      :retry
      {:failures (inc failures) :retry bead :retry-note summary :stop false :stopped ""
       :log (line (str "↻ " bead " " title " — " summary))}
      :ask
      (let [q (str/trim (str (:question args)))]
        {:failures failures :retry "" :retry-note "" :stop true
         :stopped (str bead " needs you: " (if (str/blank? q) summary q))
         :log (line (str "? " bead " " title " — " summary))})
      {:failures failures :retry "" :retry-note "" :stop true
       :stopped (str bead " cannot go on: " summary)
       :log (line (str "✗ " bead " " title " — " summary))})
      cost (assoc :usage-cost-usd cost))))

(defn report
  "The run's answer (`drive$report`): each step's line, why it stopped, and
  what is still open under the goal's epic."
  [config args]
  (let [dir (dir-of config args)
        p (profile/read-profile dir)
        bd (bd-path config args)
        epic (:goal-epic p)
        open (when (and bd epic)
               (json-out (run dir bd "list" "--parent" epic "--status" "open,in_progress,blocked" "--json")))
        log (str/trim (str (:log args)))]
    {:text (str "/drive" (when epic (str " under " epic)) ":\n"
                (if (str/blank? log) "no bead was worked on." log)
                "\n\nStopped: " (let [s (str/trim (str (:stopped args)))] (if (str/blank? s) "the step limit was reached." s))
                (when epic
                  (str "\n\nStill open under " epic ": "
                       (if (seq open) (str/join ", " (map #(str (:id %) " " (:title %)) open)) "nothing"))))}))

(defn commands
  "The `drive$*` commands (RFC-073)."
  [config]
  (let [any-arg (fn [k desc] [k {:optional true} [:any {:desc desc}]])]
    [{:name "drive$next" :kind :command
      :description "/drive's next step: the next ready bead under the goal's epic, claimed, with its brief — or why the run stops (no profile, the profile's limits, nothing ready, the person said no)."
      :input-schema [:map (any-arg :dir "the project directory") (any-arg :failures "failures in a row so far")
                     (any-arg :usage "the run's usage so far") (any-arg :since "when the run began")
                     (any-arg :retry "a bead to work again") (any-arg :retry-note "what it lacked")]
      :output-schema [:map [:stop :any] [:stopped :any] [:bead :any] [:brief :any]
                      [:title {:optional true} :any] [:head {:optional true} :any] [:since {:optional true} :any]]
      :effects #{:fs/read :process}
      :fn (fn [args ctx] (next-bead config ctx args))}
     {:name "drive$check" :kind :command
      :description "What a bead's turn left: uncommitted files, the commits since the step began, the bead's status, and what the profile expects. Reads only."
      :input-schema [:map (any-arg :dir "the project directory") (any-arg :bead "the bead") (any-arg :head "HEAD when the step began")]
      :output-schema [:map [:text :string] [:clean? :any] [:committed? :any] [:status :any]]
      :effects #{:fs/read :process}
      :fn (fn [args _] (check-bead config args))}
     {:name "drive$settle" :kind :command
      :description "Act on the judgement of a bead's turn: close it as the profile says, count a retry, or stop the run with the question for the person."
      :input-schema [:map (any-arg :dir "the project directory") (any-arg :bead "the bead") (any-arg :title "its title")
                     (any-arg :status "done, retry, ask or stop") (any-arg :question "for ask") (any-arg :summary "one line")
                     (any-arg :bead-status "the bead's status after the turn") (any-arg :failures "failures so far")
                     (any-arg :log "the run's log so far") (any-arg :work-usage "what the bead's turn spent")]
      :output-schema [:map [:failures :any] [:stop :any] [:stopped :any] [:log :any]
                      [:retry :any] [:retry-note :any] [:usage-cost-usd {:optional true} :any]]
      :effects #{:process}
      :fn (fn [args ctx] (settle config ctx args))}
     {:name "drive$report" :kind :command
      :description "/drive's answer: each step, why it stopped, and what is still open under the goal's epic. Reads only."
      :input-schema [:map (any-arg :dir "the project directory") (any-arg :log "the run's log") (any-arg :stopped "why it stopped")]
      :output-schema [:map [:text :string]]
      :effects #{:fs/read :process}
      :fn (fn [args _] (report config args))}]))
