;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.coach
  "The coach stance (RFC-067 phase 3, byj-gly.461): a session asked to coach
  the person toward a goal — they write the code, the agent hints by degrees
  and checks what they wrote — keeps it until it is stopped, across turns,
  compaction and a resume. Not Emacs's: Emacs is where the hints are best
  shown (`emacs$show`, `emacs$note`), but a session without it coaches in its
  answers.

  The stance is a file of the session's own, `<session dir>/coach.edn`,
  `{:goal :level :slug :started-at}`, so it is not in the history compaction
  shrinks, and a resume finds it where the session's snapshot is. `coach$set`
  writes it; the session tree's `:session/coach` node reads it as each turn
  begins and puts `prompts/coach.edn`, filled in, into the turn's
  user-context (`context-text`), as `@here` puts the editor. How to coach is
  that prompt's, not this namespace's (RFC-001)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.persist :as persist]))

(def levels
  "How much a hint says: the prompt's words for each."
  #{"beginner" "practitioner"})

(def subjects
  "What is coached: the person's code (phase 3), or Emacs itself (phase 9),
  each with its own section in prompts/coach.edn."
  #{"code" "emacs"})

(defn file
  "Where the session `config` names keeps its stance, or nil without one."
  [config]
  (when-let [id (:session-id config)]
    (io/file (persist/session-dir config id) "coach.edn")))

(defn stance
  "The stance in force, `{:goal :level :slug :started-at}`, or nil."
  [config]
  (try (let [f (file config)]
         (when (and f (.isFile f))
           (let [m (edn/read-string (slurp f))]
             (when (and (map? m) (not (str/blank? (str (:goal m))))) m))))
       (catch Throwable _ nil)))

(defn slug
  "The goal as a checklist's name: `coach-` and its first words, kebab-cased."
  [goal]
  (let [words (->> (str/split (str/lower-case (str goal)) #"[^a-z0-9]+") (remove str/blank?) (take 6))]
    (str "coach" (when (seq words) (str "-" (str/join "-" words))))))

(defn set-stance!
  "Start coaching toward `goal` at `level`, or with `stop`, end it: what was
  set, or `{:error}`."
  [config {:keys [goal level subject stop]}]
  (let [f (file config)]
    (cond
      (nil? f) {:error "this session keeps nothing between turns (no session id), so it cannot keep coaching"}
      stop (let [was (stance config)]
             (io/delete-file f true)
             (if was {:stopped (:goal was)} {:stopped nil :note "no coaching was on"}))
      (str/blank? (str goal)) {:error "give :goal, what the person wants to be able to do, or :stop"}
      :else (let [level (if (contains? levels (str level)) (str level) "practitioner")
                  subject (if (contains? subjects (str subject)) (str subject) "code")
                  m {:goal (str/trim (str goal)) :level level :subject subject :slug (slug goal)
                     :started-at (System/currentTimeMillis)}]
              (io/make-parents f)
              (spit f (pr-str m))
              m))))

(defn context-text
  "`templates` (prompts/coach.edn) filled in for stance `m`: the section a
  turn's user-context gets while coaching is on, or nil when it is not."
  [templates m]
  (when m
    (let [fill (fn [t vs] (str/replace (str t) #"\{([a-z-]+)\}"
                                       (fn [[whole k]] (if (contains? vs (keyword k)) (str (get vs (keyword k))) whole))))]
      ;; a stance kept before subjects were is the code's
      (fill (if (= "emacs" (:subject m)) (:emacs-context templates (:context templates)) (:context templates))
            {:goal (:goal m) :level (:level m) :slug (:slug m)
             :level-words (get-in templates [:levels (keyword (:level m))] "")}))))

(defn commands
  "`coach$set`, the session's own agent's: where the stance is set and ended."
  [config]
  [{:name "coach$set" :kind :command
    :description (str "Start coaching the person toward a goal, or stop. While it is on, every turn is told the goal and how to coach:"
                      " they write the code; you hint by degrees, check what they wrote and leave findings as notes, and write their"
                      " code only when they ask. It lasts until stopped, across turns and a resume. :level beginner or practitioner"
                      " (the default) says how much a hint gives away. :subject emacs coaches Emacs itself, from a lesson"
                      " (emacs$lessons), checking what they did with emacs$habits; code (the default) their code.")
    :input-schema [:map
                   [:goal {:optional true} [:string {:desc "What the person wants to be able to do, in their words."}]]
                   [:level {:optional true} [:string {:desc "beginner or practitioner."}]]
                   [:subject {:optional true} [:string {:desc "code (the default) or emacs, for learning Emacs itself."}]]
                   [:stop {:optional true} [:boolean {:desc "End coaching."}]]]
    :output-schema [:map-of :keyword :any]
    :effects #{}
    :fn (fn [args _ctx] (set-stance! config args))}])
