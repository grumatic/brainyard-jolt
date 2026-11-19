;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.acp-test
  "`byj acp`, the Agent Client Protocol front end (RFC-053, byj-g5n.4): an ACP
  v1 client's view of byj, in process over the connection's line fns, and once
  as a process for what only a process shows — its stdout, its end."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.acp :as acp]
            [brainyard.channel :as channel]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.sdk :as sdk]
            [brainyard.tools :as tools]
            [jolt.process :as p]))

(defn- fresh-dir [name]
  (let [d (io/file "target/acp-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- script!
  "A scripted mock's replies (`launch/mock-lm`), written beside the project."
  [dir replies]
  (let [f (io/file dir "replies.edn")]
    (spit f (pr-str (mapv #(merge {:reasoning "r" :tool-calls [] :answer ""} %) replies)))
    (str "script:" (.getPath f))))

(defn- client
  "An ACP client in process: every message byj sends is kept; a request byj
  makes is answered by `(answer msg)`, which returns the result."
  [& {:keys [answer] :or {answer (constantly nil)}}]
  (let [seen (atom [])
        box (atom nil)
        conn (acp/connection
              (fn [line]
                (let [m (json/read-str line)]
                  (swap! seen conj m)
                  (when (and (:method m) (some? (:id m)))
                    ;; answered off the writer, as a client reading its pipe would
                    (fx/run-detached
                     (fx/task-of #(acp/receive! @box (json/write-str {:jsonrpc "2.0" :id (:id m) :result (answer m)})))))))
              (acp/handlers)
              (acp/notification-handlers))]
    (reset! box conn)
    {:conn conn :seen seen :next (atom 0)}))

(defn- call
  "Send a request and wait for its answer."
  [{:keys [conn seen next]} method params & [ms]]
  (let [id (swap! next inc)]
    (acp/receive! conn (json/write-str {:jsonrpc "2.0" :id id :method method :params params}))
    (loop [n 0]
      (or (some #(when (and (= id (:id %)) (nil? (:method %))) %) @seen)
          (if (> n (quot (or ms 20000) 10))
            (throw (ex-info (str "no answer to " method) {:seen @seen}))
            (do (Thread/sleep 10) (recur (inc n))))))))

(defn- updates [{:keys [seen]} sid]
  (keep #(when (and (= "session/update" (:method %)) (= sid (get-in % [:params :sessionId])))
           (get-in % [:params :update]))
        @seen))

(defn- new-session [c dir model & [more]]
  (:result (call c "session/new" {:cwd dir :mcpServers []
                                  :_meta {:brainyard (merge {:provider "mock" :model model
                                                             :config {:builtin-agents? false}}
                                                            more)}})))

(deftest initialize-says-what-it-is
  (let [c (client)
        r (:result (call c "initialize" {:protocolVersion 1 :clientCapabilities {}}))]
    (is (= 1 (:protocolVersion r)))
    (is (= "byj" (get-in r [:agentInfo :name])))
    (is (true? (get-in r [:agentCapabilities :loadSession])))
    (testing "a client asking for a later version is answered with ours"
      (is (= 1 (get-in (call c "initialize" {:protocolVersion 2}) [:result :protocolVersion]))))
    (testing "an unknown method is -32601, and bad JSON -32700"
      (is (= -32601 (get-in (call c "session/fly" {}) [:error :code])))
      (acp/receive! (:conn c) "{not json")
      (is (some #(= -32700 (get-in % [:error :code])) @(:seen c))))))

(deftest a-prompt-streams-its-turn-and-ends
  (let [dir (fresh-dir "prompt")
        _ (spit (io/file dir "notes.md") "the notes")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        {:keys [sessionId modes]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "read-file" :tool-args {:path "notes.md"}}]}
                                                                    {:answer "the notes say: the notes" :goal-achieved true}]))]
    (try
      (testing "the session is byj's, its modes the permission modes"
        (is (re-matches #"\d+-[0-9a-f]{8}" sessionId))
        (is (= #{"auto" "ask-each-time" "auto-approve" "deny-by-default"} (set (map :id (:availableModes modes))))))
      (let [r (:result (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "what do the notes say?"}]}))
            us (updates c sessionId)]
        (testing "the turn ends end_turn; with no extension asked for, no _meta"
          (is (= "end_turn" (:stopReason r)))
          (is (nil? (:_meta r))))
        (testing "the tool call, read, then its result"
          (let [call (first (filter #(= "tool_call" (:sessionUpdate %)) us))
                done (first (filter #(= "tool_call_update" (:sessionUpdate %)) us))]
            (is (= "read" (:kind call)))
            (is (str/starts-with? (:title call) "read-file notes.md"))
            (is (= (:toolCallId call) (:toolCallId done)))
            (is (= "completed" (:status done)))
            (is (str/includes? (get-in done [:content 0 :content :text]) "the notes"))))
        (testing "the answer as the agent's message"
          (is (some #(and (= "agent_message_chunk" (:sessionUpdate %))
                          (= "the notes say: the notes" (get-in % [:content :text])))
                    us))))
      (finally (acp/end! (:conn c))))))

(deftest an-editors-fs-is-what-the-file-tools-read-and-write
  ;; byj-gly.464: a client that advertises fs.readTextFile/writeTextFile (an
  ;; editor: agent-shell, Zed) answers reads from its buffers, unsaved edits
  ;; included, and takes the writes, for files in the session's cwd
  (let [dir (fresh-dir "client-fs")
        other (fresh-dir "client-fs-other")
        a (io/file dir "a.txt")
        out (io/file other "o.txt")
        asked (atom [])
        _ (spit a "disk text\n")
        _ (spit (io/file dir "d.txt") "on disk alone\n")
        _ (spit out "outside\n")
        c (client :answer (fn [m]
                            (case (:method m)
                              "fs/read_text_file" (do (swap! asked conj [:read (get-in m [:params :path])])
                                                      ;; open in the editor, edited and not saved; d.txt not open
                                                      (when (= (str a) (get-in m [:params :path])) {:content "buffer text\n"}))
                              "fs/write_text_file" (do (swap! asked conj [:write (get-in m [:params :path]) (get-in m [:params :content])])
                                                       nil)
                              "session/request_permission" {:outcome {:outcome "selected" :optionId "allow-once"}}
                              nil)))
        _ (call c "initialize" {:protocolVersion 1 :clientCapabilities {:fs {:readTextFile true :writeTextFile true}}})
        {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "read-file" :tool-args {:path "a.txt"}}]}
                                                             {:tool-calls [{:tool-name "update-file" :tool-args {:path "a.txt" :pattern "buffer" :replacement "edited"}}]}
                                                             {:tool-calls [{:tool-name "write-file" :tool-args {:path "c.txt" :content "new\n"}}]}
                                                             {:tool-calls [{:tool-name "read-file" :tool-args {:path "d.txt"}}]}
                                                             {:tool-calls [{:tool-name "read-file" :tool-args {:path (str out)}}]}
                                                             {:answer "done" :goal-achieved true}])
                                         {:config {:builtin-agents? false :path-permission? false}})]
    (try
      (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "edit a"}]})
      (let [results (keep #(when (= "tool_call_update" (:sessionUpdate %)) (get-in % [:content 0 :content :text]))
                          (updates c sessionId))]
        (testing "a read is the editor's buffer, unsaved edits included"
          (is (str/includes? (first results) "buffer text")))
        (testing "an update reads and writes through the editor; the disk is left to it"
          (is (some #{[:write (str a) "edited text\n"]} @asked))
          (is (= "disk text\n" (slurp a))))
        (testing "a write goes to the editor"
          (is (some #{[:write (str (io/file dir "c.txt")) "new\n"]} @asked))
          (is (not (.exists (io/file dir "c.txt")))))
        (testing "a file the editor cannot answer for is read from the disk"
          (is (some #{[:read (str (io/file dir "d.txt"))]} @asked))
          (is (str/includes? (nth results 3) "on disk alone")))
        (testing "a file outside the session's cwd is the disk's, never asked of the editor"
          (is (str/includes? (nth results 4) "outside"))
          (is (not-any? #(= (str out) (second %)) @asked))))
      (finally (acp/end! (:conn c)))))
  (testing "a client that offers no fs: the disk, and nothing asked of it"
    (let [dir (fresh-dir "client-no-fs")
          _ (spit (io/file dir "a.txt") "disk text\n")
          c (client :answer (constantly nil))
          _ (call c "initialize" {:protocolVersion 1 :clientCapabilities {:fs {:readTextFile false :writeTextFile false}}})
          {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "read-file" :tool-args {:path "a.txt"}}]}
                                                               {:answer "done" :goal-achieved true}]))]
      (try
        (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "read a"}]})
        (is (str/includes? (str (updates c sessionId)) "disk text"))
        (is (not-any? #(some-> (:method %) (str/starts-with? "fs/")) @(:seen c)))
        (finally (acp/end! (:conn c)))))))

(deftest the-editor-is-asked-in-its-own-words
  ;; the interop client's cwd was /var/…, byj's path /private/var/…: an
  ;; editor finds a buffer by the name it knows
  (let [dir (fresh-dir "client-fs-words")
        link (str (io/file (fresh-dir "client-fs-link") "cwd"))
        _ (fx/sh {:out :string :err :string} "ln" "-s" dir link)
        asked (atom [])
        c (client :answer (fn [m] (swap! asked conj [(:method m) (:params m)])
                            (when (= "fs/read_text_file" (:method m)) {:content "buffer"})))
        fs (acp/client-fs (:conn c) (atom "s1") link {:readTextFile true :writeTextFile true})]
    (try
      (is (= "buffer" ((:read fs) (io/file dir "a.txt"))))
      (is (nil? ((:write fs) (io/file dir "sub" "b.txt") "x")))
      (is (= [["fs/read_text_file" {:sessionId "s1" :path (str link "/a.txt")}]
              ["fs/write_text_file" {:sessionId "s1" :path (str link "/sub/b.txt") :content "x"}]]
             @asked)
          "under the cwd as the client gave it, with the session's id")
      (finally (acp/end! (:conn c))))))

(deftest a-tool-given-in-a-primitives-name-takes-its-place
  (let [mine (assoc (first (filter #(= "read-file" (:name %)) tools/builtin-tools)) :description "mine")
        names (mapv :name (:tools (sdk/base {:project-dir (fresh-dir "replace") :provider :mock :tools [mine]})))]
    (is (= 1 (count (filter #{"read-file"} names))) "one of the name, not two")
    (is (= (mapv :name tools/builtin-tools) names) "in the primitive's place")
    (is (= "mine" (:description (first (filter #(= "read-file" (:name %)) (:tools (sdk/base {:project-dir (fresh-dir "replace") :provider :mock :tools [mine]})))))))))

(deftest a-prompt-is-the-clients-to-answer
  (let [dir (fresh-dir "permission")
        _ (spit (io/file dir "a.md") "A")
        asked (atom [])
        c (client :answer (fn [m]
                            (when (= "session/request_permission" (:method m))
                              (swap! asked conj (:params m))
                              {:outcome {:outcome "selected" :optionId (if (= 1 (count @asked)) "allow-always" "reject-once")}})))
        _ (call c "initialize" {:protocolVersion 1})
        {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "read-file" :tool-args {:path "a.md"}}]}
                                                             {:tool-calls [{:tool-name "read-file" :tool-args {:path "a.md"}}]}
                                                             {:answer "done"}])
                                          {:permissionMode "ask-each-time"
                                           :config {:builtin-agents? false :tool-ask-effects ["fs/read"]}})]
    (try
      (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "read a twice"}]})
      (testing "the gate asked the client, naming the call and offering byj's four answers"
        (is (= 1 (count @asked)) "allow-always is remembered for the session: the second call is not asked")
        (let [{:keys [toolCall options]} (first @asked)]
          (is (= "read" (:kind toolCall)))
          (is (= ["allow_once" "allow_always" "reject_once" "reject_always"] (mapv :kind options)))))
      (testing "and both calls ran"
        (is (= 2 (count (filter #(and (= "tool_call_update" (:sessionUpdate %)) (= "completed" (:status %)))
                                (updates c sessionId))))))
      (finally (acp/end! (:conn c))))))

(deftest a-request-asked-every-time-offers-no-always
  ;; byj-8b3: a profile write is asked each time; the client is not offered
  ;; an always, and one it sends anyway is not kept
  (let [asked (atom [])
        pick (atom "allow-always")
        pfn (acp/permission-fn {:state (atom {})} (atom "s1") (constantly #{:fs/write}))
        req {:tool "profile$edit" :display "change the profile" :pattern "profile$edit" :every-time? true}]
    (with-redefs [acp/request! (fn [_ _ params]
                                 (swap! asked conj params)
                                 (doto (promise) (deliver {:result {:outcome {:outcome "selected" :optionId @pick}}})))]
      (is (:denied (pfn req)) "an always it was not offered is a no")
      (is (= ["allow_once" "reject_once"] (mapv :kind (:options (first @asked)))))
      (reset! pick "allow-once")
      (is (= {:allowed true} (pfn req)))
      (is (= {:allowed true} (pfn req)))
      (is (= 3 (count @asked)) "every one asked"))))

(deftest a-cancel-ends-the-turn-cancelled
  (let [dir (fresh-dir "cancel")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        ;; bash sleeping: a turn that would run long
        {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "sleep 30"}}]}])
                                          {:permissionMode "auto-approve"})
        id 900]
    (try
      (acp/receive! (:conn c) (json/write-str {:jsonrpc "2.0" :id id :method "session/prompt"
                                               :params {:sessionId sessionId :prompt [{:type "text" :text "wait"}]}}))
      (loop [n 0] (when (and (< n 500) (not (some #(= "tool_call" (:sessionUpdate %)) (updates c sessionId))))
                    (Thread/sleep 10) (recur (inc n))))
      (acp/receive! (:conn c) (json/write-str {:jsonrpc "2.0" :method "session/cancel" :params {:sessionId sessionId}}))
      (let [r (loop [n 0] (or (some #(when (= id (:id %)) %) @(:seen c))
                              (when (< n 1000) (Thread/sleep 10) (recur (inc n)))))]
        (is (= "cancelled" (get-in r [:result :stopReason]))))
      (finally (acp/end! (:conn c))))))

(deftest a-session-is-loaded-with-its-history
  (let [dir (fresh-dir "load")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        model (script! dir [{:answer "first answer"}])
        {:keys [sessionId]} (new-session c dir model)]
    (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "first question"}]})
    (acp/end! (:conn c))
    (let [c2 (client)
          _ (call c2 "initialize" {:protocolVersion 1})
          r (call c2 "session/load" {:sessionId sessionId :cwd dir :mcpServers []
                                     :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false}}}})
          us (updates c2 sessionId)]
      (try
        (is (:result r))
        (testing "its history replayed as updates before the load is answered"
          (is (some #(and (= "user_message_chunk" (:sessionUpdate %)) (= "first question" (get-in % [:content :text]))) us))
          (is (some #(and (= "agent_message_chunk" (:sessionUpdate %)) (= "first answer" (get-in % [:content :text]))) us)))
        (testing "a session already open is refused"
          (is (:error (call c2 "session/load" {:sessionId sessionId :cwd dir :mcpServers []}))))
        (testing "session/close ends it: its lock released, and it is no longer there"
          (is (= {} (:result (call c2 "session/close" {:sessionId sessionId}))))
          (is (not (.isFile (persist/lock-file {:project-dir dir} sessionId))))
          (is (:error (call c2 "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "x"}]}))))
        (finally (acp/end! (:conn c2)))))))

(deftest a-session-answers-the-provider-and-model-it-runs-on
  ;; byj-66y: a client that did not choose them could not say which a
  ;; session runs on — one made elsewhere, or one /model changed
  (let [dir (fresh-dir "lm")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        model (script! dir [{:answer "first answer"}])
        r (new-session c dir model {:commands true})
        sessionId (:sessionId r)]
    (is (= {:provider "mock" :model model :label (str "mock/" model)} (get-in r [:_meta :brainyard :lm]))
        "the one asked for")
    (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "first question"}]})
    (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "/model mock/another"}]})
    (call c "session/close" {:sessionId sessionId})
    (testing "loaded asking for none, it runs on the model it chose, and says so"
      (let [r (call c "session/load" {:sessionId sessionId :cwd dir :mcpServers []
                                      :_meta {:brainyard {:config {:builtin-agents? false}}}})]
        (is (= {:provider "mock" :model "another" :label "mock/another"} (get-in r [:result :_meta :brainyard :lm])))
        (call c "session/close" {:sessionId sessionId})))
    (testing "session/list says the model a session chose"
      (let [row (some #(when (= sessionId (:sessionId %)) %) (:sessions (:result (call c "session/list" {:cwd dir}))))]
        (is (= "mock/another" (get-in row [:_meta :brainyard :model])))))
    (acp/end! (:conn c))))

;; byj-lie: a 92-turn session's snapshot kept 500 transcript entries, which
;; began in turn 63, and session/load replayed only those
(deftest a-loaded-session-replays-every-turn-not-only-the-snapshots-window
  (let [dir (fresh-dir "load-all")
        _ (spit (io/file dir "notes.md") "the notes")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        model (script! dir [{:tool-calls [{:tool-name "read-file" :tool-args {:path "notes.md"}}]}
                            {:answer "first answer" :goal-achieved true}
                            {:answer "second answer"}
                            {:answer "third answer"}])
        {:keys [sessionId]} (new-session c dir model)]
    (doseq [q ["first question" "second question" "third question"]]
      (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text q}]}))
    (acp/end! (:conn c))
    ;; the snapshot's window, as a long session's: the last turn's alone
    (let [f (persist/session-file {:project-dir dir} sessionId)
          snap (read-string (slurp f))]
      (spit f (pr-str (update snap :transcript #(vec (take-last 2 %))))))
    (let [c2 (client)
          _ (call c2 "initialize" {:protocolVersion 1})
          r (call c2 "session/load" {:sessionId sessionId :cwd dir :mcpServers []
                                     :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false}}}})
          us (updates c2 sessionId)
          of (fn [kind] (filter #(= kind (:sessionUpdate %)) us))]
      (try
        (is (:result r))
        (testing "every turn's question, from the first, each naming its turn as byj turns list does"
          (is (= [["first question" 1] ["second question" 2] ["third question" 3]]
                 (map (juxt #(get-in % [:content :text]) #(get-in % [:_meta :brainyard :turn])) (of "user_message_chunk")))))
        (testing "and its answer"
          (is (= [["first answer" 1] ["second answer" 2] ["third answer" 3]]
                 (map (juxt #(get-in % [:content :text]) #(get-in % [:_meta :brainyard :turn])) (of "agent_message_chunk")))))
        (testing "a tool call it made, done, with its result"
          (let [tc (first (of "tool_call"))]
            (is (str/starts-with? (:title tc) "read-file notes.md"))
            (is (= "completed" (:status tc)))
            (is (= "read" (:kind tc)))
            (is (= 1 (get-in tc [:_meta :brainyard :turn])))
            (is (str/includes? (get-in tc [:content 0 :content :text]) "the notes"))))
        (finally (acp/end! (:conn c2)))))))

(deftest a-replayed-call-says-when-it-began-and-how-long-it-ran
  (let [dir (fresh-dir "load-timed")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        model (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "sleep 0.3"}}]}
                            {:answer "slept" :goal-achieved true}])
        {:keys [sessionId]} (new-session c dir model)
        before (System/currentTimeMillis)
        _ (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "sleep a little"}]})
        after (System/currentTimeMillis)
        live (first (filter #(= "tool_call_update" (:sessionUpdate %)) (updates c sessionId)))
        _ (acp/end! (:conn c))
        load! (fn []
                (let [c2 (client)
                      _ (call c2 "initialize" {:protocolVersion 1})
                      _ (call c2 "session/load" {:sessionId sessionId :cwd dir :mcpServers []
                                                 :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false}}}})
                      tc (first (filter #(= "tool_call" (:sessionUpdate %)) (updates c2 sessionId)))]
                  (acp/end! (:conn c2))
                  tc))]
    (testing "a live call's update carries byj's own time for it"
      (is (<= before (get-in live [:_meta :brainyard :startedAt]) after))
      (is (<= 300 (get-in live [:_meta :brainyard :durationMs]) (- after before))))
    (testing "a replayed call, the time the run kept, beside its turn"
      (let [tc (load!)
            {:keys [startedAt durationMs turn]} (get-in tc [:_meta :brainyard])]
        (is (= "completed" (:status tc)))
        (is (= 1 turn))
        (is (= (get-in live [:_meta :brainyard :startedAt]) startedAt))
        (is (= (get-in live [:_meta :brainyard :durationMs]) durationMs))))
    (testing "a call recorded before calls were timed has neither, and still loads"
      (let [f (io/file dir ".brainyard" "sessions" sessionId "trajectory.edn")
            untime (fn [r] (update r :iterations
                                   (fn [its] (mapv (fn [it] (cond-> it
                                                              (:tools it) (update :tools (fn [ts] (mapv #(dissoc % :started-at :duration-ms) ts)))))
                                                   its))))]
        (is (.exists f))
        (spit f (str (str/join "\n" (map (comp pr-str untime read-string) (remove str/blank? (str/split-lines (slurp f))))) "\n"))
        (let [tc (load!)]
          (is (= "completed" (:status tc)))
          (is (= {:turn 1} (get-in tc [:_meta :brainyard]))))))))

(deftest a-loaded-turn-says-what-a-rewind-of-it-goes-back-to
  (let [dir (fresh-dir "load-checkpoint")
        git #(str/trim (str (:out (apply fx/sh {:dir dir :out :string :err :string} "git" %&))))
        _ (do (git "init" "-q") (spit (io/file dir "notes.md") "the notes\n")
              (git "add" "-A") (git "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-q" "-m" "first"))
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        model (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "echo made > made.txt"}}]}
                            {:answer "made it" :goal-achieved true}
                            {:answer "nothing to change"}
                            {:answer "still nothing"}])
        {:keys [sessionId]} (new-session c dir model)]
    (doseq [q ["make a file" "look" "look again"]]
      (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text q}]}))
    (acp/end! (:conn c))
    ;; turn 3's refs gone, as pruning takes them
    (git "update-ref" "-d" (str "refs/byj/turns/" sessionId "/3"))
    (let [c2 (client)
          _ (call c2 "initialize" {:protocolVersion 1})
          _ (call c2 "session/load" {:sessionId sessionId :cwd dir :mcpServers []
                                     :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false}}}})
          us (updates c2 sessionId)
          cps (for [n [1 2 3]] (some #(when (= n (get-in % [:_meta :brainyard :turn])) (get-in % [:_meta :brainyard :checkpoint])) us))]
      (try
        (testing "each turn's first update says its checkpoint: its ref, the commits a rewind goes back to, the files it changed"
          (let [[c1 c2'] cps]
            (is (= (str "refs/byj/turns/" sessionId "/1") (:ref c1)))
            (is (= 1 (:filesChanged c1)))
            (is (= (git "rev-parse" (str "refs/byj/turns/" sessionId "/1")) (:after c1)))
            (is (= (git "rev-parse" (str "refs/byj/turns/" sessionId "/1^")) (:before c1)))
            (is (= 0 (:filesChanged c2')) "a turn that changed nothing is still a start to go back to")))
        (testing "once, on the first"
          (is (= 1 (count (filter #(and (= 1 (get-in % [:_meta :brainyard :turn])) (get-in % [:_meta :brainyard :checkpoint])) us)))))
        (testing "a turn whose refs are gone has none, and is replayed all the same"
          (is (nil? (nth cps 2)))
          (is (some #(and (= 3 (get-in % [:_meta :brainyard :turn])) (= "look again" (get-in % [:content :text]))) us)))
        (finally (acp/end! (:conn c2)))))))

(deftest saved-sessions-are-listed-for-an-editor-to-import
  (let [dir (fresh-dir "list")
        c (client)
        r (:result (call c "initialize" {:protocolVersion 1}))
        model (script! dir [{:answer "one"} {:answer "two"}])
        a (:sessionId (new-session c dir model))
        _ (call c "session/prompt" {:sessionId a :prompt [{:type "text" :text "the first\n  question"}]})
        b (:sessionId (new-session c dir model))
        _ (call c "session/prompt" {:sessionId b :prompt [{:type "text" :text "the second"}]})
        idle (:sessionId (new-session c dir model))]
    (try
      (is (= {} (get-in r [:agentCapabilities :sessionCapabilities :list])))
      (let [{:keys [sessions nextCursor]} (:result (call c "session/list" {:cwd dir}))]
        (testing "newest first, each as ACP's SessionInfo; one that never ran a turn is not there"
          (is (= [b a] (mapv :sessionId sessions)))
          (is (not-any? #(= idle (:sessionId %)) sessions))
          (is (every? #(= dir (:cwd %)) sessions))
          (is (= "the first question" (:title (second sessions))))
          (is (re-matches #"\d{4}-\d\d-\d\dT.*Z" (str (:updatedAt (first sessions)))))
          (is (= 1 (get-in (first sessions) [:_meta :brainyard :turnCount])))
          (is (nil? nextCursor))))
      (testing "a page at a time, the cursor the next page's"
        (with-redefs [acp/list-page 1]
          (let [p1 (:result (call c "session/list" {:cwd dir}))
                p2 (:result (call c "session/list" {:cwd dir :cursor (:nextCursor p1)}))]
            (is (= [b] (mapv :sessionId (:sessions p1))))
            (is (= [a] (mapv :sessionId (:sessions p2))))
            (is (nil? (:nextCursor p2))))))
      (testing "a cursor this agent did not give is refused"
        (is (= -32602 (get-in (call c "session/list" {:cwd dir :cursor "x"}) [:error :code]))))
      (testing "a listed session loads"
        (call c "session/close" {:sessionId a})
        (is (:result (call c "session/load" {:sessionId a :cwd dir :mcpServers []
                                             :_meta {:brainyard {:provider "mock" :model model
                                                                 :config {:builtin-agents? false}}}}))))
      (finally (acp/end! (:conn c))))))

(deftest a-saved-session-is-resumed-without-its-history
  (let [dir (fresh-dir "resume")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        model (script! dir [{:answer "first answer"} {:answer "second answer"}])
        opts {:provider "mock" :model model :config {:builtin-agents? false}}
        {:keys [sessionId]} (new-session c dir model)]
    (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "first question"}]})
    (acp/end! (:conn c))
    (let [c2 (client)
          r (:result (call c2 "initialize" {:protocolVersion 1}))]
      (try
        (is (= {} (get-in r [:agentCapabilities :sessionCapabilities :resume])))
        (let [res (call c2 "session/resume" {:sessionId sessionId :cwd dir :mcpServers [] :_meta {:brainyard opts}})]
          (is (get-in res [:result :modes]))
          (testing "nothing of its history is sent"
            (is (empty? (updates c2 sessionId)))))
        (testing "it goes on where it stood: the next turn is its second"
          (is (= "end_turn" (get-in (call c2 "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "again"}]})
                                    [:result :stopReason])))
          (is (= 2 (:turn-count (persist/read-session {:project-dir dir} sessionId)))))
        (testing "a session already open is refused"
          (is (:error (call c2 "session/resume" {:sessionId sessionId :cwd dir :mcpServers []}))))
        (finally (acp/end! (:conn c2)))))))

(deftest a-saved-session-is-deleted
  (let [dir (fresh-dir "delete")
        c (client)
        r (:result (call c "initialize" {:protocolVersion 1}))
        model (script! dir [{:answer "one"} {:answer "two"} {:answer "three"}])
        ran (fn [] (let [sid (:sessionId (new-session c dir model))]
                     (call c "session/prompt" {:sessionId sid :prompt [{:type "text" :text "q"}]})
                     sid))
        a (ran) b (ran) held (ran)
        listed #(set (map :sessionId (get-in (call c "session/list" {:cwd dir}) [:result :sessions])))
        cfg {:project-dir dir}]
    (try
      (is (= {} (get-in r [:agentCapabilities :sessionCapabilities :delete])))
      (call c "session/close" {:sessionId a})
      (call c "session/close" {:sessionId held})
      (is (= #{a b held} (listed)))
      (io/make-parents (io/file dir ".brainyard" "transcripts" a "t.edn"))
      (spit (io/file dir ".brainyard" "transcripts" a "t.edn") "[]")
      ;; what a and b kept to rewind, as RFC-069 keeps it
      (let [git #(str/trim (:out (apply fx/sh {:dir dir :out :string :err :string} "git" %&)))]
        (git "init" "-q")
        (let [c0 (git "-c" "user.name=byj" "-c" "user.email=byj@localhost" "commit-tree" "-m" "byj"
                      (git "hash-object" "-w" "-t" "tree" "/dev/null"))]
          (doseq [r [(str "refs/byj/turns/" a "/1") (str "refs/byj/rewinds/" a "/1") (str "refs/byj/turns/" b "/1")]]
            (git "update-ref" r c0))))
      (testing "a listed session is deleted, its transcript with it, and is listed no more"
        (is (= {} (:result (call c "session/delete" {:sessionId a}))))
        (is (not (.exists (persist/session-dir cfg a))))
        (is (not (.exists (io/file dir ".brainyard" "transcripts" a))))
        (is (= #{b held} (listed))))
      (testing "and what it kept to rewind goes with it, another session's left (byj-ayk)"
        (let [refs (str/trim (:out (fx/sh {:dir dir :out :string} "git" "for-each-ref" "--format=%(refname)" "refs/byj/")))]
          (is (= (str "refs/byj/turns/" b "/1") refs))))
      (testing "one open on this connection is closed first"
        (is (= {} (:result (call c "session/delete" {:sessionId b}))))
        (is (:error (call c "session/prompt" {:sessionId b :prompt [{:type "text" :text "x"}]})))
        (is (not (.exists (persist/session-dir cfg b)))))
      (testing "one another live process holds is refused, and kept"
        (spit (persist/lock-file cfg held) (pr-str {:pid "1" :at 0}))
        (is (re-find #"held by process 1" (str (get-in (call c "session/delete" {:sessionId held}) [:error :message]))))
        (is (.exists (persist/session-file cfg held))))
      (testing "an unknown session, or an id not in a session id's shape, is refused"
        (is (= -32602 (get-in (call c "session/delete" {:sessionId "1792000000000-00000000"
                                                       :_meta {:brainyard {:cwd dir}}}) [:error :code])))
        (is (= -32602 (get-in (call c "session/delete" {:sessionId "../x"}) [:error :code]))))
      (finally (acp/end! (:conn c))))))

(defn- line-reader
  "Read `is` to its end on blk, each line as it is written — as a client reads
  a process's stdout — into an atom, `{:lines [{:text :msg}] :eof? bool}`,
  each line parsed once as it arrives (`::not-json` when it is not JSON).
  Returns `[atom cancel]`. Read a byte buffer at a time, as
  `fx/streaming-process` does: the readers that look tidier deliver nothing
  until the process exits. A last line with no newline is still a line, and a
  stream closed under the read is its end."
  [^java.io.InputStream is]
  (let [seen (atom {:lines [] :eof? false})
        buf (byte-array 8192)
        line (java.io.ByteArrayOutputStream.)
        emit! (fn []
                (let [text (String. (.toByteArray line) "UTF-8")]
                  (.reset line)
                  (when-not (str/blank? text)
                    (swap! seen update :lines conj
                           {:text text :msg (try (json/read-str text) (catch Throwable _ ::not-json))}))))]
    [seen
     (fx/run-detached
      (fx/task-of #(try (loop []
                          (let [n (try (.read is buf) (catch java.io.IOException _ -1))]
                            (when (pos? n)
                              (dotimes [i n]
                                (let [b (aget buf i)]
                                  (if (= 10 b) (emit!) (.write line (int b)))))
                              (recur))))
                        (when (pos? (.size line)) (emit!))
                        (finally (swap! seen assoc :eof? true))))
      "acp-test stdout")]))

(defn- within
  "True once `f` returns truthy, polled every 10 ms; false once `deadline`
  (epoch ms) has passed, however long each poll took."
  [deadline f]
  (loop []
    (cond (f) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 10) (recur)))))

(deftest stdout-is-the-protocol-alone-and-the-client-going-away-ends-everything
  ;; a process: what only a process shows
  (let [dir (fresh-dir "process")
        model (script! dir [{:answer "hello back"}])
        proc (fx/start {:in :stream :out :stream :err :string}
                       "jolt" "-m" "brainyard.main" "acp" "-p" "mock" "-m" model)
        ^java.io.OutputStream in (:in proc)
        send (fn [m] (.write in (.getBytes (str (json/write-str (assoc m :jsonrpc "2.0")) "\n") "UTF-8")) (.flush in))
        [seen stop-reading] (line-reader (:out proc))
        msgs #(map :msg (:lines @seen))
        out #(str/join "\n" (map :text (:lines @seen)))
        answer (fn [id] (some #(when (and (map? %) (= id (:id %))) %) (msgs)))
        ;; every wait below, the process's end included, shares one 90 s: a hung
        ;; process fails this test in about the time the old one took, inside
        ;; the runner's 120 s for any one test (byj-gly.444)
        deadline (+ (System/currentTimeMillis) 90000)
        left #(max 1000 (- deadline (System/currentTimeMillis)))]
    (try
      (send {:id 1 :method "initialize" :params {:protocolVersion 1}})
      ;; a process of its own, without the runner's properties: the memory
      ;; store is switched off by config, so its turn never lists this
      ;; repository's beads (provenance P-40), and so are the turn's
      ;; snapshots, whose work tree under target/ is this repository's
      ;; (provenance P-55)
      (send {:id 2 :method "session/new" :params {:cwd dir :mcpServers []
                                                  :_meta {:brainyard {:config {:builtin-agents? false :memory? false
                                                                               :memory-hint? false :changes? false}}}}})
      ;; the session's id from its answer, as a client has it. Read from the
      ;; session store, it was there before the connection held the session,
      ;; and a prompt sent then under jolt test:byj's six shards was refused
      ;; "no session" — id 3 with no end_turn (byj-315). The first answer waits
      ;; on jolt starting the process too: over 30 s once with every core busy
      (let [sid (when (within deadline #(answer 2)) (get-in (answer 2) [:result :sessionId]))]
        (is sid (str "the session was opened: " (pr-str (answer 2)) "\nstdout: " (out)))
        (when sid
          (send {:id 3 :method "session/prompt" :params {:sessionId sid :prompt [{:type "text" :text "hi"}]}})
          ;; answered before stdin closes, as the test says: closing it closes
          ;; the session, which cancels a turn still running
          (is (within deadline #(answer 3)) (str "the prompt was answered\nstdout: " (out))))
        (.close in)
        ;; stdout read to its end before the process is awaited, so nothing
        ;; written is lost to the stream being closed under the reader
        (is (within deadline #(:eof? @seen)) (str "stdout reached its end\nstdout: " (out)))
        (let [{:keys [exit err]} (deref proc (left) {:exit :timeout})]
          (testing "every line on stdout is a JSON-RPC message"
            (is (seq (msgs)))
            (is (every? #(and (map? %) (= "2.0" (:jsonrpc %))) (msgs)) (str "stdout: " (out) "\nstderr: " err)))
          (testing "the prompt was answered before the end"
            (is (= "end_turn" (get-in (answer 3) [:result :stopReason])) (str "stdout: " (out) "\nstderr: " err)))
          (testing "closing stdin ends the process, 0, the session's lock released"
            (is (= 0 exit))
            (when sid (is (not (.isFile (persist/lock-file {:project-dir dir} sid))))))))
      ;; a process that hangs is not left running, nor its stdout's reader parked on it
      (finally (try (p/destroy-tree proc) (catch Throwable _ nil))
               (try (stop-reading) (catch Throwable _ nil))))))

;; --- byj's extensions (byj-g5n.5) -----------------------------------------------------

(defn- with-ext [c] (call c "initialize" {:protocolVersion 1 :_meta {:brainyard {:sdk "test" :version "0"}}}) c)

(deftest a-live-turn-names-itself-and-says-what-it-changed
  (let [dir (fresh-dir "live-turn")
        git #(str/trim (str (:out (apply fx/sh {:dir dir :out :string :err :string} "git" %&))))
        _ (do (git "init" "-q") (spit (io/file dir "notes.md") "the notes\n")
              (git "add" "-A") (git "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-q" "-m" "first"))
        c (with-ext (client))
        {:keys [sessionId]} (new-session c dir (script! dir [{:reasoning "make it" :tool-calls [{:tool-name "bash" :tool-args {:command "echo made > made.txt"}}]}
                                                             {:answer "made it" :goal-achieved true}
                                                             {:answer "nothing to change"}]))
        r1 (:result (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "make a file"}]}))
        seen-1 (vec (updates c sessionId))
        r2 (:result (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "look"}]}))
        seen-2 (drop (count seen-1) (updates c sessionId))]
    (try
      (testing "every update of a turn as it runs names its turn, as a replayed one does"
        (is (seq seen-1))
        (is (every? #(= 1 (get-in % [:_meta :brainyard :turn])) seen-1) (pr-str (map (juxt :sessionUpdate :_meta) seen-1)))
        (is (every? #(= 2 (get-in % [:_meta :brainyard :turn])) seen-2))
        (is (some #(and (= "tool_call_update" (:sessionUpdate %)) (get-in % [:_meta :brainyard :durationMs])) seen-1)
            "beside what it said already"))
      (testing "and its result says the turn, how many files it changed and its checkpoint"
        (is (= {:turn 1 :filesChanged 1} (select-keys (get-in r1 [:_meta :brainyard]) [:turn :filesChanged])))
        (is (= {:ref (str "refs/byj/turns/" sessionId "/1")
                :before (git "rev-parse" (str "refs/byj/turns/" sessionId "/1^"))
                :after (git "rev-parse" (str "refs/byj/turns/" sessionId "/1"))
                :filesChanged 1}
               (get-in r1 [:_meta :brainyard :checkpoint])))
        (is (= {:turn 2 :filesChanged 0} (select-keys (get-in r2 [:_meta :brainyard]) [:turn :filesChanged])))
        (is (= 0 (get-in r2 [:_meta :brainyard :checkpoint :filesChanged])) "a turn that changed nothing is still one to go back to"))
      (finally (acp/end! (:conn c))))))

(deftest extensions-only-when-asked
  (let [dir (fresh-dir "ext")
        run (fn [c]
              (let [{:keys [sessionId]} (new-session c dir (script! dir [{:answer "an answer" :goal-achieved true}])
                                                     {:events "all"})
                    r (:result (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "q"}]}))]
                (acp/end! (:conn c))
                [r (filter #(= "_byj/event" (:method %)) @(:seen c))]))]
    (testing "a plain client asking for events gets none, and no _meta on the result"
      (let [c (client)
            _ (call c "initialize" {:protocolVersion 1})
            [r events] (run c)]
        (is (empty? events))
        (is (nil? (:_meta r)))))
    (testing "a client that asked gets the turn's result and byj's events"
      (let [[r events] (run (with-ext (client)))]
        (is (= "an answer" (get-in r [:_meta :brainyard :answer])))
        (is (= "answer-channel" (get-in r [:_meta :brainyard :terminatedBy])))
        (is (number? (get-in r [:_meta :brainyard :iterations])))
        (is (some #(= "session.turn/start" (get-in % [:params :event])) events))
        (is (some #(= "session.turn/end" (get-in % [:params :event])) events))))))

(defn- git-project
  "A fresh git project with one commit, for a session's turns to be kept."
  [name]
  (let [dir (fresh-dir name)
        git #(str/trim (str (:out (apply fx/sh {:dir dir :out :string :err :string} "git" %&))))]
    (git "init" "-q") (spit (io/file dir "notes.md") "the notes\n")
    (git "add" "-A") (git "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-q" "-m" "first")
    [dir git]))

(deftest a-sessions-turns-are-listed-diffed-and-taken-back-over-acp
  (let [[dir git] (git-project "turns-methods")
        c (with-ext (client))
        {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "echo a > a.txt"}}]}
                                                             {:answer "wrote a" :goal-achieved true}
                                                             {:tool-calls [{:tool-name "bash" :tool-args {:command "echo b > b.txt"}}]}
                                                             {:answer "wrote b" :goal-achieved true}
                                                             {:answer "looked"}
                                                             {:answer "and again"}])
                                         {:events "all"})
        ask! #(call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text %}]})
        a (io/file dir "a.txt") b (io/file dir "b.txt")]
    (ask! "write a") (ask! "write b") (ask! "look")
    (try
      (testing "the turns byj keeps, each with what it changed: what a rewind can go back to"
        (let [ts (get-in (call c "_byj/turns/list" {:sessionId sessionId}) [:result :turns])]
          (is (= [1 2 3] (mapv :turn ts)))
          (is (= [["a.txt"] ["b.txt"] []] (mapv :files ts)))
          (is (= ["write a" "write b" "look"] (mapv :question ts)))
          (is (= (git "rev-parse" (str "refs/byj/turns/" sessionId "/1^")) (:before (first ts))))))
      (testing "a turn's diff, the last that changed files when none is named"
        (is (str/includes? (get-in (call c "_byj/turns/diff" {:sessionId sessionId :turn 1}) [:result :diff]) "+a"))
        (is (= 2 (get-in (call c "_byj/turns/diff" {:sessionId sessionId}) [:result :turn])))
        (is (re-find #"changed no files" (get-in (call c "_byj/turns/diff" {:sessionId sessionId :turn 3}) [:error :message]))))
      (testing "back to turn 2's start, answered with what it did; the session opened without commands"
        (let [r (:result (call c "_byj/turns/rewind" {:sessionId sessionId :turn 2}))]
          (is (= {:kind "rewind" :turn 2 :end false :files ["b.txt"] :conflicts []
                  :saved (str "refs/byj/rewinds/" sessionId "/1")} r))
          (is (not (.exists b)))
          (is (.exists a))))
      (testing "and the session's next turn is told, in /rewind's words"
        (ask! "what now")
        (is (some #(and (= "agent.llm/request" (get-in % [:params :event]))
                        (str/includes? (json/write-str (get-in % [:params :payload])) "/rewind 2: the person put the work tree back"))
                  @(:seen c))))
      (testing "the rewind taken back"
        (is (= ["b.txt"] (get-in (call c "_byj/turns/undoRewind" {:sessionId sessionId}) [:result :files])))
        (is (= "b\n" (slurp b))))
      (testing "one turn taken back alone, what came after it kept"
        (let [r (:result (call c "_byj/turns/undoTurn" {:sessionId sessionId :turn 1}))]
          (is (= {:kind "undo-turn" :turn 1 :files ["a.txt"] :conflicts []} (select-keys r [:kind :turn :files :conflicts])))
          (is (not (.exists a)))
          (is (= "b\n" (slurp b)))))
      (testing "what cannot be done is refused in the command's words, and changes nothing"
        (is (re-find #"not kept" (get-in (call c "_byj/turns/rewind" {:sessionId sessionId :turn 9}) [:error :message])))
        (is (re-find #"nothing to undo" (get-in (call c "_byj/turns/undoTurn" {:sessionId sessionId :turn 3}) [:error :message])))
        (is (= -32602 (get-in (call c "_byj/turns/rewind" {:sessionId sessionId}) [:error :code])))
        (is (= -32602 (get-in (call c "_byj/turns/list" {:sessionId "1792000000000-00000000"}) [:error :code]))))
      (testing "a prompt that is a command is still refused: the methods are not a way to type one"
        (is (:error (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "/rewind 1"}]}))))
      (finally (acp/end! (:conn c))))))

(deftest a-turn-a-later-one-conflicts-with-is-not-said-to-be-taken-back-already
  ;; byj-v55: turn 1 made notes.txt, turn 2 changed it; taking turn 1 back
  ;; alone is a modify/delete conflict, and nothing is written
  (let [[dir _] (git-project "undo-conflict")
        c (with-ext (client))
        {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "echo one > notes.txt"}}]}
                                                             {:answer "made" :goal-achieved true}
                                                             {:tool-calls [{:tool-name "bash" :tool-args {:command "echo two >> notes.txt"}}]}
                                                             {:answer "added" :goal-achieved true}]))
        ask! #(call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text %}]})
        notes (io/file dir "notes.txt")]
    (ask! "make notes") (ask! "add to them")
    (try
      (let [r (:result (call c "_byj/turns/undoTurn" {:sessionId sessionId :turn 1}))]
        (testing "answered with the conflict, as byj turns undo-turn answers it"
          (is (= {:kind "undo-turn" :turn 1 :files [] :conflicts ["notes.txt"]} (select-keys r [:kind :turn :files :conflicts])) (pr-str r)))
        (testing "in words that say a conflict kept it back, not that it was already so"
          (is (re-find #"not taken back" (str (:message r))))
          (is (not (re-find #"already as they were" (str (:message r))))))
        (testing "and nothing was written"
          (is (= "one\ntwo\n" (slurp notes)))))
      (finally (acp/end! (:conn c))))))

(deftest a-session-is-forked-before-a-turn-over-acp
  (let [[dir git] (git-project "fork-before")
        c (with-ext (client))
        model (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "echo a > a.txt"}}]}
                            {:answer "wrote a" :goal-achieved true}
                            {:tool-calls [{:tool-name "bash" :tool-args {:command "echo b > b.txt"}}]}
                            {:answer "wrote b" :goal-achieved true}
                            {:answer "the fork's first"}
                            {:answer "the fork's kept"}])
        {:keys [sessionId]} (new-session c dir model)
        ask! #(call c "session/prompt" {:sessionId %1 :prompt [{:type "text" :text %2}]})]
    (ask! sessionId "write a") (ask! sessionId "write b")
    (try
      (testing "forkBeforeTurn needs forkFrom, and a turn"
        (is (= -32602 (get-in (call c "session/new" {:cwd dir :mcpServers [] :_meta {:brainyard {:provider "mock" :model model :forkBeforeTurn 2}}}) [:error :code]))))
      (testing "a fork before turn 2: its conversation without turn 2, the files put back as turn 2 began, this session told"
        (let [r (:result (call c "session/new" {:cwd dir :mcpServers []
                                                :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false}
                                                                    :forkFrom sessionId :forkBeforeTurn 2}}}))
              nid (:sessionId r)]
          (is (= {:from sessionId :beforeTurn 2 :files ["b.txt"] :saved (str "refs/byj/rewinds/" sessionId "/1")}
                 (get-in r [:_meta :brainyard :fork])))
          (is (not (.exists (io/file dir "b.txt"))))
          (is (.exists (io/file dir "a.txt")))
          (is (= 1 (:turn-count (persist/read-session {:project-dir dir} nid))) "its next turn is 2")
          (is (seq (persist/take-notes! {:project-dir dir} sessionId)) "the session forked from is told at its next turn")))
      (testing "keepFiles leaves them, and tells the new session what they hold"
        (spit (io/file dir "b.txt") "b\n")
        (let [nid (get-in (call c "session/new" {:cwd dir :mcpServers []
                                                 :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false}
                                                                     :forkFrom sessionId :forkBeforeTurn 2 :keepFiles true}}})
                          [:result :sessionId])]
          (is (.exists (io/file dir "b.txt")))
          (is (re-find #"forked from session" (str (first (persist/take-notes! {:project-dir dir} nid)))))))
      (finally (acp/end! (:conn c))))))

(deftest what-a-session-says-while-it-answers-reaches-the-client-as-a-notice
  ;; byj-tc7 (seen finding GitHub #1): a turn's start waiting for an MCP
  ;; server, and the servers changed, were said only to the session's :emit,
  ;; which ACP did not pass on, so a client could not tell a session that had
  ;; its MCP tools from one that did not
  (let [c (client)
        dir (fresh-dir "notices")
        stub (.getCanonicalPath (io/file "test/fixtures/mcp/stub_server.py"))
        {:keys [sessionId]} (:result (call c "session/new"
                                           {:cwd dir
                                            ;; up a second after it is started: still starting when the
                                            ;; session builds its tools, and when the prompt comes
                                            :mcpServers [{:name "slow" :command "sh" :args ["-c" (str "sleep 1; exec python3 " stub)] :env []}]
                                            :_meta {:brainyard {:provider "mock" :model (script! dir [{:answer "done"}])
                                                                ;; the agent's tool list names the server's tools, so its
                                                                ;; turn waits for them
                                                                :config {:builtin-agents? false :visible-tools ["mcp$slow$*" "read-file"]}}}}))
        r (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "use the slow tools"}]} 40000)
        us (updates c sessionId)
        notices (filter #(get-in % [:_meta :brainyard :notice]) us)
        said (str/join "\n" (map #(get-in % [:content :text]) notices))]
    (is (= "end_turn" (get-in r [:result :stopReason])))
    (is (str/includes? said "waiting for MCP server(s) slow, whose tools this turn uses") said)
    (is (re-find #"MCP servers changed, in force from this turn: 3 tool\(s\) from slow" said) said)
    (is (every? #(= "agent_message_chunk" (:sessionUpdate %)) notices))
    (is (= #{"info"} (set (map #(get-in % [:_meta :brainyard :notice]) notices))))
    (is (some #(and (= "done" (get-in % [:content :text])) (nil? (get-in % [:_meta :brainyard :notice]))) us)
        "the answer is not a notice")
    (is (< (.indexOf (vec us) (first notices))
           (.indexOf (vec us) (first (filter #(= "done" (get-in % [:content :text])) us))))
        "said as it was said: before the answer")
    (testing "nothing is said twice, nor outside a prompt"
      (let [n (count notices)]
        (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "again"}]} 40000)
        (is (= n (count (filter #(get-in % [:_meta :brainyard :notice]) (updates c sessionId))))
            "the servers moved no more, so the next turn says nothing of them")))
    (call c "session/close" {:sessionId sessionId})))

(deftest a-session-is-forked-before-a-turn-into-a-worktree-of-its-own-over-acp
  ;; byj-8yt: the session forked from and the project's work tree untouched
  (let [[dir git] (git-project "fork-worktree")
        c (with-ext (client))
        model (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "echo a > a.txt"}}]}
                            {:answer "wrote a" :goal-achieved true}
                            {:tool-calls [{:tool-name "bash" :tool-args {:command "echo b > b.txt"}}]}
                            {:answer "wrote b" :goal-achieved true}])
        ;; each session's mock reads its script from the start
        fork-model (script! (fresh-dir "fork-worktree-model")
                            [{:tool-calls [{:tool-name "bash" :tool-args {:command "echo c > c.txt"}}]}
                             {:answer "wrote c" :goal-achieved true}])
        {:keys [sessionId]} (new-session c dir model)
        ask! #(call c "session/prompt" {:sessionId %1 :prompt [{:type "text" :text %2}]})
        fork! #(call c "session/new" {:cwd dir :mcpServers []
                                      :_meta {:brainyard (merge {:provider "mock" :model model :config {:builtin-agents? false}} %)}})
        refs #(git "for-each-ref" "--format=%(refname)" (str "refs/byj/rewinds/" sessionId "/"))
        worktrees (io/file dir ".brainyard" "worktrees")]
    (ask! sessionId "write a") (ask! sessionId "write b")
    (try
      (testing "a fork's worktree holds the turn's start: not files kept as they are"
        (is (re-find #"keep-files" (get-in (fork! {:forkFrom sessionId :forkBeforeTurn 2 :keepFiles true :worktree {}}) [:error :message])))
        (is (re-find #"not kept" (get-in (fork! {:forkFrom sessionId :forkBeforeTurn 9 :worktree {}}) [:error :message])))
        (is (empty? (.list worktrees)) "and none is left behind"))
      (let [r (:result (fork! {:forkFrom sessionId :forkBeforeTurn 2 :worktree {} :events "all" :model fork-model}))
            nid (:sessionId r)
            {:keys [path cwd branch base files] :as wt} (get-in r [:_meta :brainyard :fork :worktree])
            in-wt #(str/trim (str (:out (apply fx/sh {:dir path :out :string :err :string} "git" %&))))]
        (testing "a new worktree on a branch of its own, from HEAD"
          (is (= {:from sessionId :beforeTurn 2} (dissoc (get-in r [:_meta :brainyard :fork]) :worktree)) "no files put back here")
          (is (= (str "byj/fork-" nid) branch))
          (is (= (.getCanonicalPath (io/file worktrees (str "byj-fork-" nid))) path))
          (is (= path cwd) "the project is the repository's top")
          (is (= (git "rev-parse" "HEAD") base (in-wt "rev-parse" "HEAD")))
          (is (= branch (in-wt "rev-parse" "--abbrev-ref" "HEAD"))))
        (testing "its files as turn 2 began, uncommitted on that branch"
          (is (= "a\n" (slurp (io/file path "a.txt"))))
          (is (not (.exists (io/file path "b.txt"))))
          (is (= "the notes\n" (slurp (io/file path "notes.md"))))
          (is (some #{"a.txt"} files) (pr-str wt)))
        (testing "the project's work tree and the session forked from as they were"
          (is (= "a\n" (slurp (io/file dir "a.txt"))))
          (is (= "b\n" (slurp (io/file dir "b.txt"))))
          (is (= "" (refs)) "nothing put back here, so no rewind kept")
          (is (= 2 (:turn-count (persist/read-session {:project-dir dir} sessionId))))
          (is (empty? (persist/take-notes! {:project-dir dir} sessionId))))
        (testing "the fork is a session of the worktree: listed there, not here, its conversation turn 1's"
          (is (some #{nid} (map :sessionId (get-in (call c "session/list" {:cwd cwd}) [:result :sessions]))))
          (is (not (some #{nid} (map :sessionId (get-in (call c "session/list" {:cwd dir}) [:result :sessions])))))
          (is (= ["write a"] (map :question (:previous-turns (persist/read-session {:project-dir cwd} nid))))))
        (testing "its next turn works in the worktree, told where it now is"
          (ask! nid "write c")
          (is (= "c\n" (slurp (io/file path "c.txt"))))
          (is (not (.exists (io/file dir "c.txt"))))
          (is (some #(and (= "_byj/event" (:method %)) (= nid (get-in % [:params :sessionId]))
                          (str/includes? (json/write-str (get-in % [:params :payload])) "into a git worktree of its own"))
                    @(:seen c))
              "the note, in the request its turn sent")))
      (finally (acp/end! (:conn c))))))

(deftest a-new-session-starts-in-a-worktree-of-its-own-over-acp
  ;; byj-z0z: no session forked — a checkout of HEAD, of a branch as it is,
  ;; or of a ref on a new branch
  (let [[dir git] (git-project "new-worktree")
        trunk (git "branch" "--show-current")
        commit! #(git "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-q" "-m" %)
        _ (do (git "checkout" "-q" "-b" "feature") (spit (io/file dir "f.txt") "f\n") (git "add" "f.txt") (commit! "f")
              (git "checkout" "-q" trunk) (spit (io/file dir "dirty.txt") "not committed\n"))
        c (with-ext (client))
        model (script! (fresh-dir "new-worktree-model")
                       [{:tool-calls [{:tool-name "bash" :tool-args {:command "echo c > c.txt"}}]}
                        {:answer "wrote c" :goal-achieved true}])
        open! #(call c "session/new" {:cwd dir :mcpServers []
                                      :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false} :worktree %}}})
        worktrees (io/file dir ".brainyard" "worktrees")
        at #(str/trim (str (:out (fx/sh {:dir %1 :out :string :err :string} "git" "rev-parse" %2))))]
    (try
      (testing "on HEAD: a new branch, a clean checkout, the session there"
        (let [r (:result (open! {}))
              sid (:sessionId r)
              {:keys [path cwd branch base created] :as wt} (get-in r [:_meta :brainyard :worktree])]
          (is (= {:branch (str "byj/" sid) :base (git "rev-parse" "HEAD") :created true} (select-keys wt [:branch :base :created])) (pr-str r))
          (is (= (.getCanonicalPath (io/file worktrees (str "byj-" sid))) path cwd))
          (is (nil? (get-in r [:_meta :brainyard :fork])) "nothing forked")
          (is (= "the notes\n" (slurp (io/file path "notes.md"))))
          (is (not (.exists (io/file path "dirty.txt"))) "the project's uncommitted work stays where it is")
          (is (not (.exists (io/file path "f.txt"))))
          (call c "session/prompt" {:sessionId sid :prompt [{:type "text" :text "write c"}]})
          (is (= "c\n" (slurp (io/file path "c.txt"))) "its turn works there")
          (is (not (.exists (io/file dir "c.txt"))))
          (is (some #{sid} (map :sessionId (get-in (call c "session/list" {:cwd cwd}) [:result :sessions]))))
          (is (not (some #{sid} (map :sessionId (get-in (call c "session/list" {:cwd dir}) [:result :sessions])))))))
      (testing "an existing branch, checked out as it is"
        (let [{:keys [path branch base created]} (get-in (open! {:branch "feature"}) [:result :_meta :brainyard :worktree])]
          (is (= ["feature" false] [branch created]))
          (is (= (git "rev-parse" "feature") base (at path "HEAD")))
          (is (= "f\n" (slurp (io/file path "f.txt"))))))
      (testing "a ref, on a new branch named"
        (let [{:keys [path branch created]} (get-in (open! {:branch "feature-2" :from "feature"}) [:result :_meta :brainyard :worktree])]
          (is (= ["feature-2" true] [branch created]))
          (is (.exists (io/file path "f.txt")))))
      (testing "what cannot be made is refused, and leaves no worktree"
        (let [before (set (.list worktrees))]
          (is (re-find #"feature" (str (get-in (open! {:branch "feature"}) [:error :message]))) "checked out in another worktree")
          (is (= -32602 (get-in (open! {:from "no-such-ref"}) [:error :code])))
          (is (= -32602 (get-in (open! "yes") [:error :code])))
          (is (= before (set (.list worktrees))))))
      (testing "not on a load: a saved session is loaded where it was made"
        (is (= -32602 (get-in (call c "session/load" {:sessionId "1792000000000-00000000" :cwd dir :mcpServers []
                                                       :_meta {:brainyard {:worktree {}}}})
                              [:error :code]))))
      (finally (acp/end! (:conn c))))))

(deftest a-client-tool-is-called-over-the-wire
  (let [dir (fresh-dir "client-tool")
        asked (atom [])
        c (with-ext (client :answer (fn [m]
                                      (case (:method m)
                                        "_byj/tool/call" (do (swap! asked conj (:params m))
                                                             {:content {:status "refunded" :id (get-in m [:params :args :id])}})
                                        "session/request_permission" {:outcome {:outcome "selected" :optionId "allow-once"}}
                                        nil))))
        tool {:name "order_lookup" :description "An order by its id"
              :inputSchema {:type "object" :properties {:id {:type "string" :description "the order id"}} :required ["id"]}}
        {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "order_lookup" :tool-args {:id "1042"}}]}
                                                             {:answer "refunded" :goal-achieved true}])
                                          {:tools [tool]
                                           :permissionMode "ask-each-time"
                                           :config {:builtin-agents? false :tool-ask-effects ["sdk/call"]}})]
    (try
      (let [r (:result (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "why 1042?"}]}))
            us (updates c sessionId)]
        (testing "the call reached the client with its arguments and the call's id"
          (is (= 1 (count @asked)))
          (is (= {:id "1042"} (:args (first @asked))))
          (is (= (:toolCallId (first (filter #(= "tool_call" (:sessionUpdate %)) us))) (:toolCallId (first @asked)))))
        (testing "gated as any tool: the client was asked first"
          (is (some #(= "session/request_permission" (:method %)) @(:seen c))))
        (testing "its answer was the tool's result"
          (is (str/includes? (get-in (first (filter #(= "tool_call_update" (:sessionUpdate %)) us)) [:content 0 :content :text])
                             "refunded"))
          (is (= "refunded" (get-in r [:_meta :brainyard :answer])))))
      (finally (acp/end! (:conn c)))))
  (testing "a tool whose schema says what this cannot read is refused, by name"
    (let [c (with-ext (client))
          r (call c "session/new" {:cwd (fresh-dir "bad-tool") :mcpServers []
                                   :_meta {:brainyard {:provider "mock" :tools [{:name "x" :inputSchema {:oneOf []}}]}}})]
      (is (= -32602 (get-in r [:error :code])))
      (is (str/includes? (get-in r [:error :message]) "oneOf"))
      (acp/end! (:conn c)))))

(deftest a-client-hook-decides
  (let [dir (fresh-dir "hook")
        seen-hooks (atom [])
        c (with-ext (client :answer (fn [m]
                                      (when (= "_byj/hook" (:method m))
                                        (swap! seen-hooks conj (:params m))
                                        {:decision "block" :reason "no shell here"}))))
        {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "touch made-it"}}]}
                                                             {:answer "done"}])
                                          {:permissionMode "auto-approve"
                                           :hooks [{:id "no-shell" :event "agent.tool-use/pre" :match {:tool "bash"}}]})]
    (try
      (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "make it"}]})
      (testing "the hook was asked, with the call, and its block stood"
        (is (= "agent.tool-use/pre" (:event (first @seen-hooks))))
        (is (= "bash" (get-in (first @seen-hooks) [:payload :tool-name])))
        (is (not (.exists (io/file dir "made-it"))) "bash never ran"))
      (finally (acp/end! (:conn c)))))
  (testing "a hook that does not answer in time fails open, or closed when it says so"
    (let [dir (fresh-dir "hook-timeout")
          c (with-ext (client :answer (fn [m] (when (= "_byj/hook" (:method m)) (Thread/sleep 1000) nil))))
          {:keys [sessionId]} (new-session c dir (script! dir [{:tool-calls [{:tool-name "bash" :tool-args {:command "touch made-it"}}]}
                                                               {:answer "done"}])
                                            {:permissionMode "auto-approve"
                                             :hooks [{:id "slow" :event "agent.tool-use/pre" :timeoutMs 50 :failClosed true}]})]
      (try
        (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "make it"}]})
        (is (not (.exists (io/file dir "made-it"))))
        (finally (acp/end! (:conn c))))))
  (testing "an event that is no hook point is refused"
    (let [c (with-ext (client))
          r (call c "session/new" {:cwd (fresh-dir "bad-hook") :mcpServers []
                                   :_meta {:brainyard {:provider "mock" :hooks [{:event "agent.nothing/pre"}]}}})]
      (is (str/includes? (get-in r [:error :message]) "no hook event agent.nothing/pre"))
      (acp/end! (:conn c)))))

(deftest predict-over-the-wire
  (let [dir (fresh-dir "predict")
        f (io/file dir "replies.edn")
        _ (spit f (pr-str [{:reasoning "a charge twice is billing" :category "billing"}]))
        c (with-ext (client))
        r (call c "_byj/predict" {:cwd dir :provider "mock" :model (str "script:" (.getPath f))
                                  :signature {:instructions "Classify the ticket."
                                              :inputs [{:name "ticket" :description "the ticket"}]
                                              :outputs [{:name "category" :schema {:enum ["billing" "bug" "other"]}}]}
                                  :inputs {:ticket "I was charged twice"}})]
    (is (= "billing" (get-in r [:result :outputs :category])))
    (is (= "user/inline" (get-in r [:result :predictorId])))
    (testing "a signature field this cannot read is refused"
      (is (= -32602 (get-in (call c "_byj/predict" {:cwd dir :signature {:instructions "x" :inputs [{:name "a" :schema {:$ref "#/x"}}]
                                                                          :outputs [{:name "b"}]}})
                            [:error :code]))))
    (acp/end! (:conn c))))

;; --- a structured answer (byj-g5n.10) ----------------------------------------------

(deftest an-answer-is-given-back-as-the-data-asked-for
  (let [dir (fresh-dir "structured")
        c (with-ext (client))
        {:keys [sessionId]} (new-session c dir (script! dir [{:answer "Order 1042 was charged twice; the second charge was refunded on 3 October." :goal-achieved true}
                                                             ;; the structuring call: a priority that is no choice, refused…
                                                             {:reasoning "r" :order "1042" :refunded true :priority "urgent"}
                                                             ;; …and asked again with what was wrong; a field the
                                                             ;; answer does not state, null
                                                             {:reasoning "r" :order "1042" :refunded true :priority "high" :note nil}]))
        schema {:type "object"
                :properties {:order {:type "string" :description "the order id"}
                             :refunded {:type "boolean"}
                             :priority {:enum ["high" "normal"]}
                             :note {:type "string"}}
                :required ["order" "refunded" "priority"]}]
    (try
      (let [r (:result (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "what happened to 1042?"}]
                                                 :_meta {:brainyard {:outputSchema schema}}}))]
        (testing "the turn's answer, and the same answer as the schema's fields, repaired once"
          (is (str/starts-with? (get-in r [:_meta :brainyard :answer]) "Order 1042"))
          (is (= {:order "1042" :refunded true :priority "high" :note nil}
                 (get-in r [:_meta :brainyard :structuredOutput])))))
      (testing "a schema this cannot read is refused before the turn runs"
        (let [r (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "x"}]
                                          :_meta {:brainyard {:outputSchema {:oneOf []}}}})]
          (is (= -32602 (get-in r [:error :code])))
          (is (str/includes? (get-in r [:error :message]) "oneOf"))))
      (finally (acp/end! (:conn c))))))

;; --- a session this process did not start (byj-g5n.11) --------------------------------

(deftest a-live-session-is-listed-asked-and-watched
  (let [dir (fresh-dir "live")
        config {:project-dir dir :working-dir dir :ask-timeout-ms 5000}
        sid "1792900000000-live0001"
        _ (persist/lock! config sid)
        ch (channel/open! config sid {:cancel! (constantly true) :label "the-live-one"})
        stop? (atom false)
        ;; the session: each request a turn, answered — what the session tree
        ;; does through next-turn!, turn-started! and turn-ended!
        _ (.start (Thread. #(loop []
                              (when-not @stop?
                                (if-let [{:keys [text]} (channel/next-turn! ch)]
                                  (do (channel/turn-started! ch)
                                      (channel/turn-ended! ch {:answer (str "you asked: " text) :terminated-by :answer-channel
                                                               :usage {:input-tokens 1}}))
                                  (Thread/sleep 10))
                                (recur)))))
        c (with-ext (client))]
    (try
      (testing "listed, live, by its label"
        (let [rows (get-in (call c "_byj/live/list" {:cwd dir}) [:result :sessions])]
          (is (= [sid] (map :sessionId rows)))
          (is (= "the-live-one" (:label (first rows))))
          (is (true? (:live (first rows))))))
      (testing "asked by its label, its answer back"
        (is (= "you asked: how far along?"
               (get-in (call c "_byj/live/ask" {:cwd dir :sessionId "the-live-one" :question "how far along?"}) [:result :answer]))))
      (testing "watched: its events as _byj/live/event, until detached"
        (let [{:keys [attachId sessionId]} (:result (call c "_byj/live/attach" {:cwd dir :sessionId sid :events ["x/ev"]}))
              frames #(filter (fn [m] (= "_byj/live/event" (:method m))) @(:seen c))]
          (is (= sid sessionId))
          (channel/publish! ch :x/ev {:n 1})
          (channel/publish! ch :y/other {:n 2})
          (loop [i 0] (when (and (empty? (frames)) (< i 300)) (Thread/sleep 10) (recur (inc i))))
          (is (= [{:attachId attachId :sessionId sid :event "x/ev" :payload {:n 1}}] (map :params (frames))))
          (call c "_byj/live/detach" {:attachId attachId})
          (loop [i 0] (when (and (seq @(:subscribers ch)) (< i 300)) (Thread/sleep 10) (recur (inc i))))
          (is (empty? @(:subscribers ch)) "detaching unsubscribed")))
      (testing "a session nobody runs is said so"
        (is (= -32602 (get-in (call c "_byj/live/ask" {:cwd dir :sessionId "nobody" :question "q"}) [:error :code]))))
      (finally
        (reset! stop? true)
        (acp/end! (:conn c))
        (channel/close! ch)
        (persist/unlock! config sid)))))

(deftest a-fork-is-a-new-session-from-a-saved-one
  (let [dir (fresh-dir "fork")
        c (client)
        _ (call c "initialize" {:protocolVersion 1})
        model (script! dir [{:answer "first answer"}])
        {:keys [sessionId]} (new-session c dir model)
        _ (call c "session/prompt" {:sessionId sessionId :prompt [{:type "text" :text "first question"}]})
        _ (call c "session/close" {:sessionId sessionId})
        forked "1792900000001-0000abcd"
        r (call c "session/new" {:cwd dir :mcpServers []
                                 :_meta {:brainyard {:provider "mock" :model model :config {:builtin-agents? false}
                                                     :forkFrom sessionId :sessionId forked}}})]
    (try
      (testing "the id the client chose, its history replayed under it"
        (is (= forked (get-in r [:result :sessionId])))
        (is (some #(and (= "user_message_chunk" (:sessionUpdate %)) (= "first question" (get-in % [:content :text])))
                  (updates c forked))))
      (testing "an id not in a session id's shape is refused"
        (is (str/includes? (get-in (call c "session/new" {:cwd dir :mcpServers [] :_meta {:brainyard {:sessionId "../x"}}})
                                   [:error :message])
                           "<millis>-<8 hex>")))
      (finally (acp/end! (:conn c))))))

(deftest a-session-that-listens-is-told-of-the-turns-put-in-and-shares-through-share
  ;; byj-p2q: a session byj acp holds listens on its channel when the client asks,
  ;; so another session, a script or a share's sidecar puts turns in, which the
  ;; client is told of; `_byj/share` runs /share between its turns
  (let [dir (fresh-dir "channel")
        c (with-ext (client))
        {:keys [sessionId]} (new-session c dir (script! dir [{:answer "put in, answered" :goal-achieved true}])
                                         {:channel true})
        path (get-in @(:state (:conn c)) [:sessions sessionId :channel :path])]
    (try
      (testing "the session listens on its channel, and lists itself live"
        (is (string? path))
        (is (.exists (io/file path)))
        (is (some #(and (= sessionId (:session-id %)) (:live? %)) (channel/sessions {:project-dir dir :working-dir dir}))))
      (testing "a turn put in over the channel is run and answered there"
        (is (= {:status :ok :injected :turn :answer "put in, answered"}
               (select-keys (channel/request! path {:op :inject :as :turn :text "from a script"} 20000)
                            [:status :injected :answer]))))
      (let [us (updates c sessionId)
            turns (keep #(when (= "_byj/turn" (:method %)) (:params %)) @(:seen c))]
        (testing "the client is told the question as the person's message"
          (is (some #(and (= "user_message_chunk" (:sessionUpdate %)) (= "from a script" (get-in % [:content :text]))) us)))
        (testing "and the answer as the agent's"
          (is (some #(and (= "agent_message_chunk" (:sessionUpdate %)) (= "put in, answered" (get-in % [:content :text]))) us)))
        (testing "and when the turn it sent no prompt for starts and ends"
          (is (= ["started" "ended"] (mapv :state turns)))
          (is (every? #(= sessionId (:sessionId %)) turns))))
      (testing "_byj/share runs /share: with no share, who watches says so"
        (let [r (call c "_byj/share" {:sessionId sessionId :args ""})]
          (is (string? (get-in r [:result :message])) (pr-str r))))
      (testing "_byj/share start starts the sidecar on the session's own channel"
        (let [stub (doto (io/file dir "byj-share-stand-in")
                     ;; a stand-in sidecar: says how it was started, then waits to be stopped
                     (spit (str "#!/bin/sh\necho \"$@\" > \"$(dirname \"$0\")/argv.txt\"\n"
                                "trap 'exit 0' TERM INT\nwhile true; do sleep 0.2; done\n"))
                     (.setExecutable true))
              argv (io/file dir "argv.txt")
              {shared :sessionId} (new-session c dir (script! dir [{:answer "x" :goal-achieved true}])
                                               {:channel true :config {:builtin-agents? false :share-sidecar (.getPath stub)}})
              sock (get-in @(:state (:conn c)) [:sessions shared :channel :path])]
          (.delete argv)
          (let [r (call c "_byj/share" {:sessionId shared :args "start --target link"})]
            (is (string? (get-in r [:result :message])) (pr-str r)))
          (is (loop [n 0] (cond (.exists argv) true (> n 100) false :else (do (Thread/sleep 50) (recur (inc n))))))
          (let [words (str/split (str/trim (slurp argv)) #"\s+")]
            (is (= ["run" "--socket" sock "--target" "link" "--session" shared] (take 7 words)) (pr-str words)))
          (testing "and stop ends it"
            (is (string? (get-in (call c "_byj/share" {:sessionId shared :args "stop"}) [:result :message]))))))
      (testing "a session that does not listen is told why _byj/share cannot run"
        (let [{other :sessionId} (new-session c dir (script! dir [{:answer "x" :goal-achieved true}]))]
          (is (str/includes? (get-in (call c "_byj/share" {:sessionId other :args ""}) [:error :message]) "does not listen"))))
      (testing "a session not asked to listen opens no channel: the two asked do"
        (is (= 2 (count (filter #(:live? %) (channel/sessions {:project-dir dir :working-dir dir}))))))
      (finally (acp/end! (:conn c))))))

(deftest a-turn-a-viewer-puts-in-runs-once-the-owner-allows-it
  ;; byj-p2q: RFC-043's :approval :owner, the owner being the client, asked
  ;; through session/request_permission: allowed, the turn runs; rejected, the
  ;; viewer is told so and nothing runs
  (let [dir (fresh-dir "channel-approval")
        choice (atom "allow-once")
        asked (atom [])
        c (with-ext (client :answer (fn [m]
                                       (when (= "session/request_permission" (:method m))
                                         (swap! asked conj (:params m))
                                         {:outcome {:outcome "selected" :optionId @choice}}))))
        {:keys [sessionId]} (new-session c dir (script! dir [{:answer "allowed, answered" :goal-achieved true}])
                                         {:channel true})
        path (get-in @(:state (:conn c)) [:sessions sessionId :channel :path])
        put-in #(channel/request! path {:op :inject :as :turn :text % :approval :owner
                                        :from {:user "u-1" :name "Kim"}} 20000)]
    (try
      (testing "allowed, the turn runs and is answered"
        (is (= "allowed, answered" (:answer (put-in "may I?"))))
        (let [q (first @asked)]
          (is (= sessionId (:sessionId q)))
          (is (str/includes? (get-in q [:toolCall :title]) "Kim"))
          (is (str/includes? (get-in q [:toolCall :title]) "may I?"))
          (is (= ["allow-once" "reject-once"] (mapv :optionId (:options q))))))
      (testing "the client is told who put it in"
        (is (some #(= "Kim" (get-in % [:_meta :brainyard :injectedBy :name]))
                  (filter #(= "user_message_chunk" (:sessionUpdate %)) (updates c sessionId)))))
      (testing "rejected, the viewer is told so and no turn runs"
        (reset! choice "reject-once")
        (let [before (count (filter #(= "_byj/turn" (:method %)) @(:seen c)))
              r (put-in "and again?")]
          (is (= :error (:status r)))
          (is (str/includes? (str (:error r)) "rejected"))
          (is (= before (count (filter #(= "_byj/turn" (:method %)) @(:seen c)))))))
      (finally (acp/end! (:conn c))))))
