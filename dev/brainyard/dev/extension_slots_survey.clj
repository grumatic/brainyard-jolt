;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.extension-slots-survey
  "How would each way of extending the bundled ReAct loop have fared over its
  history? (byj-9sp.1, RFC-072 \"Deciding\")

      jolt -A:dev -m brainyard.dev.extension-slots-survey REPO…

  Reads every version of the ReAct loop in each git REPO's branches and tags
  — `resources/brainyard/react-loop.edn`, later `loops/react.edn` — oldest
  first, the repositories' histories joined by commit time. A turn's
  checkpoint commit (`refs/byj/…`) is not history and is not read. No model
  is called.

  Two series: every commit, and every release (`v*` tags), the second being
  what a project upgrading byj meets. For each step from one version to the
  next it counts:

  - **semantic:** the tree as data changed (`flow/tree-hash`, comments and
    layout not counted). Each is one a whole-loop override falls behind and is
    asked to be evaluated again for (RFC-071).
  - **anchors broken:** node ids removed or moved under another parent. Each
    refuses a patch overlay anchored on that id.
  - **slot sites:** the steps that changed a proposed slot's surroundings.
    `ext-after-tools` sits after `:react/tool-dispatch` in its parent.
    `ext-answer-check` must sit on every path that accepts an answer: the
    subtree of `:react/answer-path`. A step here is one byj's maintainers would
    have had to carry the slot's contract through. It does not break an
    attachment, since the slot moves with the loop.

  Then the cost of two empty slots: the expansion's time and hash, with the
  slots as the `:sequence` they would expand to."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.bt :as bt]
            [brainyard.effect :as fx]
            [brainyard.flow :as flow]))

(def paths ["resources/brainyard/react-loop.edn" "resources/brainyard/loops/react.edn"])

(defn- git [dir & args]
  (let [r (apply fx/sh {:dir dir :out :string :err :string} "git" args)]
    (when (zero? (:exit r)) (:out r))))

