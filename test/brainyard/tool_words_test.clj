;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tool-words-test
  "A built-in tool's description is a resource (byj-2e8.5), and so are its
  arguments' and result's (byj-2e8.9): the source is read as data, every tool
  map in it is found, and its `:description` and every `:desc` are checked to
  be a `tool-words/description` call naming words that exist."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.tool-words :as tool-words]))

(defn- forms
  "Every top-level form of every namespace under src/brainyard, as data."
  []
  (for [f (sort-by str (file-seq (io/file "src/brainyard")))
        :when (str/ends-with? (.getName ^java.io.File f) ".clj")
        form (let [r (java.io.PushbackReader. (io/reader f))]
               (loop [out []]
                 (let [x (read {:eof ::eof :read-cond :allow} r)]
                   (if (= ::eof x) out (recur (conj out x))))))]
    [(.getName ^java.io.File f) form]))

(defn- nodes [form]
  (tree-seq coll? #(if (map? %) (mapcat identity %) (seq %)) form))

(defn- tool-name?
  "A tool map's `:name`: an id with a family (`x$y`), or one `tool-id` builds."
  [n]
  (or (and (string? n) (str/includes? n "$"))
      (and (seq? n) (= 'tool-id (first n)))))

(defn- described-call? [v]
  (and (seq? v) (= 'tool-words/description (first v))))

(def ^:private other-resources
  "Tools whose description is a prompt resource of its own, read whole."
  {"meta-agent$create" "prompts/meta-agent-create.md"
   "hook$create" "prompts/hook-create.md"
   "tool$create" "prompts/tool-lisp.md, with the Tool Lisp reference"
   "tool$run" "prompts/tool-run.md, with the Tool Lisp reference"})

(deftest every-tool-description-is-a-resource
  (let [tools (for [[file form] (forms)
                    node (nodes form)
                    :when (and (map? node) (tool-name? (:name node)) (contains? node :description))]
                [file (:name node) (:description node)])
        in-src (remove (fn [[_ n d]] (or (described-call? d) (contains? other-resources n))) tools)]
    (is (< 150 (count tools)) "the source was read and its tools found")
    (is (empty? in-src)
        (str "these tools keep their description in src; it goes in resources/brainyard/tools/descriptions/ "
             "and is read with tool-words/description: " (pr-str (map (juxt first second) in-src))))))

(deftest no-desc-in-src-is-a-string
  ;; an argument's or a result field's :desc is read by the model through
  ;; get-tool-info, so a resource like the tool's own (byj-2e8.9); a :desc
  ;; built from a value, as an MCP server's, is not words of src's
  (let [literal? (fn [v] (or (string? v) (and (seq? v) (= 'str (first v)) (some string? (rest v)))))
        in-src (for [[file form] (forms)
                     :when (not= "schema.clj" file)
                     node (nodes form)
                     :when (and (map? node) (literal? (:desc node)))]
                 [file (:desc node)])]
    (is (empty? in-src)
        (str ":desc written in src; it goes in resources/brainyard/tools/descriptions/ as \"<tool> :arg\" "
             "and is read with tool-words/description: " (pr-str in-src)))))

(deftest every-description-is-asked-for-and-every-one-asked-for-is-there
  ;; an id is the call's first argument, or a string in it: `(if read-only?
  ;; "…read-only" "…")` asks for both
  (let [asked (set (for [[_ form] (forms)
                         node (nodes form)
                         :when (described-call? node)
                         id (nodes (second node))
                         :when (string? id)]
                     id))
        there (set (keys (tool-words/descriptions)))]
    (is (< 150 (count asked)))
    (is (empty? (remove there asked)) "asked for, and not in resources/brainyard/tools/descriptions/")
    (is (empty? (remove asked there)) "in resources/brainyard/tools/descriptions/, and no tool asks for it")))

(deftest every-descriptions-file-is-in-the-index
  ;; a built binary reads only what index.edn names
  (let [files (set (for [f (.listFiles (io/file "resources/brainyard/tools/descriptions"))
                         :let [n (.getName ^java.io.File f)]
                         :when (and (str/ends-with? n ".edn") (not= "index.edn" n))]
                     (subs n 0 (- (count n) 4))))
        index (set (read-string (slurp (io/resource "brainyard/tools/descriptions/index.edn"))))]
    (is (= files index))))

(deftest a-filled-in-value-is-sent-as-written
  (is (str/includes? (tool-words/description "session$ask" {:timeout "{timeout}"})
                     "({timeout} by default)"))
  (is (= "a {server} tool  (from the MCP server `s`)"
         (tool-words/description "mcp$<server>$<tool>" {:description "a {server} tool" :server "s"}))))
