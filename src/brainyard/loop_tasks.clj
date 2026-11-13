;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-tasks
  "Tasks for a loop to be measured on, rebuilt from the project's own past
  turns (RFC-071 \"Tasks\", byj-ng7.3). Not in brainyard, which measures
  predictors on datasets (RFC-021) but never a loop on the project's work.

  A task is a question, the work tree it was asked in, and a check:

  - the question is the turn's trajectory record's `:question` (RFC-025);
  - the work tree is the parent of the turn's checkpoint commit,
    `refs/byj/turns/<session>/<turn>` (RFC-069) — the snapshot made as the
    turn began — saved by commit id and pinned as
    `refs/byj/tasks/<set>/<id>`, since the repository keeps only its newest
    `:changes-turns` and git's gc takes what no ref holds;
  - the check is what a machine repeats first — a command, in the shape of
    a dogfood stage's `:checks` (`sim/checks`, RFC-041) — and then what the
    person did: a turn rewound or undone is a failure, a turn whose change
    HEAD holds a success (`:outcome`, `:by`).

  Cases a person writes, `<set>.cases.edn` beside the set, are added. Each
  task's split is RFC-021's content hash of its question and start
  (`evaluate/split-of`), so adding a task never moves another and a held-out
  task stays held out. What could not be a task is counted, with why.

  Mechanism only (RFC-001): which tasks a loop is measured on, and what a
  good loop is, are the caller's."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.changes :as changes]
            [brainyard.effect :as fx]
            [brainyard.evaluate :as evaluate]
            [brainyard.trajectory :as trajectory]))

(def version 1)

(def default-set "default")

;; --- git ---------------------------------------------------------------------

(defn- git*
  "git `args` in `dir`: `{:exit :out :err}`, `:exit` nil when git could not
  be run."
  [dir & args]
  (try (let [r (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args)]
         {:exit (:exit r) :out (str (:out r)) :err (str (:err r))})
       (catch Throwable e {:exit nil :out "" :err (str (ex-message e))})))

(defn- git-line [dir & args]
  (let [{:keys [exit out]} (apply git* dir args)]
    (when (= 0 exit) (not-empty (str/trim out)))))

(defn- git-lines [dir & args]
  (let [{:keys [exit out]} (apply git* dir args)]
    (if (= 0 exit) (vec (remove str/blank? (str/split-lines out))) [])))

(defn- safe [s] (str/replace (str s) #"[^A-Za-z0-9._-]" "_"))

;; --- files -------------------------------------------------------------------

(defn tasks-dir
  "`<project>/.brainyard/loops/tasks`, where task sets are kept."
  [config]
  (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "loops" "tasks"))

(defn set-file
  "The file task set `set-name` is kept in."
  [config set-name]
  (io/file (tasks-dir config) (str (safe set-name) ".edn")))

(defn cases-file
  "The cases a person writes for task set `set-name`, beside it."
  [config set-name]
  (io/file (tasks-dir config) (str (safe set-name) ".cases.edn")))

(defn read-set
  "Task set `set-name` as written, or nil."
  [config set-name]
  (let [f (set-file config set-name)]
    (when (.isFile f)
      (try (let [m (edn/read-string (slurp f))] (when (map? m) m))
           (catch Throwable _ nil)))))

(defn- write-set!
  "`m` written as the set's file, a task a line, through a file renamed over
  it, so a reader never sees half a set."
  [f m]
  (.mkdirs (.getParentFile f))
  (let [tmp (io/file (.getParentFile f) (str "." (.getName f) "." (System/nanoTime) ".tmp"))
        head (dissoc m :tasks :dropped)
        body (str "{"
                  (str/join "\n " (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) head))
                  "\n :dropped {:counts " (pr-str (get-in m [:dropped :counts]))
                  "\n           :turns ["
                  (str/join "\n                   " (map pr-str (get-in m [:dropped :turns])))
                  "]}"
                  "\n :tasks\n ["
                  (str/join "\n  " (map pr-str (:tasks m)))
                  "]}\n")]
    (spit tmp body)
    (when-not (.renameTo tmp f)
      (spit f body)
      (.delete tmp))
    f))

;; --- a task --------------------------------------------------------------------

(defn- sha256-hex [^String s]
  (let [b (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) b))))

(defn example
  "Task `t` as RFC-021's example: its inputs, the question and the start, are
  what its content hash, its split and its id are of."
  [{:keys [question start]}]
  (evaluate/example {:question question :start start}))

(defn task-id
  "A stable 12-hex-char id of the task's own content — its question and its
  start — so the same question asked in the same work tree is one task."
  [t]
  (subs (sha256-hex (pr-str (into (sorted-map) (:inputs (example t))))) 0 12))

