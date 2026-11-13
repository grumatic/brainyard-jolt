;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.permission-test
  "The permission gate (RFC-012): glob matching, the two gates and their order,
  the modes, the ask channel, and what a refused call looks like in a turn."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.hooks :as hooks]
            [brainyard.json :as json]
            [brainyard.config :as config]
            [brainyard.permission :as permission]
            [brainyard.project-loops :as project-loops]
            [brainyard.sandbox :as sandbox]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file "target/permission-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- ctx
  "A real agent ctx — the built-in gates are loaded from their specs — with
  `config` merged in."
  [config & {:keys [permission-fn]}]
  (agent/make-ctx {:config (merge {:working-dir "."} config) :permission-fn permission-fn}))

(defn- call [ctx tool-name args] (tools/call-tool (get @(:tools ctx) tool-name) args ctx))

(deftest glob-matching
  (is (true? (permission/glob-match? "bash" "bash")))
  (is (false? (permission/glob-match? "bash" "bash-x")) "whole name, not a prefix")
  (is (true? (permission/glob-match? "tool$*" "tool$create")))
  (is (true? (permission/glob-match? "*" "anything")))
  (is (false? (permission/glob-match? "Bash" "bash")) "case-sensitive")
  (is (false? (permission/glob-match? "read.file" "read-file")) "`.` is literal")
  (is (= "write-*" (permission/first-match ["bash" "write-*"] "write-file"))
      "the pattern is returned — an approval is remembered under it"))

(deftest modes
  (is (= :auto-approve (permission/resolve-mode :auto-approve)))
  (is (= :ask-each-time (permission/resolve-mode :nonsense)))
  (is (= (if (permission/container?) :auto-approve :ask-each-time) (permission/resolve-mode :auto))))

