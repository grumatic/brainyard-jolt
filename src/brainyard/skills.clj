;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.skills
  "Skills (RFC-017) — brainyard's `agent/common/skills.clj`, loading and writing.

  A skill is a directory holding `SKILL.md`: a named procedure the model can
  follow, written by a human or another agent. The point is progressive
  disclosure — the prompt carries every skill's NAME and DESCRIPTION, and the
  body arrives only when the model calls `skill$<name>`. A body is 100+ lines;
  ten of them inlined would cost more than the tools listing.

  Roots, most local first (brainyard's `backend-rank`):

    <project-dir>/.brainyard/skills   :brainyard :project
    ~/.brainyard/skills               :brainyard :user
    ~/.claude/skills                  :claude
    ~/.agents/skills                  :agents
    resources/brainyard/skills        :builtin   (shipped in the binary)

  A name defined twice keeps the most local as `skill$<name>`; the others stay
  reachable as `skill$<backend>$<name>` rather than disappearing.

  A skill whose frontmatter says `dispatch: agent` runs in a child turn instead
  of being read into this one (RFC-018).

  Writing: `skills$write` (create, update, remove) and `skills$reload`,
  brainyard's, which the registry refreshes after so a skill written in a turn
  is callable in it. What an agent learns becomes a skill through
  `brainyard.self-improve`, staged for a person to accept."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agents :as agents]
            [brainyard.effect :as fx]))

;; --- roots ------------------------------------------------------------------------

