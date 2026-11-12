;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.configbox-test
  "Writing config (RFC-013). Mostly a test of what is REFUSED: a config file
  outlives the session that wrote it, so everything a turn can persist it can
  persist permanently."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
            [brainyard.configbox :as cb]
            [brainyard.tools :as tools]))

(defn- fresh-dir [n]
  (let [d (io/file "target" n)]
    (when (.isDirectory d) (run! io/delete-file (reverse (file-seq d))))
    (.mkdirs d)
    d))

(defn- project+home
  "A config whose project AND user dirs are both temporary. Every test sets
  `:user-dir`: a test that leaves it unset writes to the real ~/.brainyard."
  [n]
  (let [p (fresh-dir (str n "-project"))
        h (fresh-dir (str n "-home"))]
    {:working-dir (.getPath p) :project-dir (.getPath p) :user-dir (.getPath h)}))

(defn- read-edn [f] (when (.isFile f) (read-string (slurp f))))

;; --- where a write lands ---------------------------------------------------------------

(deftest auto-is-the-project-never-the-users-own-config
  ;; THE regression. brainyard resolves :auto one way for reads — the project
  ;; file when it EXISTS, else the user's — and another for writes. Following
  ;; the read rule here meant the first write to a project with no config.edn
  ;; yet silently landed in ~/.brainyard/config.edn, changing a setting for
  ;; every other project on the machine, from inside one turn, having been
  ;; asked for :auto. It was caught by it happening.
  (let [c (project+home "configbox-auto")
        home (config/config-file (:user-dir c))
        proj (config/config-file (:project-dir c))]
    (is (not (.isFile proj)) "no project file yet — the case that went wrong")
    (let [r (cb/set-key! c :auto :max-iterations 7 {:reason "t"})]
      (is (true? (:ok? r)))
      (is (= :project (:scope r)))
      (is (.isFile proj))
      (is (not (.isFile home)) "the user's own config was not touched"))

    (testing "the user's file is written only when it is asked for by name"
      (is (true? (:ok? (cb/set-key! c :user :max-iterations 5 {:reason "t"}))))
      (is (.isFile home))
      (is (= 7 (get-in (read-edn proj) [:agent :config :max-iterations]))
          "and the project's is left alone"))))

(deftest a-write-says-what-it-changed
  (let [c (project+home "configbox-write")
        r1 (cb/set-key! c :auto :max-iterations 7 {:reason "slower loop"})
        r2 (cb/set-key! c :auto :max-iterations 9 {:reason "faster"})]
    (is (= [nil 7] [(:was r1) (:was r2)]))
    (is (= [7 9] [(:now r1) (:now r2)]))
    (is (= 9 (get-in (read-edn (io/file (:path r2))) [:agent :config :max-iterations])))))

(deftest the-rest-of-the-file-survives-a-write
  ;; a write is one key, not a rewrite: a section this layer knows nothing
  ;; about must come through untouched
  (let [c (project+home "configbox-preserve")
        f (config/config-file (:project-dir c))]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str {:mcp {:servers {:linear {:command "npx"}}}
                     :agent {:config {:max-iterations 3}}}))
    (cb/set-key! c :auto :max-tool-calls 5 {:reason "t"})
    (let [m (read-edn f)]
      (is (= {:linear {:command "npx"}} (get-in m [:mcp :servers])) "a section it does not own")
      (is (= 3 (get-in m [:agent :config :max-iterations])) "and a key it did not write"))))

;; --- what is refused -------------------------------------------------------------------

(deftest a-permission-key-is-never-written-whoever-asks
  ;; brainyard's allowlist INCLUDES [:permissions :mode] and
  ;; [:permissions :allowed-dirs], guarded only by a rule that they do not
  ;; self-confirm under --auto. Here there is no confirmed route at all: the one
  ;; thing a gate must not be is something the gated party can edit.
  (let [c (project+home "configbox-perms")]
    (doseq [k [:permission-mode :allowed-dirs :tool-deny-effects :tool-deny-tools
               :bash-allow-commands :path-permission?]]
      (testing (str k)
        (is (some? (cb/unwritable k)))
        (is (str/includes? (cb/unwritable k) "permission"))
        (let [r (cb/set-key! c :auto k :auto-approve {:reason "t"})]
          (is (false? (:ok? r)))
          (is (= :writable (:stage r))))))

    (testing "and the file is not created by the attempt"
      (is (not (.isFile (config/config-file (:project-dir c))))))

    (testing "every key in the permissions family is covered, not a hand-written list"
      (is (every? #(some? (cb/unwritable %))
                  (filter #(= :permissions (get-in config/config-items [% :family]))
                          config/config-keys))))))

