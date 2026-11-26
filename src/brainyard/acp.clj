;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.acp
  "`byj acp`: byj as an Agent Client Protocol v1 agent on its own stdio
  (RFC-053, byj-g5n.4) — the front end an application's SDK and an ACP editor
  both drive. JSON-RPC 2.0, one object per line. A thin front end over
  `brainyard.sdk`: it turns requests into its calls and the session's events
  into `session/update`s, and nothing here decides what the agent does.

  The wire, as `dev/brainyard/dev/sdk_stdio_spike.clj` measured it: fd 0 read
  through libc `read(2)` on the blocking pool, `read` returning 0 the end of
  input — the client gone, so every session closes and the process exits. The
  protocol is written to a private copy of fd 1, and fd 1 itself is pointed at
  stderr (`dup2`), so a line anything else prints — a library, a child
  process, which inherits fd 1 — can never reach the protocol stream.

  brainyard has ACP as a client of other agents (`components/acp`); none of
  its code is an agent, so the server side here is the port's own (divergence
  `an-agent-sdk`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.changes :as changes]
            [brainyard.channel :as channel]
            [brainyard.config :as config]
            [brainyard.context :as context]
            [brainyard.delegates :as delegates]
            [brainyard.effect :as fx]
            [brainyard.session-history :as session-history]
            [brainyard.trajectory :as trajectory]
            [brainyard.hooks :as hooks]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.permission :as permission]
            [brainyard.persist :as persist]
            [brainyard.schema :as schema]
            [brainyard.sdk :as sdk]
            [brainyard.tools :as tools]
            [brainyard.version :as version]
            [jolt.ffi :as ffi]))

(def protocol-version
  "ACP's: v1 is stable; v2 is labelled draft (2026-10) and not spoken."
  1)

(def extensions
  "byj's extensions (RFC-053 'byj's extensions'), advertised at `initialize`
  and used only with a client that asked for them there."
  ["tools" "hooks" "events" "predict" "live" "turns" "share" "history"])

(def default-hook-timeout-ms
  "How long a client's hook is waited for before its entry's failure rule
  applies: the hook fails open unless it said `failClosed`."
  30000)

(def extensions-version
  "byj's extensions' own version (`_meta.brainyard.protocol`), bumped when an
  extension's shape changes; a key added is not a change (RFC-053)."
  1)

;; --- JSON-RPC over two line fns ----------------------------------------------------

(defn- rpc-error [code message & [data]]
  (ex-info message {::code code ::data data}))

(defn connection
  "A JSON-RPC connection writing each message with `send-line!` (a fn of one
  JSON string). Feed it the lines read with `receive!`. `handlers` maps a
  method to `(fn [conn params] result)` for requests — run off the reader, so
  one may block — and `notifications` to `(fn [conn params])`, run on it."
  [send-line! handlers notifications]
  {:send-line! send-line!
   :handlers handlers
   :notifications notifications
   :pending (atom {})                     ; our request id → promise of its answer
   :next-id (atom 0)
   :closed? (atom false)
   :in-flight (atom 0)                    ; requests of the client's still being answered
   :state (atom {})})

(defn send!
  "Write one message."
  [{:keys [send-line!]} msg]
  (send-line! (json/write-str (assoc msg :jsonrpc "2.0"))))

(defn notify! [conn method params] (send! conn {:method method :params params}))

(defn request!
  "Send a request to the client and return a promise of its answer:
  `{:result …}` or `{:error …}`; `{:closed true}` once the connection ends."
  [{:keys [pending next-id closed?] :as conn} method params]
  (let [id (str "byj-" (swap! next-id inc))
        p (promise)]
    (if @closed?
      (deliver p {:closed true})
      (do (swap! pending assoc id p)
          (send! conn {:id id :method method :params params})))
    p))

(defn- answer-request [conn id method params]
  (try
    (if-let [h (get (:handlers conn) method)]
      (send! conn {:id id :result (h conn params)})
      (throw (rpc-error -32601 (str "Method not found: " method))))
    (catch Throwable t
      (let [{::keys [code data]} (ex-data t)]
        (send! conn {:id id :error (cond-> {:code (or code -32603) :message (or (ex-message t) (str t))}
                                     data (assoc :data data))})))))

(defn receive!
  "Handle one line from the client."
  [conn line]
  (when-not (str/blank? line)
    (let [msg (try (json/read-str line) (catch Throwable _ ::unparseable))
          {:keys [id method params]} (when (map? msg) msg)]
      (cond
        (not (map? msg))
        (send! conn {:id nil :error {:code -32700 :message "Parse error"}})
        ;; an answer to one of ours
        (and (some? id) (nil? method))
        (when-let [p (get @(:pending conn) id)]
          (swap! (:pending conn) dissoc id)
          (deliver p (select-keys msg [:result :error])))
        (some? id)
        (do (swap! (:in-flight conn) inc)
            (fx/run-detached (fx/task-of #(try (answer-request conn id method params)
                                               (finally (swap! (:in-flight conn) dec))))
                             (str "acp " method)))
        :else
        (when-let [h (get (:notifications conn) method)]
          (try (h conn params) (catch Throwable _ nil)))))))

(defn close-connection!
  "The client is gone: every request of ours still waiting is answered
  `{:closed true}`."
  [{:keys [closed? pending]}]
  (reset! closed? true)
  (doseq [[_ p] (first (reset-vals! pending {}))] (deliver p {:closed true})))

;; --- sessions --------------------------------------------------------------------

(def modes
  "The permission modes (RFC-012) as ACP session modes: `permission/modes`."
  permission/modes)

(defn- ext? [conn] (boolean (:extensions? @(:state conn))))

(defn- update! [conn sid u] (notify! conn "session/update" {:sessionId sid :update u}))

(defn- text-block [t] {:type "text" :text (str t)})

