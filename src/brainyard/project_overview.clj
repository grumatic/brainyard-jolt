;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.project-overview
  "The left rail's Project section (byj-gly.384): what the project itself
  defines for brainyard — its instructions, agents, skills, tools, commands,
  hooks, MCP servers, programs and user predictors — one group per kind.

  Only the project's own: a built-in agent or a skill in `~/.claude/skills` is
  the machine's, not the project's, and the rail is a map of the project.
  Each kind is read where its subsystem reads it: `agents/load-agents` and
  `skills/list-skills`, which drop what they cannot read, and the subsystem's
  own root for what is a plain file. A tool, command or hook file is listed
  whether or not it loads — checking one means compiling it against the
  session's registry, which is the session's to do, and it says at start
  which it skipped — so a broken one is still there to open and fix.

  Mechanism only (RFC-001): when it is read is the caller's (`refresh!`), and
  a read runs off the render loop, a newer one cancelling an older, as
  `brainyard.project-views` does for the right rail."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agents :as agents]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.hookbox :as hookbox]
            [brainyard.session :as session]
            [brainyard.skills :as skills]
            [ebb.core :as m]))

(defn- project-dir [config] (or (:project-dir config) (:working-dir config) "."))

(defn- canon [f] (.getCanonicalPath (io/file (str f))))

(defn- files-in
  "The files directly in `dir` ending in `ext` (or any file, ext nil), sorted
  by name, as `[{:name :path}]`, the name without `ext`."
  [dir ext]
  (let [d (io/file (str dir))]
    (if-not (.isDirectory d)
      []
      (vec (for [f (sort-by #(.getName %) (.listFiles d))
                 :when (and (.isFile f) (not (str/starts-with? (.getName f) "."))
                            (or (nil? ext) (str/ends-with? (.getName f) ext)))]
             {:name (cond-> (.getName f) ext (subs 0 (- (count (.getName f)) (count ext))))
              :path (canon f)})))))

(defn- programs
  "The programs under `root` by id, `ns/name`: one has its params at
  `<ns>/<name>.edn`, its datasets, evals and proposals under `<ns>/<name>/`,
  or both (`programs/params-file-for`). The item opens the params where there
  are some, else the folder."
  [root]
  (let [d (io/file (str root))]
    (if-not (.isDirectory d)
      []
      (vec (for [ns-dir (sort-by #(.getName %) (.listFiles d))
                 :when (.isDirectory ns-dir)
                 nm (sort (distinct (keep (fn [f] (cond (.isDirectory f) (.getName f)
                                                        (str/ends-with? (.getName f) ".edn") (subs (.getName f) 0 (- (count (.getName f)) 4))))
                                          (.listFiles ns-dir))))
                 :let [params (io/file ns-dir (str nm ".edn"))]]
             {:name (str (.getName ns-dir) "/" nm)
              :path (canon (if (.isFile params) params (io/file ns-dir nm)))})))))

(defn read-overview
  "What project `config` names defines, as `[{:kind :title :items [{:name
  :path}]}]` in the order the rail shows them, every kind present and an
  empty one with no items. Blocking: run it off the render loop."
  [config]
  (let [dir (project-dir config)
        project-config (io/file dir ".brainyard" "config.edn")]
    [{:kind :instructions :title "Instructions"
      :items (vec (for [rel ["AGENTS.md" "CLAUDE.md" ".brainyard/config.edn"]
                        :let [f (io/file dir rel)]
                        :when (.isFile f)]
                    {:name rel :path (canon f)}))}
     {:kind :agents :title "Agents"
      :items (vec (for [a (:agents (agents/load-agents (assoc config :builtin-agents? false)))
                        :when (= "project" (:source a))]
                    {:name (:name a)
                     :path (let [edn (io/file (str (:dir a)) "agent.edn")]
                             (canon (if (.isFile edn) edn (:dir a))))}))}
     {:kind :skills :title "Skills"
      :items (vec (for [s (skills/list-skills config)
                        :when (= :project (:scope s))]
                    {:name (:name s) :path (canon (:path s)) :hint (some-> (:backend s) name)}))}
     {:kind :tools :title "Tools"
      :items (files-in (io/file dir ".brainyard" "tools") ".edn")}
     {:kind :commands :title "Commands"
      :items (vec (for [r (session/command-roots config)
                        :when (= "project" (:source r))
                        x (files-in (:dir r) ".edn")]
                    (update x :name #(str "/" %))))}
     {:kind :hooks :title "Hooks"
      :items (files-in (hookbox/hooks-dir dir) nil)}
     ;; the project's own file, not the merged config: a server in
     ;; ~/.brainyard/config.edn is the machine's
     {:kind :mcp :title "MCP servers"
      :items (vec (for [nm (sort (map name (keys (:mcp-servers (:config (config/read-file project-config))))))]
                    {:name nm :path (canon project-config)}))}
     {:kind :programs :title "Programs"
      :items (programs (io/file dir ".brainyard" "programs"))}
     {:kind :predictors :title "Predictors"
      :items (mapv #(update % :name (fn [n] (str "user/" n)))
                   (files-in (io/file dir ".brainyard" "predictors" "user") ".edn"))}]))

(defn rows
  "The section's rows for `groups` (`read-overview`), the kinds in `open`
  unfolded: a group with nothing in it is left out, a folded one is a count."
  [groups open]
  (vec (mapcat (fn [{:keys [kind title items]}]
                 (when (seq items)
                   (let [open? (contains? open kind)]
                     (cons {:label (str (if open? "▾ " "▸ ") title) :hint (str (count items)) :group kind}
                           (when open?
                             (for [{:keys [name path hint]} items]
                               {:label (str "  " name) :path path :hint hint}))))))
               groups)))

(defn views
  "The Project view for `config` and the `refresh!` that rereads it:
  `{:view v :refresh! (fn []) :state cell}`. Enter on a kind folds or
  unfolds it, on an item calls `open` with its file; `@` mentions the file
  by its path from the project. `cell` and `kept` as for
  `project-views/views`."
  [config & [{:keys [cell kept open] :or {cell atom}}]]
  (let [state (cell {:reading? true})
        unfolded ((or kept (fn [_ x] (cell x))) :project/unfolded #{})
        running (atom nil)
        dir (canon (project-dir config))
        refresh! (fn []
                   (some-> @running (apply []))
                   (reset! running
                           (fx/run-detached
                            (m/sp (try (let [g (m/? (fx/task-of #(read-overview config)))]
                                         (reset! state {:groups g}))
                                       (catch Throwable e
                                         (when-not (fx/cancelled? e)
                                           (reset! state {:error (or (ex-message e) (str e))})))))
                            :project-overview)))]
    {:refresh! refresh!
     :state state
     :view {:id "project" :title "Project"
            :items (fn [] (let [s @state]
                            (cond (:error s) [{:label (str "  could not read: " (:error s))}]
                                  (nil? (:groups s)) [{:label "  reading…"}]
                                  :else (or (not-empty (rows (:groups s) @unfolded))
                                            [{:label "  nothing of brainyard's yet"}]))))
            :act (fn [{:keys [group path]}]
                   (cond group (swap! unfolded #(if (contains? % group) (disj % group) (conj % group)))
                         (and path open) (open path)))
            :put (fn [{:keys [path]}]
                   (when path
                     (str "@" (if (str/starts-with? path (str dir "/")) (subs path (inc (count dir))) path))))
            :on-show refresh!}}))