(deftest the-hooks-that-install-the-gates-are-not-writable-either
  ;; turning :builtin-hooks? off removes the permission gates, so it is a
  ;; permission key in everything but its family
  (is (some? (cb/unwritable :builtin-hooks?)))
  (is (false? (:ok? (cb/set-key! (project+home "configbox-hooks") :auto :builtin-hooks? false {:reason "t"})))))

(deftest a-key-that-describes-this-run-is-not-a-setting
  (doseq [k [:working-dir :project-dir :session-id :resume]]
    (testing (str k)
      (is (some? (cb/unwritable k)))
      (is (str/includes? (cb/unwritable k) "single run")))))

(deftest an-unknown-key-is-refused-rather-than-written
  (let [r (cb/set-key! (project+home "configbox-unknown") :auto :not-a-key "x" {:reason "t"})]
    (is (= :writable (:stage r)))
    (is (str/includes? (:error r) "not a config key"))))

(deftest a-value-of-the-wrong-type-is-refused
  (let [r (cb/set-key! (project+home "configbox-type") :auto :max-iterations "lots" {:reason "t"})]
    (is (= :schema (:stage r)))
    (is (str/includes? (:error r) "integer"))))

(deftest a-secret-shaped-value-is-refused-and-not-echoed
  (let [c (project+home "configbox-secret")
        r (cb/set-key! c :auto :prompt-cache-key "sk-abcdefghij0123456789XYZ" {:reason "t"})]
    (is (= :secret (:stage r)))
    (testing "the match is masked — reporting it in full would put the secret in
              the transcript and the event log, which is what is being prevented"
      (is (= ["sk-abcde…"] (:matches r)))
      (is (not (str/includes? (pr-str r) "0123456789XYZ"))))
    (testing "and nothing was written"
      (is (not (.isFile (config/config-file (:project-dir c))))))

    (testing "the other shapes brainyard refuses"
      (is (seq (cb/secrets-in "AKIAIOSFODNN7EXAMPLE")))
      (is (seq (cb/secrets-in "ghp_abcdefghijklmnopqrstuvwxyz0123")))
      (is (seq (cb/secrets-in "-----BEGIN RSA PRIVATE KEY-----")))
      (is (empty? (cb/secrets-in "a perfectly ordinary value"))))))

(deftest a-file-that-changed-underneath-is-not-overwritten
  (let [c (project+home "configbox-mtime")
        _ (cb/set-key! c :auto :max-iterations 7 {:reason "t"})
        f (config/config-file (:project-dir c))
        stale (- (.lastModified f) 5000)
        r (cb/set-key! c :auto :max-iterations 9 {:reason "t" :expected-mtime stale})]
    (is (= :mtime (:stage r)))
    (is (= 7 (get-in (read-edn f) [:agent :config :max-iterations])) "the other writer's value stands")))

;; --- putting it back --------------------------------------------------------------------

