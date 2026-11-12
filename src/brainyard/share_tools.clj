;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.share-tools
  "Sharing as `/share` does it and as the session's agent does it
  (RFC-043, byj-gly.362.2): the operations — what is shared, start,
  stop, pause, resume, kick — and the `share$*` commands over them. The
  person's command and the model's tools are one code path, so they cannot
  disagree about what a share is or how it is started.

  A share of this session is the session's own task (`share/start!`), its
  presence read from `:share-box`; a share of any other session, terminal or
  agent goes through this process's sharer (`share/process-sharer`), so it
  ends with this process, as `/share start REF` always has. Starting one
  publishes what it shares to the people it invites, so `share$start` carries
  the `:share/publish` effect, which `:tool-ask-effects` asks about by
  default (RFC-012)."
  (:require [clojure.string :as str]
            [brainyard.channel :as channel]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.share :as share]
            [brainyard.teams :as teams]))

;; --- the operations ------------------------------------------------------------

(defn refs
  "What a start was asked to share, as session references: each as given
  (`byj:ID`, `claude:ID`, `term:NAME`, `agent:NAME`, a label, a bare id), and
  `team` as the team this session leads — itself and its members still open
  (RFC-032)."
  [config args]
  (vec (mapcat (fn [a]
                 (if (= "team" a)
                   (when-let [team (teams/current-team config)]
                     (cons (str "byj:" (:session-id config))
                           (for [m (:members team) :when (and (:session-id m) (not (:closed? m)))]
                             (str "byj:" (:session-id m)))))
                   [a]))
               args)))

