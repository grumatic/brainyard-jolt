;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.memory
  "Memory (RFC-020) — durable knowledge the agent can look up, held OUTSIDE the
  port.

  brainyard's memory is a subsystem: SQLite with FTS5 and sqlite-vec, three
  layers, a bi-temporal context graph, communities, a capture sidecar, a
  consolidation reducer and six schema migrations. None of that is portable to
  jolt, and little of it is this layer's business. What an agent actually needs
  is two verbs — remember this, recall that — and those are a command away.

  So a memory store here is a COMMAND, named in config:

    :memory-recall-cmd  [\"bd\" \"memories\"]   query      → matches
    :memory-read-cmd    [\"bd\" \"recall\"]     key        → one entry
    :memory-store-cmd   [\"bd\" \"remember\"]   content    → stored

  `bd` is the default because a project using beads already has one, already
  populated; any program that takes a query and prints text works, and a test
  points these at a script. The store is absent unless its program is on PATH,
  and then the tools are simply not registered — the same rule the agent roster
  follows.

  **Nothing is injected into the prompt.** brainyard renders a `## Recalled
  Memory` block into every turn, up to `:recall-limit` × 600 characters, gated
  by keyword overlap when `:recall-mode` is conditional. Recall here is a tool
  the model calls, so memory costs the prompt one line until the moment it is
  worth more than that."
  (:require [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [ebb.core :as m]))

(def default-cmds
  {:memory-recall-cmd ["bd" "memories"]
   :memory-read-cmd ["bd" "recall"]
   :memory-store-cmd ["bd" "remember"]
   ;; a person's, not the model's: /memory lists and deletes (byj-gly.327)
   :memory-list-cmd ["bd" "memories" "--json"]
   :memory-forget-cmd ["bd" "forget"]})

(defn defaults-off?
  "Whether the default store is switched off for this process: the system
  property `brainyard.memory-store` is `none`. The test runner sets it, so a
  test that names no store of its own never reaches the developer's — bd walks
  up from a test's directory under `target/` to this repository's beads, and
  listing them cost a quarter of a second a session, 80 times a suite, and made
  a turn's memory hint depend on whose machine ran it (byj-gly.382). A test
  that is about memory names its own commands, which this leaves alone
  (provenance P-40)."
  []
  (= "none" (System/getProperty "brainyard.memory-store")))

(defn cmd
  "The command for `k`, or nil when it is switched off (an empty vector) — or
  when it is the shipped default and `defaults-off?`. Compared by value, since
  a loaded config carries the default from the schema (RFC-013)."
  [config k]
  (let [c (get config k (get default-cmds k))]
    (when (and (seq c) (not (and (defaults-off?) (= (vec c) (get default-cmds k)))))
      (vec c))))

(defn available?
  "Is the store's program on PATH? A missing store is not an error — it is a
  project that keeps no memory, and the tools stay unregistered rather than
  failing at the moment the model tries one."
  [config]
  (boolean
   (when-let [[program] (cmd config :memory-recall-cmd)]
     (try (zero? (:exit (fx/sh {:out :string :err :string} "sh" "-c"
                                    (str "command -v " program))))
          (catch Throwable _ false)))))

(defn- cmd-task
  "A Task of a store command run with `args`, capped: {:out …} or {:error …}.
  It fails only when cancelled, because a memory lookup failing is not a turn
  failing."
  [config argv timeout-ms max-chars]
  (m/sp
   (try
    (let [r (m/? (fx/timeout (apply fx/process-task
                                    ;; the SESSION's project, not wherever the process
                                    ;; started: a team member runs from the checkout
                                    ;; that holds the code, and its memories are its
                                    ;; project's (byj-gly.255)
                                    (cond-> {:out :string :err :out :in ""}
                                      (or (:project-dir config) (:working-dir config))
                                      (assoc :dir (str (or (:project-dir config) (:working-dir config)))))
                                    argv)
                             (or timeout-ms 15000)
                             ::timeout))]
      (cond
        (= ::timeout r) {:error (str "memory command timed out: " (str/join " " argv))}
        (zero? (:exit r)) {:out (let [s (str (:out r))]
                                  (if (> (count s) max-chars)
                                    (str (subs s 0 max-chars) "\n…[truncated]")
                                    s))}
        :else {:error (str/trim (str (:out r)))}))
    (catch Throwable e
      (if (fx/cancelled? e)
        (throw e)
        {:error (str "memory command failed: " (ex-message e))})))))

(defn- run-cmd
  "`cmd-task`, waited on: the store's verbs are plain calls to their callers."
  [config argv timeout-ms max-chars]
  (fx/run-value!! (cmd-task config argv timeout-ms max-chars)))

;; --- what this project has already been asked -----------------------------------------

(defn- ago [ms]
  (let [mins (quot (- (System/currentTimeMillis) (or ms 0)) 60000)]
    (cond (< mins 1) "just now"
          (< mins 60) (str mins "m ago")
          (< mins 1440) (str (quot mins 60) "h ago")
          :else (str (quot mins 1440) "d ago"))))

(defn- clip [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn search-history
  "Turns from EARLIER sessions of this project whose question or answer matches
  every token of `query` — brainyard's L2, which it keeps in SQLite and this
  port already has on disk: a session snapshot holds its turns (RFC-016), so the
  cross-session record of what was asked and answered was written the whole
  time. Nothing new is captured; what was missing was a way to read it.

  The current session is excluded, as brainyard's L2 recall excludes it: those
  turns are already in the history the model is looking at."
  [config query {:keys [limit sessions snippet]
                 :or {limit 5 sessions 200 snippet 500}}]
  (let [tokens (->> (str/split (str/lower-case (str/trim (str query))) #"\s+")
                    (filter #(>= (count %) 3)))
        hit? (fn [t] (let [hay (str/lower-case (str (:question t) " " (:answer t)))]
                       (every? #(str/includes? hay %) tokens)))
        current (str (:session-id config))]
    (when (seq tokens)
      (->> (persist/summaries config)
           (remove :problem)
           (remove #(= current (str (:id %))))
           (take sessions)
           (mapcat (fn [{:keys [id updated-at]}]
                     (let [snap (persist/read-session config id)]
                       (for [t (:previous-turns snap) :when (hit? t)]
                         {:session id :when (ago updated-at) :at (or updated-at 0)
                          :question (clip (:question t) 200)
                          :answer (clip (:answer t) snippet)}))))
           (sort-by :at >)
           (take limit)
           (mapv #(dissoc % :at))))))

;; --- what a person curates: /memory (byj-gly.327) -------------------------------------

(defn parse-listing
  "The list command's output as `[{:key :content}]`, by key. The contract is
  JSON: an object of key to content (`bd memories --json`), or an array of
  objects each with a `key` and a `value` or `content`. Anything else is nil."
  [s]
  (let [v (try (json/read-str (str s)) (catch Throwable _ ::bad))]
    (some->> (cond
               ;; read with keyword keys: a key holding a / would lose its half to `name`
               ;; a memory's content is text: bd's object carries a "schema_version": 1
               ;; beside them, which /memory listed as a memory (byj-gly.335)
               (map? v) (for [[k c] v :when (string? c)] {:key (if (keyword? k) (subs (str k) 1) (str k)) :content c})
               (sequential? v) (for [m v :when (map? m)
                                     :let [g #(or (get m %) (get m (keyword %)))]
                                     :when (some? (g "key"))]
                                 {:key (str (g "key")) :content (str (or (g "value") (g "content")))})
               :else nil)
             (sort-by :key)
             vec)))

(defn list-task
  "A Task of every memory the store holds, `{:memories [{:key :content}]}` or
  `{:error}` — the store's own listing, uncapped, since a person reads it
  in a picker or the left rail rather than a model in its context."
  [config]
  (if-let [c (cmd config :memory-list-cmd)]
    (m/sp
     (let [r (m/? (cmd-task config c (:memory-timeout-ms config 15000) Long/MAX_VALUE))]
       (cond
         (:error r) r
         :else (if-let [ms (parse-listing (:out r))]
                 {:memories ms}
                 {:error (str "the list command's output is not JSON of key to content: " (str/join " " c))}))))
    (fx/success {:error "no :memory-list-cmd — the store cannot be listed"})))

(defn list-memories
  "`list-task`, waited on: what `/memory` lists."
  [config]
  (fx/run-value!! (list-task config)))

(defn matching
  "The memories whose key or content holds every word of `query`, any case."
  [memories query]
  (let [words (remove str/blank? (str/split (str/lower-case (str/trim (str query))) #"\s+"))
        hay #(str/lower-case (str (:key %) " " (:content %)))]
    (filterv (fn [m] (every? #(str/includes? (hay m) %) words)) memories)))

;; --- which memories this question is about (byj-gly.335) -------------------------------

(def ^:private stopwords
  "Words that say nothing about what a question is about. Not the model's to
  read — a filter on what is matched, so it lives with the matcher."
  #{"the" "and" "for" "are" "but" "not" "you" "all" "any" "can" "had" "her" "was" "one" "our" "out"
    "has" "him" "his" "how" "its" "may" "new" "now" "own" "say" "she" "too" "use" "way" "who" "why"
    "did" "get" "got" "let" "put" "see" "set" "yes" "yet" "this" "that" "with" "from" "have" "what"
    "when" "where" "which" "will" "would" "could" "should" "there" "their" "them" "then" "than"
    "these" "those" "into" "about" "after" "before" "been" "being" "does" "doing" "done" "each"
    "just" "like" "make" "more" "most" "much" "must" "only" "other" "over" "same" "some" "such"
    "very" "also" "here" "want" "need" "please" "thing" "things" "something" "work" "works"
    "check" "look" "tell" "show" "know" "think" "it's" "i'm" "don't" "can't" "let's" "okay"})

(defn- stem
  "A word cut to what its forms share — retries and retry, tests and testing
  — crudely: enough for a lexical match, not a linguist's stemmer."
  [w]
  (let [cut (fn [w suf rep] (when (and (str/ends-with? w suf) (>= (- (count w) (count suf)) 3))
                              (str (subs w 0 (- (count w) (count suf))) rep)))]
    (or (cut w "ies" "y") (cut w "ing" "") (cut w "ed" "") (cut w "es" "") (cut w "s" "") w)))

(defn keywords
  "The words of `text` worth matching on: lower-case, three letters or more,
  no stopwords, each stemmed, distinct, in order."
  [text]
  (->> (re-seq #"[a-z0-9]+(?:'[a-z]+)?" (str/lower-case (str text)))
       ;; a possessive is its word: the TUI's is the TUI
       (map #(str/replace % #"'s$" ""))
       (remove #(or (< (count %) 3) (stopwords %)))
       (map stem)
       distinct
       vec))

(defn rank
  "The memories `question` is about, best first: `[{:key :content :matched
  :score}]`. A memory's words are its key's and its content's; a question
  word it shares counts for more the fewer memories share it (inverse
  document frequency), so `tmux` outweighs `test`. Kept only when it shares at
  least `min-terms` of the question's words — fewer when the question has
  fewer — scores at least `min-share` of the best match's score (so a word
  the best match shared with the question does not drag in every memory
  that mentions it), scores at least `min-score` in all — one ordinary word
  in common is not relevance — and is not in `exclude`; at most `max`. A
  question with no word worth matching matches nothing: nothing is better
  than everything. A word's rarity is reckoned as if the store held `prior`
  more memories without it, so a small store scores as a larger one would.
  What the thresholds buy is measured in RFC-042."
  [memories question {:keys [min-terms min-share min-score max exclude prior]
                      :or {min-terms 1 min-share 0 min-score 0 max 3 prior 16}}]
  (let [q (keywords question)
        docs (mapv (fn [m] (assoc m ::words (set (keywords (str (:key m) " " (:content m)))))) memories)
        n (count docs)
        df (frequencies (mapcat ::words docs))
        ;; `prior` memories that do not hold the word, besides the store's: a
        ;; store of one would make every word as common as can be and score
        ;; nothing, and a store of three barely more (byj-gly.335)
        idf (fn [w] (Math/log (/ (+ 1.0 n prior) (+ 1.0 (get df w 0)))))
        need (min min-terms (count q))
        excluded (set exclude)]
    (if (empty? q)
      []
      (->> docs
           (remove #(excluded (:key %)))
           (keep (fn [d] (let [hit (filterv (::words d) q)]
                           (when (and (seq hit) (>= (count hit) need))
                             (let [score (reduce + (map idf hit))]
                               (when (>= score min-score)
                                 (-> (dissoc d ::words)
                                     (assoc :matched hit :score score))))))))
           (sort-by (juxt (comp - :score) :key))
           ((fn [hits] (let [best (:score (first hits) 0)]
                         (filter #(>= (:score %) (* min-share best)) hits))))
           (take max)
           vec))))

(defn forget!
  "Delete the memory `key` with `:memory-forget-cmd`: `{:out}` or `{:error}`."
  [config key]
  (if-let [c (cmd config :memory-forget-cmd)]
    (run-cmd config (conj c (str key)) (:memory-timeout-ms config 15000) 4000)
    {:error "no :memory-forget-cmd — the store cannot delete"}))

(defn rewrite!
  "Store `content` under `key`, as `memory$remember` with a `:key` does — the
  store replaces what the key held: `{:out}` or `{:error}`."
  [config key content]
  (if-let [c (cmd config :memory-store-cmd)]
    (run-cmd config (conj c (str/trim (str content)) "--key" (str key)) (:memory-timeout-ms config 15000) 4000)
    {:error "no :memory-store-cmd — the store cannot write"}))

(defn commands
  "`memory$*`. `memory$history` needs nothing but this project's own snapshots;
  the other three need the store's command to exist."
  [config]
  (let [chars (:memory-max-chars config 4000)
        timeout (:memory-timeout-ms config 15000)]
    (remove
     nil?
     [{:name "memory$history" :kind :command
       :description (str "Search what this project was ASKED and ANSWERED in earlier sessions."
                         " Use it before re-deriving something that sounds like it has been worked out"
                         " before, or to find how a question was answered last time. This is a record of"
                         " turns, not of decisions — memory$recall is where the decisions are.")
       :input-schema [:map [:query [:string {:desc "Key nouns; every word of 3+ characters must appear."}]]
                           [:limit {:optional true} [:int {:desc "Turns to return (default 5)."}]]]
       :output-schema [:or {:desc "newest first; :when is how long ago, :question and :answer clipped"}
                 [:map [:turns [:vector [:map [:session :string] [:when :string]
                                         [:question :string] [:answer :string]]]]
                  [:count :int]]
                 [:map [:error :string]]]
       :effects #{:fs/read}
       :fn (fn [{:keys [query limit]} ctx]
             (if (str/blank? (str query))
               {:error "query is required"}
               (let [hits (search-history (:config ctx) query
                                          {:limit (or limit 5)
                                           :snippet (:memory-snippet-chars (:config ctx) 500)})]
                 {:turns (vec hits) :count (count hits)})))}

      (when-let [c (and (available? config) (cmd config :memory-recall-cmd))]
        {:name "memory$recall" :kind :command
         :description (str "Search durable memory — what was learned in earlier sessions and written down."
                           " Reach for it when a question sounds like it has been settled before, or when you are"
                           " about to assume something about this project you cannot see in front of you."
                           " Nothing is recalled automatically; this is the only way memory enters a turn.")
         :input-schema [:map [:query [:string {:desc (str "What you are looking for. How it matches is the store's business, not"
                                                    " this tool's: a single distinctive word usually finds more than a"
                                                    " phrase, so if a query comes back empty, try one word from it.")}]]]
         :output-schema [:or {:desc ":memories is the store's own output"}
                   [:map [:memories :string]]
                   [:map [:error :string]]]
         :effects #{:process}
         :fn (fn [{:keys [query]} ctx]
               (if (str/blank? (str query))
                 {:error "query is required"}
                 (let [r (run-cmd (:config ctx) (conj c (str/trim (str query))) timeout chars)]
                   (if (:error r) r {:memories (:out r)}))))})

      (when-let [c (cmd config :memory-read-cmd)]
        {:name "memory$read" :kind :command
         :description "Read one memory in full by its key, after memory$recall named it."
         :input-schema [:map [:key [:string {:desc "The key memory$recall reported."}]]]
         :output-schema [:or [:map [:key :string] [:memory :string]]
                   [:map [:error :string]]]
         :effects #{:process}
         :fn (fn [{:keys [key]} ctx]
               (if (str/blank? (str key))
                 {:error "key is required"}
                 (let [r (run-cmd (:config ctx) (conj c (str/trim (str key))) timeout chars)]
                   (if (:error r) r {:key key :memory (:out r)}))))})

      (when-let [c (cmd config :memory-store-cmd)]
        {:name "memory$remember" :kind :command
         :description (str "Write something down for a later session: a decision and why, a constraint that is not"
                           " in the code, a dead end worth not repeating. Not a summary of what you just did —"
                           " the transcript already has that, and a memory nobody needs is a memory in everyone's way.")
         :input-schema [:map [:content [:string {:desc "The insight, in a sentence or two, written for someone who was not here."}]]
                             [:key {:optional true} [:string {:desc (str "A slug naming its topic, as a person would search for it (deploy-freeze, staging-db-port);"
                                                                          " generated from the content otherwise. A later turn is told the key and the first line"
                                                                          " of a memory its question matches, so make the first line say the point.")}]]]
         :output-schema [:or {:desc ":stored is the store's own output"}
                   [:map [:stored :string]]
                   [:map [:error :string]]]
         :effects #{:process :fs/write}
         :fn (fn [{:keys [content key]} ctx]
               (if (str/blank? (str content))
                 {:error "content is required"}
                 (let [argv (cond-> (conj c (str/trim (str content)))
                              (not (str/blank? (str key))) (conj "--key" (str/trim (str key))))
                       r (run-cmd (:config ctx) argv timeout chars)]
                   ;; the session's kept listing is stale now (byj-gly.335)
                   (some-> (:memory-cache ctx) (reset! nil))
                   (if (:error r) r {:stored (:out r)}))))})])))
