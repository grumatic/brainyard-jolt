(ns brainyard.mcp
  "MCP servers as tools (RFC-024) — brainyard's `agent/mcp/{client,integration,
  permission}.clj`, stdio only.

  An MCP server is a subprocess speaking newline-delimited JSON-RPC 2.0 on its
  stdin and stdout. Its tools become ours, named `mcp$<server>$<tool>`, and from
  the loop's point of view they are tools like any other: listed, gated, traced.

  Three things about the transport are not optional, and each cost a probe to
  learn:

  - **Reads are raw bytes.** `clojure.java.io/reader` over a subprocess stream
    delivers nothing until the process exits (`fx/streaming-process`), so
    `.readLine` on a long-lived server blocks forever. brainyard uses a
    `BufferedReader` and is fine on the JVM; here it hangs (divergence
    `mcp-reads-raw-bytes`).
  - **Responses are matched by `:id`.** A server may interleave notifications
    with replies — the reference server sends `notifications/tools/list_changed`
    before answering `tools/list` — so reading the next line and calling it the
    answer is wrong.
  - **One request at a time, per server.** brainyard has the calling thread
    drain the pipe until it sees its own id, which works only while calls never
    overlap. A lock per client costs three lines and removes the hazard.

  Only stdio is ported. A remote server is still reachable by spawning
  `npx -y mcp-remote <url>`, which is what brainyard's own HTTP seeds do
  (divergence `mcp-is-stdio-only`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [jolt.process :as p]))

(def protocol-version "2024-11-05")

;; --- the wire ------------------------------------------------------------------

(defn- send!
  "One JSON-RPC message, newline-terminated, UTF-8. brainyard wraps its streams
  without naming a charset and gets the platform default; jolt's strings are
  codepoint-indexed, so it is named here."
  [client msg]
  (let [os (:out-stream client)]
    (.write os (.getBytes (str (json/write-str msg) "\n") "UTF-8"))
    (.flush os)))

(defn- read-message
  "One line from the server, decoded, or `:timeout`/`:eof`.

  Raw bytes split on the byte `0x0A`, as `fx/streaming-process` does and for the
  same reason: a reader over this stream would buffer until the process exits.
  `0x0A` cannot appear inside a UTF-8 sequence, so a line is never cut through a
  character."
  [client deadline]
  (let [is (:in-stream client)
        buf (byte-array 1)
        baos (java.io.ByteArrayOutputStream.)]
    (loop []
      (cond
        (> (System/currentTimeMillis) deadline) :timeout
        :else (let [n (try (.read is buf) (catch Throwable _ -1))]
                (cond
                  (neg? n) :eof
                  (= 10 (aget buf 0)) (let [s (String. (.toByteArray baos) "UTF-8")]
                                        (try (json/read-str s)
                                             (catch Throwable _ {::unparseable s})))
                  :else (do (.write baos (int (aget buf 0))) (recur))))))))

(def stderr-grace-ms
  "How long a death may take to become one. A server that has broken the pipe
  is on its way out, but writing its reason and exiting races the client's next
  request — so the reason arrives a moment after the failure does."
  250)

(defn- stderr-tail
  "The last few lines the server wrote to stderr, or nil.

  It is spawned with `:err :string`, which jolt hands over on deref — so a
  process still RUNNING is never derefed indefinitely, because that would block
  until it was not. A death reported without this says only that the server
  stopped, never why (`byj-gly.72`).

  **Every caller is already reporting a death**, which is what makes a bounded
  wait right here and nowhere else. Without one the diagnostic is a race: the
  server writes `FATAL: missing API key …` and exits while the client is
  failing its next request, so on an idle machine the reason arrives and on a
  busy one it does not — silently, and exactly when it is most wanted
  (`byj-gly.92`, caught by `dev/flake-hunt.sh`)."
  [client]
  (try
    (let [proc (:proc client)
          e (:err proc)
          raw (cond
                (string? e) e
                :else (let [r (deref proc stderr-grace-ms ::running)]
                        (when-not (= ::running r) (:err r))))
          s (str/trim (str raw))]
      (when-not (str/blank? s)
        (str/join "\n" (take-last 5 (str/split-lines s)))))
    (catch Throwable _ nil)))

(defn- with-stderr [client msg]
  (if-let [tail (stderr-tail client)] (str msg " — stderr: " tail) msg))

(defn- stopped
  "The reply a dead server produces, in the shape a live one's error has."
  [client]
  {:error {:code -1 :message (with-stderr client "the server stopped")}})

(defn- answer-request!
  "Answer a request the server makes of us while we wait (byj-ahf.20):
  `elicitation/create` by `ask` — `(ask params)` → the result, `{:action
  \"accept\"|\"decline\"|\"cancel\" :content {}}` — declined where there is
  none; `ping` with nothing; anything else as not found. A server left
  without an answer would wait on us as we wait on it."
  [client {:keys [id method params]} ask]
  (let [result (case method
                 "elicitation/create" {:result (if ask
                                                 (try (ask params) (catch Throwable _ {:action "cancel"}))
                                                 {:action "decline"})}
                 "ping" {:result {}}
                 {:error {:code -32601 :message (str "byj answers no " method)}})]
    (try (send! client (merge {:jsonrpc "2.0" :id id} result))
         (catch Throwable _ nil))))

