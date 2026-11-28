;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tool-words
  "What the model reads of a built-in tool: its description, by tool id
  (byj-2e8.5). The words are `resources/brainyard/tools/descriptions/`'s, a
  file per namespace that defines tools, named in that folder's `index.edn`;
  the tool's body, schema and effects stay where they are, in the namespace
  that runs it. A primitive tool's whole spec is already a resource
  (`resources/brainyard/tools/<name>.edn`, RFC-010) and is not here.

  A description with a part only known at run time — an agent's own line,
  an MCP server's name, a configured limit — has a `{name}` for it, filled in
  one pass, so a filled-in value is never read for names again."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private dir "brainyard/tools/descriptions/")

(defn- read-resource [path]
  (edn/read-string (slurp (or (io/resource path)
                              (throw (ex-info (str "resource not found: " path) {:path path}))))))

(def ^:private table
  (delay (reduce (fn [m file]
                   (let [words (read-resource (str dir file ".edn"))]
                     (when-let [twice (seq (filter #(contains? m %) (keys words)))]
                       (throw (ex-info (str "tool descriptions given twice: " (str/join ", " twice)) {:file file})))
                     (merge m words)))
                 {}
                 (read-resource (str dir "index.edn")))))

(defn descriptions
  "Every description by tool id."
  []
  @table)

(defn description
  "The description of tool `id`, each `{name}` filled from `m`."
  ([id] (description id {}))
  ([id m]
   (str/replace (or (get @table id)
                    (throw (ex-info (str "no description for tool " id " in " dir) {:tool id})))
                #"\{([a-z-]+)\}"
                (fn [[whole n]] (if-let [[_ v] (find m (keyword n))] (str v) whole)))))
