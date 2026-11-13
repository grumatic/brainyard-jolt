;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev
  "REPL helpers for a dev image. Start one, then drive it:

      jolt -A:dev nrepl-server 7899
      clj-nrepl-eval -p 7899 \"(require 'brainyard.dev) (brainyard.dev/reload!)\"
      clj-nrepl-eval -p 7899 \"(brainyard.dev/suite)\"

  The Claude Code shell escapes `!`, which breaks symbols like `reload!` inside
  a `clj-nrepl-eval` argument — call the `reload` alias instead, or put the form
  in a file and `load-file` it (see AGENTS.md)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [brainyard.agent :as agent]
            [brainyard.json :as json]
            [brainyard.predictor :as predictor]))

(def source-order
  "src namespaces in dependency order — the order a reload must follow, since
  jolt has no tools.namespace to derive it."
  '[brainyard.effect brainyard.config brainyard.context brainyard.persist brainyard.json brainyard.llm brainyard.signature brainyard.hooks brainyard.schema brainyard.tools brainyard.tool-lisp brainyard.toolbox brainyard.hookbox brainyard.discovery brainyard.skills brainyard.permission brainyard.bt
    brainyard.predictor brainyard.todo brainyard.flow brainyard.evaluate brainyard.optimize brainyard.predictions brainyard.programs brainyard.agent brainyard.session brainyard.tui brainyard.main])

(def test-order
  '[brainyard.agent-test brainyard.agents-test brainyard.analytics-test brainyard.cache-test brainyard.cancellation-test brainyard.channel-test brainyard.share-test brainyard.split-test brainyard.config-test brainyard.configbox-test brainyard.context-test brainyard.cost-test brainyard.discovery-test brainyard.divergences-test brainyard.ebb-bindings-test brainyard.eventlog-test brainyard.events-test brainyard.flow-test brainyard.hooks-test brainyard.effect-ratchet-test brainyard.effect-test brainyard.mcp-test brainyard.main-test brainyard.cli-help-test brainyard.memory-test brainyard.persist-test brainyard.predictor-test brainyard.programs-test brainyard.session-test brainyard.sdk-test brainyard.acp-test brainyard.skills-test brainyard.self-improve-test brainyard.proposals-test brainyard.tool-distill-test brainyard.structured-output-test brainyard.tasks-test brainyard.teams-test brainyard.todo-test brainyard.tui-test brainyard.tui-frame-test brainyard.rail-state-test brainyard.clock-test brainyard.project-views-test brainyard.project-overview-test brainyard.issues-test brainyard.memory-rail-test brainyard.self-improve-rail-test brainyard.activity-test brainyard.terminal-test brainyard.emacs-test brainyard.changes-test brainyard.loop-tasks-test brainyard.links-test brainyard.release-test brainyard.update-test brainyard.toolshelf-test brainyard.claude-sessions-test brainyard.codex-sessions-test brainyard.worktree-test brainyard.claude-host-test brainyard.codex-host-test brainyard.tool-hook-test brainyard.hook-serve-test brainyard.contract-test brainyard.claude-agent-test brainyard.codex-agent-test brainyard.codex-usage-test brainyard.codex-provider-test brainyard.claude-reach-test brainyard.codex-reach-test brainyard.claude-usage-test brainyard.web-share-test brainyard.schedule-test brainyard.test-shards-test brainyard.init-test brainyard.auth-test brainyard.sim-test brainyard.line-input-test brainyard.children-test brainyard.version-test brainyard.json-test brainyard.clipboard-test brainyard.trajectory-test
   brainyard.wire-test brainyard.trajectory-export-test brainyard.toolbox-test brainyard.schema-test brainyard.test-runner])