(defn- await-id
  "Read until the reply carrying `id`. Notifications and stray replies are
  skipped — a server interleaves them with answers — and a request the
  server makes meanwhile is answered (`answer-request!`), by `ask` when it
  is an elicitation."
  [client id timeout-ms & [ask]]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [m (read-message client deadline)]
        (cond
          ;; the stream ended or the deadline passed — say which, in words a
          ;; model can act on rather than a code it cannot
          (= :eof m) (stopped client)
          (= :timeout m) {:error {:code -1 :message (str "no reply within " timeout-ms "ms")}}
          (keyword? m) {:error {:code -1 :message (name m)}}
          (::unparseable m) (recur)
          (and (:method m) (contains? m :id)) (do (answer-request! client m ask) (recur))
          (= id (:id m)) m
          :else (recur))))))

(defn- request!
  "Send a request and wait for its reply. Serialised per client: brainyard has
  the calling thread drain the pipe until it sees its own id, which steals a
  reply the moment two calls overlap. `ask` answers the elicitations the
  server makes before replying."
  [client method params & [ask]]
  (locking (:lock client)
    (let [id (swap! (:next-id client) inc)]
      ;; writing to a dead child THROWS (`process: write to child failed`), and
      ;; a read failure already returns rather than throws — so a caller that
      ;; handles one and not the other handles neither. `connect-all!` reaches
      ;; `tools/list` through here, and its whole job is that one bad server
      ;; costs the others nothing (`byj-gly.71`).
      (if (try (send! client (cond-> {:jsonrpc "2.0" :id id :method method}
                               params (assoc :params params)))
               true
               (catch Throwable _ false))
        (await-id client id (:timeout-ms client 30000) ask)
        (stopped client)))))

(defn- notify!
  "A notification expects no reply, so a failed write is nothing to report to —
  it is swallowed, and the next request says the server is gone."
  [client method params]
  (locking (:lock client)
    (try (send! client (cond-> {:jsonrpc "2.0" :method method} params (assoc :params params)))
         (catch Throwable _ nil))))

;; --- lifecycle -----------------------------------------------------------------

(defn connect!
  "Spawn a server and complete the handshake. Returns a client, or
  `{:error …}` — a server that will not start is reported, never thrown: one
  broken entry must not cost a session its other servers."
  [server-name {:keys [command args env working-dir timeout-ms connect-timeout-ms read-only-tools]}]
  (if (str/blank? (str command))
    {:error (str "mcp server " server-name ": no :command")}
    (try
      (let [;; jolt's :env REPLACES the environment, so the server's additions
            ;; are merged over the process's own (provenance P-1's neighbour)
            env* (merge (into {} (System/getenv))
                        (into {} (for [[k v] env] [(name k) (str v)])))
            proc (apply fx/start (cond-> {:in :stream :out :stream :err :string :env env*}
                                    working-dir (assoc :dir working-dir))
                        (cons (str command) (map str args)))
            client {:name server-name :proc proc
                    :out-stream (:in proc) :in-stream (:out proc)
                    :next-id (atom 0) :lock (Object.)
                    :timeout-ms (or timeout-ms 30000)
                    ;; tools its spec says only read, whatever the server
                    ;; says of them (byj-1wv.115.44)
                    :read-only-tools (set (map str read-only-tools))}
            reply (locking (:lock client)
                    (let [id (swap! (:next-id client) inc)]
                      (send! client {:jsonrpc "2.0" :id id :method "initialize"
                                     :params {:protocolVersion protocol-version
                                              ;; `sampling` belongs INSIDE
                                              ;; capabilities; brainyard's stdio
                                              ;; path puts it beside them
                                              ;; (divergence `mcp-capabilities-shape`)
                                              ;; elicitation: a server may ask the person
                                              ;; mid-call, answered through the session's
                                              ;; permission prompt (byj-ahf.20)
                                              :capabilities {:roots {:listChanged false}
                                                             :sampling {}
                                                             :elicitation {}}
                                              :clientInfo {:name "brainyard-jolt"
                                                           :version "0.1.0"}}})
                      (await-id client id (or connect-timeout-ms 120000))))]
        (if-let [e (:error reply)]
          (do (p/destroy-tree proc)
              {:error (str "mcp server " server-name ": " (:message e))})
          (do (notify! client "notifications/initialized" {})
              (assoc client :server-info (get-in reply [:result :serverInfo])))))
      (catch Throwable e
        {:error (str "mcp server " server-name ": " (ex-message e))}))))

