;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.share
  "The share sidecar's kernel side (RFC-043): the sidecar a session starts with
  `--share TARGET`, and what a session knows of the people watching it.

  Mechanism only. The sidecar (`byj-share`, a Go binary under `share/`) is the
  one that knows of a relay, a browser or WSS; this namespace knows only how to
  start it as a background task of the session (RFC-019) — so it ends with the
  session — what its argv is, and how to read the presence it reports over the
  session channel (`:emit :session.share/presence`). What leaves the machine is
  the sidecar's to decide, never this one's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.tasks :as tasks]
            [jolt.process :as p]))

(def job-type
  "The sidecar's task, as `/task` lists it and `/share stop` finds it."
  :share-sidecar)

(defn target-problem
  "Why `target` is not a share target — `link`, `class:CODE`, `class:CODE/KEY`
  (the class assignment the session works on, RFC-043 model 19; the string
  passes to the sidecar untouched), or the id of an ended share of the
  owner's to reopen (`shr_…`, RFC-043 \"Resuming a share\": `byj run -r ID
  --share shr_…`) — or nil."
  [target]
  (let [t (str/trim (str target))]
    (cond
      (str/blank? t) "--share needs a target: link, class:CODE[/KEY], or an ended share's id (shr_…)"
      (= "link" t) nil
      (re-matches #"class:[A-Za-z0-9][A-Za-z0-9_-]*(/[a-z0-9][a-z0-9-]{0,31})?" t) nil
      (re-matches #"shr_[a-z0-9]{12,}" t) nil
      :else (str "--share " t ": the target is link, class:CODE, class:CODE/KEY (an assignment), or an ended share's id (shr_…)"))))

(defn reopen-id
  "The ended share `target` names to reopen, or nil: a `shr_…` id."
  [target]
  (let [t (str/trim (str target))]
    (when (re-matches #"shr_[a-z0-9]{12,}" t) t)))

(defn resolve-binary
  "The sidecar's executable: `:share-sidecar` (\"byj-share\" by default) as a
  path when it names one, else found on `path` (this process's PATH by
  default). nil when there is none."
  [config & [path]]
  (let [cmd (str (or (not-empty (str (:share-sidecar config))) "byj-share"))]
    (if (str/includes? cmd "/")
      (let [f (io/file cmd)] (when (and (.isFile f) (.canExecute f)) (.getAbsolutePath f)))
      (some (fn [d]
              (let [f (io/file d cmd)]
                (when (and (not (str/blank? d)) (.isFile f) (.canExecute f)) (.getAbsolutePath f))))
            (str/split (str (or path (System/getenv "PATH"))) #":")))))

(defn argv
  "The sidecar's command line: `byj-share run --socket <ask.sock> --target
  <TARGET> --session <id> --relay <url>`, with `--parent` (this process: the
  share ends with it), `--title` when the session has one and
  `--record` when it is recorded (`:share-record?`). A `shr_…` target is an
  ended share of the owner's reopened under its own id: `--target link
  --share-id <id>` (RFC-043 \"Resuming a share\")."
  [{:keys [binary socket target session-id title relay record? parent]}]
  (let [reopen (reopen-id target)]
    (cond-> [binary "run" "--socket" (str socket) "--target" (if reopen "link" (str target)) "--session" (str session-id)]
      reopen (into ["--share-id" reopen])
      parent (into ["--parent" (str parent)])
      (not (str/blank? (str relay))) (into ["--relay" (str relay)])
      (not (str/blank? (str title))) (into ["--title" (str title)])
      record? (conj "--record"))))

(defn ended-why
  "What a sidecar said on its way out, as one line: its last lines that say
  something, joined — `byj-share: …` when it failed — or that it said nothing."
  [lines]
  (if (seq lines) (str/join " · " (map str/trim lines)) "the sidecar ended without a word"))

(defn last-ended
  "This session's last sidecar task that ended on its own — not stopped —
  as `{:task-id :status :why :completed-at}`, or nil (byj-gly.362.4): what
  `/share` and `share$status` say when nothing is shared, so a share that
  never opened says why."
  [{:keys [task-registry]}]
  (when task-registry
    (some->> (vals @task-registry)
             (filter #(and (= job-type (:job-type %)) (:share-why %)))
             (sort-by :completed-at)
             last
             ((fn [t] {:task-id (:id t) :status (:status t) :completed-at (:completed-at t) :why (:share-why t)})))))

(defn running-task
  "The id of this session's running sidecar task, or nil."
  [{:keys [task-registry]}]
  (when task-registry
    (some (fn [[id t]] (when (and (= job-type (:job-type t)) (= :running (:status t))) id))
          @task-registry)))

(declare relay-of)

(defn start!
  "Start the sidecar for the session `ctx` is, sharing its channel at
  `socket` to `target`: a quiet background task of the session, so it ends
  with it. Returns `{:task-id :argv :relay}`, or `{:error}` saying plainly why not —
  the session carries on unshared either way. When the sidecar ends on its
  own — not stopped: a login for another relay, a relay that refused it —
  `:session.share/ended` says so with its last lines, which the session tells
  the person: a quiet task's ending reaches nobody else (byj-gly.362.4)."
  [{:keys [config task-registry] :as ctx} {:keys [target socket title path]}]
  (let [binary (resolve-binary config path)]
    (cond
      (target-problem target) {:error (target-problem target)}
      (nil? task-registry) {:error "not sharing: this session keeps no background tasks"}
      (str/blank? (str socket)) {:error "not sharing: this session has no channel for the sidecar to reach (RFC-031)"}
      (running-task ctx) {:error "already sharing"}
      (nil? binary) {:error (str "not sharing: " (or (not-empty (str (:share-sidecar config))) "byj-share")
                                 " is not on PATH — install it, or set :share-sidecar to the sidecar to run")}
      :else
      (let [task-id (promise)
            relay (relay-of config (:share-home ctx))
            cmd (argv {:binary binary :socket socket :target (str/trim (str target))
                       :session-id (:session-id config) :title title :relay relay
                       :record? (true? (:share-record? config)) :parent (persist/own-pid)})
            id (tasks/start! ctx {:name (str "share " (str/trim (str target)))
                                  :job-type job-type
                                  ;; its ending is the share ending, not a result
                                  :quiet? true
                                  :task-fn (fn [append!]
                                             (let [tail (atom [])
                                                   task (apply fx/streaming-process
                                                               {:dir (or (:working-dir config) ".") :in "" :err :out}
                                                               (fn [line]
                                                                 (when-not (str/blank? line)
                                                                   (swap! tail #(vec (take-last 3 (conj % line)))))
                                                                 (append! line))
                                                               cmd)]
                                               ;; the process settled by itself, unless it was
                                               ;; stopped: a stop — /share stop, the session
                                               ;; closing — comes through the canceller first,
                                               ;; and says nothing. What it said is kept on the
                                               ;; task as :share-why, which its settling keeps
                                               (fn [s f]
                                                 (let [stopped? (atom false)
                                                       cancel (task (fn [r]
                                                                      (when-not @stopped?
                                                                        (let [why (ended-why @tail)]
                                                                          (swap! task-registry assoc-in [@task-id :share-why] why)
                                                                          (try (events/emit! ctx :session.share/ended
                                                                                             {:task-id @task-id :exit (:exit r) :why why})
                                                                               (catch Throwable _ nil))))
                                                                      (s r))
                                                                    f)]
                                                   (fn [] (reset! stopped? true) (cancel))))))})]
        (deliver task-id id)
        (events/emit! ctx :session.share/started {:task-id id :target target :argv cmd})
        {:task-id id :argv cmd :relay relay}))))

(defn stop!
  "Stop the sidecar, if it runs: its process tree goes with its task. True
  when one was running."
  [{:keys [task-registry] :as ctx}]
  (if-let [id (running-task ctx)]
    (boolean (tasks/cancel! task-registry id))
    false))

;; --- presence: what the sidecar reports -------------------------------------------------

(defn- role-of [p] (some-> (:role p) name))

(defn others
  "The participants other than the owner, from a presence payload."
  [presence]
  (vec (remove #(= "owner" (role-of %)) (:participants presence))))

(defn who
  "A participant, or a note's or a turn's `:from`, as a person reads it:
  `Kim (coach)`; a session's id as it is."
  [p]
  (if (map? p)
    (str (or (not-empty (str (:name p))) (not-empty (str (:user p))) "someone")
         (when-let [r (not-empty (role-of p))] (str " (" r ")")))
    (or (not-empty (str p)) "someone")))

(defn footer
  "What the footer says of sharing: how many shares run from this process and
  how many people watch them — `sharing 2 · 1 watching`, or `sharing 2 ·
  paused` while this session's own share is paused; nil when nothing is
  shared. `presence` is this session's share as its sidecar last reported it;
  `shares` are the shares this process started (`from-here`), its own among
  them once byj-share has registered it, which is counted once. The count
  was this session's viewers alone, under the word `sharing`: two shares
  open and nobody watching read `sharing · 0`, and a share started by
  reference was never counted at all."
  [presence & [shares]]
  (let [besides (remove #(and presence (= (str (:share presence)) (str (:share %)))) shares)
        n (+ (if presence 1 0) (count besides))
        watching (+ (count (others presence)) (reduce + 0 (map (comp count :watching) besides)))]
    (when (pos? n)
      (str "sharing " n " · " (if (:paused presence) "paused" (str watching " watching"))))))

(defn url-line
  "The line that says where the share is, when presence `after` brings a URL
  that `before` did not have — the first one the relay answers with, or a new
  one after a reconnect; nil otherwise, so the same presence again says
  nothing (byj-1wv.7)."
  [before after]
  (let [u (not-empty (str (:url after)))]
    (when (and u (not= u (str (:url before))))
      (str "sharing at " u " — /share says who is watching"))))

(defn changes
  "Who joined and who left between presence `before` and `after`, as lines a
  person reads: `Kim (coach) joined the share`."
  [before after]
  (let [ids #(into {} (map (juxt (fn [p] (str (or (:user p) (:name p)))) identity)) (others %))
        b (ids before) a (ids after)]
    (vec (concat (for [[k p] a :when (not (contains? b k))] (str (who p) " joined the share"))
                 (for [[k p] b :when (not (contains? a k))] (str (who p) " left the share"))))))

(defn report
  "`/share` with no argument: where the share stands."
  [presence running?]
  (cond
    (and (nil? presence) (not running?))
    "not sharing — start a session with --share link (or --share class:CODE)"
    (nil? presence)
    "sharing: the sidecar is starting — nobody has joined yet"
    :else
    (str/join "\n"
              (concat [(str "sharing" (when (:paused presence) " (paused)")
                            (when (:recording presence) " (recorded)")
                            (when-let [u (not-empty (str (:url presence)))] (str " at " u)))]
                      (let [ps (:participants presence)]
                        (if (seq ps)
                          (for [p ps] (str "  " (who p) (when-let [u (:user p)] (str "  " u))))
                          ["  nobody yet"]))
                      ["/share pause · resume · kick USER · record on|off · stop"]))))

(defn inline-approver
  "An injected turn's approval where there is no overlay to ask in — the
  inline terminal session (byj-1wv.3.12). The request is said with `say`,
  and the person answers at the prompt they already have: `/share allow` or
  `/share refuse` (`:answer!`). Nobody answering by `:timeout-ms` is a
  refusal, as the overlay's is. One request at a time: a second waits for the
  first, inside its own deadline.

  Returns `{:approve (fn [{:keys [who text timeout-ms]}]) :answer! (fn
  [yes?])}` — `:approve` for `session/run`'s `:approve`, `:answer!` for its
  `:approve-answer`."
  [say]
  (let [pending (atom nil)]
    {:approve (fn [{:keys [who text timeout-ms]}]
                (let [ms (or timeout-ms 120000)
                      deadline (+ (System/currentTimeMillis) ms)
                      p (promise)
                      took? (loop []
                              (cond (compare-and-set! pending nil p) true
                                    (>= (System/currentTimeMillis) deadline) false
                                    :else (do (Thread/sleep 50) (recur))))]
                  (if-not took?
                    :expired
                    (let [t (str/trim (str text))]
                      (say (str "\n⇠ " who " wants to run: "
                                (if (> (count t) 300) (str (subs t 0 300) "…") t)
                                "\n  /share allow or /share refuse within "
                                (quot (max 0 (- deadline (System/currentTimeMillis))) 1000)
                                " s — otherwise it is refused"))
                      (let [r (deref p (max 0 (- deadline (System/currentTimeMillis))) ::expired)]
                        (compare-and-set! pending p nil)
                        (if (= ::expired r)
                          ;; answered as the deadline passed: the answer stands
                          (let [late (deref p 0 ::expired)]
                            (if (= ::expired late)
                              (do (say "⇠ the injected turn was refused: nobody answered in time") :expired)
                              late))
                          r))))))
     :answer! (fn [yes?]
                (if-let [p @pending]
                  (do (deliver p (if yes? :approved :rejected))
                      (if yes? "allowed — it runs as your next turn" "refused — it will not run"))
                  "nothing is waiting for your approval"))}))

;; --- any session, shared by reference (RFC-043, byj-1wv.8.4) -------------------------------

(defn ref-argv
  "The sidecar's command line for a session named by its reference (RFC-043
  `<kind>:<id>`): `byj-share run --session REF --parent PID`, with `--dir`,
  `--relay`, `--title`, `--target` and `--record` when given, and a
  terminal's `--no-input` and `--chat=false` or an agent's `--max-messages`
  and `--max-cost` (byj-gly.362.2). `--parent` is this process: the share
  ends when it does, however it ends."
  [{:keys [binary ref parent dir relay title target record? no-input? chat? max-messages max-cost]}]
  (cond-> [binary "run" "--session" (str ref)]
    parent (into ["--parent" (str parent)])
    (not (str/blank? (str dir))) (into ["--dir" (str dir)])
    (not (str/blank? (str relay))) (into ["--relay" (str relay)])
    (not (str/blank? (str title))) (into ["--title" (str title)])
    (not (str/blank? (str target))) (into ["--target" (str target)])
    record? (conj "--record")
    no-input? (conj "--no-input")
    (false? chat?) (conj "--chat=false")
    max-messages (into ["--max-messages" (str max-messages)])
    max-cost (into ["--max-cost" (str max-cost)])))

(defn ready-in
  "The share's id and links from the line `byj-share run` prints on stdout
  once the relay has it open — `{\"share\" … \"links\" {\"share\" \"coach\"
  \"spectator\"}}` — as `{:share :page :coach :spectator}`, or nil before it
  has."
  [text]
  (some (fn [line]
          (when (str/starts-with? (str/trim line) "{\"share\"")
            (when-let [m (try (json/read-str line) (catch Throwable _ nil))]
              (let [l (:links m)]
                {:share (:share m) :page (not-empty (str (:share l)))
                 :coach (not-empty (str (:coach l))) :spectator (not-empty (str (:spectator l)))}))))
        (str/split-lines (str text))))

(defn links-in
  "The share's page and invite links from what a byj-share older than its
  ready line printed on stderr — or nil before it has."
  [text]
  (let [grab (fn [label] (some-> (re-find (re-pattern (str "(?m)^\\s*" label ":\\s+(\\S+)")) (str text)) second))]
    (when-let [page (grab "page")]
      {:page page :coach (grab "coach link") :spectator (grab "watch link")})))

(defn share-home
  "byj-share's own directory: `BYJ_SHARE_HOME`, else `~/.brainyard/share` —
  the `brainyard.share-home` property before either, the suite's empty one,
  so no test reads the person's own login (as `brainyard.codex-home`)."
  []
  (or (not-empty (System/getProperty "brainyard.share-home"))
      (not-empty (System/getenv "BYJ_SHARE_HOME"))
      (str (System/getProperty "user.home") "/.brainyard/share")))

(defn login-state
  "Who byj-share is logged in as on this machine, from its credentials file —
  `{:relay :email}` — or nil (byj-gly.362.6). Read, never asked of a relay,
  so `/share` costs nothing to say it."
  [& [home]]
  (let [f (io/file (or home (share-home)) "credentials")]
    (when (.isFile f)
      (try (let [c (json/read-str (slurp f))]
             (when (not-empty (str (:device_token c)))
               {:relay (str (:relay c)) :email (str (get-in c [:user :email]))}))
           (catch Throwable _ nil)))))

(defn relay-of
  "The relay a session shares through: `:share-relay-url` when it is set,
  else the one byj-share is logged in to (`home`'s, else this machine's),
  else nil — no `--relay`, and the sidecar dials its own default: byj names
  no relay (RFC-070, byj-ahf.7). A class's student logged in to their
  class's relay shares there with the command their class page gives;
  handed another relay whatever the login, the sidecar refused them, and
  told them to log in to it instead (byj-1wv.134)."
  [config & [home]]
  (or (not-empty (str/trim (str (:share-relay-url config))))
      (not-empty (:relay (login-state home)))))

(defn relay-said
  "`relay` as a person reads it: the URL, or, with none, the sidecar's own."
  [relay]
  (or (not-empty (str relay)) "the sidecar's own relay"))

(def ^:private login-job "share-login")

(defn login!
  "Log byj-share in to `relay` (byj-gly.362.5): `byj-share login [--relay
  RELAY]` — with no relay, the sidecar's own — as a quiet background task of the session, whose lines are kept on
  the task as `:login-lines` as they come — the link and the code to approve
  it with come at once, the approval whenever the person gives it. When it
  ends, `:session.share/login-ended {:task-id :ok :why}` says how. One at a
  time: while one runs, it is returned rather than a second started, so its
  code stays the one to approve. Returns `{:task-id}` or `{:error}`."
  [{:keys [config task-registry] :as ctx} relay & [{:keys [path]}]]
  (let [binary (resolve-binary config path)
        running (when task-registry
                  (some (fn [[id t]] (when (and (= login-job (:job-type t)) (= :running (:status t))) id)) @task-registry))]
    (cond
      (nil? task-registry) {:error "this session keeps no background tasks"}
      (nil? binary) {:error (str (or (not-empty (str (:share-sidecar config))) "byj-share")
                                 " is not on PATH — install it, or set :share-sidecar to the sidecar to run")}
      running {:task-id running :again? true}
      :else
      (let [task-id (promise)
            cmd (cond-> [binary "login"] (not-empty (str relay)) (into ["--relay" (str relay)]))
            id (tasks/start! ctx {:name (str/trim (str "byj-share login " relay))
                                  :job-type login-job
                                  :quiet? true
                                  :task-fn (fn [append!]
                                             (let [task (apply fx/streaming-process
                                                               {:dir (or (:working-dir config) ".") :in "" :err :out}
                                                         (fn [line]
                                                           (when-not (str/blank? line)
                                                             (swap! task-registry update-in [@task-id :login-lines] (fnil conj []) line))
                                                           (append! line))
                                                         cmd)]
                                               (fn [s f]
                                                 (let [stopped? (atom false)
                                                       cancel (task (fn [r]
                                                                      (when-not @stopped?
                                                                        (let [lines (get-in @task-registry [@task-id :login-lines])]
                                                                          (try (events/emit! ctx :session.share/login-ended
                                                                                             {:task-id @task-id :ok (zero? (:exit r 1))
                                                                                              :why (ended-why (take-last 2 lines))})
                                                                               (catch Throwable _ nil))))
                                                                      (s r))
                                                                    f)]
                                                   (fn [] (reset! stopped? true) (cancel))))))})]
        (deliver task-id id)
        {:task-id id}))))

(defn active
  "This machine's running shares, from byj-share's registry (RFC-043 model 6):
  each `<home>/active/<share>.json` whose process is alive — `{:share :ref
  :kind :label :pid :parent :target :links :watching :paused}` — oldest
  first."
  [& [home]]
  (->> (or (.listFiles (io/file (or home (share-home)) "active")) [])
       (filter #(let [n (.getName ^java.io.File %)] (and (str/starts-with? n "shr_") (str/ends-with? n ".json"))))
       (keep #(try (json/read-str (slurp %)) (catch Throwable _ nil)))
       (filter #(persist/alive? (str (:pid %))))
       (sort-by #(str (:started %)))
       vec))

(def ^:private this-pid
  ;; asked of the shell, so asked once: the footer reads `from-here` often
  (delay (persist/own-pid)))

(defn from-here
  "The running shares this process started — by reference, as a group, or
  this session's own sidecar — from byj-share's registry (`active`): those
  whose `:parent` is this process. `/share` lists them, and the footer
  counts them. `pid` is a test's."
  [& [{:keys [home pid]}]]
  (let [me (str (or pid @this-pid))]
    (filterv #(= me (str (:parent %))) (active home))))

(defn sharer
  "Sharing sessions by reference from this process — the agent page's ctrl+s
  and `/share start`: one `byj-share run` per session, owned by this process,
  which stops them all on its way out (`:stop-all!`) and which they watch
  besides (`--parent`), so none outlives it.

  `{:toggle! (fn [ref title on-note & [opts]]) :start! (fn [ref title on-note
  & [opts]]) :stop! (fn [ref]) :shared? (fn [ref]) :stop-all! (fn [])}`, a
  ref being any byj-share takes (`byj:<id>`, `claude:<id>`, `term:<name>`,
  `agent:<name>` …). `start!` says what it did; once the relay has the share
  open, `on-note` is told its links. `opts` are `{:target :record? :dir}`,
  and a terminal's `:no-input?` and `:chat?` or an agent's `:max-messages`
  and `:max-cost` (byj-gly.362.2). `start` (a test's) starts the process,
  `fx/start`'s signature."
  [config & [{:keys [path start log-dir] :or {start fx/start}}]]
  (let [running (atom {})                 ; ref → {:proc :log}
        alive? (fn [proc] (try (p/alive? proc) (catch Throwable _ false)))
        stop! (fn [ref]
                (when-let [{:keys [proc]} (get @running ref)]
                  (swap! running dissoc ref)
                  (try (p/destroy-tree proc) (catch Throwable _ nil))
                  true))
        shared? (fn [ref] (boolean (some-> (get @running ref) :proc alive?)))
        start! (fn [ref title on-note & [{:keys [target record? dir] :as opts}]]
                 (cond
                   (shared? ref) (str title " is already shared")
                   :else
                   (if-let [binary (resolve-binary config path)]
                     (let [id (last (str/split (str ref) #":" 2))
                           log (io/file (or log-dir (System/getProperty "java.io.tmpdir"))
                                        (str "byj-share-" (subs id 0 (min 8 (count id))) "-" (System/currentTimeMillis) ".log"))
                           argv (ref-argv (merge (select-keys opts [:no-input? :chat? :max-messages :max-cost])
                                                 {:binary binary :ref ref :title title :parent (persist/own-pid)
                                                  :dir (or dir (:working-dir config)) :relay (relay-of config)
                                                  :target target :record? record?}))
                           proc (start {:out :string :err :string}
                                       "sh" "-c" (str "exec " (str/join " " (map #(str "'" (str/replace (str %) "'" "'\\''") "'") argv))
                                                      " >> '" (.getPath log) "' 2>&1"))]
                       (swap! running assoc ref {:proc proc :log log})
                       ;; the links come once the relay has the share open
                       (.start (Thread. (fn []
                                          (loop [i 0]
                                            (let [text (try (slurp log) (catch Throwable _ ""))
                                                  links (or (ready-in text) (links-in text))]
                                              (cond
                                                links
                                                (let [{:keys [page coach spectator]} links]
                                                  (on-note (str "sharing " title " at " page
                                                                (when coach (str " · coach " coach))
                                                                (when spectator (str " · watch " spectator)))))
                                                (not (alive? proc))
                                                (do (swap! running dissoc ref)
                                                    (on-note (str "sharing " title " stopped: "
                                                                  (or (last (remove str/blank? (str/split-lines text))) "the sidecar ended"))))
                                                (< i 300) (do (Thread/sleep 100) (recur (inc i)))))))))
                       (str "sharing " title " — opening the share…"))
                     (str "not sharing: " (or (not-empty (str (:share-sidecar config))) "byj-share")
                          " is not on PATH — install it, or set :share-sidecar to the sidecar to run"))))]
    {:shared? shared?
     :start! start!
     :stop! stop!
     :stop-all! (fn [] (doseq [ref (keys @running)] (stop! ref)))
     :toggle! (fn [ref title on-note & [opts]]
                (if (shared? ref)
                  (do (stop! ref) (str "stopped sharing " title))
                  (start! ref title on-note opts)))}))

(defn group-argv
  "`byj-share start --json --parent PID [--group TITLE] [--target T]
  [--record] REF…`: several sessions shared as one group (RFC-043), ending
  with this process."
  [{:keys [binary refs parent title target record?]}]
  (cond-> [binary "start" "--json" "--parent" (str parent)]
    ;; an agent among them was confirmed in the session already
    ;; (share-tools/confirm-agents, byj-1wv.47): byj-share asks no one
    (some #(str/starts-with? (str %) "agent:") refs) (conj "--yes")
    (not (str/blank? (str title))) (into ["--group" (str title)])
    (not (str/blank? (str target))) (into ["--target" (str target)])
    record? (conj "--record")
    true (into (map str refs))))

(defn group-report
  "What `byj-share start --json` printed, as lines a person reads: each
  share's session and link, then the group's link and invites."
  [out]
  (let [rows (keep #(try (json/read-str %) (catch Throwable _ nil)) (str/split-lines (str out)))
        shares (filter :share rows)
        group (first (filter :group rows))]
    (vec (concat (for [e shares] (str "sharing " (or (not-empty (str (:label e))) (:ref e)) " at " (get-in e [:links :share])))
                 (when group
                   (remove nil? [(str "as one group: " (:url group))
                                 (when-let [c (not-empty (str (:coach group)))] (str "  coach link: " c))
                                 (str "  watch link: " (:spectator group))]))))))

(defn start-group!
  "Several sessions shared as one group (RFC-043), through
  `byj-share start`, off this thread: `say` is told each share's link and the
  group's — or why not. Returns what to say now."
  [config refs {:keys [title target record?]} say & [{:keys [path]}]]
  (if-let [binary (resolve-binary config path)]
    (let [argv (group-argv {:binary binary :refs refs :parent (persist/own-pid) :title title
                            :target target :record? record?})]
      (.start (Thread. (fn []
                         (let [r (try (apply fx/sh {:out :string :err :string :dir (or (:working-dir config) ".")} argv)
                                      (catch Throwable e {:exit 1 :err (ex-message e)}))]
                           (if (zero? (:exit r))
                             (doseq [l (group-report (:out r))] (say l))
                             (say (str "not sharing them: " (str/trim (str (:err r) (:out r))))))))))
      (str "sharing " (count refs) " sessions as one group — opening the shares…"))
    (str "not sharing: " (or (not-empty (str (:share-sidecar config))) "byj-share")
         " is not on PATH — install it, or set :share-sidecar to the sidecar to run")))

(defonce ^:private sharers (atom {}))

(defn process-sharer
  "This process's `sharer` for a sidecar, relay and project: the agent page's
  ctrl+s and every session's `/share start` share through it, so each sees
  what the other shared."
  [config]
  (let [k (select-keys config [:share-sidecar :share-relay-url :working-dir])]
    (or (get @sharers k)
        (get (swap! sharers #(if (contains? % k) % (assoc % k (sharer config)))) k))))
