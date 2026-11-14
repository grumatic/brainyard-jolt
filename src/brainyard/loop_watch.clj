;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-watch
  "The turns after a loop change, watched against the turns before it (RFC-071
  \"After adoption\", byj-ng7.16): from the project's own trajectory log
  (RFC-025), the turns of the sessions begun since the loop's last change in
  `.brainyard/loops/history.edn` — the ones that ran it, since a session
  expands its loop when it starts — beside the turns that ended before it. A
  regression past a guard's bound offers the loop before the change back as
  a revert proposal (`loop-proposals/stage-revert!`), for a person to accept;
  this never accepts, and stages nothing while a revert of that loop waits.

  The guards are the evaluation's (`loop-eval/default-guards`) on what a
  record keeps — cost, tokens, iterations, tool errors, refusals — and one of
  its own, `:unfinished`, the share of turns that did not end with an answer.
  A turn's wall time is not in its record, so it is not watched. Too few
  turns since the change decide nothing.

  Mechanism only (RFC-001): when it is asked — `byj loops watch` — is the
  person's."
  (:require [clojure.string :as str]
            [brainyard.loop-eval :as loop-eval]
            [brainyard.loop-proposals :as loop-proposals]
            [brainyard.project-loops :as project-loops]
            [brainyard.trajectory :as trajectory]))

(def min-after
  "How many turns since the change before the watch says anything."
  5)

(def window
  "How many of the turns before the change are its baseline: the newest."
  30)

(def guards
  "The evaluation's guards, wall time aside, and the share of turns left
  unfinished."
  (-> loop-eval/default-guards
      (dissoc :wall-ms)
      (assoc :unfinished {:ratio 1.5 :slack 0.1})))

(defn- calls
  "The model's calls in `record`: a tree's own (`:tool/run`, an attachment's
  lint) are not its errors or refusals (byj-8gc)."
  [record]
  (filter trajectory/model-call? (mapcat :tools (:iterations record))))

