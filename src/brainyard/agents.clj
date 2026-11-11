(ns brainyard.agents
  "Subagents (RFC-018) — brainyard's `defagent` / `do-call-tool--agent` /
  `common/user_agents.clj`, data-only.

  An agent is a SPEC, never a function: a name, the instruction its child turn
  reads, and the tools it may reach. So the roster changes without a rebuild —
  `.brainyard/agents/<name>/agent.edn` beside `instruction.md`, over
  `~/.brainyard/agents/`, over the roster shipped in resources.

  Dispatching one is an ordinary tool call, `agent$<name>`, which runs a whole
  child turn and returns its ANSWER. The point is what does not come back: the
  child's iterations, its tool results and its transcript stay in the child, so
  a search that costs twenty thousand tokens costs the caller a paragraph.

  What the child inherits is deliberately short — the config, the permission
  gate, the hooks and the session id. Not the parent's history, and not its
  st-memory. Three guards bound the recursion, and they live in the child's
  CONFIG rather than in dynamic vars as brainyard's do: ebb conveys bindings per
  fiber and at run time (RFC-009), and a guard that depends on which thread ran
  a node is not a guard.

  A dispatch leaves the agent ALIVE as an instance — `explore/amber-otter-42` —
  holding its own previous turns, so `agent-registry$ask` continues where it
  left off instead of paying for the same context twice. An instance here is
  data, not a live object: the spec, the turns and some counters, from which its
  ctx is rebuilt per ask. brainyard keeps the agent itself, with its sandbox and
  memory manager, which is why its close path has so much more to do.

  `meta-agent$*` lets the model write one mid-turn, as prose only: an agent's
  allow list is inherited by its children instead of its caller's, so a model
  that could write one could hand a subagent a wider palette than its own.

  Not ported: the parent trail (`byj-gly.39`), work tiers (`byj-gly.40`),
  background dispatch (`byj-gly.13`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [brainyard.events :as events]
            [brainyard.effect :as fx]
            [brainyard.history :as history]
            [brainyard.llm :as llm]
            [brainyard.schema :as schema]
            [brainyard.permission :as permission]))

