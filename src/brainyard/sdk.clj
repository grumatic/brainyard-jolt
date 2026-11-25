;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.sdk
  "The Brainyard Agent SDK's one implementation (RFC-053, byj-g5n.3): a
  session opened by an application, in process, through the seams every front
  end uses (RFC-027). `byj acp` is a front end over this; a jolt application
  may call it directly.

  A session here is an ordinary session: `session/run` on a thread of its own,
  as the full-screen renderer runs each of its sessions (`tui/run-many!`'s
  worker), with its tree, gate, hooks, trajectory and event log. What the
  application supplies are values on the seams — a permission answer, tools,
  hooks, a viewer of the events — and what it gets back is each prompt's
  result. Mechanism only (RFC-001): nothing here decides what the agent does.

  How a prompt is answered: it waits in the session's queue until `read-input`
  takes it, and is answered the next time `read-input` is called — once the
  session is waiting again, so after the turn's end and its snapshot, as the
  session channel answers only once its turn is saved (provenance P-26). A
  line the session ran no turn for (a refused or a slash command) is answered
  with what the session said instead."
  (:require [brainyard.projects :as projects]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
            [brainyard.cost :as cost]
            [brainyard.context :as context]
            [brainyard.effect :as fx]
            [brainyard.launch :as launch]
            [brainyard.mcp :as mcp]
            [brainyard.persist :as persist]
            [brainyard.predictor :as predictor]
            [brainyard.schedule :as schedule]
            [brainyard.schema :as schema]
            [brainyard.changes :as changes]
            [brainyard.channel :as channel]
            [brainyard.session :as session]
            [brainyard.worktree :as worktree]
            [brainyard.tasks :as tasks]
            [brainyard.tools :as tools]))

(def max-queue
  "Prompts that may wait for one session, as the session channel's queue
  holds ten (RFC-031)."
  10)

(def ^:private poll-ms 50)

