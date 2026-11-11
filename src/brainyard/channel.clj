(ns brainyard.channel
  "The session channel (RFC-031): how one live session is asked something by
  another, or by a script — brainyard's `ask.sock` (`components/ask-channel`,
  `bases/agent-tui` `ask-handle-fn`).

  A live session listens at `<session-dir>/ask.sock` (`brainyard.unix-socket`,
  `0600`). A request that would change the session — `:ask`, `:inject :as
  :turn` — is never an interruption: it joins the session's own queue (at most
  10) and becomes an ordinary turn when the session is next waiting for input,
  handed over through the same seam as a background task's wakeup (RFC-019).
  When that turn ends, its answer goes back on the connection that asked.

  This namespace knows the requests and the queue. It does not know what a turn
  is: the session tells it when one starts (`turn-started!`), ends
  (`turn-ended!`), or never came (`settle-unanswered!`), and a front end takes
  the next request from it (`next-turn!`).

  brainyard's ops, replies and error strings are kept; what is added is the
  port's, and named as such:

    :ask     {:question :timeout-ms}   → {:status :ok :answer :usage :provider :model :agent}
    :inject  {:as :turn :text :await?} → {:status :ok :injected :turn :answer} or, with
                                         :await? false, {:status :ok :injected :turn :queued true}
    :status                            → {:status :ok :state :idle|:running :pending-turns
                                          :session-id :pid :frame-version :provider :model :agent}
    :cancel                            → {:status :ok :cancelled bool}
    :inject  {:as :note :text :from}   → {:status :ok :injected :note} (RFC-043)

  A request may carry `:from`, the asking session's id (the port's): the turn
  it becomes is shown with it. An injected turn's `:from` may instead be a
  person, `{:user :name :role}`, and it may need its owner's `:approval`
  (`:owner`, or `:consent` — no prompt, still marked): RFC-043, the share
  sidecar's."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [brainyard.claude-reach :as claude-reach]
            [brainyard.codex-reach :as codex-reach]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.persist :as persist]
            [brainyard.tasks :as tasks]
            [brainyard.unix-socket :as us]))

(def ops
  "What this channel answers, advertised in `meta.edn`."
  [:ask :inject :status :cancel :subscribe :config :emit :mcp :rename-session])

(def not-ported
  "brainyard's ops that have no counterpart here, each with why — asked for,
  they say so rather than `unknown op`."
  {:fsm-status "not ported: this port has no finite-state machines, a session is a behaviour tree (RFC-002)"
   :a2a "not ported: A2A peers are not part of this port (RFC-018)"
   :new-session "not ported: a session here is its own process — start one with `jolt run:byj --serve` or team$spawn"
   :resume-session "not ported: a session here is its own process — resume one with `jolt run:byj --serve --resume ID`"
   :switch-session "not ported: switching sessions is the full-screen renderer's session list, not a request"})

(def frame-version
  "The version of this socket's frames — the requests, replies and `:subscribe`
  frames above. The port's, for a client outside this repository (the share
  sidecar, RFC-043), which reads it from `:status` or `meta.edn` and refuses a
  version it does not know rather than misreading one. Bumped when a frame's
  shape changes; a key added to a reply is not a change. 2: `:inject :as :note`,
  an injected turn's `:from` map and `:approval`, and the permission gate's
  `:permission/asked` and `:permission/decided` on `:subscribe` (RFC-043)."
  2)

(def max-subscriber-queue
  "brainyard's subscriber queue: past this many unsent events, more are
  dropped, so a slow subscriber never stalls the session."
  512)

(def max-queue
  "brainyard's `input queue` bound."
  10)

(def no-answer "no answer (turn cancelled or failed)")
(def closed "target session closed")

;; --- where -----------------------------------------------------------------------------

(defn- sha256-hex [^String s]
  (apply str (map #(format "%02x" (bit-and 0xff %))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(defn socket-path
  "`<session-dir>/ask.sock`, or — when that is longer than `sun_path` holds —
  `<tmpdir>/by-<first 16 hex of sha256(session-id)>.sock`, brainyard's
  fallback. Deterministic, but a client reads the path from `meta.edn` rather
  than working it out."
  [config session-id]
  (let [p (.getPath (io/file (persist/session-dir config session-id) "ask.sock"))]
    (if (<= (count (.getBytes p "UTF-8")) (us/max-path-bytes))
      p
      (.getPath (io/file (or (System/getenv "TMPDIR") "/tmp")
                         (str "by-" (subs (sha256-hex (str session-id)) 0 16) ".sock"))))))

(defn meta-file [config session-id]
  (io/file (persist/session-dir config session-id) "meta.edn"))

(defn read-meta
  "A session's `meta.edn`, or {}."
  [config session-id]
  (let [f (meta-file config session-id)]
    (or (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil))) {})))