(defn roots
  "Every skills root, most local first. A root that does not exist is skipped.

  `:skill-roots` replaces the defaults with an explicit list of directories —
  a shared team library, or one project's skills in a test. The user roots
  are under `$HOME`, or under the `brainyard.skills-home` property when it is
  set: the test runner points it at an empty directory of its own, so no test
  lists the person's own skills (byj-gly.467)."
  [config]
  (if-let [explicit (seq (:skill-roots config))]
    (->> explicit
         (map #(hash-map :dir (io/file %) :backend :brainyard :scope :project))
         (filter #(.isDirectory (:dir %)))
         vec)
    (let [home (or (System/getProperty "brainyard.skills-home") (System/getenv "HOME"))
          project (or (:project-dir config) (:working-dir config) ".")]
      (->> [{:dir (io/file project ".brainyard" "skills") :backend :brainyard :scope :project}
            ;; a skill written for another harness is still a procedure —
            ;; brainyard's reason for reading these at all, applied to the
            ;; project too (divergence `project-scope-harness-skills`)
            {:dir (io/file project ".claude" "skills") :backend :claude :scope :project}
            {:dir (io/file project ".agents" "skills") :backend :agents :scope :project}
            (when home {:dir (io/file home ".brainyard" "skills") :backend :brainyard :scope :user})
            (when home {:dir (io/file home ".claude" "skills") :backend :claude :scope :user})
            (when home {:dir (io/file home ".agents" "skills") :backend :agents :scope :user})]
           (remove nil?)
           (filter #(.isDirectory (:dir %)))
           vec))))

(defn- backend-label [{:keys [backend scope]}]
  (if (= :builtin backend)
    "builtin"
    (str (name backend) (when scope (str "/" (name scope))))))

;; --- parsing ----------------------------------------------------------------------

(defn parse-frontmatter
  "brainyard's hand-rolled YAML-ish reader: `key: value` lines between `---`
  fences. Anything it does not recognise is skipped rather than failing — a
  skill with odd frontmatter still loads, which is the point."
  [text]
  (if-not (str/starts-with? (str/triml text) "---")
    {:frontmatter {} :body text}
    (let [lines (str/split-lines text)
          [_ & rest-lines] lines
          end (first (keep-indexed (fn [i l] (when (= "---" (str/trim l)) i)) rest-lines))]
      (if (nil? end)
        ;; no closing fence: the whole file is the body, as brainyard does
        {:frontmatter {} :body text}
        {:frontmatter (into {}
                            (for [l (take end rest-lines)
                                  :let [[_ k v] (re-matches #"([A-Za-z0-9_.-]+):[ \t]*(.*)" (str/trim l))]
                                  :when k]
                              [(keyword (str/lower-case k)) (str/trim v)]))
         :body (str/join "\n" (drop (inc end) rest-lines))}))))

(defn parse-skill
  "A SKILL.md as {:name :title :description :tags :dispatch :content}.
  Nothing is required: the title falls back through frontmatter to the first
  heading to the directory name, and the description to the first real line."
  [dir-name text]
  (let [{:keys [frontmatter body]} (parse-frontmatter text)
        heading (second (re-find #"(?m)^#\s+(.+)$" body))
        first-line (first (remove #(or (str/blank? %) (str/starts-with? % "#"))
                                  (map str/trim (str/split-lines body))))]
    {:name dir-name
     :title (or (not-empty (:title frontmatter)) (not-empty (:name frontmatter))
                (some-> heading str/trim) dir-name)
     :description (or (not-empty (:description frontmatter)) first-line "")
     :tags (vec (remove str/blank? (map str/trim (str/split (or (:tags frontmatter) "") #","))))
     :version (:version frontmatter)
     :dispatch (some-> (:dispatch frontmatter) str/lower-case str/trim not-empty)
     :content text}))

(defn- read-dir
  "The skill in `d`, or nil when it holds no SKILL.md."
  [root ^java.io.File d]
  (let [f (io/file d "SKILL.md")]
    (when (.isFile f)
      (try
        (assoc (parse-skill (.getName d) (slurp f))
               :path (.getPath f)
               :backend (:backend root)
               :scope (:scope root)
               :source (backend-label root))
        (catch Throwable _ nil)))))

(def builtin-index
  "The index of the skills shipped in resources. An index, because a built
  binary cannot list a resource directory (as `agents/index.edn` and
  `tools/index.edn` are)."
  "brainyard/skills/index.edn")

(defn builtin-skills
  "The skills shipped in resources, in the index's order — this port's own
  root, last in precedence (divergence `builtin-skills`), as `agents`'s
  `builtin-agents` is for the roster. An index entry is a skill's name, or
  `{:name n :resources [\"file\" …]}` naming the files under its
  `resources/` that calling it hands over too, read through `io/resource`.
  A built-in skill carries no scripts: an embedded file is not on disk for
  bash to run (RFC-017). An entry whose SKILL.md is missing or unreadable is
  skipped, as a directory without one is."
  ([] (builtin-skills builtin-index))
  ([index-resource]
   (when-let [index (io/resource index-resource)]
     (let [base (subs index-resource 0 (inc (str/last-index-of index-resource "/")))]
       (vec (for [entry (edn/read-string (slurp index))
                  :let [{n :name rs :resources} (if (map? entry) entry {:name entry})
                        res (str base n "/SKILL.md")
                        url (when (string? n) (io/resource res))
                        s (when url (try (parse-skill n (slurp url)) (catch Throwable _ nil)))]
                  :when s]
              (cond-> (assoc s :path (str "builtin:" res) :backend :builtin :scope :builtin
                             :source "builtin")
                (seq rs) (assoc :resources (vec (for [r rs] (str base n "/resources/" r)))))))))))

(defn builtin-on?
  "Whether the built-in skills are listed: `:builtin-skills?`, default true."
  [config]
  (:builtin-skills? config true))

(defn list-skills
  "Every skill under every root, most local first, then the built-in ones.
  Re-read each call: a skill edited between turns takes effect without a
  reload, as in brainyard."
  [config]
  (vec (concat
        (for [root (roots config)
              d (sort-by #(.getName %) (.listFiles (:dir root)))
              :when (.isDirectory d)
              :let [s (read-dir root d)]
              :when s]
          s)
        (when (builtin-on? config) (builtin-skills)))))

(defn- builtin-resources
  "A built-in skill's resources, file name to text, from what its index
  entry names; one that is not there is left out."
  [{:keys [resources]}]
  (into {} (for [r resources
                 :let [url (io/resource r)]
                 :when url]
             [(subs r (inc (str/last-index-of r "/"))) (slurp url)])))

(defn resolve-names
  "Give each skill its tool id. The most local wins `skill$<name>`; a shadowed
  one keeps `skill$<backend>$<name>` so nothing silently disappears.

  One backend now spans two scopes — `.claude/skills` in the project and
  `~/.claude/skills` — so two shadowed skills can want the same id. The second
  takes `skill$<backend>$<scope>$<name>` rather than overwriting the first in
  the registry, which is how a skill would disappear silently."
  [skills]
  (let [by-name (group-by :name skills)]
    (vec (for [[_ dupes] by-name
               :let [taken (atom #{})]
               [i s] (map-indexed vector dupes)
               :let [id (if (zero? i)
                          (str "skill$" (:name s))
                          (let [base (str "skill$" (name (:backend s)) "$" (:name s))]
                            (if (contains? @taken base)
                              (str "skill$" (name (:backend s)) "$"
                                   (name (:scope s :user)) "$" (:name s))
                              base)))]]
           (do (swap! taken conj id)
               (assoc s :id id :shadowed? (pos? i)))))))

(defn find-skills
  "brainyard's ranking: a token in the name is worth most, then tags, title,
  description. Tokens under 3 characters are dropped, and a skill scoring
  under 10 is not a match — an empty result means 'you have no such skill'.

  One difference (divergence `skill-description-matches`, byj-gly.375): a
  description holding EVERY token scores 10, a match on its own. brainyard's
  flat 6 for any description hit can never reach 10, so a Claude Code or
  agents skill — name and description only — was never found by what it is
  for: on this machine's 51 skills, 10 of 20 such queries found nothing."
  ([config query] (find-skills config query 20))
  ([config query limit]
   (let [tokens (->> (str/split (str/lower-case (str query)) #"\s+")
                     (filter #(>= (count %) 3)))
         score-in (fn [text weights]
                    (let [t (str/lower-case (str text))]
                      (reduce + (for [tok tokens
                                      :let [[exact start any] weights]]
                                  (cond (= t tok) exact
                                        (str/starts-with? t tok) start
                                        (str/includes? t tok) any
                                        :else 0)))))]
     (when (seq tokens)
       ;; resolved, so a hit carries the tool id that calls it — as
       ;; skills$list's do (RFC-017); without it a model that had found a
       ;; skill had to guess its id (byj-gly.200)
       (->> (resolve-names (list-skills config))
            (map (fn [s]
                   (assoc s :score (+ (score-in (:name s) [100 40 25])
                                      (reduce + (map #(score-in % [20 12 8]) (:tags s)))
                                      (score-in (:title s) [30 15 10])
                                      (let [d (str/lower-case (str (:description s)))
                                            ;; every token starting one of its words — "test" is
                                            ;; in "testing" but not in "latest", "word" not in
                                            ;; "password" (byj-gly.375)
                                            words (remove str/blank? (str/split d #"[^\p{L}\p{N}]+"))]
                                        (cond (every? (fn [t] (some #(str/starts-with? % t) words)) tokens) 10
                                              (some #(str/includes? d %) tokens) 6
                                              :else 0))))))
            (filter #(>= (:score %) 10))
            (sort-by :score >)
            (take limit)
            vec)))))

;; --- the registry ------------------------------------------------------------------

(defn describe
  "What the model reads before it calls a skill: brainyard's phrasing, which
  says plainly what calling it does — load the procedure, or, for a skill that
  declares `dispatch: agent`, run it and hand back an answer."
  [{:keys [description name source dispatch]}]
  (if (= "agent" dispatch)
    (str (if (str/blank? description)
           (str "Run the `" name "` skill procedure in its own agent and return its answer.")
           (str description " — runs in its own agent; `question` is the task, and you get back its answer, not the procedure."))
         " (skill: `" name "` [" source "])")
    (str (if (str/blank? description)
           (str "Load the `" name "` skill procedure into your context, then follow it.")
           (str description " — loads this skill's procedure into your context to follow."))
         " (skill: `" name "` [" source "])")))

(defn listing-text
  "What the prompt's one line says of a skill (RFC-017): its title, when the
  title says something its name does not; else its description's first
  sentence, clipped to `max-chars`; else the title anyway. A Claude Code or
  agents SKILL.md has only `name` and `description`, so its title IS its name,
  and 51 of this machine's 55 skills were listed as `skill$x — x`: the model
  saw that something called x existed, not what it was for (byj-gly.336)."
  [{:keys [name title description]} max-chars]
  (let [norm #(str/replace (str/lower-case (str %)) #"[\s_-]+" "")
        d (str/trim (or (first (remove str/blank? (str/split-lines (str description)))) ""))
        sentence (or (second (re-find #"^(.+?[.!?])(\s|$)" d)) d)
        clipped (if (> (count sentence) max-chars) (str (subs sentence 0 (dec max-chars)) "…") sentence)]
    (cond
      (and (not (str/blank? (str title))) (not= (norm title) (norm name))) title
      (not (str/blank? clipped)) clipped
      :else (str title))))

(defn skill-agent-instruction
  "brainyard's `skill-system-prompt`, which it passes to skill-agent as
  `agent-context`. Here it IS the synthesized agent's instruction, which is the
  same place in the prompt (RFC-018 folds `:tool-context` into it)."
  [name content]
  (str "You are executing the skill `" name "`. "
       "Read its definition below and follow its instructions to answer "
       "the user's question. Be concise and accurate.\n\n"
       "## SKILL DEFINITION\n" content))

(defn- delegate
  "brainyard's `delegate-to-skill-agent`: run the skill in a fresh agent and
  return its ANSWER rather than its procedure.

  There is no registered skill-agent here to hold the wrapper prose — an agent
  is a spec (RFC-018), so the spec is built from the skill itself and thrown
  away with the turn."
  [{:keys [name content backend] :as skill} question ctx run-turn]
  (if (str/blank? (str question))
    {:error (str "skill `" name "` declares `dispatch: agent`, so it needs a question to run")
     :skill name}
    (let [spec {:name name
                :description (describe skill)
                :instruction (skill-agent-instruction name content)}
          r (agents/dispatch spec {:question question} ctx run-turn {:keep? false})]
      (if-let [problem (:error-message r)]
        {:error problem :skill name}
        {:skill name
         :type (clojure.core/name (or backend :unknown))
         :answer (:answer r)
         :iterations (:iterations r)
         :usage (:usage r)
         :dispatched true}))))

(defn skill-tool
  "A skill as a registry entry. Calling it returns the whole SKILL.md and the
  model follows it in its own iterations — nothing runs it.

  Unless its frontmatter says `dispatch: agent`, brainyard's opt-out for a
  procedure better executed in an isolated context than followed inline: then
  the call runs a child turn and returns its answer. `:dispatch` is read from
  the FRESH read, as brainyard does, so flipping the frontmatter takes effect
  on the next call rather than the next reload."
  [config {:keys [id name] :as skill} & [run-turn]]
  {:name id
   :kind :skill
   :description (describe skill)
   :input-schema [:map [:question {:optional true} [:string {:desc "What you intend to use the skill for. Optional, except for a skill that runs itself in an agent — then it is the task."}]]]
   :output-schema [:or {:desc ":content is the whole SKILL.md to follow; a skill that runs in an agent returns its :answer instead"}
             [:map [:skill :string] [:type :string] [:path :string] [:content :string]
              [:loaded :boolean] [:note {:optional true} :string]
              [:resources {:optional true} [:map-of :string :string]]]
             [:map [:skill :string] [:type :string] [:answer [:maybe :string]]
              [:iterations [:maybe :int]] [:usage :any] [:dispatched :boolean]]
             [:map [:error :string] [:skill {:optional true} :string]]]
   :effects #{:fs/read}
   :fn (fn [{:keys [question]} ctx]
         ;; re-read, so an edited skill takes effect without a reload — this
         ;; skill, not the most local of its name: skill$builtin$x is the
         ;; built-in x even when a project x shadows it
         (let [fresh (first (filter #(and (= (:name skill) (:name %))
                                          (= (:backend skill) (:backend %))
                                          (= (:scope skill) (:scope %)))
                                    (list-skills config)))]
           (cond
             (not fresh) {:error (str "skill not found: " name)}

             (and (= "agent" (:dispatch fresh)) run-turn
                  (:enable-subagent-calls (:config ctx) true))
             (delegate fresh question ctx run-turn)

             :else
             (cond-> {:skill name
                      :type (str (clojure.core/name (:backend fresh)))
                      :path (:path fresh)
                      :content (:content fresh)
                      :loaded true}
               (seq (:resources fresh)) (assoc :resources (builtin-resources fresh))
               (= "agent" (:dispatch fresh))
               (assoc :note (str "this skill declares `dispatch: agent`, but subagents are off"
                                 " — follow the procedure yourself"))))))})

(defn skills-and-tools
  "Every loaded skill and the registry entries for them. `run-turn` is passed
  through to the skills that run themselves in an agent (RFC-018)."
  [config & [run-turn]]
  (let [skills (resolve-names (list-skills config))]
    {:skills skills :tools (mapv #(skill-tool config % run-turn) skills)}))

;; --- the commands ------------------------------------------------------------------

(defn- summary
  "brainyard's skill summary — :name :title :description, the backend as :type
  and its :scope — plus the tool :id that calls it, which is this port's
  (divergence `skills-results-carry-their-tool-id`)."
  [s]
  (cond-> {:name (:name s) :title (:title s) :description (:description s)
           :type (name (:backend s)) :scope (some-> (:scope s) name) :id (:id s)}
    (:shadowed? s) (assoc :shadowed-by (str "skill$" (:name s)))
    (seq (:tags s)) (assoc :tags (:tags s))))

(defn- of-type
  "brainyard's :type and :scope filters: `brainyard`, `claude` or `agents`,
  and `project` or `user`; either left out is all of them. A built-in skill
  is `builtin` for both."
  [skills type scope]
  (cond->> skills
    (not (str/blank? (str type))) (filter #(= (str/lower-case (str/trim (str type))) (name (:backend %))))
    (not (str/blank? (str scope))) (filter #(= (str/lower-case (str/trim (str scope))) (some-> (:scope %) name)))))

(defn- collapse-duplicate-names
  "brainyard's: one hit per name — the most local — with the backends the
  same name is also installed in under :also-in."
  [hits]
  (let [others (group-by :name (filter :shadowed? hits))]
    (vec (for [h hits :when (not (:shadowed? h))
               :let [also (map backend-label (get others (:name h)))]]
           (cond-> h (seq also) (assoc :also-in (vec also)))))))

(def ^:private skill-entry
  [[:name :string] [:title :string] [:description :string]
   [:type [:enum "brainyard" "claude" "agents" "builtin"]] [:scope [:maybe :string]] [:id :string]
   [:shadowed-by {:optional true} :string]
   [:tags {:optional true} [:vector :string]]])

(def ^:private type-param
  [:type {:optional true} [:string {:desc "brainyard | claude | agents | builtin (omit for all)"}]])

(defn commands
  "brainyard's read subset: list, find and read, in brainyard's shapes
  (common/skills.clj). Writing a skill is skill-agent's job there, and is not
  ported."
  [config]
  [{:name "skills$list" :kind :command
    :description "List skills across types, most local first, the built-in ones last. Optional :type (brainyard|claude|agents|builtin) and :scope (project|user|builtin)."
    :input-schema [:map type-param
                        [:scope {:optional true} [:string {:desc "project | user | builtin (omit for all)"}]]]
    :output-schema [:map {:desc "most local first; a shadowed skill names the one that shadows it"}
              [:result [:vector (into [:map] (conj skill-entry [:path :string]))]]
              [:count :int]]
    :effects #{:fs/read}
    :fn (fn [{:keys [type scope]} _]
          (let [ss (of-type (resolve-names (list-skills config)) type scope)]
            {:result (mapv #(assoc (summary %) :path (:path %)) ss) :count (count ss)}))}
   {:name "skills$find"  :kind :command
    :description "Search INSTALLED skills by query; ranked, local-only. An empty result means there is no such skill — just carry on."
    :input-schema [:map [:query [:string {:desc "key nouns of the task"}]]
                        type-param
                        [:limit {:optional true} [:int {:desc "max matches (default 20)"}]]]
    :output-schema [:or {:desc "best first; one hit per name, the most local, with :also-in naming where else it is installed"}
              [:map [:result [:vector (into [:map] (conj skill-entry [:score :int]
                                                         [:also-in {:optional true} [:vector :string]]))]]
               [:count :int]]
              [:map [:error :string]]]
    :effects #{:fs/read}
    :fn (fn [{:keys [query type limit]} _]
          (if (str/blank? (str query))
            {:error "query is required"}
            (let [hits (->> (find-skills config query Integer/MAX_VALUE)
                            (#(of-type % type nil))
                            collapse-duplicate-names
                            (take (or limit 20)))]
              {:result (mapv #(cond-> (assoc (summary %) :score (:score %))
                                (:also-in %) (assoc :also-in (:also-in %)))
                             hits)
               :count (count hits)})))}
   {:name "skills$read" :kind :command
    :description "Read a skill's SKILL.md + metadata without committing to it. To use a skill, call it by name instead — that is what loads it."
    :input-schema [:map [:skill-name [:string {:desc "skill name"}]]
                        type-param
                        [:scope {:optional true} [:string {:desc "project | user | builtin"}]]]
    :output-schema [:or [:map [:name :string] [:title :string] [:description :string]
                   [:content :string] [:path :string]
                   [:type [:enum "brainyard" "claude" "agents" "builtin"]] [:scope [:maybe :string]] [:size :int]]
              [:map [:error :string]]]
    :effects #{:fs/read}
    :fn (fn [{:keys [skill-name type scope]} _]
          (if-let [s (first (filter #(= skill-name (:name %)) (of-type (list-skills config) type scope)))]
            (assoc (select-keys s [:name :title :description :content :path])
                   :type (name (:backend s)) :scope (some-> (:scope s) name)
                   :size (count (.getBytes (str (:content s)) "UTF-8")))
            {:error (str "no skill named " skill-name)}))}])

;; --- writing ----------------------------------------------------------------------

(defn sanitize-skill-name
  "brainyard's `sanitize-skill-name`: lower-case, only `[a-z0-9-]`, whitespace
  to `-`, a run of `-` collapsed and none at either end — a name is cleaned,
  not refused, so `  Deploy Helper! ` is `deploy-helper`. nil when nothing is
  left."
  [s]
  (-> (str/lower-case (str/trim (str s)))
      (str/replace #"[^a-z0-9\s-]" "")
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")
      not-empty))

(defn- coerce
  "brainyard's `coerce-type`/`coerce-scope`: a string or keyword as a keyword,
  blank as nil."
  [x]
  (some-> x name str/trim str/lower-case not-empty keyword))

(defn skills-dir
  "Where a skill is written: brainyard's own backend only, in the project
  (`<project-dir>/.brainyard/skills`) or the user's (`~/.brainyard/skills`).
  The other harnesses' roots are read, never written."
  [config scope]
  (if (= :user scope)
    (some-> (System/getenv "HOME") (io/file ".brainyard" "skills"))
    (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "skills")))

(defn- write-extras!
  "brainyard's `write-skill-extras!`: `scripts/<file>` (a `.sh` made
  executable) and `resources/<file>` beside SKILL.md. Only a file's own name
  is used, so an extra cannot be written outside its skill."
  [dir scripts resources]
  (doseq [[sub files] [["scripts" scripts] ["resources" resources]]
          [fname text] files
          :let [base (.getName (io/file (name fname)))
                f (io/file dir sub base)]
          :when (and (not (str/blank? base)) (not (#{"." ".."} base)))]
    (.mkdirs (.getParentFile f))
    (spit f (str text))
    (when (str/ends-with? base ".sh")
      (try (fx/sh {:out :string :err :string} "chmod" "+x" (.getPath f))
           (catch Throwable _ nil)))))

(defn- written
  "What a write returns: the skill as parsed from what was written — so the
  model sees the title and description it actually gave — with where it went."
  [skill-name content scope dir stamp]
  (merge (dissoc (parse-skill skill-name content) :content)
         {:type "brainyard" :scope (name scope) :path (.getPath dir)}
         stamp))

(defn- now [] (str (java.time.Instant/now)))

(defn builtin-skill
  "The built-in skill named `skill-name`, when the built-in ones are on."
  [config skill-name]
  (when (builtin-on? config)
    (first (filter #(= skill-name (:name %)) (builtin-skills)))))

(defn- builtin-refusal
  "Why a write refuses a built-in skill, and the one way to change it: a
  person copies it into the project, where it shadows the shipped one."
  [config op n]
  {:error (str op "-skill refuses `" n "`: it is a built-in skill, shipped with byj, and read-only."
               " To change it, copy its SKILL.md to "
               (.getPath (io/file (skills-dir config :project) n "SKILL.md"))
               " by hand, where it shadows the built-in one.")})

(defn- not-brainyard [op type]
  (when-let [t (coerce type)]
    (when (not= :brainyard t)
      {:error (str op "-skill only supports :brainyard type (got " (name t) ")."
                   (if (= :builtin t)
                     " A built-in skill is shipped with byj and read-only."
                     " A skill of another harness is read here, never written."))})))

(defn- find-own
  "The brainyard skill named `skill-name`: in `scope`, or the project's before
  the user's when no scope is given. nil when there is none."
  [config skill-name scope]
  (some (fn [s]
          (let [dir (some-> (skills-dir config s) (io/file skill-name))]
            (when (and dir (.isFile (io/file dir "SKILL.md"))) [s dir])))
        (if scope [scope] [:project :user])))

(defn create-skill!
  "brainyard's `brainyard-create`: write `content` — a whole SKILL.md,
  frontmatter included; nothing is generated for it — as a new skill, with any
  scripts and resources. Refuses a skill that already exists."
  [config {:keys [skill-name content type scope scripts resources]}]
  (let [n (sanitize-skill-name skill-name)
        scope (or (coerce scope) :project)]
    (or (not-brainyard "create" type)
        (cond
          (str/blank? (str skill-name)) {:error "skill-name is required"}
          (str/blank? (str content)) {:error "content is required"}
          (nil? n) {:error "Invalid skill name"}
          (not (#{:project :user} scope)) {:error (str "scope must be project or user (got " (name scope) ")")}
          ;; a same-named skill of the model's own would shadow a shipped
          ;; one silently; shadowing it is a person's choice (RFC-017)
          (and (builtin-skill config n)
               (not (some-> (skills-dir config scope) (io/file n "SKILL.md") .exists)))
          (builtin-refusal config "create" n)
          :else
          (let [root (skills-dir config scope)
                dir (some-> root (io/file n))]
            (cond
              (nil? dir) {:error "Cannot resolve brainyard skills directory — HOME is not set"}
              (.exists (io/file dir "SKILL.md"))
              {:error (str "Skill already exists: " n ". Use skills$write with op update to modify it.")}
              :else
              (do (.mkdirs dir)
                  (spit (io/file dir "SKILL.md") content)
                  (write-extras! dir scripts resources)
                  (written n content scope dir {:created (now)}))))))))

(defn update-skill!
  "brainyard's `brainyard-update`: replace a skill's SKILL.md when `content` is
  given, and write any scripts and resources. With no `scope`, the project's
  skill is found before the user's."
  [config {:keys [skill-name content type scope scripts resources]}]
  (let [n (sanitize-skill-name skill-name)]
    (or (not-brainyard "update" type)
        (if (nil? n)
          {:error "skill-name is required"}
          (if-let [[s dir] (when (not= :builtin (coerce scope)) (find-own config n (coerce scope)))]
            (do (when (some? content) (spit (io/file dir "SKILL.md") content))
                (write-extras! dir scripts resources)
                (written n (slurp (io/file dir "SKILL.md")) s dir {:updated (now)}))
            (if (builtin-skill config n)
              (builtin-refusal config "update" n)
              {:error (str "Brainyard skill not found: " n)}))))))

(defn remove-skill!
  "brainyard's `brainyard-remove`: delete a brainyard skill's directory. Only
  a direct child of a skills root is ever deleted."
  [config {:keys [skill-name type scope]}]
  (let [n (sanitize-skill-name skill-name)]
    (or (not-brainyard "remove" type)
        (if (nil? n)
          {:error "skill-name is required"}
          (if-let [[s dir] (when (not= :builtin (coerce scope)) (find-own config n (coerce scope)))]
            (let [root (.getCanonicalPath (skills-dir config s))]
              (if (= root (.getCanonicalPath (.getParentFile (io/file (.getCanonicalPath dir)))))
                (do (doseq [f (reverse (file-seq dir))] (.delete f))
                    {:deleted n :type "brainyard" :scope (name s)})
                {:error (str "refusing to delete " (.getPath dir) ": not a skill directory")}))
            (if (builtin-skill config n)
              (builtin-refusal config "remove" n)
              {:error (str "Brainyard skill not found: " n)}))))))

(defn refresh!
  "brainyard's `reload-skills!`: register every skill as it is on disk now in
  the registry atom `reg`, and drop the ones that are gone. Precedence is
  worked out again, so a new project skill that shadows a user one takes its
  `skill$<name>` and the other moves to `skill$<backend>$<name>`.

  The prompt's one-line listing is built with the session's other stable
  zones, so a skill written mid-session is callable at once but named there
  only from the next rebuild."
  [config reg & [run-turn]]
  (let [{:keys [skills tools]} (skills-and-tools config run-turn)
        before (set (keep (fn [[k v]] (when (= :skill (:kind v)) k)) @reg))
        after (set (map :name tools))]
    (swap! reg #(into (apply dissoc % before) (map (juxt :name identity)) tools))
    {:registered (vec (sort (remove before after)))
     :unregistered (vec (sort (remove after before)))
     :shadowed (vec (for [s skills :when (:shadowed? s)]
                      {:name (:name s) :backend (name (:backend s)) :id (:id s)}))
     :total (count tools)}))

(defn write!
  "brainyard's `skills$write`: one of `create`, `update` or `remove`, then the
  registry is refreshed so the change is callable in this turn — brainyard's
  skill-watch reloads at the end of the turn instead."
  [config reg run-turn {:keys [op] :as args}]
  (let [op (coerce op)
        r (case op
            nil {:error ":op is required (one of :create :update :remove)"}
            :create (create-skill! config args)
            :update (update-skill! config args)
            :remove (remove-skill! config args)
            {:error (str "Unknown :op '" (name op) "'. Valid: :create :update :remove")})]
    (if (or (:error r) (nil? reg))
      r
      (let [_ (refresh! config reg run-turn)
            canon #(.getCanonicalPath (io/file %))
            ;; the id THIS skill got, which is not skill$<name> when a more
            ;; local one of the same name shadows it
            id (when (:path r)
                 (some #(when (= (canon (:path r)) (canon (.getParent (io/file (:path %))))) (:id %))
                       (resolve-names (list-skills config))))]
        (cond-> r id (assoc :id id))))))

(defn run-npx!
  "`npx skills …` as brainyard's `run-cmd-sync` runs it: stdout and stderr
  together, ANSI stripped, bounded by `timeout-ms` — a process-task, so the
  timeout or a cancelled turn kills the tree (RFC-009). The arguments are
  spread, never a shell line. A non-zero exit is an error here, with what the
  CLI said; brainyard reports it as a result."
  [args timeout-ms]
  (try
    (let [r (fx/run-value!! (fx/timeout (apply fx/process-task {:in "" :out :string :err :out}
                                               ;; --yes: npx asks before fetching the package the
                                               ;; first time, and there is no one here to answer
                                               "npx" "--yes" "skills" args)
                                        timeout-ms ::timeout))]
      (if (= ::timeout r)
        {:error (str "npx skills " (first args) " timed out after " timeout-ms " ms")}
        (let [out (str/trim (str/replace (str (:out r)) #"\033\[[0-9;?]*[A-Za-z]" ""))]
          (if (zero? (:exit r))
            {:result out}
            {:error (str "npx skills " (first args) " exited " (:exit r) (when (seq out) (str ": " out)))}))))
    (catch Throwable e
      (if (fx/cancelled? e)
        (throw e)
        {:error (str "could not run npx skills: " (or (ex-message e) e))}))))

(def ^:private package-re
  ;; owner/repo or owner/repo@skill, and never an option npx would read
  #"[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?")

(def ^:private agent-re
  ;; an agent the CLI knows by name — claude-code, codex, cursor — or * for all
  #"\*|[a-z0-9][a-z0-9-]*")

(defn install!
  "brainyard's `install-skill`: `npx skills add <package> -g -y`, and the
  registry refreshed, so what it installed is callable at once as
  `skill$<name>`. The CLI keeps a global skill in `~/.agents/skills` and links
  it into the agents it finds; `agent` names one or more instead (`-a`,
  `claude-code`, `codex`, `*` for every one). brainyard's `--target
  claude|agents` is gone from the CLI (divergence `skills-cli-as-it-is`)."
  [config reg run-turn {:keys [package agent]}]
  (let [pkg (str/trim (str package))
        agents (->> (str/split (str agent) #"[\s,]+") (remove str/blank?) vec)]
    (cond
      (str/blank? pkg) {:error "package is required: owner/repo or owner/repo@skill"}
      (not (re-matches package-re pkg))
      {:error (str "not a skill package: " (pr-str package) " — owner/repo or owner/repo@skill")}
      (not-every? #(re-matches agent-re %) agents)
      {:error (str "not an agent name: " (pr-str agent) " — e.g. claude-code, codex, or * for all")}
      :else (let [r (run-npx! (concat ["add" pkg "-g" "-y"] (when (seq agents) (cons "-a" agents))) 30000)]
              (if (:error r)
                r
                (assoc r :registered (:registered (refresh! config reg run-turn))))))))

(defn sync!
  "brainyard's `sync-skills`: `npx skills update -g -y` — every global skill
  to its latest version — and the registry refreshed. The CLI updates by
  scope, not by agent."
  [config reg run-turn _]
  (let [r (run-npx! ["update" "-g" "-y"] 30000)]
    (when-not (:error r) (refresh! config reg run-turn))
    r))

(defn- found-packages
  "The packages in `npx skills find`'s text: `owner/repo@skill 383.8K installs`
  lines, as `{:package :installs}`, in the CLI's order — what `skills$install`
  takes, so the model need not copy it out of prose."
  [text]
  (vec (for [[_ pkg installs] (re-seq #"(?m)^\s*([A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9._-]+(?:@[A-Za-z0-9._-]+)?)(?:\s+([0-9.]+[KkMm]?) installs)?\s*$" (str text))]
             (cond-> {:package pkg} installs (assoc :installs installs)))))

(defn search!
  "brainyard's `search-marketplace` (`cli-find`): `npx skills find <query>` —
  what COULD be installed, where `skills$find` searches what is — 15 s. The
  CLI's text as brainyard returns it, with the packages read out of it
  (`found-packages`). One marketplace: brainyard's per-backend `--target`
  is no option of `find`, which read it as more of the query."
  [{:keys [query owner]}]
  (let [q (str/trim (str query))
        o (some-> owner str str/trim not-empty)]
    (cond
      (str/blank? q) {:error "query is required"}
      (and o (not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" o))) {:error (str "not a GitHub owner: " (pr-str owner))}
      :else (let [r (run-npx! (concat ["find" q] (when o ["--owner" o])) 15000)]
              (cond-> r (:result r) (assoc :packages (found-packages (:result r))))))))

(defn import!
  "brainyard's `skills$import`: an external SKILL.md — the agentskills.io open
  standard — from a local path, a file or the directory holding one, created
  as a brainyard skill. The name is the one given, else the frontmatter's,
  else the directory's; the frontmatter must carry a description, as the
  standard does. Only SKILL.md is taken, as brainyard takes it."
  [config reg run-turn {:keys [path name scope]}]
  (let [base (io/file (or (:working-dir config) "."))
        f (let [f (io/file (str path))] (if (.isAbsolute f) f (io/file base (str path))))
        md (cond (and (.isDirectory f) (.isFile (io/file f "SKILL.md"))) (io/file f "SKILL.md")
                 (.isFile f) f)]
    (cond
      (str/blank? (str path)) {:error "path is required (a SKILL.md file or a directory holding one)"}
      (nil? md) {:error (str "no SKILL.md at " path)}
      :else
      (let [content (slurp md)
            fm (:frontmatter (parse-frontmatter content))
            n (sanitize-skill-name (or (not-empty (some-> name str/trim))
                                       (not-empty (:name fm))
                                       (when (.isDirectory f) (.getName f))
                                       ""))]
        (cond
          (str/blank? n) {:error "could not tell the skill's name — pass name, or add a `name:` to the frontmatter"}
          (str/blank? (:description fm)) {:error "the SKILL.md has no description in its frontmatter — the open standard asks for name and description"}
          :else (write! config reg run-turn {:op "create" :skill-name n :content content
                                             :scope (if (str/blank? (str scope)) "project" scope)}))))))

(defn authoring-commands
  "The write half: brainyard binds it to its skill-agent alone; here it is the
  agent's own, gated by `:skill-authoring?` and by the permission layer like
  any other write."
  [config reg & [run-turn]]
  [{:name "skills$write" :kind :command
    :description (str "Mutate a skill on disk via :op — create (needs :skill-name + :content, the whole SKILL.md"
                      " with its frontmatter), update (needs :skill-name), or remove (needs :skill-name)."
                      " Writes brainyard skills only; the change is callable at once as skill$<name>.")
    :input-schema [:map
                   [:op [:enum "create" "update" "remove"]]
                   [:skill-name [:string {:desc "lowercase-kebab; other characters are dropped"}]]
                   [:content {:optional true} [:string {:desc "the whole SKILL.md: frontmatter (name, description) then the procedure"}]]
                   [:type {:optional true} [:string {:desc "brainyard (the only type written)"}]]
                   [:scope {:optional true} [:string {:desc "project (default for create) | user"}]]
                   [:scripts {:optional true} [:map-of :string :string]]
                   [:resources {:optional true} [:map-of :string :string]]]
    :output-schema [:or [:map [:name {:optional true} :string] [:title {:optional true} :string]
                         [:description {:optional true} :string] [:type :string] [:scope :string]
                         [:path {:optional true} :string] [:id {:optional true} :string]
                         [:created {:optional true} :string] [:updated {:optional true} :string]
                         [:deleted {:optional true} :string]]
                    [:map [:error :string]]]
    :effects #{:fs/write :tools/define}
    :fn (fn [args _] (write! config reg run-turn args))}
   {:name "skills$reload" :kind :command
    :description "Re-read every skill root and register what is there now — after writing a SKILL.md with write-file, say."
    :input-schema [:map]
    :output-schema [:map [:registered [:vector :string]] [:unregistered [:vector :string]]
                    [:shadowed [:vector :any]] [:total :int]]
    :effects #{:fs/read :tools/define}
    :fn (fn [_ _] (refresh! config reg run-turn))}
   {:name "skills$install" :kind :command
    :description "Install a skill package globally (npx skills add -g): 'owner/repo' or 'owner/repo@skill', as skills$search lists them. Callable at once as skill$<name>."
    :input-schema [:map [:package [:string {:desc "owner/repo or owner/repo@skill"}]]
                   [:agent {:optional true} [:string {:desc "the agents to link it into, e.g. claude-code or codex, * for all; omit for the ones the CLI finds"}]]]
    :output-schema [:or [:map [:result :string] [:registered [:vector :string]]]
                    [:map [:error :string]]]
    :effects #{:process :net/http :fs/write :tools/define}
    :fn (fn [args _] (install! config reg run-turn args))}
   {:name "skills$search" :kind :command
    :description (str "Search the skills marketplace (npx skills find) for packages you could INSTALL — not the"
                      " skills you have, which skills$find searches. Each result's :package goes to skills$install as it is."
                      " A few seconds.")
    :input-schema [:map [:query [:string {:desc "what the skill should do, e.g. typescript, pdf, deploy"}]]
                   [:owner {:optional true} [:string {:desc "only packages from this GitHub owner"}]]]
    :output-schema [:or [:map [:result :string] [:packages [:vector [:map [:package :string] [:installs {:optional true} :string]]]]]
                    [:map [:error :string]]]
    :effects #{:process :net/http}
    :fn (fn [args _] (search! args))}
   {:name "skills$import" :kind :command
    :description "Import an external SKILL.md (the agentskills.io open standard) from a local path — the file, or the directory holding it — as a brainyard skill. Its frontmatter needs a description."
    :input-schema [:map [:path [:string {:desc "a SKILL.md, or a directory holding one"}]]
                   [:name {:optional true} [:string {:desc "the skill's name; else the frontmatter's name, else the directory's"}]]
                   [:scope {:optional true} [:enum {:desc "project (default) or user"} "project" "user"]]]
    :output-schema [:or [:map [:name {:optional true} :string] [:title {:optional true} :string]
                         [:description {:optional true} :string] [:type :string] [:scope :string]
                         [:path {:optional true} :string] [:id {:optional true} :string]
                         [:created {:optional true} :string]]
                    [:map [:error :string]]]
    :effects #{:fs/read :fs/write :tools/define}
    :fn (fn [args _] (import! config reg run-turn args))}
   {:name "skills$sync" :kind :command
    :description "Update every globally installed skill to its latest version (npx skills update -g)."
    :input-schema [:map]
    :output-schema [:or [:map [:result :string]] [:map [:error :string]]]
    :effects #{:process :net/http :fs/write :tools/define}
    :fn (fn [args _] (sync! config reg run-turn args))}])