(defonce ^:private open-ids
  ;; the sessions open in this process, by project and id: the lock file says
  ;; which PROCESS holds a session, and `persist/holder` does not count its
  ;; own, so two sessions of one process could otherwise share an id
  (atom #{}))

(defn- problem! [on-problem msg]
  (if on-problem (on-problem msg) (binding [*out* *err*] (println msg))))

(defn- absolute [dir] (.getCanonicalPath (io/file dir)))

(defn- slug [s]
  (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" "")))

(defn- worktree-place
  "Where a worktree asked for as `w` (`{:branch :path}`) goes: its branch,
  `default-branch` when not named, and its directory, `path` relative to
  the project `src`, or `worktrees/<branch>` in the project's folder of the
  home (`projects/runtime-path`, byj-84c.5)."
  [src w default-branch]
  (let [branch (or (not-empty (str/trim (str (:branch w)))) default-branch)]
    {:branch branch
     :dir (if-let [p (not-empty (str (:path w)))]
            (let [f (io/file p)] (if (.isAbsolute f) f (io/file src p)))
            (projects/runtime-path src "worktrees" (slug branch)))}))

(defn new-worktree!
  "A git worktree for a new session to start in, no session forked
  (byj-z0z): `:worktree`'s `:branch` checked out as it is when it exists
  and no `:from` is named, else a new branch — `byj/<id>` unless named —
  from `:from` (HEAD when not named); a clean checkout, the project's
  uncommitted work left where it is (`worktree/open!`). Where it goes is
  as `fork-worktree!` puts one. What `base` opens, as its `:worktree`:
  `{:dir :project-dir :branch :base :created :from-dir :session-id}`.
  Throws ex-info when it cannot be made, and leaves no worktree then."
  [{:keys [project-dir worktree session-id]}]
  (let [src (absolute project-dir)
        top (or (worktree/top src) (throw (ex-info (str src " is not in a git work tree: a worktree is git's") {})))
        nid (or session-id (context/session-id))
        {:keys [branch dir]} (worktree-place src worktree (str "byj/" nid))
        w (worktree/open! top dir branch (not-empty (str (:from worktree))))]
    (when (:error w) (throw (ex-info (:error w) {})))
    (assoc w :project-dir (worktree/within top src (:dir w)) :from-dir src :session-id nid)))

(defn fork-worktree!
  "A fork in a git worktree of its own (byj-8yt): session `:fork-from` of
  `:project-dir`, cut before `:fork-before-turn` or as it stands, written
  as a new session into a worktree whose files are the turn's start — or,
  with no turn, the project's as they are now (`changes/worktree!`). The
  worktree is `worktrees/<branch>` in the project's folder of the home unless
  `:worktree`'s `:path` names one (relative to the project), on a new
  branch `byj/fork-<id>` unless its `:branch` names one. The new session is
  told where it now works, its earlier turns' paths being the project's.
  The session forked from and the project's work tree are not touched.
  What `base` opens, as its `:worktree`: `{:dir :project-dir :branch :base
  :files :from-dir :session-id}`. Throws ex-info when it cannot be made,
  and leaves no worktree then."
  [{:keys [project-dir fork-from fork-before-turn worktree session-id keep-files?]}]
  (cond
    (not fork-from) (throw (ex-info ":worktree needs :fork-from" {}))
    keep-files? (throw (ex-info ":keep-files? is for a fork in the same work tree: a worktree's files are the turn's start" {})))
  (let [src (absolute project-dir)
        src-config {:project-dir src :working-dir src :session-id fork-from}
        nid (or session-id (context/session-id))
        {:keys [branch dir]} (worktree-place src worktree (str "byj/fork-" nid))
        snap (persist/read-session src-config fork-from)
        _ (when (:problem snap) (throw (ex-info (str "cannot fork " fork-from ": " (:problem snap)) {:fork-from fork-from})))
        w (changes/worktree! src-config fork-from fork-before-turn dir branch (not-empty (str (:from worktree))))
        _ (when (:error w) (throw (ex-info (str "cannot fork " fork-from " into a worktree: " (:error w)) {:fork-from fork-from})))
        dest {:project-dir (:project-dir w) :working-dir (:project-dir w)}
        r (persist/fork! src-config fork-from nid {:before-turn fork-before-turn :into dest})]
    (when (:problem r)
      (worktree/remove! src (:dir w) branch)
      (throw (ex-info (str "cannot fork " fork-from ": " (:problem r)) {:fork-from fork-from})))
    (persist/note! dest nid (session/rewind-line :fork-worktree {}
                                                 {:from fork-from :dir (:project-dir w) :branch branch :project src
                                                  :cut (if fork-before-turn (str " before its turn " fork-before-turn) "")
                                                  :when (if fork-before-turn (str "when turn " fork-before-turn " began") "when it was forked")}))
    (assoc w :from-dir src :session-id nid)))

;; --- opening -----------------------------------------------------------------------

(defn base
  "The `session/run` arguments for a session in `project-dir`, assembled as the
  CLI assembles a run's (`brainyard.launch`), with `opts` where the CLI has
  flags. Exposed for tests; `open-session` is the API.

  opts: :project-dir (required; the project itself, never the git root above
  it) :provider :model (or :lm, an LM map, for a
  jolt application or a test) :loop :agent :config
  (overrides, the flags' layer: above the project's files, below BY_*)
  :mcp-servers (merged over the config's) :permission-mode :permission-fn
  :tools :hooks :on-event :resume :fork-from (a session to start where it
  stands, under a new id, leaving it as it was) :fork-before-turn (with
  :fork-from, its conversation cut before that turn and its files put back
  as the turn began, as `/fork TURN` does; :keep-files? true leaves them)
  :session-id (a new session's id, chosen by the caller:
  `context/session-id`'s shape) :env-fn :on-problem :worktree (`{:branch
  :path :from}`, each optional: the session opened in a git worktree of its
  own — with :fork-from, the fork made there, `fork-worktree!`; without, a
  new session on a checkout, `new-worktree!` — or what those returned, to
  open one already made). A fork's outcome is `:fork` in what it returns:
  `{:from :before-turn :files :saved :error :worktree}`; a worktree made is
  `:worktree`."
  [{:keys [project-dir provider model loop agent mcp-servers permission-mode permission-fn
           tools hooks on-event resume env-fn on-problem] :as opts}]
  (when (str/blank? (str project-dir))
    (throw (ex-info "a session needs a :project-dir" {})))
  (let [wt (let [w (:worktree opts)]
             (cond (or (nil? w) (:dir w)) w
                   (:fork-from opts) (fork-worktree! opts)
                   :else (new-worktree! opts)))
        wd (absolute (if wt (:project-dir wt) project-dir))
        env (or env-fn (launch/env-fn wd))
        ;; the directory given IS the project: the CLI takes the git root above
        ;; its working directory, and a session opened in a subdirectory of a
        ;; repository wrote its trajectory into the repository's (byj-g5n.3)
        ;; resumed where it is kept, a session goes on in the folder it worked
        ;; in: a stage's or a member's worktree (byj-cm4.12)
        rwd (when (and resume (not (:fork-from opts)) (not wt))
              (persist/resume-working-dir {:project-dir wd :working-dir wd} resume))
        overrides (cond-> (merge {:project-dir wd :working-dir (or rwd wd)} (:config opts))
                    permission-mode (assoc :permission-mode permission-mode)
                    (seq mcp-servers) (update :mcp-servers merge mcp-servers))
        load (fn []
               (let [{:keys [config problems sources]}
                     (config/load-config {:working-dir wd :overrides overrides :env-fn env})]
                 {:config (assoc config :config-sources sources) :problems problems}))
        {:keys [config problems]} (load)
        _ (doseq [p problems] (problem! on-problem (str "config: " p)))
        provider (some-> provider keyword)
        [provider model] (launch/lm-choice provider model config #(problem! on-problem %))
        lm (or (:lm opts) (launch/make-lm provider model config env))
        ;; a fork is a new session resumed from a copy of another's snapshot
        ;; (`persist/fork!`), the other left as it was
        fork-from (:fork-from opts)
        k (:fork-before-turn opts)
        _ (when (and k (not fork-from)) (throw (ex-info ":fork-before-turn needs :fork-from" {})))
        fork (cond
               ;; made already, the session written there: what is left is
               ;; to resume it
               (and wt fork-from) (cond-> {:id (:session-id wt) :from fork-from
                           :worktree (select-keys wt [:dir :project-dir :branch :base :files])}
                    k (assoc :before-turn k))
               fork-from
               (let [nid (or (:session-id opts) (context/session-id))
                     r (persist/fork! config fork-from nid (when k {:before-turn k}))]
                 (when (:problem r) (throw (ex-info (str "cannot fork " fork-from ": " (:problem r)) {:fork-from fork-from})))
                 ;; the files put back as the turn began, unless kept, and
                 ;; each session told at its next turn, as /fork tells them
                 ;; (RFC-069, byj-d8c.3)
                 (let [files (when (and k (not (:keep-files? opts))) (changes/rewind! config fork-from k))]
                   (when (seq (:files files))
                     (persist/note! config fork-from (session/client-line (session/rewind-line :forked-away files {:session nid}))))
                   (when (and k (:keep-files? opts))
                     (persist/note! config nid (session/rewind-line :fork-kept {:turn k} {:from fork-from})))
                   (cond-> {:id nid :from fork-from}
                     k (assoc :before-turn k)
                     files (assoc :files (:files files) :saved (:saved files) :error (:error files))))))
        resume (if fork (:id fork) resume)
        config (cond-> (assoc config :session-id (or resume (:session-id wt) (:session-id opts) (context/session-id)))
                 resume (assoc :resume resume))
        tree-name (or loop session/session-loop)
        b {:lm lm
           :env-fn env
           :lm-flagged? (boolean (or (:provider opts) (:model opts)))
           ;; the client's tools beside the primitives: in :base-tools, so a
           ;; ctx built again keeps them (byj-g5n.2). One named as a primitive
           ;; is it, in its place — `byj acp`'s file tools over a client's fs
           ;; (byj-gly.464) — never a second of the name in the listing
           :tools (let [given (tools/tools-by-name tools)]
                    (into (mapv #(get given (:name %) %) tools/builtin-tools)
                          (remove #(contains? (tools/tools-by-name tools/builtin-tools) (:name %)) tools)))
           :hooks (vec hooks)
           :tree (agent/load-tree tree-name (:project-dir config))
           :config config
           :config-loaded config
           :reload-config load
           :permission-fn permission-fn
           :permission-mode-box (atom (:permission-mode config :auto))
           :fork fork
           ;; the worktree it was opened in, when one was made for it
           :worktree (some-> wt (select-keys [:dir :project-dir :branch :base :created :files]))
           :on-event (launch/event-bus config on-event)}]
    (if agent
      (or (session/with-persona b agent {:loop loop})
          (throw (ex-info (str "no agent named " (pr-str agent)) {:agent agent})))
      b)))

(defn- answer!
  "Answer the prompt the session took last, if any: with the turn's result
  when it ran one, else with what the session said."
  [state]
  (let [[{:keys [current]}] (swap-vals! state assoc :current nil)]
    ;; a turn the session channel put in has no prompt to answer: the channel
    ;; answers it (byj-p2q)
    (when-let [{:keys [reply result said]} current]
      (when reply
        (deliver reply (cond-> {:said said}
                         result (assoc :result result)
                         (nil? result) (assoc :no-turn true)))))))

(defn open-session
  "Open a session in `(:project-dir opts)` and start it on a thread of its
  own. Returns the session: `{:id :config :state :ready :done …}`, for
  `prompt`, `cancel!`, `set-mode!` and `close!`; `:ready` is a promise of
  true once it waits for its first prompt (a resumed one has replayed its
  history by then, `:session/resumed`), false if it ended first. See `base` for opts, and also:
  `:label`, `:commands?` (slash commands and `:<tool>` direct calls allowed
  in a prompt; false), `:on-said (fn [kind text])` (what the session says
  beside its events: its answers, notices and errors), `:channel?` (listen on
  the session channel, RFC-031, as a `byj run` session does: another session,
  a script or a share's sidecar asks it, each request a turn taken when the
  session is waiting, after the prompts queued here; byj-p2q), `:approve
  (fn [{:keys [text from who timeout-ms]}])` (whether such a turn put in by a
  share's viewer runs: `:approved`, `:rejected`, `:expired`, or nil when no
  one is there to ask).

  The session is locked to this process while open, a fresh one as well as
  a resumed one (RFC-031); a session another live process holds is refused.
  Its MCP servers start in the background, as a session's do (RFC-024)."
  [opts]
  (let [b (base opts)
        config (:config b)
        id (:session-id config)
        persist? (:persist-session? config)
        k [(:project-dir config) id]
        [before] (swap-vals! open-ids conj k)
        _ (when (contains? before k)
            (throw (ex-info (str "session " id " is already open in this process") {:session-id id})))
        _ (when (and persist? (not (persist/lock! config id)))
            (swap! open-ids disj k)
            (throw (ex-info (str "session " id " is open in another running process (pid "
                                 (persist/holder config id) ")")
                            {:session-id id})))
        mcp-box (mcp/box (:env-fn b))
        _ (mcp/start-all-in-background! mcp-box config)
        state (atom {:queue [] :current nil :closed? false})
        ;; the session is up and waiting for its first prompt — a resumed one
        ;; has said its history by then (`:session/resumed`)
        ready (promise)
        cancellers (atom {})
        wakeups (tasks/wakeups)
        task-registry (tasks/registry)
        ;; the model the session asks once a resume or `/model` chose one,
        ;; for `current-lm` (byj-66y)
        lm-box (atom nil)
        on-said (:on-said opts)
        ;; the session channel, when asked for and :ask-channel-enabled? allows
        ;; it (byj-p2q): one that could not listen is said, and the session
        ;; runs without it
        ch (when (and (:channel? opts) (:ask-channel-enabled? config true) persist?)
             (let [c (channel/open! config id
                                    {:label (:label opts)
                                     :cancel! #(session/cancel-turn! cancellers)
                                     :describe (fn []
                                                 (let [lm (or @lm-box (:lm b))]
                                                   {:provider (some-> (:provider lm) name) :model (:model lm)}))})]
               (if-let [p (:problem c)]
                 (do (problem! (:on-problem opts) p) nil)
                 c)))
        ;; the viewer sees every event; the turn's end is also kept, for the
        ;; prompt the turn was answering
        b (update b :on-event (fn [bus]
                                (fn [e d]
                                  (when (= :session.turn/end e)
                                    (swap! state #(cond-> % (:current %) (assoc-in [:current :result] d))))
                                  (bus e d))))
        emit (fn [kind text]
               (swap! state #(cond-> % (:current %) (update-in [:current :said] (fnil conj []) [kind text])))
               (when on-said (on-said kind text)))
        read-input (fn [_prompt]
                     ;; waiting again: whatever was asked last and not answered
                     ;; at its turn's end (a command, which runs no turn) is
                     ;; answered now
                     (answer! state)
                     (deliver ready true)
                     (loop []
                       (let [{:keys [queue closed?]} @state]
                         (cond
                           closed? nil
                           (seq queue)
                           (let [[q] queue]
                             (swap! state #(-> % (update :queue (comp vec rest)) (assoc :current q)))
                             (:text q))
                           ;; a request on the session channel, after the
                           ;; prompts queued here (byj-p2q)
                           (and ch (channel/pending? ch))
                           (if-let [{:keys [text from]} (channel/next-turn! ch)]
                             (do (swap! state assoc :current {:text text :from from :channel? true})
                                 text)
                             (recur))
                           ;; a parked turn whose task finished (RFC-019), or a
                           ;; prompt of this session set to a time now due: no
                           ;; one sent a prompt for it either, so it is marked
                           ;; as a channel turn is, and an application is told
                           ;; of it as of one (byj-5rx)
                           :else
                           (if-let [t (or (tasks/ready-wakeup! wakeups task-registry)
                                          (try (schedule/ready-turn! (or (:project-dir config) (:working-dir config) ".") (str id))
                                               (catch Throwable _ nil)))]
                             (do (swap! state assoc :current {:text t :woken? true})
                                 t)
                             (do (Thread/sleep poll-ms) (recur)))))))
        done (promise)
        worker (Thread. (fn []
                          (try
                            (session/run (cond-> (assoc b :read-input read-input :emit emit :lm-box lm-box
                                                        :mcp-box mcp-box :cancellers cancellers
                                                        :wakeups wakeups :task-registry task-registry
                                                        ;; the prompt is answered once its turn's
                                                        ;; answer is saved, not after the recap and
                                                        ;; the judges that follow it (byj-2pr)
                                                        :turn-answered (fn [_] (answer! state)))
                                           ch (assoc :channel ch)
                                           ;; an injected turn the owner allows
                                           ;; (RFC-043 :approval :owner), asked by
                                           ;; the application (byj-p2q)
                                           (:approve opts) (assoc :approve (:approve opts))))
                            (catch Throwable t
                              (problem! (:on-problem opts) (str "session " id " ended: " (ex-message t))))
                            (finally
                              ;; ended, however it ended: a prompt sent after
                              ;; is refused, never queued for no one (byj-6sd)
                              (swap! state assoc :closed? true)
                              (answer! state)
                              (some-> ch channel/close!)
                              ;; prompts never taken: the session is gone
                              (doseq [q (:queue @state)] (deliver (:reply q) {:closed true}))
                              (mcp/stop-all! mcp-box)
                              (when persist? (persist/unlock! config id))
                              (swap! open-ids disj k)
                              (deliver ready false)
                              (deliver done true)))))]
    (.start worker)
    {:id id :config config :label (:label opts) :lm (:lm b) :lm-box lm-box
     :commands? (boolean (:commands? opts))
     :fork (some-> (:fork b) (dissoc :id))
     :worktree (:worktree b)
     :state state :cancellers cancellers
     :permission-mode-box (:permission-mode-box b)
     :mcp-box mcp-box :ready ready :done done
     :on-event (:on-event b)
     ;; its session channel, when it listens (byj-p2q)
     :channel ch}))

(defn current-lm
  "The LM session `s` asks now: the one a resume took back from its save or
  `/model` switched to, else the one it was opened with (byj-66y)."
  [s]
  (or (some-> (:lm-box s) deref) (:lm s)))

;; --- talking to it -----------------------------------------------------------------

(defn refusal
  "Why `text` is not taken as a prompt, or nil: an empty one; a slash command
  or a `:<tool>` direct call where the session was not opened with
  `:commands?` (the session channel's rule, RFC-031 — an application may open
  one with it, since unlike a peer it owns the process); `/quit` and `/exit`,
  since the application ends the session; a full queue; a closed session."
  [{:keys [state commands?]} text]
  (let [t (str/trim (str text))]
    (cond
      (:closed? @state) "the session is closed"
      (str/blank? t) "empty prompt"
      (and (not commands?) (re-find #"^[/:]" t))
      (str "not run: " t " — a slash command or a direct tool call needs a session opened with commands allowed")
      ;; the application that opened the session ends it (`close!`, ACP's
      ;; session/close); typed, it ended the loop under it, and the
      ;; application went on sending prompts to a session gone (byj-6sd)
      (re-find #"^/(?:quit|exit)(?:\s|$)" t)
      (str "not run: " (first (str/split t #"\s")) " — the application that opened this session ends it")
      (>= (count (:queue @state)) max-queue) (str "input queue is full (max " max-queue ")"))))

(defn submit!
  "Queue `text` for the session. Returns a promise of the answer:
  `{:result turn-result :said [[kind text]…]}` once the turn ran (the result
  is `agent/turn-result`'s with `:elapsed-ms`), `{:no-turn true :said …}` when
  the line ran none, `{:refused reason}` at once, or `{:closed true}` when the
  session ends first."
  [session text]
  (let [reply (promise)]
    (if-let [why (refusal session text)]
      (deliver reply {:refused why})
      (swap! (:state session) update :queue conj {:text (str/trim (str text)) :reply reply}))
    reply))

(defn command!
  "Queue the byj command `text` (`/rewind 3`) for the session as `submit!`
  queues a prompt, whether or not it was opened with `:commands?`: what an
  application asks for by a method of its own (ACP's `_byj/turns/*`,
  byj-d8c.3) rather than by typing it, so it runs as the session runs its
  commands — between its turns, with what they check and tell — and never
  as a prompt the model reads. The same promise as `submit!`."
  [session text]
  (let [t (str/trim (str text))]
    (if (re-find #"^/" t)
      (submit! (assoc session :commands? true) t)
      (doto (promise) (deliver {:refused (str "not a command: " t)})))))

(defn prompt
  "`submit!` as a Task settling with the answer. Cancelling the task stops the
  wait, not the turn: `cancel!` is that."
  [session text]
  (fx/from-promise (submit! session text)))

(defn running?
  "Is a prompt being worked on?"
  [session]
  (some? (:current @(:state session))))

(defn cancel!
  "Cancel the running turn, if any. True when one was running."
  [session]
  (session/cancel-turn! (:cancellers session)))

(defn set-mode!
  "The permission mode from now on (RFC-012): `:auto`, `:auto-approve`,
  `:ask-each-time` or `:deny-by-default`."
  [session mode]
  (reset! (:permission-mode-box session) (keyword mode)))

(defn close!
  "End the session: its running turn cancelled, a prompt still queued
  answered `{:closed true}`, its lock released and its MCP servers stopped.
  Waits up to `timeout-ms` (10 s) for it; true when it ended."
  ([session] (close! session 10000))
  ([session timeout-ms]
   (swap! (:state session) assoc :closed? true)
   (cancel! session)
   (deref (:done session) timeout-ms false)))

;; --- predict -----------------------------------------------------------------------

(defn predict
  "One predictor call (RFC-005) — structured output, no session, no tools,
  no turn. `:predictor` names one by id (a `user/` id from the project's or
  home's `.brainyard/predictors`), or `:signature` is an inline spec
  (`predictor/compile-spec`'s shape: `:instructions`, `:inputs`, `:outputs`,
  `:strategy`). Returns `predictor/run`'s map: `{:predictor-id
  :params-source :outputs :reasoning :usage}`.

  opts: :project-dir :provider :model :lm :config :inputs :on-event :env-fn."
  [{:keys [project-dir predictor signature inputs provider model on-event env-fn] :as opts}]
  (let [wd (absolute (or project-dir "."))
        env (or env-fn (launch/env-fn wd))
        {:keys [config]} (config/load-config {:working-dir wd :overrides (merge {:project-dir wd :working-dir wd} (:config opts))
                                              :env-fn env})
        [provider model] (launch/lm-choice (some-> provider keyword) model config (fn [_]))
        lm (or (:lm opts) (launch/make-lm provider model config env))
        roots (or (:predictor-roots config) (predictor/default-roots (:project-dir config) "predictors"))
        p (cond
            predictor (predictor/load-predictor predictor roots)
            signature (predictor/compile-spec (merge {:id "user/inline"} signature))
            :else (throw (ex-info "predict needs a :predictor or a :signature" {})))]
    (let [r (predictor/run p (or inputs {}) {:lm lm
                                             :params-roots (or (:params-roots config) (predictor/default-roots (:project-dir config) "programs"))
                                             :on-event (launch/event-bus config on-event)
                                             :dir (:project-dir config)})
          c (cost/of (cost/table config) (:usage r) (:provider r) (:model r))]
      (cond-> r c (assoc :cost (:total-cost c))))))

;; --- a structured answer ------------------------------------------------------------

(def ^:private structured-answer-id "sdk/structured-answer")

(defn- schema-fields
  "A JSON Schema as a predictor's output fields: an object's properties, each
  its own field — one left out of `required` may be null — or, for anything
  else, one field `value`."
  [js]
  (let [js (into {} (map (fn [[k v]] [(keyword (name k)) v])) js)]
    (if (and (= "object" (some-> (:type js) name)) (seq (:properties js)))
      (let [required (set (map name (:required js)))]
        (vec (for [[k v] (:properties js)
                   :let [v (into {} (map (fn [[a b]] [(keyword (name a)) b])) v)
                         form (schema/from-json-schema (dissoc v :description))]]
               [(keyword (name k)) {:desc (or (:description v) (name k))}
                (if (contains? required (name k)) form [:maybe form])])))
      [[:value {:desc (or (:description js) "the answer")} (schema/from-json-schema js)]])))

(defn structure-answer
  "A turn's `answer` to `question` as the data `json-schema` describes
  (RFC-053, byj-g5n.10): one call of the predictor `sdk/structured-answer`,
  whose words are a resource and whose outputs are the schema's fields
  (`schema-fields`), on the session's model. A reply that does not match is
  asked for once more with what was wrong. Returns `{:value :usage :cost}`,
  `:value` the fields — or the one value when the schema is not an object —
  or throws with why. A schema this cannot read throws before any call."
  [{:keys [lm config on-event]} question answer json-schema]
  (let [fields (schema-fields json-schema)
        spec (assoc (edn/read-string (slurp (io/resource (str predictor/bundled-root "/" structured-answer-id ".edn"))))
                    :outputs fields)
        p (predictor/compile-spec spec {:source :builtin})
        object? (not= [:value] (mapv first fields))
        run (fn [zones]
              (predictor/run p {:question (str question) :answer (str answer)}
                             {:lm lm :zones zones :on-event on-event :dir (:project-dir config)
                              :params-roots (or (:params-roots config)
                                                (predictor/default-roots (:project-dir config) "programs"))}))
        r (try (run nil)
               (catch clojure.lang.ExceptionInfo e
                 (if (= :malformed (:kind (ex-data e)))
                   (run [["The previous reply was refused" (str (ex-message e) ". Answer again, every field of its type.")]])
                   (throw e))))
        c (cost/of (cost/table config) (:usage r) (:provider r) (:model r))]
    {:value (if object? (:outputs r) (get-in r [:outputs :value]))
     :usage (:usage r)
     :cost (some-> c :total-cost)}))
