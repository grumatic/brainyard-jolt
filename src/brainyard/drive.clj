;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.drive
  "/drive (RFC-073 \"The drive loop\", byj-71w.6): the `drive$*` commands
  agent$drive's loop (`brainyard/loops/drive.edn`) calls to work through a
  project's goal one issue at a time — a bead, or a GitHub issue where the
  profile keeps them there (byj-m6f) — as far as its profile allows.

  Mechanism only. The order — pick, work, check, judge, settle, again — is
  the tree's; the bead's work is a ReAct turn the tree includes; whether a
  turn finished its bead is the predictor `drive/judge`'s; every word a model
  reads is `resources/brainyard/drive/words.edn`'s. What is here reads the
  trackers (bd, gh) and the work tree, asks the person where the profile
  says to, closes an issue, and keeps the run's tally.

  brainyard has no counterpart: its exec-agent drove a todo list, and was
  not ported (RFC-023)."
  (:require [brainyard.tool-words :as tool-words]
            [brainyard.projects :as projects]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.profile :as profile]
            [brainyard.which :as which]))

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

(defn bead-attempts
  "How many turns one bead gets in a run before it is set aside: config's
  `:drive-bead-attempts`, at least 1 (byj-dw0)."
  [config]
  (max 1 (or (num-of (:drive-bead-attempts config)) 3)))

(def progress-max-chars
  "How much of a turn's answer a retry's brief, or a set-aside bead's
  comment, carries."
  1500)

