(ns brainyard.mcp-test
  "MCP servers as tools (RFC-024), against a stub server that is awkward in the
  ways a real one is: it interleaves a notification with its reply, reports a
  tool error through `isError`, and offers a tool whose name cannot be an id.

  The stub is a subprocess speaking the real protocol, not a mock of the
  client — the transport is the part most likely to be wrong, so it is the part
  under test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.schema :as schema]
            [brainyard.agents :as agents]
            [brainyard.effect :as fx]
            [brainyard.mcp :as mcp]
            [brainyard.mcp-registry :as mcp-registry]
            [brainyard.tools :as tools]))

(def ^:private stub
  {:mcp-servers {"stub" {:command "python3" :args ["test/fixtures/mcp/stub_server.py"]}}})

(defn- with-stub
  "Connect, run `f` with the result, always stop the server."
  [f]
  (let [r (mcp/connect-all! stub)]
    (try (f r) (finally (mcp/disconnect-all! (:clients r))))))

(defn- tool-of [r n] (first (filter #(= (str "mcp$stub$" n) (:name %)) (:tools r))))

;; --- the transport ----------------------------------------------------------------

(deftest a-server-becomes-tools
  (with-stub
    (fn [{:keys [clients tools problems]}]
      (testing "the handshake completed and the server named itself"
        (is (= ["stub"] (keys clients)))
        (is (= "stub" (:name (:server-info (get clients "stub")))))
        (is (mcp/alive? (get clients "stub"))))

      (testing "its tools are ours, named mcp$<server>$<tool>"
        (is (= #{"mcp$stub$echo" "mcp$stub$write-thing" "mcp$stub$boom"}
               (set (map :name tools)))))

      (testing "a tool whose name cannot be an id is named, not silently dropped"
        (is (some #(str/includes? % "bad name!") problems))))))

(deftest a-notification-is-not-mistaken-for-a-reply
  ;; the stub sends notifications/tools/list_changed before answering
  ;; tools/list, as the reference server does. A client that reads the next
  ;; line and calls it its answer gets the notification and reports no tools.
  (with-stub
    (fn [{:keys [tools problems]}]
      (is (= 3 (count tools)) "the reply was found past the notification")
      (is (not-any? #(str/includes? % "unparseable") problems)))))

(deftest json-schema-becomes-a-malli-map
  (with-stub
    (fn [r]
      (let [p (into {} (map (juxt :name identity)) (schema/inputs (tool-of r "echo")))]
        (is (= "string" (:type (:message p))))
        (is (= "what to say" (:desc (:message p))))
        (is (not (:optional? (:message p))) "it is in :required")
        (is (= "integer" (:type (:times p))))
        (is (true? (:optional? (:times p))) "it is not"))))
  (testing "brainyard's conversion: enum, arrays by their items, objects by their properties"
    (is (= [:map
            [:mode [:enum {:desc "how"} "a" "b"]]
            [:tags {:optional true} [:vector :string]]
            [:opts {:optional true} [:map [:depth :int] [:deep {:optional true} :boolean]]]
            [:n {:optional true} [:or :int :double]]
            [:x {:optional true} :any]]
           (mcp/input-schema {:type "object" :required ["mode"]
                              :properties {:mode {:enum ["a" "b"] :description "how"}
                                           :tags {:type "array" :items {:type "string"}}
                                           :opts {:type "object" :required ["depth"]
                                                  :properties {:depth {:type "integer"} :deep {:type "boolean"}}}
                                           :n {:type "number"}
                                           :x {:oneOf [{:type "string"}]}}})))
    (is (= [:map] (mcp/input-schema nil)))))

;; --- what a call returns -----------------------------------------------------------

(deftest a-result-is-flattened-to-what-a-model-can-read
  (with-stub
    (fn [r]
      (testing "content blocks become text"
        (is (= {:result "echo: hi"} ((:fn (tool-of r "echo")) {:message "hi"} {}))))

      (testing "a tool that reports an error is an ERROR, not a success carrying one"
        ;; brainyard never reads isError, so this arrives as a success with the
        ;; failure buried in a nested block (divergence mcp-results-are-flattened)
        (is (= {:error-message "the thing went wrong"}
               ((:fn (tool-of r "boom")) {} {})))))))

(deftest a-protocol-error-reaches-the-model-with-its-code
  (with-stub
    (fn [{:keys [clients]}]
      (let [c (get clients "stub")
            r (mcp/call-tool c "no-such-tool" {})]
        ;; brainyard keeps the code and message in ex-data and surfaces a
        ;; generic string
        (is (str/includes? (:error-message r) "-32602"))
        (is (str/includes? (:error-message r) "no such tool"))))))

;; --- what it costs the permission gate ----------------------------------------------

(deftest a-tool-that-does-not-say-it-is-read-only-is-treated-as-writing
  (with-stub
    (fn [r]
      (is (= #{:mcp/call} (:effects (tool-of r "echo"))) "it declared readOnlyHint")
      (is (= #{:mcp/call :mcp/write} (:effects (tool-of r "write-thing")))
          "it did not, so the gate can reach it with --ask-effects mcp/write")
      (testing "and the description says which, since the model reads that"
        (is (str/includes? (:description (tool-of r "echo")) "read-only"))
        (is (not (str/includes? (:description (tool-of r "write-thing")) "read-only")))))))

;; --- failure is reported, never thrown ------------------------------------------------

(deftest a-server-that-will-not-start-does-not-cost-the-others
  (let [r (mcp/connect-all!
           {:mcp-servers {"broken" {:command "definitely-not-a-program-xyz"}
                          "stub" {:command "python3" :args ["test/fixtures/mcp/stub_server.py"]}}})]
    (try
      (is (= ["stub"] (keys (:clients r))) "the working server still connected")
      (is (= 3 (count (:tools r))))
      (is (some #(str/includes? % "broken") (:problems r)))
      (finally (mcp/disconnect-all! (:clients r))))))

(deftest a-server-with-no-command-is-refused-before-anything-is-spawned
  (is (:error (mcp/connect! "x" {})))
  (is (str/includes? (:error (mcp/connect! "x" {})) ":command")))

(deftest a-disabled-server-is-not-started
  (let [r (mcp/connect-all! {:mcp-servers {"stub" {:command "python3"
                                                   :args ["test/fixtures/mcp/stub_server.py"]
                                                   :enabled false}}})]
    (is (empty? (:clients r)))
    (is (empty? (:tools r)))))

;; --- in the registry -------------------------------------------------------------------

(deftest mcp-tools-are-tools-like-any-other
  (with-stub
    (fn [{:keys [clients tools]}]
      (let [ctx (agent/make-ctx {:config (assoc stub :working-dir "." :project-dir ".")
                                 :tools (into (vec tools/builtin-tools) tools)
                                 :mcp-clients clients})]
        (testing "they are in the registry, gated and called like the rest"
          (is (some? (get @(:tools ctx) "mcp$stub$echo")))
          (is (= {:result "echo: through the registry"}
                 (tools/call-tool (get @(:tools ctx) "mcp$stub$echo")
                                  {:message "through the registry"} ctx))))

        (testing "a missing required argument is refused by the registry, not the server"
          (is (:error-message (tools/call-tool (get @(:tools ctx) "mcp$stub$echo") {} ctx))))

        (testing "and mcp$servers says what is connected"
          (let [r (tools/call-tool (get @(:tools ctx) "mcp$servers") {} ctx)]
            (is (= 1 (:count r)))
            (is (true? (:running (first (:servers r)))))
            (is (= 3 (count (:tools (first (:servers r))))))))))))

(def ^:private dying
  {"dying" {:command "python3" :args ["test/fixtures/mcp/dying_server.py"]}})

(deftest a-write-to-a-dead-server-is-an-error-not-a-throw
  ;; byj-gly.71, found by running the agent on this repository. A read failure
  ;; already returned rather than threw; a WRITE failure threw
  ;; `process: write to child failed`, so a caller handling one handled neither.
  (let [c (mcp/connect! "dying" (get dying "dying"))]
    (Thread/sleep 500)                                  ; let it exit
    (testing "listing its tools reports, and says what the server complained about"
      (let [r (mcp/list-tools c)]
        (is (str/includes? (:error r) "the server stopped"))
        (is (str/includes? (:error r) "MYSERVICE_TOKEN")
            "the stderr it died with, which is the whole diagnostic (byj-gly.72)")))

    (testing "so does calling one"
      (is (str/includes? (:error-message (mcp/call-tool c "x" {})) "the server stopped")))))

(deftest one-server-dying-costs-the-others-nothing
  ;; connect-all! reaches tools/list through request!, so before this a server
  ;; that died after its handshake threw out of the whole connect — which is
  ;; precisely the containment connect-all! exists to provide.
  (let [r (mcp/connect-all! {:mcp-servers (merge dying (:mcp-servers stub))})]
    (try
      (is (= 3 (count (:tools r))) "the working server's tools are all here")
      (is (some #(str/includes? % "MYSERVICE_TOKEN") (:problems r))
          "and the dead one is a reported problem, with its reason")
      (testing "the dead server stays listed, as configured-but-not-running"
        ;; dropping it would make a server that is dying every run invisible
        (is (contains? (:clients r) "dying"))
        (is (false? (mcp/alive? (get (:clients r) "dying")))))
      (finally (mcp/disconnect-all! (:clients r))))))

(deftest a-subagent-reaches-the-servers-its-roster-names
  ;; byj-gly.70 was filed claiming it could not. It could: `child-opts` hands
  ;; the child `:base-tools`, which is whatever `main` put there, MCP tools
  ;; included. What was missing was only the discovery command. This pins the
  ;; behaviour that was guessed at rather than read.
  (with-stub
    (fn [{:keys [clients tools]}]
      (let [parent (agent/make-ctx {:config (assoc stub :working-dir "." :project-dir ".")
                                    :tools (into (vec tools/builtin-tools) tools)
                                    :mcp-clients clients})
            child-ctx (fn [globs]
                        (agent/make-ctx (#'agents/child-opts
                                         {:name "x" :instruction "i" :tools globs}
                                         parent)))]

        (testing "a roster that names an MCP tool gets it, and it works"
          (let [c (child-ctx ["mcp$stub$echo"])]
            (is (= {:result "echo: from a child"}
                   (tools/call-tool (get @(:tools c) "mcp$stub$echo")
                                    {:message "from a child"} c)))))

        (testing "and a roster that does not, does not"
          (let [c (child-ctx ["mcp$stub$echo"])]
            (is (nil? (get @(:tools c) "mcp$stub$write-thing")))))

        (testing "a child may also ask what is connected"
          (let [c (child-ctx ["mcp$*"])]
            (is (= 1 (:count (tools/call-tool (get @(:tools c) "mcp$servers") {} c))))))

        (testing "a child with no MCP globs sees none of it"
          (let [c (child-ctx ["read-file"])]
            (is (not-any? #(str/starts-with? % "mcp$") (keys @(:tools c))))))))))

(deftest a-dead-server-is-reported-rather-than-hanging
  (let [r (mcp/connect-all! stub)
        t (tool-of r "echo")]
    (mcp/disconnect-all! (:clients r))
    ;; destroy-tree is asynchronous, so the call either finds the client dead
    ;; (`not running`) or reaches a dying one and reads EOF — both must say so
    ;; in words, which is why an EOF is not reported as `MCP error -1: eof`
    (is (re-find #"not running|server stopped"
                 (str (:error-message ((:fn t) {:message "hi"} {}))))
        "the tool stays bound and says plainly that its server is gone")))

;; --- the run's live set (byj-gly.182) ----------------------------------------------

(deftest a-server-starts-and-stops-while-the-run-goes-on
  (let [b (mcp/box)
        spec (get-in stub [:mcp-servers "stub"])]
    (try
      (testing "started: its client and tools are in the box, and the version moved"
        (is (= {:started "stub" :tools 3} (select-keys (mcp/start-server! b "stub" spec) [:started :tools])))
        (is (= ["stub"] (keys (mcp/box-clients b))))
        (is (= 3 (count (mcp/box-tools b))))
        (is (= 1 (:version @b))))
      (testing "a context built now binds them; one built with no box does not"
        (let [ctx (agent/make-ctx {:config {:working-dir "." :project-dir "."} :mcp-box b})]
          (is (some? (get @(:tools ctx) "mcp$stub$echo")))
          (is (= 1 (:mcp-version ctx)))
          (is (not-any? #(str/starts-with? (:name %) "mcp$") (:base-tools ctx))
              "and keeps them out of :base-tools, which a child adds the box to again")))
      (testing "starting it again is refused; stopping it takes its tools out"
        (is (:error (mcp/start-server! b "stub" spec)))
        (is (= {:stopped "stub"} (mcp/stop-server! b "stub")))
        (is (empty? (mcp/box-tools b)))
        (is (:error (mcp/stop-server! b "stub")) "stopping what is not running says so"))
      (testing "restart brings it back"
        (is (= "stub" (:started (mcp/restart-server! b "stub" spec))))
        (is (= 3 (count (mcp/box-tools b)))))
      (testing "a server that will not start is kept as its problem"
        (mcp/start-server! b "broken" {:command "definitely-not-a-command-xyz"})
        (is (str/includes? (get-in @b [:problems "broken"]) "broken")))
      (finally (mcp/disconnect-all! (mcp/box-clients b))))))

(deftest slash-mcp-manages-the-servers-and-their-tools
  (let [dir (str "target/mcp-test/slash-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir ".brainyard"))
        _ (spit (java.io.File. dir ".brainyard/config.edn") (pr-str {:agent {:config stub}}))
        load #(let [{:keys [config sources]} ((requiring-resolve 'brainyard.config/load-config)
                                              {:working-dir dir :project-dir dir :user-dir (str dir "/no-user")})]
                {:config (assoc config :config-sources sources :persist-session? false :max-iterations 4)})
        b (mcp/box)
        _ (mcp/start-all! b (:config (load)))
        prompts (atom [])
        out (atom [])
        lines (atom ["/mcp" "/mcp stub" "/mcp stub tools" "/mcp stub hide echo" "/mcp stub tools"
                     "/mcp stub stop" "/mcp" "hello" "/mcp stub start" "/mcp stub disable" "/mcp stub"
                     "/mcp stub show" "/mcp nope"])
        session-run (requiring-resolve 'brainyard.session/run)]
    (try
      (session-run {:lm {:provider :mock :format :mock
                         :responses (fn [msgs] (swap! prompts conj (:content (first msgs)))
                                      "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
                    :config (:config (load)) :config-loaded (:config (load)) :reload-config load
                    :mcp-box b
                    :read-input (fn [_] (let [[x] (swap-vals! lines #(vec (rest %)))] (first x)))
                    :emit (fn [kind text] (swap! out conj [kind text]))})
      (let [said (vec (map second (filter #(= :info (first %)) @out)))
            find (fn [pre] (filter #(str/includes? % pre) said))
            file #(read-string (slurp (java.io.File. dir ".brainyard/config.edn")))]
        (testing "the list and one server in full"
          (is (some #(str/includes? % "● stub  running · 3 tool(s)") said))
          (is (some #(str/includes? % "1/1 running") said))
          (is (some #(and (str/starts-with? % "MCP server stub") (str/includes? % "state:     running")) said)))
        (testing "its tools, and one hidden through :hidden-tools in the project config"
          (is (some #(str/includes? % "● mcp$stub$echo") said))
          (is (some #(str/starts-with? % "hidden: mcp$stub$echo") said))
          (is (some #(str/includes? % "○ mcp$stub$echo (hidden)") said)))
        (testing "stopped, its tools are gone from the next turn's prompt"
          (is (seq (find "stopped stub")))
          (is (some #(str/includes? % "· stub  stopped") said))
          (is (not (str/includes? (last @prompts) "mcp$stub$add"))))
        (testing "started again, then disabled in the project file, which stops it"
          (is (seq (find "started stub · 3 tool(s)")))
          (is (seq (find "stub disabled in the project config · disabled now")) "the edit applies: it stopped")
          (is (some #(and (str/starts-with? % "MCP server stub") (str/includes? % "state:     disabled")) said))
          (is (false? (get-in (file) [:agent :config :mcp-servers "stub" :enabled]))))
        (testing "shown again: every glob naming the server goes"
          (is (seq (find "shown: every tool of stub")))
          (is (empty? (get-in (file) [:agent :config :hidden-tools]))))
        (testing "an unknown server says what is configured"
          (is (seq (find "no MCP server nope — configured: stub")))))
      (finally (mcp/disconnect-all! (mcp/box-clients b))))))

;; --- the catalogue, and ${VAR} (byj-gly.183) -----------------------------------------

(deftest a-variable-is-expanded-when-a-server-starts
  (let [env {"TOKEN" "t-1" "EMPTY" ""}
        env-fn #(get env %)]
    (is (= "a t-1 b" (mcp/expand "a ${TOKEN} b" env-fn)))
    (is (= "fallback" (mcp/expand "${NOPE:-fallback}" env-fn)))
    (is (= "fallback" (mcp/expand "${EMPTY:-fallback}" env-fn)) "empty is unset")
    (is (= "" (mcp/expand "${NOPE}" env-fn)))
    (testing "what a spec needs: :needs, and every ${VAR} with no default"
      (let [spec {:args ["${A}" "${B:-x}"] :env {"K" "${C}"} :needs ["D"]}]
        (is (= ["D" "A" "C"] (mcp/vars-of spec)))
        (is (= ["A" "C"] (mcp/missing spec {"D" "d"})))))
    (testing "resolved: args and env expanded, and what it needs passed in"
      (is (= {:args ["x t-1"] :env {"TOKEN" "t-1" "HDR" "Bearer t-1"} :needs ["TOKEN"]}
             (mcp/resolve-spec {:args ["x ${TOKEN}"] :env {"HDR" "Bearer ${TOKEN}"} :needs ["TOKEN"]} env-fn))))))

(deftest the-catalogue-is-a-resource-a-project-can-extend
  (let [dir (str "target/mcp-test/catalog-" (System/currentTimeMillis))
        bundled (mcp/catalog dir)]
    (testing "the bundled entries: brainyard's seeds and the reference servers, each startable here"
      (is (every? #(and (:name %) (:command %) (:description %)) bundled))
      (is (some #(= "github" (:name %)) bundled))
      (is (some #(= "everything" (:name %)) bundled))
      (is (not-any? #(str/starts-with? (str (:command %)) "/opt/") bundled) "no machine's own paths")
      (is (= ["GITHUB_PERSONAL_ACCESS_TOKEN"] (:needs (first (filter #(= "github" (:name %)) bundled))))))
    (testing "a project's own catalogue adds and overrides by name"
      (.mkdirs (java.io.File. dir ".brainyard/mcp"))
      (spit (java.io.File. dir ".brainyard/mcp/catalog.edn")
            (pr-str [{:name "github" :description "ours" :command "gh-mcp"}
                     {:name "stub" :description "the test stub" :command "python3" :args ["x.py"]}]))
      (let [c (mcp/catalog dir)]
        (is (= "ours" (:description (first (filter #(= "github" (:name %)) c)))))
        (is (some #(= "stub" (:name %)) c))
        (is (= (count c) (inc (count bundled))))))
    (testing "what add writes is the spec, not the catalogue's own words"
      (is (= {:command "npx" :args ["-y" "x"] :needs ["T"]}
             (mcp/entry->spec {:name "n" :description "d" :tags ["t"] :command "npx" :args ["-y" "x"] :needs ["T"]}))))))

(deftest slash-mcp-edits-only-the-projects-own-servers
  ;; /mcp wrote the MERGED :mcp-servers into the project file: a user server,
  ;; its :env with it, landed in a file that gets committed (byj-gly.220)
  (let [dir (str "target/mcp-test/own-" (System/currentTimeMillis))
        user-dir (str dir "/home")
        stub-path (.getAbsolutePath (java.io.File. "test/fixtures/mcp/stub_server.py"))
        _ (.mkdirs (java.io.File. dir ".brainyard/mcp"))
        _ (.mkdirs (java.io.File. user-dir ".brainyard"))
        _ (spit (java.io.File. dir ".brainyard/mcp/catalog.edn")
                (pr-str [{:name "stubby" :description "the test stub" :tags ["test"] :command "python3" :args [stub-path]}]))
        user-file (java.io.File. user-dir ".brainyard/config.edn")
        _ (spit user-file (pr-str {:agent {:config {:mcp-servers {"usertok" {:command "python3" :args [stub-path]
                                                                              :env {"TOKEN" "value-from-the-user-file"}
                                                                              :enabled false}}}}}))
        load #(let [{:keys [config sources]} ((requiring-resolve 'brainyard.config/load-config)
                                              {:working-dir dir :project-dir dir :user-dir user-dir})]
                ;; configbox finds the user's file by :user-dir ($HOME in a run)
                {:config (assoc config :config-sources sources :persist-session? false :max-iterations 4
                                :user-dir user-dir)})
        b (mcp/box)
        out (atom [])
        lines (atom ["/mcp add stubby" "/mcp usertok enable" "/mcp remove usertok"])]
    (try
      ((requiring-resolve 'brainyard.session/run)
       {:lm {:provider :mock :format :mock
             :responses (fn [_] "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
        :config (:config (load)) :config-loaded (:config (load)) :reload-config load
        :mcp-box b
        :read-input (fn [_] (let [[x] (swap-vals! lines #(vec (rest %)))] (first x)))
        :emit (fn [kind text] (swap! out conj [kind text]))})
      (let [said (vec (map second @out))
            project-text (slurp (java.io.File. dir ".brainyard/config.edn"))]
        (testing "the project file holds only what the project said"
          (is (not (str/includes? project-text "value-from-the-user-file")) "no user server's env")
          (is (= #{"stubby"} (set (keys (get-in (read-string project-text) [:agent :config :mcp-servers]))))
              "enable wrote {:enabled true} over the user's spec, and remove took it back out"))
        (testing "servers merge per server across the files, so the user's is still there"
          (is (= "python3" (get-in (:config (load)) [:mcp-servers "usertok" :command]))))
        (testing "removing a user server from a project says where it lives"
          (is (some #(str/includes? (str %) "usertok is in the user config") said)))
        (testing "and the user file is left as it was"
          (is (str/includes? (slurp user-file) "value-from-the-user-file"))))
      (finally (mcp/disconnect-all! (mcp/box-clients b))))))

(deftest slash-mcp-finds-adds-and-removes
  (let [dir (str "target/mcp-test/add-" (System/currentTimeMillis))
        stub-path (.getAbsolutePath (java.io.File. "test/fixtures/mcp/stub_server.py"))
        _ (.mkdirs (java.io.File. dir ".brainyard/mcp"))
        _ (spit (java.io.File. dir ".brainyard/mcp/catalog.edn")
                (pr-str [{:name "stubby" :description "the test stub" :tags ["test"]
                          :command "python3" :args [stub-path]}
                         {:name "needy" :description "a stub that needs a token" :tags ["test"]
                          :command "python3" :args [stub-path] :needs ["BY_TEST_NEVER_SET_TOKEN"]}]))
        load #(let [{:keys [config sources]} ((requiring-resolve 'brainyard.config/load-config)
                                              {:working-dir dir :project-dir dir :user-dir (str dir "/no-user")})]
                ;; the catalogue only: /mcp find also searches the public
                ;; registry, which this test does not read, and waited out
                ;; its 10 s timeout twice where the network was slow or shut
                ;; (byj-gly.382)
                {:config (assoc config :config-sources sources :persist-session? false :max-iterations 4
                                :mcp-registry? false)})
        b (mcp/box)
        out (atom [])
        lines (atom ["/mcp" "/mcp find test" "/mcp add stubby" "/mcp add needy" "/mcp find test"
                     (str "/mcp add own -- python3 " stub-path) "/mcp own hide echo" "/mcp"
                     "/mcp remove own" "/mcp add stubby" "/mcp add nothing-like-it" "/mcp"])]
    (try
      ((requiring-resolve 'brainyard.session/run)
       {:lm {:provider :mock :format :mock
             :responses (fn [_] "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
        :config (:config (load)) :config-loaded (:config (load)) :reload-config load
        :mcp-box b
        :read-input (fn [_] (let [[x] (swap-vals! lines #(vec (rest %)))] (first x)))
        :emit (fn [kind text] (swap! out conj [kind text]))})
      (let [said (vec (map second (filter #(= :info (first %)) @out)))
            has (fn [s] (some #(str/includes? % s) said))
            file #(read-string (slurp (java.io.File. dir ".brainyard/config.edn")))]
        (testing "nothing configured says where to look"
          (is (has "no MCP servers configured — /mcp find lists the catalogue")))
        (testing "find lists the catalogue, with what each needs"
          (is (has "● stubby — the test stub [test] · ready"))
          (is (has "○ needy — a stub that needs a token [test] · needs BY_TEST_NEVER_SET_TOKEN")))
        (testing "add: a ready one starts, a needy one is added disabled and says what to set"
          (is (has "added stubby to the project config · running · 3 tool(s)"))
          (is (has "added needy to the project config · disabled until BY_TEST_NEVER_SET_TOKEN is set"))
          (is (false? (get-in (file) [:agent :config :mcp-servers "needy" :enabled])))
          (is (has "✓ stubby — the test stub [test] · added") "find says what is already there"))
        (testing "one of your own, from a command line"
          (is (has "added own to the project config · running")))
        (testing "remove stops it and takes its hidden-tools globs with it"
          (is (has "removed own from the project config · and 1 :hidden-tools glob(s) for it"))
          (is (nil? (get-in (file) [:agent :config :mcp-servers "own"])))
          (is (empty? (get-in (file) [:agent :config :hidden-tools])))
          (is (not (contains? (mcp/box-clients b) "own"))))
        (testing "twice, or not in the catalogue, is refused"
          (is (has "stubby is already configured"))
          (is (has "no nothing-like-it in the catalogue"))))
      (finally (mcp/disconnect-all! (mcp/box-clients b))))))

;; --- the MCP registry (byj-gly.185) --------------------------------------------------

(def ^:private registry-body (slurp "test/fixtures/mcp/registry_filesystem.json"))

(deftest a-registry-server-becomes-something-this-port-can-start
  (let [es (mcp-registry/parse registry-body)
        by (fn [full] (first (filter #(= full (:registry-name %)) es)))]
    (testing "an npm package runs through npx, pinned to the version published"
      (is (= {:command "npx" :args ["-y" "filesystem-mcp@1.3.0"] :kind "npm" :name "filesystem-mcp"}
             (select-keys (by "io.github.domdomegg/filesystem-mcp") [:command :args :kind :name]))))
    (testing "what it requires becomes what it needs, as ${VAR}"
      (is (= ["WORKSPACE_ROOT"] (:needs (by "io.github.Digital-Defiance/mcp-filesystem"))))
      (is (= "${WORKSPACE_ROOT}" (get-in (by "io.github.Digital-Defiance/mcp-filesystem") [:env "WORKSPACE_ROOT"]))))
    (testing "a PyPI package through uvx, a hosted one through mcp-remote"
      (is (= ["vs-filesystem-mcp-server==0.1.3"] (:args (by "io.github.Oncorporation/filesystem-server"))))
      (is (= "mcp-remote" (second (:args (by "io.github.Evozim/chroot-filesystem-jail-mcp"))))))
    (testing "one it cannot start says why"
      (is (str/includes? (:unsupported (by "io.github.fieldcure/filesystem")) "nuget")))
    (testing "the name it is added under is a usable server name"
      (is (every? #(mcp/safe-name? (:name %)) es)))))

(deftest slash-mcp-searches-the-registry-and-adds-from-it-disabled
  (let [dir (str "target/mcp-test/registry-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir ".brainyard"))
        load #(let [{:keys [config sources]} ((requiring-resolve 'brainyard.config/load-config)
                                              {:working-dir dir :project-dir dir :user-dir (str dir "/no-user")})]
                {:config (assoc config :config-sources sources :persist-session? false :max-iterations 4)})
        b (mcp/box)
        out (atom [])
        searched (atom [])
        lines (atom ["/mcp find filesystem" "/mcp add filesystem-mcp" "/mcp add mcp-filesystem"
                     "/mcp add io.github.Digital-Defiance/mcp-filesystem" "/mcp filesystem-mcp"])]
    (with-redefs [mcp-registry/search (fn [_ q _] (swap! searched conj q) {:entries (mcp-registry/parse registry-body)})]
      ((requiring-resolve 'brainyard.session/run)
       {:lm {:provider :mock :format :mock
             :responses (fn [_] "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
        :config (:config (load)) :config-loaded (:config (load)) :reload-config load
        :mcp-box b
        :read-input (fn [_] (let [[x] (swap-vals! lines #(vec (rest %)))] (first x)))
        :emit (fn [kind text] (swap! out conj [kind text]))}))
    (let [said (vec (map second (filter #(= :info (first %)) @out)))
          has (fn [x] (some #(str/includes? % x) said))
          file #(read-string (slurp (java.io.File. dir ".brainyard/config.edn")))]
      (testing "find searches the registry after the catalogue, and says it is third-party"
        (is (= ["filesystem"] @searched))
        (is (has "in the MCP registry — third-party code, added disabled"))
        (is (has "○ filesystem-mcp — "))
        (is (has "✗ filesystem — ")))
      (testing "add by the name shown: disabled, and where it came from recorded"
        (is (has "added filesystem-mcp to the project config from the MCP registry (io.github.domdomegg/filesystem-mcp@1.3.0) · disabled — third-party code"))
        (is (= {:command "npx" :args ["-y" "filesystem-mcp@1.3.0"] :enabled false
                :from "io.github.domdomegg/filesystem-mcp@1.3.0"}
               (get-in (file) [:agent :config :mcp-servers "filesystem-mcp"])))
        (is (empty? (mcp/box-clients b)) "nothing was started"))
      (testing "a name two registry servers share asks for the full one, which then works"
        (is (has "several registry servers are called mcp-filesystem"))
        (is (has "added mcp-filesystem to the project config from the MCP registry (io.github.Digital-Defiance/mcp-filesystem@0.1.9) · disabled until WORKSPACE_ROOT is set")))
      (testing "the detail names its source"
        (is (has "from:      the MCP registry, io.github.domdomegg/filesystem-mcp@1.3.0"))))))

(deftest a-session-does-not-wait-for-its-servers
  (let [b (mcp/box)
        t0 (System/currentTimeMillis)]
    (try
      (mcp/start-all-in-background! b stub)
      (testing "it returns at once, the server marked as starting"
        (is (< (- (System/currentTimeMillis) t0) 1000))
        (is (or (contains? (:starting @b) "stub") (contains? (mcp/box-clients b) "stub"))))
      (testing "and the server comes up in the box, its tools with it"
        (loop [n 0]
          (when (and (< n 100) (not (contains? (mcp/box-clients b) "stub")))
            (Thread/sleep 100) (recur (inc n))))
        (is (contains? (mcp/box-clients b) "stub"))
        (is (= 3 (count (mcp/box-tools b))))
        (is (empty? (:starting @b))))
      (finally (mcp/disconnect-all! (mcp/box-clients b))))))

(deftest a-user-tool-waits-for-the-server-it-calls
  ;; a session builds its tools while its servers start in the background, so
  ;; a user tool calling one was skipped as malformed and the first turn — a
  ;; resumed session's first question — ran without it (byj-gly.300)
  (let [dir (str "target/mcp-test/waits-" (System/currentTimeMillis))
        tools (java.io.File. dir ".brainyard/tools")
        _ (.mkdirs tools)
        _ (spit (java.io.File. tools "say.edn") (pr-str {:name "say" :description "echo through the stub"}))
        _ (spit (java.io.File. tools "say.clj") "(fn [args] (mcp$stub$echo {:message \"hi\"}))")
        _ (spit (java.io.File. tools "say-twice.edn") (pr-str {:name "say-twice" :description "calls say"}))
        _ (spit (java.io.File. tools "say-twice.clj") "(fn [args] (user$tool$say {}))")
        _ (spit (java.io.File. tools "nope.edn") (pr-str {:name "nope" :description "calls a server nobody starts"}))
        _ (spit (java.io.File. tools "nope.clj") "(fn [args] (mcp$absent$echo {:message \"hi\"}))")
        connect mcp/connect!
        b (mcp/box)
        events (atom []) prompts (atom []) out (atom [])
        lines (atom ["hello"])]
    ;; the handshake held back, so the session is built and its first turn
    ;; begins while the server is still :starting
    (with-redefs [mcp/connect! (fn [n spec] (Thread/sleep 1500) (connect n spec))]
      (try
        (mcp/start-all-in-background! b stub)
        ((requiring-resolve 'brainyard.session/run)
         {:lm {:provider :mock :format :mock
               :responses (fn [msgs] (swap! prompts conj (:content (first msgs)))
                            "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
          :config {:working-dir dir :project-dir dir :persist-session? false :max-iterations 4}
          :mcp-box b
          :on-event (fn [e d] (swap! events conj [e d]))
          :read-input (fn [_] (let [[x] (swap-vals! lines #(vec (rest %)))] (first x)))
          :emit (fn [kind text] (swap! out conj [kind text]))})
        (let [of (fn [e] (keep (fn [[e' d]] (when (= e e') d)) @events))
              file-of #(.getName (java.io.File. (str (:file %))))]
          (testing "built while its server starts, a user tool calling it waits — and one calling that waits too"
            (is (= #{"say.edn" "say-twice.edn"} (set (map file-of (of :tools/user-tool-waiting))))))
          (testing "neither is reported malformed; one calling a server nobody starts still is"
            (is (= #{"nope.edn"} (set (map file-of (of :tools/user-tool-skipped))))))
          (testing "the first turn waited for the server, and its prompt lists both"
            (is (some #(str/includes? (str (second %)) "waiting for MCP server(s) stub") @out))
            (is (= 1 (count @prompts)))
            (is (str/includes? (first @prompts) "user$tool$say"))
            (is (str/includes? (first @prompts) "user$tool$say-twice"))
            (is (not (str/includes? (first @prompts) "user$tool$nope")))))
        (finally (mcp/stop-all! b))))))

(deftest an-agent-waits-for-the-server-whose-tools-it-names
  ;; a built agent's tool list names a server's tools: its session's first
  ;; turn — on every A2A call that opens one — went on without them while the
  ;; server started (byj-1wv.115.46); an agent with no tool list does not wait
  (testing "the servers a tool list names"
    (is (= ["github"] (mcp/named-servers ["read-file" "mcp$github$get_issue"] ["github" "stub"])))
    (is (= ["github" "stub"] (mcp/named-servers ["mcp$*"] ["github" "stub"])))
    (is (= ["stub"] (mcp/named-servers ["mcp$stub$*"] ["github" "stub"])))
    (is (= [] (mcp/named-servers [] ["github" "stub"])))
    (is (= [] (mcp/named-servers ["read-file" "mcp$gitlab$x"] ["github"]))))
  (doseq [[visible waits?] [[["mcp$stub$echo"] true] [nil false]]]
    (let [connect mcp/connect!
          b (mcp/box)
          prompts (atom []) out (atom [])
          lines (atom ["hello"])]
      (with-redefs [mcp/connect! (fn [n spec] (Thread/sleep 1500) (connect n spec))]
        (try
          (mcp/start-all-in-background! b stub)
          ((requiring-resolve 'brainyard.session/run)
           {:lm {:provider :mock :format :mock
                 :responses (fn [msgs] (swap! prompts conj (:content (first msgs)))
                              "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
            :config (cond-> {:working-dir "." :persist-session? false :max-iterations 4}
                      visible (assoc :visible-tools visible))
            :mcp-box b
            :read-input (fn [_] (let [[x] (swap-vals! lines #(vec (rest %)))] (first x)))
            :emit (fn [kind text] (swap! out conj [kind text]))})
          (if waits?
            (testing "named, the first turn waits for the server and is shown its tool"
              (is (some #(str/includes? (str (second %)) "waiting for MCP server(s) stub") @out))
              (is (str/includes? (first @prompts) "mcp$stub$echo")))
            (testing "with no tool list, the first turn does not wait"
              (is (not-any? #(str/includes? (str (second %)) "waiting for MCP server") @out))))
          (finally (mcp/stop-all! b)))))))

(deftest slash-mcp-registry-feeds-the-picker
  (let [shown (atom [])
        out (atom [])
        lines (atom ["/mcp registry" "/mcp registry filesystem"])]
    (with-redefs [mcp-registry/search (fn [_ _ _] {:entries (mcp-registry/parse registry-body)})]
      ((requiring-resolve 'brainyard.session/run)
       {:lm {:provider :mock :format :mock
             :responses (fn [_] "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
        :config {:working-dir "." :persist-session? false :max-iterations 4}
        :mcp-box (mcp/box)
        :present (fn [kind data] (swap! shown conj [kind data]) true)
        :read-input (fn [_] (let [[x] (swap-vals! lines #(vec (rest %)))] (first x)))
        :emit (fn [kind text] (swap! out conj [kind text]))}))
    (is (some #(= "usage: /mcp registry WORDS" (second %)) @out))
    (let [[kind data] (last @shown)]
      (is (= :mcp kind))
      (is (:refresh? data))
      (is (not (contains? data :rows)) "only the registry changed, so only it is sent")
      (is (= "filesystem-mcp" (:name (first (filter #(= "io.github.domdomegg/filesystem-mcp" (:registry-name %)) (:registry data)))))))))

(deftest a-server-that-is-starting-is-not-started-twice
  ;; a start during another's slow handshake spawned a second process, and the
  ;; one whose client was overwritten was never stopped; stop and disable were
  ;; ignored until it came up (byj-gly.222)
  (let [connected (atom []) disconnected (atom [])
        gates (atom {})
        slow-connect (fn [n spec]
                       (swap! connected conj [n (:args spec)])
                       (let [p (promise)] (swap! gates assoc (count @connected) p) @p)
                       {:name n :args (:args spec)})
        release! (fn [i] (deliver (get @gates i) true))
        started (fn [b f] (let [t (Thread. ^Runnable (fn [] (f)))] (.start t) t))
        wait-for #(loop [k 0] (when (and (not (%)) (< k 200)) (Thread/sleep 10) (recur (inc k))))]
    (with-redefs [mcp/connect! slow-connect
                  mcp/server-tools (fn [_] {:tools [{:name "echo"}]})
                  mcp/disconnect! (fn [c] (swap! disconnected conj (:args c)))]
      (testing "a second start while one is in flight is refused, not spawned"
        (let [b (mcp/box)
              t (started b #(mcp/start-server! b "s" {:args ["one"]}))]
          (wait-for #(= 1 (count @connected)))
          (is (= {:error "s is already starting"} (mcp/start-server! b "s" {:args ["two"]})))
          (release! 1) (.join t 2000)
          (is (= 1 (count @connected)) "one process")
          (is (= ["one"] (:args (get-in @b [:clients "s"]))))))
      (reset! connected []) (reset! disconnected []) (reset! gates {})
      (testing "a stop while it starts disconnects what arrives"
        (let [b (mcp/box)
              t (started b #(mcp/start-server! b "s" {:args ["one"]}))]
          (wait-for #(= 1 (count @connected)))
          (is (= {:stopping "s"} (mcp/stop-server! b "s")))
          (release! 1) (.join t 2000)
          (is (nil? (get-in @b [:clients "s"])) "it is not kept")
          (is (= [["one"]] @disconnected) "and its process is stopped")))
      (reset! connected []) (reset! disconnected []) (reset! gates {})
      (testing "a restart while it starts replaces it with the new spec when it arrives"
        (let [b (mcp/box)
              t (started b #(mcp/start-server! b "s" {:args ["one"]}))]
          (wait-for #(= 1 (count @connected)))
          (is (= {:restarting "s"} (mcp/restart-server! b "s" {:args ["two"]})))
          (release! 1)
          (wait-for #(= 2 (count @connected)))
          (release! 2) (.join t 2000)
          (is (= [["one"]] @disconnected))
          (is (= ["two"] (:args (get-in @b [:clients "s"]))))
          (is (empty? (:starting @b))))))))

(deftest a-server-is-starting-until-its-tools-are-in-the-box
  ;; byj-gly.343 (provenance P-37): the server left :starting once its
  ;; handshake was done, before its tools were listed and the version moved,
  ;; so await-started returned to a session that rebuilt nothing
  (let [listing (promise) listed (promise)]
    (with-redefs [mcp/connect! (fn [n _] {:name n})
                  mcp/server-tools (fn [_] (deliver listing true) @listed {:tools [{:name "echo"}]})
                  mcp/disconnect! (fn [_] nil)]
      (let [b (mcp/box)
            started (fx/run-detached (fx/task-of #(mcp/start-server! b "s" {:args ["one"]})) "start s")]
        (try
          (is (true? (deref listing 5000 false)) "the handshake is done and the tools are being listed")
          (is (= ["s"] (mcp/await-started b ["s"] 200)) "still starting while they are")
          (let [v (:version @b)]
            (deliver listed true)
            (is (= [] (mcp/await-started b ["s"] 5000)) "up once they are in the box")
            (is (= ["echo"] (mapv :name (mcp/box-tools b))))
            (is (not= v (:version @b)) "and the version moved with it, for a session to rebuild on"))
          (finally (deliver listed true) (when (fn? started) (started)) (mcp/stop-all! b)))))))

(deftest reconciling-compares-the-config-with-what-runs
  ;; each session diffed its own before/after, so one restarted what another
  ;; had just started (byj-gly.223); a failed server stayed failed after its
  ;; spec was fixed (byj-gly.224)
  (let [connected (atom [])]
    (with-redefs [mcp/connect! (fn [n spec] (swap! connected conj [n (:args spec)])
                                 (if (= ["bad"] (:args spec)) {:error "boom"} {:name n :args (:args spec)}))
                  mcp/server-tools (fn [_] {:tools [{:name "echo"}]})
                  mcp/disconnect! (fn [_] nil)]
      (let [b (mcp/box)
            a {"s" {:args ["one"]}}]
        (testing "a config applied twice starts once — the second session to see it finds nothing to do"
          (is (= [["s" :started]] (mcp/reconcile! b a)))
          (is (= [] (mcp/reconcile! b a)))
          (is (= 1 (count @connected))))
        (testing "a changed spec restarts it"
          (is (= [["s" :restarted]] (mcp/reconcile! b {"s" {:args ["two"]}}))))
        (testing "a start that failed is not tried again with the same spec, but is once it is fixed"
          (is (= [["t" :started]] (mcp/reconcile! b {"s" {:args ["two"]} "t" {:args ["bad"]}})))
          (is (nil? (get-in @b [:clients "t"])))
          (is (= [] (mcp/reconcile! b {"s" {:args ["two"]} "t" {:args ["bad"]}})))
          (is (= [["t" :started]] (mcp/reconcile! b {"s" {:args ["two"]} "t" {:args ["fixed"]}})))
          (is (some? (get-in @b [:clients "t"]))))
        (testing "one a person stopped stays stopped, until its spec changes"
          (mcp/hold! b "t" {:args ["fixed"]})
          (mcp/stop-server! b "t")
          (is (= [] (mcp/reconcile! b {"s" {:args ["two"]} "t" {:args ["fixed"]}})))
          (is (= [["t" :started]] (mcp/reconcile! b {"s" {:args ["two"]} "t" {:args ["other"]}}))))
        (testing "gone from the config, or disabled, it stops"
          (is (= #{["s" :stopped] ["t" :stopped]}
                 (set (mcp/reconcile! b {"t" {:args ["other"] :enabled false}}))))
          (is (empty? (:clients @b))))))))

(deftest the-runs-servers-end-with-it
  ;; nothing stopped them at exit, and an npx server's grandchildren outlived
  ;; the run (byj-gly.225)
  (let [disconnected (atom []) gate (promise) entered (promise)]
    (with-redefs [mcp/connect! (fn [n spec] (when (= "slow" n) (deliver entered true) @gate) {:name n})
                  mcp/server-tools (fn [_] {:tools [{:name "echo"}]})
                  mcp/disconnect! (fn [c] (swap! disconnected conj (:name c)))]
      (let [b (mcp/box)
            _ (mcp/start-server! b "up" {})
            t (Thread. ^Runnable (fn [] (mcp/start-server! b "slow" {})))]
        (.start t) (deref entered 2000 nil)
        (is (= {:stopped ["up"] :stopping ["slow"]} (mcp/stop-all! b)))
        (is (= ["up"] @disconnected) "what runs is stopped at once")
        (deliver gate true) (.join t 2000)
        (is (= ["up" "slow"] @disconnected) "and what was starting, as soon as it arrives")
        (is (empty? (:clients @b)))))))

(deftest a-registry-search-is-bounded
  ;; the HTTP timeouts summed to 25 s on the session thread (byj-gly.232)
  (with-redefs [brainyard.mcp-registry/search-ms 100
                jolt.http-client/get (fn [& _] (Thread/sleep 3000) {:status 200 :body "{}"})]
    (let [t0 (System/currentTimeMillis)
          r (brainyard.mcp-registry/search nil "x" 5)]
      (is (str/includes? (str (:error r)) "did not answer"))
      (is (< (- (System/currentTimeMillis) t0) 2000) "and the session is not held for the request"))))

(deftest an-only-named-server-is-in-no-registry-but-the-agents-that-name-it
  ;; RFC-024 "A server for one agent": a turn that sets no visible or hidden
  ;; tools is narrowed all the same, so its tools are gone from the registry,
  ;; not merely from the listing — the model cannot call what it cannot see
  (let [b (mcp/box)
        spec (assoc (get-in stub [:mcp-servers "stub"]) :only-named? true)
        config {:working-dir "." :project-dir "." :mcp-servers {"stub" spec}}
        stubs #(set (filter (fn [n] (str/starts-with? n "mcp$stub$")) (keys @(:tools %))))]
    (try
      (mcp/start-server! b "stub" spec)
      (is (empty? (stubs (agent/make-ctx {:config config :mcp-box b}))) "the session's own agent")
      (is (= #{"mcp$stub$echo"} (stubs (agent/make-ctx {:config (assoc config :visible-tools ["mcp$stub$echo" "read-file"]) :mcp-box b})))
          "an agent that names one of them")
      (finally (mcp/stop-all! b)))))

;; --- a server that asks the person (byj-ahf.20) -------------------------------------

(deftest a-server-may-ask-the-person-mid-call
  ;; MCP's elicitation: the server asks the client, which answers through the
  ;; session's permission prompt, never the classifier; Share's app$publish
  ;; asks so before it sends what it publishes
  (let [r (mcp/connect-all! {:mcp-servers {"asks" {:command "python3" :args ["test/fixtures/mcp/eliciting_server.py"]}}})
        tool #(first (filter (fn [t] (= (str "mcp$asks$" %) (:name t))) (:tools r)))
        asked (atom [])
        call (fn [n args permission-fn] ((:fn (tool n)) args {:permission-fn permission-fn}))]
    (try
      (testing "a yes is accepted, after the server's ping is answered"
        (is (= {:result "heard accept; pinged"}
               (call "publish" {:what "clj-web"} (fn [req] (swap! asked conj req) {:allowed true}))))
        (is (= [{:type :mcp/elicitation :tool "mcp$asks$publish" :display "the MCP server `asks` asks: Publish clj-web?"}] @asked)
            "the person is shown what the server asks"))
      (testing "a no is declined"
        (is (= {:result "heard decline; pinged"} (call "publish" {:what "x"} (fn [_] {:denied true})))))
      (testing "with no one to ask, it is cancelled, unanswered"
        (is (= {:result "heard cancel; pinged"} (call "publish" {:what "x"} nil))))
      (testing "fields to fill in are declined: only a yes or a no is answered"
        (reset! asked [])
        (is (= {:result "heard decline; pinged"} (call "fill" {} (fn [req] (swap! asked conj req) {:allowed true}))))
        (is (empty? @asked) "and the person is not asked"))
      (finally (mcp/disconnect-all! (:clients r))))))
