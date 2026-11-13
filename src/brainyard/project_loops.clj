;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.project-loops
  "A project's own loops (RFC-071 \"Adoption\", byj-ng7.1): a tree at
  `<project>/.brainyard/loops/<name>.edn` that `agent/resolve-tree` finds
  before the bundled `brainyard/loops/<name>.edn` — for a bare name (`react`)
  and for the bundled resource path alike, since `session.edn` includes the
  turn's loop by that path.

  Mechanism only. The file is a tree like any other, whose root opts carry
  `:based-on {:loop :hash :byj}`: the bundled loop it was made from, by
  `flow/tree-hash`, and byj's version stamp (RFC-029) then. Writing one is
  `write-loop!`, which an accept and a revert call (byj-ng7.2) and nothing
  else: no tool reaches it, and resolving a loop never writes. Each write is a
  line in `.brainyard/loops/history.edn`.

  A project's attachments to a loop's slots (RFC-072) live beside its loops:
  `ext/attach.edn`, `{slot-id [name …]}`, and each tree attached as
  `ext/<name>.edn`, whose root opts carry `:based-on {:slot :contract :byj}`,
  the slot's contract it was made against. `attachments` reads them for
  `bt/expand`; `write-attachment!` changes them, for an accept and a revert,
  with a history line like a loop's.

  brainyard has no counterpart: its loops are code, so a project cannot hold
  one of its own."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.bt :as bt]
            [brainyard.flow :as flow]
            [brainyard.version :as version]))