(defn- at
  "The ReAct loop at `rev` in `dir`, as text, or nil."
  [dir rev]
  (some #(git dir "show" (str rev ":" %)) paths))

(defn commit-versions
  "`[{:at epoch-s :rev :subject :text}]` for every commit on a branch or tag
  of `dir` that touched the loop and has it."
  [dir]
  (for [line (str/split-lines (or (apply git dir "log" "--branches" "--tags" "--reverse"
                                         "--format=%H%x09%ct%x09%s" "--" paths) ""))
        :let [[rev ct subject] (str/split line #"\t" 3)
              text (at dir rev)]
        :when text]
    {:at (parse-long ct) :rev (subs rev 0 8) :subject subject :text text}))

(defn- semver [t] (mapv parse-long (rest (re-matches #"v(\d+)\.(\d+)\.(\d+)" t))))

(defn release-versions
  "`[{:at :rev :text}]` for every `vX.Y.Z` tag of `dir` holding the loop."
  [dir]
  (for [t (sort-by semver (filter #(re-matches #"v\d+\.\d+\.\d+" %)
                                  (str/split-lines (or (git dir "tag") ""))))
        :let [text (at dir t)
              ct (some-> (git dir "log" "-1" "--format=%ct" t) str/trim parse-long)]
        :when text]
    {:at ct :rev t :text text}))

(defn- distinct-texts
  "Versions in time order, a version whose text equals the one before dropped
  — the same loop reached through two repositories."
  [vs]
  (->> (sort-by :at vs)
       (reduce (fn [acc v] (if (= (:text v) (:text (peek acc))) acc (conj acc v))) [])))

(defn index
  "`{id {:parent :kind :opts :children}}` for every node with an id."
  ([tree] (index tree nil {}))
  ([[kind opts & children] parent acc]
   (let [id (:id opts)
         acc (if id
               (assoc acc id {:parent parent :kind kind :opts (dissoc opts :id)
                              :children (vec (keep #(when (vector? %) (:id (second %))) children))})
               acc)]
     (reduce (fn [a c] (if (vector? c) (index c (or id parent) a) a)) acc children))))

(defn- subtree [tree id]
  (first (filter #(and (vector? %) (= id (:id (second %)))) (tree-seq vector? seq tree))))

(defn- shape
  "A subtree's kinds, ids and fns, its other opts left out: what a slot's
  surroundings are made of."
  [node]
  (when node
    (let [[kind opts & cs] node]
      (into [kind (select-keys opts [:id :fn])] (keep #(when (vector? %) (shape %))) cs))))

(def slots
  "The proposed slots and what each one's surroundings are."
  {:react/ext-after-tools
   (fn [tree ix]
     (let [p (get-in ix [:react/tool-dispatch :parent])]
       [p (get-in ix [p :children]) (:react/tool-dispatch ix)]))
   :react/ext-answer-check
   (fn [tree _] (shape (subtree tree :react/answer-path)))})

(defn step
  "What changed from tree `a` to tree `b`."
  [a b]
  (let [ia (index a) ib (index b)
        removed (set/difference (set (keys ia)) (set (keys ib)))
        moved (set (for [[id {:keys [parent]}] ia
                         :when (and (contains? ib id) (not= parent (get-in ib [id :parent])))]
                     id))]
    {:semantic? (not= (flow/tree-hash a) (flow/tree-hash b))
     :added (set/difference (set (keys ib)) (set (keys ia)))
     :removed removed
     :moved moved
     :opts-changed (set (for [[id {:keys [opts]}] ia
                              :when (and (contains? ib id) (not= opts (get-in ib [id :opts])))]
                          id))
     :slot-sites (set (for [[slot f] slots :when (not= (f a ia) (f b ib))] slot))}))

(defn survey
  "`{:versions n :steps [...] :totals {...}}` over `versions`."
  [versions]
  (let [trees (mapv #(edn/read-string (:text %)) versions)
        steps (mapv (fn [[va vb] [a b]] (merge (select-keys vb [:rev :subject :at]) (step a b)))
                    (partition 2 1 versions) (partition 2 1 trees))]
    {:versions (count versions)
     :steps steps
     :totals {:steps (count steps)
              :semantic (count (filter :semantic? steps))
              :anchors-broken (count (filter #(seq (into (:removed %) (:moved %))) steps))
              :ext-after-tools (count (filter #(contains? (:slot-sites %) :react/ext-after-tools) steps))
              :ext-answer-check (count (filter #(contains? (:slot-sites %) :react/ext-answer-check) steps))
              :ids-at-first (count (index (first trees)))
              :ids-at-last (count (index (peek trees)))
              :first-ids-still-in-place (let [i0 (index (first trees)) il (index (peek trees))]
                                          (count (filter (fn [[id {:keys [parent]}]]
                                                           (and (contains? il id) (= parent (get-in il [id :parent]))))
                                                         i0)))}}))

(defn- with-empty-slots
  "Today's ReAct loop with the two slots as the empty `:sequence` they would
  expand to when nothing is attached."
  [tree]
  (let [slot (fn [id] [:sequence {:id id}])]
    (letfn [(walk [[k o & cs]]
              (into [k o]
                    (mapcat (fn [c]
                              (cond
                                (not (vector? c)) [c]
                                (= :react/tool-dispatch (:id (second c))) [(walk c) (slot :react/ext-after-tools)]
                                (= :react/answer-gate (:id (second c))) [(slot :react/ext-answer-check) (walk c)]
                                :else [(walk c)])))
                    cs))]
      (walk tree))))

(defn- time-ms [n f]
  (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (- (System/nanoTime) t0) 1e6 n)))

(defn cost
  "Expanding the session loop, which includes the ReAct loop: ms each, today
  and with the two empty slots, and the ReAct loop's hash before and after."
  []
  (let [react (agent/resolve-tree "react" nil)
        slotted (with-empty-slots react)
        session #(bt/expand (agent/resolve-tree "session" nil)
                            (fn [n] (if (str/includes? (str n) "react") % (agent/resolve-tree n nil))))
        n 200]
    {:expand-ms {:today (time-ms n #(session react)) :with-slots (time-ms n #(session slotted))}
     :hash {:today (flow/tree-hash react) :with-slots (flow/tree-hash slotted)}
     :nodes {:today (count (index react)) :with-slots (count (index slotted))}}))

(defn- print-series [label {:keys [versions steps totals]}]
  (println (str "\n" label ": " versions " versions, " (:steps totals) " steps"))
  (doseq [{:keys [rev subject semantic? added removed moved opts-changed slot-sites]} steps]
    (println (format "  %-10s %s  +%d -%d moved %d opts %d  %s  %s"
                     rev (if semantic? "semantic" "text    ") (count added) (count removed) (count moved)
                     (count opts-changed)
                     (if (seq slot-sites) (str/join "," (map name (sort slot-sites))) "-")
                     (subs (str subject) 0 (min 60 (count (str subject)))))))
  (println (str "  totals: " (pr-str totals))))

(defn -main [& dirs]
  (let [commits (distinct-texts (mapcat commit-versions dirs))
        releases (distinct-texts (mapcat release-versions dirs))]
    (print-series "every commit" (survey commits))
    (print-series "every release" (survey releases))
    (println "\ncost:" (pr-str (cost)))))