(defn disconnect!
  "Stop a server. `destroy-tree`, not `destroy`: a server spawned through `npx`
  or `uvx` is a wrapper whose grandchildren outlive it otherwise (RFC-009)."
  [client]
  (try (p/destroy-tree (:proc client)) (catch Throwable _ nil))
  ;; its stdin is the client's own stream: closed once, here — once the
  ;; process HAS ended, off this call. Its stdout and stderr are jolt's to
  ;; close, which it does as of 0.8.15, the floor (byj-gly.262)
  (let [proc (:proc client)]
    (fx/run-detached
     (fx/task-of #(when-not (= ::running (deref proc 10000 ::running))
                    (try (.close ^java.io.OutputStream (:in proc)) (catch Throwable _ nil))))
     ::release))
  {:stopped (:name client)})

(defn alive? [client]
  (boolean (try (p/alive? (:proc client)) (catch Throwable _ false))))

(defn list-tools [client]
  (let [r (request! client "tools/list" {})]
    (if-let [e (:error r)]
      {:error (:message e)}
      {:tools (vec (get-in r [:result :tools]))})))

;; --- results -------------------------------------------------------------------

(defn flatten-content
  "MCP answers with content BLOCKS — text, image, resource. Flatten them to
  what a model can read.

  brainyard hands the raw block vector to the model and never looks at
  `isError`, so a tool that failed is reported as a success with its error text
  buried in a nested map (divergence `mcp-results-are-flattened`)."
  [result]
  (let [blocks (:content result)
        text (->> blocks
                  (keep (fn [b]
                          (case (str (:type b))
                            "text" (:text b)
                            "image" (str "[image " (:mimeType b) "]")
                            "resource" (or (get-in b [:resource :text])
                                           (str "[resource " (get-in b [:resource :uri]) "]"))
                            (json/write-str b))))
                  (str/join "\n"))]
    (cond
      ;; the server said the call failed; brainyard reports this as success
      (:isError result) {:error-message (if (str/blank? text) "the MCP tool reported an error" text)}
      (seq blocks) {:result text}
      :else {:result (or result {})})))