(deftest the-gates-ship-inert
  (let [c (ctx {})
        gates (sort-by (comp - :priority) (hooks/entries (:hooks c) :agent.tool-use/pre))]
    (is (= [["redundant-tool-call-guard" 100] ["tool-deny-gate" 95] ["bash-command-gate" 92]
            ["path-permission-gate" 90] ["tangled-file-guard" 87] ["tool-permission-gate" 85] ["task-poll-park" 50]]
           (map (juxt :id :priority) gates))
        "brainyard's order: the loop guard, then deny, commands, paths, the approval
         gate — the literate guard (RFC-067) above the approval gate, so nobody is
         asked about a write it refuses — and below all of them auto-park, which is
         an optimisation and must never get to decide before a refusal does")
    (is (= [:throw :throw :throw :throw :throw]
           (map :on-error (remove #(#{"redundant-tool-call-guard" "task-poll-park"} (:id %)) gates)))
        "a gate that throws must not quietly let the call through")
    (is (nil? (:on-error (first (filter #(= "task-poll-park" (:id %)) gates))))
        "auto-park is the other way round: it saves iterations, it does not protect
         anything, so a throw there must let the poll through rather than cost the turn")
    (is (= "." (:path (call c "list-dir" {:path "."}))) "with nothing configured the gates abstain")))

(deftest deny-gate
  (testing "a denied tool is refused with brainyard's message, whatever the mode or allowlist"
    (doseq [mode [:auto-approve :ask-each-time :deny-by-default]]
      (let [c (ctx {:tool-deny-tools ["list-*"] :tool-allow-tools ["list-dir"] :permission-mode mode})]
        (is (= {:error (str "Tool call refused (denied): list-dir. denied by :tool-deny-tools pattern \"list-*\". "
                            permission/deny-hint)}
               (call c "list-dir" {:path "."}))))))
  (testing "an effect can deny too"
    (let [c (ctx {:tool-deny-effects #{:process} :permission-mode :auto-approve})]
      (is (str/includes? (:error (call c "bash" {:command "echo hi"})) "denied by :tool-deny-effects :process"))
      (is (= "." (:path (call c "list-dir" {:path "."}))) "a tool without the effect is untouched")))
  (testing "a user tool inherits the effects of what it calls"
    (let [dir (fresh-dir "composite")
          c (ctx {:working-dir dir :project-dir dir :tool-deny-effects #{:process} :permission-mode :auto-approve})]
      (call c "tool$create" {:name "ls-home" :body "(fn [_] (bash {:command \"echo hi\"}))"})
      (is (str/includes? (:error (call c "user$tool$ls-home" {})) "denied by :tool-deny-effects :process")))))

(deftest a-chosen-mode-outranks-the-configured-one
  (testing "shift+tab steps auto → auto-approve → ask-each-time → auto, and in from outside the cycle"
    (is (= [:auto-approve :ask-each-time :auto :auto]
           (mapv permission/next-mode [:auto :auto-approve :ask-each-time :deny-by-default]))))
  (let [box (atom :ask-each-time)
        asked (atom 0)
        c (agent/make-ctx {:config {:working-dir "." :tool-approval-patterns ["bash"] :permission-mode :auto-approve}
                           :permission-fn (fn [_] (swap! asked inc) {:allowed true})
                           :permission-mode-box box})]
    (testing "the gates read the box, not the config"
      (call c "bash" {:command "echo hi"})
      (is (= 1 @asked)))
    (testing "and a change to it reaches the next call"
      (reset! box :auto-approve)
      (call c "bash" {:command "echo hi"})
      (is (= 1 @asked)))))

(deftest permission-gate
  (testing "only a pattern match asks; an allow glob exempts it"
    (let [asked (atom [])
          c (ctx {:tool-approval-patterns ["bash" "write-*"] :tool-allow-tools ["write-file"]
                  :permission-mode :ask-each-time}
                 :permission-fn (fn [req] (swap! asked conj req) {:allowed true}))]
      (is (= "." (:path (call c "list-dir" {:path "."}))) "unmatched tools are not asked about")
      (is (= {:path "target/permission-test/allow/w.txt" :chars 1}
             (call c "write-file" {:path (str (fresh-dir "allow") "/w.txt") :content "x"}))
          "an allow glob wins over the ask pattern")
      (is (= "hi\n" (:output (call c "bash" {:command "echo hi"}))))
      (is (= [{:type :tool-use :action :call :tool "bash" :pattern "bash" :display "bash"
               :args {:command "echo hi"} :effects #{:process}}]
             @asked))))
  (testing "the modes"
    (let [refusal (str "Tool call refused (permission): bash. permission mode is :deny-by-default. " permission/approval-hint)]
      (is (= "hi\n" (:output (call (ctx {:tool-approval-patterns ["bash"] :permission-mode :auto-approve})
                                   "bash" {:command "echo hi"})))
          ":auto-approve abstains without asking")
      (is (= {:error refusal} (call (ctx {:tool-approval-patterns ["bash"] :permission-mode :deny-by-default})
                                    "bash" {:command "echo hi"})))))
  (testing "headless is fail-closed; a denial and a throwing prompt both refuse"
    (is (str/includes? (:error (call (ctx {:tool-approval-patterns ["bash"] :permission-mode :ask-each-time})
                                     "bash" {:command "echo hi"}))
                       "no interactive permission channel (headless)"))
    (is (str/includes? (:error (call (ctx {:tool-approval-patterns ["bash"] :permission-mode :ask-each-time}
                                          :permission-fn (fn [_] {:denied true :reason "not now"}))
                                     "bash" {:command "echo hi"}))
                       "bash. not now."))
    (is (str/includes? (:error (call (ctx {:tool-approval-patterns ["bash"] :permission-mode :ask-each-time}
                                          :permission-fn (fn [_] (throw (ex-info "boom" {}))))
                                     "bash" {:command "echo hi"}))
                       "permission prompt error: boom")))
  (testing "an effect can ask"
    (let [asked (atom 0)
          c (ctx {:tool-ask-effects #{:fs/write} :permission-mode :ask-each-time}
                 :permission-fn (fn [_] (swap! asked inc) {:allowed true}))]
      (is (= "." (:path (call c "list-dir" {:path "."}))))
      (call c "write-file" {:path (str (fresh-dir "fx") "/a.txt") :content "x"})
      (is (= 1 @asked))))
  (testing "deny (95) decides before permission (85)"
    (let [c (ctx {:tool-deny-tools ["bash"] :tool-approval-patterns ["bash"] :permission-mode :auto-approve})]
      (is (str/includes? (:error (call c "bash" {:command "echo hi"})) "Tool call refused (denied)")))))

(deftest a-prompt-is-said-to-watchers-and-nothing-answers-it-but-the-person
  ;; RFC-043: the gate's ask and its answer are events a :subscribe client
  ;; sees, read only
  (let [events (atom [])
        c (fn [pfn] (assoc (ctx {:tool-approval-patterns ["bash"] :permission-mode :ask-each-time} :permission-fn pfn)
                           :on-event (fn [e d] (when (= "permission" (namespace e)) (swap! events conj [e d])))))]
    (call (c (fn [_] {:allowed true})) "bash" {:command "echo hi"})
    (call (c (fn [_] {:denied true})) "bash" {:command "echo no"})
    (let [[[a1 d1] [a2 d2] [a3 d3] [a4 d4]] @events]
      (is (= [:permission/asked :permission/decided :permission/asked :permission/decided] [a1 a2 a3 a4]))
      (is (= {:tool "bash" :summary "bash"} (dissoc d1 :id)))
      (is (= {:id (:id d1) :tool "bash" :decision :allow} d2))
      (is (= :deny (:decision d4)))
      (is (not= (:id d1) (:id d3)) "each ask has its own id"))
    (testing "a headless gate asks no one, and says nothing was asked"
      (reset! events [])
      (call (c nil) "bash" {:command "echo hi"})
      (is (empty? @events)))))

(deftest auto-mode-asks-the-classifier-where-it-would-ask-the-person
  ;; byj-gly.285.1: in :auto outside a container, a gate that would ask runs the
  ;; classifier first — allow runs the call, block refuses it with the reason,
  ;; unsure (or a failure) asks the person as before
  (let [dir (fresh-dir "classify")
        outside (.getAbsolutePath (io/file (fresh-dir "classify-outside")))
        _ (spit (io/file outside "notes.txt") "n")
        seen (atom [])
        asked (atom 0)
        stub (fn [verdict] (fn [request _ctx] (swap! seen conj request)
                             (if (instance? Throwable verdict) (throw verdict) {:verdict verdict :reason "it reads a note"})))
        c (fn [verdict & {:as cfg}]
            (assoc (agent/make-ctx {:config (merge {:working-dir dir :project-dir dir :allowed-dirs [dir]} cfg)
                                    :permission-fn (fn [_] (swap! asked inc) {:allowed true})})
                   :auto-classify (stub verdict)))
        read-out #(call % "read-file" {:path (str outside "/notes.txt")})]
    (testing "allow: the call runs, and nobody is asked"
      (reset! asked 0)
      (is (= "n" (:content (read-out (c :allow)))))
      (is (zero? @asked))
      (is (= :file-access (:type (last @seen)))))
    (testing "block: refused with the classifier's reason and how a person lets one through"
      (let [{:keys [error]} (read-out (c :block))]
        (is (str/includes? error "blocked by auto mode: it reads a note"))
        (is (str/includes? error "shift+tab to ask-each-time"))))
    (testing "unsure, or a classifier that fails: the person is asked, as before"
      (reset! asked 0)
      (is (= "n" (:content (read-out (c :unsure)))))
      (is (= "n" (:content (read-out (c (ex-info "timed out" {}))))))
      (is (= 2 @asked)))
    (testing ":auto-mode-on-unsure :deny refuses instead"
      (is (str/includes? (:error (read-out (c :unsure :auto-mode-on-unsure :deny))) "the auto-mode classifier was unsure")))
    (testing "not run outside :auto, or switched off"
      (reset! seen [])
      (read-out (c :block :permission-mode :ask-each-time))
      (read-out (c :block :permission-mode :auto-approve))
      (read-out (c :block :auto-mode-classifier? false))
      (is (empty? @seen)))
    (testing "the approval gate and the command gate ask it too"
      (reset! seen [])
      (is (str/includes? (str (:error (call (c :block :tool-ask-effects #{:process}) "bash" {:command "true"})))
                         "blocked by auto mode"))
      (is (str/includes? (str (call (c :block) "bash" {:command (str "cat " outside "/notes.txt")})) "blocked by auto mode"))
      (is (= [:tool-use :file-access] (mapv :type @seen))))))

(deftest a-destructive-command-is-asked-about-inside-the-project
  ;; byj-gly.285.4: rm -rf ./src names nothing outside the allowed directories,
  ;; and was run unasked — now it is asked about, so in :auto classified
  (let [dir (fresh-dir "ask-commands")
        seen (atom [])
        asked (atom [])
        c (fn [verdict & {:as cfg}]
            (assoc (agent/make-ctx {:config (merge {:working-dir dir :project-dir dir :allowed-dirs [dir]} cfg)
                                    :permission-fn (fn [req] (swap! asked conj req) {:allowed true})})
                   :auto-classify (fn [req _] (swap! seen conj req) {:verdict verdict :reason "deletes the project's sources"})))
        mk #(do (.mkdirs (io/file dir "src")) (spit (io/file dir "src/a.clj") "a"))]
    (testing "in :auto the classifier decides: block refuses it, and nothing is deleted"
      (mk)
      (let [r (call (c :block) "bash" {:command "rm -rf ./src"})]
        (is (str/includes? (str r) "blocked by auto mode: deletes the project's sources"))
        (is (.exists (io/file dir "src/a.clj"))))
      (is (= {:type :command :display "rm -rf ./src" :pattern "rm *"} (select-keys (last @seen) [:type :display :pattern]))))
    (testing "allow runs it"
      (mk)
      (call (c :allow) "bash" {:command "rm -rf ./src"})
      (is (not (.exists (io/file dir "src/a.clj")))))
    (testing "a command no glob names is not asked about"
      (reset! seen [])
      (call (c :block) "bash" {:command "ls && cat README || true"})
      (is (empty? @seen)))
    (testing "one segment of a line is enough, past VAR=value"
      (reset! seen [])
      (call (c :block) "bash" {:command "cd src && FORCE=1 git push --force origin main"})
      (is (= ["git push --force origin main" "git push*"] ((juxt :display :pattern) (last @seen)))))
    (testing "the other modes: asked, run, refused; [] asks about none"
      (reset! asked [])
      (call (c :block :permission-mode :ask-each-time) "bash" {:command "chmod -R 777 ."})
      (is (= :command (:type (last @asked))))
      (mk)
      (call (c :block :permission-mode :auto-approve) "bash" {:command "rm -rf ./src"})
      (is (not (.exists (io/file dir "src/a.clj"))))
      (is (str/includes? (str (call (c :block :permission-mode :deny-by-default) "bash" {:command "rm -rf ./x"}))
                         "Tool call refused (command): rm -rf ./x. permission mode is :deny-by-default"))
      (reset! seen [])
      (call (c :block :bash-ask-commands []) "bash" {:command "rm -rf ./nothing-here"})
      (is (empty? @seen)))
    (testing "remembered by the whole line, and never written to config by r"
      (is (not= (permission/verdict-key {:type :command :tool "bash" :command "rm ./build/tmp"} "q")
                (permission/verdict-key {:type :command :tool "bash" :command "rm -rf ./src"} "q")))
      (is (nil? (permission/grant-for {:type :command :pattern "rm *" :display "rm -rf ./src"}))))))

(deftest auto-mode-classifies-every-command-with-a-live-model
  ;; byj-gly.285.3: measured, the list missed every dangerous command it does
  ;; not name; so in :auto, with a model to ask, every command is classified
  (let [dir (fresh-dir "every-command")
        seen (atom [])
        c (fn [lm & {:as cfg}]
            (assoc (agent/make-ctx {:config (merge {:working-dir dir :project-dir dir :allowed-dirs [dir]} cfg)})
                   :lm lm
                   :auto-classify (fn [req _] (swap! seen conj req) {:verdict :block :reason "publishes the package"})))
        live {:provider :claude-code :model "sonnet"}
        run! (fn [ctx] (call ctx "bash" {:command "npm publish"}))]
    (testing "a command no glob names is classified, and a block refuses it"
      (is (str/includes? (str (run! (c live))) "blocked by auto mode: publishes the package"))
      (is (= {:type :command :display "npm publish" :pattern nil} (select-keys (last @seen) [:type :display :pattern]))))
    (testing "not with the setting off, in another mode, or without a live model to ask"
      (reset! seen [])
      (run! (c live :auto-mode-classify-every-command? false))
      (run! (c live :permission-mode :ask-each-time))
      (run! (c {:provider :mock :format :mock}))
      (run! (c {:provider :claude-code :cli-command "/tmp/fake-claude"}))
      (run! (c nil))
      (is (empty? @seen)))
    (testing "the defaults are the measured ones"
      (is (= "claude-code/sonnet" (get-in brainyard.config/config-items [:auto-mode-classifier-lm :default])))
      (is (true? (get-in brainyard.config/config-items [:auto-mode-classify-every-command? :default]))))))

(deftest a-verdict-is-remembered-for-the-request-and-said
  ;; byj-gly.285.2: the same call for the same question costs one classifier
  ;; call; a new question asks again; an unsure verdict is not remembered; each
  ;; verdict is an event
  (let [dir (fresh-dir "classify-cache")
        outside (.getAbsolutePath (io/file (fresh-dir "classify-cache-out")))
        _ (spit (io/file outside "a.txt") "a")
        _ (spit (io/file outside "b.txt") "b")
        calls (atom 0)
        verdict (atom :allow)
        events (atom [])
        question (atom {:question "compare the notes"})
        c (assoc (agent/make-ctx {:config {:working-dir dir :project-dir dir :allowed-dirs [dir]}
                                  :permission-fn (fn [_] {:allowed true})
                                  :on-event (fn [e d] (when (= :permission/classified e) (swap! events conj d)))})
                 :st-memory question
                 :auto-classify (fn [_ _] (swap! calls inc) {:verdict @verdict :reason "reads a note"}))
        read! #(call c "read-file" {:path (str outside "/" %)})]
    (read! "a.txt")
    (read! "b.txt")
    (is (= 1 @calls) "a sibling in the same directory, for the same request: remembered")
    (is (= [false true] (map :cached? @events)))
    (is (= {:verdict :allow :reason "reads a note" :tool "read-file"}
           (select-keys (first @events) [:verdict :reason :tool])))
    (is (number? (:ms (first @events))))
    (reset! question {:question "something else"})
    (read! "a.txt")
    (is (= 2 @calls) "another request is asked about afresh")
    (testing "unsure is not remembered"
      (reset! verdict :unsure)
      (reset! question {:question "a third"})
      (read! "a.txt") (read! "a.txt")
      (is (= 4 @calls))))
  (testing "the key: the request, the kind, the tool, the directory, the program"
    (is (= ["q" :file-access :bash "bash" nil "/a" "rm"]
           (permission/verdict-key {:type :file-access :action :bash :tool "bash" :path "/a/b" :command "rm -rf /a/b"} "q")))
    (is (= ["q" :tool-use :call "bash" "bash" nil "git"]
           (permission/verdict-key {:type :tool-use :action :call :tool "bash" :pattern "bash" :args {:command "git push"}} "q")))))

(deftest the-classifier-sees-the-question-and-the-call-on-its-own-model
  ;; byj-gly.285.1: never a tool's output; an argument cut short; a cheap model —
  ;; but never a live one for a session on the mock
  (let [ran (atom nil)
        f (agent/auto-classifier {:auto-mode-classifier-lm "claude-code/haiku" :working-dir "/w"})
        ctx {:lm {:provider :claude-code :model "sonnet"} :config {:working-dir "/w" :allowed-dirs ["/w"]}
             :st-memory (atom {:question "tidy the old logs"})}]
    (with-redefs [brainyard.predictor/run (fn [_ inputs opts] (reset! ran [inputs opts])
                                            {:outputs {:verdict "block" :reason "deletes outside"}})]
      (is (= {:verdict :block :reason "deletes outside"}
             (f {:type :file-access :action :write :path "/etc/x" :tool "write-file"
                 :args {:path "/etc/x" :content (apply str (repeat 1000 "z"))}} ctx)))
      (let [[inputs opts] @ran]
        (is (= "tidy the old logs" (:question inputs)))
        (is (str/includes? (:call inputs) ":path \"/etc/x\""))
        (is (str/includes? (:call inputs) "…[600 more chars]") "an argument cut short")
        (is (str/includes? (:workspace inputs) "allowed without asking: /w"))
        (is (= "claude-code/haiku" (:lm-override opts))))
      (f {:type :file-access} (assoc ctx :lm {:provider :mock}))
      (is (nil? (:lm-override (second @ran))) "a mock session classifies on the mock")
      (f {:type :file-access} (assoc ctx :lm {:provider :claude-code :cli-command "/tmp/fake-claude"}))
      (is (nil? (:lm-override (second @ran))) "and a CLI a test swapped in is kept")
      (is (thrown? Exception (f {:type :file-access} (dissoc ctx :lm))) "and a ctx with no model does not classify"))))

(deftest a-directory-allowed-for-the-session-is-reached
  ;; byj-gly.145: /allow-path adds to :allowed-dirs-box, which every gate reads
  ;; beside :allowed-dirs, the bash command's paths included
  (let [dir (fresh-dir "session-dirs")
        outside (.getAbsolutePath (io/file (fresh-dir "session-outside")))
        box (atom [])
        c (agent/make-ctx {:config {:working-dir dir :project-dir dir :allowed-dirs [dir]}
                           :allowed-dirs-box box})]
    (spit (io/file outside "notes.txt") "n")
    (is (str/includes? (str (:error (call c "read-file" {:path (str outside "/notes.txt")}))) "Tool call refused (path)")
        "non-vacuous: refused before")
    (swap! box conj outside)
    (is (= "n" (:content (call c "read-file" {:path (str outside "/notes.txt")}))) "reached once added")
    (is (= [dir outside] (permission/allowed-dirs c dir)))
    (is (not (str/includes? (str (call c "bash" {:command (str "cat " outside "/notes.txt")})) "refused"))
        "a command naming it passes the command gate too")))

(deftest a-login-or-a-key-is-read-by-no-tool
  ;; byj-1wv.115.40: byj-share keeps its login under ~/.brainyard, a default
  ;; allowed dir; a shared agent's caller could have it read. :denied-paths
  ;; are refused in every mode, as is a directory holding one
  (let [dir (fresh-dir "denied")
        home (.getAbsolutePath (io/file (fresh-dir "home")))
        cred (io/file home ".brainyard" "share" "credentials")
        _ (io/make-parents cred)
        _ (spit cred "{\"device_token\":\"not-a-real-token\"}")
        c (fn [& {:as cfg}] (ctx (merge {:working-dir dir :project-dir dir :allowed-dirs [dir (str home)]
                                         :denied-paths [(.getPath (io/file home ".brainyard" "share" "credentials")) (.getPath (io/file home ".ssh"))]}
                                        cfg)))]
    (doseq [mode [:auto-approve :auto :ask-each-time]]
      (let [{:keys [error content]} (call (c :permission-mode mode) "read-file" {:path (.getPath cred)})]
        (is (nil? content) (str mode))
        (is (str/includes? (str error) "it holds a login or a key") (str mode))))
    (is (str/includes? (str (:error (call (c :permission-mode :auto-approve) "grep" {:pattern "token" :path (.getPath (io/file home ".brainyard"))})))
                       "it holds a login or a key")
        "a directory holding one: a recursive read would open it")
    (is (not (str/includes? (str (:error (call (c :permission-mode :auto-approve) "read-file" {:path (.getPath (io/file home ".brainyard" "other.txt"))})))
                            "login"))
        "beside it, not refused for it")
    (is (str/includes? (str (:error (call (c :permission-mode :auto-approve) "bash" {:command (str "cat " (.getPath cred))})))
                       "it names a login or a key")
        "a shell naming one is refused too: a tripwire, not a sandbox")
    (is (= "~/.ssh" (permission/denied-path ["~/.ssh"] ["/h/.ssh/id_ed25519"] "/h")) "~ is the home")
    (is (nil? (permission/denied-path ["~/.ssh"] ["/h/.sshx/a"] "/h")) "by segment")
    ;; provenance P-52: a commit message's "~0.4 ms" was read as the home
    (is (= ["/h" "/h/.ssh/id" "/h"] (permission/home-paths "ls ~ && cat ~/.ssh/id x=~" "/h"))
        "a bare ~ or a ~/ at a word's start is the home")
    (is (empty? (permission/home-paths "git commit -m \"began ~0.4 ms, result ~1.1 s\"" "/h"))
        "a ~ before a number is no path")
    (is (empty? (permission/home-paths "echo a~/b ~root/x" "/h"))
        "nor one mid-word, nor one naming another user")
    (is (nil? (permission/denied-path ["~/.ssh"] (permission/home-paths "echo took ~0.4 ms" "/h") "/h"))
        "so a message with one in it is not refused")
    (is (= "~/.ssh" (permission/denied-path ["~/.ssh"] (permission/home-paths "ls ~" "/h") "/h"))
        "the home itself still holds one")))

;; byj-ng7.12: since byj-ng7.1 a tree at .brainyard/loops/<name>.edn is the
;; project's loop from the next session; a tool that wrote one would make a
;; loop live with no person accepting it (RFC-071)
(deftest a-projects-loop-is-written-by-no-tool
  (let [dir (.getAbsolutePath (io/file (fresh-dir "loops")))
        loops (io/file dir ".brainyard" "loops")
        react (io/file loops "react.edn")
        _ (io/make-parents react)
        _ (spit react "[:action {:id :p/x :fn :react/init}]")
        c (fn [mode] (ctx {:working-dir dir :project-dir dir :allowed-dirs [dir] :permission-mode mode}
                          :permission-fn (fn [_] {:allowed true})))
        refused? #(str/includes? (str (:error %)) "Tool call refused (loop)")]
    (doseq [mode [:auto-approve :auto :ask-each-time :deny-by-default]]
      (is (refused? (call (c mode) "write-file" {:path (.getPath react) :content "[]"})) (str "write-file, " mode))
      (is (refused? (call (c mode) "update-file" {:path ".brainyard/loops/react.edn" :old "x" :new "y"}))
          (str "update-file, by a relative path, " mode))
      (is (refused? (call (c mode) "write-file" {:path ".brainyard/loops/history.edn" :content "[]"}))
          (str "its history too, " mode))
      (is (refused? (call (c mode) "bash" {:command "cp /tmp/better.edn .brainyard/loops/react.edn"}))
          (str "a shell naming one, " mode)))
    (is (= "[:action {:id :p/x :fn :react/init}]" (slurp react)) "and the file is as it was")
    (is (refused? (call (c :auto-approve) "flow$patch" {:path (.getPath react) :ops []}))
        "flow$patch's file goes through the path gate as write-file's does")
    (testing "reading one, and what makes no loop live, are not refused for it"
      (is (str/includes? (str (:content (call (c :auto-approve) "read-file" {:path (.getPath react)}))) ":p/x"))
      (is (not (refused? (call (c :auto-approve) "write-file" {:path ".brainyard/loops/tasks/s.cases.edn" :content "[]"})))
          "a task set's cases are the person's to write, with an agent's help")
      (is (not (refused? (call (c :auto-approve) "write-file" {:path "candidate.edn" :content "[]"})))
          "a candidate elsewhere, to try with --loop")
      (is (not (refused? (call (c :auto-approve) "bash" {:command "ls .brainyard/loops/tasks"})))))
    (testing "the file it guards is the one resolve-tree reads"
      (is (some? (permission/loop-file dir (project-loops/project-file dir "react"))))
      (is (some? (permission/loop-file dir (project-loops/project-file dir "brainyard/loops/react.edn"))))
      (is (some? (permission/loop-file dir (.getPath loops))) "the directory itself: removing it is a change too")
      (is (nil? (permission/loop-file dir (.getPath (io/file loops "tasks" "s.edn")))))
      (is (nil? (permission/loop-file dir (.getPath (io/file dir "react.edn")))))
      (is (nil? (permission/loop-file nil (.getPath react))) "no project, no project loop"))
    (is (= [".brainyard/loops/react.edn"] (permission/named-loop-files "sed -i '' s/a/b/ .brainyard/loops/react.edn && echo ok")))
    (is (= [".brainyard/loops/plan.edn"] (permission/named-loop-files (str "echo x > " dir "/.brainyard/loops/plan.edn"))) "absolute")
    (is (empty? (permission/named-loop-files "cat .brainyard/loops/tasks/s.edn")))))

(deftest path-gate
  (let [dir (fresh-dir "paths")
        outside (.getAbsolutePath (io/file (fresh-dir "outside")))
        ;; the DEFAULT allowed set, for the one block that is about it
        defaults (fn [& {:as cfg}] (ctx (merge {:working-dir dir :project-dir dir} cfg)))
        ;; and the gate with its allowed set PINNED, for everything about
        ;; refusal. `config/default-allowed-dirs` includes /tmp and the system
        ;; temp dir, so a checkout under either makes the `outside` fixture
        ;; allowed and every refusal below vacuous — green here, red from a
        ;; temp clone, and no hint why (byj-gly.73).
        c (fn [& {:as cfg}] (ctx (merge {:working-dir dir :project-dir dir
                                         :allowed-dirs [dir]}
                                        cfg)))]
    (spit (io/file outside "secret.txt") "s")
    (testing "paths under the working dir, /tmp and the temp dir are allowed"
      (is (= {:path "a.txt" :chars 1} (call (defaults) "write-file" {:path "a.txt" :content "x"})))
      (is (= "x" (:content (call (defaults) "read-file" {:path "a.txt"}))))
      (is (= {:path "/tmp/byj-path-test.txt" :chars 1}
             (call (defaults) "write-file" {:path "/tmp/byj-path-test.txt" :content "x"}))))
    (testing "a path outside them is refused, and the message names the directories"
      (let [{:keys [error]} (call (c) "read-file" {:path (str outside "/secret.txt")})]
        (is (str/starts-with? error "Tool call refused (path): "))
        (is (str/includes? error "outside the allowed directories, and there is no interactive permission channel (headless)"))
        (is (str/includes? error (str "Allowed directories: " dir))))
      (is (str/includes? (:error (call (c) "write-file" {:path (str outside "/x.txt") :content "x"}))
                         "Tool call refused (path)")))
    (testing "the refusal says which action it was"
      (is (str/includes? (:reason (permission/path-gate {:tool-name "write-file" :args {:path (str outside "/x")}}
                                                        (c :permission-mode :deny-by-default) {}))
                         "Path write refused"))
      (is (str/includes? (:reason (permission/path-gate {:tool-name "read-file" :args {:path (str outside "/x")}}
                                                        (c :permission-mode :deny-by-default) {}))
                         "Path read refused")))
    (testing ":allowed-dirs extends the set; ..-escapes resolve before the check"
      (is (= "s" (:content (call (c :allowed-dirs [dir outside]) "read-file" {:path (str outside "/secret.txt")}))))
      (is (str/includes? (:error (call (c) "read-file" {:path "../outside/secret.txt"}))
                         "Tool call refused (path)")))
    (testing "a sibling directory is not inside one: /tmpfoo is not under /tmp"
      (is (true? (permission/under? "/tmp" "/tmp/x")))
      (is (true? (permission/under? "/tmp" "/tmp")))
      (is (false? (permission/under? "/tmp" "/tmpfoo/x"))))
    (testing "every path is under the root, so :allowed-dirs [\"/\"] allows everything (byj-gly.466)"
      (is (true? (permission/under? "/" "/x/a")))
      (is (true? (permission/under? "/" "/")))
      (is (= "s" (:content (call (c :allowed-dirs ["/"] :permission-mode :deny-by-default)
                                 "read-file" {:path (str outside "/secret.txt")})))))
    (testing ":auto-approve abstains, :ask-each-time asks with the path and action"
      (is (map? (call (c :permission-mode :auto-approve) "list-dir" {:path outside})))
      (let [asked (atom nil)
            r (call (agent/make-ctx {:config {:working-dir dir :project-dir dir :allowed-dirs [dir]
                                              :permission-mode :ask-each-time}
                                     :permission-fn (fn [req] (reset! asked req) {:allowed true})})
                    "read-file" {:path (str outside "/secret.txt")})]
        (is (= "s" (:content r)))
        (is (= {:type :file-access :action :read :tool "read-file" :path (str outside "/secret.txt")}
               (select-keys @asked [:type :action :tool :path])))))
    (testing "a user tool's own calls are gated as they run"
      (call (c) "tool$create" {:name "peek" :body (str "(fn [_] (read-file {:path \"" outside "/secret.txt\"}))")})
      (is (str/includes? (:error (call (c) "user$tool$peek" {})) "Tool call refused (path)")))
    (testing ":path-permission? false turns the check off"
      (is (= "s" (:content (call (c :path-permission? false) "read-file" {:path (str outside "/secret.txt")})))))))

(deftest the-ask-channel
  (let [answers (atom ["y" "n" "a" "" "d"])
        prompts (atom [])
        ask (fn [p] (swap! prompts conj p) (let [[a] (swap-vals! answers #(vec (rest %)))] (first a)))
        pfn (permission/asking-permission-fn ask)
        req {:display "bash" :pattern "bash" :args {:command "rm -rf /"} :effects #{:process}}]
    (is (= {:allowed true} (pfn req)))
    (is (str/includes? (first @prompts) "permission: bash {:command \"rm -rf /\"} — effects :process"))
    (is (= {:denied true :reason "denied by user"} (pfn req)))
    (testing "`a` is remembered for the session, and nothing is asked again"
      (is (= {:allowed true} (pfn req)))
      (let [n (count @prompts)]
        (is (= {:allowed true} (pfn req)))
        (is (= n (count @prompts)))))
    (testing "another key still asks; an empty answer denies once"
      (is (= {:denied true :reason "denied by user"} (pfn (assoc req :pattern "other")))))
    (testing "`d` is remembered too"
      (is (= {:denied true :reason "denied for this session (other)"} (pfn (assoc req :pattern "other"))))
      (is (= {:denied true :reason "denied for this session (other)"} (pfn (assoc req :pattern "other")))))
    (testing "end of input denies"
      (is (= {:denied true :reason "denied by user"} (pfn (assoc req :pattern "eof")))))

    (testing "without a config there is no remember option, and r is not an answer"
      (is (not (str/includes? (first @prompts) "remember"))))))

;; --- a grant that outlives the session (byj-gly.83) ---------------------------------

(deftest remember-writes-the-grant-and-says-where
  (let [dir (fresh-dir "grant")
        cfg {:working-dir dir :project-dir dir :user-dir (fresh-dir "grant-home")}
        said (atom [])
        prompts (atom [])
        answers (atom ["r" "r"])
        ask (fn [p] (swap! prompts conj p) (first (first (swap-vals! answers #(vec (rest %))))))
        pfn (permission/asking-permission-fn ask cfg #(swap! said conj %))]

    (testing "a tool grant lands in :tool-allow-tools"
      (is (= {:allowed true} (pfn {:display "bash" :pattern "bash" :effects #{:process}})))
      (is (= ["bash"] (get-in (read-string (slurp (config/config-file dir)))
                              [:agent :config :tool-allow-tools]))))

    (testing "a file grant lands in :allowed-dirs, as the DIRECTORY that was asked about"
      (is (= {:allowed true} (pfn {:type :file-access :action :write
                                   :display "write-file" :path (str dir "/sub/x.txt")})))
      (is (some #(str/ends-with? % "/sub")
                (get-in (read-string (slurp (config/config-file dir)))
                        [:agent :config :allowed-dirs]))))

    (testing "the prompt offers it, and what was written is reported — a config
              file changed quietly by answering a prompt is the thing not to do"
      (is (str/includes? (first @prompts) "[r]emember"))
      (is (some #(str/includes? % "saved to") @said))
      (is (some #(str/includes? % "tool-allow-tools") @said)))))

(deftest a-grant-that-cannot-be-written-still-allows-the-call
  ;; the person said yes; a failed write must not turn that into a refusal
  (let [dir (fresh-dir "grant-fail")
        blocked (io/file dir ".brainyard")
        _ (spit blocked "not a directory")
        said (atom [])
        pfn (permission/asking-permission-fn (fn [_] "r")
                                             {:project-dir dir
                                              :user-dir (fresh-dir "grant-fail-home")}
                                             #(swap! said conj %))]
    (is (= {:allowed true} (pfn {:display "bash" :pattern "bash"})))
    (is (some #(str/includes? % "could not save") @said))))

(deftest what-a-remember-answer-would-write
  (is (= [:tool-allow-tools "bash"] (permission/grant-for {:display "bash" :pattern "bash"})))
  (is (= [:tool-allow-tools "x"] (permission/grant-for {:display "x"})))
  (testing "a file grants its parent directory, which is what the prompt asked about —
            granting the one file would ask again for its sibling"
    (is (= [:allowed-dirs "/a/b"] (permission/grant-for {:type :file-access :path "/a/b/c.txt"}))))
  (is (nil? (permission/grant-for {}))))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(deftest a-refused-call-does-not-end-the-turn
  (let [dir (fresh-dir "turn")
        prompts (atom [])
        replies (atom [(reply {:tool-calls [{:tool-name "bash" :tool-args {:command "rm -rf /"}}]})
                       (reply {:answer "I was not allowed to run that."})])
        r (agent/ask "q" {:lm {:provider :mock :format :mock
                               :responses (fn [msgs] (swap! prompts conj msgs)
                                            (let [[x] (swap-vals! replies #(vec (rest %)))] (first x)))}
                          :config {:working-dir dir :project-dir dir :tool-deny-tools ["bash"]}})]
    (is (= "I was not allowed to run that." (:answer r)))
    (is (= :answer-channel (:terminated-by r)) "a refusal is :replace, so the turn goes on")
    (is (str/includes? (:content (second (nth @prompts 1))) "Tool call refused (denied): bash")
        "the model reads the refusal as the tool's result")))

;; --- the sandbox ---------------------------------------------------------------------

(def ^:private opts {:home "/Users/x" :cwd "/w" :project-dir "/w" :tmpdir "/var/folders/tmp/"})

(deftest sandbox-profile
  (let [p (sandbox/profile opts)
        ls (str/split-lines p)]
    (testing "brainyard's baseline: deny everything, read anything, write nothing outside the roots"
      (is (= ["(version 1)" "(deny default)" "(allow process-exec*)" "(allow process-fork)"
              "(allow signal (target self))" "(allow file-read*)" "(allow file-read-metadata)"
              "(allow file-write* (subpath \"/dev\"))" "(allow file-ioctl (subpath \"/dev\"))"
              "(allow sysctl-read)" "(allow mach-lookup)" "(allow ipc-posix-sem*)" "(allow network*)"]
             (take 13 ls))))
    (testing "the write roots, in order, with trailing slashes stripped"
      (is (= ["/var/folders/tmp" "/private/var/folders" "/var/folders" "/tmp" "/private/tmp"
              "/Users/x/.brainyard" "/Users/x/Library/Caches" "/w"]
             (sandbox/write-roots opts)))
      (is (= 1 (count (filter #(= "(allow file-write* (subpath \"/w\"))" %) ls)))
          "cwd and project-dir are the same here, and the roots are deduped")))
  (testing "network can be denied, and paths with quotes or spaces are escaped"
    (is (not (str/includes? (sandbox/profile (assoc opts :network? false)) "(allow network*)")))
    (is (str/includes? (sandbox/profile (assoc opts :extra-writes ["/a b/c\"d"]))
                       "(allow file-write* (subpath \"/a b/c\\\"d\"))"))))

(deftest sandbox-argv
  (is (= [sandbox/sandbox-exec "-p" (sandbox/profile opts) "byj" "run"]
         (sandbox/argv opts ["byj" "run"])))
  (testing "a custom profile takes brainyard's -D parameters instead"
    (is (= [sandbox/sandbox-exec "-f" "/p.sb" "-D" "HOME=/Users/x" "-D" "CWD=/w"
            "-D" "PROJECT_DIR=/w" "-D" "TMPDIR=/var/folders/tmp/" "byj"]
           (sandbox/argv (assoc opts :profile-file "/p.sb") ["byj"]))))
  (testing "available? refuses off macOS or without sandbox-exec"
    (is (= (if (sandbox/macos?) {:ok? true} {:ok? false :reason "--sandbox is macOS-only (uses sandbox-exec / seatbelt)."})
           (sandbox/available?)))))

(deftest sandbox-allow-writes
  (is (= ["/abs" (str (System/getenv "HOME") "/h") "/w/rel"]
         (sandbox/parse-allow-writes ["/abs,~/h" "rel"] "/w")))
  (is (= [] (sandbox/parse-allow-writes [] "/w")))
  (is (= ["/one"] (sandbox/parse-allow-writes "/one,/one" "/w")) "deduped"))

;; --- the command gate ----------------------------------------------------------------

(deftest command-segmentation
  (testing "a line is split into the simple commands a shell would run"
    (is (= ["a" "b"] (permission/command-segments "a && b")))
    (is (= ["a" "b" "c"] (permission/command-segments "a; b | c")))
    (is (= ["a" "b"] (permission/command-segments "a\nb"))))
  (testing "the program is the first word past any VAR=value assignments"
    (is (= "bd" (permission/program-of "bd ready")))
    (is (= "bd" (permission/program-of "  BD_HOME=/tmp FOO=1 bd ready")))
    (is (= "/usr/bin/git" (permission/program-of "/usr/bin/git status")))))

(deftest a-separator-inside-quotes-is-text
  ;; byj-gly.315.14: agent$beads' bd create descriptions held ; and were
  ;; refused once a bead — the quoted ; was read as a second command
  (testing "segments split as a shell reads its quoting"
    (is (= ["bd create \"x; y\" -t task"] (permission/command-segments "bd create \"x; y\" -t task")))
    (is (= ["bd create 'a|b'" "ls"] (permission/command-segments "bd create 'a|b' && ls")))
    (is (= ["echo \\; ls"] (permission/command-segments "echo \\; ls")) "an escaped ; is text")
    (is (= ["echo \"a\\\"; b\""] (permission/command-segments "echo \"a\\\"; b\"")) "an escaped quote does not close")
    (is (= ["a" "b" "c" "d"] (permission/command-segments "a | b || c && d"))))
  (let [why #(:reason (permission/command-problem {:bash-allow-commands ["bd"]} %))]
    (testing "a bd create whose quoted text holds ; or a line break runs"
      (is (nil? (why "bd create \"Shell\" --description \"Context: a page; the terminal comes later\"")))
      (is (nil? (why "bd create 'one\ntwo'"))))
    (testing "a real second command is still held to the list"
      (is (= "`ls` is not in :bash-allow-commands" (why "bd create \"a; b\" && ls"))))
    (testing "what runs at a time the text cannot see is refused inside quotes too"
      (is (str/includes? (why "bd create \"$(rm -rf x)\"") "command substitution")))
    (testing "an unclosed quote is refused"
      (is (str/includes? (why "bd create \"a; rm -rf x") "leaves a quote open")))))

(deftest an-allow-list-refuses-what-it-cannot-read
  (let [allow {:bash-allow-commands ["bd" "git"]}
        why #(:reason (permission/command-problem allow %))]
    (testing "the program of every segment must be allowed, by token or basename"
      (is (nil? (why "bd ready")))
      (is (nil? (why "/usr/bin/bd ready")) "an absolute path to an allowed program")
      (is (nil? (why "bd ready && git status")))
      (is (nil? (why "BD_HOME=/tmp bd ready")))
      (is (str/includes? (why "bd ready; rm -rf /tmp/x") "`rm` is not in"))
      (is (str/includes? (why "bd ready | grep x") "`grep` is not in"))
      (is (str/includes? (why "sudo bd ready") "`sudo` is not in")
          "a wrapper is a program too — allowing `bd` does not allow sudo"))

    (testing "what it cannot segment it refuses, rather than guessing"
      (is (str/includes? (why "bd $(rm -rf x)") "command substitution"))
      (is (str/includes? (why (str "echo " (char 96) "whoami" (char 96))) "backquote"))
      (is (str/includes? (why "cat <(bd ready)") "process substitution"))
      (is (nil? (why "bd create --title=\"a; b\""))
          "a quoted separator is text, as the shell reads it (byj-gly.315.14)"))

    (testing "a deny list is a tripwire anywhere in the line, and needs no allow list"
      (is (str/includes? (:reason (permission/command-problem {:bash-deny-commands ["*rm -rf*"]}
                                                              "cd /tmp && rm -rf foo"))
                         ":bash-deny-commands"))
      (is (nil? (permission/command-problem {} "anything at all; really"))
          "with neither list the gate has nothing to say"))))

(deftest the-command-gate-covers-every-tool-that-names-a-command
  (let [dir (fresh-dir "commands")
        c (ctx {:working-dir dir :project-dir dir :bash-allow-commands ["echo"]})]
    (testing "the bash tool"
      (is (str/includes? (:output (call c "bash" {:command "echo hello"})) "hello"))
      (let [r (call c "bash" {:command "whoami"})]
        (is (str/includes? (:error r) "Tool call refused (command)"))
        (is (str/includes? (:error r) "`whoami` is not in :bash-allow-commands"))
        (is (str/includes? (:error r) "Allowed commands: echo"))))

    (testing "and task$run, which starts one in the background"
      (let [r (call c "task$run" {:job-type "bash" :command "whoami"})]
        (is (str/includes? (:error r) "Tool call refused (command)")
            "backgrounding a command is not a way around the gate")
        (is (nil? (:task-id r)) "and no task was started")))

    (testing "a denied command is refused whatever the mode, as the deny gate is"
      (let [c (ctx {:working-dir dir :project-dir dir
                    :permission-mode :auto-approve
                    :bash-deny-commands ["*shutdown*"]})]
        (is (str/includes? (:error (call c "bash" {:command "sudo shutdown -h now"}))
                           ":bash-deny-commands"))))))

(deftest a-command-may-not-name-a-path-it-could-not-open
  (let [dir (fresh-dir "command-paths")
        c (ctx {:working-dir dir :project-dir dir
                :allowed-dirs [dir] :permission-mode :deny-by-default})]
    (testing "an absolute path outside the allowed dirs is refused"
      (let [r (call c "bash" {:command "cat /etc/hosts"})]
        (is (str/includes? (:error r) "Tool call refused (path)"))
        (is (str/includes? (:error r) "/etc/hosts"))))
    (testing "a path that does not exist is not a violation — brainyard's rule"
      (is (= [] (permission/command-paths "echo hi > /etc/nothing-here-at-all"))))
    (testing "and a path inside them is fine"
      (is (str/includes? (:output (call c "bash" {:command (str "ls " dir)})) "")))))
