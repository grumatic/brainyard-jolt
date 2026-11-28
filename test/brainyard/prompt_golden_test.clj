;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.prompt-golden-test
  "What byj sends a model, held byte for byte (byj-2e8.1).

  Each case drives one turn on the mock provider, in a project of its own
  outside this repository, and keeps the request a model would be sent at the
  moment the case is about — a ReAct turn's first call, the call after a
  FORMAT ERROR or a NO ACTION, the call after a refused tool, a subagent's and
  a skill-agent's first call, and the `claude -p` argv and stdin of a call with
  a schema — as `test/fixtures/prompts/<case>.txt`.

  It is what lets text move out of `src/` into resources (byj-2e8) and be
  shown to have changed no byte the model reads. A change that means to change
  the words writes the files again in the same commit —
  `BYJ_PROMPT_RECORD=1 jolt -M:test brainyard.prompt-golden-test` — and its
  diff is the change to what the model reads.

  What differs between two machines or two runs — the project's directory,
  the home, the user, the host, the shell, the clock — is written `<dir>`,
  `<home>`, `<user>`, `<os>`, `<arch>`, `<shell>` and `<now>`; anything else
  that varies is a finding, not something to write over."
  (:refer-clojure :exclude [record?])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.json :as json]
            [jolt.process :as p]))

(def root "test/fixtures/prompts")

(def record? (= "1" (System/getenv "BYJ_PROMPT_RECORD")))

(defn- fresh-project
  "An empty project of its own for `case`, outside this repository, so no file
  of this repository's (its AGENTS.md, its .brainyard/) reaches the prompt. It
  has an AGENTS.md of its own, so the project's instructions are held too."
  [case]
  (let [d (io/file (System/getProperty "java.io.tmpdir") "byj-prompt-golden" case)]
    (doseq [f (reverse (file-seq d))] (.delete ^java.io.File f))
    (.mkdirs d)
    (spit (io/file d "AGENTS.md") "Answer in one sentence.\n")
    (spit (io/file d "notes.txt") "the build is green\n")
    (.getCanonicalPath d)))

