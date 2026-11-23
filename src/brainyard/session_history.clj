;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.session-history
  "A session's whole history, turn by turn, from what it left on disk
  (byj-lie): what a client shows again when it loads a session.

  A snapshot keeps the last `persist/transcript-entries` of what was said —
  a screenful, which in a long session that ran many tools begins dozens of
  turns from the start. The rest is on disk all the same, in three places,
  none of them whole:

  - the trajectory (RFC-025): every turn that ended, with its question, its
    thoughts, its tool calls and its answer — but no turn that was cancelled
    or a `!` line, and, before byj-pzb, no turn number;
  - the turn refs (RFC-069, `changes/subjects`): every turn's number and
    question, kept or not changed — when the project is a git repository,
    `:changes?` is on, and the newest `:changes-turns` of them;
  - the snapshot's `:previous-turns`: the last ten turns, a cancelled one's
    answer among them.

  `turns` puts them together: each turn once, numbered as `byj turns list`
  numbers it, with what each source knows of it, and the checkpoint the
  turn refs keep of it — what a rewind goes back to (byj-d8c.1). A call
  that ran a subagent holds that subagent's turn as `:children`, from the
  subagent's own record (byj-cux). Reads only."
  (:require [clojure.string :as str]
            [brainyard.changes :as changes]
            [brainyard.persist :as persist]
            [brainyard.trajectory :as trajectory]))

(defn- norm [s] (str/trim (str/replace (str s) #"\s+" " ")))

(def ^:private compared-chars
  "How much of a question two sources must agree on to be one turn's: a turn
  ref's subject is the question's first paragraph, and a record's may be cut."
  60)

(defn same-question?
  "Do a record's question `q` and a turn ref's `subject` name one turn: their
  first `compared-chars`, blanks collapsed, alike."
  [q subject]
  (let [a (norm q) b (norm subject) n (min (count a) (count b) compared-chars)]
    (and (pos? n) (= (subs a 0 n) (subs b 0 n)))))

(defn numbered-records
  "The session's own records (not a subagent's: those name an `:owner`), in
  order, each as `[turn record]`. A record that says its `:turn` is that
  turn. One written before records did (byj-pzb) is the next of `subjects`
  (`changes/subjects`, ascending) whose question is its own; with none, the
  turn after the last one numbered — so a session with no turn refs is
  numbered by position, as it always was."
  [records subjects]
  (loop [rs (remove :owner records) ss subjects last-n 0 out []]
    (if-let [r (first rs)]
      (let [hit (when-not (:turn r) (first (filter #(same-question? (:question r) (:subject %)) ss)))
            n (or (:turn r) (:turn hit) (inc last-n))]
        (recur (rest rs) (drop-while #(<= (:turn %) n) ss) n (conj out [n r])))
      out)))

(defn- steps
  "A record's iterations as what a client shows of them, in order: a thought,
  then the calls it made."
  [record]
  (vec (for [it (:iterations record)
             :when (not= "answer" (:channel it))
             step (concat (when-not (str/blank? (str (:thought it))) [{:thought (:thought it)}])
                          (when (seq (:tools it)) [{:tools (:tools it)}]))]
         step)))

(defn- runs-child?
  "Did call `tool` run subagent instance `id`: a dispatch whose result names
  it (`:subagent-id`), or an `agent-registry$ask` of it (its `:id`)."
  [{:keys [args result]} id]
  (or (str/includes? (str result) (str ":subagent-id " (pr-str id)))
      (= id (str (get (if (map? args) args {}) :id)))))

(defn- agent-of
  "The agent an instance id is of: `explore` for `explore/red-cat-1`."
  [id]
  (first (str/split (str id) #"/" 2)))

(declare with-children)

(defn- child-node
  "A subagent's record as a client is shown it under the call that ran it:
  its instance, and its own steps with its children under theirs."
  [rec recs]
  {:agent (:agent rec) :steps (with-children (steps rec) (:agent rec) recs)})

(defn- with-children
  "STEPS, the turn of caller `owner` (`root`, or an instance), with each
  call that ran a subagent holding that subagent's turn as `:children`
  (byj-cux): `recs` are the turn's subagent records, in order. A call claims
  the first record of `owner`'s it names (`runs-child?`), else the first of
  its agent (`agent$<agent>`): a dispatch that kept no instance."
  [steps owner recs]
  (let [mine (atom (vec (filter #(= (str owner) (str (:owner %))) recs)))
        take! (fn [pred] (when-let [r (first (filter pred @mine))]
                           (swap! mine (fn [rs] (vec (remove #(identical? % r) rs))))
                           r))]
    (vec (for [step steps]
           (if-let [tools (:tools step)]
             (assoc step :tools
                    (vec (for [t tools
                               :let [r (or (take! #(runs-child? t (:agent %)))
                                           (take! #(= (str (:name t)) (str "agent$" (agent-of (:agent %))))))]]
                           (cond-> t r (assoc :children [(child-node r recs)])))))
             step)))))

(defn checkpoint
  "A turn's checkpoint as a client is told it: its ref, the work tree's
  commits as it began and ended, and how many files it changed — from
  `changes/rows`."
  [{:keys [ref before after files]}]
  {:ref ref :before before :after after :files-changed (count files)})

(defn turns
  "Session `id`'s turns, ascending: `{:turn :question :answer :steps
  :terminated-by :checkpoint}`, each from the first source that has it —
  the trajectory, then the snapshot's last turns, then the turn refs,
  which know only the question. `:checkpoint` (`checkpoint`) is there while
  byj keeps the turn's refs (RFC-069): not for one begun outside git, with
  `:changes?` off, or pruned. A turn in a /compact summary is the summary's
  no longer. [] when the session left none of them."
  [config id]
  (let [records (trajectory/read-trajectories config id)
        subjects (try (changes/subjects config id) (catch Throwable _ []))
        kept (into {} (map (juxt :turn checkpoint)) (try (changes/rows config id) (catch Throwable _ [])))
        snap (persist/read-session config id)
        prev (when-not (:problem snap)
               (remove (fn [[_ e]] (:compacted e))
                       (persist/numbered (:previous-turns snap) (:turn-count snap 0))))
        ;; each turn's subagent records (byj-pzb numbers them by the session
        ;; turn they ran in), put under the calls that ran them (byj-cux)
        kids (group-by :turn (filter #(and (:owner %) (pos-int? (:turn %))) records))
        from-records (for [[n r] (numbered-records records subjects)]
                       [n {:turn n :question (:question r) :answer (trajectory/record-answer r)
                           :steps (with-children (steps r) "root" (get kids n)) :terminated-by (:terminated-by r)}])
        from-prev (for [[n e] prev]
                    [n {:turn n :question (:question e) :answer (:answer e) :steps []
                        :terminated-by (:terminated-by e)}])
        from-refs (for [{:keys [turn subject]} subjects]
                    [turn {:turn turn :question subject :steps []}])]
    (->> (concat from-refs from-prev from-records)
         ;; the later source wins: records, then the snapshot, then the refs
         (reduce (fn [m [n t]] (if (pos-int? n) (assoc m n t) m)) (sorted-map))
         vals
         (mapv #(if-let [c (kept (:turn %))] (assoc % :checkpoint c) %)))))