(defn- failed?
  "A call the record marks failed (`:error?`, RFC-025), or whose result reads
  as one: an error map, or a command's non-zero exit."
  [c]
  (let [s (str (:result c))]
    (boolean (or (:error? c) (str/starts-with? s "{:error") (re-find #":exit-code [1-9]" s)))))

(defn- refused?
  "A failed call whose result says it was refused — a gate's block included,
  which prints as `{:hook-blocked …}`. Only a failed one: a read of a file
  that mentions refusals is not (byj-ng7.17)."
  [c]
  (and (failed? c) (str/includes? (str (:result c)) "refused")))

(defn turn-metrics
  "What one turn record says of the guards: `{:cost :tokens :iterations
  :tool-errors :refusals :unfinished}`."
  [r]
  (let [cs (calls r)]
    {:cost (double (or (:cost r) 0.0))
     :tokens (+ (get-in r [:usage :in] 0) (get-in r [:usage :out] 0))
     :iterations (or (:total-iterations r) (count (:iterations r)))
     :tool-errors (count (filter failed? cs))
     :refusals (count (filter refused? cs))
     :unfinished (if (= :answer-channel (:terminated-by r)) 0 1)}))

(defn- means [records]
  {:means (into {} (for [k (keys guards)]
                     [k (if (seq records)
                          (/ (double (reduce + (map #(get (turn-metrics %) k 0) records))) (count records))
                          0.0)]))})

(defn- epoch-ms [iso] (try (.toEpochMilli (java.time.Instant/parse (str iso))) (catch Throwable _ nil)))

(defn last-change
  "The last line of loop `ln`'s history, with `:at-ms`, or nil."
  [config ln]
  (some-> (last (filter #(= ln (:loop %)) (project-loops/history (loop-proposals/project-dir config))))
          (#(assoc % :at-ms (epoch-ms (:at %))))))

(defn split-turns
  "The project's root turns around `at-ms`: `{:before :after}` — after, every
  turn of a session whose first turn ended after it; before, the newest
  `window` turns that ended before it."
  [config at-ms]
  (let [config (assoc config :project-dir (loop-proposals/project-dir config))
        by-session (for [id (trajectory/sessions config)
                         :let [rs (vec (remove :owner (trajectory/read-trajectories config id)))]
                         :when (seq rs)]
                     rs)]
    {:after (vec (mapcat (fn [rs] (when (> (or (:ts (first rs)) 0) at-ms) rs)) by-session))
     :before (->> (mapcat identity by-session)
                  (filter #(< (or (:ts %) 0) at-ms))
                  (sort-by :ts)
                  (take-last window)
                  vec)}))

(defn watch
  "How the turns since loop `:loop`'s last change compare with those before:
  `{:loop :change :before :after :means {:before :after} :regressions
  :verdict}`, `:verdict` `:regressed`, `:holding`, `:too-early` or
  `:no-change`."
  [config {:keys [loop]}]
  (let [ln (or (project-loops/loop-name (or loop "react")) "react")
        change (last-change config ln)]
    (if-not (:at-ms change)
      {:loop ln :verdict :no-change}
      (let [{:keys [before after]} (split-turns config (:at-ms change))
            b (means before) a (means after)
            regs (loop-eval/guard-regressions b a guards)]
        {:loop ln :change (dissoc change :at-ms)
         :before (count before) :after (count after)
         :means {:before (:means b) :after (:means a)}
         :regressions regs
         :verdict (cond
                    (< (count after) min-after) :too-early
                    (empty? before) :too-early
                    (seq regs) :regressed
                    :else :holding)}))))

(defn- revert-waiting? [config ln]
  (some #(and (= ln (:loop %)) (or (= :revert (:action %)) (:revert? %))) (loop-proposals/pending config)))

(defn watch!
  "`watch`, and on `:regressed` the loop before the change staged as a revert
  proposal with why — unless one for that loop already waits. Adds `:staged
  {:id :dir}` or `:not-staged` (why)."
  [config opts]
  (let [w (watch config opts)
        ln (:loop w)]
    (cond
      (not= :regressed (:verdict w)) w
      (revert-waiting? config ln) (assoc w :not-staged (str "a revert of the " ln " loop already waits at /proposals"))
      :else
      (let [why (str "Since " (or (get-in w [:change :proposal]) "the last change") " (" (name (get-in w [:change :action]))
                     " at " (get-in w [:change :at]) "), " (:after w) " turn(s) against the " (:before w) " before regressed: "
                     (str/join "; " (for [{:keys [metric base candidate]} (:regressions w)]
                                      (format "%s %.2f against %.2f" (name metric) candidate base)))
                     ".")
            s (loop-proposals/stage-revert! config ln)]
        (if (:error s)
          (assoc w :not-staged (:error s))
          (do (spit (java.io.File. (str (:dir s)) "why.md") (str why "\n"))
              (assoc w :staged s)))))))

(defn summary-lines
  "What `byj loops watch` prints."
  [{:keys [loop verdict before after regressions staged not-staged change]}]
  (concat
   [(str loop ": " (name verdict)
         (case verdict
           :no-change " — the project's history holds no change to this loop"
           :too-early (str " — " after " turn(s) since the change, " before " before; " min-after " since are needed")
           :holding (str " — " after " turn(s) since " (or (:proposal change) "the change") " hold against " before " before")
           :regressed (str " — " after " turn(s) since " (or (:proposal change) "the change") " regressed against " before " before")
           ""))]
   (for [{:keys [metric base candidate bound]} regressions]
     (format "  %s: %.2f since, %.2f before (bound %.2f)" (name metric) candidate base bound))
   (cond
     staged [(str "staged loop/" (:id staged) " — the loop before the change, for a person to accept at /proposals")]
     not-staged [(str "not staged: " not-staged)])))
