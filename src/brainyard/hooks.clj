;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.hooks
  "The hook bus (RFC-011) — brainyard's `agent/core/hooks.clj`, scoped to a
  ctx instead of the process.

  A registry is an atom {event-key [entry ...]}; an entry is
    {:id \"redundant-tool-call-guard\" :event :agent.tool-use/pre
     :handler (fn [payload ctx] ...) :match (fn [payload] bool)
     :priority 0 :source :builtin :on-error :log}
  Higher :priority runs first; equal priorities keep registration order.

  Two ways to fire, as in brainyard:
  - `fire!` calls every matching handler for its side effects;
  - `fire-decision!` (gated events only) returns the first valid non-:allow
    decision — {:result :block|:replace|:modify-args ...} — or
    {:result :allow}. An :allow is an abstention, not a vote.

  A handler that throws is reported (:hooks/handler-failed) and skipped, so a
  gate fails open, unless its entry says :on-error :throw."
  (:require [malli.core :as m]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.schema :as schema]))

;; --- the catalog ------------------------------------------------------------------

(def event-catalog
  "The events this port fires: brainyard's names and payload keys, for the
  hook points that exist here. `:gates?` marks the events whose handlers may
  decide (fire-decision!)."
  {:agent.session/created {:keys #{:session-id :user-id :session} :doc "a session starts fresh"}
   :agent.session/resumed {:keys #{:session-id :user-id :session} :doc "a session starts from its snapshot (--resume)"}
   :agent.session/closed {:keys #{:session-id :user-id :session} :doc "a session ends, however it ends"}
   :agent.ask/pre {:keys #{:input} :doc "a turn starts"}
   :agent.ask/post {:keys #{:input :result :terminated-by} :doc "a turn ended"}
   :agent.iteration/pre {:keys #{:iteration :max-iterations} :doc "before each loop iteration"}
   :agent.iteration/post {:keys #{:iteration :max-iterations :channel :last-reasoning :goal-achieved}
                          :doc "after each loop iteration"}
   :agent.dspy-action/pre {:keys #{:node-id :predictor-id :inputs} :doc "before each LLM call"}
   :agent.dspy-action/post {:keys #{:node-id :predictor-id :inputs :outputs :reasoning :usage :error}
                            :doc "after each LLM call, success or failure"}
   :agent.tool-calls/pre {:keys #{:iteration :calls} :doc "before an iteration's tool-call batch"}
   :agent.tool-calls/post {:keys #{:iteration :calls :results} :doc "after the batch"}
   :agent.tool-use/pre {:keys #{:tool-name :args :call-id :depth} :gates? true
                        :doc "before each tool runs, including calls from user tools and hooks; may block, replace or modify-args"}
   :agent.tool-use/post {:keys #{:tool-name :args :call-id :result} :doc "after each tool result"}
   :agent.tool-use/rejected {:keys #{:tool-name :args :result :reason} :doc "a tool call's args were invalid; the tool did not run"}})

(defn gated? [event] (boolean (get-in event-catalog [event :gates?])))

(defn known-event? [event] (contains? event-catalog event))

;; --- registry ---------------------------------------------------------------------

(defn registry
  "A hook registry holding `entries`."
  ([] (registry []))
  ([entries] (let [reg (atom {})]
               (doseq [e entries] (swap! reg (fn [m] (update m (:event e) (fnil conj []) e))))
               reg)))

(defn register!
  "Add an entry; one with the same event and id is replaced (brainyard's
  register-hook!)."
  [reg {:keys [event id] :as entry}]
  (swap! reg update event
         (fn [es] (conj (vec (remove #(= id (:id %)) es)) entry))))

(defn unregister!
  "Remove the entry `id` from `event`; true when there was one."
  [reg event id]
  (let [[old _] (swap-vals! reg update event (fn [es] (vec (remove #(= id (:id %)) es))))]
    (boolean (some #(= id (:id %)) (get old event)))))

(defn entries
  "Every entry, or those for one event."
  ([reg] (vec (mapcat val @reg)))
  ([reg event] (get @reg event [])))

;; --- firing -----------------------------------------------------------------------

(def ^:dynamic *depth*
  "Hook re-entrancy depth: 1 inside a handler, 2 inside a handler fired from
  one (brainyard's *hook-depth*)."
  0)

(defn- matching [reg event payload]
  (->> (get @reg event)
       (filter (fn [{:keys [match]}]
                 (or (nil? match) (try (boolean (match payload)) (catch Throwable _ false)))))
       (sort-by (comp - #(or % 0) :priority))))

(defn- emit!
  "Put an event on the bus (RFC-026). One named door, so the catalogue of what
  the kernel emits can be checked against the source."
  [ctx event data]
  (events/emit! ctx event data))

(defn- invoke [ctx event {:keys [id handler on-error source]} payload]
  (try
    (binding [*depth* (inc *depth*)]
      (handler payload ctx))
    (catch Throwable e
      ;; a cancelled turn is not a hook failure (RFC-009 rule 7)
      (when (or (= :throw on-error) (fx/cancelled? e))
        (throw e))
      (emit! ctx :hooks/handler-failed {:event event :id id :source source :error (ex-message e)})
      nil)))

(defn fire!
  "Call every handler matching `payload` for its side effects. Returns nil."
  [ctx event payload]
  (when-let [reg (:hooks ctx)]
    (doseq [e (matching reg event payload)]
      (invoke ctx event e payload))))

(def allow {:result :allow})

(def decision-schema
  "A gated handler's decision, other than allow: block; replace the result
  with `:replacement` (which may be nil); or run the tool on other, non-empty
  `:args`."
  [:multi {:dispatch :result}
   [:block :map]
   [:replace [:map [:replacement :any]]]
   [:modify-args [:map [:args [:and :map [:fn seq]]]]]])

(def ^:private valid-decision? (m/validator (schema/schema decision-schema)))

(defn fire-decision!
  "Run the handlers of a gated event in order until one returns a valid
  non-:allow decision, and return it with :by set to the handler's id; else
  {:result :allow}. Modifications do not chain. A malformed decision is
  reported and skipped."
  [ctx event payload]
  (or (when-let [reg (:hooks ctx)]
        (reduce (fn [_ {:keys [id] :as e}]
                  (let [d (invoke ctx event e payload)]
                    (cond
                      (or (not (map? d)) (nil? (:result d)) (= :allow (:result d))) nil
                      (valid-decision? d) (reduced (assoc d :by (or (:by d) id)))
                      :else (do (emit! ctx :hooks/malformed-decision {:event event :id id :decision d})
                                nil))))
                nil
                (matching reg event payload)))
      allow))

;; --- the tree leaf ----------------------------------------------------------------

(defn- payload-of
  "Build a payload from a node's :payload template: a keyword is read from
  st-memory, `:config/x` from config, a map is built recursively, anything
  else is taken literally."
  [template mem config]
  (cond
    (map? template) (into {} (for [[k v] template] [k (payload-of v mem config)]))
    (and (keyword? template) (= "config" (namespace template))) (get config (keyword (name template)))
    (keyword? template) (get mem template)
    :else template))

(defn ^{:reads #{} :writes #{}}
  fire-action
  "Leaf `:hooks/fire` — fire the node's `:event` with a payload built from its
  `:payload` template, so which hook points a loop has is part of the tree:

    [:action {:fn :hooks/fire :event :agent.iteration/pre
              :payload {:iteration :iteration-count :max-iterations :config/max-iterations}}]"
  [{:keys [node st-memory config] :as ctx}]
  (fire! ctx (:event node) (payload-of (:payload node) @st-memory config)))
