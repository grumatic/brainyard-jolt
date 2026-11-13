;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.test-runner
  "The suite. A new test namespace goes in BOTH the :require list and
  `namespaces`, or it silently never runs."
  (:require [clojure.edn]
            [clojure.java.io]
            [clojure.set]
            [clojure.string]
            [brainyard.schema :as schema]
            [clojure.test :as t]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.test-shards :as shards]
            [brainyard.test-run :as test-run]
            [brainyard.split-test]
            [brainyard.test-shards-test]
            [brainyard.agent-test]
            [brainyard.cache-test]
            [brainyard.cancellation-test]
            [brainyard.channel-test]
            [brainyard.share-test]
            [brainyard.discovery-test]
            [brainyard.config-test]
            [brainyard.configbox-test]
            [brainyard.context-test]
            [brainyard.cost-test]
            [brainyard.divergences-test]
            [brainyard.ebb-bindings-test]
            [brainyard.eventlog-test]
            [brainyard.events-test]
            [brainyard.flow-test]
            [brainyard.hooks-test]
            [brainyard.effect-ratchet-test]
            [brainyard.effect-test]
            [brainyard.permission-test]
            [brainyard.mcp-test]
            [brainyard.main-test]
            [brainyard.memory-test]
            [brainyard.persist-test]
            [brainyard.predictor-test]
            [brainyard.programs-test]
            [brainyard.session-test]
            [brainyard.sdk-test]
            [brainyard.acp-test]
            [brainyard.agents-test]
            [brainyard.analytics-test]
            [brainyard.self-improve-test]
            [brainyard.proposals-test]
            [brainyard.tool-distill-test]
            [brainyard.skills-test]
            [brainyard.structured-output-test]
            [brainyard.tasks-test]
            [brainyard.teams-test]
            [brainyard.tui-test]
            [brainyard.tui-frame-test]
            [brainyard.rail-state-test]
            [brainyard.clock-test]
            [brainyard.project-views-test]
            [brainyard.project-overview-test]
            [brainyard.issues-test]
            [brainyard.memory-rail-test]
            [brainyard.self-improve-rail-test]
            [brainyard.activity-test]
            [brainyard.terminal-test]
            [brainyard.emacs-test]
            [brainyard.changes-test]
            [brainyard.loop-tasks-test]
            [brainyard.loop-proposals-test]
            [brainyard.loop-eval-test]
            [brainyard.loop-watch-test]
            [brainyard.links-test]
            [brainyard.release-test]
            [brainyard.update-test]
            [brainyard.toolshelf-test]
            [brainyard.claude-sessions-test]
            [brainyard.codex-sessions-test]
            [brainyard.worktree-test]
            [brainyard.claude-agent-test]
            [brainyard.codex-agent-test]
            [brainyard.codex-usage-test]
            [brainyard.codex-provider-test]
            [brainyard.claude-reach-test]
            [brainyard.codex-reach-test]
            [brainyard.claude-usage-test]
            [brainyard.web-share-test]
            [brainyard.schedule-test]
            [brainyard.init-test]
            [brainyard.auth-test]
            [brainyard.sim-test]
            [brainyard.line-input-test]
            [brainyard.children-test]
            [brainyard.claude-host-test]
            [brainyard.codex-host-test]
            [brainyard.tool-hook-test]
            [brainyard.hook-serve-test]
            [brainyard.contract-test]
            [brainyard.cli-help-test]
            [brainyard.version-test]
            [brainyard.json-test]
            [brainyard.clipboard-test]
            [brainyard.todo-test]
            [brainyard.trajectory-test]
            [brainyard.wire-test]
            [brainyard.trajectory-export-test]
            [brainyard.toolbox-test]
            [brainyard.schema-test]))

(def namespaces
  '[brainyard.agent-test
    brainyard.cache-test
    brainyard.cancellation-test
    brainyard.channel-test
    brainyard.share-test brainyard.split-test
    brainyard.discovery-test
    brainyard.config-test
    brainyard.configbox-test
    brainyard.context-test
    brainyard.cost-test
    brainyard.divergences-test
    brainyard.ebb-bindings-test
    brainyard.eventlog-test
    brainyard.events-test
    brainyard.flow-test
    brainyard.hooks-test
    brainyard.effect-ratchet-test
    brainyard.effect-test
    brainyard.permission-test
    brainyard.mcp-test
    brainyard.main-test
    brainyard.memory-test
    brainyard.persist-test
    brainyard.predictor-test
    brainyard.programs-test
    brainyard.session-test
    brainyard.sdk-test
    brainyard.acp-test
    brainyard.agents-test
    brainyard.analytics-test
    brainyard.self-improve-test
    brainyard.proposals-test
    brainyard.tool-distill-test
    brainyard.skills-test
    brainyard.structured-output-test
    brainyard.tasks-test
    brainyard.teams-test
    brainyard.tui-test
    brainyard.tui-frame-test
    brainyard.rail-state-test
    brainyard.clock-test
    brainyard.project-views-test
    brainyard.project-overview-test
    brainyard.issues-test
    brainyard.memory-rail-test
    brainyard.self-improve-rail-test
    brainyard.activity-test
    brainyard.terminal-test
    brainyard.emacs-test
    brainyard.changes-test
    brainyard.loop-tasks-test
    brainyard.loop-proposals-test
    brainyard.loop-eval-test
    brainyard.loop-watch-test
    brainyard.links-test
    brainyard.release-test
    brainyard.update-test
    brainyard.toolshelf-test
    brainyard.claude-sessions-test
    brainyard.codex-sessions-test
    brainyard.worktree-test
    brainyard.claude-agent-test
    brainyard.codex-agent-test
    brainyard.codex-usage-test
    brainyard.codex-provider-test
    brainyard.claude-reach-test
    brainyard.codex-reach-test
    brainyard.claude-usage-test
    brainyard.web-share-test
    brainyard.schedule-test
    brainyard.test-shards-test
    brainyard.init-test
    brainyard.auth-test
    brainyard.sim-test
    brainyard.line-input-test
    brainyard.children-test
    brainyard.claude-host-test
    brainyard.codex-host-test
    brainyard.tool-hook-test
    brainyard.hook-serve-test
    brainyard.contract-test
    brainyard.cli-help-test
    brainyard.version-test
    brainyard.json-test
    brainyard.clipboard-test
    brainyard.todo-test
    brainyard.trajectory-test
    brainyard.wire-test
    brainyard.trajectory-export-test
    brainyard.toolbox-test
    brainyard.schema-test])

(defn run
  "byj's suite in this process, `test-run/run` over `namespaces` unless
  `opts` names others: (brainyard.test-runner/run) from a live image."
  [& [opts]]
  (test-run/run (merge {:nses namespaces} opts)))

(defn -main
  "The suite — in shards, or in one image with BY_TEST_SHARDS=1 — or, given
  namespaces (`jolt -M:test brainyard.session-test …`), those alone in this
  process, behind the suite's guards: none of the person's stores, nothing
  left in this repository (provenance P-55)."
  [& args]
  (test-run/main-for namespaces args))