(defn elicitation-asker
  "How an elicitation is answered in a call: the person, through
  `permission-fn` (the session's prompt, never the classifier), shown the
  server's message. Only a yes or a no is answered: a request for fields to
  fill in is declined; where there is no one to ask (nil `permission-fn`)
  the question is cancelled, unanswered."
  [permission-fn server tool]
  (fn [{:keys [message requestedSchema]}]
    (cond
      (nil? permission-fn) {:action "cancel"}
      (seq (:required requestedSchema)) {:action "decline"}
      :else (let [r (permission-fn {:type :mcp/elicitation :tool (str "mcp$" server "$" tool)
                                    :display (str "the MCP server `" server "` asks: " message)})]
              (if (:allowed r) {:action "accept" :content {}} {:action "decline"})))))

(defn call-tool [client tool-name args & [{:keys [ask]}]]
  (let [r (request! client "tools/call" {:name tool-name :arguments (or args {})} ask)]
    (if-let [e (:error r)]
      ;; the JSON-RPC code and message reach the model; brainyard keeps them in
      ;; ex-data and surfaces only a generic string
      {:error-message (str "MCP error " (:code e) ": " (:message e))}
      (flatten-content (:result r)))))

;; --- tools ---------------------------------------------------------------------

(def ^:private name-re #"[A-Za-z_][A-Za-z0-9_.+!?<>=-]*")

(defn safe-name? [s] (boolean (re-matches name-re (str s))))

(defn tool-id [server-name tool-name] (str "mcp$" server-name "$" tool-name))

(defn- json-schema->malli
  "brainyard's `json-schema->malli` (`mcp/integration.clj`): one JSON Schema
  property as malli — enum, the primitives, arrays by their items, objects by
  their properties with each level's :required. Anything exotic (oneOf, a
  list of types) is :any: the server validates its own arguments anyway."
  [spec]
  (let [g #(or (get spec %) (get spec (name %)))
        t (g :type)
        desc (g :description)
        props (g :properties)
        bare (cond
               (g :enum) (into [:enum] (g :enum))
               (= t "string") :string
               (= t "integer") :int
               (= t "number") [:or :int :double]
               (= t "boolean") :boolean
               (= t "array") [:vector (if-let [items (g :items)] (json-schema->malli items) :any)]
               (= t "object") (if (seq props)
                                (let [required (set (map name (g :required)))]
                                  (into [:map]
                                        (for [[k spec*] props
                                              :let [inner (json-schema->malli spec*)]]
                                          (if (contains? required (name k))
                                            [(keyword (name k)) inner]
                                            [(keyword (name k)) {:optional true} inner]))))
                                :map)
               :else :any)]
    (cond
      (nil? desc) bare
      (keyword? bare) [bare {:desc (str desc)}]
      (map? (second bare)) (update bare 1 assoc :desc (str desc))
      :else (into [(first bare) {:desc (str desc)}] (rest bare)))))

(defn input-schema
  "An MCP tool's JSON Schema as its malli :input-schema — brainyard's
  `mcp-json-schema->input-schema`. A tool with no schema takes `[:map]`."
  [json-schema]
  (let [s (or json-schema {})
        g #(or (get s %) (get s (name %)))
        required (set (map name (g :required)))]
    (into [:map]
          (for [[k spec] (g :properties)
                :let [m (json-schema->malli spec)]]
            (if (contains? required (name k))
              [(keyword (name k)) m]
              [(keyword (name k)) {:optional true} m])))))

(defn ->tool
  "One MCP tool as a registry entry. Read-only when its server says so
  (`readOnlyHint`), or its spec does (`:read-only-tools`, the server's own
  names): a server that annotates nothing — the npm GitHub server — has
  tools that only read all the same, and a spec that names them is what
  the reach is computed from (byj-1wv.115.44)."
  [client {:keys [name description inputSchema annotations] :as spec}]
  (let [server (:name client)
        read-only? (boolean (or (:readOnlyHint annotations) (get annotations "readOnlyHint")
                                (contains? (:read-only-tools client) name)))]
    {:name (tool-id server name)
     :kind :command
     :description (str (or description "an MCP tool")
                       "  (from the MCP server `" server "`"
                       (when read-only? ", read-only") ")")
     :input-schema (input-schema inputSchema)
     :output-schema [:or {:desc "the content blocks flattened to text; a result with no content blocks is passed through as it came"}
              [:map [:result [:or :string [:map-of :any :any]]]]
              [:map [:error-message :string]]]
     ;; a server that does not say it is read-only is treated as though it
     ;; writes, so `--ask-effects mcp/write` covers what it should
     :effects (cond-> #{:mcp/call} (not read-only?) (conj :mcp/write))
     :mcp {:server server :tool name :read-only? read-only?}
     :fn (fn [args ctx]
           (if-not (alive? client)
             {:error-message (with-stderr client (str "the MCP server `" server "` is not running"))}
             (call-tool client name args {:ask (elicitation-asker (:permission-fn ctx) server name)})))}))

(defn server-tools
  "Every tool a connected server offers, as registry entries. A name that
  cannot be a tool id is skipped and named, not silently dropped."
  [client]
  (let [{:keys [tools error]} (list-tools client)]
    (if error
      {:error error :tools []}
      (let [{ok true bad false} (group-by #(boolean (safe-name? (:name %))) tools)]
        (cond-> {:tools (mapv #(->tool client %) ok)}
          (seq bad) (assoc :skipped (mapv :name bad)))))))

;; --- the configured set ---------------------------------------------------------

(declare resolve-spec)

(defn connect-all!
  "Connect every enabled server in `:mcp-servers` and return
  `{:clients {name client} :tools [...] :problems [...]}`.

  A server that fails to start is a problem reported, not an exception: one
  broken entry must not cost a session the servers that do work."
  [config]
  (reduce
   (fn [acc [server-name spec]]
     (let [server-name (name server-name)]
       (cond
         (false? (:enabled spec true)) acc
         (not (safe-name? server-name))
         (update acc :problems conj (str "mcp server " (pr-str server-name) ": unusable name"))
         :else
         (let [c (connect! server-name (resolve-spec spec #(System/getenv %)))]
           (if (:error c)
             (update acc :problems conj (:error c))
             (let [{:keys [tools error skipped]} (server-tools c)]
               (cond-> (-> acc
                           ;; the client remembers what it offered, so
                           ;; `mcp$servers` can say without asking again
                           (assoc-in [:clients server-name] (assoc c :tool-names (mapv :name tools)))
                           (update :tools into tools))
                 error (update :problems conj (str "mcp server " server-name ": " error))
                 (seq skipped) (update :problems conj
                                       (str "mcp server " server-name ": skipped tools with unusable names: "
                                            (str/join ", " skipped))))))))))
   {:clients {} :tools [] :problems []}
   (:mcp-servers config)))

(defn disconnect-all! [clients]
  (doseq [[_ c] clients] (disconnect! c))
  {:stopped (vec (keys clients))})

;; --- the run's live set (byj-gly.182) -------------------------------------------
;;
;; `connect-all!` starts what is configured and is done. A person managing
;; servers from `/mcp` needs the set to move while the run goes on: a server
;; started, stopped, restarted. The box is that set — one per run, shared by
;; every session in it, since a server is a process of the run's — and its
;; `:version` moves on every change, which is what a session looks at, at a
;; turn's start, to know that its tools and its prompt must be built again.

(defn box
  "A run's MCP servers as they stand: `{:clients {name client} :tools {name
  [tool…]} :problems {name msg} :version n}`, and the run's `env-fn` — how a
  `${VAR}` in a spec is resolved, `.env` included (RFC-013). While they run,
  `:specs` holds the spec each was started with, `:failed` the spec a start
  failed with, and `:held` the servers a person stopped: what `reconcile!`
  compares a config against."
  ([] (box #(System/getenv %)))
  ([env-fn] (atom {:clients {} :tools {} :problems {} :version 0 :env-fn env-fn})))

;; --- ${VAR}, and the catalogue (byj-gly.183) --------------------------------------

(def ^:private var-re #"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")

(defn expand
  "`s` with each `${VAR}` replaced by the variable's value from `env-fn`, and
  each `${VAR:-default}` by the default when it is unset. An unset variable
  with no default expands to nothing — `missing` is what says so beforehand."
  [s env-fn]
  (str/replace (str s) var-re
               (fn [[_ v default]]
                 (let [x (env-fn v)]
                   (if (str/blank? (str x)) (str default) (str x))))))

(defn vars-of
  "The variables a spec needs: its `:needs`, and every `${VAR}` in its `:args`
  and `:env` that has no default."
  [{:keys [needs args env]}]
  (vec (distinct (concat needs
                         (for [s (concat args (vals env))
                               [_ v default] (re-seq var-re (str s))
                               :when (nil? default)]
                           v)))))

(defn missing
  "The variables a spec needs that `env-fn` has no value for."
  [spec env-fn]
  (vec (remove #(not (str/blank? (str (env-fn %)))) (vars-of spec))))

(defn resolve-spec
  "A spec as it is started: `${VAR}` expanded in `:args` and `:env`, and each
  variable it `:needs` passed into its environment — a wrapper script reads
  its own (google-workspace's `$GCP_OAUTH_CLIENT_ID`), and a value found only in
  `.env` is not in the process's environment otherwise."
  [spec env-fn]
  (-> spec
      (update :args #(mapv (fn [a] (expand a env-fn)) %))
      (update :env (fn [env]
                     (merge (into {} (for [v (:needs spec) :let [x (env-fn v)] :when x] [v x]))
                            (into {} (for [[k v] env] [k (expand v env-fn)])))))))

(def catalog-resource "brainyard/mcp/catalog.edn")

(defn catalog
  "The servers `/mcp find` offers: the bundled catalogue with the project's
  `.brainyard/mcp/catalog.edn` over it, entry by entry by `:name`, in name
  order."
  [project-dir]
  (let [read (fn [s] (try (let [v (edn/read-string s)] (when (sequential? v) v)) (catch Throwable _ nil)))
        bundled (some-> (io/resource catalog-resource) slurp read)
        own (let [f (io/file (str (or project-dir ".")) ".brainyard" "mcp" "catalog.edn")]
              (when (.isFile f) (read (slurp f))))]
    (->> (concat bundled own)
         (reduce (fn [m e] (assoc m (str (:name e)) e)) (sorted-map))
         vals vec)))

(defn entry->spec
  "What `/mcp add` writes for a catalogue entry: the spec, without the
  catalogue's own description, tags and name."
  [entry]
  (select-keys entry [:command :args :env :working-dir :timeout-ms :connect-timeout-ms :needs :read-only-tools :only-named?]))

(defn box-clients [b] (:clients @b))

(defn box-tools
  "Every tool the running servers offer, server by server in name order."
  [b]
  (vec (mapcat (fn [[_ ts]] ts) (sort-by key (:tools @b)))))

(defn- changed [m] (update m :version inc))

(defn server-of
  "The server a tool id `mcp$<server>$<tool>` belongs to, or nil."
  [tool-name]
  (second (re-matches #"mcp\$([^$]+)\$.+" (str tool-name))))

(defn starting-tool?
  "A fn of a tool name, true when it names a tool of a server in `b` that is
  still starting — a tool that is not there yet but is on its way, which a
  user tool calling it waits for rather than being skipped (byj-gly.300)."
  [b]
  (fn [tool-name]
    (boolean (some->> (server-of tool-name) (contains? (:starting @b))))))

(defn named-servers
  "Those of `server-names` whose tools `globs` name — a tool of it by name
  (`mcp$<server>$<tool>`), or a glob that may reach its tools (`*`,
  `mcp$*`, `mcp$<server>$*`, …) — as a persona's `:visible-tools` does
  (byj-1wv.115.46). None for no globs: an agent with no tool list."
  [globs server-names]
  (let [lit #(first (str/split (str %) #"\*" 2))]
    (filterv (fn [s]
               (let [prefix (str "mcp$" s "$")]
                 (some (fn [g] (let [p (lit g)]
                                 (if (str/includes? (str g) "*")
                                   (or (str/starts-with? p prefix) (str/starts-with? prefix p))
                                   (str/starts-with? (str g) prefix))))
                       globs)))
             server-names)))

(defn await-started
  "Wait, up to `ms`, while any of `server-names` is still starting in `b`.
  Returns those still starting when it stopped waiting — empty when every one
  is up or failed, which moved the box's version either way."
  [b server-names ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))
        left #(filterv (fn [n] (contains? (:starting @b) n)) server-names)]
    (loop []
      (let [l (left)]
        (if (and (seq l) (< (System/currentTimeMillis) deadline))
          (do (Thread/sleep 50) (recur))
          l)))))

(defn stop-server!
  "Stop one server and take its tools out of the set. One still starting is
  stopped when its start completes: its client is disconnected on arrival
  rather than kept (byj-gly.222). A server that is neither is a no-op, and
  says so."
  [b server-name]
  (cond
    (get-in @b [:clients server-name])
    (let [c (get-in @b [:clients server-name])]
      (disconnect! c)
      (swap! b #(-> % (update :clients dissoc server-name) (update :tools dissoc server-name)
                    (update :problems dissoc server-name) (update :specs dissoc server-name) changed))
      {:stopped server-name})

    (contains? (:starting @b) server-name)
    (do (swap! b #(-> % (update :stop-requested (fnil conj #{}) server-name) (update :pending dissoc server-name)))
        {:stopping server-name})

    :else {:error (str server-name " is not running")}))

(defn- claim!
  "Take `server-name` for a start, atomically: `:ok`, or `:running` / `:starting`
  when it is already taken. Two starts of one server — a picker's start during
  a background start's slow handshake — spawned two processes, and the one
  whose client was overwritten was never stopped (byj-gly.222)."
  [b server-name]
  (let [[old _] (swap-vals! b (fn [m] (if (or (get-in m [:clients server-name])
                                               (contains? (:starting m) server-name))
                                         m
                                         (update m :starting (fnil conj #{}) server-name))))]
    (cond (get-in old [:clients server-name]) :running
          (contains? (:starting old) server-name) :starting
          :else :ok)))

(declare start-server!)

(defn- start-claimed!
  "Start a server whose name `claim!` took, and release it. A stop asked for
  while it started disconnects the client that arrives; a restart asked for
  starts the new spec next.

  The server leaves `:starting` in the same swap that puts its tools in the
  box and moves the version (or records its failure) — never before. It left
  as soon as its handshake was done, while its tools were still being
  listed, so `await-started` returned to a session that then saw the same
  version, rebuilt nothing, and ran its first turn without the user tools
  that call the server (byj-gly.343, provenance P-37)."
  [b server-name spec]
  (swap! b #(-> % (assoc-in [:specs server-name] spec) (update :failed dissoc server-name)))
  (let [c (try (connect! server-name (resolve-spec spec (or (:env-fn @b) #(System/getenv %))))
               (catch Throwable e {:error (str "mcp server " server-name ": " (or (ex-message e) e))}))
        [old _] (swap-vals! b #(-> % (update :stop-requested disj server-name)
                                   (update :pending dissoc server-name)))
        stop? (contains? (:stop-requested old) server-name)
        pending (get-in old [:pending server-name])
        done #(update % :starting disj server-name)
        result
        (cond
          (and stop? (not (:error c))) (do (disconnect! c) (swap! b #(-> % (update :specs dissoc server-name) done)) {:stopped server-name})
          stop? (do (swap! b #(-> % (update :specs dissoc server-name) done)) {:stopped server-name})
          (:error c) (do (swap! b #(-> % (assoc-in [:problems server-name] (:error c))
                                       (update :specs dissoc server-name)
                                       (assoc-in [:failed server-name] spec)
                                       done
                                       changed))
                         {:error (:error c)})
          :else
          (let [{:keys [tools error skipped]} (server-tools c)
                problem (or (some->> error (str "mcp server " server-name ": "))
                            (when (seq skipped)
                              (str "mcp server " server-name ": skipped tools with unusable names: "
                                   (str/join ", " skipped))))]
            (swap! b #(cond-> (-> % (assoc-in [:clients server-name] (assoc c :tool-names (mapv :name tools)))
                                  (assoc-in [:tools server-name] tools)
                                  (update :problems dissoc server-name)
                                  done
                                  changed)
                        problem (assoc-in [:problems server-name] problem)))
            (cond-> {:started server-name :tools (count tools)} problem (assoc :problem problem))))]
    (if pending (start-server! b server-name pending) result)))

(defn start-server!
  "Start one server as `spec` says, and put its tools in the set. What failed
  is kept as the server's problem, for `/mcp` to show, rather than thrown."
  [b server-name spec]
  (if-not (safe-name? server-name)
    {:error (str "mcp server " (pr-str server-name) ": unusable name")}
    (case (claim! b server-name)
      :running {:error (str server-name " is already running")}
      :starting {:error (str server-name " is already starting")}
      :ok (start-claimed! b server-name spec))))

(defn restart-server!
  "Stop a server if it runs, and start it again: how a server that died comes
  back without leaving the run (RFC-024 had no reconnect). One still starting
  is stopped when it arrives and `spec` started after it."
  [b server-name spec]
  (if (contains? (:starting @b) server-name)
    (do (swap! b #(-> % (update :stop-requested (fnil conj #{}) server-name)
                      (assoc-in [:pending server-name] spec)))
        {:restarting server-name})
    (do (when (get-in @b [:clients server-name]) (stop-server! b server-name))
        (start-server! b server-name spec))))

(defn reconcile!
  "Make the box what `servers` (a config's `:mcp-servers`, by string name)
  says, compared with what the box itself runs — not with what one session
  last saw, which let every session restart a server another had just started,
  and replay old edits (byj-gly.223):
  - running or starting, and no longer wanted enabled: stopped;
  - running or starting with another spec than wanted: restarted;
  - wanted, not running: started — unless it failed with this very spec
    (a start that failed with a spec since fixed is tried again, byj-gly.224),
    or a person stopped it and its spec has not changed since.
  Returns what it did, `[[name action]]`."
  [b servers]
  (let [on? #(and % (not (false? (:enabled % true))))
        want (into {} (for [[n spec] servers :when (on? spec)] [(name n) spec]))
        m @b
        names (distinct (concat (keys want) (keys (:clients m)) (:starting m)))]
    (vec (for [n names
               :let [m @b
                     w (get want n)
                     running? (contains? (:clients m) n)
                     starting? (contains? (:starting m) n)
                     had (get-in m [:specs n])
                     held? (contains? (:held m) n)
                     action (cond
                              (and (or running? starting?) (nil? w)) (do (stop-server! b n) :stopped)
                              (and (or running? starting?) (not= w had)) (do (restart-server! b n w) :restarted)
                              (or running? starting? (nil? w)) nil
                              (= w (get-in m [:failed n])) nil
                              (and held? (= w (get-in m [:held-specs n]))) nil
                              :else (do (start-server! b n w) :started))]
               :when action]
           [n action]))))

(defn hold!
  "A person stopped `server-name`: `reconcile!` leaves it stopped while its
  spec is `spec`. Starting it again lifts that."
  [b server-name spec]
  (swap! b #(-> % (update :held (fnil conj #{}) server-name) (assoc-in [:held-specs server-name] spec))))

(defn unhold! [b server-name]
  (swap! b #(-> % (update :held (fnil disj #{}) server-name) (update :held-specs dissoc server-name))))

(defn enabled-specs
  "The servers in `:mcp-servers` not turned off, as [name spec]."
  [config]
  (for [[server-name spec] (:mcp-servers config)
        :when (not (false? (:enabled spec true)))]
    [(name server-name) spec]))

(defn start-all!
  "Start every enabled server in `:mcp-servers` into `b`, one after another,
  and wait: what a one-shot run does, since its only question may need them.
  Returns the problems."
  [b config]
  (doseq [[n spec] (enabled-specs config)] (start-server! b n spec))
  (vec (vals (:problems @b))))

(defn start-all-in-background!
  "Start every enabled server in `:mcp-servers` into `b`, each on its own task,
  and return at once — what a session does, so it is on the screen before a
  slow server finishes its handshake (an `npx` download, `mcp-remote`'s
  sign-in). While one starts it is in `:starting`; when it is up its tools are
  in the box and the version moved, which a session picks up at its next turn
  (`session/refresh-mcp!`); when it fails, that is its problem, for `/mcp` to
  show."
  [b config]
  (doseq [[n spec] (enabled-specs config)
          :when (and (safe-name? n) (= :ok (claim! b n)))]
    ;; the canceller is kept, for stop-all! at the run's end
    (let [cancel (fx/run-detached (fx/task-of #(try (start-claimed! b n spec)
                                                    (finally (swap! b update :cancels dissoc n))))
                                  (str "mcp start " n))]
      (swap! b #(if (contains? (:starting %) n) (assoc-in % [:cancels n] cancel) %)))))

(defn stop-all!
  "Everything the run started, stopped: every running server disconnected —
  its process tree with it — and every start still in flight asked to stop, so
  what arrives is disconnected too, and cancelled where it can be. Called when
  the run ends: nothing did, and an `npx`/`uvx` server's grandchildren outlived
  the process that started them (byj-gly.225)."
  [b]
  (let [m @b]
    (swap! b update :stop-requested (fnil into #{}) (:starting m))
    (doseq [[_ cancel] (:cancels m)] (try (when (fn? cancel) (cancel)) (catch Throwable _ nil)))
    (doseq [[n c] (:clients m)]
      (try (disconnect! c) (catch Throwable _ nil))
      (swap! b #(-> % (update :clients dissoc n) (update :tools dissoc n) (update :specs dissoc n))))
    {:stopped (vec (keys (:clients m))) :stopping (vec (:starting m))}))

;; --- commands -------------------------------------------------------------------

(defn commands
  "`mcp$servers` — what is connected and what it offers. The tools themselves
  are bound as `mcp$<server>$<tool>`, so this is discovery, not a proxy:
  brainyard also routes calls through `mcp$tools :op :call`, which is a second
  way to do what the bindings already do (divergence `mcp-has-no-call-proxy`)."
  [config clients]
  (when (and (:mcp? config true) (seq clients))
    [{:name "mcp$servers" :kind :command
      :description (str "List the connected MCP servers and the tools each offers."
                        " Their tools are bound as `mcp$<server>$<tool>` and called directly,"
                        " like any other tool.")
      :input-schema [:map]
      :output-schema [:map [:servers [:vector [:map [:name :string] [:running :boolean]
                                             [:server-info [:maybe [:map-of :any :any]]]
                                             [:tools [:vector :string]]]]]
                [:count :int]]
      :effects #{}
      :fn (fn [_ _]
            {:servers (vec (for [[n c] (sort-by key clients)]
                             {:name n
                              :running (alive? c)
                              :server-info (:server-info c)
                              :tools (vec (sort (:tool-names c)))}))
             :count (count clients)})}]))
