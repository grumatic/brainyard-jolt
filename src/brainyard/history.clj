(ns brainyard.history
  "What a finished turn leaves behind, and how it is shown to the next one —
  brainyard's `previous-turns/append-turn` and coact `format-previous-turns`.

  Two callers share it, which is why it is its own namespace: a session's turns
  (RFC-004) and a subagent instance's own turns (RFC-018). They are the same
  thing at different scopes — a question, an answer, and how the answer was
  reached — and rendering them differently would mean a model reading two
  formats for one idea.

  Recent turns keep their last tool calls; older ones keep only Q and A."
  (:require [clojure.string :as str]))

(defn clip [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn turn-summary
  "What a finished turn contributes to history."
  [{:keys [question answer trajectory-iterations terminated-by]}]
  {:question question
   :answer answer
   :terminated-by terminated-by
   :tool-trail (vec (for [{:keys [tool-results]} trajectory-iterations
                          {:keys [tool-name tool-args]} tool-results]
                      (str tool-name " " (pr-str tool-args))))})

(defn format-previous-turns
  "The history-context block, after brainyard's coact format-previous-turns."
  [turns {:keys [full-depth max-question-chars max-answer-chars]
          :or {full-depth 3 max-question-chars 500 max-answer-chars 1000}}]
  (when (seq turns)
    (let [n (count turns)]
      (str/join
       "\n\n"
       (map-indexed
        (fn [i {:keys [question answer tool-trail terminated-by]}]
          (let [full? (>= i (- n full-depth))]
            (cond-> (str "[Turn " (inc i) (if full? " · full" " · summary") "]"
                         "\n  Q: " (clip question max-question-chars))
              ;; a compacted summary is the history it replaced, written to
              ;; fit: clipping it like an answer cut it on every later prompt
              ;; (byj-gly.232)
              (not (str/blank? answer)) (str "\n  A: " (if (= :compacted terminated-by)
                                                         answer
                                                         (clip answer max-answer-chars)))
              (and full? (seq tool-trail))
              (str "\n  tools: " (str/join "; " (map #(clip % 200) (take-last 5 tool-trail)))))))
        turns)))))

(defn parent-trail
  "The caller's last turns, as the block a subagent reads — brainyard's
  `## Parent Trail` section of `coact-user-context`.

  Rendered by the same formatter as a turn's own history, which is brainyard's
  choice and the right one: a child that has learned to read `[Turn n]` should
  not have to learn a second shape for the same thing. The turns are numbered
  from 1 WITHIN the trail rather than by the caller's real numbers — telling a
  child it is looking at turns 7 to 9 invites it to ask about turn 6, which it
  cannot see."
  [turns config]
  (when (seq turns)
    (str "## Parent Trail (last " (count turns) " turns from caller)\n"
         (format-previous-turns turns config))))

(defn append
  "Add a finished turn to `turns`, keeping at most `max-previous-turns`."
  [turns turn config]
  (vec (take-last (:max-previous-turns config 100) (conj (vec turns) (turn-summary turn)))))