(defn others
  "The other shares this process started (`/share start REF`, `share$start`,
  the agent page), from byj-share's registry: a line each, or nil."
  [{:keys [config]}]
  (let [own (str "byj:" (:session-id config))
        mine (remove #(= own (:ref %)) (share/from-here))]
    (when (seq mine)
      (str/join "\n" (cons "also shared from here:"
                            (for [e mine]
                              (str "  " (:ref e) (when-let [l (not-empty (str (:label e)))] (str " (" l ")"))
                                   (when (:paused e) " — paused")
                                   "  " (get-in e [:links :share])
                                   (when (seq (:watching e)) (str "  watching: " (str/join ", " (:watching e)))))))))))

(declare await-ms)

(defn relay
  "The relay this session shares through (`share/relay-of`), `home` a test's byj-share directory."
  [config & [home]]
  (share/relay-of config home))

(defn- same-relay? [a b]
  (= (str/lower-case (str/replace (str a) #"/+$" "")) (str/lower-case (str/replace (str b) #"/+$" ""))))

(defn relays
  "Which relay this session shares through, and which byj-share is logged in
  to (byj-gly.362.6): a share refused for its login is a login to another
  relay as often as none, and the two side by side say which."
  [{:keys [config share-home]}]
  (let [mine (share/relay-said (relay config share-home))
        ;; `:share-home` is a test's byj-share directory
        {:keys [email] :as login} (share/login-state share-home)]
    (cond
      (nil? login) (str "this session shares through " mine "; byj-share is not logged in (share$login logs it in)")
      (same-relay? mine (:relay login)) (str "this session shares through " mine ", where byj-share is logged in as " email)
      :else (str "this session shares through " mine "; byj-share is logged in to " (:relay login) " as " email
                 " — another relay: if a share is refused, log in to " mine " (share$login)"))))

(defn login
  "Start byj-share's login to this session's relay and wait for its link and
  code (byj-gly.362.5): what to tell the person, who approves it in a
  browser; the login goes on in the background until they do, and the
  session says when it has."
  [{:keys [config task-registry] :as ctx} wait-ms]
  (let [r (share/login! ctx (relay config))]
    (if (:error r)
      r
      (let [lines #(get-in @task-registry [(:task-id r) :login-lines])
            ended? #(#{:completed :failed :cancelled} (get-in @task-registry [(:task-id r) :status]))
            got (await-ms wait-ms ::pending #(cond (some (fn [l] (re-find #"(?i)enter the code" l)) (lines)) :code
                                                   (ended?) :ended
                                                   :else ::pending))]
        (case got
          :code {:task-id (:task-id r) :relay (share/relay-said (relay config))
                 :approve (str/join "\n" (lines))
                 :next (str "Give the person the link and the code above, word for word, to approve in a browser where they are"
                            " signed in. Then task$wait on " (:task-id r) " (up to 300000 ms): it completes once they approve.")}
          :ended {:error (str "byj-share login ended: " (share/ended-why (take-last 2 (lines))))}
          {:error (str "byj-share login printed no code within " wait-ms " ms; task$detail " (:task-id r) " shows what it printed")})))))

(defn status
  "Where sharing stands: this session's share — who is watching, and where —
  and the other shares this process started. Not sharing, and the last
  sidecar ended on its own, it says what that one said on its way out
  (byj-gly.362.4)."
  [{:keys [share-box] :as ctx}]
  (let [presence (some-> share-box deref)
        running? (boolean (share/running-task ctx))]
    (str/join "\n" (remove nil? [(share/report presence running?)
                                 (when-not (or presence running?)
                                   (some->> (share/last-ended ctx) :why (str "the last share of this session ended: ")))
                                 (when-not presence (relays ctx))
                                 (others ctx)]))))

(defn- await-ms [ms pending f]
  ;; a command is a synchronous handler: the edge where a task is waited on
  (let [r (fx/run!! (fx/poll-until 200 pending f) ms)]
    (when (contains? r :ok) (:ok r))))

(declare confirm-agents)

(defn start
  "Share this session (no `:refs`) or the sessions named; what to say now.
  `on-note` is told each share's links once the relay has it open — the
  person's `/share` says them as they come. With `:wait-ms`, `start` waits
  that long for them itself and says them too: the agent has nothing else
  to read them from. `:no-input?`, `:chat?`, `:max-messages` and `:max-cost`
  are a terminal's or an agent's (RFC-043 models 20 and 21)."
  [{:keys [config st-memory share-box] :as ctx}
   {refs-asked :refs :keys [target record? wait-ms] :as opts} on-note]
  (let [target (or (not-empty (str target)) "link")
        refs (refs config refs-asked)
        refused (confirm-agents ctx refs)
        notes (atom [])
        on-note* (fn [s] (swap! notes conj s) (on-note s))
        waited (fn [said n]
                 (if-not wait-ms
                   said
                   (let [got (await-ms wait-ms ::pending #(if (>= (count @notes) n) @notes ::pending))]
                     (str/join "\n" (cons said (or got [(str "no link yet after " wait-ms " ms — share$status says when it opens")]))))))]
    (cond
      (share/target-problem target) (share/target-problem target)

      ;; an agent that does more than read is shared once the person says so
      ;; (byj-1wv.47)
      refused refused

      (empty? refs)
      (let [r (share/start! ctx {:target target :socket (channel/socket-path config (:session-id config))
                                 :title (or (:label config) (some-> st-memory deref :conversation-title))})]
        (cond
          (:error r) (:error r)
          (not wait-ms) (str "sharing this session (" target ") through " (share/relay-said (:relay r)) " — its URL follows when the relay answers")
          :else (let [ended? #(#{:completed :failed :cancelled} (get-in @(:task-registry ctx) [(:task-id r) :status]))
                      got (await-ms wait-ms ::pending #(cond (some-> share-box deref :url not-empty) :url
                                                              (ended?) :ended
                                                              :else ::pending))]
                  (case got
                    :url (str "sharing this session (" target ") through " (share/relay-said (:relay r)) "\n" (status ctx))
                    ;; the sidecar is gone: what it said, not a wait for a URL
                    ;; that cannot come (byj-gly.362.4)
                    :ended (str "not sharing: the sidecar ended at once — "
                                (or (some-> (share/last-ended ctx) :why) "it said nothing"))
                    (str "sharing this session (" target ") through " (share/relay-said (:relay r))
                         " — no URL yet after " wait-ms " ms; share$status says when it opens")))))

      ;; several, or a team: one group, one invite (RFC-043)
      (> (count refs) 1)
      (waited (share/start-group! config refs {:target target :record? record?
                                               :title (when-let [team (and (some #{"team"} refs-asked) (teams/current-team config))]
                                                        (str "team " (or (:name team) (:id team))))}
                                  on-note*)
              1)

      :else
      (let [sharer (share/process-sharer config)
            ref (first refs)]
        (waited ((:start! sharer) ref ref on-note* (select-keys opts [:target :record? :no-input? :chat? :max-messages :max-cost]))
                1)))))

(defn- confirm-agents
  "Ask the person before sharing an agent that does more than read
  (byj-1wv.47), through the session's own permission prompt, the question
  being what callers could make it do (`agent-reach/summary`). nil when every
  agent among `refs` may go; else why not — the person said no, or there is
  no one to ask (a headless session)."
  [{:keys [config permission-fn mcp-box]} refs]
  (let [reach (requiring-resolve 'brainyard.agent-reach/agent-reach)
        summary (requiring-resolve 'brainyard.agent-reach/summary)]
    (some (fn [ref]
            (when-let [[_ nm] (re-matches #"agent:(.+)" (str ref))]
              (let [r (reach config nm {:mcp-box mcp-box})]
                ;; reading its own files, or nothing, needs no asking; a shell,
                ;; held or not, reads any file on the machine (byj-1wv.115.39),
                ;; and another service, read only or not, is read with the
                ;; owner's connection (byj-1wv.115.42)
                (when (and r (not (and (#{:reads :none} (:level r)) (not (:reads-anywhere r)) (empty? (:services r)))))
                  (cond
                    (nil? permission-fn)
                    (str "not sharing agent:" nm ": it can do more than read, and no one is here to confirm it")
                    (not (:allowed (permission-fn {:display (summary r) :pattern (str "share agent:" nm)})))
                    (str "not sharing agent:" nm ": not confirmed"))))))
          refs)))

(defn- byj-share!
  "byj-share run with `args` to its end: what it said."
  [config args]
  (if-let [bin (share/resolve-binary config)]
    (let [r (try (apply fx/sh {:out :string :err :string} bin args) (catch Throwable e {:exit 1 :err (ex-message e)}))]
      (str/trim (str (:out r) (:err r))))
    "byj-share is not on PATH"))

(defn other!
  "`stop`, `pause` or `resume` one of the other shares, by reference: this
  process's sharer stops its own, and byj-share says the rest."
  [{:keys [config]} action ref]
  (let [sharer (share/process-sharer config)]
    (if (and (= "stop" action) ((:stop! sharer) ref))
      (str "stopped sharing " ref)
      (byj-share! config [action ref]))))

(defn record!
  "Turn a running share's recording on or off (byj-gly.362.7), as the owner
  console's checkbox does: this session's, said to its sidecar on the bus as
  `:session.share/control` `:record-on` or `:record-off`; another, by
  reference, through `byj-share record on|off REF`. On, the events since the
  share began are kept and the screen from now, everyone watching is told,
  and it is uploaded for review when the share ends; off, nothing more is
  kept, and what was is still uploaded then."
  [{:keys [config share-box] :as ctx} on? ref]
  (cond
    ref (byj-share! config ["record" (if on? "on" "off") ref])
    (not (or (some-> share-box deref) (share/running-task ctx))) (share/report nil false)
    :else (do (events/emit! ctx :session.share/control {:action (if on? :record-on :record-off)})
              (if on?
                "recording this share — everyone watching is told, and it is uploaded for review when the share ends"
                "recording stopped — what was kept is uploaded for review when the share ends"))))

(defn control!
  "`stop`, `pause`, `resume` or `kick` this session's share — said to the
  sidecar as `:session.share/control` on the session's bus, which it watches;
  `stop` ends its task too."
  [{:keys [share-box] :as ctx} action & [user]]
  (let [say! (fn [a & [extra]] (events/emit! ctx :session.share/control (merge {:action a} extra)))]
    (if-not (or (some-> share-box deref) (share/running-task ctx))
      (share/report nil false)
      (case action
        "stop" (do (say! :stop)
                   (share/stop! ctx)
                   (some-> share-box (reset! nil))
                   "sharing stopped — nobody sees this session any more")
        "pause" (do (say! :pause) "sharing paused — viewers see nothing new until /share resume")
        "resume" (do (say! :resume) "sharing resumed")
        "kick" (if (str/blank? (str user))
                 "usage: /share kick USER — /share lists who is watching"
                 (do (say! :kick {:user user}) (str "asked the sidecar to remove " user)))))))

(defn ls
  "`byj-share ls`: the sessions, terminals and agents that can be shared, in
  this project or (`all?`) on this machine."
  [{:keys [config]} all?]
  (if-let [bin (share/resolve-binary config)]
    (let [argv (cond-> [bin "ls" "--dir" (str (or (:project-dir config) (:working-dir config) "."))] all? (conj "--all"))
          r (try (apply fx/sh {:out :string :err :string} argv) (catch Throwable e {:exit 1 :err (ex-message e)}))]
      (str/trim (str (:out r) (:err r))))
    "byj-share is not on PATH — install it (`byj doctor` says how), or set :share-sidecar"))

;; --- the commands --------------------------------------------------------------

(def ^:private err [:map [:error :string]])

(defn- say
  "A line for the person, as `/share` says one: the session's own printer, so
  a share's links reach them when they come, after the call has returned."
  [ctx s]
  (when-let [f (:emit ctx)] (f :info s)))

(defn commands
  "`share$status`, `share$ls`, `share$start` and `share$control`: sharing as
  `/share` does it, for the session's own agent — never a subagent's, as a
  share is the session's (registered behind `:share-tools?`)."
  [_config]
  [{:name "share$status" :kind :command
    :description "Where sharing stands: whether this session is shared, its page, who is watching and in what role, and the other shares this session started."
    :input-schema [:map]
    :output-schema [:or [:map [:status :string]] err]
    :effects #{}
    :fn (fn [_ ctx] {:status (status ctx)})}
   {:name "share$ls" :kind :command
    :description (str "What can be shared, from byj-share: this project's byj, Claude Code and Codex sessions, tmux terminals and byj's agents,"
                      " each with the reference share$start takes (byj:ID, claude:ID, codex:ID, term:NAME, agent:NAME).")
    :input-schema [:map [:all {:optional true} [:boolean {:desc "Every session on this machine, not only this project's."}]]]
    :output-schema [:or [:map [:sessions :string]] err]
    :effects #{:process}
    :fn (fn [{:keys [all]} ctx] {:sessions (ls ctx all)})}
   {:name "share$start" :kind :command
    :description (str "Share this session, or the sessions named, with the people the person invites: they watch it live in a browser."
                      " With no :refs, this session. :refs names others — byj:ID, claude:ID, codex:ID (an id prefix will do),"
                      " term:NAME (a tmux session, made if there is none, typed into from the page), agent:NAME (one of byj's agents —"
                      " prefer a read-only one such as explore; one that does more than read is shared only once the person confirms it —"
                      " asked by invited callers), or team; several are shared as one group. Each ends when this session does."
                      " Returns the share's page and its coach and watch links once the relay has it open. Publishes what it shares:"
                      " never start a share the person did not ask for.")
    :input-schema [:map
                   [:refs {:optional true} [:vector {:desc "What to share; none: this session."} :string]]
                   [:target {:optional true} [:string {:desc "link (the default: invite links), or class:CODE to share into a class."}]]
                   [:record {:optional true} [:boolean {:desc "Keep it for review when it ends; everyone watching is told."}]]
                   [:no-input {:optional true} [:boolean {:desc "A terminal: view-only, nobody types from the page."}]]
                   [:chat {:optional true} [:boolean {:desc "A terminal: spectators may chat too (default true)."}]]
                   [:max-messages {:optional true} [:int {:desc "An agent: how many messages callers may send in all (default 200)."}]]
                   [:max-cost {:optional true} [:double {:desc "An agent: how much callers' messages may cost in USD (default 10)."}]]]
    :output-schema [:or [:map [:result :string]] err]
    :effects #{:share/publish :process}
    :fn (fn [{:keys [refs target record no-input chat max-messages max-cost]} ctx]
          {:result (start ctx {:refs refs :target target :record? record :no-input? no-input :chat? chat
                               :max-messages max-messages :max-cost max-cost :wait-ms 20000}
                          #(say ctx %))})}
   {:name "share$login" :kind :command
    :description (str "Log byj-share in to the relay this session shares through, when share$start says this device is not"
                      " logged in, or is logged in to another relay. Returns at once with a link and a code the person"
                      " approves in a browser, and the task that completes once they have; logging in replaces byj-share's"
                      " login for any other relay.")
    :input-schema [:map]
    :output-schema [:or [:map [:task-id :string] [:relay :string] [:approve :string] [:next :string]] err]
    :effects #{:process :net/http}
    :fn (fn [_ ctx] (login ctx 15000))}
   {:name "share$record" :kind :command
    :description (str "Turn a running share's recording on or off: this session's (no :ref) or another by reference (byj:ID, claude:ID,"
                      " term:NAME, agent:NAME …). On, it keeps the share's events from its start and its screen from now, tells"
                      " everyone watching, and uploads it for review when the share ends; off, it keeps nothing more. Only when the"
                      " person asked to record, or to stop recording.")
    :input-schema [:map
                   [:on [:boolean {:desc "true to record, false to stop recording."}]]
                   [:ref {:optional true} [:string {:desc "Another share; none: this session's."}]]]
    :output-schema [:or [:map [:result :string]] err]
    :effects #{:share/publish :process}
    :fn (fn [{:keys [on ref]} ctx] {:result (record! ctx (true? on) ref)})}
   {:name "share$control" :kind :command
    :description (str "Stop, pause or resume a share, or remove someone from this session's. With no :ref, this session's share;"
                      " with one, that share (byj:ID, claude:ID, term:NAME, agent:NAME …). :user is whom kick removes — share$status names them.")
    :input-schema [:map
                   [:action [:enum {:desc "What to do."} "stop" "pause" "resume" "kick"]]
                   [:ref {:optional true} [:string {:desc "Another share to act on; none: this session's."}]]
                   [:user {:optional true} [:string {:desc "For kick: who to remove."}]]]
    :output-schema [:or [:map [:result :string]] err]
    :effects #{:process}
    :fn (fn [{:keys [action ref user]} ctx]
          (cond
            (and ref (= "kick" action)) {:error "kick removes someone from this session's share; give no :ref"}
            ref {:result (other! ctx action ref)}
            :else {:result (control! ctx action user)}))}])
