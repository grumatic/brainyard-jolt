;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.permission
  "The tool permission gate (RFC-012) — brainyard's
  `agent/common/tool_permission.clj`, as two built-in hooks on the gated
  `:agent.tool-use/pre` event (RFC-011):

    tool-deny-gate        priority 95   :tool-deny-tools, :tool-deny-effects
    path-permission-gate  priority 90   :allowed-dirs (brainyard's write/read path check)
    tool-permission-gate  priority 85   :tool-approval-patterns, :tool-ask-effects

  Both ship inert: with no patterns configured they read one config key and
  abstain, so the default agent behaves exactly as before.

  A refusal is `:replace`, never `:block` — the model reads `{:error …}` and the
  turn goes on (a block would end it, which is the wrong shape for \"ask again
  with permission\"). An allow is an abstention, not a licence: it only means
  this gate has nothing to say, and a later gate may still refuse."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.configbox :as configbox]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.toolbox :as toolbox]))

;; --- matching ---------------------------------------------------------------------

(defn glob->re
  "brainyard's `util.core.glob/glob->re`: only `*` is special (any run of
  characters); everything else is literal. Whole-name, case-sensitive."
  [pat]
  (re-pattern (str "^" (->> (str/split (str pat) #"\*" -1)
                            (map #(str/replace % #"([.\\+?^$\[\](){}|/-])" "\\\\$1"))
                            (str/join ".*"))
                   "$")))

(defn glob-match? [glob target]
  (boolean (re-matches (glob->re glob) (str target))))

(defn first-match
  "The first glob in `globs` matching `target` — the pattern, because it is what
  an approval is remembered under."
  [globs target]
  (first (filter #(glob-match? % target) globs)))

;; --- mode -------------------------------------------------------------------------

(defn container?
  "brainyard's `container-detected?`, in the small: an explicit BY_CONTAINER, or
  Docker's marker file."
  []
  (or (= "1" (System/getenv "BY_CONTAINER"))
      (.exists (io/file "/.dockerenv"))))

(defn resolve-mode
  "brainyard's `resolve-permission-mode`: :auto means approve in a container and
  ask outside one; an unknown mode asks. Where :auto asks, the auto-mode
  classifier decides first (`ask`, byj-gly.285)."
  [mode]
  (case mode
    :auto (if (container?) :auto-approve :ask-each-time)
    (:auto-approve :ask-each-time :deny-by-default) mode
    :ask-each-time))

;; --- refusals ---------------------------------------------------------------------

(def approval-hint
  "To allow it: approve interactively, add a matching :tool-allow-tools glob, or set :permission-mode :auto-approve.")

(def deny-hint
  "This tool is denied by :tool-deny-tools. No permission mode overrides it — narrow or remove the matching glob to allow it.")

(defn deny-replace
  "brainyard's `deny-replace`: the refusal the model reads, as a tool result."
  [{:keys [by display reason reason-prefix error-prefix hint]}]
  {:result :replace
   :by by
   :reason (str reason-prefix ": " display " — " reason)
   :replacement {:error (str error-prefix ": " display ". " reason ". " hint)}})

(declare mode config command-segments program-of)

(defn verdict-key
  "What a classifier verdict on `request` is remembered under for the session
  (byj-gly.285.2): the turn's `question` — the same call can be right for one
  request and wrong for another — the kind of request, its tool, and what the
  person's prompt would remember an answer by: a path's parent directory, a
  command's program, an approval's pattern."
  [request question]
  (let [{:keys [type action tool path command pattern args]} request
        cmd (or command (:command args))]
    [question type action tool pattern
     (some-> path io/file .getParent)
     ;; a command asked about for what it does is remembered by its whole
     ;; line: `rm ./build/tmp` allowed must not let `rm -rf ./src` through
     ;; (byj-gly.285.4); otherwise by its program
     (if (= :command type) (some-> cmd str str/trim) (some-> cmd str command-segments first program-of))]))

(def classifier-hint
  "What a call the auto-mode classifier blocked says about letting one through:
  the person decides, not a turn — there is no override at the block itself."
  "To let such a call run: shift+tab to ask-each-time and answer it yourself, /allow-path DIR for a directory, or a :tool-allow-tools glob.")

(defn- classify
  "The auto-mode classifier's verdict on `request`, or nil when it does not
  run: it runs only when the mode in force is `:auto` (outside a container,
  where `:auto` would ask), `:auto-mode-classifier?` is on and the ctx carries
  `:auto-classify` (byj-gly.285.1). Anything but allow or block — and a
  classifier that throws — is `:unsure`; a cancelled turn is not swallowed."
  [ctx request]
  (when (and (= :auto (mode ctx)) (config ctx :auto-mode-classifier? true))
    (when-let [f (:auto-classify ctx)]
      ;; an allow or a block is remembered for the session, so the same call
      ;; for the same request costs one model call (byj-gly.285.2); an unsure
      ;; one is not — the person was asked, and may be asked about it again
      (let [cache (:classifier-verdicts ctx)
            k (verdict-key request (str (some-> (:st-memory ctx) deref :question)))
            t0 (System/currentTimeMillis)
            r (if-let [hit (some-> cache deref (get k))]
                (assoc hit :cached? true)
                (let [r (try
                          (let [{:keys [verdict] :as r} (f request ctx)]
                            (if (#{:allow :block} verdict) r (assoc r :verdict :unsure)))
                          (catch Throwable e
                            (if (fx/cancelled? e)
                              (throw e)
                              {:verdict :unsure :reason (str "the classifier failed: " (ex-message e))})))]
                  (when (and cache (#{:allow :block} (:verdict r)))
                    (swap! cache assoc k (select-keys r [:verdict :reason])))
                  r))]
        (events/emit! ctx :permission/classified
                      {:verdict (:verdict r) :reason (:reason r) :tool (:tool request)
                       :display (:display request) :type (:type request)
                       :cached? (boolean (:cached? r)) :ms (- (System/currentTimeMillis) t0)})
        r))))

(defonce ^:private ask-counter (atom 0))

(defn- ask-summary
  "What a person is asked about, in a line: the prompt's own display, an
  access's action in front — what `:permission/asked` carries to a watcher
  (RFC-043), who sees it and cannot answer it."
  [{:keys [type action display]}]
  (str (when (and (= :file-access type) action) (str (name action) " ")) display))

(defn- ask
  "What a gate does where it would ask the person about `request`. In `:auto`,
  the classifier decides first: allow lets the call run, block refuses it with
  the classifier's reason and how a person lets such a call through, unsure
  asks as before — or refuses, with `:auto-mode-on-unsure :deny`. Then
  `:permission-fn`, refusing with `refuse` (fn [reason]) when it denies or there
  is none (`headless`: headless is fail-closed). A request handed to the person
  is said as `:permission/asked {:id :tool :summary}`, and its answer as
  `:permission/decided {:id :tool :decision}` — read only, for a watcher on
  the session channel (RFC-043): nothing answers a prompt from there."
  [ctx request refuse headless]
  (let [{:keys [verdict reason]} (classify ctx request)]
    (cond
      (= :allow verdict) nil
      (= :block verdict) (refuse (str "blocked by auto mode: " reason ". " classifier-hint))
      (and (= :unsure verdict) (= :deny (config ctx :auto-mode-on-unsure :ask)))
      (refuse (str "the auto-mode classifier was unsure (" reason ") and :auto-mode-on-unsure is :deny"))
      :else
      (if-let [pfn (:permission-fn ctx)]
        (let [id (str "perm-" (swap! ask-counter inc))
              tool (some-> (or (:tool request) (some-> (:action request) name)) str)
              _ (events/emit! ctx :permission/asked {:id id :tool tool :summary (ask-summary request)})
              resp (try (pfn request)
                        (catch Throwable e
                          {:denied true :reason (str "permission prompt error: " (ex-message e))}))]
          (events/emit! ctx :permission/decided {:id id :tool tool :decision (if (:allowed resp) :allow :deny)})
          (when-not (:allowed resp)
            (refuse (or (:reason resp) "denied by user"))))
        (refuse headless)))))

(defn- refuse-approval [display reason]
  (deny-replace {:by "tool-permission-gate" :display display :reason reason
                 :reason-prefix "Tool permission refused"
                 :error-prefix "Tool call refused (permission)"
                 :hint approval-hint}))

;; --- the gates --------------------------------------------------------------------

(defn- effects-of [ctx tool-name]
  (when-let [reg (:tools ctx)]
    (when (get @reg tool-name) (toolbox/effects reg tool-name))))

(defn- config [ctx k default] (get-in ctx [:config k] default))

(defn allowed-dirs
  "The directories a path may be under: `:allowed-dirs` (unset: brainyard's
  defaults), and those a person added for this session with `/allow-path`
  (`:allowed-dirs-box`, byj-gly.145) — read at every gate, as the mode is."
  [ctx working-dir]
  (vec (distinct (concat (or (seq (config ctx :allowed-dirs nil)) (config/default-allowed-dirs working-dir))
                         (some-> (:allowed-dirs-box ctx) deref)))))

(defn mode
  "The permission mode in force: what a person last chose in the front end
  (`:permission-mode-box`, which shift+tab cycles), else the config's. Read at
  every gate, so a change reaches the next tool call — mid-turn included."
  [ctx]
  (or (some-> (:permission-mode-box ctx) deref)
      (config ctx :permission-mode :auto)))

(def cycle-modes
  "The modes shift+tab steps through, in order. `:deny-by-default` is not one:
  it is a policy a config sets, not a posture a person toggles into."
  [:auto :auto-approve :ask-each-time])

(defn next-mode
  "The mode after `m` in `cycle-modes`; the first for one outside it."
  [m]
  (let [i (or (first (keep-indexed #(when (= m %2) %1) cycle-modes)) -1)]
    (nth cycle-modes (mod (inc i) (count cycle-modes)))))

(defn deny-gate
  "Priority 95. A tool matching `:tool-deny-tools`, or with an effect in
  `:tool-deny-effects`, is refused whatever the mode and whatever
  `:tool-allow-tools` says — brainyard's `::tool-deny-gate`, plus the effect
  rule (divergence `permission-by-effects`)."
  [{:keys [tool-name]} ctx _opts]
  (let [deny (config ctx :tool-deny-tools [])
        deny-fx (config ctx :tool-deny-effects #{})
        pattern (when (seq deny) (first-match deny tool-name))
        effect (when (seq deny-fx) (first (sort (filter (set deny-fx) (effects-of ctx tool-name)))))]
    (when (or pattern effect)
      (deny-replace {:by "tool-deny-gate" :display tool-name
                     :reason (if pattern
                               (str "denied by :tool-deny-tools pattern " (pr-str pattern))
                               (str "denied by :tool-deny-effects " effect))
                     :reason-prefix "Tool permission refused"
                     :error-prefix "Tool call refused (denied)"
                     :hint deny-hint}))))

(defn permission-gate
  "Priority 85. A tool matching `:tool-approval-patterns` (and not
  `:tool-allow-tools`), or with an effect in `:tool-ask-effects`, needs
  permission — brainyard's `::tool-permission-gate` with `gate-verdict`:

  - `:auto-approve` abstains;
  - `:deny-by-default` refuses;
  - `:ask-each-time` asks ctx's `:permission-fn` — `(fn [request])` →
    `{:allowed true}` or `{:denied true :reason …}`; a throwing fn refuses with
    its message, and no fn at all refuses (headless is fail-closed)."
  [{:keys [tool-name args]} ctx _opts]
  (let [approve (config ctx :tool-approval-patterns [])
        ask-fx (config ctx :tool-ask-effects #{})
        allow (config ctx :tool-allow-tools [])]
    (when-not (and (seq allow) (first-match allow tool-name))
      (let [pattern (when (seq approve) (first-match approve tool-name))
            effect (when (seq ask-fx) (first (sort (filter (set ask-fx) (effects-of ctx tool-name)))))]
        (when (or pattern effect)
          (case (resolve-mode (mode ctx))
            :auto-approve nil
            :deny-by-default (refuse-approval tool-name "permission mode is :deny-by-default")
            (ask ctx {:type :tool-use :action :call :tool tool-name
                      :pattern (or pattern effect) :display tool-name
                      ;; brainyard sends the name alone; a human
                      ;; deciding about `bash` needs the command
                      :args args :effects (effects-of ctx tool-name)}
                 #(refuse-approval tool-name %)
                 "no interactive permission channel (headless)")))))))

(defn tool-verdict
  "What a call of `tool-name` meets at `deny-gate` and `permission-gate` now,
  before any argument is known — what `/tools` shows beside a tool
  (byj-gly.273.1): `{:verdict :deny|:ask|:allow :why}`. The same rules, asked
  without calling anything. The path and command gates look at the arguments,
  so a call can still be asked about or refused there; the verdict says so."
  [tool-name ctx]
  (let [deny (config ctx :tool-deny-tools [])
        deny-fx (config ctx :tool-deny-effects #{})
        fx (effects-of ctx tool-name)
        denied (or (when (seq deny) (some->> (first-match deny tool-name) pr-str (str ":tool-deny-tools ")))
                   (when (seq deny-fx) (some->> (first (sort (filter (set deny-fx) fx))) (str ":tool-deny-effects "))))
        allowed (let [allow (config ctx :tool-allow-tools [])] (and (seq allow) (first-match allow tool-name)))
        asked (when-not allowed
                (or (let [approve (config ctx :tool-approval-patterns [])]
                      (when (seq approve) (some->> (first-match approve tool-name) pr-str (str ":tool-approval-patterns "))))
                    (let [ask-fx (config ctx :tool-ask-effects #{})]
                      (when (seq ask-fx) (some->> (first (sort (filter (set ask-fx) fx))) (str ":tool-ask-effects "))))))
        m (resolve-mode (mode ctx))]
    (cond
      denied {:verdict :deny :why (str "refused by " denied)}
      (not asked) {:verdict :allow :why (if allowed "allowed by :tool-allow-tools" "no rule asks about it")}
      (= :auto-approve m) {:verdict :allow :why (str asked " — approved: the mode is :auto-approve")}
      (= :deny-by-default m) {:verdict :deny :why (str asked " — refused: the mode is :deny-by-default")}
      :else {:verdict :ask :why (str "asks first: " asked)})))

;; --- paths ------------------------------------------------------------------------

(defn- canonical [f]
  (try (.getCanonicalPath (io/file f)) (catch Throwable _ (.getAbsolutePath (io/file f)))))

(defn under?
  "Is `path` `dir` itself or inside it? Canonical and segment-aware, so /tmpfoo
  is not under /tmp — brainyard compares raw string prefixes and lets it
  through (divergence `path-match-by-segment`). The root is its own prefix:
  every path is under /, which `:allowed-dirs [\"/\"]` means (byj-gly.466)."
  [dir path]
  (let [d (canonical dir) p (canonical path)]
    (or (= d p) (str/starts-with? p (if (str/ends-with? d "/") d (str d "/"))))))

(defn call-paths
  "The paths a call names: the args of the tool's `:path-params`, resolved
  against the working dir. A user tool has none of its own — the primitives it
  calls are gated as they run."
  [tool args working-dir]
  (for [k (:path-params tool)
        :let [v (get args k)]
        :when (string? v)]
    (if (.isAbsolute (io/file v)) v (str (io/file (or working-dir ".") v)))))

(defn denied-path
  "The `:denied-paths` entry a call's `paths` reach, or nil: a path under one,
  or a directory holding one, which a recursive read would open
  (byj-1wv.115.40). `~` is `home`."
  [denied paths home]
  (let [expand #(if (and home (or (= "~" %) (str/starts-with? % "~/"))) (str home (subs % 1)) %)]
    (first (for [d denied
                 :let [full (expand (str d))]
                 p paths
                 :when (or (under? full p) (under? p full))]
             d))))

(defn home-paths
  "The paths a shell `command` names from `~`, with `~` as `home`: a bare `~`
  or one starting `~/`, at the start of a word. A `~` before anything else --
  `~0.4 ms` in a commit message, `~user`, `a~/b` -- names nothing of the
  person's home; read as a bare `~` it was the home itself, which holds every
  :denied-paths entry, and refused the command (provenance P-52)."
  [command home]
  (for [[_ p] (re-seq #"(?:^|[\s\"'=])(~[^\s\"'>;|&]*)" (str command))
        :when (or (= "~" p) (str/starts-with? p "~/"))]
    (str home (subs p 1))))

(defn loop-file
  "The project loop a write to `path` would make live or take away, or nil:
  a file `<name>.edn` directly in `<project-dir>/.brainyard/loops` — a loop
  `agent/resolve-tree` finds before the bundled one, or `history.edn` — or
  that directory itself. `project-loops/loops-dir` says where it is; this
  namespace cannot require that one, which `hookbox` would reach through
  `flow`. What is under it in a directory of its own — `tasks/`,
  `proposals/` — makes no loop live (RFC-071, byj-ng7.12)."
  [project-dir path]
  (when (and project-dir (not (str/blank? (str project-dir))) path)
    (let [dir (canonical (io/file (str project-dir) ".brainyard" "loops"))
          p (canonical path)]
      (when (or (= dir p)
                (and (str/ends-with? p ".edn")
                     (= dir (some-> (io/file p) .getParentFile .getPath))))
        p))))

(defn named-loop-files
  "The project loop files a shell `command` names: `.brainyard/loops/<name>.edn`
  as written in it, relative or absolute. A tripwire, as `:denied-paths` in a
  command is: a shell reaches a file by other spellings (RFC-071, byj-ng7.12)."
  [command]
  (vec (distinct (map second (re-seq #"(\.brainyard/loops/[\w-]+\.edn)(?:$|[\s\"';|&>)])" (str command))))))

(def ^:private loop-hint
  (str "A loop is the project's when a person makes it so (RFC-071): write the candidate to"
       " another file and try it with --loop; a person accepts it, or puts it there by hand."))

(defn- loop-refusal [by display reason]
  (deny-replace {:by by :display display :reason reason
                 :reason-prefix "Loop write refused"
                 :error-prefix "Tool call refused (loop)"
                 :hint loop-hint}))

(defn path-gate
  "Priority 90. Every path a call names must be under `:allowed-dirs` (unset:
  /tmp, the system temp dir, the working dir, ~/.brainyard). One that is not
  goes to the permission channel — `{:type :file-access :action :read|:write}`
  — and a refusal there refuses the call.

  brainyard checks paths inside each tool, with a stricter always-allowed set
  for writes than its own `:allowed-dirs` (divergence
  `write-paths-use-allowed-dirs`); here one gate covers every tool that
  declares `:path-params`, including calls a user tool makes."
  [{:keys [tool-name args]} ctx _opts]
  (or
   ;; a project's loop, written by no tool in any mode: a loop there is
   ;; every later session's procedure, and only a person makes it so
   ;; (RFC-071, byj-ng7.12)
   (when (contains? (effects-of ctx tool-name) :fs/write)
     (let [tool (some-> (:tools ctx) deref (get tool-name))
           working-dir (config ctx :working-dir ".")]
       (when-let [p (first (keep #(loop-file (config ctx :project-dir working-dir) %)
                                 (call-paths tool args working-dir)))]
         (loop-refusal "path-permission-gate" p "it is the project's loop, which a person makes live, not a tool"))))
   ;; a person's logins and keys, refused in every mode: what a shared
   ;; agent's caller, or a file it reads, could ask it to fetch (byj-1wv.115.40)
   (let [tool (some-> (:tools ctx) deref (get tool-name))
         paths (call-paths tool args (config ctx :working-dir "."))]
     (when-let [d (denied-path (config ctx :denied-paths (get-in config/config-items [:denied-paths :default])) paths (System/getenv "HOME"))]
       (deny-replace {:by "path-permission-gate" :display (first paths)
                      :reason (str "it holds a login or a key (" d ", :denied-paths)")
                      :reason-prefix "Path refused"
                      :error-prefix "Tool call refused (path)"
                      :hint "A person's logins and keys are read by no tool; name a narrower path that does not hold one."})))
   (when (config ctx :path-permission? true)
    (let [tool (some-> (:tools ctx) deref (get tool-name))
          working-dir (config ctx :working-dir ".")
          dirs (allowed-dirs ctx working-dir)
          effects (effects-of ctx tool-name)
          action (if (contains? effects :fs/write) :write :read)
          outside (remove (fn [p] (some #(under? % p) dirs)) (call-paths tool args working-dir))]
      (when-let [p (first outside)]
        (let [refuse (fn [reason]
                       (deny-replace {:by "path-permission-gate" :display p :reason reason
                                      :reason-prefix (str "Path " (name action) " refused")
                                      :error-prefix (str "Tool call refused (path)")
                                      :hint (str "Allowed directories: " (str/join ", " dirs)
                                                 ". Add one with --allow-path DIR, or [:permissions :allowed-dirs] in .brainyard/config.edn.")}))]
          (case (resolve-mode (mode ctx))
            :auto-approve nil
            :deny-by-default (refuse "outside the allowed directories")
            (ask ctx {:type :file-access :action action :path p :paths (vec outside)
                      :tool tool-name :display p :args args :effects effects}
                 refuse
                 "outside the allowed directories, and there is no interactive permission channel (headless)"))))))))

(def ^:private unparseable
  "Shell constructs an allow-list cannot segment: what runs is decided at run
  time, so the program names we would check are not in the text. With an
  allow-list in force these are refused rather than guessed at."
  {"$(" "command substitution"
   "`" "a backquote"
   "<(" "process substitution"
   ">(" "process substitution"})

(defn- scan-quotes
  "Walk `command` as a shell reads its quoting — single quotes literal to the
  next one, double quotes with backslash escapes, a backslash outside quotes
  escaping the next character — calling `(f i c quote)` for each character
  that is not escaped, `quote` the open quote or nil. The open quote at the
  end, nil when every quote closed."
  [command f]
  (let [s (str command) n (count s)]
    (loop [i 0 q nil]
      (if (>= i n)
        q
        (let [c (.charAt s i)]
          (cond
            (and (= c \\) (not= q \') (< (inc i) n)) (recur (+ i 2) q)
            (and (nil? q) (#{\' \"} c)) (do (f i c nil) (recur (inc i) c))
            (and q (= c q)) (recur (inc i) nil)
            :else (do (f i c q) (recur (inc i) q))))))))

(defn unclosed-quote?
  "Does `command` leave a quote open — a line an allow-list cannot segment?"
  [command]
  (some? (scan-quotes command (fn [_ _ _]))))

(defn command-segments
  "A command line split into the simple commands a shell would run: on `;`
  `&&` `||` `|` and line breaks OUTSIDE quotes — a separator inside single or
  double quotes is text, as the shell reads it (byj-gly.315.14: agent$beads'
  bd create descriptions held `;`, and were refused once a bead). What the
  shell runs at a time this text cannot see — `$(` `` ` `` `<(` `>(` — is
  refused by `command-problem` wherever it is, inside quotes too; an unclosed
  quote is refused; a `#` comment and a heredoc's body are split as though
  they ran. Each errs only toward refusing."
  [command]
  (let [s (str command)
        cuts (transient [])]
    (scan-quotes s (fn [i c q]
                     (when (nil? q)
                       (let [nxt (when (< (inc i) (count s)) (.charAt s (inc i)))]
                         (cond
                           (#{\; \newline} c) (conj! cuts [i 1])
                           (and (= c \&) (= nxt \&)) (conj! cuts [i 2])
                           (and (= c \|) (= nxt \|)) (conj! cuts [i 2])
                           ;; the second of a || or && was cut with the first
                           (and (= c \|) (not (and (pos? i) (= \| (.charAt s (dec i)))))) (conj! cuts [i 1])
                           :else nil)))))
    (let [cuts (persistent! cuts)
          ;; drop a cut that starts inside the previous one (the 2nd char of && / ||)
          cuts (reduce (fn [acc [i w]] (if (and (seq acc) (< i (+ (first (peek acc)) (second (peek acc))))) acc (conj acc [i w])))
                       [] cuts)]
      (->> (map (fn [start [i _]] (subs s start i))
                (cons 0 (map (fn [[i w]] (+ i w)) cuts))
                (concat cuts [[(count s) 0]]))
           (map str/trim)
           (remove str/blank?)))))

(defn program-of
  "The program a segment runs: its first word, past any leading `VAR=value`
  assignments, as a shell reads it."
  [segment]
  (->> (str/split (str/trim segment) #"\s+")
       (remove str/blank?)
       (drop-while #(re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" %))
       first))

(defn command-problem
  "Why this command line may not run, or nil.

  `:bash-deny-commands` globs match the WHOLE line — a deny list is a tripwire,
  and the thing it is looking for can appear anywhere. `:bash-allow-commands`
  globs match the PROGRAM of every segment, by its full token and by its
  basename, so `git` allows `/usr/bin/git`.

  **This is not a sandbox.** A shell is a programming language; an allow-list
  that tried to understand one would be a parser with a security bug in it. So
  when an allow-list is in force, anything we cannot segment is refused, and the
  boundary that actually holds is `sandbox-exec` (RFC-012)."
  [{:keys [bash-allow-commands bash-deny-commands]} command]
  (let [line (str command)]
    (or (when-let [pattern (first-match bash-deny-commands line)]
          {:reason (str "the command matches :bash-deny-commands " (pr-str pattern))})
        (when (seq bash-allow-commands)
          (or (when-let [[frag what] (first (filter (fn [[frag _]] (str/includes? line frag)) unparseable))]
                {:reason (str "the command uses " what " (" frag "), which an allow-list cannot check"
                              " — run the parts as separate calls")})
              (when (unclosed-quote? line)
                {:reason "the command leaves a quote open, which an allow-list cannot segment — close it, or run the parts as separate calls"})
              (when-let [prog (first (remove (fn [p]
                                               (or (first-match bash-allow-commands p)
                                                   (first-match bash-allow-commands
                                                                (.getName (io/file p)))))
                                             (keep program-of (command-segments line))))]
                {:reason (str "`" prog "` is not in :bash-allow-commands")}))))))

(defn segment-text
  "A segment as `:bash-ask-commands` reads it: its words, past any leading
  `VAR=value` assignments, one space apart."
  [segment]
  (->> (str/split (str/trim segment) #"\s+")
       (remove str/blank?)
       (drop-while #(re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" %))
       (str/join " ")))

(defn asked-command
  "The first segment of `line` a `:bash-ask-commands` glob matches, as
  `[segment glob]`, or nil — a command that could destroy or change what the
  person did not ask to, asked about though it names nothing outside the
  allowed directories (byj-gly.285.4)."
  [cfg line]
  (when-let [globs (seq (get cfg :bash-ask-commands []))]
    (first (keep (fn [seg] (let [t (segment-text seg)]
                             (when-let [g (first-match globs t)] [t g])))
                 (command-segments line)))))

(defn- live-model?
  "Is there a real model to classify with? Not the mock, not a CLI a test swapped
  in, not none — on those the classifier cannot judge, and every command it was
  handed would come back unsure."
  [ctx]
  (let [lm (:lm ctx)]
    (boolean (and lm (not= :mock (:provider lm)) (not (:cli-command lm))))))

(defn classify-every-command?
  "Does `:auto` classify a command no `:bash-ask-commands` glob names? Measured
  (byj-gly.285.3): the list missed every one of the dangerous commands it does
  not name, and the classifier caught all of them with no false blocks — so,
  where `:auto` would ask (outside a container), the classifier is on and
  there is a live model to ask, every command is classified."
  [ctx]
  (and (= :auto (mode ctx))
       (= :ask-each-time (resolve-mode :auto))
       (config ctx :auto-mode-classifier? true)
       (config ctx :auto-mode-classify-every-command? true)
       (some? (:auto-classify ctx))
       (live-model? ctx)))

(defn command-paths
  "Absolute paths named in a command line — brainyard's `validate-bash-paths`
  regex. A path that does not exist is not a violation there, and is not here:
  a redirection into a new file under an allowed dir would otherwise be refused
  for not existing yet."
  [command]
  (->> (re-seq #"(?:^|[\s\"'=])(/[^\s\"'>;|&]+)" (str command))
       (map second)
       (filter #(.exists (io/file %)))
       distinct
       vec))

(defn command-gate
  "Priority 92. For a tool that declares `:command-params`, every named argument
  is read as a shell command line: refused outright by `:bash-deny-commands`,
  held to `:bash-allow-commands`, and its absolute paths checked against
  `:allowed-dirs` through the permission channel.

  brainyard checks paths this way for `task$run`'s bash job alone
  (`validate-bash-paths`) and has no command list at all; this gate covers every
  tool that names a command, including `task$run` (divergence
  `bash-command-gate`)."
  [{:keys [tool-name args]} ctx _opts]
  (let [tool (some-> (:tools ctx) deref (get tool-name))
        cfg (:config ctx)
        commands (keep #(get args %) (:command-params tool))]
    (when (seq commands)
      (or
       ;; the command itself
       (when-let [{:keys [reason]} (first (keep #(command-problem cfg %) commands))]
         (deny-replace {:by "bash-command-gate" :display tool-name :reason reason
                        :reason-prefix "Command refused"
                        :error-prefix "Tool call refused (command)"
                        :hint (str "Allowed commands: "
                                   (if (seq (:bash-allow-commands cfg))
                                     (str/join ", " (:bash-allow-commands cfg))
                                     "anything not denied")
                                   ". Set them with [:permissions :bash-allow-commands] in .brainyard/config.edn.")}))
       ;; a project's loop file it names — a tripwire, as a login's is; before
       ;; anything asks, so a person is not asked about what is refused (byj-ng7.12)
       (when-let [f (first (mapcat named-loop-files commands))]
         (loop-refusal "bash-command-gate" f "it names a project's loop, which a person makes live, not a tool"))
       ;; a command that could destroy or change things — asked about, so in
       ;; :auto classified, inside the allowed directories too (byj-gly.285.4)
       (when-let [[seg glob] (or (first (keep #(asked-command cfg %) commands))
                                 ;; in :auto with a live model, every command
                                 ;; goes to the classifier (byj-gly.285.3)
                                 (when (classify-every-command? ctx)
                                   [(str/trim (str (first commands))) nil]))]
         (let [refuse (fn [reason]
                        (deny-replace {:by "bash-command-gate" :display seg :reason reason
                                       :reason-prefix "Command refused"
                                       :error-prefix "Tool call refused (command)"
                                       :hint (if glob
                                               (str "It matches :bash-ask-commands " (pr-str glob) ".")
                                               "In :auto every command is classified (:auto-mode-classify-every-command?).")}))]
           (case (resolve-mode (mode ctx))
             :auto-approve nil
             :deny-by-default (refuse "permission mode is :deny-by-default")
             (ask ctx {:type :command :action :run :tool tool-name :display seg :pattern glob
                       :command (first commands)}
                  refuse
                  "it asks first, and there is no interactive permission channel (headless)"))))
       ;; a login or a key it names, absolute or from ~ — a tripwire, as the
       ;; deny list is: a shell reaches a file by other spellings (byj-1wv.115.40)
       (let [home (System/getenv "HOME")
             named (concat (mapcat command-paths commands)
                           (mapcat #(home-paths % home) commands))]
         (when-let [d (denied-path (config ctx :denied-paths (get-in config/config-items [:denied-paths :default])) named home)]
           (deny-replace {:by "bash-command-gate" :display tool-name
                          :reason (str "it names a login or a key (" d ", :denied-paths)")
                          :reason-prefix "Command refused"
                          :error-prefix "Tool call refused (command)"
                          :hint "A person's logins and keys are read by no tool."})))
       ;; the paths it names
       (when (config ctx :path-permission? true)
         (let [working-dir (config ctx :working-dir ".")
               dirs (allowed-dirs ctx working-dir)
               outside (remove (fn [p] (some #(under? % p) dirs))
                               (mapcat command-paths commands))]
           (when-let [p (first outside)]
             (let [refuse (fn [reason]
                            (deny-replace {:by "bash-command-gate" :display p :reason reason
                                           :reason-prefix "Path access refused"
                                           :error-prefix "Tool call refused (path)"
                                           :hint (str "Allowed directories: " (str/join ", " dirs)
                                                      ". Use a relative path, or add one with --allow-path DIR.")}))]
               (case (resolve-mode (mode ctx))
                 :auto-approve nil
                 :deny-by-default (refuse (str "the command names " p ", outside the allowed directories"))
                 (ask ctx {:type :file-access :action :bash :path p :paths (vec outside)
                           :tool tool-name :display p :command (first commands)}
                      refuse
                      (str "the command names " p
                           ", outside the allowed directories, and there is no interactive permission channel")))))))))))

;; --- the ask channel --------------------------------------------------------------

(defn grant-for
  "What a `remember` answer writes for this request: `[key value]`, or nil when
  the request is not one a config key can express.

  A file access grants its PARENT DIRECTORY, which is the unit the prompt asks
  about and remembers by — granting the single file would ask again for its
  sibling, which is not what the person answered."
  [{:keys [type pattern display path]}]
  (cond
    ;; a command asked about by :bash-ask-commands: remembered for the session
    ;; only — no config key says "run this without asking" (byj-gly.285.4)
    (= :command type) nil
    (= :file-access type)
    (when-let [d (or (some-> path io/file .getParent) path)]
      [:allowed-dirs d])
    :else
    (when-let [g (or pattern display)]
      [:tool-allow-tools g])))

(defn asking-permission-fn
  "A `:permission-fn` over a terminal: `ask` is (fn [prompt] line-or-nil) — the
  session's own reader — and answers are remembered for the session, keyed by
  the pattern that matched, as brainyard's TUI does: y/n once, a/d for good.

  With `config`, a fifth answer — `r` — also writes the grant to
  `.brainyard/config.edn`, so the next run does not ask again (`byj-gly.83`).
  That write is the ONLY route by which a permission key is ever persisted, and
  a human pressing a key in a terminal is the whole of what makes it allowed:
  `config$set` refuses these keys, and no command may ask as a person.

  `say` reports what was written, because a config file changed quietly by
  answering a prompt is the thing worth not doing.

  `nil` (end of input) denies. There is no timeout: the prompt blocks until it
  is answered, and Ctrl-C cancels the turn (divergence `permission-prompt-cli`)."
  ([ask] (asking-permission-fn ask nil nil))
  ([ask config say]
   (let [remembered (atom {})
         can-remember? (boolean (and config (configbox/scope-file config :auto)))]
     (fn [{:keys [display pattern args effects type action path] :as req}]
       (let [key (if (= :file-access type)
                   ;; remembered per parent directory, as brainyard does
                   (str action " " (or (some-> path io/file .getParent) path))
                   (or pattern display))]
         (if-let [r (get @remembered key)]
           r
           (let [answer (str/trim (str (ask (str "\npermission: "
                                                 (when (= :file-access type) (str (name action) " "))
                                                 display
                                                 (when (seq args) (str " " (pr-str args)))
                                                 (when (seq effects) (str " — effects " (str/join ", " (sort effects))))
                                                 "\n[y]es once / [n]o / [a]lways"
                                                 (when (and can-remember? (grant-for req)) " / [r]emember (writes config)")
                                                 " / [d]eny always > "))))
                 allow {:allowed true}
                 deny {:denied true :reason "denied by user"}
                 note (fn [msg] (when say (say msg)))]
             (case answer
               ("y" "yes") allow
               ("a" "always") (do (swap! remembered assoc key allow) allow)
               ("r" "remember")
               (do (swap! remembered assoc key allow)
                   (if-let [[k v] (and can-remember? (grant-for req))]
                     (let [r (configbox/grant! config :auto k v (str "granted at the prompt: " key))]
                       (cond
                         (:already? r) (note (str "already in config: " k " " (pr-str v)))
                         (:ok? r) (note (str "saved to " (:path r) " — " (name k) " now " (pr-str (:now r))))
                         :else (note (str "could not save (" (:error r) ") — allowed for this session only"))))
                     (note "nothing to save for this request — allowed for this session only"))
                   allow)
               ("d" "deny") (let [r {:denied true :reason (str "denied for this session (" key ")")}]
                              (swap! remembered assoc key r)
                              r)
               deny))))))))
