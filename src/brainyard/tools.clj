(ns brainyard.tools
  "The built-in tools, ported from brainyard's `agent.common.tools` (bash,
  read-file, write-file, update-file, grep) plus list-dir.

  A tool is a map:
    {:name \"read-file\" :description \"...\"
     :input-schema [:map [:path [:string {:desc \"...\"}]]
                         [:limit {:optional true} :int]]
     :fn (fn [args ctx] result)}

  :input-schema is brainyard's: a malli [:map …] of the arguments (byj-gly.237).
  `call-tool` checks args against it, coercing what models commonly stringify,
  and returns {:error-message ...} instead of throwing, as brainyard's
  call-tool does (RFC-030)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.hooks :as hooks]
            [brainyard.events :as events]
            [brainyard.schema :as schema]))

;; --- paths --------------------------------------------------------------------

(defn- resolve-path [ctx path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (io/file (or (get-in ctx [:config :working-dir]) ".") path))))

;; --- bash ------------------------------------------------------------------------

(defn- truncate [s max-chars]
  (if (> (count s) max-chars)
    (str (subs s 0 max-chars) "\n…[truncated " (- (count s) max-chars) " chars]")
    s))

(defn timed-out
  "What a command that ran out of time says: that it and every process it
  started were stopped — they are, the process tree killed — so the model
  does not go looking for them (byj-gly.315.11: in the sim pilot it ran
  `pkill`, and the gate put that to the person); and how to give it longer."
  [timeout]
  (str "command timed out after " timeout " ms; it and every process it started were stopped, so nothing of it is"
       " left running. If it needs longer, run it again with a larger :timeout (ms)."))

(defonce ^:private background
  ;; process group id → {:session :command}: groups a bash call left running
  ;; (byj-gly.315.16)
  (atom {}))

