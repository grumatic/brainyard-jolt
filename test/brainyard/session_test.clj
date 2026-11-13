;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.session-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.bt :as bt]
            [brainyard.changes :as changes]
            [brainyard.persist :as persist]
            [brainyard.project-loops :as project-loops]
            [brainyard.coach :as coach]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.schedule]
            [brainyard.agent-host :as ah]
            [brainyard.session :as session]
            [brainyard.terminal :as terminal]
            [brainyard.tasks :as tasks]
            [brainyard.todo :as todo]))

;; --- :scope / :include ------------------------------------------------------------

(deftest scope-isolates-memory
  (let [outer (atom {:input "q" :answer "outer answer"})
        seen (atom nil)
        ctx {:st-memory outer
             :registry {:inner (fn [{:keys [st-memory parent-memory]}]
                                 (reset! seen [@st-memory (:input @parent-memory)])
                                 (swap! st-memory assoc :answer "inner answer" :scratch 1))}}]
    (is (= :success (bt/tick ctx [:scope {:in {:question :input :missing :nope}
                                          :out {:result :answer :all :*}}
                                  [:action {:fn :inner}]])))
    (testing "inner memory is seeded only from :in"
      (is (= [{:question "q"} "q"] @seen)))
    (testing "only :out keys come back; the outer :answer is untouched"
      (is (= "outer answer" (:answer @outer)))
      (is (= "inner answer" (:result @outer)))
      (is (= {:question "q" :answer "inner answer" :scratch 1} (:all @outer)))
      (is (not (contains? @outer :scratch))))))

(deftest include-expansion
  (let [trees {"a" [:sequence {:id :a} [:include {:tree "b" :id :b-renamed}]]
               "b" [:action {:id :b :fn :x}]
               "loop" [:sequence {} [:include {:tree "loop"}]]}]
    (is (= [:sequence {:id :a} [:action {:id :b-renamed :fn :x}]]
           (bt/expand (trees "a") trees)))
    (is (thrown-with-msg? Exception #"include cycle"
                          (bt/expand (trees "loop") trees)))
    (testing "the bundled session loop nests the whole react loop"
      (let [tree (agent/load-tree session/session-loop)
            refs (bt/fn-refs tree)]
        (is (contains? refs :session/read-input))
        (is (contains? refs :react/think-act))
        (is (nil? (bt/validate tree (merge agent/registry session/registry))))))))

;; --- sessions ----------------------------------------------------------------------

(def ^:private no-codex
  "An empty Codex home. /usage and the footer read Codex's plan from it
  (RFC-049), and the person's own ~/.codex said 'Codex plan free' under /usage
  wherever this namespace ran outside the runner, which points every test at
  one of its own (byj-gly.443)."
  (.getCanonicalPath (doto (io/file "target/test-codex-home") .mkdirs)))