(defn forget-tests
  "Drop a namespace's entries from jolt's process-wide clojure.test registry.
  jolt's deftest APPENDS to that registry on every load and nothing removes the
  old entries, so without this each :reload makes run-tests run the namespace's
  tests once more (26 tests reported as 40 after two reloads; provenance P-9). Not in the JVM's
  clojure.test, which finds tests through var metadata."
  [ns-sym]
  (swap! clojure.test/registry (fn [r] (vec (remove #(= ns-sym (:ns %)) r)))))

(defn- reload-ns [ns-sym]
  (forget-tests ns-sym)
  (require ns-sym :reload))

(defn reload!
  "Reload every src namespace in dependency order, then the tests. Returns the
  namespaces reloaded. One namespace at a time with :reload — not
  :reload-all, which re-enters jolt.time."
  []
  (doseq [ns-sym (concat source-order test-order)]
    (reload-ns ns-sym))
  (vec (concat source-order test-order)))

(def reload
  "`reload!` under a name the shell cannot mangle."
  reload!)

(defn suite
  "Run the whole suite in place; returns the summary map."
  []
  (select-keys ((requiring-resolve 'brainyard.test-runner/run)) [:test :pass :fail :error]))

(defn test-ns
  "Reload and run one test namespace, e.g. (test-ns 'brainyard.predictor-test),
  kept from the person's own stores as the runner keeps the suite: a run
  scored online goes to the suite's eval store, no memory store is read,
  Codex's home is an empty one (byj-gly.443), and a turn run in this
  repository is not kept in its refs (provenance P-55). The image's own are put back
  after, so a live session asked from it next reads them as before."
  [ns-sym]
  (let [props {"brainyard.eval-root" (.getCanonicalPath (clojure.java.io/file "target" "test-eval-store"))
               "brainyard.memory-store" "none"
               "brainyard.codex-home" (.getCanonicalPath (doto (clojure.java.io/file "target" "test-codex-home") .mkdirs))
               ;; nor this repository's refs: a test's turn in it is not kept (provenance P-55)
               "brainyard.changes-skip-root" (.getCanonicalPath (clojure.java.io/file "."))}
        was (into {} (for [k (keys props)] [k (System/getProperty k)]))]
    (doseq [[k v] props] (System/setProperty k v))
    (try
      (reload-ns ns-sym)
      (select-keys (t/run-tests ns-sym) [:test :pass :fail :error])
      (finally
        (doseq [[k v] was] (if v (System/setProperty k v) (System/clearProperty k)))))))

(defn mock-lm
  "An LM that answers every call with `reply` (a map, encoded) or (reply messages)."
  [reply]
  {:provider :mock :format :mock
   :responses (fn [messages] (json/write-str (if (fn? reply) (reply messages) reply)))})

(defn prompt
  "The exact messages the ReAct loop's first LLM call sends for `question`,
  without calling a model. opts as brainyard.agent/ask (:config, :params, …)."
  ([question] (prompt question {}))
  ([question opts]
   (let [seen (atom nil)]
     (agent/ask question (merge {:lm (mock-lm (fn [messages]
                                                (reset! seen messages)
                                                {:reasoning "" :tool-calls [] :answer "(dev)"}))}
                                opts))
     @seen)))

(defn ask
  "Run one ReAct turn. provider defaults to :mock; e.g.
  (ask \"list src\" :claude-code {:model \"sonnet\"})."
  ([question] (ask question :mock {}))
  ([question provider] (ask question provider {}))
  ([question provider lm-overrides]
   (let [lm (if (= :mock provider)
              (mock-lm {:reasoning "mock" :tool-calls [] :answer "(mock answer)"})
              (brainyard.llm/create-lm provider #(System/getenv %) lm-overrides))]
     (agent/ask question {:lm lm
                          :on-event (fn [e d]
                                      (case e
                                        :agent.tools/calls (println "🔧" (pr-str (:tool-calls d)))
                                        :agent.think/reasoning (println "💭" (:reasoning d))
                                        nil))}))))

(defn predictor
  "A predictor as the loop would load it from this working directory, with
  its resolved params."
  ([id] (predictor id "."))
  ([id working-dir]
   (let [p (predictor/load-predictor id (predictor/default-roots working-dir "predictors"))]
     (assoc p :resolved-params
            (predictor/resolve-params p {:params-roots (predictor/default-roots working-dir "programs")})))))

(comment
  (reload)
  (suite)
  (test-ns 'brainyard.session-test)
  (println (:content (first (prompt "read deps.edn"))))
  (predictor "react/think-act")
  (ask "what is in deps.edn?" :claude-code {:model "sonnet"}))