(defn loop-name
  "The bundled loop `name` names, or nil: `react` for `react` and for
  `brainyard/loops/react.edn` alike. Any other resource path or a file path
  names no bundled loop."
  [name]
  (let [s (str name)]
    (or (re-matches #"[\w-]+" s)
        (second (re-matches #"brainyard/loops/([\w-]+)\.edn" s)))))

(defn bundled-resource
  "The classpath resource of bundled loop `ln`: `brainyard/loops/<ln>.edn`."
  [ln]
  (str "brainyard/loops/" ln ".edn"))

(defn loops-dir
  "`<project-dir>/.brainyard/loops`."
  [project-dir]
  (io/file (str project-dir) ".brainyard" "loops"))

(defn project-file
  "Where the project's own loop for `name` would be, whether or not it is
  there: `<project-dir>/.brainyard/loops/<loop>.edn` when `name` names a
  bundled loop's name (`loop-name`) and there is a project dir; else nil."
  [project-dir name]
  (when-let [l (and project-dir (not (str/blank? (str project-dir))) (loop-name name))]
    (io/file (loops-dir project-dir) (str l ".edn"))))

(defn project-loop-file
  "The project's own loop for `name`, when the file is there: what
  `agent/resolve-tree` reads ahead of the bundled loop. nil otherwise."
  [project-dir name]
  (let [f (project-file project-dir name)]
    (when (and f (.isFile f)) f)))

(defn bundled-tree
  "Bundled loop `ln` as data, or nil when byj bundles none of that name."
  [ln]
  (some-> (io/resource (bundled-resource ln)) slurp edn/read-string))

(defn bundled-hash
  "`flow/tree-hash` of bundled loop `ln` — of the tree as read, so a comment
  or a re-indent of the resource does not change it — or nil when byj bundles
  none of that name. The same hash `flow$status` shows for the loop."
  [ln]
  (some-> (bundled-tree ln) flow/tree-hash))

(defn based-on
  "What a project loop made now from bundled loop `ln` records:
  `{:loop :hash :byj}`."
  [ln]
  {:loop (str ln) :hash (bundled-hash ln) :byj (version/version)})

(defn tree-based-on
  "A tree's `:based-on`, from its root opts, or nil."
  [tree]
  (when (and (vector? tree) (map? (second tree)))
    (:based-on (second tree))))

(defn history-file
  "`<project-dir>/.brainyard/loops/history.edn`: a line per adoption and
  revert."
  [project-dir]
  (io/file (loops-dir project-dir) "history.edn"))

(defn history
  "Every line of the project's loop history, oldest first, skipping lines that
  do not read."
  [project-dir]
  (let [f (history-file project-dir)]
    (if (.isFile f)
      (vec (keep #(when-not (str/blank? %)
                    (try (edn/read-string %) (catch Throwable _ nil)))
                 (str/split-lines (slurp f))))
      [])))

(defn- read-hash [f]
  (when (.isFile f)
    (try (flow/tree-hash (edn/read-string (slurp f))) (catch Throwable _ nil))))

(defn write-loop!
  "Make `tree` the project's loop for `name` — or, `tree` nil, remove the
  project's loop so the bundled one is found again — and append one line to
  `.brainyard/loops/history.edn`. What an accept and a revert do (RFC-071,
  byj-ng7.2); no tool calls it.

  The written tree's root opts carry `:based-on`: `(:based-on opts)`, else the
  one `tree` already carries (a candidate made from an older bundled loop
  keeps saying so), else the bundled loop as it is now (`based-on`).

  opts: `:action` — `:accept` or `:revert` (required); `:proposal` — the id
  the change came from, recorded in the line.

  The line, which is also what this returns:
  `{:at :action :loop :hash :replaced :based-on :proposal}` — `:hash` of the
  tree written (nil: the override removed), `:replaced` of the file it
  replaced (nil: there was none, the bundled loop was in force)."
  [project-dir name tree {:keys [action proposal] :as opts}]
  (let [ln (or (loop-name name)
                 (throw (ex-info (str "not a loop name: " name " — react, or brainyard/loops/react.edn")
                                 {:name name})))
        _ (when-not (#{:accept :revert} action)
            (throw (ex-info (str "a loop is written by an accept or a revert, not " (pr-str action))
                            {:action action})))
        _ (when (and (some? tree) (not (and (vector? tree) (keyword? (first tree)) (map? (second tree)))))
            (throw (ex-info "a loop is a tree: [kind opts & children]" {:tree tree})))
        f (project-file project-dir ln)
        replaced (read-hash f)
        base (when tree (or (:based-on opts) (tree-based-on tree) (based-on ln)))
        written (when tree
                  (let [[k o & cs] tree] (into [k (assoc o :based-on base)] cs)))
        line (cond-> {:at (str (java.time.Instant/now))
                      :action action
                      :loop ln
                      :hash (some-> written flow/tree-hash)
                      :replaced replaced}
               base (assoc :based-on base)
               proposal (assoc :proposal proposal))]
    (.mkdirs (loops-dir project-dir))
    (if written
      (spit f (with-out-str (pp/pprint written)))
      (io/delete-file f true))
    (spit (history-file project-dir) (str (pr-str line) "\n") :append true)
    line))

;; --- what doctor says ----------------------------------------------------------

(defn overrides
  "Every loop the project holds of its own: `[{:loop :file :based-on :bundled-hash :stale?}]`,
  by name. `:bundled-hash` is nil for a loop byj bundles none of; `:stale?`
  says an override's `:based-on` hash is not the bundled loop's now — byj was
  upgraded since it was made, or it never said what it was made from."
  [project-dir]
  (let [d (loops-dir project-dir)]
    (vec (for [f (sort-by #(.getName %) (or (seq (.listFiles d)) []))
               :let [n (.getName f)]
               :when (and (.isFile f) (str/ends-with? n ".edn") (not= n "history.edn"))
               :let [ln (subs n 0 (- (count n) 4))]
               :when (loop-name ln)
               :let [b (try (tree-based-on (edn/read-string (slurp f))) (catch Throwable _ nil))
                     now (bundled-hash ln)]]
           {:loop ln :file f :based-on b :bundled-hash now
            :stale? (boolean (and now (not= (:hash b) now)))}))))

(defn- override-line [{ln :loop :keys [based-on bundled-hash stale?]}]
  (let [where (str ".brainyard/loops/" ln ".edn")
        base (when based-on (str (:hash based-on) (when-let [v (:byj based-on)] (str ", byj " v))))]
    (str "  " ln ": "
         (cond
           (nil? bundled-hash)
           (str "the project's " where " — a loop of its own; byj bundles none of that name")
           (and stale? based-on)
           (str "the project's " where " was made from an older bundled " ln " (" base
                "); the bundled one is now " bundled-hash
                " — evaluate it again against the bundled loop before keeping it: byj loops propose --bundled "
                ln " (RFC-071)")
           stale?
           (str "the project's " where " does not say which bundled " ln
                " it was made from (no :based-on) — evaluate it against the bundled loop ("
                bundled-hash ") before keeping it: byj loops propose --bundled " ln " (RFC-071)")
           :else
           (str "the project's " where ", made from the bundled " ln " as it is (" base ")")))))

(declare attachment-report attachment-line)

(defn report-text
  "doctor's `Loops:` section — every loop the project holds of its own, an
  override whose base no longer matches the bundled loop named as such, and
  every attachment to a slot, one whose slot changed named as such — or nil
  when the project holds none of either."
  [project-dir]
  (when project-dir
    (let [os (seq (overrides project-dir))
          as (seq (try (attachment-report project-dir
                                          (let [resolve #((requiring-resolve 'brainyard.agent/resolve-tree) % project-dir)]
                                            (bt/expand (resolve "react") resolve)))
                       (catch Throwable e [{:error (ex-message e)}])))]
      (when (or os as)
        (str/join "\n" (concat
                        (when os (cons "Loops (the project's own, found before the bundled ones):"
                                       (map override-line os)))
                        (when as (cons "Attachments (the project's, in the ReAct loop's slots, RFC-072):"
                                       (map #(if (:error %) (str "  " (:error %)) (attachment-line %)) as)))))))))

(defn source-line
  "What `--print-tree` says of where one loop of a tree was found
  (`agent/tree-sources`), as an EDN comment so the output still reads as EDN."
  [{:keys [name from path based-on]}]
  (str ";; " name " — "
       (case from
         :project (str "the project's " path
                       (if based-on
                         (str ", based on the bundled " (:loop based-on) " " (:hash based-on)
                              (when-let [v (:byj based-on)] (str " (byj " v ")")))
                         ", which does not say what it was based on"))
         :bundled (str "bundled, " path)
         :attachment (str "the project's " path ", attached to " (:slot based-on)
                          (when-let [v (:byj based-on)] (str " (byj " v ")")))
         :resource (str "the resource " path)
         :file (str "the file " path)
         (str path))))

;; --- attachments to a loop's slots (RFC-072) ----------------------------------------

(defn ext-dir
  "`<project-dir>/.brainyard/loops/ext`: the project's attachments."
  [project-dir]
  (io/file (loops-dir project-dir) "ext"))

(defn attach-file
  "`ext/attach.edn`: `{slot-id [name …]}`, each slot's attachments in order."
  [project-dir]
  (io/file (ext-dir project-dir) "attach.edn"))

(defn attachment-name
  "`name` when it may name an attachment — a word of letters, digits, `-`
  and `_` — else nil. It is a file's name under `ext/`."
  [name]
  (re-matches #"[\w-]+" (str name)))

(defn attachment-file
  "`ext/<name>.edn`, the tree attached as `name`."
  [project-dir name]
  (io/file (ext-dir project-dir) (str (or (attachment-name name)
                                          (throw (ex-info (str "not an attachment's name: " (pr-str name)) {:name name})))
                                      ".edn")))

(defn attached
  "The project's `ext/attach.edn`: `{slot-id [name …]}`, `{}` when there is
  none. Throws when it does not read as that, since every turn's loop is made
  from it."
  [project-dir]
  (let [f (attach-file project-dir)]
    (if-not (and project-dir (.isFile f))
      {}
      (let [m (try (edn/read-string (slurp f))
                   (catch Throwable e
                     (throw (ex-info (str ".brainyard/loops/ext/attach.edn does not read: " (ex-message e)) {:file (.getPath f)}))))]
        (when-not (and (map? m) (every? keyword? (keys m)) (every? #(and (vector? %) (every? attachment-name %)) (vals m)))
          (throw (ex-info ".brainyard/loops/ext/attach.edn is {slot-id [name …]}, each name a file under ext/" {:file (.getPath f)})))
        m))))

(defn attachments
  "The project's attachments as `bt/expand` takes them: `{slot-id [{:name
  :tree} …]}`, each tree read from `ext/<name>.edn` — `{}` with no project
  dir or nothing attached. Throws when an attached tree is missing or does
  not read: the loop a turn runs would not be the one accepted."
  [project-dir]
  (into {} (for [[slot names] (attached project-dir)]
             [slot (vec (for [n names
                              :let [f (attachment-file project-dir n)]]
                          {:name n
                           :tree (if (.isFile f)
                                   (try (edn/read-string (slurp f))
                                        (catch Throwable e
                                          (throw (ex-info (str ".brainyard/loops/ext/" n ".edn does not read: " (ex-message e))
                                                          {:file (.getPath f)}))))
                                   (throw (ex-info (str ".brainyard/loops/ext/attach.edn attaches " n " to " slot
                                                        ", and there is no .brainyard/loops/ext/" n ".edn")
                                                   {:file (.getPath f)})))}))])))

(defn slots
  "Every slot in the expanded `tree`, `{slot-id contract}`: what a project
  may attach to there."
  [tree]
  (into {} (for [n (tree-seq vector? #(filter vector? (drop 2 %)) tree)
                 :let [[_ o] n]
                 :when (and (map? o) (:slot o))]
             [(:id o) (:slot o)])))

(defn write-attachment!
  "Attach `tree` to `slot` as `name`, after what is attached there, or —
  `tree` nil — detach `name` from it, removing `ext/<name>.edn`; and append
  one line to `history.edn`. What an accept and a revert do (RFC-072); no
  tool calls it.

  opts: `:contract` — the slot's contract now, recorded in the tree's
  `:based-on`; `:loop` — the loop the slot is in (react); `:proposal`.

  The line, which is also what this returns: `{:at :action :loop :slot :name
  :hash :replaced :proposal}`, `:action` `:attach` or `:detach`, `:hash` of
  the tree written and `:replaced` of the one removed."
  [project-dir slot name tree {:keys [contract proposal] :as opts}]
  (let [f (attachment-file project-dir name)
        now (attached project-dir)
        names (get now slot [])
        replaced (read-hash f)
        written (when tree
                  (let [[k o & cs] tree]
                    (into [k (assoc o :based-on {:slot slot :contract contract :byj (version/version)})] cs)))
        attach (if tree
                 (assoc now slot (conj (vec (remove #{name} names)) name))
                 (let [left (vec (remove #{name} names))] (if (seq left) (assoc now slot left) (dissoc now slot))))
        line (cond-> {:at (str (java.time.Instant/now))
                      :action (if tree :attach :detach)
                      :loop (str (or (:loop opts) "react"))
                      :slot slot :name name
                      :hash (some-> written flow/tree-hash)
                      :replaced replaced}
               proposal (assoc :proposal proposal))]
    (when (and (nil? tree) (not (some #{name} names)))
      (throw (ex-info (str name " is not attached to " slot) {:slot slot :name name})))
    (.mkdirs (ext-dir project-dir))
    (if written
      (spit f (with-out-str (pp/pprint written)))
      (io/delete-file f true))
    (spit (attach-file project-dir) (with-out-str (pp/pprint attach)))
    (spit (history-file project-dir) (str (pr-str line) "\n") :append true)
    line))

(defn attachment-report
  "Every attachment the project holds, against the slots of `tree`, the
  loop it is attached into, expanded without attachments:
  `[{:slot :name :based-on :contract :stale?}]`. `:stale?` says the slot is
  gone or its contract is not the one the attachment was made against."
  [project-dir tree]
  (let [now (slots tree)]
    (vec (for [[slot names] (sort-by (comp str key) (attached project-dir))
               n names
               :let [f (attachment-file project-dir n)
                     b (try (tree-based-on (edn/read-string (slurp f))) (catch Throwable _ nil))
                     c (get now slot)]]
           {:slot slot :name n :based-on b :contract c
            :stale? (or (nil? c) (not= c (:contract b)))}))))

(defn attachment-line [{:keys [slot name contract stale? based-on]}]
  (str "  " name " → " slot ": "
       (cond
         (nil? contract) (str "the loop in force has no slot " slot " — the attachment runs nowhere")
         (and stale? based-on) (str "made against a slot that has changed since (byj " (:byj based-on)
                                    ") — evaluate it again: byj loops attach " slot " .brainyard/loops/ext/" name ".edn (RFC-072)")
         stale? (str "does not say which slot contract it was made against — evaluate it again (RFC-072)")
         :else (str "attached (.brainyard/loops/ext/" name ".edn)"))))