(defn check->checks
  "A command as a task's `:checks`, a dogfood stage's shape (`sim/checks`):
  `[{:type :command :name \"check\" :run cmd}]`, `[]` for none."
  [cmd]
  (if (str/blank? (str cmd)) [] [{:type :command :name "check" :run (str cmd)}]))

(defn- finish
  "`t` with its id and split."
  [t]
  (assoc t :id (task-id t) :split (evaluate/split-of (example t))))

;; --- what the person did -----------------------------------------------------

(defn- rewind-entries
  "Session `id`'s rewind refs (RFC-069) read back as what each did:
  `{:n :at :kind :turn :undoes}`, `:kind` `:rewind-start`, `:rewind-end`,
  `:undo-turn` or `:undo-rewind` — read off the snapshot's message, which
  `changes/restore!` writes as \"byj ID rewind to turn K's start\",
  \"… K's end\", \"byj ID undo turn K\" or \"byj ID undo rewind N\"."
  [root id]
  (vec (for [l (git-lines root "for-each-ref" "--format=%(refname)%09%(committerdate:unix)%09%(subject)"
                          (str "refs/byj/rewinds/" (safe id) "/"))
             :let [[ref at subject] (str/split l #"\t" 3)
                   n (some-> (re-find #"/(\d+)$" (str ref)) second parse-long)
                   [_ k se ut ur] (re-find #"^byj \S+ (?:rewind to turn (\d+)'s (start|end)|undo turn (\d+)|undo rewind (\d+))$" (str subject))]
             :when (and n (or k ut ur))]
         (cond-> {:n n :at (some-> at parse-long)}
           k (assoc :kind (if (= "start" se) :rewind-start :rewind-end) :turn (parse-long k))
           ut (assoc :kind :undo-turn :turn (parse-long ut))
           ur (assoc :kind :undo-rewind :undoes (parse-long ur))))))

(defn- in-force
  "The entries whose effect stands: not undone by a later undo that itself
  stands. An undo names an earlier number, so newest first decides it."
  [entries]
  (loop [es (sort-by (comp - :n) entries) standing []]
    (if-let [e (first es)]
      (recur (rest es) (if (some #(= (:n e) (:undoes %)) standing) standing (conj standing e)))
      standing)))

(defn rewound-turns
  "The turns of session `id` a person took back, and so failed, given its
  kept `rows`: a rewind to turn K's start takes back K and every turn ended
  by then, a rewind to K's end every turn after K ended by then, an undone
  turn that turn — unless the rewind was itself undone. `#{turn …}`."
  [root id rows]
  (set (for [{:keys [kind turn at]} (in-force (rewind-entries root id))
             :when (not= :undo-rewind kind)
             {t :turn end :at} rows
             :when (case kind
                     :undo-turn (= t turn)
                     :rewind-start (and (>= t turn) (<= (or end 0) (or at 0)))
                     :rewind-end (and (> t turn) (<= (or end 0) (or at 0))))]
         t)))

(defn committed?
  "Whether HEAD holds the change the turn made from `before` to `after`:
  git's three-way merge of that change onto HEAD (merge-tree, the turn's
  start the base) leaves HEAD's tree as it is. What came before the turn,
  and what HEAD holds besides, do not count; a change HEAD has since
  changed again on the same lines no longer counts."
  [root before after]
  (when-let [head-tree (git-line root "rev-parse" "--verify" "-q" "HEAD^{tree}")]
    (let [{:keys [exit out]} (git* root "merge-tree" "--write-tree" "--no-messages"
                                   (str "--merge-base=" before) "HEAD" after)]
      (and (= 0 exit) (= head-tree (first (str/split-lines out)))))))

(defn person-signal
  "What the person did with turn `row` of a session: `{:outcome :failure :by
  :rewound}`, `{:outcome :success :by :committed}`, `{:conflicting true}`
  when both, or nil — a turn that changed no files has neither, since there
  was nothing to take back or commit."
  [root rewound {:keys [turn before after files]}]
  (when (seq files)
    (let [r (contains? rewound turn)
          c (committed? root before after)]
      (cond (and r c) {:conflicting true}
            r {:outcome :failure :by :rewound}
            c {:outcome :success :by :committed}))))

;; --- turns and their records ---------------------------------------------------

(defn- first-line [s] (or (some-> s str str/split-lines first str/trim) ""))

(defn- clip [s] (let [s (first-line s)] (if (> (count s) 80) (str (subs s 0 79) "…") s)))

(defn- commit-time [root sha]
  (some-> (git-line root "log" "-1" "--format=%ct" sha) parse-long))

(def ^:private truncated
  ;; what `trajectory/trunc` leaves at the end of a question cut short
  #"…\[truncated \d+ chars\]$")

(defn- same-question?
  "Whether the ref's `subject`, the first line of the question the turn
  began with, is the first line of the record's `question` — of what is left
  of it, when the record cut it short."
  [subject question]
  (let [fl (first-line question)
        subject (str subject)]
    (or (= subject fl)
        (when-let [[_ kept] (re-find #"^(.*)…\[truncated \d+ chars\]$" fl)]
          (str/starts-with? subject kept)))))

(defn- match-records
  "Each kept turn of `rows` with its trajectory record of `records` (a
  session's own turns, oldest first): a record carries no turn number
  (RFC-025), so it is the one written while the turn ran — from its start
  snapshot to its end snapshot, a second's slack either side — whose
  question's first line is the ref's subject (when the ref has none, the
  latest written then). `{:pairs [[row record|nil] …] :unmatched [record
  …]}`."
  [root rows records]
  (loop [rows rows, left (vec (map-indexed vector records)), pairs []]
    (if-let [{:keys [before at subject] :as row} (first rows)]
      (let [from (* 1000 (dec (or (commit-time root before) 0)))
            to (* 1000 (+ 2 (or at 0)))
            in (filter (fn [[_ r]] (<= from (or (:ts r) 0) to)) left)
            pick (last (if (str/blank? (str subject))
                         in
                         (filter (fn [[_ r]] (same-question? subject (:question r))) in)))]
        (recur (rest rows)
               (if pick (vec (remove #(= (first pick) (first %)) left)) left)
               (conj pairs [row (second pick)])))
      {:pairs pairs :unmatched (mapv second left)})))

(defn- turn-sessions
  "Session ids with turns kept in the repository at `root`."
  [root]
  (->> (git-lines root "for-each-ref" "--format=%(refname)" "refs/byj/turns/")
       (keep #(second (re-find #"^refs/byj/turns/(.+)/\d+$" %)))
       distinct vec))

(defn turn-tasks
  "Tasks rebuilt from the kept turns of `sessions` (every session with a
  trajectory or a kept turn when nil), each with `checks` — the machine's,
  `[]` for none: `{:tasks [task …] :dropped [{:session :turn? :reason
  :question?} …]}`. A task is `{:id :question :start :checks :outcome? :by?
  :split :source :turn :from {:session :turn :ref :end}}`."
  [config root sessions checks]
  (let [ids (or (seq sessions) (distinct (concat (trajectory/sessions config) (turn-sessions root))))]
    (reduce
     (fn [acc id]
       (let [records (vec (remove :owner (trajectory/read-trajectories config id)))
             rows (changes/rows (assoc config :project-dir root) id)
             rewound (rewound-turns root id rows)
             {:keys [pairs unmatched]} (match-records root rows records)
             drop! (fn [acc m] (update acc :dropped conj (merge {:session id} m)))]
         (as-> acc acc
           (if (and (empty? records) (empty? rows))
             (drop! acc {:reason :no-session})
             acc)
           (reduce (fn [acc r] (drop! acc {:reason :not-kept :question (clip (:question r))})) acc unmatched)
           (reduce
            (fn [acc [{:keys [turn ref before after] :as row} rec]]
              (let [signal (person-signal root rewound row)
                    q (some-> (:question rec) str)]
                (cond
                  (nil? rec) (drop! acc {:turn turn :reason :no-record :question (clip (:subject row))})
                  (str/blank? q) (drop! acc {:turn turn :reason :no-question})
                  ;; the record cut it short (RFC-025): not the question asked
                  (re-find truncated q) (drop! acc {:turn turn :reason :truncated :question (clip q)})
                  (and (empty? checks) (:conflicting signal)) (drop! acc {:turn turn :reason :conflicting :question (clip q)})
                  (and (empty? checks) (not (:outcome signal))) (drop! acc {:turn turn :reason :no-check :question (clip q)})
                  :else (update acc :tasks conj
                                (finish (cond-> {:question q :start before :checks checks :source :turn
                                                 :from {:session id :turn turn :ref ref :end after}}
                                          (:outcome signal) (merge (select-keys signal [:outcome :by]))))))))
            acc pairs))))
     {:tasks [] :dropped []}
     ids)))

(defn person-cases
  "The cases a person wrote for set `set-name` (`cases-file`): a vector of
  `{:question :start? :checks? :check?}` — `:start` any revision, HEAD when
  none, saved as its commit id; `:check` a command, short for `:checks`; a
  case without checks of its own takes `checks`. `{:tasks :dropped}`."
  [config root set-name checks]
  (let [f (cases-file config set-name)
        cases (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ ::unreadable)))]
    (cond
      (nil? cases) {:tasks [] :dropped []}
      (not (sequential? cases)) {:tasks [] :dropped [{:case (.getName f) :reason :unreadable}]}
      :else
      (reduce
       (fn [acc [i {:keys [question start check] :as c}]]
         (let [sha (when (map? c) (git-line root "rev-parse" "--verify" "-q" (str (or start "HEAD") "^{commit}")))
               own (if (map? c) (vec (concat (:checks c) (check->checks check))) [])
               cs (if (seq own) own checks)
               drop! (fn [reason] (update acc :dropped conj {:case i :reason reason :question (clip question)}))]
           (cond
             (or (not (map? c)) (str/blank? (str question))) (drop! :no-question)
             (nil? sha) (drop! :start-unknown)
             (empty? cs) (drop! :no-check)
             :else (update acc :tasks conj (finish {:question (str question) :start sha :checks cs :source :person :case i})))))
       {:tasks [] :dropped []}
       (map-indexed vector cases)))))

;; --- the set -------------------------------------------------------------------

(defn- pin!
  "Each task's start kept from git's gc by a ref of the set's own."
  [root set-name tasks]
  (doseq [{:keys [id start]} tasks]
    (git* root "update-ref" (str "refs/byj/tasks/" (safe set-name) "/" id) start)))

(defn build!
  "Task set `name` (`default-set` when nil) built from the kept turns of
  `sessions` and the person's cases, and written to `set-file`: what the set
  held before stays — its starts are pinned, though the turns they came
  from may be gone — a task rebuilt replaces its old self, and the
  machine's check, `check` or the set's own, is every rebuilt task's.
  `{:file :set :tasks n :added n :splits {:train :val :test} :hash :dropped
  {reason n}}` or `{:error}`."
  [config {:keys [name sessions check]}]
  (let [set-name (or (not-empty (str name)) default-set)]
    (if-let [root (changes/root config)]
      (let [old (read-set config set-name)
            check (or (not-empty (str check)) (:check old))
            checks (check->checks check)
            {t1 :tasks d1 :dropped} (turn-tasks config root sessions checks)
            {t2 :tasks d2 :dropped} (person-cases config root set-name checks)
            ;; one task per question and start, the first found kept
            [fresh dups] (reduce (fn [[keep dup] t]
                                   (if (some #(= (:id t) (:id %)) keep)
                                     [keep (conj dup (cond-> {:reason :duplicate :question (clip (:question t))}
                                                       (:from t) (merge (select-keys (:from t) [:session :turn]))
                                                       (:case t) (assoc :case (:case t))))]
                                     [(conj keep t) dup]))
                                 [[] []] (concat t1 t2))
            old-ids (set (map :id (:tasks old)))
            fresh-ids (set (map :id fresh))
            tasks (->> (concat (remove #(fresh-ids (:id %)) (:tasks old)) fresh)
                       (map #(if (and (= :turn (:source %)) (seq checks)) (assoc % :checks checks) %))
                       (sort-by (juxt (comp str :split) :id))
                       vec)
            dropped (vec (concat d1 d2 dups))
            out {:v version
                 :set set-name
                 :built-at (System/currentTimeMillis)
                 :check check
                 :from (vec sessions)
                 :hash (evaluate/dataset-hash (map example tasks))
                 :splits (merge {:train 0 :val 0 :test 0} (frequencies (map :split tasks)))
                 :dropped {:counts (frequencies (map :reason dropped)) :turns dropped}
                 :tasks tasks}
            f (set-file config set-name)]
        (pin! root set-name tasks)
        (write-set! f out)
        {:file (.getPath f) :set set-name :tasks (count tasks)
         :added (count (remove old-ids fresh-ids))
         :splits (:splits out) :hash (:hash out) :check check
         :dropped (frequencies (map :reason dropped))})
      {:error (str (or (:project-dir config) (:working-dir config) ".")
                   " is not in a git work tree: a task's start is a turn's git snapshot (RFC-069)")})))

(defn summary-lines
  "What `build!` returned, as lines for a person."
  [{:keys [file tasks added splits dropped]}]
  (cond-> [(str "wrote " file ": " tasks " task" (when (not= 1 tasks) "s") " (" added " new) — "
                (:train splits 0) " train, " (:val splits 0) " val, " (:test splits 0) " test")]
    (seq dropped)
    (conj (str "dropped " (reduce + (vals dropped)) ": "
               (str/join ", " (for [[r n] (sort-by (comp - val) dropped)] (str n " " (clojure.core/name r))))))))