(defn- run-session
  "Drive a session with scripted input lines; `llm` answers every LLM call.
  A `:config` given is the session's, with no Codex home but `no-codex`
  unless it names one."
  [lines llm & {:as opts}]
  (let [queue (atom (vec lines))
        out (atom [])
        prompts (atom [])
        opts (cond-> opts (:config opts) (update :config #(merge {:codex-home no-codex} %)))
        final (session/run (merge {:lm {:provider :mock :format :mock
                                        :responses (fn [messages]
                                                     (swap! prompts conj messages)
                                                     (llm messages))}
                                   ;; a session persists (RFC-016), so it must not
                                   ;; persist into the repository it is testing
                                   :config {:working-dir "." :max-iterations 4
                                            :persist-session? false
                                            :codex-home no-codex}
                                   :read-input (fn [_] (let [[l] @queue]
                                                         (swap! queue #(vec (rest %)))
                                                         l))
                                   :emit (fn [kind text] (swap! out conj [kind text]))}
                                  opts))]
    {:final final :out @out :prompts @prompts :unread @queue}))

(defn- answer [text]
  (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer text})))

(defn- system-of [messages] (:content (first messages)))

(deftest a-colon-tool-is-called-directly-and-gated
  ;; byj-gly.273.3: brainyard's colon command — `:<tool> …` runs the tool
  ;; without the model, its arguments brainyard's kwargs or an EDN map, and the
  ;; next turn is told (console activity). Unlike brainyard's, it goes through
  ;; call-tool, so the permission gates and the hooks see it
  (let [dir (str "target/session-test/direct-" (System/nanoTime))
        _ (.mkdirs (java.io.File. dir))
        _ (spit (str dir "/a.txt") "one\ntwo TODO\n")
        shown (atom [])
        {:keys [out prompts final]}
        (run-session [(str ":grep :pattern TODO :path " dir " :max-results 5")
                      (str ":list-dir {:path \"" dir "\"}")
                      (str ":list-dir {:path \"" dir "\"}")
                      ":grep :max-results many" ":nosuch x" ":) hi" "and now?"]
                     (answer "ok")
                     :config {:working-dir "." :project-dir "." :max-iterations 3 :persist-session? false
                              :recap? false :console-activity-max-entries 5}
                     :present (fn [kind data] (when (= :tool-call kind) (swap! shown conj data) true)))
        said (mapv second out)
        system (fn [msgs] (:content (first msgs)))]
    (testing "a call runs, and the front end is handed it whole to draw as a tool block"
      (is (= ["grep" "list-dir" "list-dir"] (map :tool-name @shown)))
      (is (= {:pattern "TODO" :path dir :max-results 5} (:tool-args (first @shown))) "5 is an integer, by the schema")
      (is (str/includes? (:tool-result (first @shown)) "two TODO"))
      (is (number? (:elapsed-ms (first @shown))))
      (is (some #(= [:tool "grep"] [(first %) (:tool-name (second %))]) (:transcript final)) "and kept in the transcript"))
    (testing "an argument that does not check lists what the tool takes"
      (is (some #(and (str/starts-with? (str %) "Invalid args for :grep")
                      (str/includes? % "Expected inputs (pass as :key value pairs, or one EDN map):")
                      (str/includes? % "  :pattern string — ")) said)))
    (testing "an unknown tool says where to look; a line that is not a call is a question"
      (is (some #(= [:error "unknown tool :nosuch — /tools lists them"] %) out))
      (is (some #(str/includes? (str (:content (second %))) "question: :) hi") prompts)))
    (testing "the next turn is told what was run, a repeat once"
      (let [sys (system (first prompts))]
        (is (str/includes? sys "The person did these directly between turns"))
        (is (str/includes? sys (str "- :grep :pattern TODO :path " dir " :max-results 5 → {:matches")))
        (is (= 1 (count (re-seq #"- :list-dir" sys))) "the same line twice is told once"))))
  (testing "a denied tool is refused, and never runs"
    (let [dir (str "target/session-test/denied-" (System/nanoTime))
          _ (.mkdirs (java.io.File. dir))
          {:keys [out]} (run-session [(str ":write-file :path " dir "/x.txt :content hi")] (answer "ok")
                                     :config {:working-dir "." :project-dir "." :persist-session? false
                                              :tool-deny-tools ["write-file"]})]
      (is (some #(and (= :error (first %)) (str/starts-with? (str (second %)) "✗ write-file")) out))
      (is (not (.exists (java.io.File. (str dir "/x.txt")))))))
  (testing "in ask mode the person is asked, as for a model's call"
    (let [dir (str "target/session-test/ask-" (System/nanoTime))
          _ (.mkdirs (java.io.File. dir))
          asked (atom [])
          _ (run-session [(str ":write-file :path " dir "/x.txt :content hi")] (answer "ok")
                         :config {:working-dir "." :project-dir "." :persist-session? false
                                  :permission-mode :ask-each-time :tool-ask-effects #{:fs/write}}
                         :permission-fn (fn [req] (swap! asked conj req) {:denied true :reason "no"}))]
      (is (= ["write-file"] (map :tool @asked)))
      (is (not (.exists (java.io.File. (str dir "/x.txt")))) "and a no is a no"))))

(deftest the-renderer-is-handed-the-tools-to-complete
  ;; byj-gly.273.4: before it waits for a line, the session hands a front end
  ;; the tool rows — once, and again only when the registry changes
  (let [shown (atom [])]
    (run-session ["hello" "again"] (answer "ok")
                 :present (fn [kind data] (swap! shown conj [kind data]) true))
    (let [handed (filter #(= :tool-rows (first %)) @shown)]
      (is (= 1 (count handed)) "three waits for input, one hand-over: nothing changed")
      (is (some #(= "grep" (:id %)) (:rows (second (first handed))))))))

(deftest a-slash-tool-asks-the-model-to-call-it
  ;; byj-gly.273.2: brainyard's slash path — `/<tool> args` is a turn whose
  ;; question is "call directly as a tool: <tool> for <args>"; with no args and
  ;; a required one, the usage instead; a built-in command keeps its name
  (let [{:keys [out prompts final]} (run-session ["/grep TODO in src" "/list-tools" "/grep" "/help" "/nosuch x"]
                                                 (answer "done"))
        said (mapv second out)
        user-of (fn [msgs] (:content (second msgs)))]
    (testing "a tool with its arguments is asked for, in brainyard's words"
      (is (some #(str/includes? (user-of %) "question: call directly as a tool: grep for TODO in src") prompts))
      (is (some #(str/starts-with? (str %) "[grep] Recursively search files") said) "its [tool] line first"))
    (testing "one that needs none is asked for without them"
      (is (some #(str/includes? (user-of %) "question: call directly as a tool: list-tools") prompts)))
    (testing "two turns, and what they were asked is history"
      (is (= 2 (:turn-count final)))
      (is (= ["call directly as a tool: grep for TODO in src" "call directly as a tool: list-tools"]
             (map :question (:previous-turns final)))))
    (testing "a required argument not given: brainyard's usage, and no turn"
      (is (some #(and (str/starts-with? (str %) "Usage: /grep <args> or <question>")
                      (str/includes? % "  :pattern  — Extended regular expression")) said)))
    (testing "a built-in command keeps its name, and an unknown one says where to look"
      (is (some #(str/includes? (str %) "/tools") said))
      (is (some #(= [:error "unknown command /nosuch — /help lists them, /tools the tools"] %) out)))))

(deftest tools-lists-every-tool-and-shows-one
  ;; byj-gly.273.1: with a picker, /tools hands it the rows; without one it
  ;; lists what a filter matches, or prints one tool's spec
  (testing "a front end with a picker is handed every row, and the filter or the tool named"
    (let [shown (atom [])
          {:keys [out]} (run-session ["/tools" "/tools grep" "/tools file"] (answer "unused")
                                     :present (fn [kind data] (when-not (= :tool-rows kind) (swap! shown conj [kind data])) true))
          [[k0 d0] [_ d1] [_ d2]] @shown]
      (is (= :tools k0))
      (is (some #(= "grep" (:id %)) (:rows d0)))
      (is (= ["grep" nil] [(:select d1) (:query d1)]) "a tool's name opens on it")
      (is (= [nil "file"] [(:select d2) (:query d2)]) "anything else is the filter")
      (is (not-any? #(str/includes? (str (second %)) "Arguments:") out) "and nothing is printed")))
  (testing "without one, a name prints its spec, a filter lists by family"
    (let [{:keys [out]} (run-session ["/tools grep" "/tools file" "/tools zzqq" "/help"] (answer "unused"))
          said (mapv second (filter #(= :info (first %)) out))]
      (is (some #(and (str/starts-with? % "grep  (tool · built in)") (str/includes? % "Call directly:   :grep :pattern")) said))
      (is (some #(and (str/includes? % "_ungrouped (") (str/includes? % "  read-file — ")) said))
      (is (some #(str/starts-with? % "no tool matches \"zzqq\"") said))
      (is (some #(str/includes? % "/tools") (filter #(str/includes? % "/help") said)) "/help lists it"))))

(deftest a-command-that-throws-is-not-put-to-the-model
  ;; a failed command node fell through to an agent turn, and the model was
  ;; asked `/proposals accept …` as a question (provenance P-23)
  (with-redefs [session/proposals-report (fn [& _] (throw (ex-info "boom" {})))]
    (let [{:keys [out prompts final]} (run-session ["/proposals accept tool/x" "a question"] (answer "an answer"))]
      (is (= [[:error "/proposals failed: boom"] [:answer "an answer"]] out))
      (is (= 1 (count prompts)) "only the question reached the model")
      (is (= 1 (:turn-count final))))))

(deftest multi-turn-history-reaches-the-nested-loop
  (let [{:keys [final out prompts]} (run-session ["first question" "second question"]
                                                 (answer "an answer"))]
    (is (= 2 (:turn-count final)))
    (is (= [[:answer "an answer"] [:answer "an answer"]] out))
    (testing "turn 1 has no history; turn 2 sees turn 1 in history-context"
      (is (not (str/includes? (system-of (first prompts)) "## history-context")))
      (is (str/includes? (system-of (second prompts))
                         "## history-context\n[Turn 1 · full]\n  Q: first question\n  A: an answer")))
    (testing "each turn's react loop starts from a fresh memory"
      (is (str/includes? (:content (second (second prompts))) "iterations: []")))
    (is (= 2 (get-in final [:usage :llm-calls])))
    (is (= ["first question" "second question"] (map :question (:previous-turns final))))))

(defn- recap-or [llm]
  (fn [messages]
    (if (str/includes? (system-of messages) "You write the recap")
      (json/write-str {:recap "We read the notes." :next-step "Nothing is pending."
                       :conversation-title "Read The Notes, and summarise them please!"})
      (llm messages))))

(deftest a-long-turn-ends-with-a-recap
  (let [events (atom [])
        {:keys [final out prompts]}
        (run-session ["first question" "second question"] (recap-or (answer "an answer"))
                     :config {:working-dir "." :max-iterations 4 :persist-session? false
                              ;; every turn is long enough
                              :recap-after-ms 0}
                     :on-event (fn [e d] (swap! events conj [e d])))
        recaps (filter #(= :session.turn/recap (first %)) @events)
        recap-prompts (filter #(str/includes? (system-of %) "You write the recap") prompts)]
    (testing "each turn is recapped, after its answer, and says where things stand and what is next"
      (is (= 2 (count recaps)))
      (is (= {:turn 1 :recap "We read the notes." :next-step "Nothing is pending."}
             (select-keys (second (first recaps)) [:turn :recap :next-step]))))
    (testing "and names the conversation: lowercase, hyphens, five words at most"
      (is (= "read-the-notes-and-summarise" (:conversation-title (second (first recaps)))))
      (is (= "read-the-notes-and-summarise" (:conversation-title final))))
    (testing "the recap reads the session so far and the turn that just ended"
      (let [user (:content (second (second recap-prompts)))]
        (is (str/includes? user "Q: second question"))
        (is (str/includes? user "A: an answer"))
        (is (str/includes? user "[Turn 1 · full]") "the earlier turn, in history")))
    (testing "it costs like any call, and is counted like one"
      (is (= 4 (get-in final [:usage :llm-calls]))))
    (testing "the answers are what they were"
      (is (= [[:answer "an answer"] [:answer "an answer"]] out)))
    (testing "the turn's end says how long it took and when it ended"
      (let [[_ end] (first (filter #(= :session.turn/end (first %)) @events))]
        (is (nat-int? (:elapsed-ms end)))
        (is (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" (str (:ended-at end))))))))

(deftest a-conversation-title-is-a-short-name
  (is (= "fix-drag-performance-and-crashes" (session/conversation-title "Fix drag performance and crashes")))
  (is (= "fix-drag-performance-and-crashes" (session/conversation-title "fix_drag--performance: and crashes, then more words")))
  (is (= "한글-제목" (session/conversation-title "한글 제목")) "letters in any script")
  (is (nil? (session/conversation-title "  -- ")))
  (is (nil? (session/conversation-title nil))))

(deftest a-recap-is-earned-and-never-costs-a-turn
  (testing "a short turn is its own recap: none by default"
    ;; the default threshold is a minute, and a mock turn is milliseconds
    (let [{:keys [final]} (run-session ["q"] (recap-or (answer "a")))]
      (is (= 1 (get-in final [:usage :llm-calls])))))
  (testing ":recap? false turns it off whatever the length"
    (let [{:keys [final]} (run-session ["q"] (recap-or (answer "a"))
                                       :config {:working-dir "." :max-iterations 4 :persist-session? false
                                                :recap? false :recap-after-ms 0})]
      (is (= 1 (get-in final [:usage :llm-calls])))))
  (testing "a recap that fails is no recap — the session carries on"
    (let [{:keys [final out]} (run-session ["q" "r"]
                                           (fn [messages]
                                             (if (str/includes? (system-of messages) "You write the recap")
                                               (throw (ex-info "provider down" {}))
                                               ((answer "a") messages)))
                                           :config {:working-dir "." :max-iterations 4 :persist-session? false
                                                    :recap-after-ms 0})]
      (is (= 2 (:turn-count final)))
      (is (= [[:answer "a"] [:answer "a"]] out))
      (is (nil? (:recap final))))))

(deftest short-turns-are-recapped-every-nth-turn
  (let [recapped (fn [n & {:as config}]
                   (let [events (atom [])]
                     (run-session (mapv #(str "q" %) (range n)) (recap-or (answer "a"))
                                  :config (merge {:working-dir "." :max-iterations 4 :persist-session? false}
                                                 config)
                                  :on-event (fn [e d] (swap! events conj [e d])))
                     (mapv (comp :turn second) (filter #(= :session.turn/recap (first %)) @events))))]
    (testing "every third short turn by default"
      (is (= [3 6] (recapped 7))))
    (testing "every Nth turn, set in config"
      (is (= [2 4] (recapped 5 :recap-every-nth-turns 2))))
    (testing "a long turn is recapped whatever its number"
      (is (= [1 2 3 4] (recapped 4 :recap-after-ms 0))))
    (testing "0 leaves only the time trigger"
      (is (= [] (recapped 4 :recap-every-nth-turns 0))))))

(deftest config-is-a-command
  (let [{:keys [out]} (run-session ["/config" "/config provider" "/config max-iterations" "/config nope"]
                                   (answer "unused")
                                   :config {:working-dir "." :max-iterations 4 :persist-session? false
                                            :prompt-cache-key "sk-abcdefghijklmnopqrstuvwxyz0123456789"})
        said (into [nil nil nil] (map second (filter #(= :info (first %)) out)))]
    (testing "/config lists the families — brainyard's /feature — with what is on and what changed"
      (is (str/starts-with? (said 3) "settings by family"))
      (is (re-find #"\n  loop +7 settings · 1 changed" (said 3)))
      (is (re-find #"\n  session +\d+/\d+ on · " (said 3)) "a family with switches counts them"))
    (testing "/config FAMILY lists its settings, a secret-shaped value masked"
      ;; five since :default-lm (byj-gly.257)
      (is (str/starts-with? (said 4) "provider (5)"))
      (is (str/includes? (said 4) "default-lm"))
      (is (str/includes? (said 4) "● prompt-cache?"))
      (is (str/includes? (said 4) "•••• (secret-shaped)"))
      (is (not (str/includes? (said 4) "abcdefghijklmnop"))))
    (testing "/config KEY shows one in full"
      (is (str/starts-with? (said 5) "max-iterations = 4"))
      (is (str/includes? (said 5) "default 20"))
      (is (str/includes? (said 5) "family loop"))
      (is (str/includes? (said 5) "Iteration ceiling for one turn.")))
    (testing "and names what is neither"
      (is (str/includes? (said 6) "nope is not a config key or family")))))

(deftest compact-summarizes-the-earlier-turns
  (let [summarized (atom [])
        llm (fn [messages]
              (if (str/includes? (system-of messages) "You compress the earlier part of a coding session")
                (do (swap! summarized conj (:content (second messages)))
                    (json/write-str {:summary "We read the notes and fixed two tests."}))
                ((answer "an answer") messages)))
        {:keys [out final]} (run-session ["one" "two" "three" "four" "five" "/compact" "/compact 1" "/compact x"]
                                         llm
                                         :config {:working-dir "." :max-iterations 4 :persist-session? false
                                                  :full-depth 3})
        said (vec (map second (filter #(= :info (first %)) out)))]
    (testing "the turns before the last :full-depth become one summary turn"
      (is (str/starts-with? (said 0) "compacted 2 turns into one summary"))
      (is (str/includes? (first @summarized) "Q: one"))
      (is (str/includes? (first @summarized) "Q: two"))
      (is (not (str/includes? (first @summarized) "Q: three")) "the kept turns are not sent"))
    (testing "a number keeps fewer; the summary is folded into the next one"
      (is (str/starts-with? (said 1) "compacted 3 turns into one summary"))
      (is (str/includes? (second @summarized) "We read the notes") "the earlier summary went in"))
    (testing "what is left: one summary, then the kept turn, and the history the next turn reads"
      ;; turns 1–2 were already one summary, so folding it with three and four
      ;; covers turns 1–4 — not the 3 entries it replaced (byj-gly.232)
      (is (= ["(turns 1–4, compacted)" "five"] (mapv :question (:previous-turns final))))
      (is (= :compacted (:terminated-by (first (:previous-turns final)))))
      (is (str/includes? (:history-context final) "We read the notes and fixed two tests.")))
    (testing "and nothing to compact, or a bad number, says so"
      (is (str/starts-with? (said 2) "nothing to compact")))))

(deftest status-says-what-this-session-is
  (let [{:keys [out]} (run-session ["hello" "/status"] (answer "a")
                                   :config {:working-dir "." :max-iterations 4 :persist-session? false
                                            :session-id "s-42" :output-style :concise
                                            :config-sources {:output-style :project :max-iterations :caller}}
                                   :env-fn (fn [k] (when (= k "ANTHROPIC_API_KEY") "sk-ant-secret")))
        status (last (map second (filter #(= :info (first %)) out)))
        field (fn [label] (some #(when (str/starts-with? % (str label ":")) (str/trim (subs % (inc (count label)))))
                                (str/split-lines status)))]
    (is (re-find #"^v\d+\.\d+\.\d+" (field "Version")) "the build, RFC-029")
    (is (= "s-42" (field "Session ID")))
    (is (= "(not named yet — the first recap names it)" (field "Session name")))
    (is (= "inline" (field "Session kind")))
    (is (= "mock" (field "Model")))
    (is (= "none (the mock provider)" (field "Credentials")))
    (is (= "concise" (field "Output style")))
    (is (= "project (.brainyard/config.edn), command-line flags" (field "Setting sources")))
    (is (= "none configured" (field "MCP servers")))
    (is (str/starts-with? (field "Turns") "1 · 1 LLM calls"))
    (is (str/starts-with? (field "Runtime") "jolt "))
    (is (apply = (map #(str/index-of % (field (first (str/split % #":")))) (remove str/blank? (str/split-lines status))))
        "the values line up in one column"))
  (testing "a provider's credentials are named, never shown"
    (let [cred #'session/credential-source
          env {"ANTHROPIC_API_KEY" "sk-ant-secret"}]
      (is (= "ANTHROPIC_API_KEY is set" (cred {:provider :anthropic} env)))
      (is (= "OPENAI_API_KEY is NOT set" (cred {:provider :openai} env)))
      (is (= "the claude CLI's own login" (cred {:provider :claude-code} env)))
      (is (not (str/includes? (cred {:provider :anthropic :api-key "sk-ant-secret"} env) "sk-ant"))))))

(deftest output-style-sets-how-much-is-shown
  (testing "each style is a display level, and -v outranks it"
    (is (= [:normal :quiet :debug :verbose]
           [(config/display-level {:output-style :default} false)
            (config/display-level {:output-style :concise} false)
            (config/display-level {:output-style :explanatory} false)
            (config/display-level {:output-style :concise} true)])))
  (testing "it takes only the styles it names"
    (is (config/valid? :output-style :concise))
    (is (not (config/valid? :output-style :loud))))
  (testing "an edit is in force for every viewer from the next command or turn"
    (let [base {:working-dir "." :max-iterations 4 :persist-session? false :output-style :default}
          file (atom base)
          box (atom :normal)]
      (run-session ["/help" "EDIT" "/help"] (answer "a")
                   :config base :config-loaded base :display-box box
                   :reload-config (fn [] {:config @file})
                   :read-input (let [in (atom ["/help" "EDIT" "/help"])]
                                 (fn [_] (let [[x] (swap-vals! in #(vec (rest %)))
                                               l (first x)]
                                           (if (= "EDIT" l)
                                             (do (reset! file (assoc base :output-style :concise)) "/help")
                                             l)))))
      (is (= :quiet @box)))))

(deftest the-transcript-keeps-its-configured-window
  (let [{:keys [final]} (run-session ["one" "two" "three"] (answer "an answer")
                                     :config {:working-dir "." :max-iterations 4 :persist-session? false
                                              :transcript-max-lines 3})]
    (is (= 3 (count (:transcript final))) "the newest three lines, the oldest gone")
    (is (= [:answer "an answer"] (last (:transcript final))))))

(deftest a-session-at-its-turn-limit-says-so
  (let [{:keys [out unread final]} (run-session ["one" "two" "three"] (answer "a")
                                                :config {:working-dir "." :max-iterations 4 :persist-session? false
                                                         :max-turns 2 :session-id "s-limit"})
        said (map second (filter #(= :info (first %)) out))]
    (is (= ["three"] unread) "two turns, then no more")
    (is (some #(str/starts-with? % "the session reached its limit of 2 turns") said))
    (testing "and keeps what it said as it closed, with which session it was, for a screen that is gone (byj-gly.252)"
      (is (= 1 (count (:closing-notices final))))
      (is (str/starts-with? (first (:closing-notices final)) "the session reached its limit"))
      (is (= "s-limit" (:session-id final)))
      (is (false? (:persisted? final)))))
  (testing "a session that was ended says nothing of the kind"
    (let [{:keys [out]} (run-session ["one" "/quit"] (answer "a"))]
      (is (not-any? #(str/includes? (str (second %)) "reached its limit") out)))))

(deftest the-config-editor-and-the-model-picker-are-handed-their-rows
  (let [dir (str "target/session-test/editor-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir))
        load #(let [{:keys [config sources]} (config/load-config {:working-dir dir :project-dir dir
                                                                  :user-dir (str dir "/no-user")})]
                {:config (assoc config :config-sources sources :persist-session? false)})
        shown (atom [])
        box (atom nil)
        {:keys [out]} (run-session ["/config" "/config recap? false" "/config unset recap?"
                                    "/model" "/model 2" "/model gemma4:12b" "/model list"]
                                   (answer "unused")
                                   :config (:config (load))
                                   :config-loaded (:config (load))
                                   :reload-config load
                                   :lm-box box
                                   :present (fn [kind data] (when-not (= :tool-rows kind) (swap! shown conj [kind data])) true))
        said (mapv second (filter #(= :info (first %)) out))
        [[k0 d0] [k1 d1] [k2 d2] [k3 d3]] @shown]
    (testing "/config opens the editor on every row, grouped by family, and prints nothing"
      (is (= :config k0))
      (is (= (count config/config-keys) (count (:rows d0))))
      (is (= :loop (:family (first (:rows d0)))) "the families in order")
      (is (not-any? #(str/starts-with? % "settings by family") said)))
    (testing "a write through it is in force at once, and the open editor is handed the new rows"
      (is (some #(str/starts-with? % "recap?: nil → false in ") said))
      (is (some #(= "config changed, in force from this turn: recap? true → false" %) said))
      (is (= [:config true] [k1 (:refresh? d1)]))
      (is (false? (:value (some #(when (= :recap? (:key %)) %) (:rows d1))))))
    (testing "unset puts the default back"
      (is (some #(str/starts-with? % "recap?: false → unset in ") said))
      (is (true? (:value (some #(when (= :recap? (:key %)) %) (:rows d2))))))
    (testing "/model opens the picker on the curated models, with the one in use"
      (is (= :model k3))
      (is (= "mock" (:current d3)))
      (is (= "opus" (:model (first (:models d3))))))
    (testing "a number is that entry, and a bare id the list knows is its provider's"
      (is (some #(= "model claude-code/sonnet — from the next turn" %) said))
      (is (some #(= "model ollama/gemma4:12b — from the next turn" %) said))
      (is (= :ollama (:provider @box))))
    (testing "/model list is the numbered list"
      (is (some #(str/starts-with? % "  1. claude-code/opus — ") said)))))

(deftest config-key-value-writes-the-project-file
  ;; byj-gly.142: /config KEY VALUE writes as config$set does, and a permission
  ;; key is refused here as it is to the agent (RFC-012)
  (let [dir (str "target/session-test/config-set-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir))
        {:keys [out]} (run-session ["/config max-iterations 7" "/config max-iterations seven"
                                    "/config permission-mode auto-approve"]
                                   (answer "unused")
                                   :config {:working-dir dir :project-dir dir :max-iterations 4
                                            :persist-session? false})
        said (mapv second (filter #(= :info (first %)) out))
        file (java.io.File. dir ".brainyard/config.edn")]
    (testing "a value is typed by the key and written, with where and what it replaced"
      (is (str/includes? (said 0) "max-iterations: nil → 7 in "))
      (is (str/includes? (slurp file) ":max-iterations 7")))
    (testing "a value the key cannot take is refused and nothing changes"
      (is (str/starts-with? (said 1) "not written: :max-iterations should be an integer"))
      (is (str/includes? (slurp file) ":max-iterations 7")))
    (testing "a permission key is not written from here — the gate is where a person grants one"
      (is (str/includes? (said 2) "not written: :permission-mode is a permission key"))
      (is (not (str/includes? (slurp file) "permission-mode"))))))

(deftest an-edited-config-applies-from-the-next-turn
  ;; brainyard swaps its cached config at once; the port read it once, at the
  ;; start, and every edit waited for the next session (byj-gly.162)
  (let [base {:working-dir "." :max-iterations 4 :persist-session? false :recap-after-ms 60000
              :enable-mouse true}
        file (atom base)
        lines (atom ["first" "EDIT" "/config recap-after-ms" "second" "third"])
        out (atom [])]
    (session/run {:lm {:provider :mock :format :mock :responses (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))}
                  :config base
                  :config-loaded base
                  :reload-config (fn [] {:config @file})
                  :read-input (fn [_] (let [[l] @lines]
                                        (swap! lines #(vec (rest %)))
                                        (if (= "EDIT" l)
                                          (do (reset! file (assoc base :recap-after-ms 30000 :enable-mouse false))
                                              "/help")
                                          l)))
                  :emit (fn [kind text] (swap! out conj [kind text]))})
    (let [info (map second (filter #(= :info (first %)) @out))]
      (testing "the edit is in force from the next command or turn, and said so"
        (is (some #(= "config changed, in force from this turn: recap-after-ms 60000 → 30000" %) info))
        (is (some #(str/starts-with? % "recap-after-ms = 30000") info) "/config shows the value in force"))
      (testing "a key read only at the start waits for the next session, and is said once"
        (is (= 1 (count (filter #(= "config changed, applies from the next session: enable-mouse true → false" %) info)))))
      (testing "and nothing is reported when nothing changed"
        (is (= 1 (count (filter #(str/starts-with? % "config changed, in force") info))))))))

(deftest an-edited-permission-key-waits-for-the-next-session
  ;; config.edn sits in the working directory and a file tool can write it: a
  ;; mode or an allow-list the agent wrote must not govern the run that wrote
  ;; it (byj-gly.226). shift+tab is how a person changes the mode mid-run.
  (let [base {:working-dir "." :max-iterations 4 :persist-session? false :permission-mode :auto}
        file (atom base)
        box (atom :ask-each-time)
        said (atom [])
        lines (atom ["first" "second"])]
    (session/run {:lm {:provider :mock :format :mock :responses (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))}
                  :config base
                  :config-loaded base
                  :permission-mode-box box
                  :reload-config (fn [] {:config @file})
                  :read-input (fn [_] (let [[l] @lines]
                                        (swap! lines #(vec (rest %)))
                                        (when (= "second" l) (reset! file (assoc base :permission-mode :auto-approve
                                                                                 :tool-allow-tools ["bash"])))
                                        l))
                  :emit (fn [kind text] (swap! said conj (str text)))})
    (is (= :ask-each-time @box) "the mode chosen stays")
    (is (some #(and (str/includes? % "applies from the next session") (str/includes? % "permission-mode")) @said)
        "and the edit is said to wait")))

(deftest a-config-edit-rebuilds-what-the-next-turn-is-shown
  ;; not only the value: the tools and the prompt built from the config are
  ;; built again, so turning a family of tools off takes it out of the prompt
  (let [base {:working-dir "." :max-iterations 4 :persist-session? false :tasks? true}
        file (atom base)
        lines (atom ["first" "second"])
        prompts (atom [])]
    (session/run {:lm {:provider :mock :format :mock
                       :responses (fn [msgs] (swap! prompts conj (:content (first msgs)))
                                    (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))}
                  :config base
                  :config-loaded base
                  :reload-config (fn [] {:config @file})
                  :read-input (fn [_] (let [[l] @lines]
                                        (swap! lines #(vec (rest %)))
                                        (when (= "second" l) (reset! file (assoc base :tasks? false)))
                                        l))
                  :emit (fn [_ _])})
    (is (str/includes? (first @prompts) "task$list") "turn 1 is shown the task commands")
    (is (not (str/includes? (second @prompts) "task$list")) "turn 2, after the edit, is not")))

(deftest a-callers-hooks-survive-a-rebuild
  ;; byj-g5n.2: a hook handed to session/run — an SDK client's — was dropped
  ;; when a config edit built the ctx again, so it fired for turn 1 only
  (let [base {:working-dir "." :max-iterations 4 :persist-session? false :tasks? true}
        file (atom base)
        lines (atom ["first" "second"])
        fired (atom [])]
    (session/run {:lm {:provider :mock :format :mock
                       :responses (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))}
                  :config base
                  :config-loaded base
                  :reload-config (fn [] {:config @file})
                  :hooks [{:id :sdk/watch :event :agent.ask/pre
                           :handler (fn [payload _] (swap! fired conj (:question payload)) nil)}]
                  :read-input (fn [_] (let [[l] @lines]
                                        (swap! lines #(vec (rest %)))
                                        (when (= "second" l) (reset! file (assoc base :tasks? false)))
                                        l))
                  :emit (fn [_ _])})
    (is (= 2 (count @fired)) "the hook fires for the turn after the edit as well")))

(deftest usage-tables-the-calls-the-totals-are-made-of
  (let [{:keys [out]} (run-session ["a question" "/usage" "/usage 1 --breakdown" "/usage nope"]
                                   (answer "an answer"))
        said (fn [n] (second (nth (filter #(= :info (first %)) out) n)))]
    (testing "the totals, and a table of the calls behind them"
      (let [report (said 0)]
        (is (str/includes? report "1 turn(s) · 1 LLM call(s)"))
        (is (str/includes? report "last 1 call(s):"))
        (is (str/includes? report "react/think-act") "the call names the predictor that made it")))
    (testing "--breakdown says where the prompt went, and what the cache did"
      (let [report (said 1)]
        (is (str/includes? report "agent-core:") "one row per zone")
        (is (str/includes? report "cache: 0 read, 0 written"))))
    (testing "and an argument that is not a window is refused, not guessed at"
      (is (= "usage: /usage [N] [--breakdown]" (said 2))))))

(deftest the-subscription-is-shown-by-usage-and-doctor
  ;; byj-gly.324: what agent$claude, the claude-code provider and the
  ;; simulation spend — from a seam, never the network or a keychain here
  (let [u {:logged-in? true :limits [{:label "Session" :percent 42 :resets-at nil}]
           :account {:email "a@b.c" :organization "Org" :plan "max"
                     :refresh-expires-at (+ (System/currentTimeMillis) (* 3 86400000))}}
        said (fn [out] (keep #(when (= :info (first %)) (second %)) out))
        {:keys [out]} (run-session ["/doctor" "/usage"] (answer "x")
                                   :claude-usage-fn (constantly u)
                                   :config {:working-dir "." :persist-session? false :claude-usage "on"})
        [doctor usage] (said out)]
    (testing "/doctor: the tools and the project, what is installed, then the account and its windows"
      (is (str/includes? doctor "Tools:"))
      (is (re-find #"Installed[^\n]*:\n- byj: " doctor) "byj-gly.181.13")
      (is (str/includes? doctor "signed in as a@b.c (Org), plan max"))
      (is (str/includes? doctor "refresh token: 2 day(s) left — /login claude soon"))
      (is (str/includes? doctor "Claude Code subscription (max, a@b.c, Org):"))
      (is (re-find #"Session  █{8}░{12}  42%" doctor)))
    (testing "/usage adds the windows when :claude-usage is on"
      (is (str/includes? usage "Claude Code subscription")))
    (testing "and leaves them out on another provider when it is auto, unasked"
      (let [{:keys [out]} (run-session ["/usage"] (answer "x")
                                       :claude-usage-fn (fn [] (throw (ex-info "must not be asked" {}))))]
        (is (not (str/includes? (first (said out)) "subscription")))))))

(deftest loop-runs-a-prompt-now-and-again-as-claude-codes-does
  ;; byj-gly.325
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "loop-" (System/nanoTime))) .mkdirs))
        cfg {:working-dir dir :project-dir dir :persist-session? false :session-id "t-1" :max-iterations 4}
        said (fn [out k] (keep #(when (= k (first %)) (second %)) out))
        asked (fn [prompts] (str/join "\n" (map :content (filter #(= "user" (:role %)) (last prompts)))))]
    (testing "an interval: scheduled for this session, and run now with the prompt alone"
      (let [{:keys [out prompts]} (run-session ["/loop 7m check the build"] (answer "ok") :config cfg)
            [job] (brainyard.schedule/specs dir)]
        (is (re-find #"Scheduled every 6 min \(cron \*/6 \* \* \* \*\), job check-the-build-\d+ \(rounded: 7m → 6m\)"
                     (first (said out :info))))
        (is (= ["check the build" "*/6 * * * *" "t-1"] ((juxt :prompt :cron :session) job)))
        (is (str/includes? (asked prompts) "check the build"))
        (is (not (str/includes? (asked prompts) "/loop")) "the model is asked the prompt, not the command")))
    (testing "the trailing form"
      (is (= {:interval "20m" :prompt "watch the deploy"} (session/parse-loop "watch the deploy every 20 minutes")))
      (is (= {:prompt "check every PR"} (session/parse-loop "check every PR")) "every, not followed by a time")
      (is (= {:interval "5m" :prompt "/usage"} (session/parse-loop "5m /usage"))))
    (testing "no interval: run now, self-paced, told the exact line to arm the next with"
      (let [{:keys [prompts]} (run-session ["/loop wait on the CI run"] (answer "ok") :config cfg)
            q (asked prompts)]
        (is (str/includes? q "wait on the CI run"))
        (is (str/includes? q "[/loop, self-paced]"))
        (is (str/includes? q "schedule$add with :prompt exactly \"/loop wait on the CI run\""))))
    (testing "/schedule lists this session's jobs; remove stops one; a bare /loop says how"
      (let [id (:id (first (brainyard.schedule/specs dir)))
            {:keys [out]} (run-session ["/schedule" (str "/schedule remove " id) "/loop"] (answer "x") :config cfg)
            [listing removed usage] (said out :info)]
        (is (str/includes? listing id))
        (is (str/includes? listing "fire only while this session is open"))
        (is (= (str "removed " id) removed))
        (is (str/starts-with? usage "usage: /loop [interval] <prompt>"))
        (is (empty? (brainyard.schedule/specs dir)))))))

(deftest memory-lists-searches-edits-and-deletes-the-projects-memories
  ;; byj-gly.327: the store's own commands, a fake store here
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "memcmd-" (System/nanoTime))) .mkdirs))
        log (str dir "/log")
        script! (fn [n body] (let [f (str dir "/" n)]
                               (spit f (str "#!/bin/sh\n" body "\n"))
                               (fx/sh {:out :string :err :string} "chmod" "+x" f)
                               f))
        cfg {:working-dir dir :project-dir dir :persist-session? false :max-iterations 4
             :memory-list-cmd [(script! "list.sh" "echo '{\"retry-budget\": \"Three retries, then stop.\\nWhy: the API bills each.\", \"auth-jwt\": \"Auth is JWT\"}'")]
             :memory-forget-cmd [(script! "forget.sh" (str "echo \"forget $*\" >> " log))]
             :memory-store-cmd [(script! "store.sh" (str "echo \"store $*\" >> " log))]}
        said (fn [out] (keep #(when (= :info (first %)) (second %)) out))
        logged #(str/split-lines (slurp log))]
    (testing "with no picker: each key and its first line; a query narrows; show says one whole"
      (let [{:keys [out]} (run-session ["/memory" "/memory retries" "/memory show retry-budget" "/memory nothing-like-it"]
                                       (answer "x") :config cfg)
            [all some one none] (said out)]
        (is (= "auth-jwt — Auth is JWT\nretry-budget — Three retries, then stop.\n2 memories · /memory show KEY · edit KEY · delete KEY" all))
        (is (str/starts-with? some "retry-budget — "))
        (is (= "retry-budget\n\nThree retries, then stop.\nWhy: the API bills each." one))
        (is (= "no memory matches \"nothing-like-it\"" none))))
    (testing "delete runs the store's forget with the key"
      (let [{:keys [out]} (run-session ["/memory delete auth-jwt"] (answer "x") :config cfg)]
        (is (= ["deleted auth-jwt"] (said out)))
        (is (= ["forget auth-jwt"] (logged)))))
    (testing "edit hands the terminal to the editor and stores what comes back under the key"
      (let [edited (atom nil)
            {:keys [out]} (run-session ["/memory edit retry-budget" "/memory edit auth-jwt"] (answer "x") :config cfg
                                       :env-fn {"EDITOR" "my-editor"}
                                       :hand-terminal (fn [argv _]
                                                        (reset! edited argv)
                                                        (when (str/includes? (last argv) "retry-budget")
                                                          (spit (last argv) "Four retries now.\n"))))]
        (is (= "my-editor" (first @edited)))
        (run-session ["/memory edit auth-jwt"] (answer "x") :config cfg
                     :env-fn {} :hand-terminal (fn [argv _] (reset! edited argv)))
        (is (= "vi" (first @edited)) "with no editor set, vi — the pager cannot edit")
        (is (= ["retry-budget rewritten" "auth-jwt unchanged"] (said out)))
        (is (= "store Four retries now. --key retry-budget" (last (logged))))))
    (testing "a front end with a picker gets every memory, the query its filter"
      (let [shown (atom nil)]
        (run-session ["/memory jwt"] (answer "x") :config cfg :present (fn [k d] (reset! shown [k d]) true))
        (is (= [:memory ["auth-jwt" "retry-budget"] "jwt"]
               (let [[k d] @shown] [k (mapv :key (:rows d)) (:query d)])))))
    (testing "headless, edit says how instead; memory off, nothing"
      (let [{:keys [out]} (run-session ["/memory edit auth-jwt"] (answer "x") :config cfg)]
        (is (str/starts-with? (first (said out)) "no terminal to edit in here")))
      (let [{:keys [out]} (run-session ["/memory"] (answer "x") :config (assoc cfg :memory? false))]
        (is (= ["memory is off here (:memory? false)"] (said out)))))))

(deftest a-turn-is-told-the-memories-its-question-is-about
  ;; byj-gly.335: keys and first lines in the turn's user-context, never the contents
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "memhint-" (System/nanoTime))) .mkdirs))
        lists (atom 0)
        script! (fn [n body] (let [f (str dir "/" n)]
                               (spit f (str "#!/bin/sh\n" body "\n"))
                               (fx/sh {:out :string :err :string} "chmod" "+x" f)
                               f))
        lister (script! "list.sh" (str "echo x >> " dir "/lists; echo '{\"live-tui-checks-use-tmux\": \"Drive the built TUI with tmux, not pyte.\\nSecond line, never shown.\", \"test-cadence\": \"Run the relevant namespaces, not the suite.\"}'"))
        cfg {:working-dir dir :project-dir dir :persist-session? false :max-iterations 4
             :memory-hint? true :memory-list-cmd [lister] :memory-recall-cmd [lister]}
        system (fn [prompts] (:content (first (first prompts))))
        user-context (fn [prompts] (last (str/split (system prompts) #"## user-context")))]
    (testing "a question that shares rare words with a memory: its key and first line, in the turn's zone"
      (let [{:keys [prompts]} (run-session ["drive the built TUI picker with tmux"] (answer "ok") :config cfg)
            uc (user-context prompts)]
        (is (str/includes? uc "## Possibly relevant memories"))
        (is (str/includes? uc "- `live-tui-checks-use-tmux` — Drive the built TUI with tmux, not pyte."))
        (is (not (str/includes? (system prompts) "Second line, never shown.")) "the first line only")
        (is (not (str/includes? uc "test-cadence")) "a memory it shares nothing with is not named")
        (is (str/includes? uc "memory$read"))))
    (testing "a question about nothing stored: no hint at all"
      (let [{:keys [prompts]} (run-session ["write a haiku about autumn"] (answer "ok") :config cfg)]
        (is (not (str/includes? (system prompts) "Possibly relevant memories")))))
    (testing "every turn its own; the store listed once for the session"
      (spit (str dir "/lists") "")
      (let [{:keys [prompts]} (run-session ["drive the built TUI with tmux" "a haiku" "drive the built TUI with tmux again"] (answer "ok") :config cfg)
            told (mapv #(str/includes? (:content (first %)) "live-tui-checks-use-tmux") prompts)]
        (is (= [true false true] (take 3 told)) "the three turns' calls; a recap may follow")
        (is (= 1 (count (str/split-lines (str/trim (slurp (str dir "/lists")))))) "listed once")))
    (testing "on by default; :memory-hint? false turns it off"
      (let [{:keys [prompts]} (run-session ["drive the built TUI picker with tmux"] (answer "ok") :config (dissoc cfg :memory-hint?))]
        (is (str/includes? (system prompts) "Possibly relevant memories")))
      (let [{:keys [prompts]} (run-session ["drive the built TUI picker with tmux"] (answer "ok") :config (assoc cfg :memory-hint? false))]
        (is (not (str/includes? (system prompts) "Possibly relevant memories")))))))

(deftest a-question-that-says-at-here-is-told-what-the-person-is-looking-at
  ;; byj-gly.453: read from the running editor as the turn begins, never left
  ;; to the model to ask for with terminal$context
  (let [label (str "byj-here-test-" (System/nanoTime))
        dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "here-" (System/nanoTime))) .mkdirs))
        asked (io/file dir "asked")
        cfg {:working-dir dir :project-dir dir :persist-session? false :max-iterations 4 :memory-hint? false}
        system (fn [prompts] (:content (first (first prompts))))
        user-context (fn [prompts] (last (str/split (system prompts) #"## user-context")))]
    (.mkdirs (io/file dir ".brainyard"))
    (spit (io/file dir ".brainyard" "terminal.edn")
          (pr-str [{:name "ed" :command ["sleep" "300"]
                    :context ["sh" "-c" (str "echo x >> asked; printf '%s' '{\"file\":\"/p/a.clj\",\"line\":7,\"column\":3,\"modified\":true,\"region\":\"(defn f [] {line})\"}' > \"$1\"") "sh" "${OUT}"]}]))
    (binding [ah/*tmux* ["tmux" "-L" label]]
      (try
        (testing "with no editor running, the turn is told it could not be read, and so is the person"
          (let [{:keys [prompts out]} (run-session ["what does @here do?"] (answer "ok") :config cfg)
                uc (user-context prompts)]
            (is (str/includes? uc "## @here"))
            (is (str/includes? uc "could not be read"))
            (is (str/includes? uc "Ask them which file and lines they mean"))
            (is (some (fn [[k t]] (and (= :info k) (str/starts-with? (str t) "@here: "))) out))))
        (terminal/start! dir {:name "ed" :command ["sleep" "300"]})
        (testing "running, its file, cursor and selection are in the turn's zone, as they are"
          (let [{:keys [prompts]} (run-session ["explain @here, please"] (answer "ok") :config cfg)
                uc (user-context prompts)]
            (is (str/includes? uc "## @here — what the person is looking at"))
            (is (str/includes? uc "they meant this, in ed:"))
            (is (str/includes? uc "- File: `/p/a.clj`"))
            (is (str/includes? uc "- Cursor at line 7, column 3 — with unsaved edits"))
            (is (str/includes? uc "(defn f [] {line})") "a placeholder in the selection is left as it is")))
        (testing "a question without it reads nothing from the editor"
          (spit asked "")
          (let [{:keys [prompts]} (run-session ["what does here do? mail me@here.org"] (answer "ok") :config cfg)]
            (is (not (str/includes? (system prompts) "## @here")))
            (is (= "" (slurp asked)) "the editor was never asked")))
        (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server"))))))

(deftest a-turns-writes-are-kept-for-its-review
  ;; RFC-067 phase 8 (byj-gly.476, byj-gly.481): the project's git work tree
  ;; snapshotted as the turn begins and ends, the turn kept as a ref, and its
  ;; end says how many files differ
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "changes-" (System/nanoTime))) .mkdirs))
        f (io/file dir "a.txt")
        _ (spit f "before\n")
        _ (fx/sh {:dir dir :out :string :err :string} "git" "init" "-q")
        cfg {:working-dir dir :project-dir dir :session-id "s-changes" :persist-session? false :max-iterations 4
             :memory-hint? false :recap? false}
        replies (atom [(json/write-str {:reasoning "r" :answer ""
                                        :tool-calls [{:tool-name "write-file" :tool-args {:path (str f) :content "after\n"}}]})
                       (json/write-str {:reasoning "r" :tool-calls [] :answer "written"})
                       (json/write-str {:reasoning "r" :tool-calls [] :answer "nothing written"})])
        events (atom [])]
    (run-session ["write it" "now nothing"] (fn [_] (let [r (first @replies)] (swap! replies #(vec (rest %))) r))
                 :config cfg :on-event (fn [e d] (when (= :session.turn/end e) (swap! events conj d))))
    (is (= [1 0] (map :files-changed @events)) "the turn that wrote, and the one that did not")
    (let [{:keys [turn ref files diff]} (changes/diff cfg "s-changes")]
      (is (= 1 turn))
      (is (= "refs/byj/turns/s-changes/1" ref))
      (is (= ["a.txt"] files))
      (is (str/includes? diff "-before\n+after\n")))))

(deftest the-person-puts-files-back-and-the-next-turn-is-told
  ;; RFC-069 (byj-gly.484): /rewind lists the turns, puts the files back as
  ;; they were when one began, and is taken back; /undo-turn takes back one
  ;; turn alone; the next turn's history says what the person did
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "rewind-" (System/nanoTime))) .mkdirs))
        f (io/file dir "a.txt")
        _ (spit f "before\n")
        _ (fx/sh {:dir dir :out :string :err :string} "git" "init" "-q")
        cfg {:working-dir dir :project-dir dir :session-id "s-rewind" :persist-session? false :max-iterations 4
             :memory-hint? false :recap? false}
        replies (atom [(json/write-str {:reasoning "r" :answer ""
                                        :tool-calls [{:tool-name "write-file" :tool-args {:path (str f) :content "after\n"}}]})
                       (json/write-str {:reasoning "r" :tool-calls [] :answer "written"})
                       (json/write-str {:reasoning "r" :tool-calls [] :answer "seen"})])
        events (atom [])
        {:keys [out prompts]}
        (run-session ["write it" "/rewind" "/rewind 1" "/undo-turn" "/rewind 9" "what is there now?"]
                     (fn [_] (let [r (first @replies)] (swap! replies #(vec (rest %))) r))
                     :config cfg :on-event (fn [e d] (when (= :session/rewind e) (swap! events conj d))))
        infos (map second (filter #(= :info (first %)) out))
        errors (map second (filter #(= :error (first %)) out))]
    (is (some #(re-find #"(?m)^\s+1  1 file\s+\S+  write it$" %) infos) "/rewind lists the turns kept")
    (is (some #(str/starts-with? % "put the files back as they were when turn 1 began: 1 file (a.txt)") infos))
    (is (= "before\n" (slurp f)))
    (is (= [{:kind :rewind :turn 1 :end? false :files ["a.txt"] :conflicts [] :saved "refs/byj/rewinds/s-rewind/1"}] @events))
    (is (some #(str/starts-with? % "usage: /undo-turn TURN") errors))
    (is (some #(re-find #"turn 9 of session s-rewind is not kept" %) errors))
    (let [sys (system-of (last prompts))]
      (is (str/includes? sys "The person did these directly between turns"))
      (is (str/includes? sys "- /rewind 1: the person put the work tree back as it was when turn 1 began. Changed on disk: a.txt.")))
    (testing "taken back, and one turn undone alone, outside a session as the CLI does"
      (is (= ["a.txt"] (:files (changes/undo-rewind! cfg "s-rewind"))))
      (is (= "after\n" (slurp f)))
      (is (= ["a.txt"] (:files (changes/undo-turn! cfg "s-rewind" 1))))
      (is (= "before\n" (slurp f))))))

(deftest a-session-is-forked-before-a-turn-with-its-files
  ;; RFC-069 (byj-gly.486): /fork K — a new session whose conversation stops
  ;; before turn K, the files put back to K's start; this one untouched
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "fork-" (System/nanoTime))) .mkdirs))
        f (io/file dir "a.txt")
        _ (spit f "zero\n")
        _ (fx/sh {:dir dir :out :string :err :string} "git" "init" "-q")
        cfg {:working-dir dir :project-dir dir :session-id "s-fork" :persist-session? true :max-iterations 4
             :memory-hint? false :recap? false}
        write (fn [c] (json/write-str {:reasoning "r" :answer "" :tool-calls [{:tool-name "write-file" :tool-args {:path (str f) :content c}}]}))
        done (json/write-str {:reasoning "r" :tool-calls [] :answer "done"})
        replies (atom [(write "one\n") done (write "two\n") done done])
        opened (atom [])
        {:keys [out prompts]} (run-session ["first" "second" "/fork 2" "/fork 9" "/fork x" "/fork 1 keep-files" "third"]
                                   (fn [_] (let [r (first @replies)] (swap! replies #(vec (rest %))) r))
                                   :config cfg :start-session (fn [o] (swap! opened conj o) {:session-id (:resume o)}))
        infos (map second (filter #(= :info (first %)) out))
        errors (map second (filter #(= :error (first %)) out))
        nid (some #(second (re-find #"^forked as (\S+), its conversation before turn 2" %)) infos)]
    (is nid (pr-str infos))
    (is (= {:resume nid} (first @opened)) "opened beside this one, where the front end can")
    (is (some #(str/includes? % "it is open beside this one") infos))
    (is (= "one\n" (slurp f)) "the files as turn 2 found them")
    (let [snap (persist/read-session cfg nid)]
      (is (= ["first"] (map :question (:previous-turns snap))))
      (is (= 1 (:turn-count snap)))
      (is (= "s-fork" (:forked-from snap))))
    (is (= 3 (:turn-count (persist/read-session cfg "s-fork"))) "this session as it was, and went on: its third turn")
    (is (some #(re-find #"could not fork: the session has turns 1–2, not 9" %) errors))
    (is (some #(str/starts-with? % "usage: /fork") errors))
    (testing "this session's next turn is told its files went back under it (byj-gly.488)"
      (is (str/includes? (system-of (last prompts))
                         (str "- /fork 2: the person forked a new session (" nid ") from this conversation before turn 2, "
                              "and put the work tree back as it was when turn 2 began. Changed on disk: a.txt."))))
    (testing "a fork that kept the files: its first turn is told they hold turns it does not remember"
      (let [nid2 (some #(second (re-find #"^forked as (\S+), its conversation before turn 1" %)) infos)
            {:keys [prompts]} (run-session ["hello"] (answer "ok") :config (assoc cfg :session-id nid2 :resume nid2))]
        (is nid2)
        (is (= "one\n" (slurp f)) "the files as they were")
        (is (str/includes? (system-of (first prompts))
                           "This session was forked from session s-fork before its turn 1, keeping the files as they were"))
        (is (not (.exists (persist/notes-file cfg nid2))) "told once")))))

(deftest a-coached-session-tells-every-turn-how-to-coach
  ;; RFC-067 phase 3 (byj-gly.461): the stance is the session's coach.edn,
  ;; read as each turn begins, so it is in every turn's zone until stopped
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "coach-" (System/nanoTime))) .mkdirs))
        cfg {:working-dir dir :project-dir dir :session-id "s-coached" :persist-session? false :max-iterations 4 :memory-hint? false}
        user-context (fn [prompts] (last (str/split (:content (first (first prompts))) #"## user-context")))]
    (coach/set-stance! cfg {:goal "write a retry loop" :level "beginner"})
    (let [{:keys [prompts]} (run-session ["how do I start?" "and then?"] (answer "ok") :config cfg)]
      (is (= 2 (count (filter #(str/includes? (user-context [%]) "## Coaching: write a retry loop") prompts)))
          "both turns, not only the one that set it")
      (is (str/includes? (user-context prompts) "Do not write their code")))
    (coach/set-stance! cfg {:stop true})
    (let [{:keys [prompts]} (run-session ["thanks"] (answer "ok") :config cfg)]
      (is (not (str/includes? (user-context prompts) "## Coaching")) "stopped, a turn is told nothing"))))

(deftest at-here-is-a-word-of-its-own
  (is (session/mentions? "what is @here" "@here"))
  (is (session/mentions? "@here, explain." "@here"))
  (is (session/mentions? "fix (@here)" "@here") "trailing punctuation aside")
  (is (not (session/mentions? "read @here.clj" "@here")) "a file named so is a path")
  (is (not (session/mentions? "mail me@here" "@here")))
  (is (not (session/mentions? nil "@here")))
  (testing "the words: no file, no selection"
    (let [t (session/editor-context-text {:program "emacs" :context {:buffer "*scratch*" :line 1 :column 1 :modified false}})]
      (is (str/includes? t "- Buffer: `*scratch*` (visiting no file)"))
      (is (str/includes? t "Nothing is selected"))
      (is (not (str/includes? t "unsaved"))))))

(deftest parse-usage-args-is-brainyards
  (is (= {:n 10 :breakdown? false} (session/parse-usage-args [])))
  (is (= {:n 3 :breakdown? false} (session/parse-usage-args ["3"])))
  (is (= {:n 10 :breakdown? true} (session/parse-usage-args ["--breakdown"])))
  (is (= {:n 5 :breakdown? true} (session/parse-usage-args ["--breakdown" "5"])) "in any order")
  (is (nil? (session/parse-usage-args ["0"])) "a window of nothing is not a window")
  (is (nil? (session/parse-usage-args ["twelve"]))))

(deftest commands-front-what-the-kernel-already-does
  (testing "/task reads the registry the model has had all along"
    (let [reg (tasks/registry)
          {:keys [out]} (run-session ["/task" "/task nope" "/task cancel"] (answer "a")
                                     :task-registry reg)
          said (mapv second (filter #(= :info (first %)) out))]
      (is (= "no background tasks" (first said)))
      (is (= "no task with id nope" (second said)))
      (is (= "usage: /task cancel <id>" (nth said 2)))))

  (testing "/todo shows the checklist that outlives a turn"
    (let [dir (str "target/session-test/todo-" (System/currentTimeMillis))
          _ (.mkdirs (java.io.File. dir))
          config {:working-dir dir :project-dir dir :max-iterations 4 :persist-session? false}
          {:keys [out]} (run-session ["/todo"] (answer "a") :config config)
          said (fn [o] (second (first (filter #(= :info (first %)) o))))]
      (is (str/includes? (said out) "no checklists"))
      (todo/create! config "retry-budget" "Retry budget" ["widen the bound" "measure it"] nil)
      (let [{:keys [out]} (run-session ["/todo" "/todo nope"] (answer "a") :config config)
            infos (mapv second (filter #(= :info (first %)) out))]
        (is (str/includes? (first infos) "widen the bound") "one list needs no name")
        (is (str/includes? (second infos) "no checklist nope")))))

  (testing "/mcp says what is connected, and what that is when nothing is"
    (let [{:keys [out]} (run-session ["/mcp"] (answer "a"))]
      (is (str/starts-with? (second (first (filter #(= :info (first %)) out))) "no MCP servers configured")))))

(deftest model-switches-for-the-next-turn
  (let [box (atom nil)
        {:keys [out]} (run-session ["/model" "/model anthropic/claude-sonnet-5" "/model" "/model nonsense"]
                                   (answer "a") :lm-box box)
        said (mapv second (filter #(= :info (first %)) out))]
    (testing "with no argument it says what this session is asking"
      (is (str/starts-with? (first said) "model mock\n") "the harness's LM names a provider and no model")
      (is (str/includes? (first said) "/model list shows the ")))
    (testing "with one it switches, and says when it takes effect"
      (is (str/includes? (second said) "anthropic/claude-sonnet-5"))
      (is (str/includes? (second said) "from the next turn"))
      (is (= :anthropic (:provider @box)) "the box the next call reads"))
    (testing "and the switch is what /model then reports"
      (is (str/includes? (nth said 2) "anthropic/claude-sonnet-5"))
      (is (str/includes? (nth said 2) "switched this session")))
    (testing "a label naming no provider is refused, and the session keeps its model"
      (is (str/includes? (nth said 3) "not a model: nonsense"))
      (is (= :anthropic (:provider @box))))))

(deftest a-switched-model-is-what-the-next-call-runs-on
  ;; the seam itself: agent/current-lm prefers the box over the ambient LM, and
  ;; a node that pins its own model still wins (RFC-005's order)
  (let [ambient {:provider :mock :model "mock"}
        switched {:provider :anthropic :model "claude-sonnet-5"}]
    (is (= ambient (agent/current-lm {:lm ambient})))
    (is (= ambient (agent/current-lm {:lm ambient :lm-box (atom nil)})))
    (is (= switched (agent/current-lm {:lm ambient :lm-box (atom switched)})))))

(deftest allow-path-is-the-persons-and-the-sessions
  ;; byj-gly.145: brainyard's /allow-path, for this session only, and only from
  ;; the person — a line the session channel handed over is refused
  (let [dir (str "target/session-test/allow-" (System/currentTimeMillis))
        other (.getAbsolutePath (java.io.File. (str dir "-other")))
        _ (.mkdirs (java.io.File. dir))
        _ (.mkdirs (java.io.File. other))
        box (atom [])
        ctx {:config {:working-dir dir :project-dir dir :allowed-dirs [dir]} :allowed-dirs-box box}
        allow #(#'session/allow-path! %1 %2)
        canon (brainyard.config/canonical-dir other)]
    (is (str/includes? (allow ctx "no/such/dir") "is not a directory"))
    (is (empty? @box))
    (is (str/includes? (allow ctx other) (str "tools may reach " canon " for this session — not saved")))
    (is (= [canon] @box))
    (allow ctx other)
    (is (= [canon] @box) "once")
    (is (str/includes? (allow ctx "") canon) "listed with the rest")
    (testing "over the session channel, refused"
      (let [box2 (atom [])]
        (is (str/includes? (allow (assoc ctx :allowed-dirs-box box2 :channel {:in-flight (atom {:text "/allow-path /"})}) "/")
                           "came over the session channel"))
        (is (empty? @box2))))
    (testing "and nothing is written to config.edn"
      (is (not (.exists (java.io.File. dir ".brainyard/config.edn")))))
    (testing "a session is given its own box, and a command line reaches it"
      (let [{:keys [out]} (run-session [(str "/allow-path " other) "/allow-path"] (answer "x")
                                       :config {:working-dir dir :project-dir dir :allowed-dirs [dir] :persist-session? false})]
        (is (some #(str/includes? (str (second %)) (str "tools may reach " canon)) out))
        (is (some #(str/includes? (str (second %)) "(1 added with /allow-path, for this session)") out))))))

(deftest brainyards-pause-and-sandbox-are-not-commands
  ;; byj-gly.145, divergences no-pause-command and no-sandbox-eval-command
  (let [names (set (map first session/commands))]
    (is (not-any? names ["/pause" "/continue" "/sandbox"]))
    (is (contains? names "/allow-path"))))

(deftest capture-writes-what-was-said
  (let [dir (str "target/session-test/capture-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir))
        path (str dir "/session.txt")
        _ (spit (str dir "/notes.txt") "hello")
        replies (atom [(json/write-str {:reasoning "r" :tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}] :answer ""})])
        {:keys [out]} (run-session ["what is here" (str "/capture " path)]
                                   (fn [m] (or (let [[x] (swap-vals! replies #(vec (rest %)))] (first x))
                                               ((answer "an answer") m)))
                                   :config {:working-dir dir :project-dir dir
                                            :max-iterations 4 :persist-session? false})
        text (slurp path)]
    (testing "the file holds the conversation, question and answer alike"
      (is (str/includes? text "› what is here"))
      (is (str/includes? text "an answer")))
    (testing "and the tool calls between them, one line each behind the thought that made them, in the order they ran"
      (is (re-find #"(?s)› what is here\n· r\n⏺ list-dir \{:path \"\.\"\} → .*notes\.txt.*\nan answer" text)))
    (testing "and the session says where it went"
      (is (some #(and (= :info (first %)) (str/includes? (second %) (str "line(s) to " path))) out)))))

(deftest capture-picks-its-own-file-when-nobody-says
  (let [dir (str "target/session-test/capture-default-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir))
        {:keys [out]} (run-session ["a question" "/capture"] (answer "an answer")
                                   :config {:working-dir dir :project-dir dir
                                            :max-iterations 4 :persist-session? false})
        said (some #(when (and (= :info (first %)) (str/includes? (second %) "captured")) (second %)) out)]
    (is (some? said))
    (is (str/includes? said "/.brainyard/captures/capture-"))
    (is (str/includes? (slurp (last (str/split said #" to "))) "a question"))))

(deftest tool-trail-is-kept-for-recent-turns
  (let [calls (atom 0)
        llm (fn [_]
              (json/write-str
               (if (odd? (swap! calls inc))
                 {:reasoning "r" :tool-calls [{:tool-name "bash" :tool-args {:command "echo x"}}] :answer ""}
                 {:reasoning "r" :tool-calls [] :answer "done"})))
        {:keys [prompts final]} (run-session ["do it" "again"] llm)]
    (is (= 2 (:turn-count final)))
    (is (str/includes? (system-of (nth prompts 2)) "tools: bash {:command \"echo x\"}"))))

(deftest commands
  (let [{:keys [final out prompts unread]}
        (run-session ["hello" "/usage" "" "/history" "/clear" "/nope" "after clear" "/quit" "never read"]
                     (answer "hi"))]
    (is (= ["never read"] unread) "/quit stops reading input")
    (is (= [:answer :info :info :info :error :answer] (map first out)))
    (is (str/starts-with? (second (nth out 1)) "1 turn(s) · 1 LLM call(s)"))
    (is (str/includes? (second (nth out 2)) "Q: hello"))
    (is (= "history cleared" (second (nth out 3))))
    (is (not (str/includes? (system-of (last prompts)) "history-context")) "/clear empties history")
    (is (= 2 (:turn-count final)))
    (is (= ["after clear"] (map :question (:previous-turns final))))))

(deftest a-failed-turn-does-not-end-the-session
  (testing "LLM failures abort the turn, not the session"
    (let [{:keys [final out]} (run-session ["bad" "good"]
                                           (let [n (atom 0)]
                                             (fn [m] (if (<= (swap! n inc) 3)
                                                       "not json"
                                                       ((answer "recovered") m)))))]
      (is (= 2 (:turn-count final)))
      (is (str/starts-with? (second (first out)) "Agent stopped:"))
      (is (= [:answer "recovered"] (second out)))))
  (testing "an inner tree failure is caught by the turn guard"
    (let [{:keys [final out]} (run-session ["x" "y"] (answer "unused")
                                           :registry {:react/init (fn [_] :failure)})]
      (is (= 2 (:turn-count final)))
      (is (every? #(str/starts-with? (second %) "*(The agent loop failed") out))
      (is (= [:loop-error :loop-error] (map :terminated-by (:previous-turns final)))))))

(deftest a-compacted-summary-is-not-clipped-and-mcp-add-needs-a-command
  ;; byj-gly.232: every later prompt cut the summary to :max-answer-chars; and
  ;; `/mcp add NAME --` wrote a server with no :command
  (let [long-summary (apply str (repeat 3000 "s"))
        text (brainyard.history/format-previous-turns
              [{:question "(turns 1–9, compacted)" :answer long-summary :terminated-by :compacted}
               {:question "q" :answer (apply str (repeat 3000 "a"))}]
              {:full-depth 3 :max-answer-chars 1000})]
    (is (str/includes? text long-summary) "the summary is whole")
    (is (not (str/includes? text (apply str (repeat 2000 "a")))) "an ordinary answer is still clipped"))
  (is (:no-command? (#'session/parse-add ["own" "--"])))
  (is (nil? (:no-command? (#'session/parse-add ["own" "--" "python3" "x.py"])))))

(defn- init-plan-reply
  "agent$init's own plan predictor, asked to only check: write nothing."
  [messages]
  (when (str/includes? (str (system-of messages)) "You prepare a project directory for brainyard")
    (json/write-str {:reasoning "r" :scaffold false :beads false :draft false :onboard false :install "" :prefix ""})))

(defn- drive-agent-init
  "The session's agent in an /init turn: it calls agent$init with what its
  question asks for — \"check\" when the question says so — then answers
  with what agent$init reported. Every main-agent prompt is kept in `seen`."
  [seen]
  (fn [messages]
    (or (init-plan-reply messages)
        (let [user (str (:content (second messages)))]
          (swap! seen conj user)
          (json/write-str
           (if (str/includes? (str/join "\n" (map :content messages)) "Tools:")
             {:reasoning "r" :tool-calls [] :answer "Here is what /init found: Tools: …"}
             {:reasoning "r" :tool-calls [{:tool-name "agent$init" :tool-args {:question "check"}}] :answer ""}))))))

(deftest init-is-a-turn-that-drives-agent-init
  ;; /init (RFC-040, byj-gly.350): not a direct call — a turn whose question
  ;; asks the session's agent to drive agent$init; with no turns yet, the
  ;; command's template; here "check", which writes nothing
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/init-" (System/nanoTime))) .mkdirs))
        seen (atom [])
        events (atom [])
        calls (atom [])
        {:keys [final out]} (run-session ["/init  check"] (drive-agent-init seen)
                                         :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false}
                                         :present (fn [kind data] (when (= :tool-call kind) (swap! calls conj data)) nil)
                                         :on-event (fn [e d] (swap! events conj [e d])))]
    (is (= "/init" (some #(when (= "/init" %) %) (map first session/commands))) "listed, so it is completed and in /help")
    (is (some #(and (str/starts-with? (str %) "question: check") (str/includes? % "agent$init") (str/includes? % "[/init]")) @seen)
        "the turn's question is the template: the words, what /init is for, and how to use agent$init")
    (is (some #(= [:info "/init → check"] %) out) "the person is told what their agent is asked")
    (is (some #(= [:you "/init  check"] [(first %) (second %)]) (:transcript final)) "the transcript keeps what was typed")
    (is (some #(and (= :session.turn/start (first %)) (= "/init  check" (:command (second %)))) @events)
        "the turn's start names the command it came from")
    (is (some #(= [:tool "agent$init"] [(first %) (:tool-name (second %))]) (:transcript final))
        "the agent called agent$init, in the turn")
    (is (some #(and (= :answer (first %)) (str/includes? (str (second %)) "Tools:")) out) "and answered with its report")
    (is (some #(and (= :session.turn/end (first %)) (= "I want to build" (:next-user-prompt (second %)))) @events)
        "agent$init's suggestion for an empty directory is offered when the turn ends, as the direct call offered it")
    (is (not-any? #(re-find #":next-user-prompt\s+\"" (str %)) @seen)
        "and the model's tool result carries no suggestion field, as for any model's call")
    (is (empty? (remove #(str/includes? (.getPath ^java.io.File %) "/.brainyard/") (rest (file-seq (java.io.File. dir)))))
        "and nothing was written")
    (is (= "Prepare this project for brainyard" (subs (session/command-request-text (first (session/agent-command {} "/init")) "") 0 34))
        "a plain /init asks the default request, never nothing")
    (is (= "/initialize" (second (session/agent-command {} "/init /initialize"))) "only the command itself is taken off")))

(deftest init-after-other-turns-is-rephrased-for-them
  ;; byj-gly.350: with turns behind it, /init's question is the
  ;; session/rephrase-command predictor's, written from the turns so far; a
  ;; predictor that fails, or :command-rephrase? off, gives the template
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/init-r-" (System/nanoTime))) .mkdirs))
        rephrase? #(str/includes? (str (system-of %)) "A person typed a slash command in a coding session")
        run (fn [rephrase-reply config]
              (let [seen (atom [])
                    asked (atom [])
                    drive (drive-agent-init seen)
                    r (run-session ["we installed bd" "/init"]
                                   (fn [messages]
                                     (if (rephrase? messages)
                                       (do (swap! asked conj (str/join "\n" (map :content messages))) (rephrase-reply))
                                       (or (init-plan-reply messages)
                                           (if (empty? @seen)
                                             (do (swap! seen conj "first") (json/write-str {:reasoning "r" :tool-calls [] :answer "noted"}))
                                             (drive messages)))))
                                   :config (merge {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false} config))]
                (assoc r :seen @seen :asked @asked)))]
    (testing "the predictor's question and line"
      (let [{:keys [seen asked out]} (run #(json/write-str {:question "bd is installed now, so run agent$init with check again and report."
                                                             :shown "/init → check again, now that bd is installed"}) {})]
        (is (= 1 (count asked)) "asked once, before the turn")
        (is (str/includes? (first asked) "we installed bd") "with the turns so far")
        (is (str/includes? (first asked) "agent$init") "and the subagent's tool")
        (is (some #(str/starts-with? (str %) "question: bd is installed now") seen) "its question is the turn's")
        (is (some #(= [:info "/init → check again, now that bd is installed"] %) out))))
    (testing "a predictor that fails gives the template"
      (let [{:keys [seen asked]} (run #(throw (ex-info "provider down" {})) {})]
        (is (seq asked))
        (is (some #(and (str/includes? (str %) "Prepare this project for brainyard") (str/includes? % "[/init]")) seen))))
    (testing ":command-rephrase? off gives the template without asking"
      (let [{:keys [seen asked]} (run #(json/write-str {:question "x" :shown "x"}) {:command-rephrase? false})]
        (is (empty? asked))
        (is (some #(str/includes? (str %) "[/init]") seen))))))

(deftest an-install-init-is-asked-for-in-its-turn-still-asks-the-person
  ;; byj-gly.350: in /init's turn it is the session's model that calls
  ;; agent$init, but an install still goes through the person's own prompt —
  ;; the child keeps the session's permission-fn whoever dispatched it — and a
  ;; refusal installs nothing. The tool is one this machine lacks and a
  ;; package manager would install unattended; with none, nothing to show
  (if-let [tool (some #(let [st (#'brainyard.init/tool-status % {})]
                         (when (and (not (:available? st)) (some-> (first (:install st)) (#'brainyard.init/runnable?)))
                           (:name %)))
                      @brainyard.init/requirements)]
    (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/init-i-" (System/nanoTime))) .mkdirs))
          asked (atom [])
          llm (fn [messages]
                (if (str/includes? (str (system-of messages)) "You prepare a project directory for brainyard")
                  (json/write-str {:reasoning "r" :scaffold false :beads false :draft false :onboard false
                                   :install tool :prefix ""})
                  (json/write-str
                   (if (str/includes? (str/join "\n" (map :content messages)) "Installing:")
                     {:reasoning "r" :tool-calls [] :answer "done"}
                     {:reasoning "r" :tool-calls [{:tool-name "agent$init" :tool-args {:question (str "install " tool)}}] :answer ""}))))
          {:keys [final]} (run-session [(str "/init install " tool)] llm
                                       :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false}
                                       :permission-fn (fn [req] (swap! asked conj req) {:denied true :reason "no"}))
          installs (filter #(= "init$install" (:tool %)) @asked)]
      (is (= [tool] (map #(get-in % [:args :tool]) installs)) "the person was asked, once, for that tool")
      (is (not (brainyard.init/on-path (:command (some #(when (= tool (:name %)) %) @brainyard.init/requirements))))
          "and, refused, it is not installed")
      (is (some #(and (= :tool (first %)) (= "agent$init" (:tool-name (second %)))) (:transcript final))))
    (is true "every tool /init knows is installed here, or none installs unattended: nothing to ask")))

(deftest init-turn-can-call-agent-init-again
  ;; byj-gly.350: the point of /init being a turn — the session's agent reads
  ;; agent$init's report and calls it again, in the same turn, before it
  ;; answers
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/init-2-" (System/nanoTime))) .mkdirs))
        n (atom 0)
        llm (fn [messages]
              (or (init-plan-reply messages)
                  (json/write-str
                   (case (swap! n inc)
                     1 {:reasoning "r" :tool-calls [{:tool-name "agent$init" :tool-args {:question "check"}}] :answer ""}
                     2 {:reasoning "the report says what is missing; ask what to do about it"
                        :tool-calls [{:tool-name "agent$init" :tool-args {:question "check again and say what to install"}}] :answer ""}
                     {:reasoning "r" :tool-calls [] :answer "Checked twice: Tools: …"}))))
        {:keys [final out]} (run-session ["/init check"] llm
                                         :config {:working-dir dir :project-dir dir :max-iterations 4 :persist-session? false})]
    (is (= 2 (count (filter #(and (= :tool (first %)) (= "agent$init" (:tool-name (second %)))) (:transcript final))))
        "two calls of agent$init in one turn")
    (is (some #(= [:answer "Checked twice: Tools: …"] %) out))
    (is (= 1 (:turn-count final)) "one turn")))

(defn- command-project
  "A project directory with `files`, `{name edn-string}`, in its
  .brainyard/commands — and the config that reads only those."
  [files]
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/cmds-" (System/nanoTime))) .mkdirs))
        cdir (doto (java.io.File. dir ".brainyard/commands") .mkdirs)]
    (doseq [[n text] files] (spit (java.io.File. cdir (str n ".edn")) text))
    {:dir dir :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                       :command-roots [(.getPath cdir)]}}))

(deftest review-is-a-turn-that-drives-agent-explore
  ;; byj-gly.354: /review is bundled — alone, the uncommitted changes; with
  ;; words, what they name. The session's agent reads the change with git
  ;; (explore has no shell) and asks agent$explore to check it; nothing is
  ;; changed
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/review-" (System/nanoTime))) .mkdirs))
        seen (atom [])
        {:keys [out]} (run-session ["/review" "/review HEAD~1" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "reviewed"}))
                                   :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                                            :command-roots [(str dir "/none")] :command-rephrase? false})
        [e] (session/agent-command {:command-roots [(str dir "/none")]} "/review")]
    (is (= "explore" (:agent e)) "bundled, driving agent$explore")
    (is (some #(and (str/starts-with? % "question: Review my uncommitted changes.") (str/includes? % "git diff")
                    (str/includes? % "agent$explore") (str/includes? % "Change nothing")) @seen)
        "/review alone: the uncommitted changes, read with git, checked by agent$explore, nothing changed")
    (is (some #(str/starts-with? % "question: HEAD~1") @seen) "/review WORDS: what the words name")
    (is (some #(= [:info "/review → Review my uncommitted changes."] %) out))
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "/review")
                    (str/includes? (str (second %)) "agent$explore")) out)
        "listed in /help with its agent")))

(deftest plan-and-edit-drive-their-agents
  ;; byj-gly.360: /plan and /edit are bundled beside /init and /review. /plan
  ;; alone plans the work the conversation is about; /edit needs words — a
  ;; bare /edit says its usage and runs no turn
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/plan-edit-" (System/nanoTime))) .mkdirs))
        none [(str dir "/none")]
        seen (atom [])
        {:keys [out]} (run-session ["/plan" "/plan move the retry cap into config" "/edit" "/edit set max-tries to 5 in src/retry.clj" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                   :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                                            :command-roots none :command-rephrase? false})]
    (is (= "plan" (:agent (first (session/agent-command {:command-roots none} "/plan")))))
    (is (= "edit" (:agent (first (session/agent-command {:command-roots none} "/edit")))))
    (is (some #(and (str/starts-with? % "question: Write a plan for the work this conversation is about.")
                    (str/includes? % "agent$plan") (str/includes? % "Need:")) @seen)
        "/plan alone: the work the conversation is about, its three answers handled")
    (is (some #(str/starts-with? % "question: move the retry cap into config") @seen))
    (is (some #(and (str/starts-with? % "question: set max-tries to 5 in src/retry.clj")
                    (str/includes? % "agent$edit") (str/includes? % "once per file") (str/includes? % "Rollback:")) @seen)
        "/edit WORDS: agent$edit one file at a time, its rollback lines reported")
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "usage: /edit WORDS")) out) "a bare /edit says its usage")
    (is (= 3 (count (filter #(str/starts-with? % "question: ") @seen))) "and runs no turn: three questions for four lines")
    (is (every? (fn [c] (some #(and (= :info (first %)) (str/includes? (str (second %)) c)) out)) ["/plan" "/edit"])
        "both listed in /help")))

(deftest claude-drives-one-claude-code-session
  ;; byj-gly.361: /claude hands agent$claude the work and has the session's
  ;; agent judge each result and send the follow-ups to the same instance
  ;; with agent-registry$ask, so one Claude Code session does them; a bare
  ;; /claude has no task and says its usage
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/claude-cmd-" (System/nanoTime))) .mkdirs))
        none [(str dir "/none")]
        seen (atom [])
        {:keys [out]} (run-session ["/claude" "/claude add a --dry-run flag to the deploy script" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                   :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                                            :command-roots none :command-rephrase? false})]
    (is (= "claude" (:agent (first (session/agent-command {:command-roots none} "/claude")))))
    (is (some #(and (str/starts-with? % "question: add a --dry-run flag to the deploy script")
                    (str/includes? % "agent$claude") (str/includes? % "agent-registry$ask")
                    (str/includes? % "same Claude Code session") (str/includes? % "Never dispatch agent$claude afresh"))
              @seen)
        "/claude WORDS: agent$claude briefed, each result judged, follow-ups to the same instance")
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "usage: /claude WORDS")) out) "a bare /claude says its usage")
    (is (= 1 (count (filter #(str/starts-with? % "question: ") @seen))) "and runs no turn")
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "/claude")
                    (str/includes? (str (second %)) "agent$claude")) out)
        "listed in /help with its agent")))

(deftest codex-drives-one-codex-session
  ;; byj-gly.430.6: /codex is /claude for Codex — agent$codex briefed, each
  ;; result judged, follow-ups to the same instance; a bare /codex says its usage
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/codex-cmd-" (System/nanoTime))) .mkdirs))
        none [(str dir "/none")]
        seen (atom [])
        {:keys [out]} (run-session ["/codex" "/codex add a --dry-run flag to the deploy script" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                   :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                                            :command-roots none :command-rephrase? false})]
    (is (= "codex" (:agent (first (session/agent-command {:command-roots none} "/codex")))))
    (is (some #(and (str/starts-with? % "question: add a --dry-run flag to the deploy script")
                    (str/includes? % "agent$codex") (str/includes? % "agent-registry$ask")
                    (str/includes? % "same Codex session") (str/includes? % "Never dispatch agent$codex afresh"))
              @seen))
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "usage: /codex WORDS")) out) "a bare /codex says its usage")
    (is (= 1 (count (filter #(str/starts-with? % "question: ") @seen))) "and runs no turn")
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "/codex")) out) "listed in /help")))

(deftest team-splits-the-work-across-a-team
  ;; /team WORDS: the session's agent leads a team with the team$* tools — status
  ;; first, a plan before anyone starts, self-contained tasks on the board, one
  ;; wait, every answer verified, then merge and close; bare /team says its usage
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/team-cmd-" (System/nanoTime))) .mkdirs))
        none [(str dir "/none")]
        seen (atom [])
        {:keys [out]} (run-session ["/team" "/team review the parser and its tests in parallel" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                   :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                                            :command-roots none :command-rephrase? false})
        [e] (session/agent-command {:command-roots none} "/team")]
    (is (= ["team$*"] (:tools e)) "bundled, standing for the team$* tools")
    (is (some #(and (str/starts-with? % "question: review the parser and its tests in parallel")
                    (str/includes? % "team$status first") (str/includes? % "team$create")
                    (str/includes? % "team$spawn") (str/includes? % "team$assign")
                    (str/includes? % "task$wakeup once on the dispatcher") (str/includes? % "team$close"))
              @seen)
        "/team WORDS: a turn that plans, starts, assigns, waits once, verifies and closes")
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "usage: /team WORDS")) out) "a bare /team says its usage")
    (is (= 1 (count (filter #(str/starts-with? % "question: ") @seen))) "and runs no turn")
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "/team")
                    (str/includes? (str (second %)) "team$*")) out)
        "listed in /help with its tools")))

(deftest handover-passes-the-work-on-with-a-brief
  ;; byj-gly.386: /handover WORDS — the session's agent finds or starts the
  ;; session that takes the work, writes a brief that stands on its own, sends
  ;; it and stops; a bare /handover says its usage
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/handover-cmd-" (System/nanoTime))) .mkdirs))
        none [(str dir "/none")]
        seen (atom [])
        {:keys [out]} (run-session ["/handover" "/handover to a new session: finish the parser tests" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                   :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                                            :command-roots none :command-rephrase? false})
        [e] (session/agent-command {:command-roots none} "/handover")]
    (is (= ["session$*"] (:tools e)) "bundled, standing for the session$* tools")
    (is (some #(and (str/starts-with? % "question: to a new session: finish the parser tests")
                    (str/includes? % "session$list") (str/includes? % "session$start") (str/includes? % "session$send")
                    (str/includes? % "must stand on its own") (str/includes? % "do no more of that work here"))
              @seen)
        "/handover WORDS: find or start the target, a brief that stands on its own, sent, and the work left alone here")
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "usage: /handover WORDS")) out) "a bare /handover says its usage")
    (is (= 1 (count (filter #(str/starts-with? % "question: ") @seen))) "and runs no turn")
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "/handover")) out) "listed in /help")))

(deftest a-project-defines-its-own-command-for-an-agent
  ;; byj-gly.351: .brainyard/commands/<name>.edn — :command, :agent and
  ;; :purpose are enough; the rest comes from commands/defaults.edn. It is a
  ;; turn as /init is, listed in /help and given to a front end to complete
  (let [{:keys [config]} (command-project {"review" "{:command \"/review\" :agent \"explore\" :purpose \"look over the uncommitted changes and say what is wrong with them\"}"})
        seen (atom [])
        {:keys [out]} (run-session ["/review  the retry change" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "looked"}))
                                   :config config)]
    (is (some #(and (str/includes? % "the retry change") (str/includes? % "agent$explore") (str/includes? % "[/review]")
                    (str/includes? % "look over the uncommitted changes")) @seen)
        "the turn's question is the defaults' template: the words, the purpose, how to drive agent$explore")
    (is (some #(= [:info "/review → the retry change"] %) out))
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "/review")
                    (str/includes? (str (second %)) "agent$explore")) out)
        "/help lists it, with the agent it drives")
    (is (some #(= "/review" (first %)) (session/commands-for config)) "and a front end is given it to complete")))

(deftest a-command-stands-for-tools-or-for-none
  ;; byj-gly.362.1: a command file may name :tools (names or globs) instead
  ;; of an :agent, or neither — a prompt command the agent does with its own
  ;; tools. Each is a turn whose question carries the words, the purpose and
  ;; the kind's default usage; one whose tools are not registered says so and
  ;; runs no turn; /help names what each stands for
  (let [{:keys [config]} (command-project {"tally" "{:command \"/tally\" :tools [\"list-*\" \"grep\"] :purpose \"count what the words name\"}"
                                           "tidy" "{:command \"/tidy\" :purpose \"tidy what the words name\"}"
                                           "ghost" "{:command \"/ghost\" :tools [\"nothing$*\"] :purpose \"x\"}"})
        seen (atom [])
        {:keys [out]} (run-session ["/tally the clj files" "/tidy the README" "/ghost boo" "/help"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                   :config config)]
    (is (some #(and (str/starts-with? % "question: the clj files") (str/includes? % "count what the words name")
                    (str/includes? % "Do it with list-*, grep")) @seen)
        "a tools command: its words, purpose and the tools usage")
    (is (some #(and (str/starts-with? % "question: the README") (str/includes? % "Do it with your own tools")) @seen)
        "a prompt command: the prompt usage")
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "/ghost needs nothing$*")) out)
        "tools that are not registered: said, and no turn")
    (is (= 2 (count (filter #(str/starts-with? % "question: ") @seen))))
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "/tally")
                    (str/includes? (str (second %)) "list-*, grep")) out)
        "/help names the tools")))

(deftest share-hands-words-it-cannot-run-to-its-command-file
  ;; byj-gly.362.1: /share runs its verbs itself, and a command file named
  ;; /share takes any other line as a turn; bare /share stays the status
  (is (every? session/share-takes? [[] ["start"] ["start" "claude:5f0c1d2e"] ["start" "team" "--record"]
                                    ["start" "byj:abc" "--target" "link"] ["stop"] ["pause" "term:work"]
                                    ["kick" "alice"] ["allow"] ["refuse"]
                                    ["record" "on"] ["record" "off" "term:work"]]))
  (is (not-any? session/share-takes? [["start" "my" "claude" "session"] ["stop" "everything" "now"]
                                      ["share" "this"] ["start" "--target"] ["allow" "it"] ["kick"]
                                      ["record"] ["record" "maybe"] ["record" "on" "my" "session"]]))
  (let [{:keys [config]} (command-project {"share" "{:command \"/share\" :tools [\"list-*\"] :purpose \"share what the words ask\"}"})
        seen (atom [])
        {:keys [out]} (run-session ["/share" "/share start my claude session"]
                                   (fn [messages] (swap! seen conj (str (:content (second messages))))
                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                   :config config)]
    (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "not sharing")) out)
        "bare /share: the status, as before")
    (is (= ["question: start my claude session"] (map #(first (str/split-lines %)) (filter #(str/starts-with? % "question: ") @seen)))
        "the words are the turn's question")
    (is (nil? (session/agent-command config "/share start claude:5f0c1d2e")) "a verb /share runs is never the file's"))
  (testing "the bundled commands/share.edn: share$* tools, status first, links reported"
    (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/share-words-" (System/nanoTime))) .mkdirs))
          seen (atom [])
          {:keys [out]} (run-session ["/share make a watch link for my claude session" "/share pause"]
                                     (fn [messages] (swap! seen conj (str (:content (second messages))))
                                       (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                     :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false
                                              :command-roots [(str dir "/none")] :command-rephrase? false})]
      (is (= ["share$*"] (:tools (first (session/agent-command {:command-roots [(str dir "/none")]} "/share it")))))
      (is (some #(and (str/starts-with? % "question: make a watch link for my claude session")
                      (str/includes? % "share$status first") (str/includes? % "share$start")
                      (str/includes? % "call share$login") (str/includes? % "Never run `byj-share login` with bash")) @seen))
      (is (= 1 (count (filter #(str/starts-with? % "question: ") @seen))) "/share pause is the verb, not a turn")
      (is (some #(and (= :info (first %)) (str/starts-with? (str (second %)) "not sharing")) out)
          "and says the share's state, as before"))))

(deftest a-command-makes-its-own-call-before-its-turn
  ;; byj-gly.468: /emacs coach GOAL once answered as a coach without calling
  ;; coach$set, and kept no stance. The command's :before makes the call
  ;; itself, whatever the model then does — here a model that calls nothing
  (let [dir (.getCanonicalPath (doto (java.io.File. (str "target/session-test/coach-cmd-" (System/nanoTime))) .mkdirs))
        cfg {:working-dir dir :project-dir dir :session-id "s-coach-cmd" :persist-session? false :max-iterations 3
             :memory-hint? false :command-roots [(str dir "/none")] :command-rephrase? false}
        seen (atom [])
        ;; every message: the coaching section is the first's user-context,
        ;; the question and the note the second's
        model (fn [messages] (swap! seen conj (str/join "\n" (map :content messages)))
                (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))]
    (testing "coach GOAL starts the stance, beginner when the words say so, and the turn is told"
      (run-session ["/emacs coach write a retry loop, I am a beginner"] model :config cfg)
      (is (= {:goal "write a retry loop, I am a beginner" :level "beginner"} (select-keys (coach/stance cfg) [:goal :level]))
          "set though the model called nothing")
      (is (some #(str/includes? % "[/emacs] Before this turn the command itself called coach$set with {:goal \"write a retry loop, I am a beginner\", :level \"beginner\"}") @seen))
      (is (some #(str/includes? % "## Coaching: write a retry loop") @seen)
          "and the coaching section is in that same turn"))
    (testing "another goal, the level coach$set's default"
      (run-session ["/emacs coach parse a CSV file"] model :config cfg)
      (is (= {:goal "parse a CSV file" :level "practitioner"} (select-keys (coach/stance cfg) [:goal :level]))))
    (testing "coach emacs: GOAL coaches Emacs itself (byj-gly.477)"
      (reset! seen [])
      (run-session ["/emacs coach emacs: moving around, 처음이에요"] model :config cfg)
      (is (= {:goal "moving around, 처음이에요" :level "beginner" :subject "emacs"}
             (select-keys (coach/stance cfg) [:goal :level :subject])))
      (is (some #(str/includes? % "## Coaching Emacs: moving around") @seen) "the Emacs section, not the code's")
      (run-session ["/emacs coach parse a CSV file"] model :config cfg)
      (is (= "code" (:subject (coach/stance cfg))) "a goal not about Emacs is the code's"))
    (testing "coach stop ends it"
      (run-session ["/emacs coach stop"] model :config cfg)
      (is (nil? (coach/stance cfg)))
      (is (str/includes? (last @seen) ":stopped \"parse a CSV file\"")))
    (testing "words no entry matches make no call"
      (reset! seen [])
      (run-session ["/emacs show me the buffer"] model :config cfg)
      (is (not-any? #(str/includes? % "Before this turn the command itself") @seen)))))

(deftest a-commands-before-is-checked-with-its-file
  (let [{:keys [config]} (command-project
                          {"ok" "{:command \"/ok\" :tools [\"todo$*\"] :purpose \"p\" :before [{:when \"^go (.+)$\" :call \"todo$create\" :args {:title \"$1\"}}]}"
                           "outside" "{:command \"/outside\" :tools [\"todo$*\"] :purpose \"p\" :before [{:when \"^x$\" :call \"bash\"}]}"
                           "badre" "{:command \"/badre\" :tools [\"todo$*\"] :purpose \"p\" :before [{:when \"(\" :call \"todo$create\"}]}"
                           "notvec" "{:command \"/notvec\" :tools [\"todo$*\"] :purpose \"p\" :before {:when \"x\"}}"})
        {:keys [commands problems]} (session/agent-commands config)
        why (into {} (map (fn [{:keys [file problems]}] [(.getName (java.io.File. ^String file)) problems]) problems))]
    (is (contains? commands "/ok"))
    (is (some #(str/includes? % ":call must be one of the command's :tools") (why "outside.edn")))
    (is (some #(str/includes? % ":when must be a regex string") (why "badre.edn")))
    (is (some #(str/includes? % ":before must be a vector of maps") (why "notvec.edn")))))

(deftest a-project-command-without-words-or-a-request-says-how-to-use-it
  ;; byj-gly.351: with no :default-request, a bare command has nothing to ask
  ;; its agent: no turn, its usage instead; with words it is a turn
  (let [{:keys [config]} (command-project {"review" "{:command \"/review\" :agent \"explore\" :purpose \"look over a change\"}"})
        {:keys [out]} (run-session ["/review"] (answer "never asked") :config config)]
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "usage: /review WORDS")) out))
    (is (not (some #(= [:answer "never asked"] %) out)) "no turn ran")))

(deftest a-project-command-replaces-a-bundled-one-and-bad-files-are-reported
  ;; byj-gly.351: the most local entry for a command wins — a project's /init
  ;; drives the agent it names — and a file that is not a command is left out
  ;; and said in /help: an :agent that names none, :tools that are not a
  ;; vector of names, both at once (byj-gly.362.1), a session command's name,
  ;; unreadable EDN
  (let [{:keys [config]} (command-project {"init" "{:command \"/init\" :agent \"explore\" :purpose \"say what this project needs\" :default-request \"Survey the project.\"}"
                                           "badagent" "{:command \"/survey\" :agent \"Not An Agent\" :purpose \"x\"}"
                                           "badtools" "{:command \"/tally\" :tools \"bash\" :purpose \"x\"}"
                                           "both" "{:command \"/both\" :agent \"explore\" :tools [\"bash\"] :purpose \"x\"}"
                                           "help" "{:command \"/help\" :agent \"explore\" :purpose \"x\"}"
                                           "broken" "{:command"})
        {:keys [commands problems shadowed]} (session/agent-commands config)]
    (is (= "explore" (:agent (get commands "/init"))) "the project's /init")
    (is (= [{:command "/init" :dropped "builtin" :kept (:file (get commands "/init"))}] shadowed) "and the bundled one is said hidden")
    (is (= #{"broken.edn" "help.edn" "badagent.edn" "badtools.edn" "both.edn"} (set (map #(.getName (java.io.File. ^String (:file %))) problems))))
    (is (some #(str/includes? % "one of the session's own commands") (mapcat :problems problems)))
    (is (some #(str/includes? % ":agent must name an agent") (mapcat :problems problems)))
    (is (some #(str/includes? % ":tools must be a vector") (mapcat :problems problems)))
    (is (some #(str/includes? % ":agent and :tools cannot both be given") (mapcat :problems problems)))
    (is (not-any? #(contains? commands %) ["/survey" "/tally" "/both"]) "a bad file is not a command")
    (let [seen (atom [])
          {:keys [out]} (run-session ["/init" "/help"]
                                     (fn [messages] (swap! seen conj (str (:content (second messages))))
                                       (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))
                                     :config config)]
      (is (some #(and (str/includes? % "Survey the project.") (str/includes? % "agent$explore")) @seen)
          "/init asks agent$explore the project's default request")
      (is (some #(and (= :info (first %)) (str/includes? (str (second %)) "broken.edn is not a command")) out)
          "/help says which files were left out"))))

(deftest init-without-subagents-says-why
  ;; byj-gly.350: /init needs agent$init; with subagent calls off it is not a
  ;; turn, and the person is told why
  (let [{:keys [out]} (run-session ["/init"] (answer "never asked")
                                   :config {:working-dir "." :max-iterations 2 :persist-session? false
                                            :enable-subagent-calls false})]
    (is (some #(and (= :error (first %)) (str/includes? (str (second %)) "needs agent$init")) out))
    (is (not (some #(= [:answer "never asked"] %) out)) "no turn ran")))

(deftest login-signs-in-through-the-front-ends-terminal
  ;; /login (RFC-006, byj-gly.311): the overview, a target's login with the
  ;; terminal the front end hands over, and what a session with none says
  (let [handed (atom [])
        {:keys [out]} (run-session ["/login" "/login nope" "/logout" "/login openai"] (answer "ok")
                                   :hand-terminal (fn [argv env] (swap! handed conj argv)))
        said (mapv second out)]
    (is (some #(and (str/starts-with? (str %) "Sign-in targets:") (str/includes? % "claude")) said) "the overview")
    (is (some #{"no sign-in target nope — /login lists them"} said))
    (is (some #{"usage: /logout TARGET — /login lists them"} said))
    (is (some #(str/includes? (str %) "OPENAI_API_KEY") said) "an API key: where to put it")
    (is (empty? @handed) "nothing run for those")
    (testing "a CLI target not signed in: its login, run with the terminal the front end handed over"
      (let [bin (.getCanonicalPath (doto (java.io.File. "target/session-test/login-bin") .mkdirs))
            f (java.io.File. bin "claude")]
        (spit f "#!/bin/bash\necho '{\"loggedIn\": false}'\n")
        (.setExecutable f true)
        (let [{:keys [out]} (run-session ["/login claude"] (answer "ok")
                                         :env-fn #(if (= "PATH" %) bin (System/getenv %))
                                         :hand-terminal (fn [argv env] (swap! handed conj argv)))]
          (is (= [["claude" "auth" "login"]] @handed))
          (is (some #{"claude: claude auth login ran — now not signed in."} (map second out))))))
    (is (every? #(some #{%} (map first session/commands)) ["/login" "/logout"]) "listed, so completed and in /help")))

(deftest what-a-sessions-commands-left-running-ends-with-it
  ;; byj-gly.315.16: a server a turn starts with & outlived the session
  (let [mark (str "byjsessbg" (System/nanoTime))
        {:keys [out]} (run-session [(str ":bash {:command \"sh -c 'sleep 30; true " mark "' >/dev/null 2>&1 & echo ok\"}")]
                                   (answer "ok")
                                   :config {:working-dir "." :max-iterations 2 :persist-session? false
                                            :permission-mode :auto-approve :session-id (str "s-" mark)})]
    (Thread/sleep 300)
    (is (str/blank? (:out (jolt.process/sh {:out :string :err :string} "pgrep" "-f" mark))) "nothing of it is left")
    (is (some #(str/includes? (str (second %)) "stopped 1 process group(s) its commands left running") out))))

;; byj-ng7.13: /doctor and byj doctor, whole, with what it reads of the
;; machine — PATH, Claude Code's usage — given
(deftest the-doctor-report-says-each-part-in-order
  (let [dir (.getCanonicalPath (doto (io/file "target/session-test/doctor") .mkdirs))
        _ (doseq [f (reverse (file-seq (io/file dir ".brainyard")))] (.delete f))
        ctx {:config {:project-dir dir :working-dir dir}
             :doctor-check-fn (fn [_] {:text "Tools:\n  bd 1.0"})
             :install-lines-fn (fn [checkout] [{:text (str "  byj v9 (" (or checkout "binary") ")")}])
             :checkout nil
             :claude-usage-fn (fn [] {:logged-in? true
                                      :account {:email "a@example.invalid" :plan "max"
                                                :refresh-expires-at (+ (System/currentTimeMillis) (* 3 86400000) 3600000)}
                                      :limits [{:label "5h" :percent 40}]})}
        r (session/doctor-report ctx)]
    (is (str/starts-with? r "Tools:\n  bd 1.0\n\nInstalled:\n  byj v9 (binary)\n\nClaude Code:\n  signed in as a@example.invalid, plan max"))
    (is (str/includes? r "refresh token: 3 day(s) left — /login claude soon"))
    (is (re-find #"Claude Code subscription \(max, a@example.invalid\):\n  5h .* 40%" r))
    (is (not (str/includes? r "Loops")) "a project with no loop of its own says nothing of loops")
    (testing "the project's own loop, between the project and what is installed"
      (let [[k o & cs] (project-loops/bundled-tree "react")]
        (project-loops/write-loop! dir "react" (into [k (assoc o :doc "mine")] cs)
                                   {:action :accept :based-on {:loop "react" :hash "000000000000" :byj "v0"}}))
      (let [r (session/doctor-report (assoc ctx :checkout "/src/byj"))]
        (is (re-find #"(?s)bd 1.0\n\nLoops \(the project's own.*\n  react: the project's .brainyard/loops/react.edn was made from an older bundled react.*byj loops propose --bundled react.*\n\nInstalled \(this run is from the checkout /src/byj\):\n  byj v9 \(/src/byj\)" r))))
    (testing "signed out"
      (is (str/includes? (session/doctor-report (assoc ctx :claude-usage-fn (fn [] {:logged-in? false})))
                         "Claude Code:\n  not signed in — /login claude")))))