(def ^:private registry-lookups
  "The tools that look things up in byj's own tool registry, with no effect
  of their own to name a kind by: ACP's `search` (byj-sff)."
  #{"list-tools" "get-tool-info"})

(defn tool-kind
  "ACP's kind of a tool call, from the tool's effects (RFC-007); a lookup in
  byj's tool registry, which has none, is a `search` (`tool-name`)."
  ([effects] (tool-kind effects nil))
  ([effects tool-name]
   (let [e (set effects)]
     (cond
       (:process e) "execute"
       (:fs/write e) "edit"
       (:fs/read e) "read"
       (some #(= "net" (namespace %)) (filter keyword? e)) "fetch"
       (contains? registry-lookups (str tool-name)) "search"
       :else "other"))))

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- call-title [tool-name args]
  (let [a (cond (map? args) (some (fn [k] (get args k)) [:command :path :pattern :query :url])
                :else nil)]
    (str tool-name (when a (str " " (clip a 80))))))

(defn- call-timing
  "`meta` with when a call began (`startedAt`, epoch ms) and how long it ran
  (`durationMs`), which ACP has no field for (byj-53r); a call recorded
  before calls were timed has neither, and a client shows no time."
  [meta started-at duration-ms]
  (cond-> meta
    started-at (assoc :startedAt started-at)
    duration-ms (assoc :durationMs duration-ms)))

(defn- checkpoint-meta
  "A turn's checkpoint (`session-history/checkpoint`) as `_meta` says it."
  [{:keys [ref before after files-changed]}]
  {:ref ref :before before :after after :filesChanged files-changed})

(defn- replay-call!
  "One recorded call `t` replayed as a completed or failed `tool_call` with
  id `id`, its `_meta.brainyard` `(brainyard-meta)`; then the calls of the
  subagents it ran (`:children`, session-history's), each marked as a live
  one is — `subagent {id name}`, `parentToolCallId` this call's id — and
  theirs under them (byj-cux)."
  [conn sid effects-of turn id {:keys [name args result error? children]} brainyard-meta]
  (update! conn sid {:sessionUpdate "tool_call" :toolCallId id
                     :title (call-title name args)
                     :kind (tool-kind (effects-of name) name)
                     :status (if error? "failed" "completed")
                     :rawInput (if (map? args) args {})
                     :content [{:type "content" :content (text-block (clip result 4000))}]
                     :_meta {:brainyard (brainyard-meta)}})
  (doseq [{:keys [agent steps]} children
          [k step] (map-indexed vector steps)
          [i t] (map-indexed vector (:tools step))]
    (replay-call! conn sid effects-of turn (str id "/" agent "-" k "-" i) t
                  #(cond-> {:turn turn
                            :subagent {:id agent :name (first (str/split (str agent) #"/" 2))}
                            :parentToolCallId id}
                     (:started-at t) (call-timing (:started-at t) (:duration-ms t))))))

(defn- recap-update
  "Turn `turn`'s recap as a client is told it (byj-s5h): an
  `agent_message_chunk` of the words the full-screen UI shows under the
  turn, marked `_meta.brainyard.notice` `recap`, with the recap itself in
  `_meta.brainyard.recap {recap nextStep}`."
  [turn {:keys [recap next-step]}]
  {:sessionUpdate "agent_message_chunk"
   :content (text-block (str "recap: " recap (when (seq next-step) (str " " next-step))))
   :_meta {:brainyard (cond-> {:notice "recap"
                               :recap (cond-> {:recap recap} (seq next-step) (assoc :nextStep next-step))}
                        turn (assoc :turn turn))}})

(declare json-safe)

(defn- note-update
  "A note for people (`:inject :as :note`, RFC-043) as a client is told it
  (byj-cm4.10): an `agent_message_chunk` marked `_meta.brainyard.notice`
  `note`, with who put it in (`from`), never part of an answer."
  [text from]
  {:sessionUpdate "agent_message_chunk" :content (text-block text)
   :_meta {:brainyard (cond-> {:notice "note"} (some? from) (assoc :from (json-safe from)))}})

(defn- notes-after
  "Session `sid`'s notes for people, by how many of its turn records came
  before each in its trajectory: what a replay puts it after (byj-cm4.10)."
  [config sid]
  (let [n (atom 0)]
    (reduce (fn [m rec] (if (trajectory/note? rec) (update m @n (fnil conj []) rec) (do (swap! n inc) m)))
            {} (try (trajectory/read-entries config sid) (catch Throwable _ [])))))

(defn- replay-turns!
  "A loaded session's history (`session-history/turns`) as `session/update`s, every
  turn from its first, each update naming its turn in `_meta.brainyard.turn`
  as `byj turns list` numbers it: the question as the user's message, each
  thought, each tool call with its result — one `tool_call`, already
  completed or failed, its id `turn-N-S-I`, with its `startedAt` and
  `durationMs` when the record kept them (byj-53r) — and the answer
  (byj-lie), then, to a client that asked for byj's extensions, its recap
  (byj-s5h). A turn byj keeps a checkpoint of says it on its first update,
  `_meta.brainyard.checkpoint {ref before after filesChanged}`: what a
  rewind of it goes back to (byj-d8c.1); one with none can be taken back
  to no more."
  [conn sid effects-of turns & [notes]]
  ;; a client that asked for byj's extensions is told the notes for people too,
  ;; each after the turns before it (byj-cm4.10)
  (let [notes (when (ext? conn) notes)
        say-notes! (fn [k] (doseq [r (get notes k)] (update! conn sid (note-update (:text r) (:from r)))))]
  (say-notes! 0)
  (doseq [[k {:keys [turn question answer steps checkpoint recap]}] (map-indexed vector turns)]
    (let [first? (atom true)
          ;; the turn's first update, whatever it is, carries its checkpoint
          meta #(cond-> %
                  (and checkpoint (compare-and-set! first? true false))
                  (assoc :checkpoint (checkpoint-meta checkpoint)))
          up! #(update! conn sid (assoc % :_meta {:brainyard (meta {:turn turn})}))]
      (when-not (str/blank? (str question))
        (up! {:sessionUpdate "user_message_chunk" :content (text-block question)}))
      (doseq [[k step] (map-indexed vector steps)]
        (when-let [t (not-empty (str (:thought step)))]
          (up! {:sessionUpdate "agent_thought_chunk" :content (text-block t)}))
        (doseq [[i t] (map-indexed vector (:tools step))]
          (replay-call! conn sid effects-of turn (str "turn-" turn "-" k "-" i) t
                        #(meta (call-timing {:turn turn} (:started-at t) (:duration-ms t))))))
      (when-not (str/blank? (str answer))
        (up! {:sessionUpdate "agent_message_chunk" :content (text-block answer)}))
      ;; and its recap, after its answer as it came live (byj-s5h)
      (when (and recap (ext? conn))
        (update! conn sid (recap-update turn recap)))
      (say-notes! (inc k))))))

(defn- in-turn
  "Update `u` naming the turn session `sid` is in, from the connection's
  state `st`, beside whatever `_meta.brainyard` it says already."
  [st sid u]
  (if-let [t (get-in st [:turn sid])] (assoc-in u [:_meta :brainyard :turn] t) u))

(defn- lm-json
  "An LM as a client is told it: `{provider model label}`, the model null
  when the provider's own default is asked, `label` as `/model` takes it."
  [lm]
  {:provider (some-> (:provider lm) name) :model (not-empty (str (:model lm))) :label (llm/lm-label lm)})

(defn- notice
  "What session `sid` said beside its answer, `kind` `:info` or `:error`, as
  an `agent_message_chunk` marked `_meta.brainyard.notice` with the kind, so
  a client can show it apart from the answer (byj-tc7)."
  [st sid kind text]
  (in-turn st sid {:sessionUpdate "agent_message_chunk" :content (text-block text)
                   :_meta {:brainyard {:notice (name kind)}}}))

(declare json-safe)

(defn- injected?
  "Is the turn session `sid` runs one the client sent no prompt for: one its
  session channel put in (byj-p2q), or one byj started on its own — a parked
  task's wakeup, a due scheduled prompt (byj-5rx)?"
  [st sid]
  (boolean (some-> (get-in st [:sessions sid]) :state deref :current ((some-fn :channel? :woken?)))))

(defn- approve-fn
  "A session's `:approve` (RFC-043 :approval :owner) as ACP's
  `session/request_permission`: a share's viewer asks to put in a turn, and the
  client allows it once or rejects it; unanswered in the time the channel
  gives, it has expired (byj-p2q)."
  [conn sid-box]
  (fn [{:keys [text who timeout-ms]}]
    (let [sid @sid-box
          p (request! conn "session/request_permission"
                      {:sessionId sid
                       :toolCall {:toolCallId (str "inject-" (System/currentTimeMillis))
                                  :title (str (or who "someone") " asks to run a turn: " (clip text 200))
                                  :kind "other" :status "pending"
                                  :rawInput {:text (str text) :from (str who)}
                                  :_meta {:brainyard {:injected true}}}
                       :options [{:optionId "allow-once" :name "Allow" :kind "allow_once"}
                                 {:optionId "reject-once" :name "Reject" :kind "reject_once"}]})
          answer (deref p (or timeout-ms 120000) ::expired)
          outcome (when (map? answer) (get-in answer [:result :outcome]))]
      (cond
        (= ::expired answer) :expired
        (:closed answer) nil
        (= "allow-once" (when (= "selected" (:outcome outcome)) (:optionId outcome))) :approved
        :else :rejected))))

(def ^:private child-ignored
  "A subagent's events that are not the session's to send: its turn's start
  and end (the session's turn goes on), and what it streams — its answer is
  its agent$ call's result, and its thoughts, streamed beside the session's,
  would read as the session's own in a client that shows one thought (byj-wmm)."
  #{:session.turn/start :session.turn/end :session/rewind :session/resumed :agent.llm/chunk})

(defn- as-child
  "Update `u`, one of subagent `data`'s calls, marked as its own:
  `_meta.brainyard.subagent` `{id name}`, and `parentToolCallId` the id of the
  call that started it, when it is known (byj-wmm)."
  [st sid data u]
  (let [parent (get-in st [:parents sid (:subagent-id data)])]
    (cond-> (assoc-in u [:_meta :brainyard :subagent] {:id (:subagent-id data) :name (:subagent data)})
      parent (assoc-in [:_meta :brainyard :parentToolCallId] parent))))

(defn- on-event
  "The session's events as `session/update`s: the answer field's chunks as
  the agent's message, the reasoning as its thoughts, a batch of tool calls
  as `tool_call`s and their results as `tool_call_update`s, each naming
  its turn in `_meta.brainyard.turn` as a replayed one does (byj-d8c.2). A
  subagent's calls are its own: their own ids, marked
  `_meta.brainyard.subagent` `{id name}` with the `parentToolCallId` of the
  agent$ call that started it, and its answer and thoughts are not streamed
  as the session's (byj-wmm). A
  loaded session's history is replayed from its first turn
  (`replay-turns!`), or, where it left nothing to read it from, from its
  snapshot's transcript."
  [conn sid-box effects-of replay? project-dir]
  (fn [event data]
    (when-let [sid @sid-box]
      (let [st (:state conn)
            ;; a subagent's event (RFC-018, its :subagent-id): its calls are
            ;; kept apart from the session's and marked as its own (byj-wmm)
            who (when (map? data) (:subagent-id data))
            live! #(update! conn sid (in-turn @st sid (cond->> % who (as-child @st sid data))))]
        (case (if (and who (child-ignored event)) ::ignored event)
          ::ignored nil
          :agent.subagent/dispatch
          ;; the caller's call that started it: the first of its pending
          ;; agent$<name> calls no child has claimed yet
          (let [want (str "agent$" (:agent data))
                claimed (set (vals (get-in @st [:parents sid])))
                id (some (fn [[id t]] (when (and (= t want) (not (claimed id))) id)) (get-in @st [:calls sid who]))]
            (when id (swap! st assoc-in [:parents sid (:id data)] id)))
          :session.turn/start
          (do (swap! st assoc-in [:turn sid] (:turn data))
              ;; a turn the session channel put in (another session, a script,
              ;; a share's viewer): the client sent no prompt for it, so it is
              ;; told the question, who put it in, and when it ends (byj-p2q)
              (when (injected? @st sid)
                (swap! st assoc-in [:streamed sid] false)
                (live! (cond-> {:sessionUpdate "user_message_chunk" :content (text-block (:question data))}
                         (:injected-by data) (assoc-in [:_meta :brainyard :injectedBy] (json-safe (:injected-by data)))))
                (when (ext? conn)
                  (notify! conn "_byj/turn" (cond-> {:sessionId sid :turn (:turn data) :state "started"}
                                              (:injected-by data) (assoc :injectedBy (json-safe (:injected-by data))))))))
          :session.share/note
          ;; a note for people (RFC-043): told when it comes, a turn running
          ;; or not, marked a note with who put it in (byj-cm4.10)
          (when (and (not who) (ext? conn) (seq (str (:text data))))
            (update! conn sid (note-update (:text data) (:from data))))
          :session.turn/recap
          ;; after the prompt was answered (byj-2pr), so told on its own,
          ;; naming its turn (byj-s5h)
          (when (and (not who) (ext? conn))
            (update! conn sid (recap-update (:turn data) data)))
          :session/suggestion
          ;; what a command run between turns (/init) thinks is asked next:
          ;; its prompt's answer says it (byj-s5h)
          (when-not who
            (swap! st assoc-in [:suggestion sid] (not-empty (str/trim (str (:next-user-prompt data))))))
          :session/rewind
          ;; what a _byj/turns method answers with (byj-d8c.3)
          (swap! st assoc-in [:rewound sid] data)
          :session.turn/end
          ;; what session/prompt answers with: the turn, and what it changed
          ;; and no turn is running: what comes next is no turn's
          (let [turn (get-in @st [:turn sid])]
            (swap! st #(-> % (assoc-in [:ended sid] {:turn (get-in % [:turn sid]) :files-changed (:files-changed data)})
                           (update :turn dissoc sid)))
            ;; its answer, when it was not streamed as it was written: no
            ;; session/prompt answers this turn to carry it
            (when (and (injected? @st sid) (seq (str (:answer data))) (not (get-in @st [:streamed sid])))
              (update! conn sid (cond-> {:sessionUpdate "agent_message_chunk" :content (text-block (:answer data))}
                                  turn (assoc-in [:_meta :brainyard :turn] turn))))
            (when (and (injected? @st sid) (ext? conn))
              (notify! conn "_byj/turn" (cond-> {:sessionId sid :turn turn :state "ended"
                                                 :terminatedBy (some-> (:terminated-by data) name)}
                                          (some? (:files-changed data)) (assoc :filesChanged (:files-changed data))))))
          :agent.llm/chunk
          (case (some-> (:field data) name)
            "answer" (do (swap! st assoc-in [:streamed sid] true)
                         (live! {:sessionUpdate "agent_message_chunk" :content (text-block (:text data))}))
            "reasoning" (live! {:sessionUpdate "agent_thought_chunk" :content (text-block (:text data))})
            nil)
          :agent.tools/calls
          (let [n (get-in (swap! st update-in [:batches sid] (fnil inc 0)) [:batches sid])
                prefix (if who (str "call-" who "-") "call-")
                ids (vec (for [[i {:keys [tool-name tool-args]}] (map-indexed vector (:tool-calls data))]
                           (let [id (str prefix n "-" i)]
                             (live! {:sessionUpdate "tool_call" :toolCallId id
                                                :title (call-title tool-name tool-args)
                                                :kind (tool-kind (effects-of tool-name) tool-name)
                                                :status "pending"
                                                :rawInput (or tool-args {})})
                             [id tool-name])))]
            (swap! st assoc-in [:calls sid who] ids))
          :agent.tools/results
          (let [ids (get-in @st [:calls sid who])]
            (doseq [[[id _] r] (map vector ids (:results data))]
              (live! (cond-> {:sessionUpdate "tool_call_update" :toolCallId id
                                         :status (if (:tool-error? r) "failed" "completed")
                                         :content [{:type "content"
                                                    :content (text-block (clip (:tool-result r) 4000))}]}
                                  ;; byj's own time for the call, so a client
                                  ;; need not keep one (byj-53r)
                                  (:started-at r) (assoc :_meta {:brainyard (call-timing {} (:started-at r) (:elapsed-ms r))})))))
          :session/resumed
          (when replay?
            (let [turns (try (session-history/turns {:project-dir project-dir :working-dir project-dir} (or (:id data) sid))
                             (catch Throwable _ nil))]
              (if (seq turns)
                (replay-turns! conn sid effects-of turns
                               (notes-after {:project-dir project-dir :working-dir project-dir} (or (:id data) sid)))
                (doseq [[kind x] (:transcript data)]
                  (case kind
                    :you (update! conn sid {:sessionUpdate "user_message_chunk" :content (text-block x)})
                    :answer (update! conn sid {:sessionUpdate "agent_message_chunk" :content (text-block x)})
                    :thought (update! conn sid {:sessionUpdate "agent_thought_chunk" :content (text-block x)})
                    nil)))))
          nil)))))

(defn- pending-call-id
  "The id of the newest pending call of `tool`, to name in a permission
  request; a fresh one when the gate asks before any call was announced."
  [conn sid tool]
  (or (some (fn [[id t]] (when (= t tool) id)) (reverse (apply concat (vals (get-in @(:state conn) [:calls sid])))))
      (str "ask-" (System/currentTimeMillis))))

(defn permission-fn
  "A session's `:permission-fn` (RFC-012) as ACP's `session/request_permission`:
  the client chooses allow once, allow always, reject once or reject always;
  an always is remembered for the session by the pattern that matched, as the
  terminal remembers one. No timeout, as the terminal has none (divergence
  `permission-prompt-cli`): the client closing its end answers it. A
  request asked every time (`:every-time?`, a profile write) offers allow
  once and reject alone, and nothing is remembered of it (byj-8b3). What the
  person is asked about — the request's `:display`, a drafted profile and
  what it lacks for init$profile — goes as the tool call's content, as the
  terminal shows it, so a client's yes is never given to a bare title
  (byj-ci6)."
  [conn sid-box effects-of]
  (let [remembered (atom {})]
    (fn [{:keys [tool display pattern args type action path every-time?] :as req}]
      (let [key (if (= :file-access type) (str (some-> action name) " " path) (or pattern display))]
        (or (when-not every-time? (get @remembered key))
            (let [sid @sid-box
                  title (if (= :file-access type)
                          (str (some-> action name) " " path)
                          (call-title (or tool display) args))
                  answer @(request! conn "session/request_permission"
                                    {:sessionId sid
                                     :toolCall {:toolCallId (pending-call-id conn sid tool)
                                                :title title
                                                :content (if (and (not (str/blank? (str display))) (not= (str/trim (str display)) title))
                                                           [{:type "content" :content {:type "text" :text (str display)}}]
                                                           [])
                                                :kind (if (= :file-access type)
                                                        (if (= :write action) "edit" "read")
                                                        (tool-kind (effects-of tool) tool))
                                                :status "pending"
                                                :rawInput (or args {})}
                                     :options (if every-time?
                                                [{:optionId "allow-once" :name "Allow once" :kind "allow_once"}
                                                 {:optionId "reject-once" :name "Reject" :kind "reject_once"}]
                                                [{:optionId "allow-once" :name "Allow once" :kind "allow_once"}
                                                 {:optionId "allow-always" :name "Always allow" :kind "allow_always"}
                                                 {:optionId "reject-once" :name "Reject" :kind "reject_once"}
                                                 {:optionId "reject-always" :name "Always reject" :kind "reject_always"}])})
                  outcome (get-in answer [:result :outcome])
                  chosen (when (= "selected" (:outcome outcome)) (:optionId outcome))
                  allow {:allowed true}
                  deny {:denied true :reason "denied by the client"}]
              (case (if (and every-time? (not= "allow-once" chosen) (some? chosen)) "reject-once" chosen)
                "allow-once" allow
                "allow-always" (do (swap! remembered assoc key allow) allow)
                "reject-always" (do (swap! remembered assoc key deny) deny)
                "reject-once" deny
                {:denied true :reason (if (:closed answer) "the client is gone" "the request was cancelled")})))))))

(defn- mcp-servers
  "ACP's stdio MCP servers as byj's `:mcp-servers` (RFC-024)."
  [servers]
  (into {} (for [{:keys [name command args env]} servers
                 :when (and name command)]
             [(keyword name) {:command command :args (vec args)
                              :env (into {} (for [{n :name v :value} env] [n v]))}])))

(defn- session-of [conn sid]
  (or (get-in @(:state conn) [:sessions sid])
      (throw (rpc-error -32602 (str "no session " sid)))))

;; --- a session's turns, as data (RFC-069 over ACP, byj-d8c.3) ---------------------

(defn- row-json [{:keys [turn ref before after files subject at]}]
  {:turn turn :ref ref :before before :after after :files files :question subject :endedAt at})

(defn- session-history
  "`_byj/session/history {sessionId cwd}` (byj-cm4.9): a saved session's
  finished turns, read only — sent as `session/update`s, as `session/load`
  replays them (`replay-turns!`) — without opening it: no lock taken or
  minded, nothing written, no channel, no prompt. So a client can show a
  session another process holds, as a workflow stage's headless session is
  held by its own byj (RFC-076): what it has done so far, its running turn not
  yet in the record (`byj session watch` follows that). The files are read as
  any reader may: each written whole and renamed, the trajectory appended a
  line at a time, a torn last line skipped. Answers `{turnCount heldBy?}`."
  [conn {:keys [sessionId cwd]}]
  (when-not (and (string? sessionId) (re-matches #"[A-Za-z0-9-]+" sessionId))
    (throw (rpc-error -32602 "sessionId is required")))
  (let [cwd (if (str/blank? (str cwd)) (System/getProperty "user.dir") (str cwd))
        config {:project-dir cwd :working-dir cwd}
        snap (persist/read-session config sessionId)]
    (when (:problem snap) (throw (rpc-error -32602 (:problem snap))))
    (let [turns (try (session-history/turns config sessionId) (catch Throwable _ []))
          tool-effects (into {} (for [t tools/builtin-tools] [(:name t) (:effects t)]))
          held (persist/holder config sessionId)]
      (replay-turns! conn sessionId #(get tool-effects % #{}) turns (notes-after config sessionId))
      (cond-> {:turnCount (count turns)} held (assoc :heldBy (str held))))))

(defn- turns-list
  "`_byj/turns/list {sessionId}`: the turns byj keeps of an open session,
  ascending, each as `byj turns list` shows it — what a rewind can go back
  to."
  [conn {:keys [sessionId]}]
  (let [s (session-of conn sessionId)]
    {:turns (mapv row-json (changes/rows (:config s) sessionId))}))

(defn- turns-diff
  "`_byj/turns/diff {sessionId turn?}`: git's diff of a turn, the last that
  changed files when none is named."
  [conn {:keys [sessionId turn]}]
  (let [s (session-of conn sessionId)
        {:keys [error] :as r} (changes/diff (:config s) sessionId turn)]
    (when error (throw (rpc-error -32602 error)))
    (select-keys r [:turn :ref :before :after :files :diff])))

(defn- run-turns-command!
  "Run `cmd` (`/rewind 3`) in open session `sid` as its own command runs —
  between its turns, refused while background work writes, the next turn
  told and :session/rewind on its bus — and answer with what it did, as
  data: `{kind turn end files conflicts saved}`. Refused while the session's
  turn runs, rather than run after it: a rewind decided before the turn
  ended would undo what it did."
  [conn sid cmd]
  (let [s (session-of conn sid)]
    (when (sdk/running? s)
      (throw (rpc-error -32602 (str "session " sid " is in the middle of a turn: its files may be being written — try again when it ends"))))
    (swap! (:state conn) update :rewound dissoc sid)
    (let [r (deref (sdk/command! s cmd))
          done (get-in @(:state conn) [:rewound sid])
          said (str/join "\n" (keep (fn [[_ t]] (not-empty (str t))) (:said r)))]
      (cond
        (:refused r) (throw (rpc-error -32602 (:refused r)))
        (:closed r) (throw (rpc-error -32602 (str "session " sid " closed")))
        done (let [{:keys [kind turn end? files conflicts saved]} done]
               ;; a conflict that kept the turn's change from being taken back
               ;; at all is an answer, not an error: what it is, and why
               ;; nothing was written, in the command's words (byj-v55)
               (cond-> {:kind (name kind) :files (vec files) :conflicts (vec conflicts) :saved saved}
                 turn (assoc :turn turn)
                 (some? end?) (assoc :end end?)
                 (empty? files) (assoc :message said)))
        ;; nothing changed on disk: the files were already so — or it was
        ;; refused, in the words the command said it in
        (some (fn [[k _]] (= :error k)) (:said r)) (throw (rpc-error -32602 said))
        :else {:files [] :conflicts [] :message said}))))

(defn json-safe
  "`x` as data JSON can carry: a keyword as its name with its namespace
  (`agent.tool-use/pre`), a set or seq as an array, a map's keys as strings,
  anything else — a fn, a record, a file — as its string."
  [x]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x)) x
    (keyword? x) (subs (str x) 1)
    (map? x) (into {} (for [[k v] x] [(if (keyword? k) (subs (str k) 1) (str k)) (json-safe v)]))
    (coll? x) (mapv json-safe x)
    :else (str x)))

(defn- client-tool
  "A tool the client declared (`_meta.brainyard.tools`), as a tool map
  (RFC-007) whose call is a `_byj/tool/call` request to the client: its
  answer's `content` is the result, an error the tool's failure. Its
  arguments are described by its JSON Schema, read as malli
  (`schema/from-json-schema`); its effects are what it declares, `sdk/call`
  when it declares none, so the gate and `--deny-effects` see it."
  [conn sid-box {:keys [name description inputSchema effects]}]
  (when (str/blank? (str name)) (throw (rpc-error -32602 "a client tool needs a name")))
  (let [input (try (schema/from-json-schema (or inputSchema {:type "object"}))
                   (catch clojure.lang.ExceptionInfo e
                     (throw (rpc-error -32602 (str "tool " name ": " (ex-message e))))))]
    {:name name
     :description (str description)
     :input-schema input
     :effects (if (seq effects) (set (map #(keyword (str/replace (str %) #"^:" "")) effects)) #{:sdk/call})
     :fn (fn [args _ctx]
           (let [sid @sid-box
                 answer @(request! conn "_byj/tool/call"
                                   {:sessionId sid
                                    :toolCallId (pending-call-id conn sid name)
                                    :tool name
                                    :args (json-safe args)})]
             (cond
               (:closed answer) {:error "the client is gone"}
               (:error answer) {:error (str (get-in answer [:error :message] "the client's tool failed"))}
               (get-in answer [:result :isError]) {:error (str (get-in answer [:result :content]))}
               :else (get-in answer [:result :content]))))}))

(defn- client-hook
  "A hook the client declared (`_meta.brainyard.hooks`), as an entry on the
  hook bus (RFC-011) whose handler is a `_byj/hook` request: the answer's
  `decision` — `block` with a `reason`, `replace` with a `replacement`,
  `modifyArgs` with `args`, or none — is the entry's. Waited for at most
  `timeoutMs` (`default-hook-timeout-ms`); past it, or with the client gone,
  it allows, unless it said `failClosed`. `match.tool` is a glob on the
  payload's `:tool-name`."
  [conn sid-box {:keys [id event match timeoutMs failClosed]}]
  (let [ev (keyword (str/replace (str event) #"^:" ""))]
    (when-not (contains? hooks/event-catalog ev)
      (throw (rpc-error -32602 (str "no hook event " event " — one of "
                                    (str/join ", " (sort (map #(subs (str %) 1) (keys hooks/event-catalog))))))))
    {:id (keyword "sdk" (str (or id event)))
     :event ev
     :source :sdk
     :priority 0
     :match (when-let [t (:tool match)] (fn [p] (permission/glob-match? t (:tool-name p))))
     :handler (fn [payload _ctx]
                (let [p (request! conn "_byj/hook" {:sessionId @sid-box :id (str (or id event))
                                                    :event (subs (str ev) 1) :payload (json-safe payload)})
                      answer (deref p (or timeoutMs default-hook-timeout-ms) ::timeout)
                      {:keys [decision reason replacement args]} (:result answer)]
                  (cond
                    (or (= ::timeout answer) (:closed answer) (:error answer))
                    (when failClosed
                      {:result :block :reason (str "the client's hook " (or id event)
                                                   (if (= ::timeout answer) " did not answer in time" " could not be asked"))})
                    (= "block" decision) {:result :block :reason (str (or reason "blocked by the client's hook"))}
                    (= "replace" decision) {:result :replace :replacement replacement}
                    (= "modifyArgs" decision) {:result :modify-args :args args}
                    :else nil)))}))

(def client-fs-timeout-ms
  "How long a read or write through the client's editor may take."
  30000)

(defn client-fs
  "The file tools' fs (`tools/disk-fs`'s shape) for a client that advertised
  `clientCapabilities.fs` (RFC-053, RFC-067 §8, byj-gly.464): a file under the
  session's `cwd` is read with `fs/read_text_file` when it said
  `readTextFile`, and written with `fs/write_text_file` when it said
  `writeTextFile` — an editor answers from its buffer, unsaved edits
  included, and writes into it. Any other file, and a read the client cannot
  answer (no such file, or gone), is the disk's; a write it refuses is that
  error, never written behind it."
  [conn sid-box cwd {:keys [readTextFile writeTextFile]}]
  (let [disk tools/disk-fs
        inside? (fn [^java.io.File f] (permission/under? cwd (.getPath f)))
        root (.getCanonicalPath (io/file cwd))
        ;; the path in the client's own words, under the cwd as it gave it: an
        ;; editor finds its buffer by the name it knows, and byj's may be the
        ;; canonical one (/private/var/… where the client said /var/…)
        client-path (fn [^java.io.File f]
                      (let [c (.getCanonicalPath f)]
                        (if (str/starts-with? c (str root "/"))
                          (str (str/replace (str cwd) #"/+$" "") (subs c (count root)))
                          (.getAbsolutePath f))))
        ask (fn [method params]
              (deref (request! conn method (assoc params :sessionId @sid-box)) client-fs-timeout-ms ::timeout))]
    {:read (fn [^java.io.File f]
             (or (when (and readTextFile (inside? f))
                   (let [a (ask "fs/read_text_file" {:path (client-path f)})]
                     (when (and (map? a) (contains? a :result) (string? (get-in a [:result :content])))
                       (get-in a [:result :content]))))
                 ((:read disk) f)))
     :write (fn [^java.io.File f text]
              (if (and writeTextFile (inside? f))
                (let [a (ask "fs/write_text_file" {:path (client-path f) :content text})]
                  (cond (= ::timeout a) "the editor did not answer the write in time; nothing was written"
                        (:closed a) "the client is gone; nothing was written"
                        (:error a) (str "the editor refused the write: " (get-in a [:error :message] "no reason given"))
                        :else nil))
                ((:write disk) f text)))}))

(defn- event-filter
  "Which of a session's events go to the client as `_byj/event`: `all`, a
  list of names, or none."
  [events]
  (cond
    (= "all" events) (constantly true)
    (seq events) (let [want (set (map #(keyword (str/replace (str %) #"^:" "")) events))] #(contains? want %))
    :else (constantly false)))

(defn- open!
  "Open a session for `session/new`, or a saved one (`resume`) for
  `session/load` and `session/resume` — the last without its history
  replayed (`replay?` false)."
  [conn {:keys [cwd mcpServers] :as params} {:keys [resume replay?] :or {replay? true}}]
  (when (str/blank? (str cwd)) (throw (rpc-error -32602 "cwd is required")))
  (let [o (get-in params [:_meta :brainyard])
        _ (when-let [k (:forkBeforeTurn o)]
            (when-not (and (:forkFrom o) (pos-int? k))
              (throw (rpc-error -32602 "forkBeforeTurn is a turn of the session forkFrom names")))
            ;; its files put back under a turn running in this process: no
            ;; (another process's is refused by changes/rewind!)
            (when-let [src (get-in @(:state conn) [:sessions (:forkFrom o)])]
              (when (and (not (:keepFiles o)) (not (:worktree o)) (sdk/running? src))
                (throw (rpc-error -32602 (str "session " (:forkFrom o) " is in the middle of a turn: its files may be being written — fork when it ends, or with keepFiles"))))))
        defaults (:defaults @(:state conn))
        ;; the id chosen here — or by the client, in context/session-id's shape,
        ;; so a fork's history replayed before session/new answers reaches it —
        ;; so a hook firing as the session starts names it
        chosen (:sessionId o)
        _ (when (and chosen (not (re-matches #"\d{13}-[0-9a-f]{8}" (str chosen))))
            (throw (rpc-error -32602 (str "a session id is <millis>-<8 hex>, not " chosen))))
        sid-box (atom (or resume chosen (context/session-id)))
        ;; a worktree of its own — a fork's (byj-8yt), or a new session's on
        ;; a checkout (byj-z0z): made first, since the session works there —
        ;; its files, its tools' fs, its history
        _ (when-let [w (:worktree o)]
            (cond
              resume (throw (rpc-error -32602 "worktree is for session/new: a saved session is loaded where it was made"))
              (not (or (true? w) (map? w))) (throw (rpc-error -32602 "worktree is true or {branch? path? from?}"))))
        wt (when-let [w (:worktree o)]
             (let [w (when (map? w) (select-keys w [:branch :path :from]))
                   opts {:project-dir cwd :worktree w :session-id @sid-box}]
               (try (if (:forkFrom o)
                      (sdk/fork-worktree! (assoc opts :fork-from (:forkFrom o) :fork-before-turn (:forkBeforeTurn o)
                                                 :keep-files? (boolean (:keepFiles o))))
                      (sdk/new-worktree! opts))
                    (catch clojure.lang.ExceptionInfo e (throw (rpc-error -32602 (ex-message e)))))))
        cwd (if wt (:project-dir wt) cwd)
        ;; the client's editor as the file tools' fs, when it offers one
        fs-cap (get-in @(:state conn) [:client :clientCapabilities :fs])
        fs-tools (when (or (:readTextFile fs-cap) (:writeTextFile fs-cap))
                   (tools/file-tools (client-fs conn sid-box cwd fs-cap)))
        client-tools (into (mapv #(client-tool conn sid-box %) (:tools o)) fs-tools)
        client-hooks (mapv #(client-hook conn sid-box %) (:hooks o))
        wanted? (event-filter (:events o))
        tool-effects (into {} (for [t (concat tools/builtin-tools client-tools)] [(:name t) (:effects t)]))
        effects-of #(get tool-effects % #{})
        translate (on-event conn sid-box effects-of replay? cwd)
        s (try
            (sdk/open-session
             (cond-> {:project-dir cwd
                      :provider (or (:provider o) (:provider defaults))
                      :model (or (:model o) (:model defaults))
                      :agent (or (:agent o) (:agent defaults))
                      :loop (:loop o)
                      :config (config/from-json (:config o))
                      :label (:label o)
                      :fork-from (:forkFrom o)
                      :fork-before-turn (:forkBeforeTurn o)
                      :keep-files? (boolean (:keepFiles o))
                      :worktree wt
                      :commands? (boolean (:commands o))
                      :mcp-servers (mcp-servers mcpServers)
                      :permission-mode (some-> (or (:permissionMode o) (:permission-mode defaults)) keyword)
                      :permission-fn (permission-fn conn sid-box effects-of)
                      ;; the session channel (RFC-031), when the client asks
                      ;; for it: a share's sidecar, another session or a
                      ;; script puts turns in, which the client is told of
                      ;; (byj-p2q)
                      :channel? (boolean (:channel o))
                      :approve (approve-fn conn sid-box)
                      :tools client-tools
                      :hooks client-hooks
                      :session-id @sid-box
                      :on-event (fn [e d]
                                  (translate e d)
                                  (when (and (ext? conn) (wanted? e))
                                    (notify! conn "_byj/event" {:sessionId @sid-box :event (subs (str e) 1)
                                                                :payload (json-safe d)})))
                      :on-problem (fn [msg] (binding [*out* *err*] (println msg)))
                      ;; what the session says while it answers a prompt — a
                      ;; turn's start waiting for an MCP server, the servers
                      ;; changed, a command's words — as it says it, marked as
                      ;; a notice, not the answer (byj-tc7): a client could
                      ;; not tell a session that had its MCP tools from one
                      ;; that did not (GitHub #1). The answer is sent as it is
                      ;; streamed; what a _byj/turns method runs, and a resume's
                      ;; notes, are not a prompt's
                      :on-said (fn [kind text]
                                 (let [sid @sid-box st @(:state conn)]
                                   (when (and sid (#{:info :error} kind) (seq (str text))
                                              (get-in st [:prompting sid]))
                                     (update! conn sid (notice st sid kind text)))))}
               resume (assoc :resume resume)))
            (catch clojure.lang.ExceptionInfo e
              (throw (rpc-error -32602 (ex-message e)))))
        sid (:id s)]
    (swap! (:state conn) assoc-in [:sessions sid] s)
    ;; up and waiting — a resumed one has replayed its history as updates
    (when-not (deref (:ready s) 60000 false)
      (swap! (:state conn) update :sessions dissoc sid)
      (throw (rpc-error -32603 (str "session " sid " did not start"))))
    (cond-> {:sessionId sid
             :modes {:currentModeId (name (or (some-> (:permission-mode-box s) deref) :auto))
                     :availableModes modes}
             ;; the provider and model it runs on, as opening settled them —
             ;; its own saved model, the one asked for, byj acp's -p/-m, or
             ;; :default-lm — read once it is up, a resume having chosen
             ;; (byj-66y)
             :_meta {:brainyard (cond-> {:lm (lm-json (sdk/current-lm s))}
                                  ;; what the model thought would be asked next,
                                  ;; which a resume offers again (byj-s5h)
                                  resume (as-> m (if-let [p (some-> (persist/read-session (:config s) sid) :next-user-prompt str/trim not-empty)]
                                                   (assoc m :nextUserPrompt p)
                                                   m)))}}
      ;; the worktree it was opened in (byj-z0z): `cwd` is where it is
      ;; loaded and listed from
      (:worktree s) (assoc-in [:_meta :brainyard :worktree]
                              (let [{:keys [dir project-dir branch base created files]} (:worktree s)]
                                (cond-> {:path dir :cwd project-dir :branch branch :base base}
                                  (some? created) (assoc :created created)
                                  files (assoc :files files))))
      ;; what a fork did: where from, before which turn, the files it put
      ;; back in the session forked from (byj-d8c.3), or the worktree it
      ;; was made in (byj-8yt): `cwd` is where it is loaded and listed from
      (:fork s) (assoc-in [:_meta :brainyard :fork]
                          (let [{:keys [from before-turn files saved error worktree]} (:fork s)]
                            (cond-> {:from from}
                              before-turn (assoc :beforeTurn before-turn)
                              files (assoc :files files)
                              saved (assoc :saved saved)
                              error (assoc :error error)
                              worktree (assoc :worktree {:path (:dir worktree) :cwd (:project-dir worktree)
                                                         :branch (:branch worktree) :base (:base worktree)
                                                         :files (:files worktree)})))))))

(defn- prompt-text
  "A prompt's content blocks as the line a session takes: text as it is, a
  resource's text after its URI."
  [blocks]
  (str/join "\n\n"
            (for [{:keys [type text resource uri]} blocks
                  :let [t (case type
                            "text" text
                            "resource" (str (:uri resource) "\n" (:text resource))
                            "resource_link" (str uri)
                            nil)]
                  :when (seq t)]
              t)))

(defn- stop-reason [{:keys [terminated-by]}]
  (case terminated-by
    :cancelled "cancelled"
    :max-iterations "max_turn_requests"
    "end_turn"))

(defn- prompt! [conn {:keys [sessionId prompt] :as params}]
  (let [s (session-of conn sessionId)
        ;; an answer wanted as data (byj-g5n.10): its schema read before the
        ;; turn runs, so one this cannot read costs nothing
        out-schema (get-in params [:_meta :brainyard :outputSchema])
        _ (when out-schema
            (try (#'sdk/schema-fields out-schema)
                 (catch clojure.lang.ExceptionInfo e (throw (rpc-error -32602 (str "outputSchema: " (ex-message e)))))))
        text (prompt-text prompt)
        _ (swap! (:state conn) #(-> % (assoc-in [:streamed sessionId] false) (update :ended dissoc sessionId)
                                    (update :suggestion dissoc sessionId)
                                    (assoc-in [:prompting sessionId] true)))
        r (try (deref (sdk/submit! s text))
               (finally (swap! (:state conn) update :prompting dissoc sessionId)))
        ;; the turn this ran, and what it changed (byj-d8c.2)
        {:keys [turn files-changed]} (get-in @(:state conn) [:ended sessionId])
        in-it #(cond-> % turn (assoc-in [:_meta :brainyard :turn] turn))]
    (cond
      (:refused r) (throw (rpc-error -32602 (:refused r)))
      (:closed r) {:stopReason "cancelled"}
      (:no-turn r)
      ;; its notices went out as it said them; anything else it said, now
      (do (doseq [[k t] (:said r) :when (and (not (#{:info :error} k)) (seq (str t)))]
            (update! conn sessionId {:sessionUpdate "agent_message_chunk" :content (text-block t)}))
          ;; the model it runs on now: a command (/model NAME) may have
          ;; switched it (byj-11o)
          (cond-> {:stopReason "end_turn"}
            (ext? conn) (assoc :_meta {:brainyard (cond-> {:lm (lm-json (sdk/current-lm s))}
                                                    ;; what a command thinks is asked next (/init's)
                                                    (get-in @(:state conn) [:suggestion sessionId])
                                                    (assoc :nextUserPrompt (get-in @(:state conn) [:suggestion sessionId])))})))
      :else
      (let [{:keys [answer terminated-by usage] :as result} (:result r)]
        (when (= :loop-error terminated-by)
          (throw (rpc-error -32603 "the turn failed" {:detail (str answer)})))
        ;; the answer, when it was not streamed as it was written
        (when (and (seq (str answer)) (not (get-in @(:state conn) [:streamed sessionId])))
          (update! conn sessionId (in-it {:sessionUpdate "agent_message_chunk" :content (text-block answer)})))
        (let [structured (when (and out-schema (not= :cancelled terminated-by))
                           (try (sdk/structure-answer s text answer out-schema)
                                (catch Throwable e {:error (or (ex-message e) (str e))})))
              cost (let [cs (remove nil? [(:cost usage) (:cost structured)])] (when (seq cs) (reduce + cs)))
              ;; what a rewind of it goes back to, as a loaded turn says it
              ;; (byj-d8c.1): byj kept it before it said the turn ended
              checkpoint (when (and turn (ext? conn))
                           (some-> (try (changes/row (:config s) sessionId turn) (catch Throwable _ nil))
                                   session-history/checkpoint checkpoint-meta))]
          (cond-> {:stopReason (stop-reason result)}
            (ext? conn)
            (assoc :_meta {:brainyard (cond-> {:answer answer
                                               :lm (lm-json (sdk/current-lm s))
                                               :terminatedBy (some-> terminated-by name)
                                               :iterations (:iterations result)
                                               :goalAchieved (:goal-achieved result)
                                               :nextUserPrompt (:next-user-prompt result)
                                               :elapsedMs (:elapsed-ms result)
                                               :usage (dissoc usage :cost :unpriced)
                                               :costUsd cost}
                                        turn (assoc :turn turn)
                                        (some? files-changed) (assoc :filesChanged files-changed)
                                        checkpoint (assoc :checkpoint checkpoint)
                                        (contains? structured :value) (assoc :structuredOutput (json-safe (:value structured)))
                                        (:error structured) (assoc :structuredOutputError (:error structured)))})))))))

;; --- saved sessions, for an editor's import (byj-g5n.14) --------------------------

(def list-page
  "How many sessions one `session/list` answer holds; past it, `nextCursor`."
  50)

(defn- session-info
  "A saved session as ACP's `SessionInfo`: its title the conversation's name,
  else its last question."
  [cwd {:keys [id updated-at conversation-title last-question turn-count created-at model working-dir leader]}]
  (let [held (persist/holder {:project-dir cwd} id)
        ;; where it worked, when not where it is kept: a workflow stage or a
        ;; team member in a worktree of the project (byj-cm4.6); :cwd stays
        ;; the project's, which a session/load finds it in
        elsewhere (when (and (string? working-dir) (not (str/blank? working-dir))
                             (not= (config/canonical-dir working-dir) (config/canonical-dir cwd)))
                    working-dir)]
    {:sessionId id
     :cwd cwd
     :title (some-> (or conversation-title last-question) str/trim (str/replace #"\s+" " ") (clip 80))
     :updatedAt (some-> updated-at java.time.Instant/ofEpochMilli str)
     :_meta {:brainyard (cond-> {:turnCount (or turn-count 0)}
                          created-at (assoc :createdAt (str (java.time.Instant/ofEpochMilli created-at)))
                          ;; the model it last asked when it chose one of its
                          ;; own (`/model`), as a label; else it asks whatever
                          ;; opens it (byj-66y)
                          model (assoc :model model)
                          held (assoc :heldBy (str held))
                          elsewhere (assoc :workingDir elsewhere)
                          ;; the session leading it, a member's (byj-cm4.13)
                          leader (assoc :leader leader))}}))

(defn- delete-session
  "ACP's `session/delete`: a saved session deleted, closed first when this
  connection has it open; refused while another live process holds it. The
  request names no project, so it is the one `session/list` listed the session
  in on this connection, else `_meta.brainyard.cwd`, else the directory byj
  runs in."
  [conn {:keys [sessionId] :as params}]
  (when-not (re-matches #"\d{13}-[0-9a-f]{8}" (str sessionId))
    (throw (rpc-error -32602 (str "a session id is <millis>-<8 hex>, not " sessionId))))
  (let [st @(:state conn)
        open (get-in st [:sessions sessionId])
        cwd (or (some-> open :config :project-dir)
                (get-in st [:listed sessionId])
                (get-in params [:_meta :brainyard :cwd])
                (System/getProperty "user.dir"))]
    (when open
      (swap! (:state conn) update :sessions dissoc sessionId)
      (sdk/close! open))
    (let [r (persist/delete! {:project-dir cwd} sessionId)
          ;; one open here that never ran a turn is gone once closed
          ;; (`persist/prune-empty!`): deleted, as asked (byj-wb6)
          gone? (and open (:problem r) (not (.exists (persist/session-dir {:project-dir cwd} sessionId))))]
      (when (and (:problem r) (not gone?)) (throw (rpc-error -32602 (:problem r))))
      ;; and what it kept to rewind: nothing goes back to a session there
      ;; is no more (RFC-069, byj-ayk)
      (changes/forget! {:project-dir cwd :working-dir cwd} sessionId)
      ;; and the Claude Code and Codex sessions it delegated to: none left
      ;; orphaned, one running ended (byj-wb6)
      (delegates/forget-session! {:project-dir cwd :working-dir cwd} sessionId)
      (swap! (:state conn) update :listed dissoc sessionId)
      {})))

(defn- list-sessions
  "ACP's `session/list`: the project's saved sessions, newest first, less a
  snapshot that cannot be read. A session is saved when it starts, so one
  that has not run a turn yet is listed too (byj-y6a); one that never did and
  that no process has held for a day is removed first (`persist/prune-empty!`).
  byj keeps sessions per project, so the list is `cwd`'s, or the directory
  byj runs in. The cursor is the next page's offset."
  [conn {:keys [cwd cursor]}]
  (let [cwd (if (str/blank? (str cwd)) (System/getProperty "user.dir") cwd)
        from (if (str/blank? (str cursor))
               0
               (or (some-> (re-matches #"\d+" (str cursor)) parse-long)
                   (throw (rpc-error -32602 (str "not a cursor this agent gave: " cursor)))))
        _ (persist/prune-empty! {:project-dir cwd})
        all (->> (persist/summaries {:project-dir cwd})
                 (remove :problem))
        page (->> all (drop from) (take list-page))]
    ;; session/delete names a session alone; the project is the one it was listed in
    (swap! (:state conn) update :listed merge (zipmap (map :id page) (repeat cwd)))
    (cond-> {:sessions (mapv #(session-info cwd %) page)}
      (< (+ from list-page) (count all)) (assoc :nextCursor (str (+ from list-page))))))

;; --- live sessions this process did not start (byj-g5n.11) -------------------------

(defn- project-config
  "The config of the project at `cwd`, for finding its sessions where its
  config keeps them."
  [cwd]
  (when (str/blank? (str cwd)) (throw (rpc-error -32602 "cwd is required")))
  (:config (config/load-config {:working-dir cwd :overrides {:project-dir cwd :working-dir cwd}})))

(defn- live-session
  "The live session `target` names in the project — id, prefix or label — or
  an error saying why there is none (`channel/find-session`)."
  [cwd target]
  (let [config (project-config cwd)
        found (channel/find-session config target)]
    (if (:error found)
      (throw (rpc-error -32602 (:error found)))
      [config found])))

(defn- live-row [{:keys [session-id live? label last-question ops owner-pid]}]
  {:sessionId session-id :live (boolean live?) :label label :lastQuestion last-question
   :pid (some-> owner-pid str) :ops (json-safe ops)})

(defn- live-handlers []
  {"_byj/live/list"
   (fn [_ {:keys [cwd all]}]
     {:sessions (mapv live-row (cond->> (channel/sessions (project-config cwd)) (not all) (filter :live?)))})
   "_byj/live/ask"
   (fn [_ {:keys [cwd sessionId question timeoutMs]}]
     (when (str/blank? (str question)) (throw (rpc-error -32602 "empty question")))
     (let [[_ s] (live-session cwd sessionId)
           r (channel/request! (:ask-socket-path s) {:op :ask :question question
                                                     :timeout-ms (or timeoutMs 120000)}
                               (+ 5000 (or timeoutMs 120000)))]
       (if (= :ok (:status r))
         {:sessionId (:session-id s) :answer (:answer r) :usage (json-safe (:usage r))
          :provider (some-> (:provider r) name) :model (some-> (:model r) str)}
         (throw (rpc-error -32603 (str (or (:error r) "no answer")))))))
   "_byj/live/attach"
   (fn [conn {:keys [cwd sessionId events]}]
     (let [[_ s] (live-session cwd sessionId)
           id (str "attach-" (swap! (:next-id conn) inc))
           sid (:session-id s)
           {:keys [ack stop!]} (try (channel/subscribe! (:ask-socket-path s) (seq events)
                                                       (fn [{:keys [event payload]}]
                                                         (notify! conn "_byj/live/event"
                                                                  {:attachId id :sessionId sid
                                                                   :event (if (keyword? event) (subs (str event) 1) (str event))
                                                                   :payload (json-safe payload)})))
                                    (catch clojure.lang.ExceptionInfo e (throw (rpc-error -32603 (ex-message e)))))]
       (when-not (= :ok (:status ack)) (throw (rpc-error -32603 (str (or (:error ack) "could not subscribe")))))
       (swap! (:state conn) assoc-in [:attached id] stop!)
       {:attachId id :sessionId sid}))
   "_byj/live/detach"
   (fn [conn {:keys [attachId]}]
     (when-let [stop! (get-in @(:state conn) [:attached attachId])]
       (stop!)
       (swap! (:state conn) update :attached dissoc attachId))
     {})})

(defn- open-saved!
  "`session/load` (`replay?`, its history sent first as updates) or
  `session/resume` (not): a saved session opened; one already open here is
  refused."
  [conn {:keys [sessionId] :as params} replay?]
  (when (get-in @(:state conn) [:sessions sessionId])
    (throw (rpc-error -32602 (str "session " sessionId " is already open"))))
  (open! conn params {:resume sessionId :replay? replay?}))

(defn- acp-handlers
  []
  {"initialize"
   (fn [conn {:keys [protocolVersion] :as params}]
     (swap! (:state conn) assoc
            :extensions? (some? (get-in params [:_meta :brainyard]))
            :client (select-keys params [:clientInfo :clientCapabilities]))
     {:protocolVersion (min protocol-version (or protocolVersion protocol-version))
      :agentCapabilities {:loadSession true
                          :sessionCapabilities {:close {} :list {} :resume {} :delete {}}
                          :promptCapabilities {:image false :audio false :embeddedContext true}
                          :mcpCapabilities {:http false :sse false}
                          :_meta {:brainyard {:protocol extensions-version
                                              :extensions extensions}}}
      :agentInfo {:name "byj" :title "brainyard" :version (version/version)}
      :authMethods []})
   "authenticate" (fn [_ _] {})
   "session/new" (fn [conn params] (open! conn params {}))
   "session/load" (fn [conn params] (dissoc (open-saved! conn params true) :sessionId))
   "session/resume" (fn [conn params] (dissoc (open-saved! conn params false) :sessionId))
   "session/delete" delete-session
   "session/prompt" prompt!
   "session/list" list-sessions
   "_byj/turns/list" turns-list
   "_byj/session/history" session-history
   ;; a share of the session (RFC-043, byj-p2q): `/share ARGS` run between its
   ;; turns — start [--target T] [--record], stop, pause, resume, kick USER,
   ;; record on|off, or none for who watches — answered with what it said. Its
   ;; sidecar is a background task of the session; the session must listen on
   ;; its channel (session/new's _meta.brainyard.channel)
   "_byj/share"
   (fn [conn {:keys [sessionId args]}]
     (let [s (session-of conn sessionId)
           args (str/trim (str args))]
       (when-not (:channel s)
         (throw (rpc-error -32602 (str "session " sessionId " does not listen on its channel: open it with _meta.brainyard.channel true"))))
       (when-not (re-matches #"[A-Za-z0-9:/_.,@ -]*" args)
         (throw (rpc-error -32602 "args: /share's own words")))
       ;; a turn the channel put in is answered there as it ends, a moment
       ;; before the session waits again: that moment is waited out
       (when (loop [n 0] (cond (not (sdk/running? s)) false (> n 40) true :else (do (Thread/sleep 50) (recur (inc n)))))
         (throw (rpc-error -32602 (str "session " sessionId " is in the middle of a turn: try again when it ends"))))
       (let [r (deref (sdk/command! s (str/trim (str "/share " args))) 60000 {:timeout true})
             said (str/join "\n" (keep (fn [[_ t]] (not-empty (str t))) (:said r)))]
         (cond
           (:timeout r) (throw (rpc-error -32603 "/share did not answer in a minute"))
           (:refused r) (throw (rpc-error -32602 (:refused r)))
           (:closed r) (throw (rpc-error -32602 (str "session " sessionId " closed")))
           :else {:message said}))))
   "_byj/turns/diff" turns-diff
   "_byj/turns/rewind"
   (fn [conn {:keys [sessionId turn end]}]
     (when-not (pos-int? turn) (throw (rpc-error -32602 "turn is a turn byj keeps: _byj/turns/list says which")))
     (run-turns-command! conn sessionId (str "/rewind " turn (when end " end"))))
   "_byj/turns/undoTurn"
   (fn [conn {:keys [sessionId turn]}]
     (when-not (pos-int? turn) (throw (rpc-error -32602 "turn is a turn byj keeps: _byj/turns/list says which")))
     (run-turns-command! conn sessionId (str "/undo-turn " turn)))
   "_byj/turns/undoRewind"
   (fn [conn {:keys [sessionId n]}]
     (when (and (some? n) (not (pos-int? n))) (throw (rpc-error -32602 "n is a rewind's number")))
     (run-turns-command! conn sessionId (str "/rewind undo" (when n (str " " n)))))
   "_byj/predict"
   (fn [conn {:keys [predictor signature inputs provider model cwd config]}]
     (let [field (fn [{:keys [name description schema]}]
                   (when (str/blank? (str name)) (throw (rpc-error -32602 "a signature field needs a name")))
                   [(keyword name) (cond-> {} description (assoc :desc description))
                    (try (schema/from-json-schema (or schema {:type "string"}))
                         (catch clojure.lang.ExceptionInfo e (throw (rpc-error -32602 (str name ": " (ex-message e))))))])
           defaults (:defaults @(:state conn))
           sig (when signature
                 (cond-> {:instructions (str (:instructions signature))
                          :inputs (mapv field (:inputs signature))
                          :outputs (mapv field (:outputs signature))}
                   (:strategy signature) (assoc :strategy (keyword (:strategy signature)))))
           r (try (sdk/predict {:project-dir (or cwd (System/getProperty "user.dir"))
                                :predictor predictor :signature sig
                                :inputs (into {} (for [[k v] inputs] [(keyword (name k)) v]))
                                :provider (or provider (:provider defaults))
                                :model (or model (:model defaults))
                                :config (config/from-json config)})
                  (catch clojure.lang.ExceptionInfo e
                    (throw (rpc-error -32602 (ex-message e)))))]
       {:predictorId (:predictor-id r)
        :paramsSource (json-safe (:params-source r))
        :outputs (json-safe (:outputs r))
        :reasoning (:reasoning r)
        :usage (json-safe (dissoc (:usage r) :cost :unpriced))
        :costUsd (:cost r)}))
   "session/close" (fn [conn {:keys [sessionId]}]
                     ;; ACP's sessionCapabilities.close: the session ended — its
                     ;; turn cancelled, its lock released, its MCP stopped
                     (let [s (session-of conn sessionId)]
                       (swap! (:state conn) update :sessions dissoc sessionId)
                       (sdk/close! s)
                       {}))
   "session/set_mode" (fn [conn {:keys [sessionId modeId]}]
                        (when-not (some #(= modeId (:id %)) modes)
                          (throw (rpc-error -32602 (str "unknown mode " modeId))))
                        (sdk/set-mode! (session-of conn sessionId) modeId)
                        {})})

(defn handlers
  "ACP's methods, as byj answers them (RFC-053 'ACP's methods'), and byj's
  extensions."
  []
  (merge (acp-handlers) (live-handlers)))

(defn notification-handlers []
  {"session/cancel" (fn [conn {:keys [sessionId]}]
                      (some-> (get-in @(:state conn) [:sessions sessionId]) sdk/cancel!))})

(defn close-sessions!
  "Every session closed: each turn cancelled, locks released, MCP stopped."
  [conn]
  (let [ss (vals (:sessions @(:state conn)))]
    (doseq [s ss] (swap! (:state s) assoc :closed? true) (sdk/cancel! s))
    (doseq [s ss] (sdk/close! s 10000))))

(defn end!
  "The client closed its end: our requests answered `{:closed true}`, every
  session closed — which answers the prompts waiting on them — and then, for
  up to `ms` (5 s), the client's requests still in flight are let finish, so
  a client that wrote its last request and closed still reads the answers."
  ([conn] (end! conn 5000))
  ([conn ms]
   (close-connection! conn)
   (doseq [[_ stop!] (:attached @(:state conn))] (try (stop!) (catch Throwable _ nil)))
   (close-sessions! conn)
   (let [until (+ (System/currentTimeMillis) ms)]
     (loop []
       (when (and (pos? @(:in-flight conn)) (< (System/currentTimeMillis) until))
         (Thread/sleep 10)
         (recur))))))

;; --- the process's stdio ---------------------------------------------------------

(ffi/defcfn ^:private c-read "read" [:int :pointer :int] :int :blocking)
(ffi/defcfn ^:private c-write "write" [:int :pointer :int] :int :blocking)
(ffi/defcfn ^:private c-dup "dup" [:int] :int)
(ffi/defcfn ^:private c-dup2 "dup2" [:int :int] :int)

(defn- write-fd!
  "Write all of `bytes` to `fd`."
  [fd ^bytes bytes]
  (let [n (alength bytes)
        buf (ffi/alloc (max 1 n))]
    (try
      (dotimes [i n] (ffi/write buf :uint8 (bit-and (aget bytes i) 0xff) i))
      (loop [off 0]
        (when (< off n)
          (let [w (c-write fd (if (zero? off) buf (ffi/slice buf off)) (- n off))]
            (when (pos? w) (recur (+ off w))))))
      (finally (ffi/free buf)))))

(defn- read-lines!
  "Read fd 0 until its end, calling `on-line` with each line (UTF-8, the
  newline dropped). Blocks; returns at EOF."
  [on-line]
  (let [cap 65536
        buf (ffi/alloc cap)
        pending (java.io.ByteArrayOutputStream.)]
    (try
      (loop []
        (let [n (c-read 0 buf cap)]
          (when (pos? n)
            (dotimes [i n]
              (let [b (ffi/read buf :uint8 i)]
                (if (= b 10)
                  (let [s (String. (.toByteArray pending) "UTF-8")]
                    (.reset pending)
                    (on-line s))
                  (.write pending (int b)))))
            (recur))))
      (when (pos? (.size pending)) (on-line (String. (.toByteArray pending) "UTF-8")))
      (finally (ffi/free buf)))))

(defn serve!
  "Serve ACP on this process's stdio until the client closes its end; then
  close every session and return. `defaults`: `:provider` `:model` `:agent`
  `:permission-mode` for a session that names none (`byj acp -p … -m …`)."
  [defaults]
  (let [out (c-dup 1)
        _ (c-dup2 2 1)                   ; fd 1 is stderr from here on
        lock (Object.)
        conn (connection (fn [line]
                           (locking lock
                             (write-fd! out (.getBytes (str line "\n") "UTF-8"))))
                         (handlers)
                         (notification-handlers))]
    (swap! (:state conn) assoc :defaults defaults)
    (read-lines! #(receive! conn %))
    (end! conn)))
