;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.split-test
  "RFC-070 \"Standing alone\": byj names no program built on it. Such a
  program reaches byj only as a process, an MCP server or a file in the
  person's home; nothing under byj's src or resources requires, resolves or
  ships one, and byj's documents and scripts name it only by the protocol
  it speaks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def share-namespaces
  "The 34 namespaces of D4, by the name byj would require them by."
  #"brainyard\.(exam(-[a-z]+)?|eval-[a-z]+|class-mgmt|builder|app-template|task-score|reticle-rail)\b")

(defn- files [dir ext]
  (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ext))
          (file-seq (io/file dir))))

(deftest byjs-source-names-none-of-shares-namespaces
  (let [named (for [f (files "src/brainyard" ".clj")
                    :let [hits (distinct (map first (re-seq share-namespaces (slurp f))))]
                    :when (seq hits)]
                [(.getPath f) hits])]
    (is (empty? named) "a namespace of D4's named in byj: Share's, reached as a program or an MCP server")))

(deftest byjs-resources-ship-none-of-shares-agents-or-their-words
  (testing "no agent, slash command or resource directory of Share's in byj's roster"
    (doseq [[index names] {"resources/brainyard/agents/index.edn" ["exam-kit" "class-mgmt" "app" "builder"]
                           "resources/brainyard/commands/index.edn" ["exam-kit" "class-mgmt" "app"]}]
      (let [listed (set (read-string (slurp index)))]
        (doseq [n names] (is (not (listed n)) (str n " is Share's: byj-share setup installs it"))))))
  (testing "nor the resources only Share's features read"
    (doseq [d ["resources/brainyard/eval" "resources/brainyard/exam" "resources/brainyard/app" "resources/brainyard/builder"]]
      (is (not (.exists (io/file d))) (str d " is Share's")))))

(deftest byjs-tree-reads-nothing-of-shares
  ;; RFC-070 phase 2 (byj-ahf.6): what Share relies on of byj is byj's
  ;; contract/, which Share copies; byj's sources, tests and dev namespaces
  ;; name no path into share/, so byj's tree stands alone
  (let [into-share #"\"(?:\.\./)*share/(?:testdata|engine|main|testinfra|contract|internal|machine|portal|cmd|deploy|go\.)"
        hits (for [dir ["src/brainyard" "test/brainyard" "dev/brainyard"]
                   f (files dir ".clj")
                   :let [n (count (re-seq into-share (slurp f)))]
                   :when (pos? n)]
               (.getPath f))]
    (is (empty? hits) "a path into share/ in byj's tree: export what Share needs of byj in contract/ instead")))

(def byj-tree (read-string (slurp "dev/byj-tree.edn")))

(defn- byj-rfcs
  "The numbers of byj's own RFCs, from the files in docs/RFCS."
  []
  (set (keep #(second (re-find #"^RFC-(\d{3})-" (.getName ^java.io.File %)))
             (.listFiles (io/file "docs/RFCS")))))

(def another-programs
  ;; a program built on byj named by its binaries or its tree, not by the
  ;; protocol it speaks (the share sidecar, A2A, a reader of the trajectory)
  #"byj-a2a|byj-ext|byj-relay|byj-main\b|byj-onebox|byj-portal|byj-exam\b|Brainyard Share|\bshare/(?:engine|main|docs|testdata|testinfra|contract|internal|machine|portal|cmd|deploy|web|byj-ext)\b")

(deftest byjs-documents-and-scripts-name-no-program-built-on-it
  ;; RFC-070 phase 2 (byj-ahf.8): byj's tree is dev/byj-tree.edn's paths; its
  ;; documents and scripts name another program only by the protocol it
  ;; speaks, never by its RFCs, binaries or tree, but where :until names the
  ;; bead that ends the exception
  (let [{:keys [paths until]} byj-tree
        excused (set (mapcat val until))]
    (testing "every path of byj's tree exists"
      (doseq [p paths] (is (.exists (io/file p)) (str p " is listed as byj's and is not there"))))
    (testing "and its documents and scripts name no program built on it"
      (let [ours (byj-rfcs)
            scanned (for [p paths
                          :when (or (str/starts-with? p "docs/") (str/starts-with? p "dev/") (str/starts-with? p ".github/"))
                          ^java.io.File f (file-seq (io/file p))
                          :let [path (.getPath f)]
                          :when (and (.isFile f) (not (str/includes? path "node_modules"))
                                     (re-find #"\.(md|sh|clj|edn|mjs|yml)$" path)
                                     (not (excused path)))]
                      path)
            named (for [path scanned
                        :let [text (slurp path)
                              rfcs (remove ours (map second (re-seq #"RFC-(\d{3})\b" text)))
                              names (map #(if (string? %) % (first %)) (re-seq another-programs text))
                              hits (distinct (concat (map #(str "RFC-" %) rfcs) names))]
                        :when (seq hits)]
                    [path hits])]
        (is (seq scanned) "nothing scanned: byj-tree.edn lists no documents or scripts")
        (is (empty? named) (str "byj's tree names another program beyond its protocol: " (pr-str (vec named))))))
    (testing "an exception names a path of byj's tree"
      (doseq [p excused] (is (some #(or (= p %) (str/starts-with? p (str % "/"))) paths) (str p " is excused but not byj's"))))))

(deftest byjs-code-names-no-program-built-on-it
  ;; byj-ahf.26: byj's source, tests and resources name another program only
  ;; by the protocol it speaks (the share sidecar, A2A, a reader of the
  ;; trajectory), never by its RFCs, binaries or tree — but the paths
  ;; dev/byj-tree.edn's :until excuses until a bead closes, and its :named
  ;; for good, each with why
  (let [{:keys [until named]} byj-tree
        excused (into (set (mapcat val until)) (keys named))
        ours (byj-rfcs)
        named-here (for [dir ["src" "test" "resources"]
                         ^java.io.File f (file-seq (io/file dir))
                         :let [path (.getPath f)]
                         :when (and (.isFile f) (re-find #"\.(clj|edn|md|el|json)$" path)
                                    (not (str/starts-with? path "test/fixtures/"))
                                    (not (excused path)))
                         :let [text (slurp f)
                               hits (distinct (concat (map #(str "RFC-" %) (remove ours (map second (re-seq #"RFC-(\d{3})\b" text))))
                                                      (map #(if (string? %) % (first %)) (re-seq another-programs text))))]
                         :when (seq hits)]
                     [path hits])]
    (is (empty? named-here) (str "byj's code names another program beyond its protocol: " (pr-str (vec named-here))))
    (testing "a path named for good says why"
      (doseq [[p why] named] (is (.isFile (io/file p)) p) (is (not (str/blank? why)) p)))))

