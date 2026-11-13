;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-proposals
  "`loop/` proposals (RFC-071 \"Adoption\", byj-ng7.2): the fourth kind of
  self-improvement in `/proposals`, beside a skill, a user tool and predictor
  params. A candidate loop is staged with its evidence; a person accepts it,
  and it is the project's loop from the next session; a revert is a proposal
  too.

    <project>/.brainyard/loops/proposals/<id>/
      meta.edn     {:loop :action :status :created-ts :base}
      loop.edn     the tree to write (absent: a revert to the bundled loop)
      report.edn   the evaluation it won (byj-ng7.5), when there is one
      why.md       why it is proposed, in words
      replaced.edn once accepted: the project's loop it replaced, which a
                   revert puts back

  Staging refuses a candidate `flow check` refuses — a fn not registered, a
  tool not bound, a predictor not found, a broken memory chain — and one that
  names a config key of the permissions family anywhere in its opts: the
  gates run in `tools/call-tool` whatever the tree, and a loop must not try
  to set what they read. Accepting goes through `project-loops/write-loop!`,
  which writes the history line; nothing here is a tool, so no tool reaches
  an accept (RFC-021's rule). A proposal staged against a loop that has
  changed since — another accepted, byj upgraded — is refused at accept: its
  evidence was about the loop that was in force then.

  brainyard has no counterpart: its loops are code."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.bt :as bt]
            [brainyard.config :as config]
            [brainyard.flow :as flow]
            [brainyard.project-loops :as project-loops]))

(defn project-dir
  "The run's project dir, as every other store under `.brainyard/` reads it."
  [config]
  (str (or (:project-dir config) (:working-dir config) ".")))

(defn proposals-dir
  "`<project>/.brainyard/loops/proposals`."
  [config]
  (io/file (project-loops/loops-dir (project-dir config)) "proposals"))

(defn- read-edn [f] (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil))))

(defn- spit-edn [f x] (spit f (with-out-str (pp/pprint x))))

;; --- what a candidate may be -------------------------------------------------------

