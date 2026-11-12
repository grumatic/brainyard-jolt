;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.config-test
  "Layered configuration (RFC-013): the schema, the file shape, the layer order,
  the environment, and what a bad file does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
            [brainyard.json :as json]
            [brainyard.session :as session]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file "target/config-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- write-config! [dir m]
  (let [f (config/config-file dir)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str m))
    dir))

(defn- load
  "A fixture directory is its own project root, so the walk does not climb out
  of it into this repo."
  [& {:as opts}]
  (config/load-config (merge {:user-dir (fresh-dir "no-user")
                              :project-dir (:working-dir opts)}
                             opts)))

(deftest the-schema-declares-what-the-kernel-reads
  (testing "every key the kernel reads is declared, with a type and a doc"
    (is (= config/defaults agent/default-config) "agent/default-config is the schema's defaults")
    (is (every? #(contains? config/config-items %) [:max-iterations :max-tool-calls :working-dir
                                              :tool-lisp-budget :max-previous-turns
                                              :permission-mode :enable-user-hooks :tool-run?]))
    (is (empty? (remove #(and (:doc %) (:type %)) (vals config/config-items)))))
  (testing "a value is validated against its declared type"
    (is (true? (config/valid? :max-iterations 5)))
    (is (false? (config/valid? :max-iterations "5")))
    (is (true? (config/valid? :tool-deny-effects #{:process})))
    (is (false? (config/valid? :permission-mode "auto")))))

(deftest there-is-one-schema-and-no-second-source
  ;; byj-gly.64. A tree reads `:config/max-turns` from the merged config, so the
  ;; session's keys are config keys by RFC-013's own definition. While they lived
  ;; in a second map they had no BY_* name and no --print-config row, and a
  ;; config.edn that set one was told the key was UNKNOWN while the session ran
  ;; on a value from somewhere else.
  (testing "a session's keys are the schema's, not a second map's"
    (is (every? #(contains? config/config-items %) (keys session/default-config)))
    (is (= session/default-config
           (select-keys config/defaults (keys session/default-config)))
        "session/default-config is the schema's view of them, not a source"))

  (testing "so a tree's :config/… reference resolves against one set of keys"
    (is (contains? config/config-items :max-turns)
        "read by resources/brainyard/loops/session.edn"))

  (testing "and each is settable where every other key is"
    (let [dir (fresh-dir "session-keys")
          _ (write-config! dir {:agent {:config {:max-turns 7 :full-depth 1}}})
          {:keys [config problems sources]}
          (load :working-dir dir :env-fn {"BY_MAX_QUESTION_CHARS" "42"})]
      (is (empty? problems) "no longer reported as unknown keys")
      (is (= 7 (:max-turns config)))
      (is (= :project (:max-turns sources)))
      (is (= 1 (:full-depth config)))
      (is (= 42 (:max-question-chars config)) "and from the environment")
      (is (= :env (:max-question-chars sources)))))

  (testing "a real typo is still reported"
    (let [dir (fresh-dir "still-strict")
          _ (write-config! dir {:agent {:config {:max-turnz 7}}})
          {:keys [problems]} (load :working-dir dir :env-fn (constantly nil))]
      (is (some #(str/includes? % "max-turnz") problems)))))

(deftest strings-are-coerced-by-type
  (is (= 7 (config/coerce "integer" "7")))
  (is (= "seven" (config/coerce "integer" "seven")) "an unparseable value is left alone, as brainyard does")
  (is (= false (config/coerce "boolean" "FALSE")))
  (is (= :deny-by-default (config/coerce "keyword" ":deny-by-default")))
  (is (= ["bash" "write-*"] (config/coerce "vector" "bash, write-*")))
  (is (= #{:process :fs/write} (config/coerce "set" "process,fs/write")))
  (is (= {:steps 10} (config/coerce "object" "{:steps 10}"))))

(deftest layers
  (let [user (write-config! (fresh-dir "user") {:agent {:config {:max-iterations 5 :max-tool-calls 9}}})
        project (write-config! (fresh-dir "project") {:agent {:config {:max-iterations 6}}})]
    (testing "default < user < project < caller < env"
      (let [{:keys [config sources]} (config/load-config {:user-dir user :working-dir project :project-dir project
                                                          :overrides {:max-iterations 7}
                                                          :env-fn {"BY_MAX_ITERATIONS" "8"}})]
        (is (= 8 (:max-iterations config)))
        (is (= :env (:max-iterations sources))))
      (let [{:keys [config sources]} (config/load-config {:user-dir user :working-dir project :project-dir project
                                                          :overrides {:max-iterations 7}})]
        (is (= [7 :caller] [(:max-iterations config) (:max-iterations sources)])))
      (let [{:keys [config sources files]} (config/load-config {:user-dir user :working-dir project :project-dir project})]
        (is (= [6 :project] [(:max-iterations config) (:max-iterations sources)]))
        (is (= [9 :user] [(:max-tool-calls config) (:max-tool-calls sources)])
            "the user's file is the floor, not replaced wholesale")
        (is (= 2 (count files)))
        (is (= [10 :default] [(:max-history-iterations config) (:max-history-iterations sources)])
            "and the schema underneath")))
    (testing "an unset environment variable does not count as a layer"
      (is (= 6 (:max-iterations (:config (config/load-config {:user-dir user :working-dir project :project-dir project
                                                              :env-fn {"BY_MAX_ITERATIONS" "  "}}))))))))

(deftest the-file-is-brainyards-shape
  (testing "[:agent :config], with the permissions section bridged onto flat keys"
    (let [dir (write-config! (fresh-dir "shape")
                             {:agent {:config {:tool-run? false :tool-deny-tools ["bash"]}}
                              :permissions {:mode :deny-by-default}})
          {:keys [config sources problems]} (load :working-dir dir)]
      (is (= false (:tool-run? config)) "a persisted false shadows a true default")
      (is (= ["bash"] (:tool-deny-tools config)))
      (is (= :deny-by-default (:permission-mode config)))
      (is (= :project (:permission-mode sources)))
      (is (empty? problems))))
  (testing "unknown keys, misplaced keys and wrong types are reported and skipped"
    (let [dir (write-config! (fresh-dir "bad-keys")
                             {:max-iterations 3
                              :agent {:config {:max-iterations 4 :nonsense 1 :max-tool-calls "many"}}})
          {:keys [config problems]} (load :working-dir dir)]
      (is (= 4 (:max-iterations config)) "the key under [:agent :config] is the one that counts")
      (is (= 30 (:max-tool-calls config)) "a wrong type falls back to the default")
      (is (= 3 (count problems)))
      (is (some #(str/includes? % ":max-iterations belong under [:agent :config]") problems))
      (is (some #(str/includes? % "unknown keys ignored: :nonsense") problems))
      (is (some #(str/includes? % "wrong value, ignored: :max-tool-calls should be an integer") problems))))
  (testing "a key that names its values says which (byj-gly.204)"
    (is (= ":output-style should be either :default, :concise or :explanatory"
           (config/value-problem :output-style :loud)))
    (is (nil? (config/value-problem :output-style :concise)))
    (is (config/valid? :allowed-dirs nil) "a vector key may be unset")
    (is (not (config/valid? :max-iterations "20"))))
  (testing "an unreadable file is a problem, not an exception"
    (let [dir (fresh-dir "broken")
          f (config/config-file dir)]
      (.mkdirs (.getParentFile f))
      (spit f "{:agent {:config {:max-iterations 4}")
      (let [{:keys [config problems]} (load :working-dir dir)]
        (is (= 20 (:max-iterations config)) "the run carries on with the defaults")
        (is (some #(str/includes? % "unreadable EDN") problems)))))
  (testing "a missing file is silent"
    (is (empty? (:problems (load :working-dir (fresh-dir "empty")))))))

(deftest environment
  (testing "a key's variable is its name: BY_ + SCREAMING_SNAKE, without the ?"
    (let [env {"BY_TOOL_RUN" "false" "BY_PERMISSION_MODE" "deny-by-default"
               "BY_TOOL_DENY_EFFECTS" "process,fs/write" "BY_MAX_ITERATIONS" "3"}
          {:keys [config sources]} (load :env-fn env)]
      (is (= false (:tool-run? config)))
      (is (= :deny-by-default (:permission-mode config)))
      (is (= #{:process :fs/write} (:tool-deny-effects config)))
      (is (= 3 (:max-iterations config)))
      (is (= #{:env} (set (map sources [:tool-run? :permission-mode :tool-deny-effects :max-iterations])))))))

(deftest features-are-a-view-over-the-schema
  (is (= {:gate :enable-user-hooks :family :hooks} (select-keys (get config/features :hooks/enable-user-hooks) [:gate :family])))
  (is (true? (config/feature-on? config/defaults :tools/tool-run)))
  (is (false? (config/feature-on? (assoc config/defaults :tool-run? false) :tools/tool-run)))
  (is (false? (config/feature-on? config/defaults :tools/nonsense)) "an unknown feature is off")
  ;; the invariants, not a literal list: a new key should not need this edited
  (is (every? :family (vals config/config-items)) "every key has a family, which is where /config shows it")
  (is (= (set config/families) (set (map :family (vals config/config-items))))
      "and the families listed are exactly the ones used")
  (is (= (set (for [[k {:keys [type]}] config/config-items :when (= "boolean" type)] k))
         (set (map :gate (vals config/features))))
      "every switch is a feature, and nothing else is"))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(deftest a-project-config-file-reaches-the-loop
  (let [dir (write-config! (fresh-dir "turn") {:agent {:config {:tool-run? false :max-iterations 2}}})
        {:keys [config]} (config/load-config {:working-dir dir :project-dir dir :user-dir (fresh-dir "no-user")})
        seen (atom nil)
        r (agent/ask "q" {:lm {:provider :mock :format :mock
                               :responses (fn [msgs] (reset! seen (:content (first msgs)))
                                            (reply {:tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}]}))}
                          :config config})]
    (is (not (str/includes? @seen "`tool$run`")) "the file switched the command off")
    (is (not (str/includes? @seen "### Composing Tools")) "and the word on when to use it went with it (byj-gly.242)")
    (is (= 2 (:iterations r)) "and set the iteration ceiling")
    (is (= :max-iterations-exhausted (:terminated-by r)))))

(deftest composing-guidance-comes-with-the-command
  ;; when to reach for tool$run is said only where it can be called (byj-gly.242)
  (let [dir (fresh-dir "compose")
        seen (atom nil)]
    (agent/ask "q" {:lm {:provider :mock :format :mock
                         :responses (fn [msgs] (reset! seen (:content (first msgs))) (reply {:answer "a"}))}
                    :config {:working-dir dir :project-dir dir :max-iterations 1}})
    (is (str/includes? @seen "### Composing Tools"))
    (is (str/includes? @seen "ONE `tool$run` call, not a call per step"))))

(deftest saved-tools-are-named-with-their-arguments
  ;; a user tool's arguments on its line, and the project's user tools named in
  ;; Composing Tools, each by its own key (byj-gly.251)
  (let [dir (fresh-dir "saved")
        tool {:name "user$tool$sum-field" :kind :composite
              :description "Sum a field over the JSON files in a directory"
              :input-schema [:map [:dir :string] [:field :string] [:strict {:optional true} :boolean]]}
        prompt (fn [extra]
                 (let [seen (atom nil)]
                   (agent/ask "q" {:lm {:provider :mock :format :mock
                                        :responses (fn [msgs] (reset! seen (:content (first msgs))) (reply {:answer "a"}))}
                                   :tools (conj tools/builtin-tools
                                                (assoc tool :fn (fn [_ _] 0))
                                                {:name "tool$run" :kind :command :description "run a body" :fn (fn [_ _] nil)})
                                   :config (merge {:working-dir dir :project-dir dir :max-iterations 1} extra)})
                   @seen))
        line "- `user$tool$sum-field` {:dir string, :field string, :strict? boolean} — Sum a field"]
    (is (= "{:dir string, :field string, :strict? boolean}" (tools/args-line tool)))
    (testing "off: the tool is listed by name and description only"
      (let [p (prompt {})]
        (is (str/includes? p "- `user$tool$sum-field` — Sum a field"))
        (is (not (str/includes? p "Saved tools")))))
    (testing ":compact-saved-tool-args? puts the arguments on its line"
      (is (str/includes? (prompt {:compact-saved-tool-args? true}) line)))
    (testing ":composition-saved-tools? names it in Composing Tools, after the word on tool$run"
      (let [p (prompt {:composition-saved-tools? true})
            at (str/index-of p "Saved tools")]
        (is (and at (< (str/index-of p "### Composing Tools") at)))
        (is (str/includes? (subs p at) line))))))

(deftest the-project-root-is-the-git-root
  (let [root (fresh-dir "repo")
        _ (.mkdirs (io/file root ".git"))
        sub (doto (io/file root "src" "deep") .mkdirs)]
    (testing "the walk finds the nearest .git at or above the working dir"
      (is (= (.getCanonicalPath (io/file root)) (.getCanonicalPath (io/file (config/find-git-root (.getPath sub))))))
      (is (= (.getCanonicalPath (io/file root)) (.getCanonicalPath (io/file (config/find-git-root root))))))
    (testing "a worktree's .git FILE counts too, where brainyard wants a directory"
      (let [wt (fresh-dir "worktree")]
        (spit (io/file wt ".git") "gitdir: /elsewhere/.git/worktrees/wt")
        (is (= (.getCanonicalPath (io/file wt)) (.getCanonicalPath (io/file (config/find-git-root wt)))))))
    (testing "with no .git anywhere above, the working dir is the project"
      (is (= (.getAbsolutePath (io/file "/")) (config/resolve-project-dir "/" nil))))
    (testing "the project config file, .brainyard roots and allowed-dirs follow the project, not the subdirectory"
      (write-config! root {:agent {:config {:max-iterations 3}}})
      (let [{:keys [config sources]} (config/load-config {:working-dir (.getPath sub)
                                                          :user-dir (fresh-dir "no-user")})]
        (is (= [3 :project] [(:max-iterations config) (:max-iterations sources)]))
        (is (= (.getCanonicalPath (io/file root)) (.getCanonicalPath (io/file (:project-dir config)))))
        (is (some #(= (.getCanonicalPath (io/file root)) (.getCanonicalPath (io/file %))) (:allowed-dirs config))
            "so a subdirectory run may still read the whole project"))
      (let [{:keys [config]} (agent/make-ctx {:config {:working-dir (.getPath sub)}})]
        (is (str/ends-with? (first (:predictor-roots config)) (str root "/.brainyard/predictors")))))
    (testing "an explicit project dir wins over the walk"
      (is (= "/elsewhere" (:project-dir (:config (config/load-config {:working-dir (.getPath sub)
                                                                      :user-dir (fresh-dir "no-user")
                                                                      :overrides {:project-dir "/elsewhere"}}))))))))

(deftest a-shares-allowed-dirs-come-from-the-environment
  ;; byj-1wv.115.40: an A2A gateway gives a caller's turn its project and the temp
  ;; dir alone, as BY_ALLOWED_DIRS, over the project's own config
  (let [{:keys [config]} (config/load-config {:working-dir "." :env-fn {"BY_ALLOWED_DIRS" "/p/agent,/tmp"}})]
    (is (= ["/p/agent" "/tmp"] (:allowed-dirs config))))
  (is (some #{"~/.brainyard/share/credentials"} (:denied-paths (:config (config/load-config {:working-dir "."}))))
      "byj-share's login is denied by default"))

(deftest a-key-another-program-declares-is-not-called-unknown
  ;; byj-ahf.21: Share keeps its own keys in the same [:agent :config], and
  ;; byj-share setup declares them, so byj neither warns of them nor reads them
  (let [home (fresh-dir "declared-home")
        dir (write-config! (fresh-dir "declared-project") {:agent {:config {:max-iterations 4 :eval-root "/x" :nonsense 1}}})
        decl (io/file home ".brainyard" "config-keys" "byj-share.edn")]
    (testing "undeclared, both are unknown"
      (let [{:keys [problems]} (load :working-dir dir :user-dir home)]
        (is (some #(str/includes? % "unknown keys ignored: :eval-root, :nonsense") problems))))
    (io/make-parents decl)
    (spit decl (pr-str {:program "byj-share" :keys [:eval-root :exam-kits-dir]}))
    (spit (io/file home ".brainyard" "config-keys" "broken.edn") "{:keys [")
    (testing "declared, only the other is"
      (let [{:keys [config problems]} (load :working-dir dir :user-dir home)]
        (is (some #(str/includes? % "unknown keys ignored: :nonsense") problems))
        (is (not-any? #(str/includes? % ":eval-root") problems))
        (is (not (contains? config :eval-root)) "byj does not read it")
        (is (= 4 (:max-iterations config)))))
    (is (= #{:eval-root :exam-kits-dir} (config/declared-keys home)) "an unreadable declaration declares nothing")))