(defn- write-meta! [config session-id m]
  (let [f (meta-file config session-id)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str (merge (read-meta config session-id) m)))))

;; --- the queue -------------------------------------------------------------------------

(defn- enqueue!
  "Add a request to the queue, or say why not."
  [{:keys [queue closed?]} entry]
  (cond
    @closed? {:status :error :error closed}
    :else (let [[before] (swap-vals! queue #(if (< (count %) max-queue) (conj % entry) %))]
            (if (< (count before) max-queue)
              nil
              {:status :error :error (str "input queue is full (max " max-queue ")")}))))

(defn- wait-reply
  "The reply to a queued request, or a timeout. A request that timed out is
  NOT withdrawn: its turn still runs later, as brainyard's does."
  [reply timeout-ms]
  (let [r (deref reply timeout-ms ::timeout)]
    (if (= ::timeout r)
      {:status :error :error (str "timed out after " timeout-ms "ms")}
      r)))

(defn- ask!
  [{:keys [config] :as ch} text timeout-ms from & [entry]]
  (if (str/blank? (str text))
    {:status :error :error "empty question"}
    (let [reply (promise)
          cap (:ask-timeout-ms config 120000)
          timeout (min cap (or timeout-ms cap))]
      (or (enqueue! ch (merge {:op :ask} entry {:text (str text) :from from :reply reply}))
          (wait-reply reply timeout)))))

(defn sender-label
  "Who a request's `:from` names, for a person to read: a session's id as it
  is, a person (`{:user :name :role}`, RFC-043) as `Kim (coach)`; nil for none."
  [from]
  (cond
    (map? from) (let [who (or (not-empty (str (:name from))) (not-empty (str (:user from))) "someone")
                      role (:role from)]
                  (str who (when (and role (not= "" (str role))) (str " (" (name role) ")"))))
    (some? from) (str from)
    :else nil))

(def approval-ms
  "How long an injected turn's owner has to answer, by default — RFC-043's
  two minutes; `:share-inject-approval-ms` sets it."
  120000)

(def no-approver "this session has no one to approve an injected turn")

(defn- approve
  "nil when the injected turn `msg` may be queued, else the reply refusing it.
  `:approval :owner` asks the session's person through the `:approve!` it
  lent (`attach!`): `(approve! {:text :from :who :timeout-ms})` answers
  `:approved`, `:rejected`, `:expired`, or nil when no one is there to ask.
  `:consent` asks no one — a class's consent stands for it — but the turn is
  still marked (RFC-043)."
  [{:keys [config hooks]} msg]
  (case (some-> (:approval msg) keyword)
    nil nil
    :consent nil
    :owner (if-let [f (:approve! @hooks)]
             (let [ms (:share-inject-approval-ms config approval-ms)
                   r (try (f {:text (str (:text msg)) :from (:from msg) :who (sender-label (:from msg))
                              :timeout-ms ms})
                          (catch Throwable _ :rejected))]
               (case r
                 :approved nil
                 :expired {:status :error :error "approval expired"}
                 nil {:status :error :error no-approver}
                 {:status :error :error "rejected by the owner"}))
             {:status :error :error no-approver})
    {:status :error :error (str "unknown :approval " (pr-str (:approval msg)) " (:owner or :consent)")}))

(defn handle
  "The reply to one request — `ask-handle-fn`. Blocks, for `:ask` and an
  awaited `:inject`, until the turn it became has ended or the timeout."
  [{:keys [queue running? session-id describe cancel!] :as ch} {:keys [op] :as msg}]
  (case op
    :ask (ask! ch (:question msg) (:timeout-ms msg) (:from msg))

    :inject
    (case (:as msg)
      :memory (let [f (:remember! @(:hooks ch))]
                (cond
                  (nil? f) {:status :error :error "this session keeps no memory store"}
                  (str/blank? (str (:content msg))) {:status :error :error "empty memory"}
                  :else (let [r (f (:slug msg) (:content msg))]
                          (if (:error r)
                            (assoc r :status :error)
                            {:status :ok :injected :memory :slug (:slug msg) :stored (:stored r)}))))
      :artifact {:status :error :error "not ported: :inject :as :artifact — this port has no live artifacts (RFC-017)"}
      ;; for people only (RFC-043): shown, recorded and published by the
      ;; session, and never a turn — nothing of it reaches the model
      :note (let [f (:note! @(:hooks ch))]
              (cond
                (str/blank? (str (:text msg))) {:status :error :error "empty note"}
                (nil? f) {:status :error :error "no session to note on yet"}
                :else (do (f (str (:text msg)) (:from msg))
                          {:status :ok :injected :note})))
      :turn (let [entry (cond-> {:op :inject} (:approval msg) (assoc :approval (keyword (:approval msg))))]
              (cond
                (str/blank? (str (:text msg))) {:status :error :error "empty question"}
                ;; the owner says yes before anything is queued
                :else
                (or (approve ch msg)
                    (if (false? (:await? msg))
                      (or (enqueue! ch (assoc entry :text (str (:text msg)) :from (:from msg)))
                          {:status :ok :injected :turn :queued true})
                      (let [r (ask! ch (:text msg) (:timeout-ms msg) (:from msg) entry)]
                        (if (= :ok (:status r))
                          {:status :ok :injected :turn :answer (:answer r)}
                          r))))))
      {:status :error :error (str "unsupported :inject :as " (pr-str (:as msg)) " (:turn, :note or :memory)")})

    :status (merge {:status :ok
                    :state (if @running? :running :idle)
                    :pending-turns (count @queue)
                    :session-id session-id
                    :pid (persist/own-pid)
                    :frame-version frame-version}
                   (when describe (describe)))

    :cancel {:status :ok :cancelled (boolean (when cancel! (cancel!)))}

    ;; the session's effective config, secrets redacted: brainyard's read-only
    ;; `:config`, with each key's layer as this port records it (RFC-013)
    :config (if-let [f (:config-rows @(:hooks ch))]
              (let [rows (f)
                    q (some-> (:query msg) str str/lower-case not-empty)]
                (if q
                  {:status :ok :matches (vec (filter #(str/includes? (str (:key %)) q) rows))}
                  {:status :ok
                   :overrides (into (sorted-map) (for [{:keys [key value source]} rows :when (not= :default source)]
                                                   [key {:value value :source source}]))
                   :total (count rows)}))
              {:status :error :error "no session to read the config of yet"})

    ;; an event of the caller's, emitted on this session's bus: its hooks, its
    ;; subscribers and its event log see it
    :emit (let [f (:emit! @(:hooks ch))
                e (some-> (:event msg) keyword)]
            (cond (nil? f) {:status :error :error "no session to emit on yet"}
                  (nil? e) {:status :error :error ":event is required"}
                  :else (do (f e (:payload msg))
                            {:status :ok :emitted e :subscribers (count @(:subscribers ch))})))

    ;; the session's MCP servers, as /mcp shows them — read only
    :mcp (let [f (:mcp-rows @(:hooks ch))]
           (cond (nil? f) {:status :error :error "no session to ask yet"}
                 (not (contains? #{nil :list "list"} (:action msg)))
                 {:status :error :error "only :action :list — /mcp in the session changes servers"}
                 :else {:status :ok :servers (f)}))

    ;; brainyard's `:rename-session`: both surfaces, the name `session list`
    ;; shows and the conversation's own title
    :rename-session (let [label (str/trim (str (:label msg)))]
                      (if (str/blank? label)
                        {:status :error :error ":label is required"}
                        (do (write-meta! (:config ch) session-id {:label label})
                            (when-let [f (:rename! @(:hooks ch))] (f label))
                            {:status :ok :label label})))

    ;; brainyard's event stream: an ack, then a frame per event until the
    ;; subscriber goes away or the session closes
    :subscribe
    (let [wanted (some->> (:events msg) (map keyword) set not-empty)
          id (str (gensym "sub"))
          box (atom {:frames [] :dropped 0})]
      (swap! (:subscribers ch) assoc id {:events wanted :box box})
      (us/stream {:status :ok :subscribed (if wanted (vec (sort wanted)) :all)}
                 (fn [write! open?]
                   (try
                     (loop []
                       (when (and (open?) (not @(:closed? ch)))
                         (let [[{:keys [frames]}] (swap-vals! box assoc :frames [])]
                           (doseq [f frames] (write! f))
                           (when (empty? frames) (Thread/sleep 50))
                           (recur))))
                     (finally (swap! (:subscribers ch) dissoc id))))))

    ;; a member's permission prompt, asked of the person at THIS session's gate
    ;; (the port's: a headless member has no one of its own to ask)
    :permission (if-let [pf (:permission-fn ch)]
                  (let [who (or (:from-label msg) (:from msg) "another session")
                        r (pf (-> (:request msg)
                                  (update :display #(str "⇠ " who ": " %))))]
                    (merge {:status :ok} (select-keys r [:allowed :denied :reason])))
                  {:status :error :error "this session cannot ask a person for permission"})

    ;; brainyard's process-level op, here for the one kind of session that
    ;; has no person to quit it: the headless host (`jolt run:byj --serve`)
    :close-session (if-let [close! (:close! ch)]
                     (do (close!) {:status :ok :closing session-id})
                     {:status :error :error "this session is not headless: a person closes it"})

    (if-let [why (not-ported op)]
      {:status :error :error why}
      {:status :error :error (str "unknown op: " (pr-str op))})))

;; --- the session's side ------------------------------------------------------------------

(defn next-turn!
  "The next request, as `{:text :from}`, for a front end to hand the session
  while it waits for input — or nil. From here until the turn ends it is the
  request being answered."
  [{:keys [queue in-flight closed?]}]
  (when-not @closed?
    (let [[before] (swap-vals! queue #(if (seq %) (subvec % 1) %))]
      (when-let [e (first before)]
        (reset! in-flight e)
        (select-keys e [:text :from :approval])))))

(defn injected-by
  "Who injected the turn being answered (`:inject :as :turn`), as the
  trajectory records it (RFC-043): a person's `{:user :name :role}`, a
  session's `{:session id}`, `{}` for neither — with the `:approval` it came
  under. nil for a turn nobody injected, an `:ask` included."
  [ch]
  (when-let [{:keys [op from approval]} (some-> ch :in-flight deref)]
    (when (= :inject op)
      (cond-> (cond (map? from) (select-keys from [:user :name :role])
                    (some? from) {:session (str from)}
                    :else {})
        approval (assoc :approval approval)))))

(defn- edn-safe
  "`x` as data any reader can take back: a value that is not — a function, an
  atom, an exception — is written as its string, as brainyard's `edn-safe`."
  [x]
  (walk/postwalk (fn [v]
                   (if (or (nil? v) (string? v) (number? v) (keyword? v) (symbol? v) (boolean? v)
                           (map? v) (vector? v) (set? v) (seq? v) (map-entry? v))
                     v
                     (str v)))
                 x))

(defn publish!
  "Hand event `e` to every subscriber that wants it. A subscriber that has
  fallen `max-subscriber-queue` behind loses the event rather than holding up
  the session that emitted it."
  [{:keys [subscribers session-id]} e data]
  (when (and subscribers (seq @subscribers))
    (let [frame (delay {:event e :sid session-id :payload (edn-safe data)})]
      (doseq [[_ {:keys [events box]}] @subscribers
              :when (or (nil? events) (contains? events e))]
        (swap! box (fn [{:keys [frames] :as b}]
                     (if (< (count frames) max-subscriber-queue)
                       (update b :frames conj @frame)
                       (update b :dropped inc))))))))

(defn attach!
  "The session running on this channel lends it what only a session has:
  `:config-rows` (its config, as `config/describe` rows, redacted), `:emit!`
  (its event bus), `:mcp-rows`, `:remember!` (its memory store), `:rename!`
  (its conversation's title), `:note!` (a note for people, RFC-043) and —
  when a person is there to ask — `:approve!` (an injected turn's approval)."
  [ch hooks]
  (when ch (reset! (:hooks ch) hooks))
  ch)

(defn pending?
  "Is a request waiting?"
  [{:keys [queue]}]
  (boolean (seq @queue)))

(defn turn-started! [{:keys [running?]}] (reset! running? true) nil)

(defn from-channel?
  "Is the line the session is handling one this channel handed over — a
  request still being answered? (byj-gly.282)"
  [ch]
  (boolean (some-> ch :in-flight deref)))

(defn- settle! [{:keys [in-flight]} reply]
  (let [[e] (reset-vals! in-flight nil)]
    (when-let [p (:reply e)] (deliver p reply))
    nil))

(defn turn-ended!
  "The turn ended with `result` (`agent/turn-result`'s shape): answer the
  request it came from, if it came from one. A cancelled or failed turn is
  brainyard's `no answer`."
  [{:keys [running? describe] :as ch} {:keys [answer terminated-by usage]}]
  (reset! running? false)
  (settle! ch (if (or (str/blank? (str answer)) (#{:cancelled :loop-error} terminated-by))
                {:status :error :error no-answer}
                (merge {:status :ok :answer answer :usage usage}
                       (when describe (describe))))))

(defn refuse!
  "Answer the request being handled with an error saying why it was not run
  (byj-gly.282)."
  [ch reason]
  (when ch
    (let [[e] (reset-vals! (:in-flight ch) nil)]
      (when-let [p (:reply e)] (deliver p {:status :error :error reason}))
      nil)))

(defn settle-unanswered!
  "The session is waiting for input again, and the request handed to it never
  became a turn — a slash command, an empty line — so it has no answer."
  [ch]
  (settle! ch {:status :error :error no-answer}))

;; --- opening and closing -----------------------------------------------------------------

(defn open!
  "Listen for this session. Returns the channel; one that could not listen —
  another process owns the socket, the platform has no unix sockets — carries
  `:problem` and takes no requests, and the session runs without it, as
  brainyard's does.

  opts: `:cancel!` cancels the running turn and says whether one was running;
  `:describe` returns `{:provider :model :agent}` for replies; `:close!`, a
  headless session's, ends it when asked (`:close-session`); `:label` names
  the session in `sessions`; `:permission-fn`, the session's own gate, answers
  a member's forwarded prompt (`:permission`); `:tmux-pane`, the terminal the
  session draws in as `[SOCKET,]PANE`, lets a share of it show its screen
  (RFC-043)."
  [config session-id {:keys [cancel! describe close! label permission-fn tmux-pane]}]
  (let [ch {:config config :session-id session-id
            :queue (atom []) :in-flight (atom nil) :running? (atom false) :closed? (atom false)
            :cancel! cancel! :describe describe :close! close! :permission-fn permission-fn
            :subscribers (atom {})
            ;; what the session running on this channel lends it (`attach!`)
            :hooks (atom {})
            :server (atom nil)}
        path (socket-path config session-id)]
    (try
      (reset! (:server ch) (us/serve! path #(handle ch %)))
      ;; every key, so a label from an earlier run does not outlive it
      (write-meta! config session-id {:ask-socket-path path
                                      :frame-version frame-version
                                      :ops (cond-> ops
                                             permission-fn (conj :permission)
                                             close! (conj :close-session))
                                      :label label
                                      :tmux-pane tmux-pane})
      (assoc ch :path path)
      (catch Throwable e
        (reset! (:closed? ch) true)
        (assoc ch :problem (str "no session channel: " (ex-message e)))))))

(defn close!
  "Stop listening, and answer everything still waiting — queued or handed over
  — with brainyard's `target session closed`."
  [{:keys [closed? queue server] :as ch}]
  (when ch
    (reset! closed? true)
    (when-let [s @server] ((:stop! s)) (reset! server nil))
    (settle! ch {:status :error :error closed})
    (doseq [{:keys [reply]} (first (reset-vals! queue []))]
      (when reply (deliver reply {:status :error :error closed})))
    nil))

(defn request!
  "Ask the live session whose socket is at `path`: brainyard's client."
  [path msg & [timeout-ms]]
  (us/request! path msg timeout-ms))

(defn subscribe!
  "Subscribe to the events of the live session at `path` — all of them, or
  `events` — calling `(on-frame {:event :sid :payload})` for each. Returns
  `{:ack :stop! :done}` (`brainyard.unix-socket/stream!`)."
  [path events on-frame]
  (us/stream! path (cond-> {:op :subscribe} (seq events) (assoc :events (vec events))) on-frame))

;; --- finding a live session ------------------------------------------------------------

(defn- lock-pid [config id]
  (let [f (persist/lock-file config id)]
    (when (.isFile f)
      (some-> (try (edn/read-string (slurp f)) (catch Throwable _ nil)) :pid str))))

(defn sessions
  "Every session under the project that has ever listened, newest first:
  brainyard's `enriched-summaries` row — `:session-id :live? :owner-pid
  :ask-socket-path :ops`, and the `:label` (the conversation's title) and
  `:last-question` a person picks one by. Live means its lock names a running
  process and its socket is there to answer."
  [config]
  (let [dir (persist/sessions-dir config)]
    (->> (when (.isDirectory dir) (.listFiles dir))
         (filter #(.isDirectory %))
         (keep (fn [d]
                 (let [id (.getName d)
                       {:keys [ask-socket-path ops label]} (read-meta config id)]
                   (when ask-socket-path
                     (let [pid (lock-pid config id)
                           snap (let [f (io/file d "session.edn")]
                                  (when (.isFile f) (persist/read-session config id)))]
                       {:session-id id
                        :live? (boolean (and pid (persist/alive? pid) (.exists (io/file ask-socket-path))))
                        :owner-pid pid
                        :ask-socket-path ask-socket-path
                        :ops ops
                        ;; a headless member's given name, else what the
                        ;; conversation came to be called
                        :label (or label (:conversation-title snap))
                        :last-question (:last-question snap)
                        :updated-at (or (:updated-at snap) (.lastModified (io/file d "meta.edn")))})))))
         (sort-by #(- (or (:updated-at %) 0)))
         vec)))

(defn find-session
  "The live session `target` names — its id, a prefix of one id, or its label
  — or `{:error}` saying why there is none. The session asking is never its
  own target. `hint` ends an error that a list would answer: how the asker
  lists sessions — the model and a person do it differently."
  [config target & [hint]]
  (let [target (str/trim (str target))
        mine (:session-id config)
        live (remove #(= mine (:session-id %)) (filter :live? (sessions config)))
        hits (or (seq (filter #(= target (:session-id %)) live))
                 (seq (filter #(= target (:label %)) live))
                 (seq (filter #(str/starts-with? (:session-id %) target) live)))]
    (cond
      (str/blank? target) {:error (str "session is required" hint)}
      (= target mine) {:error "that is this session"}
      (empty? hits) {:error (str "no live session " (pr-str target) hint)}
      (next hits) {:error (str (pr-str target) " names " (count hits) " live sessions: "
                               (str/join ", " (map :session-id hits)))}
      :else (first hits))))

;; --- the model's side ------------------------------------------------------------------

(def ^:private tool-hint " — session$list names the live ones")

(def ^:private usage-keys
  [:input-tokens :output-tokens :llm-calls :cache-read-tokens :cache-write-tokens :cost])

(defn spent!
  "What another session spent answering this one, counted as this session's:
  added to the SESSION's usage — the answer may land after the turn that asked
  has ended — and said as `:session.member/spent` for a front end that totals
  a session's cost. `/usage` and the footer then say what the team cost."
  [ctx session-id {:keys [usage]}]
  (when (seq usage)
    (let [u (select-keys usage usage-keys)]
      (when-let [mem (or (:parent-memory ctx) (:st-memory ctx))]
        (swap! mem update :usage #(merge-with + (or % {}) u)))
      (events/emit! ctx :session.member/spent {:session-id session-id :usage u :cost (:cost u 0.0)}))))

(defn- ask-over
  "One request to the session `target` names, from this one."
  [config target msg timeout-ms]
  (let [s (find-session config target tool-hint)]
    (if (:error s)
      (assoc s :status :error)
      (try (request! (:ask-socket-path s) (assoc msg :from (:session-id config)) timeout-ms)
           (catch Throwable e {:status :error :error (ex-message e)})))))

(def ^:private session-param
  [:session [:string {:desc "a live session's id, a prefix of it, or its label (session$list) — a byj session's, a Claude Code session's as claude:<id>, or a Codex session's as codex:<thread>"}]])

(defn- claude-opts [config] {:home (:claude-home config)})
(defn- codex-opts [config] {:home (:codex-home config)})

(defn- target-of
  "What `session` names among the session tools' targets: `{:byj s}`, a byj
  session reached through its channel; `{:claude s}`, a live Claude Code
  session of this project (`brainyard.claude-reach`, byj-gly.429) — named
  `claude:…`; `{:codex s}`, a live Codex one (`brainyard.codex-reach`,
  byj-gly.430.5) — named `codex:…`; or either by an id or label no byj session
  has; or `{:error}`."
  [config session]
  (cond
    (claude-reach/claude-target? session)
    (let [c (claude-reach/find-session config session (claude-opts config))]
      (if (:error c) c {:claude c}))
    (codex-reach/codex-target? session)
    (let [c (codex-reach/find-session config session (codex-opts config))]
      (if (:error c) c {:codex c}))
    :else
    (let [s (find-session config session tool-hint)]
      (if-not (:error s)
        {:byj s}
        (let [c (claude-reach/find-session config session (claude-opts config))]
          (if-not (:error c)
            {:claude c}
            (let [x (codex-reach/find-session config session (codex-opts config))]
              (if (:error x) s {:codex x}))))))))

(defn commands
  "The session tools (the port's; brainyard's senders are its CLI and scripts):
  find the live sessions of this project, and ask one — waiting for the
  answer, or not. `session$send` does not wait: the reply is a background task
  of this session's (RFC-019), so `task$wakeup` resumes this session when it
  lands, and a leader hands work to several sessions at once.

  `session$start` is offered only with `start-session`, a front end's way to
  start a session beside this one (byj-gly.386): `(fn [{:keys [task]}])` →
  `{:session-id}` or `{:error}`. The full screen has one; a single inline or
  headless session has none, and then there is no such tool."
  ([config] (commands config nil))
  ([config {:keys [start-session]}]
  (let [timeout (:ask-timeout-ms config 120000)]
    (into
    [{:name "session$list" :kind :command
      :description (str "The live sessions of this project other than this one — byj's, Claude Code's as claude:<id>, and Codex's as codex:<thread> — "
                        "their ids, labels and what each last asked. Their ids are what session$ask and session$send take. "
                        "A Claude Code session is reachable only when it runs in tmux: one that is not says why. A Codex session is reachable wherever it runs.")
      :input-schema [:map [:all {:optional true} [:boolean {:desc "include byj sessions no longer live"}]]]
      :output-schema [:map [:sessions [:vector :any]] [:count :int]]
      :effects #{:fs/read}
      :fn (fn [{:keys [all]} _]
            (let [ss (into (->> (sessions config)
                                (remove #(= (:session-id config) (:session-id %)))
                                (filter #(or all (:live? %)))
                                (mapv #(select-keys % [:session-id :live? :label :last-question])))
                           (concat (claude-reach/sessions config (claude-opts config))
                                   (codex-reach/sessions config (codex-opts config))))]
              {:sessions ss :count (count ss)}))}
     {:name "session$status" :kind :command
      :description "Whether a live session is idle or running a turn, how many requests wait for it, and its model."
      :input-schema [:map session-param]
      :output-schema [:map [:status :keyword]]
      :effects #{:fs/read}
      :fn (fn [{:keys [session]} _]
            (let [t (target-of config session)]
              (cond (:error t) (assoc t :status :error)
                    (:claude t) (claude-reach/status (:claude t) (claude-opts config))
                    (:codex t) (codex-reach/status (:codex t) (codex-opts config))
                    :else (ask-over config session {:op :status} 10000))))}
     {:name "session$ask" :kind :command
      :description (str "Ask another live session something and wait for its answer. It runs as a turn of THAT session, "
                        "with its own history and tools, when it is next waiting; this waits up to :timeout-ms ("
                        timeout " by default). To hand work off without waiting, use session$send.")
      :input-schema [:map session-param
                     [:question [:string {:desc "what that session should do or answer — self-contained: it has not seen this conversation"}]]
                     [:timeout-ms {:optional true} [:int {:desc "the longest to wait"}]]]
      :output-schema [:or [:map [:status :keyword] [:answer :string]] [:map [:status :keyword] [:error :string]]]
      :effects #{:session/send}
      :fn (fn [{:keys [session question timeout-ms]} ctx]
            (let [t (target-of config session)]
              (cond
                (:error t) (assoc t :status :error)
                (:claude t) (fx/run-value!! (claude-reach/ask-task config (:claude t) question (or timeout-ms timeout)
                                                                   (claude-opts config)))
                (:codex t) (fx/run-value!! (codex-reach/ask-task config (:codex t) question (or timeout-ms timeout)
                                                                 (codex-opts config)))
                :else (let [r (ask-over config session {:op :ask :question question :timeout-ms (or timeout-ms timeout)}
                                        (+ 5000 (or timeout-ms timeout)))]
                        (spent! ctx session r)
                        r))))}
     {:name "session$send" :kind :command
      :description (str "Hand another live session a task without waiting: it runs as a turn of that session, and its answer "
                        "arrives as a background task of this one — task$wakeup resumes you when it lands, task$wait waits for it.")
      :input-schema [:map session-param
                     [:question [:string {:desc "the task — self-contained: that session has not seen this conversation"}]]]
      :output-schema [:or [:map [:task-id :string] [:session-id :string]] [:map [:error :string]]]
      :effects #{:session/send}
      :fn (fn [{:keys [session question]} ctx]
            (let [t (target-of config session)
                  s (or (:byj t) (:claude t) (:codex t) t)]
              (cond
                (:error s) s
                (str/blank? (str question)) {:error "question is required"}
                (nil? (:task-registry ctx)) {:error "no task registry here — session$ask waits instead"}
                ;; a Claude Code session's turn, typed into its terminal; refused
                ;; at once when nothing can reach it, not as a task that fails
                (and (:claude t) (not (:reachable? s)))
                {:error (str (:session-id s) " cannot be reached: " (:why s)
                             ". Run it inside tmux, or from byj's agent page, to hand it work; or give the task to a new Claude Code session (agent$claude).")}
                (:claude t)
                (let [timeout (:claude-handover-timeout-ms config 1800000)
                      id (tasks/start!
                          ctx {:name (str "hand " (:session-id s) ": " (subs (str question) 0 (min 60 (count (str question)))))
                               :job-type :session-ask
                               :task (claude-reach/ask-task config s question timeout (claude-opts config))})]
                  {:task-id id :session-id (:session-id s)
                   :note "typed into that Claude Code session's terminal; its answer is this task's result: task$wakeup to be resumed when it lands"})
                ;; a Codex session's turn, typed into its tmux or queued (byj-gly.430.5)
                (:codex t)
                (let [timeout (:codex-handover-timeout-ms config 1800000)
                      id (tasks/start!
                          ctx {:name (str "hand " (:session-id s) ": " (subs (str question) 0 (min 60 (count (str question)))))
                               :job-type :session-ask
                               :task (codex-reach/ask-task config s question timeout (codex-opts config))})]
                  {:task-id id :session-id (:session-id s)
                   :note (str (if (= :tmux (:via s)) "typed into that Codex session's terminal" "queued to that Codex session")
                              "; its answer is this task's result: task$wakeup to be resumed when it lands")})
                :else
                (let [id (tasks/start!
                          ctx {:name (str "ask " (:session-id s) ": " (subs (str question) 0 (min 60 (count (str question)))))
                               :job-type :session-ask
                               :task (fx/task-of
                                      (fn [] (let [r (ask-over config (:session-id s)
                                                               {:op :ask :question question :timeout-ms timeout}
                                                               (+ 5000 timeout))]
                                               (spent! ctx (:session-id s) r)
                                               r)))})]
                  {:task-id id :session-id (:session-id s)
                   :note "its answer is this task's result: task$wakeup to be resumed when it lands"}))))}
     {:name "session$cancel" :kind :command
      :description "Cancel the turn another live session is running, whoever started it."
      :input-schema [:map session-param]
      :output-schema [:map [:status :keyword]]
      :effects #{:session/send}
      :fn (fn [{:keys [session]} _]
            (let [t (target-of config session)]
              (cond (:error t) (assoc t :status :error)
                    (:claude t) {:status :error :error (str (:session-id (:claude t)) " is a Claude Code session: its turn is cancelled with Esc in its own terminal")}
                    (:codex t) {:status :error :error (str (:session-id (:codex t)) " is a Codex session: its turn is cancelled with Esc in its own terminal")}
                    :else (ask-over config session {:op :cancel} 10000))))}]
     (when start-session
       [{:name "session$start" :kind :command
         :description (str "Start a new session beside this one, in this window, with :question as its first turn: it carries on there "
                           "on its own, with this project, model and permission mode, and the person can switch to it. "
                           "Returns its id, which session$status and session$send take. For a session already running, use session$send.")
         :input-schema [:map [:question [:string {:desc "its first turn — self-contained: the new session has not seen this conversation"}]]]
         :output-schema [:or [:map [:session-id :string]] [:map [:error :string]]]
         :effects #{:session/send}
         :fn (fn [{:keys [question]} _]
               (if (str/blank? (str question))
                 {:error "question is required: the new session's first turn"}
                 (try (or (not-empty (start-session {:task (str question)}))
                          {:error "the session did not start"})
                      (catch Throwable e {:error (str "the session did not start: " (ex-message e))}))))}])))))

;; --- a member asks its leader --------------------------------------------------------

(def ^:private forwarded-keys
  [:type :tool :display :pattern :args :effects :action :path :paths])

(defn forwarding-permission-fn
  "A headless member's `:permission-fn`: each prompt goes to the session
  `leader` names, whose person answers it at their own gate (`:permission`).
  A leader that is gone, cannot ask, or does not answer within
  `:team-permission-timeout-ms` (five minutes) is a refusal, never a hang."
  [config leader label]
  (let [timeout (:team-permission-timeout-ms config 300000)]
    (fn [req]
      (let [s (find-session (dissoc config :session-id) leader)]
        (if (:error s)
          {:denied true :reason (str "no leader to ask: " (:error s))}
          (let [r (try (request! (:ask-socket-path s)
                                 {:op :permission :from (:session-id config) :from-label label
                                  :request (select-keys req forwarded-keys)}
                                 (+ timeout 5000))
                       (catch Throwable e {:status :error :error (ex-message e)}))]
            (if (:allowed r)
              {:allowed true}
              {:denied true :reason (or (:reason r) (some->> (:error r) (str "the leader could not be asked: "))
                                        "denied by the leader")})))))))

