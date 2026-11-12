;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.claude-sessions
  "This project's Claude Code sessions, as the agent page lists them beside
  brainyard's own (byj-gly.287.1): the ones running now and the ones saved.

  Read from what Claude Code leaves under `~/.claude` — not a published
  interface (seen: Claude Code 2.1.282), so everything here is read defensively:
  a file or a line that does not parse is skipped, a session whose title cannot
  be found is shown by its id, and nothing here throws.

  - saved: `~/.claude/projects/<project path, every non-alphanumeric → ->/<uuid>.jsonl`,
    one JSON object a line. Its title is the newest `ai-title` line, else its
    first real question (not a `<command-…>` or `<local-command-…>` line a slash
    command leaves), else its `last-prompt`. The files run to tens of MB, so
    only the head and the tail are read, and what was read is remembered by
    path and modification time.
  - live: `~/.claude/sessions/<pid>.json` — `{pid sessionId cwd name status
    kind …}` — whose process is alive and whose cwd is this project.

  Mechanism only: what the page shows, and how, is the renderer's (RFC-028)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.config :as config]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.worktree :as worktree]))

(def head-bytes
  "How much of a saved session's start is read for its first question."
  65536)

(def tail-bytes
  "How much of its end is read for its newest title: `ai-title` lines recur
  through a session, and the newest is usually far past its first 64 KB."
  131072)

(defn claude-home
  "`~/.claude`, or `home` when given (a test's fixture)."
  [home]
  (io/file (or home (str (System/getProperty "user.home") "/.claude"))))

