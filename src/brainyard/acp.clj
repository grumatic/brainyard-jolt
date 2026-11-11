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
            [brainyard.channel :as channel]
            [brainyard.config :as config]
            [brainyard.context :as context]
            [brainyard.effect :as fx]
            [brainyard.hooks :as hooks]
            [brainyard.json :as json]
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
  ["tools" "hooks" "events" "predict" "live"])

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
  "The permission modes (RFC-012) as ACP session modes."
  [{:id "auto" :name "Auto" :description "A classifier decides each gated call, asking when unsure"}
   {:id "ask-each-time" :name "Ask each time" :description "Every gated call is asked about"}
   {:id "auto-approve" :name "Auto-approve" :description "Gated calls run without asking"}
   {:id "deny-by-default" :name "Deny by default" :description "Gated calls are refused"}])

(defn- ext? [conn] (boolean (:extensions? @(:state conn))))

(defn- update! [conn sid u] (notify! conn "session/update" {:sessionId sid :update u}))

(defn- text-block [t] {:type "text" :text (str t)})

(defn tool-kind
  "ACP's kind of a tool call, from the tool's effects (RFC-007)."
  [effects]
  (let [e (set effects)]
    (cond
      (:process e) "execute"
      (:fs/write e) "edit"
      (:fs/read e) "read"
      (some #(= "net" (namespace %)) (filter keyword? e)) "fetch"
      :else "other")))

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- call-title [tool-name args]
  (let [a (cond (map? args) (some (fn [k] (get args k)) [:command :path :pattern :query :url])
                :else nil)]
    (str tool-name (when a (str " " (clip a 80))))))

(defn- on-event
  "The session's events as `session/update`s: the answer field's chunks as
  the agent's message, the reasoning as its thoughts, a batch of tool calls
  as `tool_call`s and their results as `tool_call_update`s."
  [conn sid-box effects-of replay?]
  (fn [event data]
    (when-let [sid @sid-box]
      (let [st (:state conn)]
        (case event
          :agent.llm/chunk
          (case (some-> (:field data) name)
            "answer" (do (swap! st assoc-in [:streamed sid] true)
                         (update! conn sid {:sessionUpdate "agent_message_chunk" :content (text-block (:text data))}))
            "reasoning" (update! conn sid {:sessionUpdate "agent_thought_chunk" :content (text-block (:text data))})
            nil)
          :agent.tools/calls
          (let [n (get-in (swap! st update-in [:batches sid] (fnil inc 0)) [:batches sid])
                ids (vec (for [[i {:keys [tool-name tool-args]}] (map-indexed vector (:tool-calls data))]
                           (let [id (str "call-" n "-" i)]
                             (update! conn sid {:sessionUpdate "tool_call" :toolCallId id
                                                :title (call-title tool-name tool-args)
                                                :kind (tool-kind (effects-of tool-name))
                                                :status "pending"
                                                :rawInput (or tool-args {})})
                             [id tool-name])))]
            (swap! st assoc-in [:calls sid] ids))
          :agent.tools/results
          (let [ids (get-in @st [:calls sid])]
            (doseq [[[id _] r] (map vector ids (:results data))]
              (update! conn sid {:sessionUpdate "tool_call_update" :toolCallId id
                                 :status (if (:tool-error? r) "failed" "completed")
                                 :content [{:type "content"
                                            :content (text-block (clip (:tool-result r) 4000))}]})))
          :session/resumed
          (doseq [[kind x] (when replay? (:transcript data))]
            (case kind
              :you (update! conn sid {:sessionUpdate "user_message_chunk" :content (text-block x)})
              :answer (update! conn sid {:sessionUpdate "agent_message_chunk" :content (text-block x)})
              :thought (update! conn sid {:sessionUpdate "agent_thought_chunk" :content (text-block x)})
              nil))
          nil)))))

(defn- pending-call-id
  "The id of the newest pending call of `tool`, to name in a permission
  request; a fresh one when the gate asks before any call was announced."
  [conn sid tool]
  (or (some (fn [[id t]] (when (= t tool) id)) (reverse (get-in @(:state conn) [:calls sid])))
      (str "ask-" (System/currentTimeMillis))))

