(ns brainyard.literate
  "Literate programming in org (RFC-067 phase 2, byj-gly.460): in a literate
  project the org file is the source and the files it tangles to are output,
  so a change made to the output is lost at the next tangle. This is the
  guard that says so — the `tangled-file-guard` built-in hook — and how it
  knows a tangled file: org-babel's link comments (`:comments link`, or
  `both`), a comment line holding `[[file:SOURCE.org::…][…]]` at a block's
  start and one ending `… ends here` after it. A file tangled without them is
  not recognised, and no tool but write-file and update-file is held: bash
  writes what it writes. Mechanism only: which tools it holds is the hook
  spec's `:opts`, whether it is on `:literate-guard?`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.permission :as permission]))

(def ^:private max-read
  "How much of a file is read to look for link comments."
  524288)

(def ^:private link-re
  #"\[\[file:([^\]]+\.org)(::[^\]]*)?\]\[[^\]]*\]\]")

(defn- comment-line? [line]
  (re-find #"^\s*[^\sA-Za-z0-9\"'(\[{]" line))

(defn tangled-from
  "The org file `path` was tangled from, by its link comments — the first
  one's file, absolute, when it exists — or nil."
  [path]
  (try
    (let [f (io/file (str path))]
      (when (and (.isFile f) (not (str/ends-with? (.getName f) ".org")))
        (let [text (with-open [r (io/reader f)]
                     (let [buf (char-array max-read)
                           n (.read r buf 0 max-read)]
                       (if (pos? n) (String. buf 0 n) "")))
              lines (str/split-lines text)
              src (some (fn [l] (when (comment-line? l) (some-> (re-find link-re l) second))) lines)]
          (when (and src (some #(and (comment-line? %) (str/includes? % " ends here")) lines))
            (let [org (io/file (if (.isAbsolute (io/file src)) src (io/file (.getParentFile (.getAbsoluteFile f)) src)))]
              (when (.isFile org) (.getCanonicalPath org)))))))
    (catch Throwable _ nil)))

(defn tangled-file-guard
  "A built-in hook (`resources/brainyard/hooks/tangled-file-guard.edn`):
  refuse a call of one of `tools` whose `:path` was tangled from an org
  file, saying which to edit instead. Off with `:literate-guard? false`."
  [{:keys [tool-name args]} ctx {:keys [tools]}]
  (when (and (contains? (set tools) tool-name)
             (not (false? (get-in ctx [:config :literate-guard?]))))
    (when-let [p (some-> (get args :path) str not-empty)]
      (let [wd (get-in ctx [:config :working-dir] ".")
            f (if (.isAbsolute (io/file p)) p (str (io/file (str wd) p)))]
        (when-let [org (tangled-from f)]
          (permission/deny-replace
           {:by "tangled-file-guard" :display p
            :reason (str "it was tangled from " org ", which is its source: a change here is lost at the next tangle")
            :reason-prefix "Write refused"
            :error-prefix "Tool call refused (tangled file)"
            :hint (str "Edit the src block in " org " — emacs$org-outline says which block tangles to it — then tangle it with"
                       " emacs$org-tangle. :literate-guard? false lets such a file be written directly.")}))))))
