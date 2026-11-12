;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.memory-rail
  "The left rail's Memory section: what the project remembers, by source,
  last in the rail, after Issues (byj-gly.421, byj-gly.422).

  - **beads** (or whatever `:memory-list-cmd` names): byj's own store, the
    one the agent reads and writes through `memory$*` (RFC-020), as the store
    lists it (`memory/list-task`, `bd memories --json` by default). `@`
    mentions a memory's key.
  - **Claude Code**: Claude Code's auto-memory for this project,
    `~/.claude/projects/<project key>/memory/` — a file a memory, with
    `name` and `description` in its front matter, beside the `MEMORY.md`
    index. The person's own, on this machine; read-only here, and not
    something byj's agent recalls. `@` mentions the file's path.

  The old JVM brainyard's SQLite store (`~/.brainyard/memory`) is not a
  source: the port does not read it (RFC-020). Codex's memories are not yet
  (byj-gly.423).

  One source is drawn flat, its memories as rows; several are folded
  headings with their counts, as Issues' epics are. Enter on a memory shows
  it whole in the transcript. Editing and deleting beads' stay `/memory`'s
  (byj-gly.327).

  Mechanism only (RFC-001): when it is read is the caller's (`refresh!`), and
  a read is a Task off the render loop, a newer one cancelling an older, as
  `brainyard.issues` does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.claude-sessions :as claude-sessions]
            [brainyard.effect :as fx]
            [brainyard.init :as init]
            [brainyard.issues :as issues]
            [brainyard.memory :as memory]
            [ebb.core :as m]))

;; --- Claude Code's memory -----------------------------------------------------------

(defn claude-memory-dir
  "Where Claude Code keeps `project-dir`'s memories, under `home` (`~/.claude`
  unless given)."
  [project-dir home]
  (io/file (claude-sessions/claude-home home) "projects" (claude-sessions/project-key project-dir) "memory"))

(defn parse-memory-file
  "A Claude Code memory file as `{:name :description :type :body}`: its front
  matter's fields, read line by line, and the text after it. A file with no
  front matter is all body."
  [text]
  (let [text (str text)
        [_ front body] (re-matches #"(?s)---\r?\n(.*?)\r?\n---\r?\n?(.*)" text)
        field (fn [k] (some->> front (re-find (re-pattern (str "(?m)^\\s*" k ":\\s*(.+?)\\s*$"))) second))]
    {:name (field "name") :description (field "description") :type (field "type")
     :body (str/trim (if front body text))}))

(defn claude-memories
  "Claude Code's memories in `dir`: `[{:key :description :content :path}]`
  by key, a file a memory, the `MEMORY.md` index left out. The key is the
  file's `name`, else its file name."
  [^java.io.File dir]
  (->> (or (.listFiles dir) [])
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".md")
                     (not= "MEMORY.md" (.getName ^java.io.File %))))
       (map (fn [^java.io.File f]
              (let [{:keys [name description body]} (parse-memory-file (slurp f))]
                {:key (or name (str/replace (.getName f) #"\.md$" ""))
                 :description description
                 :content (str/join "\n\n" (remove str/blank? [description body]))
                 :path (.getPath f)})))
       (sort-by :key)
       vec))

;; --- the sources ---------------------------------------------------------------------

(defn sources
  "What the project remembers in, for `config`: each `{:id :label :read
  (fn [] Task)}`, present only when it can be read. A bd store with no bd on
  PATH, or no beads database for bd to find, reads as `{:unavailable true}`
  rather than bd's error, asked again on each read, so /init's beads are read
  once it has made them (byj-gly.458)."
  [config {:keys [home]}]
  (let [project-dir (str (or (:project-dir config) (:working-dir config) "."))
        list-cmd (memory/cmd config :memory-list-cmd)
        cdir (claude-memory-dir project-dir home)]
    (vec (concat
          (when list-cmd
            (let [program (.getName (io/file (str (first list-cmd))))]
              [{:id :store :label (if (= "bd" program) "beads" program)
                :read #(if (and (= "bd" program)
                                (not (and (init/on-path "bd") (issues/beads-found? project-dir))))
                         (fx/success {:unavailable true})
                         (memory/list-task config))}]))
          ;; the person's own: never a test's, unless it names a home of its
          ;; own (the runner switches the default store off, provenance P-40)
          (when (and (or home (not (memory/defaults-off?))) (.isDirectory cdir))
            [{:id :claude :label "Claude Code"
              :read #(fx/task-of (fn [] {:memories (claude-memories cdir)}))}])))))

;; --- the rows ------------------------------------------------------------------------

(defn- memory-rows [source {:keys [memories error unavailable]}]
  (cond
    unavailable issues/beads-unavailable-rows
    error [{:label (str "  could not read: " (first (str/split-lines (str error)))) :style {:dim true}}]
    (nil? memories) [{:label "  reading…"}]
    (empty? memories) [{:label "  no memories yet" :style {:dim true}}]
    :else (mapv (fn [mem] {:label (str "  " (:key mem)) :memory (assoc mem :source source)}) memories)))

(defn rows
  "The section's rows: one source's memories, or each source a folded
  heading with its count, those in `open` unfolded. `read` is each source's
  `{:memories}`, `{:error}` or nothing yet, by id."
  [sources read open]
  (if (= 1 (count sources))
    (memory-rows (:id (first sources)) (get read (:id (first sources))))
    (vec (mapcat (fn [{:keys [id label]}]
                   (let [r (get read id)
                         open? (contains? open id)]
                     (cons {:label (str (if open? "▾ " "▸ ") label)
                            :hint (cond (:memories r) (str (count (:memories r))) (:error r) "!" :else "")
                            :group id}
                           (when open? (memory-rows id r)))))
                 sources))))

;; --- the view ----------------------------------------------------------------------

(defn views
  "The Memory view over `config`'s sources and the `refresh!` that rereads
  them, or nil when there is none to read or memory is off here (`:memory?
  false`): `{:view v :refresh! (fn []) :state cell}`. `show` is called with
  a title and a memory's text; `home` is Claude Code's (`~/.claude` unless
  given); `cell` and `kept` as for `project-views/views`."
  [config & [{:keys [cell kept show home] :or {cell atom}}]]
  (let [srcs (when-not (false? (:memory? config)) (sources config {:home home}))]
    (when (seq srcs)
      (let [state (cell {})
            unfolded ((or kept (fn [_ x] (cell x))) :memory/unfolded #{})
            running (atom nil)
            refresh! (fn []
                       (some-> @running (apply []))
                       (reset! running
                               (fx/run-detached
                                (m/sp (doseq [{:keys [id read]} srcs]
                                        (let [r (try (m/? (read))
                                                     (catch Throwable e
                                                       (if (fx/cancelled? e) (throw e) {:error (or (ex-message e) (str e))})))]
                                          (swap! state assoc id r))))
                                :memories)))]
        {:refresh! refresh!
         :state state
         :view {:id "memory"
                :title (if (= 1 (count srcs)) (str "Memory · " (:label (first srcs))) "Memory")
                :items (fn [] (rows srcs @state @unfolded))
                :act (fn [{:keys [group memory]}]
                       (cond group (swap! unfolded #(if (contains? % group) (disj % group) (conj % group)))
                             (and memory show)
                             (show (str (if (= :claude (:source memory)) "Claude Code memory — " "memory — ") (:key memory))
                                   (str (:content memory)
                                        (when (:path memory) (str "\n\n" (:path memory)))))))
                :put (fn [{:keys [memory]}] (when memory (or (:path memory) (:key memory))))
                :on-show refresh!}}))))
