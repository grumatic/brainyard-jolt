(ns brainyard.codex-usage
  "Codex's usage (byj-gly.430.7, RFC-049): a session's tokens and what they
  cost, and the plan's limits — what `claude-usage` is for Claude Code, read
  where `/usage` and the footer read Claude Code's.

  No call to anything: Codex writes both into its rollouts. A `token_count`
  event carries the thread's running `total_token_usage` — `input_tokens`
  INCLUDING its `cached_input_tokens`, `output_tokens` including its
  `reasoning_output_tokens` (the adapter rule an evaluator's run records follow) — and the plan's
  `rate_limits` as Codex last heard them: `plan_type`, and a `primary` and a
  `secondary` window, each `{used_percent window_minutes resets_at}` (epoch
  seconds). The model is the newest `turn_context`'s. A session's cost is
  priced from prices.edn's `:openai` table; a model it does not price costs
  nothing known — nil, never a guess (measured: Codex 0.163's default model,
  gpt-6-luna, is not in it)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.claude-usage :as claude-usage]
            [brainyard.codex-sessions :as cxs]
            [brainyard.cost :as cost]
            [brainyard.json :as json]))

(def tail-bytes
  "How much of a rollout's end is read for its newest token_count and
  turn_context: one is written after every reply."
  (* 1024 1024))

(defn- tail-lines [^java.io.File f]
  (let [n (.length f)
        start (max 0 (- n tail-bytes))
        s (with-open [in (java.io.FileInputStream. f)]
            (when (pos? start) (.skip in start))
            (String. (.readAllBytes in) "UTF-8"))]
    (cond->> (str/split-lines s) (pos? start) rest)))

(defn- newest [lines pred]
  (some (fn [l] (when (pred l) (try (let [m (json/read-str l)] (when (map? m) m)) (catch Throwable _ nil))))
        (reverse lines)))

(defn session-usage
  "Rollout `f`'s running totals: `{:model :input :cached :output :reasoning
  :rate-limits}`, or nil when it has counted nothing yet."
  [f]
  (when (and f (.isFile (io/file f)))
    (let [ls (tail-lines (io/file f))
          tc (newest ls #(str/includes? % "\"token_count\""))
          ctx (newest ls #(str/includes? % "\"turn_context\""))
          u (get-in tc [:payload :info :total_token_usage])]
      (when u
        {:model (get-in ctx [:payload :model])
         :input (:input_tokens u 0) :cached (:cached_input_tokens u 0)
         :output (:output_tokens u 0) :reasoning (:reasoning_output_tokens u 0)
         :rate-limits (get-in tc [:payload :rate_limits])}))))

(defn usage-cost
  "What `usage` (`session-usage`'s) cost, in USD, by the price table's
  `:openai` rates; nil when the model is not priced."
  [config {:keys [model input cached output]}]
  (when model
    (let [table (cost/table config)]
      (when (cost/rates-for table :openai model)
        (:total-cost (cost/of table {:input-tokens input :output-tokens output :cache {:read-tokens cached}}
                              :openai model))))))

;; --- the plan's limits ----------------------------------------------------------------------

(defn- window-label [minutes]
  (case (long (or minutes 0))
    300 "5-hour" 10080 "Weekly" 43200 "30-day"
    (str minutes "-min")))

(defn- limits-of
  "`rate_limits` as `claude-usage`'s snapshot: `{:plan :limits [{:label
  :percent :resets-at}]}`."
  [rl]
  {:plan (:plan_type rl)
   :limits (vec (for [k [:primary :secondary]
                      :let [w (get rl k)]
                      :when (map? w)]
                  {:label (window-label (:window_minutes w))
                   :percent (int (Math/round (double (or (:used_percent w) 0))))
                   :resets-at (some-> (:resets_at w) long (java.time.Instant/ofEpochSecond) str)}))})

(def ttl-ms
  "How long `limits` keeps what it read: the footer asks every frame."
  30000)

(defonce ^:private cache (atom {}))

(defn limits
  "The plan's limits as Codex last reported them — the newest rollout's newest
  `rate_limits` — `{:plan :limits :at}`, or nil when the home holds none. Read
  at most every `ttl-ms` for a home."
  [& [home]]
  (let [k (str (cxs/codex-home home))
        {:keys [at v]} (get @cache k)
        now (System/currentTimeMillis)]
    (if (and at (< (- now at) ttl-ms))
      v
      (let [f (last (sort-by #(.lastModified ^java.io.File %) (cxs/rollouts home)))
            rl (some-> f session-usage :rate-limits)
            v (when (map? rl) (assoc (limits-of rl) :at (when f (.lastModified ^java.io.File f))))]
        (swap! cache assoc k {:at now :v v})
        v))))

(defn limit-lines
  "The plan's windows, a line each, as `claude-usage/limit-lines` draws Claude
  Code's — `label  bar  n%  resets in …`."
  [l]
  (if (empty? (:limits l))
    ["  unknown — Codex has reported no limits"]
    (claude-usage/limit-lines {:logged-in? true :limits (:limits l)})))

(defn warning
  "A footer word when a window Codex last reported is at 90% or more —
  `Codex 30-day 93%` — else nil."
  [& [home]]
  (when-let [hot (seq (filter #(>= (:percent %) 90) (:limits (limits home))))]
    (let [{:keys [label percent]} (apply max-key :percent hot)]
      (str "Codex " label " " percent "%"))))