(defn- group-survivors
  "The processes still in process group `pgid`: `[{:pid :command}]`."
  [pgid]
  (let [pids (str/split-lines (str/trim (str (:out (fx/sh {:out :string :err :string} "pgrep" "-g" (str pgid))))))
        pids (remove str/blank? pids)]
    (if (empty? pids)
      []
      (let [ps (:out (fx/sh {:out :string :err :string} "ps" "-o" "pid=,command=" "-p" (str/join "," pids)))]
        (vec (for [l (str/split-lines (str ps))
                   :let [[_ pid cmd] (re-matches #"\s*(\d+)\s+(.*)" l)]
                   :when pid]
               {:pid pid :command (let [c (str/trim cmd)] (if (> (count c) 120) (str (subs c 0 120) "…") c))}))))))

(defn stop-background!
  "Stop the process groups bash calls left running — `session-id`'s, or every
  one with no session given — and forget them. How many it stopped."
  [& [session-id]]
  (let [[before _] (swap-vals! background #(into {} (remove (fn [[_ v]] (or (nil? session-id) (= session-id (:session v)))) %)))
        mine (filter (fn [[_ v]] (or (nil? session-id) (= session-id (:session v)))) before)]
    (doseq [[pgid _] mine]
      ;; `--` before the group: procps-ng 3.3.17's kill (Ubuntu 22.04) took
      ;; `kill -TERM -<pgid>` as its caller's own group, and ended the shell
      ;; that ran byj (provenance P-62)
      (fx/sh {:out :string :err :string} "kill" "-s" "TERM" "--" (str "-" pgid)))
    (count mine)))

(defonce ^:private background-exit-hook
  ;; this process ending ends what its bash calls left running
  (delay (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (stop-background!))))))

(defn bash [{:keys [command timeout]} ctx]
  (let [timeout (or timeout 120000)
        ;; the command leads a process group of its own and says which, so
        ;; what it leaves running in the background can be found after it
        ;; ends — a server started with `&` outlived the session, untracked
        ;; (byj-gly.315.16). While the TUI owns the terminal, `fx/start`
        ;; already starts it so; a second setsid would fail.
        pid-file (java.io.File/createTempFile "byj-bash-" ".pid")
        wrapped (str "echo $$ > " (pr-str (.getPath pid-file)) "; " command)
        argv (concat (when-not (fx/detached?) @fx/no-terminal-argv) ["bash" "-c" wrapped])
        ;; read as it is printed, so a command stopped by its timeout still
        ;; says what it printed — a login's code, a server's port — instead of
        ;; nothing (byj-gly.363). The timeout — or a cancelled turn — kills the
        ;; whole process tree, not just the wait on it (RFC-009 rule 1)
        printed (atom [])
        r (fx/run-value!! (fx/timeout (apply fx/streaming-process {:dir (get-in ctx [:config :working-dir] ".")
                                                                   :in "" :err :out}
                                             ;; and said as it comes, so the person
                                             ;; sees it while the call runs (byj-gly.364)
                                             (fn [line]
                                               (swap! printed conj line)
                                               (events/emit! ctx :agent.tool/output {:tool-name "bash" :line line :call (:tool-call-index ctx)}))
                                             argv)
                                      timeout ::timeout))
        pgid (try (some-> (slurp pid-file) str/trim not-empty) (catch Throwable _ nil))
        _ (.delete pid-file)
        ;; only a group the command led: without setsid, $$ is no group's id
        left (when (and pgid (or (fx/detached?) @fx/no-terminal-argv) (not= ::timeout r))
               (group-survivors pgid))]
    (when (seq left)
      @background-exit-hook
      (swap! background assoc pgid {:session (get-in ctx [:config :session-id]) :command command}))
    (if (= ::timeout r)
      {:status "timeout" :exit-code nil
       :output (let [so-far (str/join "\n" @printed)]
                 (if (str/blank? so-far)
                   (timed-out timeout)
                   (str (timed-out timeout) "\nWhat it printed before it was stopped:\n"
                        ;; the end is what it was doing when it stopped
                        (let [n (count so-far)] (if (> n 30000) (str "…" (subs so-far (- n 30000))) so-far)))))}
      (cond-> {:status (if (zero? (:exit r)) "completed" "failed")
               :exit-code (:exit r)
               :output (str (truncate (str (:out r)) 30000)
                            (when (seq left)
                              (str "\n[still running, started by this command: "
                                   (str/join "; " (map #(str "pid " (:pid %) " " (:command %)) left))
                                   " — stopped when the session ends; stop it yourself (kill <pid>) when you are done with it]")))}
        (seq left) (assoc :left-running left)))))

;; --- files -------------------------------------------------------------------

(defn- printed
  "How many characters `s` takes once the result map holding it is printed:
  a newline, a tab or a quote is two. A page sized by `count` alone passed
  the cap once printed and was cut again (byj-gly.218)."
  [s]
  (- (count (pr-str (str s))) 2))

(defn- page
  "Numbered lines of `lines` from index `i`, character `c` of it, up to `limit`
  lines, within `room` characters as printed: `{:content :cut}`, `:cut` the
  `[line column]` (1-based) to read on from when the page ended before the
  range did. A line longer than the page is cut into pieces, so a file of one
  enormous line — a printed data structure, minified JSON — is readable a page
  at a time; handed back whole, it was cut past the cap and spilled, and
  reading the spill whole was cut and spilled again (byj-gly.190)."
  [lines i c limit room]
  (let [end (min (count lines) (+ i (or limit (count lines))))]
    (loop [i i c c used 0 out []]
      (if (>= i end)
        ;; ended by :limit with more of the file after it: say where it goes
        ;; on, as a page ended by room does (byj-gly.228)
        (cond-> {:content (str/join "\n" out)}
          (< end (count lines)) (assoc :more (inc end)))
        (let [text (subs (nth lines i) (min c (count (nth lines i))))
              label (str (inc i) "\t" (when (pos? c) "…"))
              ;; the line, and the newline joining it to the next
              need (+ (printed (str label text)) 2)]
          (cond
            (<= (+ used need) room)
            (recur (inc i) 0 (+ used need) (conj out (str label text)))

            (empty? out)
            ;; as much of the line as fits once printed: shrink by the excess
            ;; until it does, since escapes make it longer than it counts
            (let [budget (- room 2)
                  n (loop [n (max 1 (min (count text) (- budget (printed label))))]
                      (let [over (- (printed (str label (subs text 0 n) "…")) budget)]
                        ;; proportionally: a line of quotes prints at twice
                        ;; its length, and subtracting the excess undershot to 2
                        (if (and (pos? over) (> n 1))
                          (recur (max 1 (min (dec n) (quot (* n budget) (+ budget over)))))
                          n)))]
              {:content (str label (subs text 0 n) "…") :cut [(inc i) (+ c n 1)]})

            :else {:content (str/join "\n" out) :cut [(inc i) (inc c)]}))))))

(def disk-fs
  "Where the file tools read and write text: the disk. `:read` is a file's
  text, or nil when there is none; `:write` puts text there, nil when it did
  or why not. `byj acp` gives the tools a client editor's in its place, whose
  buffers hold what the person has not saved (RFC-053, RFC-067 §8)."
  {:read (fn [^java.io.File f] (when (.isFile f) (slurp f)))
   :write (fn [^java.io.File f text]
            (when-let [parent (.getParentFile f)] (.mkdirs parent))
            (spit f text)
            nil)})

(defn read-file* [fs {:keys [path offset limit column]} ctx]
  (let [f (resolve-path ctx path)
        content (when-not (.isDirectory f) ((:read fs) f))]
    (cond
      (.isDirectory f) {:error (str "is a directory: " path " (use list-dir)")}
      (nil? content) {:error (str "file not found: " path)}
      :else
      (let [cap (get-in ctx [:config :max-output-chars] 16000)]
        (if (and (nil? offset) (nil? limit) (nil? column)
                 ;; whole, if the printed result — content, path and size —
                 ;; fits (byj-gly.218)
                 (<= (+ (printed content) (count (str path)) 64) cap))
          ;; :total-lines whole or paged: a body cannot tell which it got,
          ;; and one that counted on it read 0 for every file (byj-gly.243)
          {:path path :size (count content) :total-lines (count (str/split-lines content)) :content content}
          ;; a page never passes the cap, whatever range was asked for, so no
          ;; read of a file is cut and spilled
          (let [lines (vec (str/split-lines content))
                start (max 0 (dec (or offset 1)))
                {page :content [line col :as cut] :cut more :more}
                (page lines start (max 0 (dec (or column 1))) limit (int (* 0.85 cap)))]
            (cond-> {:path path :size (count content) :total-lines (count lines) :content page}
              more (assoc :next-offset more
                          :note (str "lines " (inc start) "–" (dec more) " of " (count lines)
                                     ". Read on with {:offset " more " :limit 200}, or grep the file for what you need."))
              cut (assoc :next-offset line
                         :next-column col
                         :note (str "not all of it fits in one result: more from line " line " of " (count lines)
                                    (when (> col 1) (str ", column " col " (the line is longer than a page)"))
                                    ". Read on with {:offset " line
                                    (when (> col 1) (str " :column " col))
                                    " :limit 200}, or grep the file for what you need.")))))))))

(defn read-file [args ctx] (read-file* disk-fs args ctx))

(defn write-file* [fs {:keys [path content append]} ctx]
  (let [f (resolve-path ctx path)
        why ((:write fs) f (if append (str ((:read fs) f) content) content))]
    (if why {:error why} {:path path :chars (count content)})))

(defn write-file [args ctx] (write-file* disk-fs args ctx))

(defn- count-occurrences [s pattern]
  (loop [from 0 n 0]
    (if-let [i (str/index-of s pattern from)]
      (recur (+ i (max 1 (count pattern))) (inc n))
      n)))

(defn update-file* [fs {:keys [path pattern replacement all? expect-count]} ctx]
  (let [f (resolve-path ctx path)
        s (when-not (.isDirectory f) ((:read fs) f))]
    (if (nil? s)
      {:error (str "file not found: " path)}
      (let [n (count-occurrences s pattern)]
        (cond
          (zero? n) {:error (str "pattern not found in " path)}
          (and expect-count (not= n expect-count))
          {:error (str "expected " expect-count " occurrence(s), found " n)}
          (and (> n 1) (not all?))
          {:error (str "pattern occurs " n " times; pass all? true or a more specific pattern")}
          :else
          (let [out (if all?
                      (str/replace s pattern replacement)
                      (str/replace-first s pattern replacement))]
            (if-let [why ((:write fs) f out)]
              {:error why}
              {:path path :replaced (if all? n 1)})))))))

(defn update-file [args ctx] (update-file* disk-fs args ctx))

(defn list-dir [{:keys [path]} ctx]
  (let [f (resolve-path ctx (or path "."))]
    (if-not (.isDirectory f)
      {:error (str "not a directory: " path)}
      {:path (or path ".")
       :entries (->> (.listFiles f)
                     (map #(str (.getName %) (when (.isDirectory %) "/")))
                     sort vec)})))

(defn- searching-agent-state?
  "Is the search aimed inside .brainyard itself? Then it is not noise but the
  point, and the exclusion below would drop everything."
  [path]
  (boolean (some->> path (re-find #"(^|/)\.brainyard(/|$)"))))

(defn grep [{:keys [pattern path max-results include-exts]} ctx]
  (let [max-results (or max-results 50)
        includes (mapcat #(vector (str "--include=*." (str/replace % #"^\." ""))) include-exts)
        ;; .brainyard is the agent's own state — its tools, hooks and logs are
        ;; not project content, and a search over "." used to find them
        ;; (divergence `search-skips-agent-state`)
        excludes (cond-> ["--exclude-dir=.git"]
                   (not (searching-agent-state? path)) (conj "--exclude-dir=.brainyard"))
        cmd (concat ["grep" "-rnHIE"] excludes includes ["--" pattern (or path ".")])
        ;; spread: jolt.process takes no command vector (provenance P-1)
        r (fx/run-value!! (apply fx/process-task {:dir (get-in ctx [:config :working-dir] ".") :in ""
                                                  :out :string :err :string}
                                 cmd))
        lines (str/split-lines (str (:out r)))
        lines (remove str/blank? lines)]
    (if (and (= 2 (:exit r)) (empty? lines))
      {:error (str/trim (str (:err r)))}
      {:matches (vec (take max-results lines))
       :total (count lines)
       :truncated (> (count lines) max-results)})))

;; --- registry --------------------------------------------------------------

;; --- built-in specs ------------------------------------------------------------

(def impls
  "Kernel fns primitive specs name by :impl (RFC-010)."
  {:brainyard.tools/bash bash
   :brainyard.tools/read-file read-file
   :brainyard.tools/write-file write-file
   :brainyard.tools/update-file update-file
   :brainyard.tools/grep grep
   :brainyard.tools/list-dir list-dir})

(defn load-spec
  "A primitive tool from resources/brainyard/tools/<name>.edn, its :impl looked
  up in `impls` (a map of keyword → fn)."
  ([name] (load-spec name impls))
  ([name impls]
  (let [spec (edn/read-string (slurp (or (io/resource (str "brainyard/tools/" name ".edn"))
                                         (throw (ex-info (str "no tool spec resource for " name) {:name name})))))
        f (or (get impls (:impl spec))
              (throw (ex-info (str "tool " name " names unknown :impl " (:impl spec)) {:spec spec})))]
    ;; :output-schema is checked against, and the model reads the one line
    ;; written from it (RFC-030 stage 2, `schema/output-text`)
    (assoc spec :fn f :kind :primitive))))

(def builtin-tools
  "The primitive tools, from resources/brainyard/tools/ (listing order from its
  index.edn)."
  (mapv load-spec (edn/read-string (slurp (io/resource "brainyard/tools/index.edn")))))

(defn tools-by-name [tools] (into {} (map (juxt :name identity)) tools))

(defn file-tools
  "read-file, write-file and update-file as the built-ins are, reading and
  writing through `fs` (`disk-fs`'s shape) in place of the disk: `byj acp`'s
  for a client that edits the files itself (RFC-053)."
  [fs]
  (let [impl {"read-file" read-file* "write-file" write-file* "update-file" update-file*}]
    (vec (for [t builtin-tools :let [f (impl (:name t))] :when f]
           (assoc t :fn (fn [args ctx] (f fs args ctx)))))))

;; --- invocation --------------------------------------------------------------

(defn normalize-args
  "Keywordize arg keys; accept [{:name :value}] pairs as brainyard does."
  [args]
  (let [m (if (sequential? args)
            (into {} (map (juxt #(or (:name %) (get % "name")) #(or (:value %) (get % "value")))) args)
            (or args {}))]
    (into {} (map (fn [[k v]] [(keyword (name k)) v])) m)))

(defn apply-aliases
  "`args` with the names a tool's spec also takes (`:arg-aliases {alias
  canonical}`, byj-gly.394) renamed to its own, and `[args problems]`: an
  alias given beside its canonical name with a different value is a problem,
  the same value is not. Models trained on other tools send their names —
  `old`/`new` to update-file, every call of it the logs refused. brainyard's
  `arg-alias->canonical` is one table for every tool, and the canonical name
  silently wins over its alias (divergence `update-file-takes-old-new`)."
  [tool args]
  (reduce (fn [[a ps] [alias canon]]
            (if-not (contains? a alias)
              [a ps]
              (let [v (get a alias)
                    a (dissoc a alias)]
                (cond
                  (not (contains? a canon)) [(assoc a canon v) ps]
                  (= v (get a canon)) [a ps]
                  :else [a (conj (or ps []) (str "give `" (name canon) "` or `" (name alias) "`, not both"))]))))
          [args nil]
          (:arg-aliases tool)))

(defn- check-args
  "{:args coerced} or {:problems [...]}, against the tool's malli
  `:input-schema` (RFC-030, `brainyard.schema`), after its aliases — a
  missing argument's problem names them, so the model knows either works."
  [tool args]
  (let [[args alias-problems] (apply-aliases tool args)]
    (if (seq alias-problems)
      {:problems alias-problems}
      (let [r (schema/check-args tool args)
            also (fn [canon] (seq (for [a (sort (for [[a c] (:arg-aliases tool) :when (= c canon)] (name a)))] (str "`" a "`"))))]
        (cond-> r
          (and (seq (:problems r)) (seq (:arg-aliases tool)))
          (update :problems (fn [ps] (mapv (fn [p]
                                             (if-let [[_ k] (re-matches #"missing required `([^`]+)`" p)]
                                               (if-let [as (also (keyword k))] (str p " (or " (str/join ", " as) ")") p)
                                               p))
                                           ps))))))))

(defn- invalid-args [tool problems]
  {:error-message (str "Invalid tool args for " (:name tool) ": " (str/join "; " problems))})

(defn- blocked-result
  "brainyard's `blocked-tool-result`: what a blocked call returns. The tool
  channel ends the turn on it (agent/tool-dispatch-action); a user tool body
  receives it as a value."
  [decision]
  (cond-> {:hook-blocked true
           :reason (or (:reason decision) "blocked by :agent.tool-use/pre hook")
           :by (:by decision)}
    (:answer decision) (assoc :answer (:answer decision))))

(defn failed?
  "Did this call fail? The three shapes this layer produces or conventionally
  returns:

  - `{:error-message …}` — invalid args, or the tool threw (`call-tool`);
  - `{:hook-blocked true …}` — a gate refused it (`blocked-tool-result`);
  - `{:error …}` — a `$`-command reporting its own failure, which is the
    convention every command family here follows.

  Here rather than in the caller, because it IS this layer's contract: a reader
  that pattern-matched result text would be guessing at what this already
  knows (`byj-gly.87`)."
  [raw]
  (boolean (and (map? raw)
                (or (some? (:error-message raw))
                    (some? (:error raw))
                    (true? (:hook-blocked raw))))))

(defn call-tool
  "Validate args against the tool's :input-schema and invoke it. Never throws —
  except a cancellation. Fires brainyard's tool hooks around it (RFC-011):
  :agent.tool-use/rejected for invalid args, the gated :agent.tool-use/pre
  (block, replace or modify-args), then :agent.tool-use/post."
  [tool args ctx]
  (let [args (normalize-args args)
        {checked :args :keys [problems]} (check-args tool args)]
    (if (seq problems)
      (let [result (invalid-args tool problems)]
        (hooks/fire! ctx :agent.tool-use/rejected
                     {:tool-name (:name tool) :args args :result result :reason :invalid-args})
        result)
      (let [pre {:tool-name (:name tool) :args checked :call-id (str (random-uuid)) :depth hooks/*depth*}
            decision (hooks/fire-decision! ctx :agent.tool-use/pre pre)
            run (fn [args]
                  (let [[ok? result]
                        (try
                          [true ((:fn tool) args ctx)]
                          (catch Throwable e
                            ;; a cancelled turn is not a tool failure (RFC-009 rule 7)
                            (if (fx/cancelled? e)
                              (throw e)
                              [false {:error-message (str (:name tool) " failed: " (or (ex-message e) (str e)))}])))]
                    ;; a result its own :output-schema does not describe is a bug in
                    ;; the tool or its description — said, never fatal: the
                    ;; call worked (RFC-030). Outside the tool's own try, so a
                    ;; check that fails cannot report a completed call as a
                    ;; failed one, and invite a retry of what it did (byj-gly.234)
                    (when (and ok? (schema/tool-output-schema tool))
                      (try
                        (let [ps (schema/output-problems tool result)]
                          (schema/note-output! (:name tool) ps)
                          (when ps
                            (events/emit! ctx :tools/output-mismatch {:tool-name (:name tool) :problems ps})))
                        (catch Throwable _ nil)))
                    result))
            [result flags]
            (case (:result decision)
              :block [(blocked-result decision) {:hook-blocked true :blocked-by (:by decision)}]
              :replace [(:replacement decision) {:hook-replaced true :replaced-by (:by decision)}]
              :modify-args (let [{modified :args :keys [problems]} (check-args tool (normalize-args (:args decision)))]
                             [(if (seq problems) (invalid-args tool problems) (run modified))
                              {:hook-modified-args true :modified-by (:by decision) :effective-args (or modified (:args decision))}])
              [(run checked) nil])]
        (hooks/fire! ctx :agent.tool-use/post (merge (dissoc pre :depth) {:result result} flags))
        result))))

(defn args-line
  "A tool's arguments as one map literal, `{:dir string, :field string,
  :case-sensitive? boolean}` — `?` marking an optional one; nil when it takes
  none."
  [tool]
  (when-let [ins (seq (schema/inputs tool))]
    (str "{" (str/join ", " (for [{k :name :keys [type optional?]} ins]
                              (str ":" (clojure.core/name k) (when optional? "?") " " type)))
         "}")))

(defn format-tools-compact
  "One line per tool: its id and the first line of its description.

  brainyard's `format-agent-tools-compact` prints the id ALONE, because a CoAct
  agent also gets a sandbox function directory to look the tool up in. This port
  has neither that nor a code channel, so an id by itself does not distinguish
  `write-file` from `update-file`; one line does, at a tenth of the full spec.
  Arguments come from `get-tool-info`, which is why this is only used when
  discovery is on (RFC-003). `:saved-args? true` puts a user tool's arguments
  on its line (`args-line`)."
  [tools & {:keys [saved-args?]}]
  (str/join
   "\n"
   (for [{:keys [name description output-schema] :as tool} tools]
     (str "- `" name "`"
          ;; a user tool's arguments on its line, when asked (byj-gly.251): it is
          ;; the one tool here the model may have written itself, and calling it
          ;; should not cost a get-tool-info that writing its body again does not
          (when (and saved-args? (= :composite (:kind tool)))
            (some->> (args-line tool) (str " ")))
          (when-not (str/blank? (str description))
            (str " — " (first (str/split-lines (str description)))))
          ;; the RESULT shape stays. Omitting it cost both wrong answers in the
          ;; Tool Lisp authoring eval (RFC-010 'Authoring evidence'): a model
          ;; composing one tool onto another has to know what comes back, and
          ;; it cannot see that by calling the tool first.
          (when output-schema (str "\n  Outputs: " (schema/output-text output-schema)))))))

(defn format-tools
  "Markdown tool listing for the system prompt, after brainyard's
  `formatters/format-agent-tools`."
  [tools]
  (str/join
   "\n"
   (for [{:keys [name description output-schema] :as tool} tools
         :let [inputs (schema/inputs tool)]]
     (str "- `" name "` — " description
          (when (seq inputs)
            (str "\n  Inputs:\n"
                 (str/join "\n"
                           (for [{k :name :keys [type optional? desc]} inputs]
                             (str "    - `" (clojure.core/name k) "` : " type
                                  (when optional? " (optional)")
                                  (when desc (str " — " desc)))))))
          ;; result shapes caused both wrong answers in the Tool Lisp authoring
          ;; eval (RFC-010 'Authoring evidence'), so they are shown
          (when output-schema (str "\n  Outputs: " (schema/output-text output-schema)))))))
