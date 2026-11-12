;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.cost
  "What a call cost (RFC-025) — brainyard's `clj-llm/core/usage.clj`
  `calculate-cost` and its price table.

  Two sources, in order:

  1. **What the provider says it billed.** The `claude` CLI reports
     `total_cost_usd` per call, which is what actually happened — it knows its
     own caching, its own discounts and its own plan. No table beats that.
  2. **The table**, `resources/brainyard/prices.edn`, for providers that report
     only tokens.

  **An unpriced model costs `nil`, not `0.0`** (divergence
  `unknown-cost-is-not-free`). brainyard returns an all-zero cost map and logs
  at debug, and names the hazard itself: *\"A model catalogued but unpriced
  still runs — it just reports a cost of 0.0, which reads as free rather than
  as unknown.\"* A turn whose cost is unknown says so here, and a total that
  had an unpriced call in it is marked `:partial?` rather than quietly
  understating."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def free
  "Providers that genuinely cost nothing: a local model and a fake one. They
  are not *unpriced* — nobody is billing for them — so they report 0.0 rather
  than nil, and a turn made of them is not marked partial."
  #{:mock :ollama})

(def bundled
  "The shipped table, USD per 1M tokens, `{provider {model rates}}`."
  (delay (edn/read-string (slurp (io/resource "brainyard/prices.edn")))))

(defn table
  "The prices in force: the bundled table with a project's
  `.brainyard/prices.edn` merged over it, per provider — so a project can
  correct one model without restating the rest.

  Never throws: an unreadable override leaves the bundled table standing,
  because a typo in a price file should cost a wrong number at worst, never a
  turn."
  [{:keys [project-dir]}]
  (let [f (io/file (or project-dir ".") ".brainyard" "prices.edn")
        extra (when (.isFile f)
                (try (let [m (edn/read-string (slurp f))] (when (map? m) m))
                     (catch Throwable _ nil)))]
    (merge-with merge @bundled extra)))

;; --- finding the rates ------------------------------------------------------------------

(defn strip-date
  "`claude-sonnet-4-5-20250929` → `claude-sonnet-4-5`. brainyard's
  `strip-version-suffix`."
  [model]
  (some-> model (str/replace #"-\d{4}-?\d{2}-?\d{2}$" "")))

(defn normalize-bedrock
  "A Bedrock id without its region prefix, `-vN(:M)` suffix or date stamp —
  brainyard's `normalize-bedrock-model-id`."
  [model]
  (some-> model
          (str/replace #"^(us|eu|apac|global)\." "")
          (str/replace #"-v\d+(:\d+)?$" "")
          (str/replace #"-\d{4}-?\d{2}-?\d{2}$" "")))

(defn rates-for
  "The rates for `provider`/`model`, or nil. brainyard's `get-pricing`: exact
  first, then without a date stamp, then — for Bedrock — normalised.

  No case folding, as brainyard has none: a model id is a vendor's string and
  guessing at its case would hide a typo rather than fix one."
  [table provider model]
  (when (and provider model)
    (let [;; a Codex model is OpenAI's, priced as the API would bill it
          ;; (byj-gly.430.9): what a ChatGPT plan's call would have cost
          byp (get table (case (keyword (name provider)) :codex :openai (keyword (name provider))))
          bedrock? (= :bedrock (keyword (name provider)))]
      (or (get byp model)
          (get byp (strip-date model))
          ;; a global endpoint is priced apart from a regional one: AWS bills
          ;; Claude 4.5 and later 10% more in-region than through `global.`
          ;; (byj-ewq). A `global.` row wins for a `global.` id; the region-less
          ;; row, the regional price, serves every other
          (when (and bedrock? (str/starts-with? (str model) "global."))
            (get byp (str "global." (normalize-bedrock model))))
          (when bedrock?
            (get byp (normalize-bedrock model)))))))

(defn rates-at
  "`rates` for a call of `input-tokens` (cache included — what a vendor counts
  as the prompt's length): its `:long-context` rates in place of the rest when
  the call is over its `:over`. Claude Haiku 5.5 bills a prompt over 100,000
  tokens at five times its rates (byj-ewq). Per call: a sum of calls priced as
  one would cross the line no call crossed."
  [rates input-tokens]
  (let [lc (:long-context rates)]
    (if (and lc (:over lc) (> (or input-tokens 0) (:over lc)))
      (merge (dissoc rates :long-context) (dissoc lc :over))
      (dissoc rates :long-context))))

;; --- the arithmetic ---------------------------------------------------------------------

(defn- at-rate [tokens rate]
  (if (and tokens rate (pos? tokens) (pos? rate))
    (* (/ (double tokens) 1000000.0) (double rate))
    0.0))

(defn of
  "What one call cost, or **nil when nothing here can say**.

  `usage` is the provider layer's shape: `:input-tokens` is the TOTAL input,
  cache included, so the fresh tokens are what is left after taking the cached
  ones out. Pricing `:input-tokens` whole would charge full rate for every
  token a cache hit just made cheap — worst on exactly the runs where caching
  is working best (RFC-015)."
  [table {:keys [input-tokens output-tokens cache cost-usd]} provider model]
  (cond
    ;; the provider told us what it billed
    (number? cost-usd)
    {:total-cost (double cost-usd) :source :provider}

    ;; free is not the same as unknown, and this namespace exists to keep them
    ;; apart — a local model and a fake one cost nothing, and saying so is an
    ;; answer where :partial? would be an admission of ignorance
    (free (keyword (name (or provider :none))))
    {:total-cost 0.0 :source :free}

    :else
    (when-let [rates (some-> (rates-for table provider model) (rates-at input-tokens))]
      (let [read (or (:read-tokens cache) 0)
            write (or (:write-tokens cache) 0)
            fresh (max 0 (- (or input-tokens 0) read write))
            in (at-rate fresh (:input rates))
            out (at-rate output-tokens (:output rates))
            cr (at-rate read (:cache-read rates))
            cw (at-rate write (:cache-write rates))]
        {:input-cost in :output-cost out
         :cache-read-cost cr :cache-write-cost cw
         :total-cost (+ in out cr cw)
         :source :table}))))

(defn add
  "Fold one call's cost into a turn's running total.

  `:cost` is the dollars known so far and `:unpriced` counts the calls that
  could not be priced. A total with `:unpriced` above zero is an
  understatement, and whoever reads it is entitled to know that rather than to
  infer it from a suspiciously round number."
  [usage-so-far c]
  (if c
    (update usage-so-far :cost (fnil + 0.0) (:total-cost c))
    (update usage-so-far :unpriced (fnil inc 0))))

(defn summary
  "A turn's cost as the trajectory records it: `{:cost … :partial? …}`, or nil
  when nothing was priced at all — an absent key says *unknown*, where a `0.0`
  would say *free*."
  [{:keys [cost unpriced]}]
  (when (or cost (pos? (or unpriced 0)))
    (cond-> {}
      cost (assoc :cost cost)
      (pos? (or unpriced 0)) (assoc :partial? true :unpriced unpriced))))