(defn- normal
  "`text` with what differs between machines and runs written as a name."
  [text dir]
  (let [home (System/getenv "HOME")
        shell (System/getenv "SHELL")]
    (cond-> text
      true (str/replace dir "<dir>")
      (not (str/blank? home)) (str/replace home "<home>")
      true (str/replace (str "- User: " (System/getProperty "user.name")) "- User: <user>")
      true (str/replace (str "- OS: " (System/getProperty "os.name") " (" (System/getProperty "os.arch") ")")
                        "- OS: <os> (<arch>)")
      (not (str/blank? shell)) (str/replace (str "- Shell: " shell) "- Shell: <shell>")
      true (str/replace #"(?m)^- Now: .*$" "- Now: <now>"))))

(defn- messages-text
  "A request's messages, each under its role."
  [messages]
  (str/join "\n" (for [m messages]
                   (str "=== " (:role m) " ===\n" (:content m)))))

(defn- held
  "`text` is `<root>/<case>.txt`'s, or, recording, becomes it."
  [case text]
  (let [f (io/file root (str case ".txt"))]
    (if record?
      (do (io/make-parents f) (spit f text) (is true))
      (is (= (when (.isFile f) (slurp f)) text)
          (str root "/" case ".txt is not what byj sends now. If the change means to change what"
               " the model reads: BYJ_PROMPT_RECORD=1 jolt -M:test brainyard.prompt-golden-test,"
               " and review the diff")))))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(def ^:private done (reply {:answer "done" :goal-achieved true}))

(defn- turn
  "Run one turn in a fresh project for `case`, the mock answering with
  `replies` in order (a reply is a string, or (fn [messages]) of one). Returns
  `{:dir :requests :result}`: every request's messages, in the order sent."
  [case replies & [{:keys [config question setup]}]]
  (let [dir (fresh-project case)
        _ (when setup (setup dir))
        requests (atom [])
        left (atom (vec replies))
        r (agent/ask (or question "What does notes.txt say?")
                     {:lm {:provider :mock :format :mock
                           :responses (fn [messages]
                                        (swap! requests conj messages)
                                        (let [[x] (swap-vals! left #(vec (rest %)))
                                              x (or (first x) done)]
                                          (if (fn? x) (x messages) x)))}
                      :config (merge {:working-dir dir :project-dir dir
                                      :session-id "golden"
                                      :max-iterations 5
                                      ;; the person's own instruction files are theirs
                                      :user-instruction-paths []}
                                     config)})]
    {:dir dir :requests @requests :result r}))

(defn- hold-request [case {:keys [dir requests]} i]
  (is (< i (count requests)) (str case ": no request " i " was sent"))
  (when (< i (count requests))
    (held case (normal (messages-text (nth requests i)) dir))))

(deftest a-react-turn
  (hold-request "react-turn" (turn "react-turn" [done]) 0))

(deftest the-call-after-a-format-error
  (let [t (turn "repair-format-error" ["not json at all" done])]
    (is (str/includes? (:content (last (second (:requests t)))) "FORMAT ERROR"))
    (hold-request "repair-format-error" t 1)))

(deftest the-call-after-no-action
  (let [t (turn "repair-no-action" [(reply {}) done])]
    (is (str/includes? (:content (last (second (:requests t)))) "NO ACTION"))
    (hold-request "repair-no-action" t 1)))

(deftest the-call-after-a-refused-tool
  (let [t (turn "permission-refused"
                [(reply {:tool-calls [{:name "write-file" :args {:path "out.txt" :content "x"}}]}) done]
                {:config {:permission-mode :deny-by-default :tool-approval-patterns ["write-file"]}})]
    (is (str/includes? (:content (last (second (:requests t)))) "refused"))
    (hold-request "permission-refused" t 1)))

(deftest a-subagents-first-call
  ;; the parent's first call, the child's (explore's persona in place of the
  ;; ReAct instruction), the parent's second
  (let [t (turn "subagent-explore"
                [(reply {:tool-calls [{:name "agent$explore" :args {:question "What does notes.txt say?"}}]})
                 done done])]
    (hold-request "subagent-explore" t 1)))

(deftest a-skill-agents-first-call
  ;; a skill that runs itself in an agent: skills.clj's instruction around
  ;; its SKILL.md is the child's
  (let [t (turn "skill-agent"
                [(reply {:tool-calls [{:name "skill$summarize-notes" :args {:question "Summarize notes.txt"}}]})
                 done done]
                {:setup (fn [dir]
                          (let [f (io/file dir ".brainyard" "skills" "summarize-notes" "SKILL.md")]
                            (io/make-parents f)
                            (spit f (str "---\nname: summarize-notes\n"
                                         "description: Summarize a notes file in one line.\n"
                                         "dispatch: agent\n---\n\n"
                                         "# Summarize notes\n\nRead the file and say what it says in one line.\n"))))})]
    (hold-request "skill-agent" t 1)))

(defn- cli-script
  "A stand-in `claude -p` that answers once, done."
  [dir]
  (let [script (io/file dir "fake-claude")]
    (spit script (str "#!/bin/bash\ncat > /dev/null\n"
                      "printf '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"{\\\\\"reasoning\\\\\":\\\\\"r\\\\\",\\\\\"tool-calls\\\\\":[],\\\\\"answer\\\\\":\\\\\"done\\\\\",\\\\\"goal-achieved\\\\\":true}\"}\\n'\n"))
    (p/sh "chmod" "+x" (.getPath script))
    (.getCanonicalPath script)))

(deftest a-claude-cli-call-with-a-schema
  ;; the structured-output directive is appended to the system prompt the
  ;; CLI is given; argv and stdin are what makes a call replayable (P-6)
  (let [dir (fresh-project "claude-cli")
        bin (doto (io/file (System/getProperty "java.io.tmpdir") "byj-prompt-golden" "claude-cli-bin") .mkdirs)
        script (cli-script bin)
        calls (atom [])
        _ (agent/ask "What does notes.txt say?"
                     {:lm {:provider :claude-code :format :claude-code :model "sonnet"
                           :cli-command script :timeout-ms 30000}
                      :config {:working-dir dir :project-dir dir :session-id "golden"
                               :max-iterations 2 :user-instruction-paths []}
                      :on-event (fn [e d] (when (= :llm.cli/call e) (swap! calls conj d)))})
        {:keys [args stdin]} (first @calls)]
    (is (some? args) "the CLI was called")
    (is (str/includes? (str/join " " args) "STRUCTURED OUTPUT"))
    (held "claude-cli"
          (normal (str (str/join "\n" (cons "=== argv 0 ===\nclaude"
                                            (map-indexed (fn [i a] (str "=== argv " (inc i) " ===\n" a)) (rest args))))
                       "\n=== stdin ===\n" stdin)
                  dir))))