(def name-re
  "brainyard's `user_agents.clj` name regex, and its error string."
  #"[a-z][a-z0-9-]*")

(defn tool-id [name] (str "agent$" name))

;; --- where agents come from -------------------------------------------------------

(defn roots
  "Every agent root, most local first. `:agent-roots` replaces the file roots —
  a shared team roster, or one project's agents in a test. The built-in roster
  is always last unless `:builtin-agents?` is false."
  [config]
  (->> (if-let [explicit (seq (:agent-roots config))]
         (map #(hash-map :dir (io/file %) :source "project") explicit)
         [{:dir (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "agents")
           :source "project"}
          (when-let [home (System/getenv "HOME")]
            {:dir (io/file home ".brainyard" "agents") :source "user"})])
       (remove nil?)
       (filter #(.isDirectory (:dir %)))
       vec))

(defn- sidecar
  "The text of `<dir>/<file>`, or nil. Prose lives in markdown where it can be
  edited, as a user tool's body does (RFC-010)."
  [^java.io.File dir file]
  (let [f (io/file dir file)]
    (when (.isFile f) (slurp f))))

;; --- what an agent is -------------------------------------------------------------

(def ^:private tier-schema
  [:maybe [:fn {:error/message (str "must be one of " (str/join ", " llm/tiers))}
           #(some? (llm/coerce-tier %))]])

(def agent-schema
  "What an agent definition is, as malli (byj-gly.204 follow-up): each rule
  carries the message it has always given, so a person fixing an agent.edn
  reads the same words. Open — an agent.edn may carry more."
  [:map
   [:name [:fn {:error/fn (fn [{:keys [value]} _] (str "must match ^[a-z][a-z0-9-]*$ (got " (pr-str value) ")"))}
           ;; re-matches, not malli's :re — that is re-find, and would pass
           ;; "Probe Agent" for the "robe" in it
           #(boolean (and (string? %) (re-matches name-re %)))]]
   [:instruction [:fn {:error/message "must be a non-blank string"}
                  #(and (string? %) (not (str/blank? %)))]]
   [:tools {:optional true}
    [:maybe [:sequential {:error/message "must be a list of tool-name globs"}
             [:string {:error/message "must be a list of tool-name globs"}]]]]
   [:deny-tools {:optional true}
    [:maybe [:sequential {:error/message "must be a list of tool-name globs"}
             [:string {:error/message "must be a list of tool-name globs"}]]]]
   [:max-iterations {:optional true} [:maybe [:int {:min 1 :error/message "must be a positive integer"}]]]
   [:loop {:optional true}
    [:maybe [:string {:error/message "must be a path to a behavior-tree EDN, beside agent.edn"}]]]
   [:tier {:optional true} tier-schema]
   [:min-tier {:optional true} tier-schema]
   [:max-tier {:optional true} tier-schema]])

(defn- problems
  "Everything wrong with a spec, in one list — brainyard reports the first and
  stops, which costs a round trip per mistake. Each as `agent :<key> <what>`."
  [agent]
  ;; a missing :instruction is the same mistake as a blank one, and says so
  (->> (schema/explain-problems agent-schema (assoc agent :instruction (:instruction agent)))
       (map #(str/replace % #"^`([^`.]+)[^`]*` " "agent :$1 "))
       distinct
       vec
       not-empty))

(defn parse-agent
  "A spec from its map and whatever sidecars its directory holds, as
  `{:agent …}` or `{:problems […]}`. A sidecar wins over an inline key: the
  markdown is the copy a person edits."
  [dir-name m & [^java.io.File dir]]
  (let [agent (cond-> (assoc m :name (or (:name m) dir-name))
                dir (assoc :dir (.getPath dir))
                (and dir (sidecar dir "instruction.md")) (assoc :instruction (sidecar dir "instruction.md"))
                (and dir (sidecar dir "tool-context.md")) (assoc :tool-context (sidecar dir "tool-context.md")))
        ps (problems agent)]
    (if (seq ps) {:problems ps :name (:name agent)} {:agent agent})))

(defn builtin-agents
  "The roster shipped in resources. An index, because a built binary cannot
  list a resource directory (as `tools/index.edn` does)."
  []
  (when-let [index (io/resource "brainyard/agents/index.edn")]
    (vec (for [n (edn/read-string (slurp index))
               :let [m (edn/read-string (slurp (io/resource (str "brainyard/agents/" n ".edn"))))]]
           (cond-> (assoc m :name n :source "builtin")
             (:instruction-resource m)
             (assoc :instruction (slurp (io/resource (:instruction-resource m)))))))))

(defn- read-dir [{:keys [source]} ^java.io.File d]
  (let [f (io/file d "agent.edn")]
    (when (.isFile f)
      (let [m (try (edn/read-string (slurp f)) (catch Throwable e {::unreadable (ex-message e)}))]
        (if (::unreadable m)
          {:problems [(str "unreadable EDN: " (::unreadable m))] :name (.getName d) :file (.getPath f)}
          (let [r (parse-agent (.getName d) (assoc m :source source) d)]
            (cond-> r (:problems r) (assoc :file (.getPath f)))))))))

(defn load-agents
  "Every agent, most local first, as `{:agents […] :shadowed […] :problems […]}`.

  A name defined twice keeps the most local and REPORTS the one it dropped:
  unlike a skill, a shadowed agent does not stay reachable under another id —
  two agents with one name are a mistake to surface, not a pair to
  disambiguate. Re-read every call, so editing one takes effect next turn."
  [config]
  (let [from-files (for [root (roots config)
                         d (sort-by #(.getName %) (.listFiles (:dir root)))
                         :when (.isDirectory d)
                         :let [r (read-dir root d)]
                         :when r]
                     r)
        found (concat from-files
                      (when (:builtin-agents? config true)
                        (map #(hash-map :agent %) (builtin-agents))))
        ok (keep :agent found)
        [kept dropped] (reduce (fn [[kept dropped] a]
                                 (if (some #(= (:name %) (:name a)) kept)
                                   [kept (conj dropped a)]
                                   [(conj kept a) dropped]))
                               [[] []] ok)]
    {:agents (vec (sort-by :name kept))
     :shadowed (vec (for [d dropped]
                      {:name (:name d) :dropped (or (:dir d) (:source d))
                       :kept (let [k (first (filter #(= (:name %) (:name d)) kept))]
                               (or (:dir k) (:source k)))}))
     :problems (vec (for [p found :when (:problems p)]
                      (select-keys p [:name :file :problems])))}))

;; --- what the child may reach -----------------------------------------------------

(defn visible?
  "Is tool `name` visible under this config? brainyard's `:tool-use-control`,
  as two config keys: `:visible-tools` globs allow (empty = everything),
  `:hidden-tools` globs hide, and hiding wins.

  A hidden tool stays hidden in every descendant — a child's hidden set is its
  parent's plus its own — while an allow list is per agent, so a specialist can
  be given a set its caller does not itself list.

  An MCP server whose spec says `:only-named? true` offers its tools only to
  an agent whose `:tools` names them: an empty allow list, the session's own,
  does not reach them (RFC-024). Its tools are a specialist's, as a built-in
  set once was when its agent's own config turned it on."
  [config name]
  (let [only-named? (when-let [[_ server] (re-matches #"mcp\$([^$]+)\$.+" (str name))]
                      (true? (get-in config [:mcp-servers server :only-named?])))]
    (and (if (or only-named? (seq (:visible-tools config)))
           (boolean (permission/first-match (:visible-tools config) name))
           true)
         (not (permission/first-match (:hidden-tools config) name)))))

;; --- instances --------------------------------------------------------------------

;; brainyard's `util/core/naming.cljc` word lists, so an instance id reads like
;; one of its own: memorable enough to be carried between turns by a model, and
;; obviously not a path or a uuid.
(def ^:private colors
  ["amber" "azure" "beige" "black" "blue" "bronze" "brown" "coral" "crimson"
   "cyan" "ebony" "emerald" "fuchsia" "gold" "gray" "green" "indigo" "ivory"
   "jade" "lavender" "lime" "magenta" "maroon" "navy" "olive" "orange"
   "peach" "pink" "plum" "purple" "red" "rose" "ruby" "salmon" "sapphire"
   "scarlet" "silver" "tan" "teal" "turquoise" "violet" "white" "yellow"])

(def ^:private animals
  ["ant" "bear" "bee" "bird" "cat" "cow" "crab" "crow" "deer" "dog" "dolphin"
   "duck" "eagle" "elk" "ferret" "finch" "fish" "fox" "frog" "goat" "hare"
   "hawk" "horse" "koala" "lion" "lynx" "mole" "moose" "mouse" "newt" "otter"
   "owl" "panda" "pig" "puma" "rabbit" "raven" "salmon" "seal" "shark"
   "sheep" "snake" "stork" "swan" "tiger" "toad" "turtle" "vole" "whale" "wolf"])

(def ^:private instance-seq
  "Ids have to be unique within the process, and `rand` cannot promise that
  here: in a full suite run, five freshly created `blk` threads minted the same
  colour-animal-number, because a fresh thread can reproduce another thread's
  random sequence (provenance P-13). Two instances then shared one id, the
  second silently replaced the first in the registry, and an ask meant for the
  evicted one reached the live one. The words stay random because they are for
  a person to recognise; the number is a counter, because it is what makes the
  id an id."
  (atom 0))

(defn instance-id
  "brainyard's `<defagent-type>/<colour>-<animal>-<n>`, as a string."
  [agent-name]
  (str agent-name "/" (rand-nth colors) "-" (rand-nth animals) "-" (swap! instance-seq inc)))

(defn caller-id
  "Who is calling: an instance id when this turn is itself a subagent, else
  \"root\". It is what an instance records as its `:owner`."
  [config]
  (or (:agent-instance-id config) "root"))

(defn resolve-id
  "brainyard's forgiving lookup: an exact id, one written with a leading colon,
  or a bare suffix when it names exactly one instance — a model that carried
  `amber-otter-42` out of a result should not be punished for it."
  [instances id]
  (let [id (str/replace (str/trim (str id)) #"^:" "")]
    (cond
      (contains? instances id) id
      :else (let [hits (filter #(= id (second (str/split % #"/" 2))) (keys instances))]
              (when (= 1 (count hits)) (first hits))))))

(defn instance-summary
  "What the model reads about an instance. Never the spec, and never its turns:
  those are the child's, and copying them up would undo the isolation that is
  the point of delegating."
  [{:keys [id agent owner status answers created-at last-ask-at last-question]}]
  {:id id :agent agent :owner owner :status status
   :answers (or answers 0)
   :idle-ms (- (System/currentTimeMillis) (or last-ask-at created-at 0))
   :last-question (history/clip last-question 200)})

(defn- evictable
  "Idle instances, least recently asked first. A running one is never evicted —
  brainyard's rule, so the cap may be briefly exceeded rather than a turn being
  pulled out from under itself."
  [instances]
  (->> (vals instances)
       (filter #(= :idle (:status %)))
       (sort-by #(or (:last-ask-at %) (:created-at %) 0))))

(defn- owned-by
  "Every instance `id` owns, transitively."
  [instances id]
  (let [direct (filter #(= id (:owner %)) (vals instances))]
    (into (mapv :id direct) (mapcat #(owned-by instances (:id %)) direct))))

(defn close-instance!
  "Remove `id` and everything it dispatched — brainyard's `cascade-close-owned!`
  on `:agent.instance/closed`. Returns the ids removed."
  [reg id reason emit]
  (let [instances @reg
        ids (cons id (owned-by instances id))]
    (swap! reg #(apply dissoc % ids))
    (doseq [i ids] (emit :agent.instance/closed {:id i :reason (if (= i id) reason :cascade)}))
    (vec ids)))

;; --- work tiers ---------------------------------------------------------------------

(defn tier-entry
  "What tier this agent works at: its own declaration, with the config's
  `:agent-tier-map` over it. brainyard keeps this table in config alone, keyed
  by defagent type, because its specialists are compiled in; here the roster is
  data, so an agent states its own tier and the table retunes one you cannot
  edit (divergence `agent-tier-in-the-spec`)."
  [config {:keys [name tier min-tier max-tier]}]
  (merge (cond-> {}
           tier (assoc :default tier)
           min-tier (assoc :min min-tier)
           max-tier (assoc :max max-tier))
         (get (:agent-tier-map config) name)
         (get (:agent-tier-map config) (keyword name))))

(defn clamp-tier
  "Bring `tier` inside the agent's window, reporting whether it had to.
  brainyard clamps rather than refusing: failing the dispatch would turn a cost
  question into an outage, and obeying the request would make the cap
  decorative. An inverted window (`:min` above `:max`) widens to `:min` rather
  than silently pinning everything to it."
  [tier {:keys [min max]}]
  (let [want (llm/tier-rank (llm/coerce-tier tier))
        lo (get llm/tier-rank (llm/coerce-tier min) 0)
        hi (let [h (get llm/tier-rank (llm/coerce-tier max) (dec (count llm/tiers)))]
             (if (< h lo) lo h))
        got (clojure.core/max lo (clojure.core/min hi want))]
    {:tier (nth llm/tiers got) :clamped? (not= got want) :from (llm/coerce-tier tier)}))

(defn resolve-work-tier
  "The tier a dispatch runs at: what the caller asked for, clamped into the
  agent's window, else the agent's default, else `:standard` — brainyard's
  `resolve-work-tier`. An unparseable request is not an error; it simply is not
  a request, so the default applies."
  [config agent requested]
  (let [entry (tier-entry config agent)]
    (if-let [req (llm/coerce-tier requested)]
      (assoc (clamp-tier req entry) :entry entry)
      {:tier (or (llm/coerce-tier (:default entry)) :standard)
       :clamped? false :from nil :entry entry})))

;; --- dispatch ---------------------------------------------------------------------

(defn refusal
  "The guard that refuses this dispatch, as brainyard's message, or nil.
  Depth and the chain are config, set by the dispatch that made this ctx."
  [config name]
  (let [depth (:agent-depth config 0)
        max-depth (:max-agent-call-depth config 3)
        chain (vec (:agent-chain config))]
    (cond
      (>= depth max-depth)
      {:error-message (format "Agent call depth limit reached (%d/%d). Cannot call '%s'."
                              depth max-depth (tool-id name))
       ::reason :depth}
      ;; brainyard pushes the CALLER's instance id and tests the TARGET's
      ;; registry id, so a → b → a is not caught; we push what we dispatch
      ;; (divergence `subagent-cycle-guard`)
      (some #{name} chain)
      {:error-message (format "Circular agent call detected: %s -> %s. Chain: %s"
                              (or (last chain) "root") name
                              (str/join " -> " (conj chain name)))
       ::reason :cycle})))

(defn- child-question
  "What the child is asked. `:agent-context` is folded in rather than handed to
  a zone: the child has no conversation, so the caller's context IS part of the
  question (RFC-018)."
  [question agent-context]
  (cond-> (str/trim (str question))
    (not (str/blank? (str agent-context)))
    (str "\n\n## Context from the caller\n" (str/trim (str agent-context)))))

(defn trail-k
  "How many of the caller's turns `agent` is shown.

  **The agent asks first.** brainyard resolves this on the caller alone, which
  works while the default is 3 and every child gets a trail; with the default at
  0 (divergence `parent-trail-off-by-default`) that would leave no way to turn
  it on for the one agent that needs it. So an agent's own spec wins, and the
  caller's config is the fallback for a project that wants it everywhere."
  [agent config]
  (or (:parent-trail-k agent)
      (:parent-trail-k (:config agent))
      (:parent-trail-k config)
      0))

(defn parent-trail-for
  "The caller's last `k` turns, for a child to read (RFC-018).

  brainyard's `parent-handoff-st-memory`, with its `take-last k` — newest k,
  oldest first. 0 hands over nothing.

  The turns come from the parent TURN's memory, which is why the session tree
  copies `:previous-turns` inward beside the rendered `:history-context`: a
  dispatch has to cut its own k, and cannot re-cut a string someone else made."
  ([ctx] (parent-trail-for nil ctx))
  ([agent ctx]
   (let [k (trail-k agent (:config ctx))]
     (when (pos? (or k 0))
       (not-empty (vec (take-last k (:previous-turns (some-> (:st-memory ctx) deref)))))))))

(def provider-fields
  "What belongs to a PROVIDER rather than to a conversation. These are the
  keys that must not cross from a caller to a child on a different provider."
  #{:api-key :auth-token :base-url :region :profile})

(defn child-lm
  "The model a child runs on.

  An agent that names only a `:model` keeps its caller's credentials, which is
  what merging has always done and is right. An agent that names a DIFFERENT
  `:provider` does not: `(merge (:lm ctx) (:lm agent))` handed it the caller's
  key and the caller's endpoint, so a child on `:openai` was given an Anthropic
  key and `https://api.anthropic.com/v1` — a failure that reads as a provider
  outage, and a credential travelling to an endpoint the agent chose
  (`byj-gly.91`).

  Its credentials are therefore resolved for ITS provider, from this run's
  environment (`.env` included), by the same `create-lm` the CLI used. What
  carries over is what belongs to the conversation rather than the provider —
  streaming, caching, temperature — and the agent's own `:lm` still wins over
  all of it, so a spec may pin a key explicitly if it must."
  [agent ctx tier-lm]
  (let [caller (:lm ctx)
        want (:lm agent)]
    (cond
      (and (:provider want) (not= (:provider want) (:provider caller)))
      (let [inherited (apply dissoc caller :provider :model provider-fields)]
        (llm/create-lm (:provider want)
                       (or (:env-fn ctx) #(System/getenv %))
                       (merge inherited want)))

      want (merge caller want)
      tier-lm tier-lm
      :else caller)))

(defn- tagged
  "`on-event` with every event marked as the child `id`'s, so a front end can
  tell a subagent's work from its caller's and show it apart (byj-gly.138). The
  innermost child wins: a grandchild's events pass through its parent's tagger
  on the way out and keep the grandchild's id."
  [on-event id agent-name]
  (when on-event
    (fn [event data]
      (on-event event (if (and (map? data) (not (:subagent-id data)))
                        (assoc data :subagent-id id :subagent agent-name)
                        data)))))

(defn loop-path
  "Where an agent's own `:loop` is: beside its `agent.edn` when relative, as
  given otherwise. nil for an agent with no loop of its own (RFC-022)."
  [{:keys [dir] :as agent}]
  (when-let [t (:loop agent)]
    (if (and dir (not (str/starts-with? t "/")))
      (.getPath (io/file dir t))
      t)))

(defn allowed-commands
  "The programs an agent's shell may run, given the session's
  `:bash-allow-commands` and the agent's own (byj-gly.357): the agent's when
  the session has none; when both have one, only what both allow — an
  agent's entry a session glob matches, and a session entry an agent glob
  matches — so an agent narrows what the person allowed and never widens
  it. nil when the agent has none: the session's stands. An empty result
  means no program at all, which `persona-config` makes no shell: an empty
  list would mean no limit."
  [session-allow agent-allow]
  (cond
    (empty? agent-allow) nil
    (empty? session-allow) (vec agent-allow)
    :else (vec (distinct (concat (filter (fn [a] (some #(permission/glob-match? % a) session-allow)) agent-allow)
                                 (filter (fn [s] (some #(permission/glob-match? % s) agent-allow)) session-allow))))))

(def reach-order
  "Reach levels, least first: what each lets a session's caller make happen.
  `:none` is an agent with no tool at all, which answers from what it is
  told (byj-1wv.115.24)."
  [:none :reads :hands-off :runs :writes :every-tool])

(def code-runners
  "Programs that run whatever code they are given: a shell allowed one of
  them runs anything, allow-list or not (byj-1wv.115.8 — an
  agent told to \"work things out with Python\" was counted as reading). And
  those that run another program they are told of, measured through the
  allow-list's own gate (byj-1wv.115.39): `git -c alias.x=!cmd x`, `git grep
  -O`, `find -exec`, `xargs`, `env`, awk's `system()`, GNU sed's `e`, a
  makefile, and what runs its arguments."
  #{"python" "python3" "node" "deno" "bun" "ruby" "perl" "php" "sh" "bash" "zsh"
    "fish" "pwsh" "lua" "Rscript" "julia" "npx" "uv" "uvx" "bb" "clojure" "clj" "jolt"
    "git" "find" "xargs" "env" "awk" "gawk" "sed" "make" "timeout" "nice" "nohup" "time"
    "sudo" "watch" "npm" "pip" "pip3" "docker" "ssh"})

(def network-programs
  "Programs that reach the network: a shell allowed one reaches it,
  whatever tools the session has (byj-1wv.115.39 — explore's curl)."
  #{"curl" "wget" "ssh" "scp" "sftp" "rsync" "nc" "ncat" "telnet" "ftp" "git"})

(defn tool-reach
  "What a session with `tools` can make happen (byj-1wv.47), read from the
  tools' `:effects`: whether it reads files, what it writes with, which
  programs its shell may run (`bash-allow-commands`, none meaning any), what
  reaches the network, and what hands work on — to another agent, or to
  Claude Code. `:level` is the most it reaches, in `reach-order`: an agent
  that writes is `:writes` whatever else it does; a shell held to an
  allow-list stays `:reads` — unless the list allows a program that runs
  other code (`code-runners`), which runs, or one that reaches the network
  (`network-programs`). Any shell reads every file the machine's user can:
  its arguments are no paths the path gate sees, and the allow-list is no
  sandbox (`permission/command-problem`), so `:reads-anywhere` says so
  (byj-1wv.115.39). An MCP tool reaches another service with the
  connection its server was given: `:services` names the servers, and
  `:service-writes` the tools that may change something there — any whose
  server did not say it is read-only — which make it `:writes`
  (byj-1wv.115.42). `every-tool?` is byj's main agent, no persona."
  [tools {:keys [bash-allow-commands every-tool?]}]
  (let [has (fn [k] (fn [t] (some #{k} (:effects t))))
        names (fn [pred] (vec (sort (keep #(when (pred %) (:name %)) tools))))
        writes (names (has :fs/write))
        services (vec (sort (distinct (keep #(when ((has :mcp/call) %) (get-in % [:mcp :server])) tools))))
        service-writes (names (has :mcp/write))
        network (names (has :network))
        hands-off (names #(or ((has :agent/dispatch) %) (str/starts-with? (str (:name %)) "claude$")))
        shell (when (some #(= "bash" (:name %)) tools)
                {:any (empty? bash-allow-commands) :programs (vec bash-allow-commands)})
        network (vec (concat network (for [p (:programs shell) :when (network-programs p)] (str "bash: " p))))]
    {:level (cond every-tool? :every-tool
                  (empty? tools) :none
                  (or (seq writes) (seq service-writes)) :writes
                  (or (:any shell) (some code-runners (:programs shell)) (seq network)) :runs
                  (seq hands-off) :hands-off
                  :else :reads)
     :reads-files (boolean (or (some (has :fs/read) tools) shell))
     :reads-anywhere (boolean shell)
     :writes writes
     :network network
     :hands-off hands-off
     :services services
     :service-writes service-writes
     :shell shell
     :tool-count (count tools)}))

(defn persona-config
  "What an agent spec makes of a config: its instruction (and tool context) in
  the ReAct instruction's place, its tool globs, its own `:config` — whose
  `:bash-deny-commands` is added to the session's and whose
  `:bash-allow-commands` narrows the session's (`allowed-commands`) — and its
  iteration cap (RFC-018). A child turn adds its depth and chain on top; a
  headless session started as this agent (`jolt run:byj --serve --agent`,
  RFC-031) is this and nothing more."
  [{:keys [instruction tool-context] :as agent} config]
  (merge (dissoc config :agent-instruction :visible-tools)
         (:config agent)
         ;; an agent's deny patterns are added to the session's, never in
         ;; their place: the person's tripwires hold inside every agent
         ;; (byj-gly.356)
         (when-let [deny (seq (concat (:bash-deny-commands config) (get-in agent [:config :bash-deny-commands])))]
           {:bash-deny-commands (vec (distinct deny))})
         ;; and its allow-list narrows the session's, never widens it
         ;; (byj-gly.357)
         (when-let [allow (allowed-commands (:bash-allow-commands config) (get-in agent [:config :bash-allow-commands]))]
           ;; nothing in common is no shell (below); the list stays the
           ;; session's, not the agent's wider one
           {:bash-allow-commands (if (seq allow) allow (vec (:bash-allow-commands config)))})
         {:agent-instruction (str/trim (str instruction
                                            (when-not (str/blank? (str tool-context))
                                              (str "\n\n" tool-context))))
          :visible-tools (vec (:tools agent))
          ;; hiding composes downward: what a parent may not see, no child of
          ;; it may see either
          :hidden-tools (vec (distinct (concat (:hidden-tools config) (:deny-tools agent)
                                               ;; the session and the agent allow no program in
                                               ;; common: no shell, since an empty allow-list is
                                               ;; no limit at all (byj-gly.357)
                                               (when (= [] (allowed-commands (:bash-allow-commands config)
                                                                             (get-in agent [:config :bash-allow-commands])))
                                                 ["bash"]))))
          ;; the agent's own :config is merged above, so a value it set there
          ;; must be considered here or the computed one overwrites it
          :max-iterations (or (:max-iterations agent)
                              (:max-iterations (:config agent))
                              (:max-iterations config))}
         ;; an agent's own predictors, searched first: what its loop's nodes
         ;; name (RFC-022)
         (when-let [d (and (:dir agent) (io/file (:dir agent) "predictors"))]
           (when (.isDirectory d)
             {:predictor-roots (into [(.getPath d)] (:predictor-roots config))}))))

(defn- child-opts
  "The opts of the child turn: the parent's world, minus everything that would
  leak the parent's conversation into it — except the bounded amount
  `:parent-trail-k` allows, which is the point of the trail.

  `tier-lm` is the model the work tier chose, if any. An agent that pins its own
  `:lm` beats it, as an explicit `:lm-config` beats a tier in brainyard; with
  neither, the child runs on its caller's LM."
  [{:keys [name] :as agent} ctx & [tier-lm]]
  (let [config (:config ctx)]
    {:lm (child-lm agent ctx tier-lm)
     :parent-trail (parent-trail-for agent ctx)
     :config (merge (persona-config agent (dissoc config :resume :history-context))
                    {:agent-depth (inc (:agent-depth config 0))
                     :agent-chain (conj (vec (:agent-chain config)) name)
                     ;; a child's cap falls back to :subagent-max-iterations
                     ;; before its caller's own
                     :max-iterations (or (:max-iterations agent)
                                         (:max-iterations (:config agent))
                                         (:subagent-max-iterations config)
                                         (:max-iterations config))})
     ;; an agent may bring its own loop and its own predictors (RFC-022): with
     ;; both, the spec IS the runnable workflow and no second artifact type is
     ;; needed. A relative :loop is resolved beside agent.edn, and a
     ;; `predictors/` directory there is searched first.
     :tree (when-let [t (loop-path agent)]
             ((requiring-resolve 'brainyard.agent/load-tree) t))
     ;; the gate that binds the parent binds the child (RFC-012)
     :permission-fn (:permission-fn ctx)
     :permission-mode-box (:permission-mode-box ctx)
     :allowed-dirs-box (:allowed-dirs-box ctx)
     :on-event (:on-event ctx)
     :params (:params ctx)
     :registry (:registry ctx)
     ;; the same primitives the caller had, narrowed by the globs above —
     ;; MCP tools included, since `main` puts them here (RFC-024)
     :tools (:base-tools ctx)
     ;; the servers themselves, so a child that may reach their tools can also
     ;; ask what is connected. They are processes the run owns, not the
     ;; parent's conversation.
     :mcp-clients (:mcp-clients ctx)
     :mcp-box (:mcp-box ctx)
     ;; one budget and one instance registry for the whole tree: a subagent's
     ;; own subagents are the session's, and are owned by it
     :subagent-counter (:subagent-counter ctx)
     :subagent-registry (:subagent-registry ctx)
     ;; the registry it shares is the one whose lifetime it inherits
     :subagent-scope (:subagent-scope ctx)}))

(defn- roll-up!
  "The child's tokens are the parent's tokens: one turn's usage is what the
  whole tree of turns under it spent. brainyard shares one tracker through the
  session; here the numbers travel back with the answer."
  [st-memory usage]
  (when st-memory
    (swap! st-memory (fn [m]
                       (-> m
                           (update :usage #(merge-with + (or % {})
                                                       (select-keys usage [:input-tokens :output-tokens :llm-calls
                                                                           :cache-read-tokens :cache-write-tokens])))
                           (update :subagent-calls (fnil inc 0)))))))

(defn- emitter [ctx]
  (fn [event data] (events/emit! ctx event data)))

(defn- spend!
  "Take one child turn from the session's budget, or nil when it is spent.
  Dispatches and follow-up asks draw on the same budget: both are a whole turn
  of a model's time, and bounding only the first would bound nothing."
  [ctx]
  (let [counter (:subagent-counter ctx)
        cap (:subagent-max-turns (:config ctx) 32)]
    (if-not counter
      1
      (let [n (swap! counter inc)]
        (if (> n cap) (do (swap! counter dec) nil) n)))))

(defn claim!
  "Atomically move an idle instance to `:running`. True when THIS caller got it:
  the busy check and the transition have to be one step, or two tool calls
  dispatched in the same iteration (`:parallel-tools?`) could both pass the
  check and run one instance's turn twice, interleaving its history."
  [reg id question]
  (let [[old _] (swap-vals! reg update id
                            (fn [i] (cond-> i
                                      (= :idle (:status i))
                                      (assoc :status :running
                                             :last-ask-at (System/currentTimeMillis)
                                             :last-question question))))]
    (= :idle (get-in old [id :status]))))

(defn- run-instance-turn!
  "One turn of instance `id`, whose own previous turns are its history — this is
  what a subagent remembers and a fresh dispatch does not. Returns `::busy`
  when another turn of this instance is already running.

  Waited on with `run-value!!` from the tool's own `blk` thread: cancelling the
  parent turn interrupts that thread, which cancels the child task, which stops
  its LLM call and kills its subprocesses (RFC-009). The status is restored on
  the way out of both exits, so a cancelled ask leaves an idle instance rather
  than one wedged at `:running` forever."
  [reg id question ctx run-turn]
  (if-not (claim! reg id question)
    ::busy
    (let [{:keys [spec previous-turns tier-lm]} (get @reg id)
          config (:config ctx)
          ;; an instance keeps the tier it was dispatched at, as brainyard's
          ;; lands on the sub-agent's own config layer
          opts (-> (child-opts spec ctx tier-lm)
                   (update :on-event tagged id (:agent (get @reg id)))
                   (assoc :history-context (history/format-previous-turns previous-turns config))
                   (assoc-in [:config :agent-instance-id] id)
                   ;; who dispatched it, for its trajectory record: another
                   ;; instance, or "root" (byj-gly.298.1)
                   (assoc-in [:config :agent-owner] (caller-id config)))]
      (try
        (let [result (fx/run-value!! (run-turn question opts))]
          (swap! reg update id
                 (fn [i]
                   (-> i
                       (assoc :status :idle :last-answer (:answer result))
                       (update :answers (fnil inc 0))
                       (update :previous-turns history/append
                               {:question question
                                :answer (:answer result)
                                :terminated-by (:terminated-by result)
                                :trajectory-iterations (:trajectory result)}
                               config))))
          (roll-up! (:st-memory ctx) (:usage result))
          result)
        (catch Throwable e
          (swap! reg update id assoc :status :idle)
          (throw e))))))

(defn ask-hint
  "brainyard's hint, in this port's channel — it writes SCI calls because its
  agents have a code fence, and a model that copied that syntax here would be
  calling a tool that does not exist.

  How long the instance lasts is not the same in both entry points, so the hint
  does not claim it is. A session owns its registry, so an instance outlives the
  turn that made it; a one-shot `ask` builds the registry with the ctx and drops
  both when it returns, so the instance is this turn's. Telling a model it can
  follow up later, in a run where there is no later, is how a promise like this
  stops being worth reading."
  [scope id]
  (if (= :session scope)
    (format (str "This subagent stays alive as \"%s\" and remembers this exchange. Follow up with"
                 " agent-registry$ask {\"id\": \"%s\", \"question\": \"…\"}; end it with"
                 " agent-registry$close {\"id\": \"%s\"} when you are done with it.")
            id id id)
    (format (str "This subagent is alive for the rest of this turn as \"%s\" and remembers this"
                 " exchange. Ask it more with agent-registry$ask {\"id\": \"%s\", \"question\": \"…\"}"
                 " while you are still working; it ends when this turn does, so do not offer it"
                 " to the user as something to come back to.")
            id id)))

(defn dispatch
  "Start a new instance of `agent` on `question`, and leave it alive.

  brainyard dispatches with `:auto-close? false` for exactly this reason: the
  expensive part of a subagent is the context it built, and throwing that away
  at the first answer means the next question pays for it again.

  `run-turn` is `(fn [question opts] Task)` — brainyard.agent/ask-task, injected
  so this namespace knows nothing about the loop it starts.

  `:keep? false` runs it once and registers nothing — brainyard's
  `:auto-close? true`, which is how a `dispatch: agent` skill runs (RFC-017): a
  procedure someone asked for is not a colleague to keep on hand, and
  registering one would evict an instance that is."
  [{:keys [name] :as agent} {:keys [question agent-context work-tier]} ctx run-turn
   & [{:keys [keep?] :or {keep? true}}]]
  (let [config (:config ctx)
        reg (when keep? (:subagent-registry ctx))
        cap (:max-subagents-per-session config 8)
        emit (emitter ctx)
        refuse (fn [result reason]
                 (emit :agent.subagent/refused {:agent name :reason reason})
                 ;; the model reads the message; which guard produced it is for
                 ;; the trace
                 (dissoc result ::reason))]
    (cond
      (str/blank? (str question)) {:error-message "question is required"}

      (refusal config name) (let [r (refusal config name)] (refuse r (::reason r)))

      (nil? (spend! ctx))
      (refuse {:error-message (format "Subagent turn budget exhausted (%d/%d child turns this session). Answer with what you have, or ask an instance you already have."
                                      (:subagent-max-turns config 32) (:subagent-max-turns config 32))}
              :budget)

      :else
      (let [;; what this dispatch costs: the caller's shape of work, clamped into
            ;; the agent's window, resolved through the tier table (RFC-005).
            ;; Inert unless that table is configured.
            {:keys [tier clamped? from]} (resolve-work-tier config agent work-tier)
            tier-lm (when-not (:lm agent) (llm/tier-lm (:agent-lm-tiers config) tier))
            _ (emit :agent.subagent/tier {:agent name :requested-tier from :tier tier
                                          :clamped (boolean clamped?)
                                          :model (:model tier-lm)
                                          :applied (boolean tier-lm)})
            ;; make room first, so the cap bounds LIVE instances rather than
            ;; refusing the new one (brainyard's `evict-subagents-to-cap!`)
            evicted (when reg
                      (vec (for [victim (take (max 0 (inc (- (count @reg) cap))) (evictable @reg))
                                 :let [ids (close-instance! reg (:id victim) :evicted emit)]
                                 i ids]
                             i)))
            id (instance-id name)
            inst (cond-> {:id id :agent name :spec agent :owner (caller-id config)
                          :status :idle :answers 0 :created-at (System/currentTimeMillis)
                          :previous-turns [] :usage {} :tier tier}
                   tier-lm (assoc :tier-lm tier-lm))]
        (when reg (swap! reg assoc id inst))
        (emit :agent.instance/created {:id id :agent name :owner (:owner inst)})
        (emit :agent.subagent/dispatch {:agent name :id id :owner (:owner inst) :depth (:agent-depth config 0)
                                        :chain (vec (:agent-chain config))
                                        :question (str/trim (str question))})
        (let [question (child-question question agent-context)
              result (if reg
                       (run-instance-turn! reg id question ctx run-turn)
                       ;; no registry (a bare `ask`, or a `dispatch: agent` skill):
                       ;; run it once and keep nothing — but it is still this
                       ;; instance, dispatched by its caller: without the two,
                       ;; its trajectory record read as a top-level turn, its
                       ;; instruction as if the person had asked it (byj-gly.376)
                       (let [r (fx/run-value!! (run-turn question (-> (child-opts agent ctx tier-lm)
                                                                      (update :on-event tagged id name)
                                                                      (assoc-in [:config :agent-instance-id] id)
                                                                      (assoc-in [:config :agent-owner] (caller-id config)))))]
                         (roll-up! (:st-memory ctx) (:usage r))
                         r))]
          ;; the id the dispatch named, so whoever is showing the child as
          ;; running can tell which one finished (byj-gly.137)
          (emit :agent.subagent/result (-> (select-keys result [:answer :iterations :terminated-by :usage])
                                           (assoc :id id :agent name)))
          ;; in a turn a slash command became (/init, byj-gly.350), what the
          ;; command's subagent suggests the person type next is still the
          ;; person's: kept beside the turn, for its end, and the model is
          ;; told nothing of it, as for any model's call
          (when-let [m (:st-memory ctx)]
            (when (and (not (:person-call? ctx)) (not (str/blank? (str (:command-input @m))))
                       (not (str/blank? (str (:next-user-prompt result)))))
              (swap! m assoc :command-suggestion (str/trim (str (:next-user-prompt result))))))
          (cond-> (-> (select-keys result [:answer :iterations :terminated-by :usage])
                      (assoc :agent name))
            ;; what the child suggests the person type next is the person's
            ;; when they called it themselves (`/init`, a typed `:agent$…`);
            ;; a model calling it is told nothing of it (byj-gly.337.1)
            (and (:person-call? ctx) (not (str/blank? (str (:next-user-prompt result)))))
            (assoc :next-user-prompt (str/trim (str (:next-user-prompt result))))
            reg (assoc :subagent-id id :askable? true
                       :askable-until (:subagent-scope ctx :turn)
                       :ask-hint (ask-hint (:subagent-scope ctx :turn) id))
            (seq evicted) (assoc :evicted-subagents evicted)))))))

;; --- the registry -----------------------------------------------------------------

(defn describe
  "What the PARENT model reads: the agent's own description, or its first line
  of instruction when it has none."
  [{:keys [description instruction]}]
  (if (str/blank? (str description))
    (str (first (remove str/blank? (str/split-lines (str instruction)))))
    description))

(def ^:private error-message-schema [:map [:error-message :string]])

(def ^:private turn-entries
  "What a child turn hands back, from `brainyard.agent/turn-result` through
  `select-keys`: a key the turn did not produce is absent, not nil."
  [[:answer {:optional true} :string]
   [:iterations {:optional true} :int]
   [:terminated-by {:optional true} [:maybe :keyword]]
   [:usage {:optional true} [:maybe [:map-of :keyword :any]]]])

(def ^:private instance-summary-entries
  "`instance-summary`, as a schema."
  [[:id :string] [:agent :string] [:owner :string] [:status [:enum :idle :running]]
   [:answers :int] [:idle-ms :int] [:last-question :string]])

(defn agent-tool
  "An agent as a registry entry. `:kind :agent`, so `list-tools :type \"agent\"`
  finds it (RFC-007) and a permission glob can match `agent$*` (RFC-012)."
  [agent run-turn]
  {:name (tool-id (:name agent))
   :kind :agent
   :description (str (describe agent)
                     " — delegates to a fresh agent that starts with NO memory of this"
                     " conversation: put everything it needs in `question` and `agent-context`."
                     " Returns its final answer and a `subagent-id`; it stays alive under that id,"
                     " so follow up with agent-registry$ask rather than dispatching it again.")
   :input-schema [:map [:question [:string {:desc "The task for this agent, stated in full."}]]
                       [:agent-context {:optional true} [:string {:desc "Anything it needs that it cannot find for itself: paths, decisions already made, what not to touch."}]]
                       ;; brainyard's router prose: a SHAPE of work, never a model
                       [:work-tier {:optional true} [:string {:desc (str "How hard this work is: \"light\" for a lookup, a status read, a mechanical"
                                    " transform; \"standard\" for ordinary work with a clear success test — the"
                                    " default; \"deep\" for synthesis across sources, judgement under ambiguity,"
                                    " or prose a person will act on. Never name a model.")}]]]
   :output-schema [:or {:desc "the child's final answer; with :subagent-id it stays alive to be asked again"}
             (into [:map [:agent :string]]
                   (concat turn-entries
                           [[:subagent-id {:optional true} :string]
                            [:askable? {:optional true} :boolean]
                            [:askable-until {:optional true} :keyword]
                            [:ask-hint {:optional true} :string]
                            [:evicted-subagents {:optional true} [:vector :string]]
                            [:next-user-prompt {:optional true} :string]]))
             error-message-schema]
   :effects #{:agent/dispatch}
   :fn (fn [args ctx] (dispatch agent args ctx run-turn))})

;; --- the instance commands --------------------------------------------------------

(defn- await-idle!
  "A person's ask of an instance still `:running` waits for its turn to end,
  where a model's is refused: a child parked as a background task works while
  the root is idle, so the line typed in its view would otherwise be refused
  as busy the moment it was sent (byj-gly.345). The instance once it is not
  running — nil when it is gone meanwhile — or, after `ms`, still running.
  Polled on the command's own `blk` thread, as `tasks/wait` is."
  [reg id ms]
  (let [deadline (+ (System/currentTimeMillis) (max 0 ms))]
    (loop []
      (let [inst (get @reg id)]
        (if (and inst (= :running (:status inst)) (< (System/currentTimeMillis) deadline))
          (do (Thread/sleep 50) (recur))
          inst)))))

(defn- reach
  "May the caller touch this instance? brainyard's fences, minus the ones about
  roots and sessions it does not have: you may ask and close what you
  dispatched, and nothing else — except the root, which may ask any subagent
  of its session, as brainyard's `authorize-ask` lets a root caller ask its
  own subtree. A person's typed call runs as the root, so the subagent on
  screen is theirs to ask, whoever dispatched it (byj-gly.344). Closing stays
  the dispatcher's."
  [config id inst verb]
  (let [me (caller-id config)]
    (cond
      (nil? inst) {:error-message (str "Instance not found: " id)}
      (= id me) {:error-message "You cannot ask yourself."}
      (and (= "ask" verb) (= "root" me)) nil
      (not= (:owner inst) me)
      {:error-message (format "Not owned by you (owner: %s). You may only %s subagents you dispatched."
                              (:owner inst) verb)})))

(defn commands
  "brainyard's `agent-registry$*`, less the halves that need what this port does
  not have — background tasks, sessions that share an agent, remote peers."
  [run-turn]
  [{:name "agent-registry$list" :kind :command
    :description (str "List the subagent instances you have dispatched and that are still alive."
                      " Each one remembers its own exchanges — ask it again rather than dispatching a fresh agent that starts from zero.")
    :input-schema [:map]
    :output-schema [:map [:agents [:vector (into [:map] instance-summary-entries)]]
              [:total :int] [:total-turns :int]]
    :effects #{}
    :fn (fn [_ ctx]
          (let [instances (some-> (:subagent-registry ctx) deref)]
            {:agents (mapv instance-summary (sort-by :created-at (vals instances)))
             :total (count instances)
             :total-turns (reduce + 0 (map #(or (:answers %) 0) (vals instances)))}))}

   {:name "agent-registry$detail" :kind :command
    :description "Everything known about one instance, including its last answer. Check it is :idle before asking it again."
    :input-schema [:map [:id [:string {:desc "Instance id, e.g. \"explore/amber-otter-42\" (the suffix alone works when it is unambiguous)."}]]]
    :output-schema [:or (into [:map] (concat instance-summary-entries
                                        [[:last-answer [:maybe :string]] [:turns :int]]))
             error-message-schema]
    :effects #{}
    :fn (fn [{:keys [id]} ctx]
          (let [instances (some-> (:subagent-registry ctx) deref)]
            (if-let [rid (resolve-id instances id)]
              (let [inst (get instances rid)]
                (assoc (instance-summary inst)
                       :last-answer (:last-answer inst)
                       :turns (count (:previous-turns inst))))
              {:error-message (str "Instance not found: " id)})))}

   {:name "agent-registry$ask" :kind :command
    :description (str "Ask a subagent you already dispatched another question. It still has its own previous"
                      " turns, so build on what it found rather than re-explaining — that is the whole reason it is still alive.")
    :input-schema [:map [:id [:string {:desc "Instance id from a dispatch result's :subagent-id."}]]
                        [:question [:string {:desc "The follow-up, stated in full."}]]]
    ;; the dispatch result calls it :subagent-id, and a model carries that name
    ;; forward (byj-gly.395); tools/apply-aliases, RFC-010
    :arg-aliases {:subagent-id :id}
    :output-schema [:or (into [:map [:agent :string]]
                         (concat turn-entries [[:subagent-id :string] [:askable? :boolean]]))
             error-message-schema]
    :effects #{:agent/dispatch}
    :fn (fn [{:keys [id question]} ctx]
          (let [config (:config ctx)
                reg (:subagent-registry ctx)
                instances (some-> reg deref)
                rid (resolve-id instances id)
                inst (get instances rid)
                emit (emitter ctx)]
            (cond
              (str/blank? (str question)) {:error-message "question is required"}
              ;; the id the CALLER wrote, so the message names what it looked for
              (nil? rid) {:error-message (str "Instance not found: " id)}
              (reach config rid inst "ask") (reach config rid inst "ask")
              :else
              (let [waited? (and (:person-call? ctx) (= :running (:status inst)))
                    inst (if waited?
                           (do (emit :agent.subagent/waiting {:id rid :agent (:agent inst)})
                               (await-idle! reg rid (:subagent-ask-wait-ms config 600000)))
                           inst)]
                (cond
                  (nil? inst) {:error-message (format "Instance %s ended while waiting for its turn." rid)}
                  (= :running (:status inst))
                  {:error-message (if waited?
                                    (format "Instance %s is still running after %d s; ask again when it is idle." rid (quot (:subagent-ask-wait-ms config 600000) 1000))
                                    (format "Instance %s is running; cannot ask until idle. Poll agent-registry$detail." rid))}
              ;; an ask starts a child turn, so it answers to the same depth
              ;; ceiling a dispatch does (brainyard re-checks it here too)
              (>= (:agent-depth config 0) (:max-agent-call-depth config 3))
              {:error-message (format "Agent call depth limit reached (%d); cannot ask '%s'."
                                      (:max-agent-call-depth config 3) rid)}
              (nil? (spend! ctx))
              {:error-message (format "Subagent turn budget exhausted (%d/%d child turns this session)."
                                      (:subagent-max-turns config 32) (:subagent-max-turns config 32))}
              :else
              (do (emit :agent.subagent/ask {:id rid :agent (:agent inst) :question (str/trim (str question))})
                  (let [result (run-instance-turn! reg rid (str/trim (str question)) ctx run-turn)]
                    (if (= ::busy result)
                      ;; lost the race with a sibling call in the same iteration
                      (do (swap! (:subagent-counter ctx) dec)
                          {:error-message (format "Instance %s is running; cannot ask until idle. Poll agent-registry$detail." rid)})
                      (do (emit :agent.subagent/result (-> (select-keys result [:answer :iterations :terminated-by :usage])
                                                           (assoc :id rid :agent (:agent inst))))
                          (-> (select-keys result [:answer :iterations :terminated-by :usage])
                              (assoc :agent (:agent inst) :subagent-id rid :askable? true)))))))))))}

   {:name "agent-registry$close" :kind :command
    :description (str "End a subagent you are done with, freeing its slot. Anything it dispatched goes with it."
                      " Closing what you no longer need keeps a useful instance from being evicted at the cap.")
    :input-schema [:map [:id [:string {:desc "Instance id."}]]]
    :output-schema [:or {:desc "the instance and every instance it dispatched"}
             [:map [:closed [:vector :string]]] error-message-schema]
    :effects #{}
    :fn (fn [{:keys [id]} ctx]
          (let [config (:config ctx)
                reg (:subagent-registry ctx)
                instances (some-> reg deref)
                rid (resolve-id instances id)
                inst (get instances rid)]
            (cond
              (nil? rid) {:error-message (str "Instance not found: " id)}
              (reach config rid inst "close") (reach config rid inst "close")
              (= :running (:status inst))
              {:error-message (format "Instance %s is running; it must finish before it can be closed." rid)}
              :else {:closed (close-instance! reg rid :closed (emitter ctx))})))}])

;; --- authoring --------------------------------------------------------------------

(defn write-dir
  "Where a defined agent is written: the project's own roster. brainyard writes
  under `.brainyard/agents/user$agent/`, keeping authored agents in their own
  namespace; here a project's agents are one directory and an authored one is
  an ordinary member of it — the file layout is the same either way."
  [config]
  (if-let [explicit (first (:agent-roots config))]
    (io/file explicit)
    (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "agents")))

(defn persist!
  "Write an agent's three files and return its directory. `:version`
  auto-increments and `:created` is kept, as brainyard's `register-agent!`
  does, so a redefinition is visibly a new version of the same agent rather
  than a different agent with the same name. Every other key of the spec
  there is kept: what the model writes is prose, and a redraft must not
  take away the `:tools` a person gave it (byj-1wv.115.9)."
  [config {:keys [name description instruction tool-context]}]
  (let [d (io/file (write-dir config) name)
        f (io/file d "agent.edn")
        existing (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))
        now (System/currentTimeMillis)]
    (.mkdirs d)
    (spit f (with-out-str
              (clojure.pprint/pprint
               (cond-> (merge (dissoc existing :description)
                              {:name name :scope :project
                               :version (inc (or (:version existing) 0))
                               :created (or (:created existing) now)
                               :updated now})
                 (not (str/blank? (str description))) (assoc :description description)))))
    (spit (io/file d "instruction.md") instruction)
    (if (str/blank? (str tool-context))
      (.delete (io/file d "tool-context.md"))
      (spit (io/file d "tool-context.md") tool-context))
    d))

(def ^:private definition-schema
  [:map
   [:name [:string {:desc "kebab-case, ^[a-z][a-z0-9-]*$ — it becomes agent$<name>"}]]
   [:instruction [:string {:desc "Who this agent is and how it works; it replaces the ReAct instruction for its turns."}]]
   [:description {:optional true} [:string {:desc "One line for the roster: what to delegate to it."}]]
   [:tool-context {:optional true} [:string {:desc "Which of the inherited tools to reach for, and when."}]]])

(defn authoring-tools
  "brainyard's `meta-agent$*` (`common/user_agents.clj`), with its verbs and
  this port's authoring shape (RFC-010, RFC-011).

  What the model may write is PROSE and nothing else — brainyard's rule, and
  here it is also a fence: an agent's `:tools` is an allow list that a child
  inherits instead of its caller's, so a model that could write one could hand
  a subagent a palette wider than its own. `:config` and `:lm` are withheld for
  the same reason (RFC-018)."
  [config run-turn]
  [{:name "meta-agent$create" :kind :command
    :description (slurp (io/resource "brainyard/prompts/meta-agent-create.md"))
    :input-schema definition-schema
    :output-schema [:or [:map [:agent :string] [:id :string] [:version :int] [:persisted :string] [:note :string]]
             [:map {:desc "every problem, one per line"} [:error :string]]]
    :effects #{:fs/write :agents/define}
    :fn (fn [{:keys [name] :as args} ctx]
          (let [spec (select-keys args [:name :description :instruction :tool-context])
                ps (problems spec)]
            (if (seq ps)
              {:error (str "meta-agent$create failed:\n- " (str/join "\n- " ps))}
              (let [d (persist! (:config ctx) spec)]
                ;; callable in the SAME turn, as a user tool is (RFC-010): the
                ;; prompt's roster listing is fixed for this turn, so the model
                ;; is told the id rather than shown it
                (when-let [reg (:tools ctx)]
                  (swap! reg assoc (tool-id name) (agent-tool spec run-turn)))
                (events/emit! ctx :agents/defined {:name name :dir (.getPath d)})
                {:agent name :id (tool-id name)
                 :version (:version (edn/read-string (slurp (io/file d "agent.edn"))))
                 :persisted (.getPath d)
                 :note (str "callable now as " (tool-id name) "; it will be listed in the roster from the next turn")}))))}

   {:name "meta-agent$validate" :kind :command
    :description "Check a definition without saving it; with `sample`, run the unsaved agent on that question once and return its answer."
    :input-schema (conj (schema/all-optional definition-schema)
                        [:sample {:optional true} [:string {:desc "A question to try the unsaved agent on."}]])
    :output-schema [:map [:valid :boolean] [:errors [:vector :string]] [:exists :boolean]
              [:sample-answer {:optional true} [:maybe :string]]]
    :effects #{:dynamic}
    :fn (fn [{:keys [name sample] :as args} ctx]
          (let [spec (select-keys args [:name :description :instruction :tool-context])
                ps (problems spec)
                existing (first (filter #(= name (:name %)) (:agents (load-agents (:config ctx)))))]
            (cond-> {:valid (empty? ps)
                     :errors (vec ps)
                     :exists (boolean existing)}
              (and (empty? ps) (not (str/blank? (str sample))))
              (assoc :sample-answer
                     ;; a dry run, so nothing is written and no instance is kept
                     (:answer (dispatch spec {:question sample} ctx run-turn {:keep? false}))))))}

   {:name "meta-agent$list" :kind :command
    :description "Every agent this project can dispatch, with where it came from."
    :input-schema [:map]
    :output-schema [:map [:agents [:vector [:map [:name :string] [:id :string] [:description :string] [:source :string]
                                      [:version {:optional true} :int] [:dir {:optional true} :string]]]]
              [:count :int]
              [:shadowed {:optional true} [:vector [:map [:name :string] [:dropped :string] [:kept :string]]]]
              [:problems {:optional true} [:vector [:map [:name :string] [:file {:optional true} :string]
                                                    [:problems [:vector :string]]]]]]
    :effects #{:fs/read}
    :fn (fn [_ ctx]
          (let [{:keys [agents shadowed problems]} (load-agents (:config ctx))]
            (cond-> {:agents (mapv (fn [a]
                                     (cond-> {:name (:name a) :id (tool-id (:name a))
                                              :description (describe a)
                                              :source (or (:source a) "builtin")}
                                       (:version a) (assoc :version (:version a))
                                       (:dir a) (assoc :dir (:dir a))))
                                   agents)
                     :count (count agents)}
              (seq shadowed) (assoc :shadowed shadowed)
              (seq problems) (assoc :problems problems))))}

   {:name "meta-agent$read" :kind :command
    :description "One agent's full definition, including the instruction it runs on."
    :input-schema [:map [:name :string]]
    :output-schema [:or [:map [:name :string] [:description {:optional true} :string] [:instruction :string]
                  [:tool-context {:optional true} :string] [:source :string]
                  [:version {:optional true} :int] [:dir {:optional true} :string]
                  [:tools {:optional true} [:sequential :string]] [:deny-tools {:optional true} [:sequential :string]]]
             [:map [:error :string]]]
    :effects #{:fs/read}
    :fn (fn [{:keys [name]} ctx]
          (if-let [a (first (filter #(= name (:name %)) (:agents (load-agents (:config ctx)))))]
            (select-keys a [:name :description :instruction :tool-context :source :version :dir :tools :deny-tools])
            {:error (str "no agent named " name)}))}

   {:name "meta-agent$delete" :kind :command
    :description "Delete an agent you defined. A built-in has no files to delete — define one of the same name to replace it instead."
    :input-schema [:map [:name :string]]
    :output-schema [:or [:map [:deleted :string]] [:map [:error :string]]]
    :effects #{:fs/write}
    :fn (fn [{:keys [name]} ctx]
          (let [a (first (filter #(= name (:name %)) (:agents (load-agents (:config ctx)))))]
            (cond
              (nil? a) {:error (str "no agent named " name)}
              (nil? (:dir a)) {:error (str name " is a built-in agent, so it has no files to delete;"
                                           " define an agent of the same name to replace it")}
              :else
              (let [d (io/file (:dir a))]
                (doseq [f (reverse (file-seq d))] (.delete f))
                (when-let [reg (:tools ctx)] (swap! reg dissoc (tool-id name)))
                (events/emit! ctx :agents/deleted {:name name :dir (.getPath d)})
                {:deleted name}))))}])

(defn agents-and-tools
  "The roster and the registry entries for it. Empty when subagent calls are
  off, so the prompt says nothing about a capability the loop would refuse."
  [config run-turn]
  (if-not (and (:subagents? config true) (:enable-subagent-calls config true))
    {:agents [] :tools [] :commands [] :shadowed [] :problems []}
    (let [{:keys [agents] :as loaded} (load-agents config)]
      (assoc loaded
             :tools (mapv #(agent-tool % run-turn) agents)
             ;; the lifecycle commands ride with the roster: with no agent to
             ;; dispatch there is never an instance to ask. The authoring ones
             ;; do NOT — an empty roster is exactly when defining one matters
             :commands (concat (when (seq agents) (commands run-turn))
                               ;; off unless asked for: the five specs and their
                               ;; guidance are the layer's expensive half, and
                               ;; the half a session is least likely to use
                               (when (:agent-authoring? config)
                                 (authoring-tools config run-turn)))))))
