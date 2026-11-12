;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.llm
  "Chat completion over jolt.http-client (and the `claude` CLI), ported from
  brainyard's `clj-llm.core.llm` / `providers` — non-streaming only.

  An LM config is a map:
    {:provider :openai|:anthropic|:bedrock|:claude-code|:codex|:ollama|:freellm|:mock
     :model \"...\" :base-url \"...\" :api-key \"...\" :auth-token \"...\"
     :temperature 0.0 :max-tokens 4096 :timeout-ms 300000}

  `chat` takes that config and [{:role \"system\"} {:role \"user\"}] messages and
  returns {:content string :usage {:input-tokens n :output-tokens n}}."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.json :as json]
            [brainyard.signature :as sig]
            [jolt.http-client :as http]
            [jolt.process :as p]))

(def providers
  "Provider registry: format, defaults and where credentials come from."
  ;; :supports-json-schema? is brainyard's flag, with brainyard's values. The
  ;; OpenAI body builder is shared by every OpenAI-compatible provider, and a
  ;; server that does not know `response_format` 400s on it — freellm is a
  ;; conservative false because the backend behind it is arbitrary.
  {:openai      {:format :openai :base-url "https://api.openai.com/v1"
                 :base-url-env "OPENAI_BASE_URL" :api-key-env "OPENAI_API_KEY"
                 :model "gpt-4.1-mini" :supports-json-schema? true}
   :freellm     {:format :openai :base-url-env "FREELLM_BASE_URL"
                 :api-key-env "FREELLM_API_KEY" :model "gpt-4.1-mini"}
   :openrouter  {:format :openai :base-url "https://openrouter.ai/api/v1"
                 :api-key-env "OPENROUTER_API_KEY" :model "anthropic/claude-sonnet-4.5"
                 :supports-json-schema? true}
   :ollama      {:format :openai :base-url "http://localhost:11434/v1"
                 :model "gemma4:12b"}
   :anthropic   {:format :anthropic :base-url "https://api.anthropic.com/v1"
                 :base-url-env "ANTHROPIC_BASE_URL" :api-key-env "ANTHROPIC_API_KEY"
                 :auth-token-env "ANTHROPIC_AUTH_TOKEN" :model "claude-sonnet-5"
                 ;; brainyard defaults :prompt-cache true on anthropic only
                 :prompt-cache? true}
   ;; `claude -p` takes no sampling parameters: `--temperature` and
   ;; `--max-tokens` are both "unknown option" on the current CLI (verified
   ;; 2026-09-24), so a temperature or a token cap on this LM is a value
   ;; nothing sends. It used to sit in the map anyway, which made every
   ;; measurement through the default provider look reproducible when the
   ;; sampler was free to move: two runs of one arm over the same 26 examples
   ;; came back 0.731 and 0.962 (`byj-gly.126`). brainyard has the same hole
   ;; and additionally passes `--max-tokens`, which this CLI now rejects — so
   ;; omitting it here is right, not a gap to close.
   :claude-code {:format :claude-code :model "opus"
                 :unsupported-params #{:temperature :max-tokens}}
   ;; `codex exec` on the person's Codex sign-in (byj-gly.430.9, RFC-049): a
   ;; ChatGPT plan, not an API key. No :model — Codex's own default unless one
   ;; is named — and, as `claude -p`, no sampling parameters
   :codex       {:format :codex :model nil
                 :unsupported-params #{:temperature :max-tokens}}
   ;; Bedrock speaks through the aws CLI (divergence `bedrock-via-cli`): jolt has
   ;; no SigV4, and the CLI already resolves profiles, SSO and regions.
   :bedrock     {:format :bedrock :model "us.anthropic.claude-haiku-4-5-20251001-v1:0"
                 :region-env "AWS_REGION" :region "us-east-1" :prompt-cache? true}
   :mock        {:format :mock :model "mock"}})

(def ^:private no-temperature
  "The model-id patterns that refuse a temperature: `resources/brainyard/sampling.edn`."
  (delay (mapv re-pattern (:no-temperature (edn/read-string (slurp (io/resource "brainyard/sampling.edn")))))))

(defn takes-temperature?
  "Does `model` accept a temperature? Not the models `sampling.edn` names:
  Claude Opus 4.7 and later, and GPT-5.6, answer any temperature with a
  validation error, so every call failed until a request to one left it out
  (byj-gly.369)."
  [model]
  (not-any? #(re-find % (str model)) @no-temperature))

(defn samples-freely?
  "Can this LM's randomness NOT be pinned? True when the provider takes no
  temperature, or the model takes none (`takes-temperature?`), so two
  identical calls may differ and a single evaluation pass measures the
  sampler as much as the change (`byj-gly.126`).

  `brainyard.programs` stamps an evaluation with it; `evaluate` itself cannot,
  because it is handed a program and never learns what is behind it (RFC-021)."
  [lm]
  (or (contains? (:unsupported-params lm) :temperature)
      (not (takes-temperature? (:model lm)))))

(defn merge-base-url-path
  "brainyard's `providers/merge-base-url-path`: a `:base-url-env` override
  against the provider's static base URL. A gateway is given as its origin
  (`ANTHROPIC_BASE_URL=http://litellm:4000`, as Claude Code takes it), while
  the static URL carries the API's version path (`…/v1`) and a call appends
  only the endpoint (`/messages`) — taken verbatim, the override is called at
  `/messages` and 404s (byj-gly.446). An override with no path inherits the
  static URL's; one with a path (`https://gw/anthropic/v1`) is kept as it is."
  [override static-url]
  (let [trimmed (str/replace (str/trim override) #"/+$" "")
        path-of (fn [u] (when u (second (re-matches #"(?:[A-Za-z][A-Za-z0-9+.-]*://)?[^/]*(/.*)?" u))))]
    (if (str/blank? (path-of trimmed))
      (str trimmed (path-of static-url))
      trimmed)))

(defn create-lm
  "Resolve an LM config for provider from the registry and `env` (a fn of a var
  name), with explicit overrides winning.

  A parameter the provider cannot convey is REMOVED rather than carried: an LM
  map must not report a temperature that no request will contain. What a caller
  asked for explicitly is kept under `:ignored-params` so the call can say it
  was dropped — a hint that cannot be honoured degrades and announces itself,
  as an unresolvable `:lm` or `:tier` does (RFC-005)."
  [provider env overrides]
  (let [{:keys [base-url-env api-key-env auth-token-env] :as spec}
        (or (get providers provider)
            (throw (ex-info (str "unknown provider " provider
                                 " (known: " (str/join ", " (map name (keys providers))) ")")
                            {:provider provider})))
        blank->nil #(when-not (str/blank? %) %)
        lm (merge {:provider provider :temperature 0.0 :max-tokens 8192 :timeout-ms 300000}
                  (dissoc spec :base-url-env :api-key-env :auth-token-env :region-env)
                  (into {} (remove (comp nil? val)
                                   {:base-url (some-> base-url-env env blank->nil (merge-base-url-path (:base-url spec)))
                                    :api-key (some-> api-key-env env blank->nil)
                                    :auth-token (some-> auth-token-env env blank->nil)
                                    :region (some-> (:region-env spec) env blank->nil)
                                    :profile (some-> (env "AWS_PROFILE") blank->nil)}))
                  (into {} (remove (comp nil? val) overrides)))
        unsupported (:unsupported-params lm)
        ;; only what the CALLER asked for is worth reporting; the defaults
        ;; above were never honoured on such a provider and never could be
        asked (select-keys (into {} (remove (comp nil? val) overrides)) (vec unsupported))]
    (cond-> (apply dissoc lm unsupported)
      (seq asked) (assoc :ignored-params asked))))

(defn split-lm-label
  "brainyard's `split-lm-str`: `\"provider/model\"`, or the legacy
  `\"provider:model\"`. The FIRST separator wins, so an openrouter id that
  itself contains a slash (`openrouter/anthropic/claude-sonnet-4.5`) survives."
  [s]
  (let [s (str/trim (str s))]
    (when-not (str/blank? s)
      (let [i (or (str/index-of s "/") (str/index-of s ":"))]
        (when (and i (pos? i) (< (inc i) (count s)))
          [(subs s 0 i) (subs s (inc i))])))))

(defn lm-label
  "`provider/model`, or just the provider when a run named no model — a label
  reads back as the thing you would type to ask for it (`parse-lm-label`)."
  [lm]
  (str (some-> (:provider lm) name)
       (when-not (str/blank? (str (:model lm))) (str "/" (:model lm)))))

(defn parse-lm-label
  "The LM a `\"provider/model\"` label names, or **nil** when it names no known
  provider — brainyard's `parse-lm-str`, which swallows the error on purpose: a
  label that cannot be honoured must not fail a call that already had a working
  LM. Credentials still come from the environment, never from the label, so a
  params file can choose a model without carrying a key — pass the RUN's env fn
  where there is one, or a key found only in a `.env` is invisible here."
  ([label] (parse-lm-label label #(System/getenv %)))
  ([label env]
   (try
     (when-let [[provider model] (split-lm-label label)]
       (create-lm (keyword provider) env {:model model}))
     (catch Throwable _ nil))))

(def curated-models
  "The models `/model` offers, most useful first: `[{:provider :model
  :description}]` from `resources/brainyard/models.edn` — brainyard's
  `get-popular-models`, for the providers this port reaches."
  (delay (edn/read-string (slurp (io/resource "brainyard/models.edn")))))

(defn model-label
  "What `/model` switches to for `s`: `provider/model` as written, or a bare
  model id (`sonnet`) as the first curated entry of that id — brainyard's
  `/model haiku`. nil when a bare id names nothing curated."
  [s]
  (let [s (str/trim (str s))]
    (when-not (str/blank? s)
      ;; the curated ids first: `gemma4:12b` is an ollama model, and the legacy
      ;; `provider:model` form would read it as a provider named gemma4
      (or (some (fn [{:keys [provider model]}]
                  (when (= s model) (str (name provider) "/" model)))
                @curated-models)
          (when (split-lm-label s) s)))))

(def tiers
  "brainyard's work tiers, cheapest first. The vector IS the ordering used by
  `clamp-tier`; there is no separate rank table to drift from it."
  [:light :standard :deep])

(def tier-rank (into {} (map-indexed (fn [i t] [t i])) tiers))

(defn coerce-tier
  "A tier from a keyword, a string, or a string with a leading colon — nil when
  it names none. brainyard returns nil rather than a default here on purpose,
  so a caller can tell \"not specified\" from \"specified as :light\"."
  [tier]
  (let [t (cond (keyword? tier) tier
                (string? tier) (keyword (str/replace (str/trim tier) #"^:" ""))
                :else nil)]
    (when (contains? tier-rank t) t)))

(defn tier-lm
  "The LM a work tier names, or nil when the table has no entry for it —
  brainyard's `resolve-tier-lm`. The table ships all-nil, so tiers are INERT
  until someone configures them: nil means \"no override\", which leaves the
  call on the LM it already had.

  An unparseable label is nil too, not a fallback to the ambient LM: letting a
  typo silently pin every call to the session model would hide the typo."
  ([table tier] (tier-lm table tier #(System/getenv %)))
  ([table tier env]
   (let [label (get table (coerce-tier tier))]
     (when (and (string? label) (not (str/blank? label)))
       (parse-lm-label label env)))))

(defn light-lm
  "The light model of `lm`'s own provider, or nil when it names none: the
  curated entry marked `:light? true` in `resources/brainyard/models.edn`
  (byj-gly.367). A light-tier call with no tier table entry runs here, on the
  session's provider — never another one, which would bill a different account
  or need a login this run does not have. Resolved as `/model` resolves a
  label, so credentials come from `env`."
  ([lm] (light-lm lm #(System/getenv %)))
  ([lm env]
   (when-let [{:keys [provider model]} (some #(when (and (:light? %) (= (:provider lm) (:provider %))) %)
                                            @curated-models)]
     (parse-lm-label (str (name provider) "/" model) env))))

;; --- errors & retry ---------------------------------------------------------

(defn- retryable-status? [s] (or (= 429 s) (= 408 s) (<= 500 s 599)))

(defn usage-cap?
  "A 429 saying the account has no credit left: no wait makes it pass, so a
  retry only wastes time (as samizdat's adapter/usage-cap?; provenance P-4)."
  [status decoded msg]
  (and (= 429 status)
       (or (= "insufficient_quota" (get-in decoded [:error :code]))
           (boolean (re-find #"(?i)no credits|insufficient.quota|credit balance|billing" (str msg))))))

(defn- api-error [provider status body]
  (let [decoded (try (json/read-str body) (catch Throwable _ nil))
        msg (or (get-in decoded [:error :message]) (:message decoded)
                (subs (str body) 0 (min 300 (count (str body)))))]
    (ex-info (str (name provider) " API error " status ": " msg)
             {:status status
              :retryable? (and (retryable-status? status) (not (usage-cap? status decoded msg)))
              :fatal? (usage-cap? status decoded msg)})))

(defn with-retry
  "Call f, retrying on ex-data :retryable? with exponential backoff and jitter.
  brainyard's `retry-with-backoff`: the policy is `fx/retry-backoff` over
  `fx/task-of f`, so the backoff sleep is cancellable and a cancelled turn
  reaching this call ends it with InterruptedException instead of waiting out
  the backoff. A cancellation is never retried.

  opts: :max-retries (3), :base-delay-ms (1000), :on-retry
  (fn [{:attempt :max :delay-ms :error}]), fired before each wait."
  [{:keys [max-retries base-delay-ms on-retry] :or {max-retries 3 base-delay-ms 1000}} f]
  (fx/run-value!!
   (fx/retry-backoff {:max-retries max-retries
                      :base-delay-ms base-delay-ms
                      :retryable? #(boolean (:retryable? (ex-data %)))
                      :on-retry on-retry}
                     (fx/task-of f))))

(def ^:private credential-headers
  "Header names whose value never reaches a log."
  #{"authorization" "x-api-key" "api-key" "cookie" "proxy-authorization"})

(defn- redact
  "Headers with credentials replaced, the rest kept.

  brainyard logs no headers at all and pays for it: a logged call is replayable
  in body and URL only, leaving auth, `anthropic-version` and the beta headers
  to be reconstructed by hand. Those decide how a provider answers, so dropping
  them all to protect one is the wrong trade (divergence `wire-headers-redacted`)."
  [headers]
  (into {} (map (fn [[k v]]
                  [k (if (credential-headers (str/lower-case (name k))) "[redacted]" v)])
                headers)))

(defn- post-json [lm url headers body on-event]
  (let [t0 (System/currentTimeMillis)
        _ (events/emit! on-event :llm.wire/request
                        {:provider (:provider lm) :model (:model lm) :url url
                         :body body :headers (redact headers)})
        resp (try
               (http/post url {:headers (merge {"Content-Type" "application/json"} headers)
                               :body (json/write-str body)
                               :socket-timeout (:timeout-ms lm)
                               :conn-timeout 15000
                               :throw-exceptions false})
               (catch Throwable e
                 ;; a cancelled turn interrupts the read (http-client reads in
                 ;; 250 ms slices); wrapped as a retryable network error it
                 ;; would read as a provider failure and enter the retry path
                 ;; instead of ending the call as a cancellation
                 (if (fx/cancelled? e)
                   (throw e)
                   (do (events/emit! on-event :llm.wire/failed
                                     {:duration-ms (- (System/currentTimeMillis) t0)
                                      :error (ex-message e) :retryable? true})
                       (throw (ex-info (str "network error: " (ex-message e)) {:retryable? true}))))))]
    (if (<= 200 (:status resp) 299)
      (do (events/emit! on-event :llm.wire/response
                        {:status (:status resp)
                         :duration-ms (- (System/currentTimeMillis) t0)
                         :body (:body resp)})
          (json/read-str (:body resp)))
      (let [e (api-error (:provider lm) (:status resp) (:body resp))]
        (events/emit! on-event :llm.wire/failed
                      {:status (:status resp)
                       :duration-ms (- (System/currentTimeMillis) t0)
                       :error (:body resp)
                       :retryable? (boolean (:retryable? (ex-data e)))})
        (throw e)))))

;; --- providers ---------------------------------------------------------------

(defmulti chat*
  "Provider call. `opts` may carry :json-schema, the reply's schema, for
  providers with a structured-output path (claude-code)."
  (fn [lm _messages _opts] (:format lm)))

(defn json-schema-strict?
  "OpenAI's `strict` flag for a `json_schema` response format, as brainyard's
  `json-schema-strict?` resolves it: an explicit `:json-schema-strict?` on the
  LM config wins, else strict exactly when the schema is strict-eligible.

  Auto-detection is the point. Strict mode forbids an optional property, so a
  schema with one 400s the whole request — and `strict:false` still sends the
  schema, which is API-level guidance rather than enforcement. Either way the
  reply is parsed and normalised downstream (`sig/normalize-outputs`), so the
  guarantee never rested on the flag."
  [lm json-schema]
  (if (contains? lm :json-schema-strict?)
    (boolean (:json-schema-strict? lm))
    (sig/strict-eligible? json-schema)))

(defn openai-body
  "The request body. OpenAI caches long prefixes by itself, so the zone
  ordering IS the optimization (RFC-015); the two cache keys only steer routing
  and retention, and are gated to OpenAI because a strict server may 400 on an
  unknown parameter.

  `response_format` carries the reply's JSON Schema where the provider takes
  one (`:supports-json-schema?`), which is brainyard's `build-openai-body`. The
  schema is already in the system prompt (`sig/json-schema-instruction`); this
  is the same thing again as API-level enforcement, and the two must not
  disagree, so both come from `sig/output-json-schema`."
  ([lm messages] (openai-body lm messages nil))
  ([{:keys [model temperature max-tokens supports-json-schema? prompt-cache-key
            cache-ttl provider] :as lm}
    messages json-schema]
   (let [schema? (boolean (and json-schema supports-json-schema?))]
     (cond-> {:model model :messages messages}
       (and temperature (takes-temperature? model)) (assoc :temperature temperature)
       max-tokens (assoc :max_tokens max-tokens)
       schema? (assoc :response_format
                      {:type "json_schema"
                       :json_schema {:name "response"
                                     :strict (json-schema-strict? lm json-schema)
                                     :schema json-schema}})
       (and (= :openai provider) prompt-cache-key) (assoc :prompt_cache_key prompt-cache-key)
       (and (= :openai provider) cache-ttl (not= "5m" cache-ttl)) (assoc :prompt_cache_retention "24h")))))

(defmethod chat* :openai [{:keys [base-url api-key] :as lm} messages {:keys [json-schema on-event]}]
  (when (str/blank? base-url)
    (throw (ex-info (str (name (:provider lm)) ": no base URL configured") {})))
  (let [resp (post-json lm (str base-url "/chat/completions")
                        (when api-key {"Authorization" (str "Bearer " api-key)})
                        (openai-body lm messages json-schema)
                        on-event)]
    {:content (get-in resp [:choices 0 :message :content])
     :usage {:input-tokens (get-in resp [:usage :prompt_tokens])
             :output-tokens (get-in resp [:usage :completion_tokens])}}))

(defn anthropic-headers
  "Exactly one auth header, as in brainyard's build-anthropic-headers, plus the
  beta flag a 1h cache TTL needs."
  [{:keys [api-key auth-token cache-ttl]}]
  (cond-> {"anthropic-version" "2023-06-01"}
    api-key (assoc "x-api-key" api-key)
    (and (nil? api-key) auth-token) (assoc "Authorization" (str "Bearer " auth-token))
    (= "1h" cache-ttl) (assoc "anthropic-beta" "extended-cache-ttl-2025-04-11")))

(def min-cache-prefix-chars
  "A breakpoint below the provider's minimum cacheable prefix (~1024 tokens,
  model-dependent) caches nothing and still spends one of the four slots —
  brainyard's `min-user-cache-prefix-chars`, applied to every boundary here."
  4000)

(def volatile-zones
  "Zones that change every turn: a breakpoint after them would be rewritten as
  often as it is read. brainyard's `:no-zone-keys`."
  #{"user-context"})

(defn system-blocks
  "The system message as Anthropic content blocks, with `cache_control` at the
  end of each stable zone (RFC-015).

  A boundary is taken only when everything before it is at least
  `min-cache-prefix-chars`, and never after a volatile zone or the last part —
  the tail changes every call, so marking it buys a write and no read. With a
  1h `ttl` every mark but the last carries it: Anthropic requires the longer
  TTLs first, and the last mark is the one that churns."
  [parts {:keys [ttl]}]
  (let [n (count parts)
        cumulative (reductions + (map (comp count second) parts))
        boundary? (fn [i [label _]]
                    (and (< i (dec n))
                         (not (contains? volatile-zones label))
                         (>= (nth cumulative i) min-cache-prefix-chars)))
        marks (vec (keep-indexed (fn [i p] (when (boundary? i p) i)) parts))
        last-mark (peek marks)]
    (vec (map-indexed
          (fn [i [_ text]]
            (cond-> {:type "text" :text text}
              (contains? (set marks) i)
              (assoc :cache_control (if (and (= "1h" ttl) (not= i last-mark))
                                      {:type "ephemeral" :ttl "1h"}
                                      {:type "ephemeral"}))))
          parts))))

(defn anthropic-body
  "The request body. `:prompt-cache?` (default true here) turns the system
  string into cache-marked blocks when the caller carried its parts along;
  without parts it stays a plain string, as before."
  [{:keys [model temperature max-tokens prompt-cache? cache-ttl]} messages]
  (let [system-messages (filter #(= "system" (:role %)) messages)
        system (str/join "\n\n" (keep :content system-messages))
        parts (mapcat :parts system-messages)]
    (cond-> {:model model
             :max_tokens (or max-tokens 4096)
             :messages (vec (remove #(= "system" (:role %)) messages))}
      (not (str/blank? system)) (assoc :system system)
      (and (not (false? prompt-cache?)) (seq parts))
      (assoc :system (system-blocks (vec parts) {:ttl cache-ttl}))
      (and temperature (takes-temperature? model)) (assoc :temperature temperature))))

(defn anthropic-usage
  "brainyard's `extract-anthropic-usage`: the three input categories do not
  overlap, so :input-tokens is their sum and the split is kept under :cache."
  [usage]
  (let [fresh (or (:input_tokens usage) 0)
        read (or (:cache_read_input_tokens usage) 0)
        write (or (:cache_creation_input_tokens usage) 0)]
    (cond-> {:input-tokens (+ fresh read write)
             :output-tokens (:output_tokens usage)}
      (pos? (+ read write)) (assoc :cache {:read-tokens read :write-tokens write}))))

(defmethod chat* :anthropic [{:keys [base-url] :as lm} messages {:keys [on-event]}]
  (let [resp (post-json lm (str base-url "/messages") (anthropic-headers lm) (anthropic-body lm messages) on-event)]
    {:content (some #(when (= "text" (:type %)) (:text %)) (:content resp))
     :usage (anthropic-usage (:usage resp))}))

;; --- bedrock ----------------------------------------------------------------------

(def bedrock-anthropic-version "bedrock-2023-05-31")

(defn bedrock-anthropic?
  "An Anthropic model on Bedrock, whatever inference profile carries it
  (`us.anthropic.…`, `global.anthropic.…`). Those take the native Messages body,
  which is the only shape that carries `cache_control` through the CLI."
  [model]
  (boolean (re-find #"(^|\.)anthropic\." (str model))))

(defn bedrock-nova?
  "An Amazon Nova model on Bedrock, whatever inference profile carries it.
  Nova takes cache points (explicit prompt caching: up to four, a 1K-token
  minimum, five minutes), and through invoke-model, which passes the body
  through unvalidated — Converse's `cachePoint` is refused by an aws CLI older
  than its model (byj-gly.37)."
  [model]
  (boolean (re-find #"(^|\.)amazon\.nova-" (str model))))

(defn bedrock-native?
  "Does `model` take its own body through invoke-model rather than Converse?
  The families whose cache markers only reach the model that way."
  [model]
  (or (bedrock-anthropic? model) (bedrock-nova? model)))

(defn- nova-system
  "The system message as Nova's blocks: a `cachePoint` on each block where
  `system-blocks` would put Anthropic's `cache_control` — the same stable-zone
  boundaries (RFC-015) — and never a `ttl`, which Nova refuses (it caches for
  five minutes only). Without parts, or with `:prompt-cache? false`, one block."
  [{:keys [prompt-cache?]} system parts]
  (if (and (not (false? prompt-cache?)) (seq parts))
    (mapv (fn [{:keys [text cache_control]}]
            (cond-> {:text text} cache_control (assoc :cachePoint {:type "default"})))
          (system-blocks (vec parts) {}))
    [{:text system}]))

(defn bedrock-body
  "The request body: the native Anthropic Messages shape for an Anthropic model
  — the same blocks `anthropic-body` builds, minus `model`, plus
  `anthropic_version`; Nova's own messages-v1 body for a Nova model, with
  cache points (`nova-system`); else a Converse request, which every Bedrock
  model understands. The rest — OpenAI's on Bedrock — have no explicit caching
  through Converse at all, so the Converse path carries no markers."
  [{:keys [model max-tokens temperature] :as lm} messages]
  (cond
    (bedrock-anthropic? model)
    (-> (anthropic-body lm messages)
        (dissoc :model)
        (assoc :anthropic_version bedrock-anthropic-version))

    (bedrock-nova? model)
    (let [system-messages (filter #(= "system" (:role %)) messages)
          system (str/join "\n\n" (keep :content system-messages))]
      (cond-> {:schemaVersion "messages-v1"
               :messages (vec (for [m messages :when (not= "system" (:role m))]
                                {:role (:role m) :content [{:text (:content m)}]}))
               :inferenceConfig (cond-> {:maxTokens (or max-tokens 4096)}
                                  (and temperature (takes-temperature? model)) (assoc :temperature temperature))}
        (not (str/blank? system)) (assoc :system (nova-system lm system (mapcat :parts system-messages)))))

    :else
    (let [system (str/join "\n\n" (keep #(when (= "system" (:role %)) (:content %)) messages))]
      (cond-> {:modelId model
               :messages (vec (for [m messages :when (not= "system" (:role m))]
                                {:role (:role m) :content [{:text (:content m)}]}))
               :inferenceConfig (cond-> {:maxTokens (or max-tokens 4096)}
                                  (and temperature (takes-temperature? model)) (assoc :temperature temperature))}
        (not (str/blank? system)) (assoc :system [{:text system}])))))

(defn bedrock-converse-usage
  "Converse's usage, and Nova's own (its cache keys end in `Count`). With
  caching on, `inputTokens` is only what was neither read nor written, so the
  input is the three summed — as `anthropic-usage` sums them; reporting
  `inputTokens` alone showed a cached call as a 4-token one (byj-gly.37).

  An aws CLI older than Converse's cache fields drops them as it parses the
  reply, so a call Bedrock cached by itself came back as `inputTokens` 2 of a
  9,118-token `totalTokens` (aws-cli 2.25.1 on GPT-5.6 Sol, whose raw reply
  said `cacheReadInputTokens` 9,111; byj-gly.372). `totalTokens` is kept, so
  what it counts beyond the named parts is input the CLI did not say the kind
  of: counted as input, priced at the input rate — an upper bound, where a
  cache read is cheaper — and said as `:unreported-input-tokens`. A current
  CLI names them."
  [usage]
  (let [fresh (or (:inputTokens usage) 0)
        read (or (:cacheReadInputTokens usage) (:cacheReadInputTokenCount usage) 0)
        write (or (:cacheWriteInputTokens usage) (:cacheWriteInputTokenCount usage) 0)
        unreported (max 0 (- (or (:totalTokens usage) 0) fresh read write (or (:outputTokens usage) 0)))]
    (cond-> {:input-tokens (+ fresh read write unreported) :output-tokens (:outputTokens usage)}
      (pos? (+ read write)) (assoc :cache {:read-tokens read :write-tokens write})
      (pos? unreported) (assoc :unreported-input-tokens unreported))))

(def ^:private bedrock-hints
  "`resources/brainyard/bedrock-hints.edn`, its patterns compiled."
  (delay (mapv #(update % :match re-pattern)
               (edn/read-string (slurp (io/resource "brainyard/bedrock-hints.edn"))))))

(defn bedrock-hint
  "What a Bedrock error means, or nil: the first of `bedrock-hints.edn` whose
  pattern is in `err`. Claude Fable's 'data retention mode default is not
  available' is an account setting only its owner can change, and said
  nothing of that (byj-gly.370)."
  [err]
  (some #(when (re-find (:match %) (str err)) (:hint %)) @bedrock-hints))

(defn bedrock-args
  "The aws CLI invocation. `invoke-model` passes the body through unvalidated,
  which is how `cache_control` reaches an Anthropic model and `cachePoint` a
  Nova one (`native?`); `converse` is the uniform path for the rest."
  [{:keys [model region profile]} native? body-file out-file]
  (cond-> ["aws" "bedrock-runtime"]
    native? (into ["invoke-model" "--model-id" model
                   "--body" (str "fileb://" body-file)
                   "--cli-binary-format" "raw-in-base64-out" out-file])
    (not native?) (into ["converse" "--cli-input-json" (str "file://" body-file)
                         "--output" "json"])
    true (into ["--region" (or region "us-east-1")])
    profile (into ["--profile" profile])))

(defn bedrock-converse-reply
  "A Converse reply as `{:content :usage}`: its text block. A reasoning model
  may end its turn having only reasoned — gpt-oss did, 2 of 6 replays of one
  request (byj-gly.371) — and no text was then read as prose with no JSON
  object; it is a reply that did not happen, retryable, since another sample
  may answer."
  [resp]
  (let [blocks (get-in resp [:output :message :content])
        text (some :text blocks)]
    (when (and (nil? text) (some :reasoningContent blocks))
      (throw (ex-info "bedrock reply had reasoning but no text" {:retryable? true})))
    {:content text
     :usage (bedrock-converse-usage (:usage resp))}))

(defmethod chat* :bedrock [{:keys [model timeout-ms] :as lm} messages _opts]
  (let [anthropic? (bedrock-anthropic? model)
        native? (bedrock-native? model)
        dir (io/file (System/getProperty "java.io.tmpdir") (str "by-bedrock-" (random-uuid)))
        _ (.mkdirs dir)
        body-file (.getPath (io/file dir "body.json"))
        out-file (.getPath (io/file dir "out.json"))
        _ (spit body-file (json/write-str (bedrock-body lm messages)))
        cmd (bedrock-args lm native? body-file out-file)
        r (try
            (fx/run-value!! (fx/timeout (apply fx/process-task {:out :string :err :string} cmd)
                                        (or timeout-ms 300000) ::timeout))
            (catch Throwable e
              (if (fx/cancelled? e) (throw e)
                  (throw (ex-info (str "cannot run aws CLI: " (ex-message e)) {:fatal? true})))))]
    (when (= ::timeout r) (throw (ex-info "bedrock call timed out" {:retryable? true})))
    (when-not (zero? (:exit r))
      (throw (ex-info (str "bedrock call failed (exit " (:exit r) "): " (str/trim (str (:err r)))
                           (some->> (bedrock-hint (:err r)) (str "\n")))
                      {:retryable? (boolean (re-find #"Throttling|TooManyRequests|ServiceUnavailable"
                                                     (str (:err r))))})))
    (let [resp (json/read-str (if native? (slurp out-file) (:out r)))]
      (if anthropic?
        {:content (some #(when (= "text" (:type %)) (:text %)) (:content resp))
         :usage (anthropic-usage (:usage resp))}
        (bedrock-converse-reply resp)))))

;; --- claude-code: brainyard's clj-llm.core.claude-code, invocation for invocation --

(def structured-output-directive
  "brainyard's `structured-output-directive`, appended to the system prompt when
  --json-schema is in force: the StructuredOutput tool is optional
  (tool_choice=auto), so without this the model may answer in prose. The
  code-blocks sentence is dropped — this port has no code channel, and the
  no-tools sentence says the prompt's tools exist (divergence
  `native-tool-calls-recovered`): brainyard's \"You have NO tools\" was quoted
  back by haiku as the reason a listed tool was unavailable."
  (str "\n\n=== STRUCTURED OUTPUT — MANDATORY ===\n"
       "You are a headless structured-output endpoint, NOT an interactive assistant. "
       "Return your ENTIRE response by calling the StructuredOutput tool with a single "
       "JSON object matching the schema. Never reply in prose, markdown, or natural "
       "language, and never narrate a plan.\n"
       "You can call NO tool directly except StructuredOutput, and you have NO filesystem "
       "access: never write files, never run shell or heredocs (e.g. `cat > file <<'EOF'`), "
       "and never perform the task yourself. The tools named in the prompt DO exist: they "
       "run when you list them in `tool-calls`.\n"
       "If you still need information, populate `tool-calls`. Populate `answer` ONLY when "
       "the task is complete."))

(def claude-code-max-turns
  "brainyard's default-max-turns. Must be > 1: the CLI spends the model's first
  assistant turn on text and the StructuredOutput call comes on the second."
  4)

(defn claude-cli-args
  "The CLI invocation. With `stream?` the output format is NDJSON and partial
  messages are included, so text arrives as it is generated rather than in one
  block at the end (RFC-006); `--verbose` is what the CLI requires to emit it."
  [{:keys [model max-turns]} system json-schema & [stream?]]
  (cond-> ["claude" "-p"
           "--no-session-persistence"
           "--output-format" (if stream? "stream-json" "json")
           "--tools" ""
           "--setting-sources" ""
           "--strict-mcp-config"
           "--disable-slash-commands"
           "--max-turns" (str (or max-turns claude-code-max-turns))]
    stream? (into ["--include-partial-messages" "--verbose"])
    model (into ["--model" model])
    true (into ["--system-prompt" system])
    json-schema (into ["--json-schema" (json/write-str json-schema)])))

(defn stream-delta
  "The text a `stream-json` line carries, or nil for every other event. The
  shape is the CLI's: `{:type \"stream_event\" :event {:type
  \"content_block_delta\" :delta {:type \"text_delta\" :text \"…\"}}}`."
  [line]
  (when-let [e (try (json/read-str (str line)) (catch Throwable _ nil))]
    (when (and (map? e) (= "stream_event" (:type e)))
      (let [ev (:event e)]
        (when (= "content_block_delta" (:type ev))
          (get-in ev [:delta :text]))))))

(defn partial-string-field
  "The value of top-level string field `k` in JSON that may still be ARRIVING,
  decoded as far as it has come, or nil before the field starts.

  A reply streams as the raw structured JSON, so a viewer watching it sees
  `{\"reasoning\":\"…` character by character rather than prose. This reads one
  field out of that, mid-flight, escapes and all — `\\n` is a newline even when
  the `n` has not arrived yet, in which case the trailing backslash is held
  back rather than shown."
  [raw k]
  (let [raw (str raw)
        needle (str "\"" (name k) "\"")
        i (str/index-of raw needle)]
    (when i
      ;; past the key, its colon and the opening quote of the value
      (let [after (subs raw (+ i (count needle)))
            j (str/index-of after "\"")]
        (when (and j (str/includes? (subs after 0 j) ":"))
          (loop [cs (seq (subs after (inc j))), out (StringBuilder.)]
            (if-not cs
              (str out)
              (let [c (first cs)]
                (cond
                  ;; an unescaped quote ends the value
                  (= \" c) (str out)
                  (not= \\ c) (recur (next cs) (.append out c))
                  ;; an escape whose second character has not arrived: hold it
                  (nil? (next cs)) (str out)
                  :else
                  (let [e (second cs)]
                    (case e
                      \n (recur (nnext cs) (.append out \newline))
                      \t (recur (nnext cs) (.append out \tab))
                      \r (recur (nnext cs) (.append out \return))
                      \u (if (>= (count (take 6 cs)) 6)
                           (recur (drop 6 cs)
                                  (.append out (char (Integer/parseInt (apply str (take 4 (drop 2 cs))) 16))))
                           (str out))
                      (recur (nnext cs) (.append out e)))))))))))))

(defn field-streamer
  "A stateful `(fn [fragment]) → [{:field :text} …]`, turning the raw fragments
  of a structured reply into the prose of the fields it carries.

  `fields` is the order they are watched in. Each call returns only what is NEW
  for each field, so a caller can print it directly.

  It labels rather than chooses: **what to show is the viewer's** — reasoning is
  what there is to show while a turn is still working and the answer is empty,
  and the answer is what matters once it starts (`byj-gly.56`)."
  [fields]
  (let [raw (StringBuilder.)
        sent (atom {})]
    (fn [fragment]
      (.append raw (str fragment))
      (let [text (str raw)]
        (if-not (str/starts-with? (str/triml text) "{")
          ;; not a structured reply at all — a provider streaming plain prose.
          ;; Label it and pass it through rather than show nothing, which is
          ;; what dropping an unrecognised shape would do.
          (let [have (count (get @sent :raw ""))]
            (when (> (count text) have)
              (swap! sent assoc :raw text)
              [{:field :raw :text (subs text have)}]))
          (vec (for [k fields
                     :let [v (partial-string-field text k)
                           have (count (get @sent k ""))]
                     :when (and v (> (count v) have))]
                 (do (swap! sent assoc k v)
                     {:field k :text (subs v have)}))))))))

(defn- cli-events
  "The CLI's stdout as events: a JSON array, one object, or NDJSON
  (provenance P-3).

  NDJSON is tried FIRST when more than one line parses. Reading the whole
  string first looks more natural and is wrong: given NDJSON, `read-str`
  succeeds on the leading object and stops, so a four-event stream arrived as
  its first event and the reply looked empty (provenance P-14). A
  pretty-printed object or array has no line that parses alone, so it still
  falls through to the whole-string read."
  [stdout]
  (let [trimmed (str/trim (str stdout))
        per-line (vec (keep #(when-not (str/blank? %)
                               (try (json/read-str %) (catch Throwable _ nil)))
                            (str/split-lines trimmed)))]
    (if (> (count per-line) 1)
      per-line
      (let [all (try (json/read-str trimmed) (catch Throwable _ nil))]
        (cond (sequential? all) (vec all)
              (map? all) [all]
              :else per-line)))))

(defn- unwrap-structured
  "Tool input as a JSON string, collapsing the {\"value\": \"<json>\"} envelope
  some models emit (brainyard's unwrap-structured-input)."
  [input]
  (if (and (map? input) (= [:value] (keys input)) (string? (:value input)))
    (:value input)
    (json/write-str input)))

(defn claude-cli-content
  "The reply text from CLI events, most reliable source first, as brainyard's
  normalize-response and salvage path do: the result event's
  :structured_output, a StructuredOutput tool_use block, the result string,
  then any assistant text."
  [events]
  (let [result (last (filter #(= "result" (:type %)) events))
        so (:structured_output result)
        tool-input (some (fn [e]
                           (when (= "assistant" (:type e))
                             (some #(when (and (= "tool_use" (:type %))
                                               (= "StructuredOutput" (:name %)))
                                      (:input %))
                                   (get-in e [:message :content]))))
                         events)
        text (->> events
                  (filter #(= "assistant" (:type %)))
                  (mapcat #(get-in % [:message :content]))
                  (keep #(when (= "text" (:type %)) (:text %)))
                  (str/join "\n"))]
    (cond
      (or (map? so) (sequential? so)) {:content (unwrap-structured so) :source :structured-output}
      tool-input {:content (unwrap-structured tool-input) :source :tool-use}
      ;; An errored result (refusal, api_error) carries its message as
      ;; assistant text; salvaging that as a reply would report a refusal as
      ;; "plain text with no JSON object" and hide the real error (provenance P-7).
      (:is_error result) nil
      (not (str/blank? (:result result))) {:content (:result result) :source :result}
      (not (str/blank? text)) {:content text :source :assistant-text}
      :else nil)))

(defn rejected-tool-calls
  "Tool calls the model made natively inside the CLI's own agent loop — a
  tool_use naming a tool from our prompt, which the CLI (run with --tools \"\")
  answered \"No such tool available\" — as [{:tool-name :tool-args}], in order.
  Only the provider sees these; after one, the model may answer that the tool
  does not exist, and the loop would stop on that answer (provenance P-12)."
  [events]
  (let [blocks (fn [type] (for [e events
                                b (get-in e [:message :content])
                                :when (= type (:type b))]
                            b))
        rejected (set (for [b (blocks "tool_result")
                            :when (and (:is_error b)
                                       (str/includes? (str (:content b)) "No such tool available"))]
                        (:tool_use_id b)))]
    (vec (distinct (for [b (blocks "tool_use")
                         :when (and (not= "StructuredOutput" (:name b)) (contains? rejected (:id b)))]
                     {:tool-name (:name b) :tool-args (or (:input b) {})})))))

(def claude-credential-keys
  "What of Claude Code's settings' `env` says how it signs in."
  ["ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN" "CLAUDE_CODE_OAUTH_TOKEN" "ANTHROPIC_BASE_URL"])

(defn claude-settings-credentials
  "The credential a person kept in Claude Code's own settings (`env` in
  `<dir>/settings.json`, `dir` CLAUDE_CONFIG_DIR's or `~/.claude`): `claude -p`
  runs here with `--setting-sources \"\"`, which reads none of its settings, so
  a key another program kept there for the person would not reach
  it. Only `claude-credential-keys`, nothing else of them."
  [dir]
  (let [f (java.io.File. (str dir) "settings.json")
        m (when (.isFile f) (try (json/read-str (slurp f)) (catch Throwable _ nil)))
        env (when (map? m) (:env m))]
    (into {} (for [k claude-credential-keys
                   :let [v (when (map? env) (get env (keyword k)))]
                   :when (and (string? v) (not (str/blank? v)))]
               [k v]))))

;; Why the invocation matches brainyard's to the flag: provenance P-6.
(defmethod chat* :claude-code [{:keys [timeout-ms stream?] :as lm} messages {:keys [json-schema on-chunk on-event]}]
  (let [;; a sampling parameter the caller asked for that no flag can carry.
        ;; Said once per call rather than swallowed: a measurement that ran at
        ;; an unknown temperature must not look like one that ran at 0
        ;; (`byj-gly.126`).
        _ (when-let [ignored (:ignored-params lm)]
            (events/emit! on-event :llm.cli/params-ignored
                          {:ignored ignored :model (:model lm)
                           :why "claude -p takes no sampling parameters"}))
        system (cond-> (str/join "\n\n" (keep #(when (= "system" (:role %)) (:content %)) messages))
                 json-schema (str structured-output-directive))
        user (str/join "\n\n" (keep #(when (not= "system" (:role %)) (:content %)) messages))
        cmd (cond-> (claude-cli-args lm system json-schema stream?)
              ;; :cli-command swaps the executable (tests stand a script in)
              (:cli-command lm) (assoc 0 (:cli-command lm)))
        ;; brainyard strips CLAUDECODE from the child: set when the host itself
        ;; runs under Claude Code, and it changes how the nested CLI behaves.
        ;; a credential in Claude Code's settings, which the flags make it
        ;; skip, given as its environment — what the environment already
        ;; says winning (byj-1wv.115.20)
        env (merge (claude-settings-credentials (or (not-empty (System/getenv "CLAUDE_CONFIG_DIR"))
                                                    (str (System/getProperty "user.home") "/.claude")))
                   (dissoc (into {} (System/getenv)) "CLAUDECODE"))
        ;; jolt's process shim does not flatten a command vector the way
        ;; babashka.process does — spread it (provenance P-1).
        ;; a process-task, so a cancelled turn or the timeout kills the CLI and
        ;; its children rather than leaving them running (RFC-009 rule 1)
        opts (cond-> {:in user :out :string :err :string :env env}
               ;; the session's project: Claude Code tells the model its cwd,
               ;; and this process's may be another repository (byj-gly.338)
               (and (:dir lm) (.isDirectory (java.io.File. (str (:dir lm))))) (assoc :dir (str (:dir lm))))
        ;; streaming reads the same events, one line at a time, so the parsing
        ;; below is unchanged — only the arrival is different (RFC-006)
        t0 (System/currentTimeMillis)
        ;; argv AND stdin: the pair that makes a call replayable. brainyard logs
        ;; the same (::cli-call, :args + :prompt), which is why its calls can be
        ;; replayed and this port's could not (byj-gly.119, provenance P-6)
        _ (events/emit! on-event :llm.cli/call
                        {:provider (:provider lm) :model (:model lm) :args cmd :stdin user})
        task (if stream?
               (apply fx/streaming-process opts
                      (fn [line] (when-let [t (stream-delta line)]
                                   (when on-chunk (on-chunk t))))
                      cmd)
               (apply fx/process-task opts cmd))
        r (try
            (fx/run-value!! (fx/timeout task (or timeout-ms 300000) ::timeout))
            (catch Throwable e
              (if (fx/cancelled? e)
                (throw e)
                (throw (ex-info (str "cannot run claude CLI: " (ex-message e)) {:fatal? true})))))]
    (when (= ::timeout r)
      (throw (ex-info "claude CLI timed out" {:retryable? true})))
    (let [events (cli-events (:out r))
          result (last (filter #(= "result" (:type %)) events))
          got (claude-cli-content events)
          attempted (rejected-tool-calls events)]
      ;; A nonzero exit or :is_error is fatal only when nothing is recoverable.
      (when-not got
        (throw (ex-info (str "claude CLI failed (exit " (:exit r) "): "
                             (or (:result result) (str/trim (str (:err r) (:out r)))))
                        {:retryable? false})))
      (events/emit! on-event :llm.cli/result
                    {:duration-ms (- (System/currentTimeMillis) t0)
                     :usage (anthropic-usage (:usage result))
                     :response (:result result)})
      (cond-> {:content (:content got)
               :source (:source got)
               ;; the CLI caches on its own, but it reports what that cost and
               ;; saved — and `total_cost_usd` is what it ACTUALLY billed, which
               ;; no price table can improve on (RFC-025, brainyard's :cli-cost)
               :usage (cond-> (anthropic-usage (:usage result))
                        (number? (:total_cost_usd result))
                        (assoc :cost-usd (:total_cost_usd result)))}
        (seq attempted) (assoc :attempted-tool-calls attempted)))))

;; --- codex: the claude-code provider for Codex (byj-gly.430.9, RFC-049) --------------------

(defn- toml-basic-string
  "`s` as a TOML basic string: JSON's escapes are TOML's, but for `\\/`, which
  data.json writes and TOML has not."
  [s]
  (str/replace (json/write-str (str s)) "\\/" "/"))

(defn codex-cli-args
  "`codex exec` as a model, not an agent (measured, RFC-049): JSON events on
  stdout; nothing persisted (`--ephemeral`); the person's config, rules and
  hooks not loaded (`--ignore-user-config`, `--ignore-rules`; auth still comes
  from `CODEX_HOME`); the project's AGENTS.md not read
  (`project_doc_max_bytes=0`); the shell tools off and the sandbox read-only,
  so it answers rather than acts; the system messages as
  `developer_instructions`; the reply's shape from `schema-file`
  (`--output-schema`); the conversation from stdin (`-`)."
  [{:keys [model effort]} system schema-file]
  (cond-> ["codex" "exec" "--json" "--ephemeral" "--skip-git-repo-check"
           "--ignore-user-config" "--ignore-rules" "-s" "read-only"
           "--disable" "shell_tool" "--disable" "unified_exec"
           "-c" "project_doc_max_bytes=0"]
    (not (str/blank? (str model))) (into ["-m" (str model)])
    effort (into ["-c" (str "model_reasoning_effort=" (toml-basic-string (name effort)))])
    (not (str/blank? system)) (into ["-c" (str "developer_instructions=" (toml-basic-string system))])
    schema-file (into ["--output-schema" (str schema-file)])
    true (conj "-")))

(defn codex-usage
  "`turn.completed`'s usage in the provider layer's shape: `input_tokens`
  already includes `cached_input_tokens` (the adapter rule an evaluator's run records follow), and
  `output_tokens` its `reasoning_output_tokens`."
  [u]
  (let [cached (or (:cached_input_tokens u) 0)]
    (cond-> {:input-tokens (or (:input_tokens u) 0)
             :output-tokens (or (:output_tokens u) 0)}
      (pos? cached) (assoc :cache {:read-tokens cached :write-tokens 0}))))

(defmethod chat* :codex [{:keys [timeout-ms] :as lm} messages {:keys [json-schema on-event]}]
  (let [_ (when-let [ignored (:ignored-params lm)]
            (events/emit! on-event :llm.cli/params-ignored
                          {:ignored ignored :model (:model lm)
                           :why "codex exec takes no sampling parameters"}))
        system (str/join "\n\n" (keep #(when (= "system" (:role %)) (:content %)) messages))
        user (str/join "\n\n" (keep #(when (not= "system" (:role %)) (:content %)) messages))
        ;; Codex sends --output-schema as a STRICT response format, and the API
        ;; refuses a schema with an optional or open property (measured, 0.163:
        ;; "'…args.properties' is required for object schemas"). Such a schema
        ;; is left to the system prompt, where it already is.
        schema-file (when (and json-schema (json-schema-strict? lm json-schema))
                      (doto (java.io.File/createTempFile "byj-codex-schema-" ".json")
                        (spit (json/write-str json-schema))
                        (.deleteOnExit)))
        cmd (cond-> (codex-cli-args lm system schema-file)
              ;; :cli-command swaps the executable (tests stand a script in)
              (:cli-command lm) (assoc 0 (:cli-command lm)))
        opts (cond-> {:in user :out :string :err :string}
               (and (:dir lm) (.isDirectory (java.io.File. (str (:dir lm))))) (assoc :dir (str (:dir lm))))
        t0 (System/currentTimeMillis)
        _ (events/emit! on-event :llm.cli/call {:provider (:provider lm) :model (:model lm) :args cmd :stdin user})
        r (try
            (fx/run-value!! (fx/timeout (apply fx/process-task opts cmd) (or timeout-ms 300000) ::timeout))
            (catch Throwable e
              (if (fx/cancelled? e)
                (throw e)
                (throw (ex-info (str "cannot run codex CLI: " (ex-message e)) {:fatal? true}))))
            (finally (some-> schema-file .delete)))]
    (when (= ::timeout r)
      (throw (ex-info "codex CLI timed out" {:retryable? true})))
    (let [events (cli-events (:out r))
          answer (some->> events (filter #(= "agent_message" (get-in % [:item :type]))) last :item :text)
          done (last (filter #(= "turn.completed" (:type %)) events))
          ;; an API error arrives as an `error` event whose message is the
          ;; API's JSON body; what it says is that body's error message
          failed (some #(when (#{"turn.failed" "error"} (:type %))
                          (let [m (or (get-in % [:error :message]) (:message %))]
                            (or (some-> (try (json/read-str m) (catch Throwable _ nil)) :error :message) m)))
                       events)
          usage (codex-usage (:usage done))]
      (when (str/blank? answer)
        (throw (ex-info (str "codex CLI failed (exit " (:exit r) "): "
                             (or failed (str/trim (str (:err r) (:out r)))))
                        {:retryable? false})))
      (events/emit! on-event :llm.cli/result
                    {:duration-ms (- (System/currentTimeMillis) t0) :usage usage :response answer})
      {:content answer :source :agent-message :usage usage})))

(defn mock-echo
  "What a mock LM with nothing scripted answers every call: the question
  echoed, and how much session history reached the prompt — enough to see the
  loops nest. `--provider mock`'s, and a mock named by label's (`mock/m`), which
  once had no replies at all and failed its first call (byj-gly.439)."
  [[system user]]
  (let [q (second (re-find #"(?m)^question: (.*)$" (str (:content user))))
        turns (count (re-seq #"\[Turn \d+" (str (:content system))))]
    (json/write-str {:reasoning "Mock provider: answering directly."
                     :tool-calls []
                     :answer (str "(mock) " q " — " turns " previous turn(s) in context")})))

(defmethod chat* :mock [{:keys [responses]} messages _opts]
  ;; :responses is an atom of queued replies — each a string or a
  ;; (fn [messages]) → string — or a single fn answering every call; none
  ;; at all is `mock-echo`. Used by tests and `--provider mock`.
  (let [responses (or responses mock-echo)
        r (if (fn? responses)
            responses
            (let [[queued] (swap-vals! responses #(vec (rest %)))] (first queued)))]
    (when (nil? r) (throw (ex-info "mock LM: no scripted responses left" {})))
    ;; a map reply is the whole provider result, e.g. {:content … :attempted-tool-calls […]}
    (let [r (if (fn? r) (r messages) r)]
      (if (map? r)
        (merge {:usage {:input-tokens 0 :output-tokens 0}} r)
        {:content r :usage {:input-tokens 0 :output-tokens 0}}))))

(defn chat
  "Run one chat completion with retry on transient failures.
  opts: :json-schema — the reply schema, for structured-output providers."
  ([lm messages] (chat lm messages {}))
  ([lm messages opts]
   (with-retry (select-keys lm [:max-retries :base-delay-ms])
     #(chat* lm messages opts))))