(defn permission-fn
  "A session's `:permission-fn` (RFC-012) as ACP's `session/request_permission`:
  the client chooses allow once, allow always, reject once or reject always;
  an always is remembered for the session by the pattern that matched, as the
  terminal remembers one. No timeout, as the terminal has none (divergence
  `permission-prompt-cli`): the client closing its end answers it."
  [conn sid-box effects-of]
  (let [remembered (atom {})]
    (fn [{:keys [tool display pattern args type action path] :as req}]
      (let [key (if (= :file-access type) (str (some-> action name) " " path) (or pattern display))]
        (or (get @remembered key)
            (let [sid @sid-box
                  title (if (= :file-access type)
                          (str (some-> action name) " " path)
                          (call-title (or tool display) args))
                  answer @(request! conn "session/request_permission"
                                    {:sessionId sid
                                     :toolCall {:toolCallId (pending-call-id conn sid tool)
                                                :title title
                                                :kind (if (= :file-access type)
                                                        (if (= :write action) "edit" "read")
                                                        (tool-kind (effects-of tool)))
                                                :status "pending"
                                                :rawInput (or args {})}
                                     :options [{:optionId "allow-once" :name "Allow once" :kind "allow_once"}
                                               {:optionId "allow-always" :name "Always allow" :kind "allow_always"}
                                               {:optionId "reject-once" :name "Reject" :kind "reject_once"}
                                               {:optionId "reject-always" :name "Always reject" :kind "reject_always"}]})
                  outcome (get-in answer [:result :outcome])
                  chosen (when (= "selected" (:outcome outcome)) (:optionId outcome))
                  allow {:allowed true}
                  deny {:denied true :reason "denied by the client"}]
              (case chosen
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
        defaults (:defaults @(:state conn))
        ;; the id chosen here — or by the client, in context/session-id's shape,
        ;; so a fork's history replayed before session/new answers reaches it —
        ;; so a hook firing as the session starts names it
        chosen (:sessionId o)
        _ (when (and chosen (not (re-matches #"\d{13}-[0-9a-f]{8}" (str chosen))))
            (throw (rpc-error -32602 (str "a session id is <millis>-<8 hex>, not " chosen))))
        sid-box (atom (or resume chosen (context/session-id)))
        ;; the client's editor as the file tools' fs, when it offers one
        fs-cap (get-in @(:state conn) [:client :clientCapabilities :fs])
        fs-tools (when (or (:readTextFile fs-cap) (:writeTextFile fs-cap))
                   (tools/file-tools (client-fs conn sid-box cwd fs-cap)))
        client-tools (into (mapv #(client-tool conn sid-box %) (:tools o)) fs-tools)
        client-hooks (mapv #(client-hook conn sid-box %) (:hooks o))
        wanted? (event-filter (:events o))
        tool-effects (into {} (for [t (concat tools/builtin-tools client-tools)] [(:name t) (:effects t)]))
        effects-of #(get tool-effects % #{})
        translate (on-event conn sid-box effects-of replay?)
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
                      :commands? (boolean (:commands o))
                      :mcp-servers (mcp-servers mcpServers)
                      :permission-mode (some-> (or (:permissionMode o) (:permission-mode defaults)) keyword)
                      :permission-fn (permission-fn conn sid-box effects-of)
                      :tools client-tools
                      :hooks client-hooks
                      :session-id @sid-box
                      :on-event (fn [e d]
                                  (translate e d)
                                  (when (and (ext? conn) (wanted? e))
                                    (notify! conn "_byj/event" {:sessionId @sid-box :event (subs (str e) 1)
                                                                :payload (json-safe d)})))
                      :on-problem (fn [msg] (binding [*out* *err*] (println msg)))}
               resume (assoc :resume resume)))
            (catch clojure.lang.ExceptionInfo e
              (throw (rpc-error -32602 (ex-message e)))))
        sid (:id s)]
    (swap! (:state conn) assoc-in [:sessions sid] s)
    ;; up and waiting — a resumed one has replayed its history as updates
    (when-not (deref (:ready s) 60000 false)
      (swap! (:state conn) update :sessions dissoc sid)
      (throw (rpc-error -32603 (str "session " sid " did not start"))))
    {:sessionId sid
     :modes {:currentModeId (name (or (some-> (:permission-mode-box s) deref) :auto))
             :availableModes modes}}))

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
        _ (swap! (:state conn) assoc-in [:streamed sessionId] false)
        r (deref (sdk/submit! s text))]
    (cond
      (:refused r) (throw (rpc-error -32602 (:refused r)))
      (:closed r) {:stopReason "cancelled"}
      (:no-turn r)
      (do (doseq [[_ t] (:said r) :when (seq (str t))]
            (update! conn sessionId {:sessionUpdate "agent_message_chunk" :content (text-block t)}))
          {:stopReason "end_turn"})
      :else
      (let [{:keys [answer terminated-by usage] :as result} (:result r)]
        (when (= :loop-error terminated-by)
          (throw (rpc-error -32603 "the turn failed" {:detail (str answer)})))
        ;; the answer, when it was not streamed as it was written
        (when (and (seq (str answer)) (not (get-in @(:state conn) [:streamed sessionId])))
          (update! conn sessionId {:sessionUpdate "agent_message_chunk" :content (text-block answer)}))
        (let [structured (when (and out-schema (not= :cancelled terminated-by))
                           (try (sdk/structure-answer s text answer out-schema)
                                (catch Throwable e {:error (or (ex-message e) (str e))})))
              cost (let [cs (remove nil? [(:cost usage) (:cost structured)])] (when (seq cs) (reduce + cs)))]
          (cond-> {:stopReason (stop-reason result)}
            (ext? conn)
            (assoc :_meta {:brainyard (cond-> {:answer answer
                                               :terminatedBy (some-> terminated-by name)
                                               :iterations (:iterations result)
                                               :goalAchieved (:goal-achieved result)
                                               :nextUserPrompt (:next-user-prompt result)
                                               :elapsedMs (:elapsed-ms result)
                                               :usage (dissoc usage :cost :unpriced)
                                               :costUsd cost}
                                        (contains? structured :value) (assoc :structuredOutput (json-safe (:value structured)))
                                        (:error structured) (assoc :structuredOutputError (:error structured)))})))))))

;; --- saved sessions, for an editor's import (byj-g5n.14) --------------------------

(def list-page
  "How many sessions one `session/list` answer holds; past it, `nextCursor`."
  50)

(defn- session-info
  "A saved session as ACP's `SessionInfo`: its title the conversation's name,
  else its last question."
  [cwd {:keys [id updated-at conversation-title last-question turn-count created-at]}]
  (let [held (persist/holder {:project-dir cwd} id)]
    {:sessionId id
     :cwd cwd
     :title (some-> (or conversation-title last-question) str/trim (str/replace #"\s+" " ") (clip 80))
     :updatedAt (some-> updated-at java.time.Instant/ofEpochMilli str)
     :_meta {:brainyard (cond-> {:turnCount (or turn-count 0)}
                          created-at (assoc :createdAt (str (java.time.Instant/ofEpochMilli created-at)))
                          held (assoc :heldBy (str held)))}}))

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
    (let [r (persist/delete! {:project-dir cwd} sessionId)]
      (when (:problem r) (throw (rpc-error -32602 (:problem r))))
      (swap! (:state conn) update :listed dissoc sessionId)
      {})))

(defn- list-sessions
  "ACP's `session/list`: the project's saved sessions, newest first — what
  `byj run --resume` lists, less a snapshot that cannot be read and a session
  that never ran a turn, which has nothing to bring back. byj keeps sessions
  per project, so the list is `cwd`'s, or the directory byj runs in. The
  cursor is the next page's offset."
  [conn {:keys [cwd cursor]}]
  (let [cwd (if (str/blank? (str cwd)) (System/getProperty "user.dir") cwd)
        from (if (str/blank? (str cursor))
               0
               (or (some-> (re-matches #"\d+" (str cursor)) parse-long)
                   (throw (rpc-error -32602 (str "not a cursor this agent gave: " cursor)))))
        all (->> (persist/summaries {:project-dir cwd})
                 (remove :problem)
                 (filter #(pos? (or (:turn-count %) 0))))
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
