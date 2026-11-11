(ns brainyard.mcp-registry
  "The MCP Registry (registry.modelcontextprotocol.io) as a second place
  `/mcp find` looks, after the bundled catalogue (RFC-024, byj-gly.185).

  The registry lists servers as published — packages on npm, PyPI, a container
  registry, and hosted endpoints — in its own schema. This turns one into what
  the catalogue holds: a stdio command this port can start, with `${VAR}` for
  what it needs and the version it was published at pinned, or says why it
  cannot. It does not decide whether a server is worth running: what it lists is
  third-party code, which is why `/mcp add` adds one from here disabled.

  brainyard has no registry search; it ships its seed set and nothing else
  (divergence `mcp-registry-search`)."
  (:require [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [jolt.http-client :as http]))

(def default-url "https://registry.modelcontextprotocol.io")

(defn short-name
  "The name a registry server is added under: the last segment of its
  reverse-DNS name (`io.github.x/mcp-filesystem` → `mcp-filesystem`), cut down
  to what a tool id may hold."
  [full]
  (let [s (-> (str full) (str/split #"/") last
              (str/replace #"[^A-Za-z0-9_.+!?<>=-]" "-")
              (str/replace #"^[^A-Za-z_]+" ""))]
    (if (str/blank? s) "server" s)))

(defn- arg-values
  "A package's runtime or package arguments as command-line words: a
  positional's value, a named one's name and value. One with no value is left
  out — the registry's `valueHint` is a hint, not something to run."
  [args]
  (vec (mapcat (fn [{:keys [type name value]}]
                 (cond
                   (str/blank? (str value)) []
                   (= "named" type) [(str name) (str value)]
                   :else [(str value)]))
               args)))

(defn- env-of
  "The package's environment variables as `${VAR}` references, and the ones it
  marks required as what it needs."
  [vars]
  {:env (into {} (for [{:keys [name]} vars :when name] [name (str "${" name "}")]))
   :needs (vec (for [{:keys [name isRequired]} vars :when (and name isRequired)] name))})

(defn- package-spec
  "A stdio package as a spec, or nil for a kind this port cannot start."
  [{:keys [registryType identifier version transport runtimeArguments packageArguments environmentVariables]}]
  (when (= "stdio" (:type transport))
    (let [{:keys [env needs]} (env-of environmentVariables)
          pinned (fn [sep] (if (str/blank? (str version)) identifier (str identifier sep version)))
          args (arg-values packageArguments)]
      (case registryType
        "npm" {:command "npx" :args (into (into (arg-values runtimeArguments)
                                                (if (some #{"-y"} (arg-values runtimeArguments)) [] ["-y"]))
                                          (cons (pinned "@") args))
               :env env :needs needs :kind "npm"}
        "pypi" {:command "uvx" :args (into [(pinned "==")] args) :env env :needs needs :kind "pypi"}
        ;; docker passes a variable through by name: `-e VAR` reads it from the
        ;; environment the server is started with, where :needs puts it
        "oci" {:command "docker"
               :args (into (into ["run" "-i" "--rm"] (mapcat (fn [k] ["-e" k]) (keys env)))
                           (cons (str identifier) args))
               :env env :needs needs :kind "oci"}
        nil))))

(defn- remote-spec
  "A hosted endpoint through the `mcp-remote` bridge — unless it wants headers
  of its own, which the bridge would need a secret written into its arguments
  for."
  [{:keys [type url headers]}]
  (when (and url (#{"streamable-http" "sse"} type) (empty? (filter :isRequired headers)))
    {:command "npx" :args ["-y" "mcp-remote" url] :kind "remote"}))

(defn ->entry
  "A registry server as a catalogue entry — `{:name :registry-name :version
  :description :command :args :env :needs :kind}` — from the first of its
  packages this port can start, else a hosted endpoint; or, with none, the
  entry with `:unsupported` saying why."
  [{:keys [name description version packages remotes]}]
  (let [base {:name (short-name name) :registry-name name :version version
              :description (str/trim (str description)) :source :registry}
        spec (or (some package-spec packages) (some remote-spec remotes))]
    (if spec
      (merge base (cond-> spec (empty? (:env spec)) (dissoc :env) (empty? (:needs spec)) (dissoc :needs)))
      (assoc base :unsupported
             (cond
               (some #(= "stdio" (get-in % [:transport :type])) packages)
               (str "a " (str/join "/" (distinct (map :registryType packages))) " package, which this port cannot start")
               (seq remotes) "a hosted endpoint that needs its own headers"
               :else "no package this port can start")))))

(defn parse
  "A registry reply's body as entries, one per server."
  [body]
  (mapv (comp ->entry :server) (:servers (json/read-str body))))

(def search-ms
  "The most a registry search may hold the session: past it the answer is that
  the registry did not answer. The HTTP client's own timeouts summed to 25 s,
  on the session thread and past Ctrl-C (byj-gly.232)."
  12000)

(defn search
  "Search the registry at `url` for `query`, the latest version of each:
  `{:entries […]}` or `{:error msg}` — never a throw, since a network problem
  must not cost a person the rest of `/mcp find`. The request is a task,
  bounded by `search-ms` and cancelled with whatever cancels its caller
  (RFC-009)."
  [url query limit]
  (try
    (let [r (fx/run-value!!
             (fx/timeout
              (fx/task-of #(http/get (str (or url default-url) "/v0/servers?version=latest&limit=" limit
                                          "&search=" (java.net.URLEncoder/encode (str query) "UTF-8"))
                                     {:socket-timeout 10000 :conn-timeout 5000 :throw-exceptions false}))
              search-ms ::timeout))
          r (if (= ::timeout r) {:status ::timeout} r)]
      (cond
        (= 200 (:status r)) {:entries (parse (:body r))}
        (= ::timeout (:status r)) {:error (str "the MCP registry did not answer in " (quot search-ms 1000) " s")}
        :else {:error (str "the MCP registry answered " (:status r))}))
    (catch Throwable e {:error (str "the MCP registry could not be reached: " (ex-message e))})))
