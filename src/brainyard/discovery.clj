;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.discovery
  "Tool discovery — brainyard's bootstrap tools `list-tools`, `get-tool-info`
  and `search` (agent/common/tools.clj), over the live registry in ctx
  (brainyard.toolbox), so a tool created during a turn is discoverable at once
  though it is listed in the prompt only from the next turn (RFC-007)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.config :as config]
            [brainyard.schema :as schema]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]))

(defn- non-blank? [s] (and (string? s) (not (str/blank? s))))

(defn- first-line [s] (some-> s str str/split-lines first str/trim))

(defn tool-type
  "brainyard's :type: `tool$*` commands are \"command\", a loaded SKILL.md is
  \"skill\" (RFC-017), a dispatchable agent is \"agent\" (RFC-018), primitives
  and user tools are \"tool\"."
  [t]
  (case (:kind t)
    :command "command"
    :skill "skill"
    :agent "agent"
    "tool"))

(defn tool-family
  "The id segment before the first `$` (`user$tool$x` → \"user\"), or
  \"_ungrouped\" — brainyard's `tool-family`. An MCP tool's family is its
  server (`mcp$linear$get_issue` → \"mcp$linear\"): one server can offer
  dozens of tools, and \"mcp\" alone would lump every server's into one."
  [id]
  (if-let [i (str/index-of id "$")]
    (let [head (subs id 0 i)]
      (if-let [j (and (= "mcp" head) (str/index-of id "$" (inc i)))]
        (subs id 0 j)
        head))
    "_ungrouped"))

(def ^:private listed-per-family
  "How many ids of a family the summary names. A family this size or smaller
  is named in full; a larger one by its first few, and a count."
  6)

(defn- visible-tools
  "Registry tools sorted by id, filtered by `type` and a case-insensitive regex
  `pattern` over id and description — brainyard's `collapsed-visible-tools`."
  [ctx {:keys [pattern type]}]
  (let [ts (sort-by :name (vals @(:tools ctx)))
        ts (if (non-blank? type) (filter #(= type (tool-type %)) ts) ts)]
    (if (non-blank? pattern)
      (let [re (re-pattern (str "(?i)" pattern))]
        (filter #(or (re-find re (:name %)) (re-find re (str (:description %)))) ts))
      ts)))

(def ^:private whole-families
  "The families the prompt lists a line per tool: the primitives, whose result
  shapes a model must see before it composes them (RFC-010), and a person's
  own tools. Every other family is one line."
  #{"_ungrouped" "user"})

(def ^:private names-per-family
  "How many of a family's tool names its prompt line carries."
  12)

(def ^:private family-blurbs
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/tool-families.edn")))))

(defn family-blurb
  "What a family is for, from `prompts/tool-families.edn`; an MCP server's
  family is named as that server."
  [family]
  (or (get @family-blurbs family)
      (when (str/starts-with? family "mcp$") (str "tools of the MCP server " (subs family 4)))))

(defn format-tools-by-family
  "The prompt's tool listing, by family (byj-gly.188): the primitives and the
  user's tools a line each, as `tools/format-tools-compact` writes them, then
  every other family on ONE line — its prefix, how many tools, and their ids —
  with how to get the rest. A connected MCP server is sixty-odd tools
  and a line each costs thousands of tokens on every call; its names say what
  it can do, and `get-tool-info` is one call away for the one about to be used.
  `opts` go to `tools/format-tools-compact`."
  [tools & {:as opts}]
  (let [by (group-by #(tool-family (:name %)) tools)
        whole (filter #(whole-families (tool-family (:name %))) tools)
        fams (sort (remove whole-families (keys by)))]
    (str (tools/format-tools-compact whole opts)
         (when (seq fams)
           (str "\n\nThe other families, one line each — their names say what they do. "
                "`get-tool-info {\"tool-id\": \"<id>\"}` gives one tool's arguments; "
                "`list-tools {\"family\": \"<family>\"}` all of a family's descriptions.\n"
                (str/join "\n"
                          (for [f fams
                                :let [ids (map :name (get by f))]]
                            ;; the ids in full: a model calls what it reads, and
                            ;; `list` under a `task$` heading invites a call to `list`
                            (str "- `" f "$…` (" (count ids) ")"
                                 (when-let [what (family-blurb f)] (str " — " what))
                                 ": "
                                 (str/join ", " (map #(str "`" % "`") (take names-per-family ids)))
                                 (when (> (count ids) names-per-family)
                                   (str ", … and " (- (count ids) names-per-family) " more"))))))))))

(defn- summary [t] {:id (:name t) :type (tool-type t) :description (:description t)})

(defn- family-summary
  "What `list-tools` says with nothing to narrow it: each family, how many
  tools it has and a few of their ids, and how to see the rest. brainyard's
  grouped index names every tool with its description — 213 of them, a few
  thousand tokens, once MCP servers are connected — for a question that is
  usually about one family (byj-gly.187)."
  [ts]
  (let [by (group-by #(tool-family (:name %)) ts)
        fams (sort-by (fn [[f _]] [(not= "_ungrouped" f) f]) by)]
    {:total (count ts)
     :families (vec (for [[fam fts] fams
                          :let [ids (mapv :name fts)
                                shown (take listed-per-family ids)]]
                      (cond-> {:family fam :count (count ids) :tools (vec shown)}
                        (> (count ids) listed-per-family) (assoc :more (- (count ids) listed-per-family)))))
     :next (str "This is a summary. For a family's tools with their descriptions, call list-tools with"
                " :family (e.g. {:family \"config\"}); to find a tool by what it does, :pattern — a"
                " case-insensitive regex over ids and descriptions (e.g. {:pattern \"issue\"}). Then"
                " get-tool-info {:tool-id \"…\"} for the parameters of the one you will call.")}))

(defn- room
  "What a listing may take of one result: under `:max-output-chars`, so it is
  never cut and spilled."
  [ctx]
  (int (* 0.85 (get-in ctx [:config :max-output-chars] 16000))))

(def ^:private detail-note
  "with their parameters they do not fit in one result — ask for :detail with a :family or a :pattern that names fewer")

(defn- ids-by-family
  "Every tool's id by family, and `why` the listing is no more than that."
  [ts why]
  {:total (count ts)
   :tools (into (sorted-map) (update-vals (group-by #(tool-family (:name %)) ts) #(mapv :name %)))
   :note (str "Every tool's id, by family — " why "."
              " For a family's descriptions call list-tools {:family \"…\"}; to find a tool by what"
              " it does, {:pattern \"…\"}; get-tool-info {:tool-id \"…\"} for one tool's parameters.")})

(defn- fit-listing
  "The flat listing within `room` characters: descriptions cut to a line of
  100, then of 50, and when even that does not fit, the ids by family with no
  descriptions and a note on how to get them. 192 tools listed flat were 31,000
  characters, past the cap, spilled — and the model paged the spill back in
  four calls to answer \"list all tools\" (byj-gly.191)."
  [ts room]
  (let [with (fn [n] (mapv (fn [t] (update (summary t) :description
                                           #(let [l (first-line %)]
                                              (if (and n (> (count (str l)) n)) (str (subs l 0 (dec n)) "…") l))))
                           ts))
        fits? #(<= (count (pr-str %)) room)]
    (or (first (filter fits? [(with (when (> (count ts) 20) 100)) (with 50)]))
        (ids-by-family ts "with descriptions they do not fit in one result"))))

(defn list-tools [{:keys [pattern grouped detail family] :as args} ctx]
  (let [ts (visible-tools ctx args)
        ts (if (non-blank? family) (filter #(= family (tool-family (:name %))) ts) ts)
        flat? (or (non-blank? pattern) (non-blank? family) (false? grouped) (true? detail))]
    (cond
      (and (non-blank? family) (empty? ts))
      {:error-message (str "no tool family " family " — list-tools with no arguments names the families")}
      ;; with its two schemas every tool is ~900 characters: 191 of them were
      ;; 171,000, spilled and read back a page at a time (byj-gly.192)
      (and flat? detail) (let [v (mapv #(cond-> (merge (summary %) {:input-schema (schema/tool-schema %)})
                                            (:output-schema %) (assoc :output-schema (:output-schema %)))
                                         ts)]
                           (if (<= (count (pr-str v)) (room ctx)) v (ids-by-family ts detail-note)))
      ;; many hits, shorter lines: a broad pattern over sixty skills was a
      ;; single result past :max-output-chars (byj-gly.189)
      flat? (fit-listing ts (room ctx))
      :else (family-summary ts))))

(defn get-tool-info [{:keys [tool-id]} ctx]
  (let [id (str/trim (str tool-id))
        t (get @(:tools ctx) id)]
    (cond
      (str/blank? id) {:error-message "tool-id is required"}
      (nil? t) {:error-message (str "tool(ID:" id ") is not defined in system")}
      :else (cond-> (assoc (summary t)
                           :input-schema (schema/tool-schema t)
                           :output-schema (:output-schema t)
                           :effects (vec (sort (toolbox/effects (:tools ctx) id))))
              (= :composite (:kind t)) (assoc :calls (vec (sort (:calls t))))))))

(def ^:private max-scanned-files 500)
(def ^:private max-file-results 50)

(defn- project-files
  "The first `max-scanned-files` files under dir, as paths relative to it, in
  sorted walk order, skipping .git and .brainyard (divergences
  `search-walks-all-files`, `search-skips-agent-state`)."
  [dir]
  (let [root (io/file dir)
        prefix (str (.getPath root) "/")
        ;; .git and the agent's own .brainyard state are not project files
        walk (fn walk [f]
               (if (.isDirectory f)
                 (when-not (contains? #{".git" ".brainyard"} (.getName f))
                   (mapcat walk (sort-by #(.getName %) (.listFiles f))))
                 [f]))]
    (->> (mapcat walk (sort-by #(.getName %) (.listFiles root)))
         (take max-scanned-files)
         (map #(let [p (.getPath %)] (if (str/starts-with? p prefix) (subs p (count prefix)) p)))
         vec)))

(defn search
  "brainyard's `search`: whitespace-split query, tokens under 3 chars dropped,
  every token a case-insensitive substring (AND). Sections: project file paths
  (first 500 files scanned, 50 kept) and registered tools (id or description).
  brainyard's memory section is NOT here: its store returns a countable list of
  hits, so an empty one is simply no section, while an external store (RFC-020)
  returns text and cannot be told apart from a polite miss — folding it in
  pasted a `No memories matching …` line into every search. `memory$recall` is
  the way in instead (divergence `search-has-no-memory-section`)."
  [{:keys [query]} ctx]
  (let [config-files (fn [] (->> [(config/config-file (get-in ctx [:config :working-dir] "."))
                                  (some-> (System/getenv "HOME") config/config-file)]
                                 (filter #(some-> % .isFile))
                                 (map #(.getPath %))))
        tokens (->> (str/split (str/trim (str query)) #"\s+")
                    (filter #(>= (count %) 3))
                    (map str/lower-case))
        matches? (fn [s] (let [s (str/lower-case (str s))] (every? #(str/includes? s %) tokens)))]
    (if (empty? tokens)
      {}
      (let [files (->> (project-files (get-in ctx [:config :working-dir] "."))
                       (filter matches?) (take max-file-results) vec)
            ts (->> (visible-tools ctx {})
                    (filter #(or (matches? (:name %)) (matches? (:description %))))
                    (mapv summary))]
        (cond-> {}
          (seq files) (assoc :project-files files)
          (seq (filter matches? (config-files))) (assoc :config-files (vec (filter matches? (config-files))))
          (seq ts) (assoc :tools ts))))))

(def impls
  {:brainyard.discovery/list-tools list-tools
   :brainyard.discovery/get-tool-info get-tool-info
   :brainyard.discovery/search search})

(def discovery-tools
  "brainyard's `bootstrap-tools`, as primitive specs."
  (mapv #(tools/load-spec % impls) ["list-tools" "get-tool-info" "search"]))
