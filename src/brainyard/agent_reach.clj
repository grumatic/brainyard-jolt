;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.agent-reach
  "What an agent can make happen when shared (byj-1wv.47): the agent as the
  headless session a share starts would run it. Its own namespace because
  both `byj agent` (main) and a session's `/share agent:NAME` (share-tools,
  which agent loads) ask it, and it builds a ctx through agent."
  (:require [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.agents :as agents]
            [brainyard.llm :as llm]
            [brainyard.mcp :as mcp]
            [brainyard.permission :as permission]))

(defn- unseen-mcp-tools
  "The tools config `c` lets an agent call of each enabled MCP server that
  is not running here — which tools a server has is known only once it
  runs — as stand-ins that may change something there: a tool the agent's
  `:tools` names is itself, a glob reaching the server's tools is the glob
  (the server's every tool for a wider one, `*` among them), each still
  hidden by what hides it (byj-1wv.115.42). A tool named that its server's
  spec says only reads (`:read-only-tools`) reads, as it will when the
  server runs (byj-1wv.115.44)."
  [c running]
  (for [[s spec] (mcp/enabled-specs c)
        :when (not (contains? running s))
        :let [prefix (str "mcp$" s "$")
              lit #(first (str/split % #"\*" 2))
              reaching (fn [g] (let [p (lit g)]
                                 (cond (not (str/includes? g "*")) (when (str/starts-with? g prefix) g)
                                       (str/starts-with? p prefix) g
                                       (str/starts-with? prefix p) (str prefix "*"))))
              stand-ins (if (empty? (:visible-tools c))
                          [(str prefix "*")]
                          (distinct (keep reaching (:visible-tools c))))]
        n stand-ins
        :when (agents/visible? c n)]
    {:name n
     :effects (if (contains? (set (map #(str prefix %) (:read-only-tools spec))) n) #{:mcp/call} #{:mcp/call :mcp/write})
     :mcp {:server s :unseen? true}}))

(defn agent-reach
  "Agent `nm` as a headless session started as it would run — what a shared
  agent's callers reach (byj-1wv.47): its tools resolved as that session
  resolves them (the persona's config, then the registry `make-ctx` builds,
  filtered), their reach (`agents/tool-reach`), the permission mode in force, the
  model and the project. \"default\" is byj's main agent, no persona. nil
  for an agent this project does not have.

  The MCP servers its session would start count too (byj-1wv.115.42): the
  tools of those running in `mcp-box`, a session's own, as they are, and
  those of any other as `unseen-mcp-tools` — never left out because no
  server runs where the reach is asked."
  ([config nm] (agent-reach config nm nil))
  ([config nm {:keys [mcp-box]}]
   (let [default? (= "default" nm)
         a (when-not default? (first (filter #(= nm (:name %)) (:agents (agents/load-agents config)))))]
     (when (or default? a)
       (let [c (if default? config (agents/persona-config a config))
             ctx (agent/make-ctx {:config c :lm {:provider :mock :format :mock :model "mock"} :mcp-box mcp-box})
             running (set (keys (some-> mcp-box mcp/box-clients)))
             mode (or (:permission-mode config) :auto)]
         (merge (agents/tool-reach (concat (vals @(:tools ctx)) (unseen-mcp-tools c running))
                                   {:bash-allow-commands (:bash-allow-commands c) :every-tool? default?})
                {:name nm
                 :description (if default? "byj's main agent: every tool, no persona" (str (:description a)))
                 :permission-mode mode
                 :resolved-mode (permission/resolve-mode mode)
                 :model (str (or (some-> (:lm a) (#(if (map? %) (llm/lm-label %) %))) (:default-lm config)))
                 :project-dir (str (or (:project-dir config) (:working-dir config)))}))))))

(defn summary
  "What callers of a shared agent can make it do, in a line a person is
  asked over: what it can do, and what asks first."
  [{:keys [level writes shell network hands-off services service-writes permission-mode reads-anywhere] nm :name}]
  (str "share agent:" nm " — callers can make it "
       (if (= :every-tool level)
         "use every tool byj has"
         (str/join ", " (remove nil? [(cond (= :none level) "answer from what it is told, with no tool"
                                             reads-anywhere "read any file on this machine"
                                             :else "read files")
                                      (when (seq writes) (str "write files (" (str/join ", " writes) ")"))
                                      (when shell (if (:any shell) "run any command" (str "run only " (str/join ", " (:programs shell)))))
                                      (when (seq network) (str "reach the network (" (str/join ", " network) ")"))
                                      (when (seq hands-off) (str "hand work on (" (str/join ", " hands-off) ")"))
                                      (when (seq services)
                                        (str "use " (str/join ", " services) " with the connection you gave it"
                                             (when (seq service-writes) (str " and change things there (" (str/join ", " service-writes) ")"))))])))
       (if (= :auto-approve permission-mode)
         "; nothing asks first: this project runs auto-approve"
         "; a call that would ask is refused — no one is there to approve it")))