(deftest a-write-can-be-undone
  (let [c (project+home "configbox-revert")
        f (config/config-file (:project-dir c))]
    (cb/set-key! c :auto :max-iterations 7 {:reason "first"})
    (is (empty? (cb/snapshots c :auto)) "nothing to snapshot before the first write")

    (cb/set-key! c :auto :max-iterations 9 {:reason "second"})
    (is (= 1 (count (cb/snapshots c :auto))) "the second write saved the first")

    (let [r (cb/revert! c :auto {:steps 1})]
      (is (true? (:ok? r)))
      (is (= 7 (get-in (read-edn f) [:agent :config :max-iterations]))))

    (testing "a snapshot names why it was taken, so a list is readable"
      (is (some #(str/includes? (:name %) "second") (cb/snapshots c :auto))))

    (testing "reverting with nothing to revert to says so rather than throwing"
      (is (str/includes? (:error (cb/revert! (project+home "configbox-empty") :auto {:steps 1}))
                         "no snapshots")))

    (testing "and a step past the end is refused"
      (is (str/includes? (:error (cb/revert! c :auto {:steps 99})) "steps must be")))))

(deftest unsetting-gives-the-default-back
  (let [c (project+home "configbox-unset")
        f (config/config-file (:project-dir c))]
    (cb/set-key! c :auto :max-iterations 7 {:reason "t"})
    (let [r (cb/set-key! c :auto :max-iterations nil {:reason "t" :unset? true})]
      (is (true? (:ok? r)))
      (is (= 7 (:was r)))
      (is (true? (:unset? r))))
    (is (not (contains? (get-in (read-edn f) [:agent :config]) :max-iterations)))
    (testing "so the schema default is what a later run reads"
      ;; :project-dir explicitly. Without it `load-config` resolves one by
      ;; walking to the git root — which is this repository — and the test then
      ;; reads the DEVELOPER'S config.edn. It passes only while they have none:
      ;; a planted :max-iterations 25 failed it (the read counterpart of
      ;; `byj-gly.88`, caught by dev/flake-hunt.sh).
      (is (= (:max-iterations config/defaults)
             (:max-iterations (:config (config/load-config {:working-dir (:project-dir c)
                                                            :project-dir (:project-dir c)
                                                            :user-dir (:user-dir c)}))))))))

(deftest a-write-leaves-no-half-written-file
  ;; brainyard spits the pprinted map straight over the file; a crash mid-write
  ;; leaves a config.edn every later run reads as unparseable
  (let [c (project+home "configbox-atomic")
        f (config/config-file (:project-dir c))]
    (cb/set-key! c :auto :max-iterations 7 {:reason "t"})
    (is (empty? (remove #(#{"config.edn" "config-snapshots"} (.getName %))
                        (.listFiles (.getParentFile f))))
        "no temp file left beside it")
    (is (map? (read-edn f)))))

;; --- through the registry -----------------------------------------------------------------

(deftest the-commands-say-where-a-setting-came-from
  (let [c (assoc (project+home "configbox-read")
                 :max-iterations 12                       ; as a -n flag would
                 :config-sources {:max-iterations :caller})
        ctx (agent/make-ctx {:config c :tools (vec tools/builtin-tools)})
        r (tools/call-tool (get @(:tools ctx) "config$read") {:key "max-iterations"} ctx)
        row (first (:keys r))]
    (is (= :max-iterations (:key row)))
    (is (= :caller (:source row)) "which layer won")
    (is (true? (:writable? row)))

    (testing "and what unsetting it would give back, which is not the same number"
      ;; byj-gly.86, found by running this agent on its own repository: launched
      ;; with -n 12, it set :max-iterations 30, then told the user "the default
      ;; (12) applies again". The default is 20. 12 was the flag — the only
      ;; number the command gave it, and it read that as the default.
      (is (= 12 (:value row)) "what is in force")
      (is (= 20 (:default row)) "what unsetting would give back")
      (is (true? (:changed? row)) "so the keys this project actually differs on are visible"))

    (testing "and says plainly what cannot be changed, with the reason"
      (let [p (first (:keys (tools/call-tool (get @(:tools ctx) "config$read")
                                             {:key "permission-mode"} ctx)))]
        (is (false? (:writable? p)))
        (is (str/includes? (:why-not p) "permission"))))

    (testing "a key at its default is not marked changed"
      (let [r (first (:keys (tools/call-tool (get @(:tools ctx) "config$read")
                                             {:key "max-tool-calls"} ctx)))]
        (is (= (:value r) (:default r)))
        (is (not (contains? r :changed?)))))))

(deftest reload-says-what-would-not-take-effect
  ;; the useful half of brainyard's config$reload: a key edited in the file and
  ;; outranked by a flag is "I edited config.edn and nothing happened"
  (let [c0 (project+home "configbox-reload")
        load! #(config/load-config {:working-dir (:project-dir c0) :project-dir (:project-dir c0)
                                    :user-dir (:user-dir c0)})
        loaded (load!)
        ;; the running config as a turn actually has it: every default resolved,
        ;; plus a flag that outranks the file for one key
        c (assoc (:config loaded)
                 :user-dir (:user-dir c0)
                 :max-iterations 20
                 :config-sources (assoc (:sources loaded) :max-iterations :caller))]
    (cb/set-key! c :auto :max-iterations 9 {:reason "t"})
    (let [fresh (load!)
          rep (cb/reload-report {:config c :sources (:config-sources c)} fresh)]
      (is (empty? (:changed rep)))
      (is (= [:max-iterations] (mapv :key (:shadowed rep))))
      (is (= :caller (:outranked-by (first (:shadowed rep))))))))

(deftest writing-can-be-switched-off-without-losing-reading
  (let [c (assoc (project+home "configbox-off") :config-write? false)
        ctx (agent/make-ctx {:config c :tools (vec tools/builtin-tools)})]
    (is (nil? (get @(:tools ctx) "config$set")))
    (is (nil? (get @(:tools ctx) "config$unset")))
    (is (nil? (get @(:tools ctx) "config$revert")))
    (testing "reading a setting is not a privilege"
      (is (some? (get @(:tools ctx) "config$read")))
      (is (some? (get @(:tools ctx) "config$reload"))))))

;; --- the one route a permission key is ever written (byj-gly.83) ----------------------

(deftest a-person-at-the-prompt-may-grant-what-the-agent-may-not
  (let [c (project+home "configbox-grant")
        f (config/config-file (:project-dir c))]
    (testing "the agent's route still refuses, as it always did"
      (is (= :writable (:stage (cb/set-key! c :auto :tool-allow-tools ["bash"] {:reason "t"})))))

    (testing "a grant appends, so an earlier one is not silently dropped"
      (is (true? (:ok? (cb/grant! c :auto :tool-allow-tools "bash" "asked"))))
      (is (true? (:ok? (cb/grant! c :auto :tool-allow-tools "write-file" "asked"))))
      (is (= ["bash" "write-file"] (get-in (read-edn f) [:agent :config :tool-allow-tools]))))

    (testing "granting the same thing twice says so rather than writing again"
      (let [r (cb/grant! c :auto :tool-allow-tools "bash" "asked")]
        (is (true? (:ok? r)))
        (is (true? (:already? r)))))

    (testing "the mode is set, not appended — it is one value"
      (is (true? (:ok? (cb/grant! c :auto :permission-mode :auto-approve "asked"))))
      (is (= :auto-approve (get-in (read-edn f) [:agent :config :permission-mode]))))

    (testing "and only the three keys the decision named"
      (is (= #{:tool-allow-tools :allowed-dirs :permission-mode} cb/grantable))
      (doseq [k [:tool-deny-tools :tool-deny-effects :tool-ask-effects
                 :path-permission? :builtin-hooks? :bash-deny-commands]]
        (testing (str k)
          (is (some? (cb/unwritable k :person)) "refused even for a person")
          (is (= :writable (:stage (cb/set-key! c :auto k [] {:reason "t" :by :person})))))))))

(deftest no-command-can-ask-as-a-person
  ;; What makes :by :person mean anything. A grant is allowed because a human
  ;; pressed a key in a terminal; a command's :fn runs because the model asked
  ;; for it. If a command ever passed :by :person the distinction would be gone,
  ;; and it would be gone silently.
  (let [src (slurp "src/brainyard/configbox.clj")
        commands-src (subs src (str/index-of src "(defn commands"))]
    (is (not (str/includes? commands-src ":person"))
        "no command body may mention :person")
    (is (not (str/includes? commands-src "grant!"))
        "nor call grant! — the prompt is its only caller"))

  (testing "and the only caller in src/ is the ask prompt"
    (let [callers (for [f (file-seq (io/file "src"))
                        :when (str/ends-with? (.getName f) ".clj")
                        :when (str/includes? (slurp f) "configbox/grant!")]
                    (.getName f))]
      (is (= ["permission.clj"] (vec callers))))))

(deftest a-write-is-an-effect-the-gate-can-reach
  (let [ctx (agent/make-ctx {:config (project+home "configbox-effects")
                             :tools (vec tools/builtin-tools)})]
    (is (= #{:fs/write :config/write} (:effects (get @(:tools ctx) "config$set")))
        "so --ask-effects config/write puts a person in front of it")
    (is (= #{:fs/read} (:effects (get @(:tools ctx) "config$read"))))))

(deftest a-config-value-is-shown-masked
  ;; the 'config changed' notice printed :mcp-servers in full, a server's
  ;; :env token with it, into the transcript and the snapshot; config$read
  ;; and config$reload handed the same to the model (byj-gly.235)
  (let [servers {"gh" {:command "npx" :env {"TOKEN" "plain-looking-value"}}}]
    (is (= {"gh" {:command "npx" :env {"TOKEN" "••••"}}} (cb/shown :mcp-servers servers))
        "an MCP server's env is masked whatever it looks like")
    (is (= servers {"gh" {:command "npx" :env {"TOKEN" "plain-looking-value"}}}) "and the value itself is untouched")
    (is (not (str/includes? (pr-str (cb/shown :prompt-cache-key "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789")) "abcdefghij"))
        "any secret-shaped string is redacted")
    (is (= "mcp-servers +new −gone ~gh"
           (cb/change-line :mcp-servers {"gh" {:command "a"} "gone" {:command "b"}}
                                  {"gh" {:command "c" :env {"TOKEN" "x"}} "new" {:command "d"}}))
        "a per-server key's change names servers, never their specs")
    (is (= "max-iterations 20 → 7" (cb/change-line :max-iterations 20 7)))))
