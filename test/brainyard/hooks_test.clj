(ns brainyard.hooks-test
  "Hooks (RFC-011): the bus, the tool hooks around call-tool, the loop's hook
  points, the built-in loop guard, and user hooks with Tool Lisp bodies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.hookbox :as hookbox]
            [brainyard.config :as config]
            [brainyard.hooks :as hooks]
            [brainyard.json :as json]
            [brainyard.session :as session]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file "target/hooks-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- entry [id event handler & {:as more}]
  (merge {:id id :event event :handler handler} more))

(defn- ctx-with [entries]
  (let [events (atom [])]
    {:hooks (hooks/registry entries)
     :on-event (fn [e d] (swap! events conj [e d]))
     :events events}))

;; --- the bus ------------------------------------------------------------------------

(deftest the-bus
  (testing "priority orders handlers, registration order breaks ties, a throwing match is false"
    (let [log (atom [])
          ctx (ctx-with [(entry "a" :agent.ask/pre (fn [_ _] (swap! log conj :a)))
                         (entry "b" :agent.ask/pre (fn [_ _] (swap! log conj :b)) :priority 10)
                         (entry "c" :agent.ask/pre (fn [_ _] (swap! log conj :c)))
                         (entry "d" :agent.ask/pre (fn [_ _] (swap! log conj :d)) :match (fn [_] (throw (ex-info "x" {}))))])]
      (hooks/fire! ctx :agent.ask/pre {})
      (is (= [:b :a :c] @log))))
  (testing "registering an id again replaces it"
    (let [reg (hooks/registry [(entry "a" :agent.ask/pre (constantly 1))])]
      (hooks/register! reg (entry "a" :agent.ask/pre (constantly 2)))
      (is (= [2] (map #((:handler %) nil nil) (hooks/entries reg :agent.ask/pre))))
      (is (true? (hooks/unregister! reg :agent.ask/pre "a")))
      (is (false? (hooks/unregister! reg :agent.ask/pre "a")))))
  (testing "a throwing handler is reported and skipped"
    (let [log (atom [])
          ctx (ctx-with [(entry "boom" :agent.ask/pre (fn [_ _] (throw (ex-info "boom" {}))) :priority 1)
                         (entry "ok" :agent.ask/pre (fn [_ _] (swap! log conj :ok)))])]
      (hooks/fire! ctx :agent.ask/pre {})
      (is (= [:ok] @log))
      (is (= [[:hooks/handler-failed {:event :agent.ask/pre :id "boom" :source nil :error "boom"}]] @(:events ctx)))))
  (testing "decisions: allow abstains, the first valid decision wins, malformed ones are skipped"
    (let [seen (atom [])
          h (fn [id d] (entry id :agent.tool-use/pre (fn [_ _] (swap! seen conj id) d)))]
      (is (= {:result :allow} (hooks/fire-decision! (ctx-with [(h "a" nil) (h "b" {:result :allow})]) :agent.tool-use/pre {})))
      (reset! seen [])
      (let [ctx (ctx-with [(h "allow" {:result :allow}) (h "bad" {:result :replace})
                           (h "block" {:result :block :reason "no"}) (h "later" {:result :replace :replacement 1})])]
        (is (= {:result :block :reason "no" :by "block"} (hooks/fire-decision! ctx :agent.tool-use/pre {})))
        (is (= ["allow" "bad" "block"] @seen) "handlers after the winner do not run")
        (is (= :hooks/malformed-decision (ffirst @(:events ctx)))))))
  (testing "a handler runs one level deeper"
    (let [depth (atom nil)]
      (hooks/fire! (ctx-with [(entry "d" :agent.ask/pre (fn [_ _] (reset! depth hooks/*depth*)))]) :agent.ask/pre {})
      (is (= 1 @depth)))))

;; --- tool hooks -------------------------------------------------------------------------

(def ^:private echo {:name "echo" :input-schema [:map [:text :string]] :fn (fn [args _] {:echo (:text args)})})

(deftest tool-hooks
  (let [posts (atom [])
        post (entry "post" :agent.tool-use/post (fn [p _] (swap! posts conj p)))
        call (fn [decision args]
               (reset! posts [])
               (tools/call-tool echo args (ctx-with [post (entry "gate" :agent.tool-use/pre (fn [_ _] decision))])))]
    (is (= {:echo "hi"} (call nil {:text "hi"})))
    (is (= {:tool-name "echo" :args {:text "hi"} :result {:echo "hi"}} (dissoc (first @posts) :call-id)))
    (testing "block: the tool does not run and the call returns brainyard's blocked result"
      (is (= {:hook-blocked true :reason "nope" :by "gate" :answer "stopped"}
             (call {:result :block :reason "nope" :answer "stopped"} {:text "hi"})))
      (is (= {:hook-blocked true :blocked-by "gate"} (select-keys (first @posts) [:hook-blocked :blocked-by]))))
    (testing "replace: the replacement is the result"
      (is (= {:cached 1} (call {:result :replace :replacement {:cached 1}} {:text "hi"})))
      (is (true? (:hook-replaced (first @posts)))))
    (testing "modify-args: the tool runs on the new args, checked again"
      (is (= {:echo "HI"} (call {:result :modify-args :args {:text "HI"}} {:text "hi"})))
      (is (= {:text "HI"} (:effective-args (first @posts))))
      (is (str/includes? (:error-message (call {:result :modify-args :args {:other 1}} {:text "hi"})) "missing required `text`")))
    (testing "invalid args fire :agent.tool-use/rejected, and no pre or post"
      (let [rejected (atom nil) pre (atom 0)
            ctx (ctx-with [(entry "r" :agent.tool-use/rejected (fn [p _] (reset! rejected p)))
                           (entry "p" :agent.tool-use/pre (fn [_ _] (swap! pre inc) nil))])]
        (tools/call-tool echo {} ctx)
        (is (= {:tool-name "echo" :reason :invalid-args} (select-keys @rejected [:tool-name :reason])))
        (is (= 0 @pre))))))

;; --- the loop -------------------------------------------------------------------------

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(defn- ask [dir replies & {:as opts}]
  (let [events (atom [])
        prompts (atom 0)
        queue (atom (vec replies))
        r (agent/ask "q" (merge-with merge
                                     {:lm {:provider :mock :format :mock
                                           :responses (fn [_] (swap! prompts inc)
                                                        (let [[x] (swap-vals! queue #(vec (rest %)))] (first x)))}
                                      :config {:working-dir dir :project-dir dir :max-iterations 6}
                                      :on-event (fn [e d] (swap! events conj [e d]))}
                                     opts))]
    (assoc r :events @events :llm-calls @prompts)))

(defn- ls [path] {:tool-calls [{:tool-name "list-dir" :tool-args {:path path}}]})

(deftest a-turn-fires-brainyards-events
  (let [dir (fresh-dir "events")
        fired (atom [])
        record (fn [ev] (entry (str ev) ev (fn [p _] (swap! fired conj [ev p]))))
        r (ask dir [(reply (ls ".")) (reply {:answer "done"})]
               :hooks (mapv record (keys hooks/event-catalog)))]
    (is (= "done" (:answer r)))
    (is (= [:agent.ask/pre
            :agent.iteration/pre :agent.dspy-action/pre :agent.dspy-action/post
            :agent.tool-calls/pre :agent.tool-use/pre :agent.tool-use/post :agent.tool-calls/post :agent.iteration/post
            :agent.iteration/pre :agent.dspy-action/pre :agent.dspy-action/post :agent.iteration/post
            :agent.ask/post]
           (map first @fired)))
    (testing "every payload carries only its catalogued keys"
      (doseq [[ev p] @fired]
        (is (every? (get-in hooks/event-catalog [ev :keys]) (keys p)) (str ev " " (keys p)))))
    (is (= {:input "q" :terminated-by :answer-channel}
           (select-keys (second (last @fired)) [:input :terminated-by])))
    (is (= "done" (get-in (second (last @fired)) [:result :answer])))))

(deftest a-blocked-tool-call-ends-the-turn
  (let [dir (fresh-dir "blocked")
        r (ask dir [(reply (ls ".")) (reply {:answer "never"})]
               :hooks [(entry "deny" :agent.tool-use/pre (fn [_ _] {:result :block :reason "not today"}))])]
    (is (= "*(Tool dispatch blocked: not today)*" (:answer r)))
    (is (= :tool-blocked-by-hook (:terminated-by r)))
    (is (= 1 (:llm-calls r)) "no LLM call after the block")
    (is (some #(= [:agent.tools/blocked {:reason "not today" :by "deny"}] %) (:events r)))))

(deftest the-loop-guard-is-a-builtin-hook
  (let [dir (fresh-dir "guard")]
    (testing "the third identical call is blocked; the answer carries the latest result"
      (let [r (ask dir [(reply (ls ".")) (reply (ls ".")) (reply (ls ".")) (reply {:answer "never"})])]
        (is (= :tool-blocked-by-hook (:terminated-by r)))
        (is (str/starts-with? (:answer r) "*(Loop guard: detected 3 consecutive identical tool calls."))
        (is (str/includes? (:answer r) ":entries"))
        (is (= 3 (:llm-calls r)))))
    (testing "different args are not a loop"
      (is (= "done" (:answer (ask dir [(reply (ls ".")) (reply (ls "./")) (reply (ls ".")) (reply {:answer "done"})])))))
    (testing "the idempotent path reads its tool set from the spec's :opts"
      (let [guard (update (hookbox/load-builtin "redundant-tool-call-guard") :handler
                          (fn [_] (fn [p ctx] (hookbox/redundant-tool-call-guard p ctx {:window 2 :idempotent-tools #{"list-dir"}}))))
            r (ask dir [(reply (ls "a")) (reply (ls "b")) (reply (ls "c")) (reply {:answer "never"})]
                   :config {:builtin-hooks? false} :hooks [guard])]
        (is (str/starts-with? (:answer r) "*(Loop guard: an idempotent read-only tool"))))
    (testing ":builtin-hooks? false removes it"
      (is (= "done" (:answer (ask dir [(reply (ls ".")) (reply (ls ".")) (reply (ls ".")) (reply {:answer "done"})]
                                  :config {:builtin-hooks? false})))))))

;; --- user hooks ----------------------------------------------------------------------

(def ^:private log-body
  "(fn [event] (write-file {:path \"hook.log\" :content (str (:tool-name event) \" \" (:path (:args event)) \"\\n\") :append true}))")

(deftest user-hooks
  (let [dir (fresh-dir "user")
        {:keys [tools hooks] :as ctx} (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        cmd (fn [n args] (tools/call-tool (get @tools n) args ctx))
        log (io/file dir "hook.log")]
    (testing "hook$create validates, persists brainyard's layout, and registers at once"
      ;; the path reported back is absolute, as every directory this layer
      ;; hands on is (RFC-013)
      (is (= {:id "list-log" :event "agent.tool-use/post"
              :persisted (str (config/canonical-dir dir) "/.brainyard/hooks/list-log.edn")}
             (cmd "hook$create" {:id "list-log" :event "agent.tool-use/post" :match {"tool-name" "list-dir"}
                                 :doc "log listings" :body log-body})))
      (is (.isFile (io/file dir ".brainyard/hooks/list-log.clj")))
      (cmd "list-dir" {:path "."})
      (cmd "read-file" {:path "hook.log"})
      (is (= "list-dir .\n" (slurp log)) "matched list-dir only; the hook's own write-file did not re-fire it"))
    (testing "every problem is reported"
      (let [{:keys [error]} (cmd "hook$create" {:id "Bad" :event "agent.tool-use/pre" :match {} :on-error "maybe"
                                                :body "(fn [e] (slurp e))"})]
        (is (str/includes? error "id must be lowercase-kebab"))
        (is (str/includes? error ":on-error must be allow or block"))
        (is (str/includes? error ":match is required (declarative scope map); use {:global true} for an unscoped hook"))
        (is (str/includes? error "`slurp` is not allowed in Tool Lisp")))
      (is (str/includes? (:error (cmd "hook$create" {:id "x" :event "agent.ask/pre" :match {"path" "a"} :body "(fn [e] nil)"}))
                         ":match must scope by :defagent-type, :agent-id, or :tool-name")))
    (testing "list, read, events, validate"
      (is (= [{:id "list-log" :event "agent.tool-use/post" :priority 0 :source :user-hook :match {:tool-name "list-dir"} :doc "log listings"}
              {:id "redundant-tool-call-guard" :event "agent.tool-use/pre" :priority 100 :source :builtin
               :doc (:doc (first hookbox/builtin-hooks))}
              ;; the permission gates are built-in hooks too (RFC-012)
              {:id "tool-deny-gate" :event "agent.tool-use/pre" :priority 95 :source :builtin
               :doc (:doc (second hookbox/builtin-hooks))}
              {:id "bash-command-gate" :event "agent.tool-use/pre" :priority 92 :source :builtin
               :doc (:doc (nth hookbox/builtin-hooks 2))}
              {:id "path-permission-gate" :event "agent.tool-use/pre" :priority 90 :source :builtin
               :doc (:doc (nth hookbox/builtin-hooks 3))}
              {:id "tangled-file-guard" :event "agent.tool-use/pre" :priority 87 :source :builtin
               :doc (:doc (nth hookbox/builtin-hooks 4))}
              {:id "tool-permission-gate" :event "agent.tool-use/pre" :priority 85 :source :builtin
               :doc (:doc (nth hookbox/builtin-hooks 5))}
              {:id "task-poll-park" :event "agent.tool-use/pre" :priority 50 :source :builtin
               :doc (:doc (nth hookbox/builtin-hooks 6))}]
             (:hooks (cmd "hook$list" {}))))
      (is (= log-body (:body (cmd "hook$read" {:id "list-log"}))))
      (is (= {:error "no user hook named \"nope\""} (cmd "hook$read" {:id "nope"})))
      (is (= {:event "agent.tool-use/pre" :gated? true}
             (select-keys (first (filter #(= "agent.tool-use/pre" (:event %)) (:events (cmd "hook$events" {}))))
                          [:event :gated?])))
      (is (= {:valid true :collision true :sample-result {:path "sample.log" :chars 4}}
             (select-keys (cmd "hook$validate" {:id "list-log" :event "agent.tool-use/post" :match {"global" true}
                                                :body "(fn [e] (write-file {:path \"sample.log\" :content (:tool-name e)}))"
                                                :sample {"tool-name" "bash"}})
                          [:valid :collision :sample-result])))
      (is (= "bash" (slurp (io/file dir "sample.log")))))
    (testing "a new ctx loads hooks from disk; a bad file is skipped and reported"
      (spit (io/file dir ".brainyard/hooks/broken.edn") "{:id \"broken\" :event :agent.ask/pre :match {:global true} :body \"(fn [e] (eval e))\"}")
      (let [events (atom [])
            ctx2 (agent/make-ctx {:config {:working-dir dir :project-dir dir} :on-event (fn [e d] (swap! events conj [e d]))})]
        (is (= ["list-log"] (map :id (filter #(= :user-hook (:source %)) (hooks/entries (:hooks ctx2))))))
        (is (= :hooks/user-hook-skipped (ffirst @events)))
        (.delete (io/file dir ".brainyard/hooks/broken.edn"))))
    (testing ":enable-user-hooks false stops them firing"
      (let [ctx3 (agent/make-ctx {:config {:working-dir dir :project-dir dir :enable-user-hooks false}})]
        (tools/call-tool (get @(:tools ctx3) "list-dir") {:path "src"} ctx3)
        (is (= "list-dir .\n" (slurp log)))))
    (testing "hook$delete unregisters and removes the files"
      (is (= {:deleted "list-log"} (cmd "hook$delete" {:id "list-log"})))
      (is (not (.exists (io/file dir ".brainyard/hooks/list-log.edn"))))
      (cmd "list-dir" {:path "."})
      (is (= "list-dir .\n" (slurp log))))))

(deftest a-user-hook-observes-a-turn
  (let [dir (fresh-dir "turn")]
    (spit (doto (io/file dir ".brainyard/hooks/turn-log.edn") (-> .getParentFile .mkdirs))
          "{:id \"turn-log\" :event :agent.ask/post :match {:agent-id \"react-agent\"} :priority 0}")
    (spit (io/file dir ".brainyard/hooks/turn-log.clj")
          "(fn [{:keys [input result]}] (write-file {:path \"turns.log\" :content (str input \" => \" (:answer result))}))")
    (is (= "done" (:answer (ask dir [(reply {:answer "done"})]))))
    (is (= "q => done" (slurp (io/file dir "turns.log"))))))

(deftest user-hooks-gate-tool-calls
  (let [dir (fresh-dir "gate")
        events (atom [])
        ;; :bash-ask-commands [] — the built-in command gate would ask about
        ;; `rm` first (byj-gly.285.4); this is about a user hook refusing it
        {:keys [tools] :as ctx} (agent/make-ctx {:config {:working-dir dir :project-dir dir :bash-ask-commands []}
                                                 :on-event (fn [e d] (swap! events conj [e d]))})
        cmd (fn [n args] (tools/call-tool (get @tools n) args ctx))
        create (fn [args] (cmd "hook$create" (merge {:event "agent.tool-use/pre"} args)))]
    (testing ":replace refuses a call and the caller sees why"
      (is (:persisted (create {:id "no-rm" :match {"tool-name" "bash"}
                               :body "(fn [{:keys [args]}] (when (clojure.string/includes? (:command args) \"rm \") {:result \"replace\" :replacement {:error \"rm is not allowed here\"}}))"})))
      (is (= {:error "rm is not allowed here"} (cmd "bash" {:command "rm -rf x"})))
      (is (= "hi\n" (:output (cmd "bash" {:command "echo hi"})))))
    (testing ":modify-args rewrites the call"
      (create {:id "root-listing" :match {"tool-name" "list-dir"} :body "(fn [e] {:result :modify-args :args {:path \".brainyard\"}})"})
      (is (= {:path ".brainyard" :entries ["hooks/"]} (cmd "list-dir" {:path "."}))))
    (testing "the decision survives a reload from disk"
      (let [ctx2 (agent/make-ctx {:config {:working-dir dir :project-dir dir :bash-ask-commands []}})]
        (is (= {:error "rm is not allowed here"} (tools/call-tool (get @(:tools ctx2) "bash") {:command "rm x"} ctx2)))))
    (testing "a failing body allows by default and is reported; :on-error block refuses"
      (create {:id "root-listing" :match {"tool-name" "list-dir"} :body "(fn [e] (clojure.string/upper-case (:args e)))"})
      (reset! events [])
      (is (vector? (:entries (cmd "list-dir" {:path "."}))))
      (is (= :hooks/handler-failed (ffirst @events)))
      (create {:id "root-listing" :match {"tool-name" "list-dir"} :on-error "block" :body "(fn [e] (clojure.string/upper-case (:args e)))"})
      (let [r (cmd "list-dir" {:path "."})]
        (is (true? (:hook-blocked r)))
        (is (str/starts-with? (:reason r) "hook root-listing failed: clojure.string/upper-case expects a string")))
      (is (= :block (:on-error (cmd "hook$read" {:id "root-listing"}))))
      (cmd "hook$delete" {:id "root-listing"}))
    (testing "a malformed decision is reported and the call goes ahead"
      (create {:id "sloppy" :match {"tool-name" "list-dir"} :body "(fn [e] {:result :replace})"})
      (reset! events [])
      (is (vector? (:entries (cmd "list-dir" {:path "."}))))
      (is (= :hooks/malformed-decision (ffirst @events)))
      (cmd "hook$delete" {:id "sloppy"}))
    (testing "hook$validate with a sample returns the decision"
      (is (= {:result "replace" :replacement {:error "rm is not allowed here"}}
             (:sample-result (cmd "hook$validate" {:id "t" :event "agent.tool-use/pre" :match {"tool-name" "bash"}
                                                   :body (:body (cmd "hook$read" {:id "no-rm"}))
                                                   :sample {"tool-name" "bash" "args" {"command" "rm y"}}})))))
    (testing "in a turn: :block ends it with the hook's answer; built-in guards still decide first"
      (create {:id "stop-bash" :match {"tool-name" "bash"} :priority 50
               :body "(fn [e] {:result :block :reason \"no shell\" :answer \"I may not run shell commands here.\"})"})
      (let [r (agent/ask "q" {:lm {:provider :mock :format :mock
                                   :responses (atom [(json/write-str {:reasoning "" :answer ""
                                                                      :tool-calls [{:tool-name "bash" :tool-args {:command "ls"}}]})])}
                              :config {:working-dir dir :project-dir dir}})]
        (is (= "I may not run shell commands here." (:answer r)))
        (is (= :tool-blocked-by-hook (:terminated-by r))))
      (is (= ["redundant-tool-call-guard" "tool-deny-gate" "bash-command-gate" "path-permission-gate" "tangled-file-guard" "tool-permission-gate" "task-poll-park" "stop-bash" "no-rm"]
             (map :id (sort-by (comp - :priority) (hooks/entries (:hooks (agent/make-ctx {:config {:working-dir dir :project-dir dir}}))
                                                                 :agent.tool-use/pre))))))))

;; --- the session's own events (byj-gly.334) ------------------------------------------

(defn- run-session [dir lines & {:as opts}]
  (let [queue (atom (vec lines))]
    (session/run (merge {:lm {:provider :mock :format :mock
                              :responses (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer "ok"}))}
                         :config {:working-dir dir :project-dir dir :max-iterations 3 :session-id "s-1"}
                         :read-input (fn [_] (let [[l] @queue] (swap! queue #(vec (rest %))) l))
                         :emit (fn [_ _])}
                        opts))))

(deftest a-session-fires-created-or-resumed-and-closed
  (let [dir (.getCanonicalPath (io/file (fresh-dir "session")))
        fired (atom [])
        record (fn [ev] (entry (str ev) ev (fn [p _] (swap! fired conj [ev p]))))
        all (mapv record (keys hooks/event-catalog))
        sessions (fn [] (filter #(= "agent.session" (namespace (first %))) @fired))]
    (testing "a fresh session: created before its first turn, closed after its last"
      (run-session dir ["hello"] :hooks all)
      (is (= :agent.session/created (first (first @fired))) "before anything else")
      (is (= :agent.session/closed (first (last @fired))) "and after everything")
      (is (= [:agent.session/created :agent.session/closed] (map first (sessions))))
      (let [[[_ created] [_ closed]] (sessions)]
        (is (= "s-1" (:session-id created)))
        (is (= {:session-id "s-1" :project-dir dir :working-dir dir :turn-count 0}
               (select-keys (:session created) [:session-id :project-dir :working-dir :turn-count])))
        (is (= 1 (get-in closed [:session :turn-count])) "closed sees the session as it ended")))
    (testing "every payload within its catalogued keys"
      (doseq [[ev p] (sessions)]
        (is (every? (get-in hooks/event-catalog [ev :keys]) (keys p)) (str ev " " (keys p)))))
    (testing "the same session again, from its snapshot: resumed, not created"
      (reset! fired [])
      (run-session dir ["again"] :hooks all
                   :config {:working-dir dir :project-dir dir :max-iterations 3 :session-id "s-1" :resume "s-1"})
      (is (= [:agent.session/resumed :agent.session/closed] (map first (sessions))))
      (is (= {:resumed-from "s-1" :turn-count 1}
             (select-keys (:session (second (first (sessions)))) [:resumed-from :turn-count]))))
    (testing "a session whose input fails is closed all the same"
      (reset! fired [])
      (try (run-session dir ["x"] :hooks all :read-input (fn [_] (throw (ex-info "the terminal went away" {}))))
           (catch Exception _ nil))
      (is (= [:agent.session/created :agent.session/closed] (map first (sessions)))))
    (testing "a user hook from disk sees it"
      (spit (doto (io/file dir ".brainyard/hooks/session-log.edn") (-> .getParentFile .mkdirs))
            "{:id \"session-log\" :event :agent.session/created :match {:global true} :priority 0}")
      (spit (io/file dir ".brainyard/hooks/session-log.clj")
            "(fn [{:keys [session-id event-key]}] (write-file {:path \"sessions.log\" :content (str event-key \" \" session-id)}))")
      (run-session dir [] :config {:working-dir dir :project-dir dir :max-iterations 3 :session-id "s-2"})
      (is (= ":agent.session/created s-2" (slurp (io/file dir "sessions.log")))))))