(def permission-keys
  "Every config key of the permissions family (RFC-013's `:family
  :permissions`), and `:permissions`, the file's section they sit in."
  (into #{:permissions} (for [[k {:keys [family]}] config/config-items :when (= :permissions family)] k)))

(defn permission-keys-in
  "The permissions-family keys `tree` names as a map key anywhere, sorted."
  [tree]
  (->> (tree-seq coll? seq tree)
       (filter map?)
       (mapcat keys)
       (filter permission-keys)
       distinct
       sort
       vec))

(defn- loader [config]
  (let [resolve-tree (requiring-resolve 'brainyard.agent/resolve-tree)
        pd (project-dir config)]
    #(resolve-tree % pd)))

(defn expand
  "`tree` with its `:include`s spliced, each found as the project would find
  it (`agent/resolve-tree`)."
  [config tree]
  (bt/expand tree (loader config)))

(defn check-opts
  "`flow check`'s options as `byj flow check` builds them: a ctx's registry,
  tools and config, with the session loop's own fns."
  [config]
  (let [ctx ((requiring-resolve 'brainyard.agent/make-ctx) {:config config})]
    (-> (flow/ctx-opts ctx)
        (update :registry merge @(requiring-resolve 'brainyard.session/registry)))))

(defn problems
  "Why `tree` cannot be staged, as `[{:node :problem}]`, or nil: not a tree, an
  include that does not expand, what `flow check` refuses, a permissions key.
  `opts` are `flow check`'s (`check-opts` when nil)."
  [config tree opts]
  (if-not (and (vector? tree) (keyword? (first tree)) (map? (second tree)))
    [{:node :tree :problem "a loop is a tree: [kind opts & children]"}]
    (let [ks (permission-keys-in tree)
          expanded (try (expand config tree) (catch Throwable e e))]
      (seq (concat
            (when (seq ks)
              [{:node :tree
                :problem (str "it names " (str/join ", " ks) " — a loop sets nothing of the permissions"
                              " family; the gates read the person's config, whatever the tree")}])
            (if (instance? Throwable expanded)
              [{:node :tree :problem (ex-message expanded)}]
              (flow/check expanded (or opts (check-opts config)))))))))

;; --- the loop in force -----------------------------------------------------------

(defn in-force
  "What the project runs for loop `ln` now: `{:from :project|:bundled :hash}`,
  the hash of the project's file, or of the bundled loop."
  [config ln]
  (let [pd (project-dir config)]
    (if-let [f (project-loops/project-loop-file pd ln)]
      {:from :project :hash (some-> (read-edn f) flow/tree-hash)}
      {:from :bundled :hash (project-loops/bundled-hash ln)})))

;; --- staging --------------------------------------------------------------------

(defn- new-id [dir ln]
  (let [ts (System/currentTimeMillis)]
    (first (remove #(.exists (io/file dir %))
                   (cons (str ln "-" ts) (map #(str ln "-" ts "-" %) (range 2 100)))))))

(defn stage!
  "Stage a loop proposal: `{:id :dir}`, or `{:error :problems}` when it may not
  be staged.

  `:loop`    the loop it is for — `react`, or `brainyard/loops/react.edn`
  `:tree`    the candidate; nil only with `:action :revert`, which puts the
             bundled loop back
  `:action`  `:accept` (default) or `:revert`
  `:report`  the evaluation it won, kept as `report.edn` (byj-ng7.5)
  `:why`     why it is proposed, in words
  `:check-opts` `flow check`'s options (`check-opts` when nil)

  Nothing a tool calls: a candidate is staged by `byj loops` and by a
  revert, and accepted by a person (`accept!`)."
  [config {:keys [tree report why action] :as opts}]
  (let [ln (project-loops/loop-name (:loop opts))
        action (or action :accept)]
    (cond
      (nil? ln) {:error (str "not a loop name: " (:loop opts) " — react, or brainyard/loops/react.edn")}
      (not (#{:accept :revert} action)) {:error (str "a loop proposal is an :accept or a :revert, not " (pr-str action))}
      (and (nil? tree) (not= :revert action)) {:error "a loop proposal needs a tree"}
      :else
      (if-let [ps (when tree (problems config tree (:check-opts opts)))]
        {:error (str "not staged: " (:problem (first ps))
                     (when (next ps) (str " (and " (dec (count ps)) " more)")))
         :problems (vec ps)}
        (let [dir (proposals-dir config)
              id (new-id dir ln)
              d (io/file dir id)]
          (.mkdirs d)
          (when tree (spit-edn (io/file d "loop.edn") tree))
          (when report (spit-edn (io/file d "report.edn") report))
          (when-not (str/blank? (str why)) (spit (io/file d "why.md") (str (str/trim (str why)) "\n")))
          (spit-edn (io/file d "meta.edn")
                    {:loop ln :action action :status "pending" :created-ts (System/currentTimeMillis)
                     :base (in-force config ln)})
          {:id id :dir (.getPath d)})))))

;; --- reading ------------------------------------------------------------------------

(defn- proposal [config id]
  (let [d (io/file (proposals-dir config) (str id))
        meta (read-edn (io/file d "meta.edn"))]
    (when (and meta (not (str/includes? (str id) "/")) (not (str/includes? (str id) "..")))
      (assoc meta :id (str id) :dir d
             :tree (read-edn (io/file d "loop.edn"))
             :report (read-edn (io/file d "report.edn"))
             :why (let [f (io/file d "why.md")] (when (.isFile f) (str/trim (slurp f))))))))

(defn- report-line [{:keys [verdict primary]}]
  (str/join " · " (remove nil? [(some-> verdict name) (some-> primary pr-str)])))

(defn summary
  "One line of what a proposal would do."
  [{:keys [loop action tree report base]}]
  (str (if (= :revert action)
         (str "revert " loop (if tree " to the loop before its last change" " to the bundled loop"))
         (str "a new " loop " loop"))
       " (in force: " (name (:from base)) " " (:hash base) ")"
       (when report (str " — " (report-line report)))))

(defn list-proposals
  "Pending loop proposals: `[{:name :summary :created-ts}]`."
  [config]
  (let [d (proposals-dir config)]
    (vec (for [f (sort-by #(.getName %) (or (seq (.listFiles d)) []))
               :when (.isDirectory f)
               :let [p (proposal config (.getName f))]
               :when (= "pending" (:status p))]
           {:name (:id p) :created-ts (:created-ts p) :summary (summary p)}))))

(defn- lcs-diff
  "Lines `a` against lines `b`: `[[:same|:del|:add line] …]`, by their longest
  common subsequence."
  [a b]
  (let [a (vec a) b (vec b) n (count a) m (count b)
        t (reduce (fn [t i]
                    (reduce (fn [t j]
                              (assoc-in t [i j]
                                        (if (= (a i) (b j))
                                          (inc (get-in t [(inc i) (inc j)] 0))
                                          (max (get-in t [(inc i) j] 0) (get-in t [i (inc j)] 0)))))
                            t (range (dec m) -1 -1)))
                  {} (range (dec n) -1 -1))]
    (loop [i 0 j 0 out []]
      (cond
        (and (< i n) (< j m) (= (a i) (b j))) (recur (inc i) (inc j) (conj out [:same (a i)]))
        (and (< j m) (or (>= i n) (>= (get-in t [i (inc j)] 0) (get-in t [(inc i) j] 0))))
        (recur i (inc j) (conj out [:add (b j)]))
        (< i n) (recur (inc i) j (conj out [:del (a i)]))
        :else out))))

(defn tree-diff
  "The expanded `before` against the expanded `after`, as lines of their
  pretty-printed forms: `- ` removed, `+ ` added, changed runs with two lines
  around them and `…` for what is the same between. \"(no change)\" when
  there is none."
  [before after]
  (let [lines #(str/split-lines (str/trimr (with-out-str (pp/pprint %))))
        d (lcs-diff (lines before) (lines after))
        near (set (for [[i [k]] (map-indexed vector d) :when (not= :same k)
                        j (range (- i 2) (+ i 3))]
                    j))]
    (if (every? #(= :same (first %)) d)
      "(no change)"
      (->> (map-indexed vector d)
           (partition-by (fn [[i _]] (contains? near i)))
           (mapcat (fn [run]
                     (if (contains? near (ffirst run))
                       (for [[_ [k l]] run] (str (case k :same "  " :del "- " :add "+ ") l))
                       ["  …"])))
           (str/join "\n")))))

(defn read-proposal
  "One proposal with its `:body`: what it does, why, the evidence, and the
  expanded tree's diff against the loop in force now."
  [config id]
  (when-let [p (proposal config id)]
    (let [now (try ((requiring-resolve 'brainyard.agent/load-tree) (:loop p) (project-dir config))
                   (catch Throwable e [:error {:problem (ex-message e)}]))
          after (if (:tree p)
                  (try (expand config (:tree p)) (catch Throwable e [:error {:problem (ex-message e)}]))
                  (try (expand config (project-loops/bundled-tree (:loop p)))
                       (catch Throwable e [:error {:problem (ex-message e)}])))]
      (assoc p :body (str (summary p)
                          (when (:why p) (str "\n\nWhy:\n" (:why p)))
                          (when (:report p) (str "\n\nEvidence (report.edn):\n"
                                                 (str/trimr (with-out-str (pp/pprint (:report p))))))
                          "\n\nThe loop, expanded, against the one in force now:\n"
                          (tree-diff now after)
                          "\n\nAccepted, it is in force from the next session.")))))

;; --- a person's decision -----------------------------------------------------------

(defn- set-status! [p status]
  (spit-edn (io/file (:dir p) "meta.edn")
            (assoc (select-keys p [:loop :action :created-ts :base]) :status status
                   :decided-ts (System/currentTimeMillis))))

(defn accept!
  "Make proposal `id` the project's loop — a person's only, at `/proposals
  accept`; no tool calls it. The project file is written with its
  `:based-on` and a history line (`project-loops/write-loop!`); the file it
  replaced is kept as the proposal's `replaced.edn`, which a revert puts
  back. Refused when the loop in force is not the one it was staged against.
  `{:said :in-force :next-session …the history line}` or `{:error}`."
  [config id]
  (let [p (proposal config id)]
    (cond
      (nil? p) {:error (str "no loop proposal named '" id "'")}
      (not= "pending" (:status p)) {:error (str "loop/" id " is " (:status p) ", not pending")}
      (not= (:hash (:base p)) (:hash (in-force config (:loop p))))
      {:error (str "the " (:loop p) " loop in force has changed since loop/" id " was proposed ("
                   (:hash (:base p)) " then, " (:hash (in-force config (:loop p))) " now) — its evidence"
                   " was about the loop then; reject it and propose it again")}
      :else
      (let [pd (project-dir config)
            old (project-loops/project-loop-file pd (:loop p))]
        (when old (spit (io/file (:dir p) "replaced.edn") (slurp old)))
        (let [line (project-loops/write-loop! pd (:loop p) (:tree p)
                                              {:action (:action p) :proposal (str "loop/" id)})]
          (set-status! p "accepted")
          (assoc line :in-force :next-session
                 :said (str (if (:tree p)
                              (str "the project's " (:loop p) " loop is now .brainyard/loops/" (:loop p) ".edn")
                              (str "the project's " (:loop p) " loop is removed; the bundled one is back"))
                            " (" (or (:hash line) "bundled") ", history.edn)")))))))

(defn reject!
  "Drop proposal `id`: its directory stays, marked rejected."
  [config id]
  (let [p (proposal config id)]
    (cond
      (nil? p) {:error (str "no loop proposal named '" id "'")}
      (not= "pending" (:status p)) {:error (str "loop/" id " is " (:status p) ", not pending")}
      :else (do (set-status! p "rejected") {:rejected (str "loop/" id)}))))

(defn stage-revert!
  "Stage the loop before the last change to loop `which` (react when nil) as
  a proposal of its own (`byj loops revert`): the file the last accept or
  revert replaced, kept as its proposal's `replaced.edn`, or the bundled loop
  when it replaced none. `{:id :dir}` or `{:error}`."
  [config which]
  (let [ln (project-loops/loop-name (or which "react"))
        pd (project-dir config)
        last-change (last (filter #(= ln (:loop %)) (project-loops/history pd)))
        from (some-> (:proposal last-change) (str/replace #"^loop/" ""))]
    (cond
      (nil? ln) {:error (str "not a loop name: " which)}
      (nil? last-change) {:error (str "nothing to revert: the " ln " loop has no change in .brainyard/loops/history.edn")}
      (and (:replaced last-change) (not (.isFile (io/file (proposals-dir config) (str from) "replaced.edn"))))
      {:error (str "the " ln " loop before its last change is not kept (no replaced.edn under loop/" from ")")}
      :else
      (let [before (when (:replaced last-change)
                     (read-edn (io/file (proposals-dir config) from "replaced.edn")))]
        (stage! config {:loop ln :tree before :action :revert
                        :why (str "Puts back the " ln " loop as it was before " (or (:proposal last-change) "its last change")
                                  " (" (name (:action last-change)) " at " (:at last-change) ").")})))))