(defn- clip [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- bd-path [config args]
  (which/on-path "bd" (or (:path args) (:bd-path config) (System/getenv "PATH"))))

(defn- gh-path [config args]
  (which/on-path "gh" (or (:path args) (:gh-path config) (System/getenv "PATH"))))

;; --- the trackers /drive works (byj-m6f) ----------------------------------------

(def drivable
  "The trackers of a profile's `:issues` /drive works, in the order it
  prefers them when the profile names none: beads (bd) and GitHub (gh)."
  [:beads :github])

(defn github-id?
  "Whether `id` is a GitHub issue's, `#N`; any other is a bead's."
  [id]
  (boolean (re-matches #"#\d+" (str id))))

(defn- tracker-of [id] (if (github-id? id) :github :beads))

(defn- number-of [id] (subs (str id) 1))

(defn tools
  "What the trackers are reached with in project `dir`: bd and gh where they
  are found, and the GitHub repository the profile names (its first GitHub
  tracker's `:github-repo`; else gh takes the origin remote's)."
  [config args dir p]
  {:dir dir :bd (bd-path config args) :gh (gh-path config args)
   :repo (some #(when (= :github (:tracker %)) (not-empty (str (:github-repo %)))) (:issues p))})

(defn- gh [{:keys [dir gh repo]} & argv]
  (apply run dir gh (concat argv (when repo ["--repo" repo]))))

(defn- available?
  "Whether tracker `t` can be worked here: bd and `.beads/`, or gh."
  [t {:keys [dir bd gh]}]
  (case t
    :beads (boolean (and bd (.isDirectory (io/file (str dir) ".beads"))))
    :github (boolean gh)
    false))

(defn- said-of [r] (when (and r (not (:ok? r))) (:said r)))

(defn- ready-items
  "Tracker `t`'s ready work, in the order it is taken: `{:items [{:id
  :title :description}] :error}`. Beads: `bd ready`, epics left out. GitHub:
  the open issues no one is assigned, oldest first. Either passes over what
  is labelled `label`, set aside for the person."
  [t tl label]
  (case t
    :beads (let [r (run (:dir tl) (:bd tl) "ready" "--json" "--limit" "20" "--exclude-label" label)]
             {:error (said-of r)
              :items (vec (for [b (json-out r) :when (not= "epic" (:issue_type b))]
                            {:id (:id b) :title (str (:title b)) :description (str (:description b))}))})
    :github (let [r (gh tl "issue" "list" "--state" "open" "--limit" "100" "--json" "number,title,body,assignees,labels")]
              {:error (said-of r)
               :items (vec (for [i (sort-by :number (json-out r))
                                 :when (and (empty? (:assignees i))
                                            (not-any? #(= label (:name %)) (:labels i)))]
                             {:id (str "#" (:number i)) :title (str (:title i)) :description (str (:body i))}))})))

(defn show-item
  "What the tracker of `id` says of it: `{:title :description :status}`,
  `:status` as bd says it — `closed`, `in_progress` (a GitHub issue someone is
  assigned), `open` — or nil when it cannot be read."
  [tl id]
  (if (github-id? id)
    (when (:gh tl)
      (when-let [i (json-out (gh tl "issue" "view" (number-of id) "--json" "title,body,state,assignees"))]
        {:title (str (:title i)) :description (str (:body i))
         :status (cond (= "CLOSED" (str (:state i))) "closed" (seq (:assignees i)) "in_progress" :else "open")}))
    (when (:bd tl)
      (when-let [b (first (json-out (run (:dir tl) (:bd tl) "show" id "--json")))]
        {:title (str (:title b)) :description (str (:description b)) :status (str (:status b))}))))

(defn claim! [tl id]
  (if (github-id? id)
    (gh tl "issue" "edit" (number-of id) "--add-assignee" "@me")
    (run (:dir tl) (:bd tl) "update" id "--claim")))

(defn unclaim! [tl id]
  (if (github-id? id)
    (gh tl "issue" "edit" (number-of id) "--remove-assignee" "@me")
    (run (:dir tl) (:bd tl) "unclaim" id)))

(defn close! [tl id reason]
  (if (github-id? id)
    (gh tl "issue" "close" (number-of id) "--comment" reason)
    (run (:dir tl) (:bd tl) "close" id "--reason" reason)))

(defn- comment! [tl id text]
  (if (github-id? id)
    (gh tl "issue" "comment" (number-of id) "--body" text)
    (run (:dir tl) (:bd tl) "comments" "add" id text)))

(defn- label!
  "`label` put on `id`. A GitHub repository without the label gets it first,
  described as `description`: gh adds only a label the repository has."
  [tl id label description]
  (if (github-id? id)
    (let [r (gh tl "issue" "edit" (number-of id) "--add-label" label)]
      (if (:ok? r)
        r
        (do (gh tl "label" "create" label "--description" description)
            (gh tl "issue" "edit" (number-of id) "--add-label" label))))
    (run (:dir tl) (:bd tl) "label" "add" id label)))

(defn reachable? [tl id] (boolean (if (github-id? id) (:gh tl) (:bd tl))))

(defn- created-since
  "How many issues tracker `t` has that were filed since `since`."
  [t tl since]
  (case t
    :beads (count (json-out (run (:dir tl) (:bd tl) "list" "--all" "--json" "--created-after" since)))
    ;; GitHub's search takes a time to the second
    :github (count (json-out (gh tl "issue" "list" "--state" "all" "--limit" "200" "--json" "number"
                                 "--search" (str "created:>=" (let [t (str since)] (if (> (count t) 19) (str (subs t 0 19) "Z") t))))))))

(defn- kind-words
  "The words for tracker `t`'s work, `{:kind :Kind :kinds}`."
  [w t]
  (get-in w [:kinds t]))

(defn- word
  "Word `k` of `w` for `id`'s tracker: its own under `:github`, else the
  common one."
  [w id k]
  (or (when (github-id? id) (get-in w [:github k])) (get w k)))

;; --- the claims a run holds (byj-2pv) ------------------------------------------

(defn claims-file
  "Where /drive keeps the beads it has claimed and not yet settled:
  `~/.brainyard/projects/<slug>/drive/claims.edn`, bead id -> `{:session :pid :run}`.
  A run that ends — or is cancelled, or dies — inside a bead's turn leaves
  the bead claimed, and `bd ready` leaves a claimed bead out, so the next
  run would pass over it; this is how that one is found and released."
  [dir]
  (projects/runtime-path dir "drive/claims.edn"))

(defn- read-claims [dir]
  (or (try (let [f (claims-file dir)]
             (when (.isFile f) (let [m (edn/read-string (slurp f))] (when (map? m) m))))
           (catch Throwable _ nil))
      {}))

(defn- update-claims!
  "The claims file rewritten as `f` of it; removed when nothing is left.
  Bookkeeping: a write that fails stops no run."
  [dir f & args]
  (try (let [m (apply f (read-claims dir) args)
             file (claims-file dir)]
         (if (empty? m)
           (.delete file)
           (do (io/make-parents file) (spit file (pr-str m)))))
       (catch Throwable _ nil)))

(defn- release!
  "Each of `ids` released — a bead `bd unclaim`ed, a GitHub issue's
  assignment to gh's user removed — when it is still in progress, and
  dropped from the claims file either way; a log line for each released.
  bd releases only its own actor's claim, and gh only its own user's, so
  one someone has taken since is left to them."
  [tl ids why]
  (let [lines (reduce (fn [acc id]
                        (let [b (when (reachable? tl id) (show-item tl id))]
                          (if (= "in_progress" (:status b))
                            (let [r (unclaim! tl id)]
                              (conj acc (str "↺ " id " " (:title b) " — " why
                                             (when-let [x (said-of r)] (str " (" (if (github-id? id) "gh" "bd") " said: " x ")")))))
                            acc)))
                      [] ids)]
    (update-claims! (:dir tl) #(apply dissoc % ids))
    lines))

(defn- run-over?
  "Whether the run that recorded `claim` has ended, as far as can be told:
  it was this session's — its turns run one at a time, so a new run means
  the last one is over — or its process is gone. A claim another live
  session holds may be its run's, still working, and is left."
  [config claim]
  (let [sid (not-empty (str (:session-id config)))]
    (or (and sid (= sid (str (:session claim))))
        (not (persist/alive? (:pid claim))))))

(defn next-bead
  "The next step (`drive$next`): stop with why when the project has no
  profile, its profile names issue trackers /drive does not work (only
  Linear or Jira, `profile/trackers`), none it names can be worked here
  (`available?`), the profile's limits are reached — failures in a row,
  spend, issues filed since the run began — or nothing is ready; else the
  work to do, claimed, with its brief. The trackers are taken in the
  profile's order, beads alone when it names none: the first ready item of
  the first that has one, a bead (`bd ready`) or a GitHub issue no one is
  assigned (`#N`, claimed by assigning it to gh's user; byj-m6f).
  A bead or issue the last step left unfinished (`:retry`) is worked again,
  with what was missing, what its last turn ended saying
  (`:retry-progress`) and which of its turns this is (`:attempts` before
  it); one labelled as set aside for the person is passed over. A run's
  first step first releases what an earlier run claimed and never settled —
  cancelled or dead in a turn (`run-over?`) — each a line of `:log`. Where
  the profile has the person choose the work (`:issue/work :ask`), the
  person is asked first. `{:stop :stopped :bead :title :brief :head
  :since}`; `:since` is when the run began, kept by the tree."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        p (profile/read-profile dir)
        tl (tools config args dir p)
        named (let [ts (profile/trackers p)] (if (seq ts) (vec (distinct ts)) [:beads]))
        driven (filterv (set drivable) named)
        here (filterv #(available? % tl) driven)
        limits (:stop p)
        failures (or (num-of (:failures args)) 0)
        spent (or (some-> (:usage args) :cost) 0)
        first-step? (str/blank? (str (:since args)))
        since (or (not-empty (str (:since args)))
                  (str (java.time.Instant/ofEpochMilli (System/currentTimeMillis))))
        retry (not-empty (str (:retry args)))
        attempts (or (num-of (:attempts args)) 0)
        head (str/trim (:out (run dir "git" "rev-parse" "HEAD")))
        released (when (and first-step? p (seq here))
                   (release! tl (for [[id c] (read-claims dir) :when (run-over? config c)] id)
                             (:released-left w)))
        log (str/join "\n" (remove str/blank? (cons (str (:log args)) released)))
        brief (fn [k item]
                (let [{:keys [kind Kind]} (kind-words w (tracker-of (:id item)))]
                  (fill (get w k) (merge {:kind kind :Kind Kind} item))))]
    (assoc
      (cond
        (nil? p) (stop (get-in w [:stops :no-profile]))
        ;; the profile says where the issues are kept: Linear's and Jira's
        ;; are not worked yet (byj-y0u, byj-m6f)
        (empty? driven)
        (stop (fill (get-in w [:stops :not-driven]) {:trackers (str/join ", " (map name named))}))
        (empty? here)
        (stop (get-in w [:stops (if (= :github (first driven)) :no-gh :no-bd)]))
        (and (:failures limits) (>= failures (:failures limits)))
        (stop (fill (get-in w [:stops :failures]) {:n failures}))
        (and (:usd limits) (>= spent (:usd limits)))
        (stop (fill (get-in w [:stops :usd]) {:n (:usd limits)}))
        (and (:issues-per-run limits)
             (>= (reduce + (map #(created-since % tl since) here)) (:issues-per-run limits)))
        (stop (fill (get-in w [:stops :issues]) {:n (:issues-per-run limits)}))
        retry
        (let [b (show-item tl retry)]
          {:stop false :stopped "" :bead retry :title (str (:title b)) :head head :since since
           :brief (brief :retry {:id retry :title (:title b) :why (:retry-note args)
                                 :n (inc attempts) :max (bead-attempts config)
                                 :progress (or (not-empty (str/trim (str (:retry-progress args)))) (:no-progress w))})})
        :else
        (let [label (:set-aside-label w)
              found (reduce (fn [acc t]
                              (let [r (ready-items t tl label)]
                                (if-let [b (first (:items r))]
                                  (reduced {:item b})
                                  (update acc :errors conj (when (:error r) (str (name t) ": " (:error r)))))))
                            {:errors []} here)
              b (:item found)]
          (cond
            (nil? b) (stop (str (fill (get-in w [:stops :nothing-ready])
                                      {:what (str/join " and " (map #(:kind (kind-words w %)) here))})
                                (when-let [e (seq (remove nil? (:errors found)))]
                                  (str " (" (str/join "; " e) ")"))))
            (= :never (profile/level p :issue/work)) (stop (get-in w [:stops :work-never]))
            :else
            (let [no (when (= :ask (profile/level p :issue/work))
                       (ask-person ctx {:type :tool-use :action :call :tool "drive$next" :pattern "drive$next"
                                        :display (fill (:ask-work w) {:id (:id b) :title (:title b)})
                                        :args {:bead (:id b)}}))]
              (if no
                (stop (fill (get-in w [:stops :work-declined]) {:id (:id b)}))
                (do (claim! tl (:id b))
                    (update-claims! dir assoc (:id b) {:session (str (:session-id config)) :pid (persist/own-pid) :run since})
                    {:stop false :stopped "" :bead (:id b) :title (str (:title b)) :head head :since since
                     :brief (brief :brief {:id (:id b) :title (:title b)
                                           :description (str/trim (str (:description b)))
                                           :goal (if (:goal p) (str " (" (str/trim (:goal p)) ")") "")})}))))))
     :log log)))

(defn check-bead
  "What a bead's or issue's turn left (`drive$check`): the work tree's
  uncommitted files (beads', brainyard's and Claude Code's state aside), the
  commits since the step began and whether one names it, its status in its
  tracker (`show-item`),
  installed dependencies among the uncommitted (`words.edn`
  `:dependency-dirs`), and what the profile expects of a finished bead.
  Reads only. `{:text :clean? :committed? :status :dependencies}`."
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
        status (:status (show-item (tools config args dir p) bead))
        {:keys [Kind]} (kind-words w (tracker-of bead))
        expect (get-in w [:expect :git/commit (or (profile/level p :git/commit) :ask)])
        ;; a porcelain line's path, past its status and any rename's arrow
        path-of #(-> (subs % (min 3 (count %))) (str/split #" -> ") last str/trim (str/replace #"/$" ""))
        deps (vec (for [l dirty
                        :let [f (path-of l)]
                        :when (some #{(last (str/split f #"/"))} (:dependency-dirs w))]
                    f))]
    {:clean? (empty? dirty)
     :committed? committed?
     :status (str status)
     :text (str Kind " " bead ": " (or status "status unknown") "\n"
                "Uncommitted: " (if (seq dirty) (str/join ", " (map #(subs % (min 3 (count %))) dirty)) "nothing") "\n"
                "Commits since the step began: " (if (str/blank? log) "none" (str/replace log #"\n+" "; "))
                (when-not committed? (str "\nNo commit since the step began names " bead "."))
                (when (seq deps) (str "\n" (fill (:dependencies w) {:paths (str/join ", " deps)})))
                "\nExpected: " expect)
     :dependencies deps}))

(defn settle
  "Act on drive/judge's word on a bead or GitHub issue (`drive$settle`), in
  its own tracker: `done` closes it as the profile's `:issue/close` says — on its own at `:act`, after the
  person's yes at `:ask`, never at `:never` — and clears the failures;
  `retry` counts a failure and has the next step work the bead again, with
  what the turn ended saying — until the bead has had `bead-attempts`
  turns, when it is set aside instead: its claim released, labelled for the
  person (`words.edn` `:set-aside-label`, which `drive$next` passes over),
  a comment saying what its turns got to, and the run goes on to the next
  ready bead (byj-dw0); `ask` and `stop` end the run with the question or
  why. Every step is a line of the run's log.
  `{:failures :retry :retry-note :attempts :progress :stop :stopped :log}`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        p (profile/read-profile dir)
        bead (str (:bead args))
        title (str (:title args))
        status (keyword (str/lower-case (str/trim (str (:status args)))))
        summary (str/trim (str (:summary args)))
        failures (or (num-of (:failures args)) 0)
        attempts (inc (or (num-of (:attempts args)) 0))
        progress (clip (:work args) progress-max-chars)
        log (str (:log args))
        line #(str log (when-not (str/blank? log) "\n") %)
        tl (tools config args dir p)
        reach? (reachable? tl bead)
        tool (if (github-id? bead) "gh" "bd")
        ;; the bead's turn spent this in a scope of its own: said as a
        ;; tool's spend, the run's usage counts it (agent/account-tool-cost!)
        cost (let [c (some-> (:work-usage args) :cost)] (when (and (number? c) (pos? c)) c))]
    (when (#{:done} status) (update-claims! dir dissoc bead))
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
            r (when (and (not closed?) (nil? no) reach?)
                (close! tl bead (str "/drive: " summary)))]
        {:failures 0 :retry "" :retry-note "" :attempts 0 :progress "" :stop false :stopped ""
         :log (line (str "✓ " bead " " title " — " summary
                         (cond closed? " (closed in its turn)"
                               no (str " (left open: " no ")")
                               (and r (not (:ok? r))) (str " (" tool " close failed: " (:said r) ")")
                               :else " (closed)")))})
      :retry
      (if (< attempts (bead-attempts config))
        {:failures (inc failures) :retry bead :retry-note summary :attempts attempts :progress progress
         :stop false :stopped ""
         :log (line (str "↻ " bead " " title " — " summary))}
        ;; its turns are spent: set aside for the person, and on to the next
        (let [said (fn [r] (when (and r (not (:ok? r))) (str " (" (:said r) ")")))
              label (:set-aside-label w)
              vars {:id bead :number (when (github-id? bead) (number-of bead)) :n attempts :why summary
                    :progress (or (not-empty progress) (:no-progress w))}
              failed (when reach?
                       (str (said (unclaim! tl bead))
                            (said (label! tl bead label (:set-aside-label-description w)))
                            (do (update-claims! dir dissoc bead) nil)
                            (said (comment! tl bead (fill (word w bead :set-aside-note) vars)))))]
          {:failures (inc failures) :retry "" :retry-note "" :attempts 0 :progress "" :stop false :stopped ""
           :log (line (str "⏸ " bead " " title " — " summary "; "
                           (fill (word w bead :set-aside) vars)
                           (when (not-empty failed) (str " — " tool " said:" failed))))}))
      :ask
      (let [q (str/trim (str (:question args)))]
        {:failures failures :retry "" :retry-note "" :attempts 0 :progress "" :stop true
         :stopped (str bead " needs you: " (if (str/blank? q) summary q))
         :log (line (str "? " bead " " title " — " summary))})
      {:failures failures :retry "" :retry-note "" :attempts 0 :progress "" :stop true
       :stopped (str bead " cannot go on: " summary)
       :log (line (str "✗ " bead " " title " — " summary))})
      cost (assoc :usage-cost-usd cost))))

(defn report
  "The run's answer (`drive$report`): each step's line and why it stopped.
  A bead this run claimed and did
  not settle — a question or a stop ended the run, or a limit did — is
  released first, so the next run finds it ready (byj-2pv)."
  [config args]
  (let [dir (dir-of config args)
        tl (tools config args dir (profile/read-profile dir))
        since (str (:since args))
        released (when-not (str/blank? since)
                   (release! tl (for [[id c] (read-claims dir)
                                          :when (and (= since (:run c))
                                                     (= (str (:session-id config)) (str (:session c))))]
                                      id)
                             (:released-end (words))))
        log (str/trim (str/join "\n" (remove str/blank? (cons (str (:log args)) released))))]
    {:text (str "/drive:\n"
                (if (str/blank? log) "nothing was worked on." log)
                "\n\nStopped: " (let [s (str/trim (str (:stopped args)))] (if (str/blank? s) "the step limit was reached." s)))}))

(defn commands
  "The `drive$*` commands (RFC-073)."
  [config]
  (let [any-arg (fn [k desc] [k {:optional true} [:any {:desc desc}]])]
    [{:name "drive$next" :kind :command
      :description (tool-words/description "drive$next")
      :input-schema [:map (any-arg :dir (tool-words/description "drive$next :dir")) (any-arg :failures (tool-words/description "drive$next :failures"))
                     (any-arg :usage (tool-words/description "drive$next :usage")) (any-arg :since (tool-words/description "drive$next :since"))
                     (any-arg :retry (tool-words/description "drive$next :retry")) (any-arg :retry-note (tool-words/description "drive$next :retry-note"))
                     (any-arg :retry-progress (tool-words/description "drive$next :retry-progress")) (any-arg :attempts (tool-words/description "drive$next :attempts"))
                     (any-arg :log (tool-words/description "drive$next :log"))]
      :output-schema [:map [:stop :any] [:stopped :any] [:bead :any] [:brief :any] [:log {:optional true} :any]
                      [:title {:optional true} :any] [:head {:optional true} :any] [:since {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (next-bead config ctx args))}
     {:name "drive$check" :kind :command
      :description (tool-words/description "drive$check")
      :input-schema [:map (any-arg :dir (tool-words/description "drive$check :dir")) (any-arg :bead (tool-words/description "drive$check :bead")) (any-arg :head (tool-words/description "drive$check :head"))]
      :output-schema [:map [:text :string] [:clean? :any] [:committed? :any] [:status :any] [:dependencies {:optional true} :any]]
      :effects #{:fs/read :process}
      :fn (fn [args _] (check-bead config args))}
     {:name "drive$settle" :kind :command
      :description (tool-words/description "drive$settle")
      :input-schema [:map (any-arg :dir (tool-words/description "drive$settle :dir")) (any-arg :bead (tool-words/description "drive$settle :bead")) (any-arg :title (tool-words/description "drive$settle :title"))
                     (any-arg :status (tool-words/description "drive$settle :status")) (any-arg :question (tool-words/description "drive$settle :question")) (any-arg :summary (tool-words/description "drive$settle :summary"))
                     (any-arg :bead-status (tool-words/description "drive$settle :bead-status")) (any-arg :failures (tool-words/description "drive$settle :failures"))
                     (any-arg :log (tool-words/description "drive$settle :log")) (any-arg :work-usage (tool-words/description "drive$settle :work-usage"))
                     (any-arg :attempts (tool-words/description "drive$settle :attempts")) (any-arg :work (tool-words/description "drive$settle :work"))]
      :output-schema [:map [:failures :any] [:stop :any] [:stopped :any] [:log :any]
                      [:retry :any] [:retry-note :any] [:attempts :any] [:progress :any]
                      [:usage-cost-usd {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (settle config ctx args))}
     {:name "drive$report" :kind :command
      :description (tool-words/description "drive$report")
      :input-schema [:map (any-arg :dir (tool-words/description "drive$report :dir")) (any-arg :log (tool-words/description "drive$report :log")) (any-arg :stopped (tool-words/description "drive$report :stopped"))
                     (any-arg :since (tool-words/description "drive$report :since"))]
      :output-schema [:map [:text :string]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args _] (report config args))}]))