(defn project-key
  "The directory name Claude Code keeps a project's sessions under: its
  canonical path with every character that is not a letter or a digit as `-`."
  [project-dir]
  (str/replace (str (or (config/canonical-dir project-dir) project-dir)) #"[^A-Za-z0-9]" "-"))

(defn- read-range
  "`n` bytes of `f` from `start`, as text (a partial first or last line is the
  caller's to drop)."
  [^java.io.File f start n]
  (with-open [in (java.io.FileInputStream. f)]
    (when (pos? start) (.skip in start))
    (let [b (byte-array n)]
      (loop [off 0]
        (let [r (if (< off n) (.read in b off (- n off)) -1)]
          (if (pos? r) (recur (+ off r)) (String. b 0 off "UTF-8")))))))

(defn- json-lines
  "The objects among `text`'s lines; a line that is not one — cut short by
  the read, or of a shape this does not know — is skipped."
  [text]
  (keep (fn [l] (when (str/starts-with? l "{")
                  (try (let [m (json/read-str l)] (when (map? m) m)) (catch Throwable _ nil))))
        (str/split-lines (str text))))

(defn unpasted
  "`text` without Claude Code's wrapper round a paste: a bracketed paste is
  recorded as `<pasted_content id=…>…</pasted_content id=…>`, and is the
  person's own prompt, not markup a slash command leaves (byj-gly.288.41)."
  [text]
  (some-> text
          (str/replace #"<pasted_content[^>]*>\n?" "")
          (str/replace #"\n?</pasted_content[^>]*>" "")))

(defn- message-text
  "A `user` line's words: its content as a string, or the text of its blocks,
  a paste unwrapped."
  [{:keys [message]}]
  (let [c (:content message)]
    (unpasted (cond (string? c) c
                    (sequential? c) (str/join " " (keep #(when (= "text" (:type %)) (:text %)) c))
                    :else nil))))

(def interrupted-text
  "What Claude Code writes as the person's line when a turn is stopped."
  "[Request interrupted by user")

(defn- question?
  "A line a person typed as a question — not what a slash command leaves
  (`<command-name>…`, `<local-command-caveat>…`), not a tool's result, not
  empty."
  [line]
  (let [t (some-> (message-text line) str/trim)]
    (and (= "user" (:type line)) (not (:isMeta line)) (not (:isSidechain line))
         ;; what Claude Code writes when it compacts, or when a turn is
         ;; stopped, is no one's question (eval-claude/prompt? says the same)
         (not (:isCompactSummary line))
         (seq t) (not (str/starts-with? t "<")) (not (str/starts-with? t interrupted-text)))))

(defn- one-line [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn read-saved
  "What the agent page says about the saved session in `f`: `{:id :title
  :updated-at :bytes}`. Its title is the newest `ai-title` (from the tail), else
  the first question (from the head), else the `last-prompt`, else nil."
  [^java.io.File f]
  (let [n (.length f)
        head (json-lines (read-range f 0 (min n head-bytes)))
        tail (if (> n head-bytes) (json-lines (read-range f (max 0 (- n tail-bytes)) (min n tail-bytes))) head)
        title (or (some :aiTitle (reverse (filter #(= "ai-title" (:type %)) (concat head tail))))
                  (some-> (first (filter question? head)) message-text)
                  (some :lastPrompt (reverse (filter #(= "last-prompt" (:type %)) (concat head tail)))))]
    {:id (str/replace (.getName f) #"\.jsonl$" "")
     :title (some-> title (one-line 120))
     :updated-at (.lastModified f)
     :bytes n}))

(def ended-reasons
  "A model reply's `stop_reason` that ends a turn; `tool_use` does not."
  #{"end_turn" "stop_sequence" "max_tokens" "refusal"})

(def first-read-bytes
  "How much of a transcript `last-turn` reads the first time it sees it — its
  end; after that, only what was added. A turn begun further back than this
  is not found until its next prompt."
  (* 8 1024 1024))

(defonce ^:private turn-cache
  ;; path → {:offset :prompt :answer :ended?}: where a transcript was read to,
  ;; and its newest turn as of there
  (atom {}))

(def ^:private turn-needles
  ["\"type\":\"user\"" "\"type\":\"assistant\""
   ;; Claude Code's own mark that a turn is over (byj-gly.290.10)
   "\"subtype\":\"turn_duration\""
   ;; the session went on under another id, in the same process (byj-gly.290.10)
   "\"type\":\"continued-in\""])

(defn- add-usage
  "A reply's tokens into the session's, by model — once a reply, though each of
  its blocks is a line repeating its `usage` (as `eval-claude` counts them)."
  [st l]
  (let [{:keys [id model usage]} (:message l)]
    (if (or (nil? usage) (nil? id) (contains? (:seen st) id) (= "<synthetic>" model))
      st
      (-> st
          (update :seen (fnil conj #{}) id)
          (update-in [:usage (str model)]
                     (fn [t] (merge-with + (or t {:fresh 0 :cache-read 0 :cache-write 0 :out 0})
                                         {:fresh (or (:input_tokens usage) 0)
                                          :cache-read (or (:cache_read_input_tokens usage) 0)
                                          :cache-write (or (:cache_creation_input_tokens usage) 0)
                                          :out (or (:output_tokens usage) 0)})))))))

(def activity-kept
  "How many of Claude Code's newest steps `last-turn` keeps in `:activity`."
  20)

(defn- brief [s n]
  (let [l (str/trim (or (first (remove str/blank? (str/split-lines (str s)))) ""))]
    (if (> (count l) n) (str (subs l 0 n) "…") l)))

(defn- step-line
  "One of a reply's blocks as a line of what Claude Code is doing — a tool
  call by its name and what it works on, text by its first line — or nil."
  [{:keys [type name input text]}]
  (case type
    "tool_use" (let [arg (or (:command input) (some-> (:file_path input) io/file .getName)
                             (:pattern input) (:description input) (:url input) (:query input))]
                 (str name (when arg (str "(" (brief arg 60) ")"))))
    "text" (when-not (str/blank? text) (str "\"" (brief text 80) "\""))
    nil))

(defn- add-activity
  "A reply line's blocks onto `:activity`, numbered so a reader can take only
  what is new (byj-gly.299)."
  [st l]
  (reduce (fn [st b]
            (if-let [line (step-line b)]
              (let [n (inc (:activity-n st 0))]
                (-> st (assoc :activity-n n)
                    (update :activity #(vec (take-last activity-kept (conj (vec %) {:n n :line line}))))))
              st))
          st
          (let [c (get-in l [:message :content])] (when (sequential? c) c))))

(defn- turn-step
  "Fold one transcript line into a turn's state."
  [st l]
  (cond
    (question? l) (assoc st :prompt (some-> (message-text l) str/trim) :answer nil :ended? false)
    (and (= "user" (:type l)) (some-> (message-text l) (str/includes? interrupted-text)))
    (assoc st :ended? true)
    ;; written once a turn is over, after its reply and stop hooks: a turn
    ;; that ends with no end_turn reply — a hook stopped it, a compaction
    ;; came last — still reads ended (byj-gly.290.10)
    (and (= "system" (:type l)) (= "turn_duration" (:subtype l)))
    (assoc st :ended? true)
    ;; Claude Code continues a session under a new id now and then, in the
    ;; running process, and writes the rest to <new id>.jsonl (byj-gly.290.10)
    (= "continued-in" (:type l))
    (cond-> st (:continuedInSessionId l) (assoc :continued-in (str (:continuedInSessionId l))))
    (and (= "assistant" (:type l)) (not (:isSidechain l)))
    (let [c (get-in l [:message :content])
          t (when (sequential? c) (not-empty (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) c))))]
      (cond-> (-> (assoc st :ended? (boolean (or (:isApiErrorMessage l)
                                                 (contains? ended-reasons (get-in l [:message :stop_reason])))))
                  (add-usage l)
                  (add-activity l))
        t (assoc :answer t)))
    :else st))

(defn- public [st] (cond-> (dissoc st :seen :activity-n) (empty? (:usage st)) (dissoc :usage)
                     (empty? (:activity st)) (dissoc :activity)))

(defn last-turn
  "Where the newest turn of transcript `f` stands: `{:prompt :answer
  :ended?}`, or nil when there is no file yet. `:prompt` is the newest
  question, `:answer` the newest text a reply gave after it, and `:ended?`
  says a reply ended the turn — or it was stopped, or failed — after it.
  `:usage`, when any reply carried one, is every token read so far by model,
  `{model {:fresh :cache-read :cache-write :out}}`, each reply counted once.

  agent$claude asks every few hundred ms, and a transcript runs to tens of MB
  with single lines of 170 KB (a `prompt_snapshot` written after a reply, so
  no fixed tail reaches the turn — measured live, byj-gly.290.2): so each file
  is read from where the last call stopped, complete lines only, and the first
  read is its last `first-read-bytes`. Claude Code writes each block of a reply
  as a line, all carrying the reply's `stop_reason`, the thinking first: so a
  turn can read ended a moment before its text is written (RFC-039).

  `:continued-in`, once the file says so, is the id Claude Code went on under:
  its later turns are in that id's transcript, not this one (byj-gly.290.10)."
  [^java.io.File f]
  (when (and f (.isFile f))
    (let [k (.getPath f)
          n (.length f)
          prev (get @turn-cache k)
          ;; a file shorter than where it was read to was rewritten
          prev (when (and prev (<= (:offset prev) n)) prev)
          start (or (:offset prev) (max 0 (- n first-read-bytes)))
          st (or (some-> prev (dissoc :offset)) {:prompt nil :answer nil :ended? false})]
      (if (= start n)
        (public st)
        (let [^bytes b (with-open [in (java.io.FileInputStream. f)]
                         (when (pos? start) (.skip in start))
                         (let [b (byte-array (- n start))]
                           (loop [off 0]
                             (let [r (if (< off (alength b)) (.read in b off (- (alength b) off)) -1)]
                               (if (pos? r) (recur (+ off r)) b)))))
              end (loop [i (dec (alength b))] (cond (neg? i) -1 (= 10 (aget b i)) i :else (recur (dec i))))]
          (if (neg? end)
            (public st)
            (let [text (String. b 0 (inc end) "UTF-8")
                  ;; the first read starts mid-line unless at the file's start
                  ls (cond->> (str/split-lines text) (and (nil? prev) (pos? start)) rest)
                  st (reduce turn-step st
                             (keep (fn [l] (when (and (str/starts-with? l "{") (some #(str/includes? l %) turn-needles))
                                             (try (let [m (json/read-str l)] (when (map? m) m)) (catch Throwable _ nil))))
                                   ls))]
              (swap! turn-cache assoc k (assoc st :offset (+ start end 1)))
              (public st))))))))

(defonce ^:private saved-cache
  ;; path → [modified bytes summary]: a session's file is tens of MB and the
  ;; page is drawn again and again; read it again only when it changed
  (atom {}))

(defn- summary [^java.io.File f]
  (let [k (.getPath f) m (.lastModified f) b (.length f)
        [m0 b0 s] (get @saved-cache k)]
    (if (and (= m m0) (= b b0))
      s
      (let [s (try (read-saved f)
                   (catch Throwable _ {:id (str/replace (.getName f) #"\.jsonl$" "") :updated-at m :bytes b}))]
        (swap! saved-cache assoc k [m b s])
        s))))

(defn- canonical [dir] (str (or (config/canonical-dir dir) dir)))

(defn saved
  "This project's saved Claude Code sessions, newest first. `dirs` are the
  directories its sessions may run in — the project and its worktrees, each
  its own directory under `projects/` — and a session from one that is not the
  project names it as `:dir` (byj-gly.290.2)."
  [project-dir & [home dirs]]
  (let [here (canonical project-dir)]
    (->> (or (seq dirs) [project-dir])
         (mapcat (fn [dir]
                   (let [d (canonical dir)]
                     (->> (or (.listFiles (io/file (claude-home home) "projects" (project-key dir))) [])
                          (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".jsonl")))
                          (map #(cond-> (summary %) (not= d here) (assoc :dir d)))))))
         (sort-by :updated-at >)
         vec)))

(defn live
  "This project's Claude Code sessions running now, from `sessions/<pid>.json`:
  `{:id :pid :title :status :kind :updated-at}`, and `:dir` for one running in
  another of `dirs` than the project (its worktrees). `alive?` (pid → bool) is
  `persist/alive?` unless a test gives its own."
  [project-dir & [home alive? dirs]]
  (let [alive? (or alive? persist/alive?)
        here (canonical project-dir)
        ours (into #{here} (map canonical) dirs)]
    (->> (or (.listFiles (io/file (claude-home home) "sessions")) [])
         (filter #(str/ends-with? (.getName ^java.io.File %) ".json"))
         (keep (fn [f] (try (let [m (json/read-str (slurp f))] (when (map? m) m)) (catch Throwable _ nil))))
         (filter #(and (:sessionId %) (:pid %) (:cwd %) (ours (canonical (:cwd %))) (alive? (:pid %))))
         (map (fn [{:keys [sessionId pid name status kind updatedAt cwd]}]
                (cond-> {:id sessionId :pid pid :title (some-> name (one-line 120)) :status status :kind kind
                         :updated-at updatedAt}
                  (not= here (canonical cwd)) (assoc :dir (canonical cwd)))))
         (sort-by :updated-at #(compare %2 %1))
         vec)))

(defn sessions
  "This project's Claude Code sessions — in it or in any of its git worktrees
  (`worktree/dirs`, unless `dirs` says) — as `{:live […] :saved […]}`, a session
  running now listed live and not saved again. A live one's title, when it has
  none of its own, is its saved file's."
  [project-dir & {:keys [home alive? dirs]}]
  (let [dirs (or dirs (worktree/dirs project-dir))
        lv (live project-dir home alive? dirs)
        sv (saved project-dir home dirs)
        by-id (into {} (map (juxt :id identity)) sv)
        live-ids (set (map :id lv))]
    {:live (mapv (fn [s] (cond-> s (nil? (:title s)) (assoc :title (:title (by-id (:id s)))))) lv)
     :saved (vec (remove #(live-ids (:id %)) sv))}))
