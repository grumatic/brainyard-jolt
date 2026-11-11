(ns brainyard.memory-rail-test
  "The left rail's Memory section (byj-gly.421, byj-gly.422): each source's
  memories, read off the loop, Enter showing one whole and @ its key or path —
  over a fake store, a script, and a Claude Code home of the test's own, never
  the developer's."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [brainyard.memory-rail :as memory-rail]
            [jolt.process :as p]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-memory-rail-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- lister [dir body]
  (let [f (io/file dir "list.sh")]
    (spit f (str "#!/bin/bash\n" body "\n"))
    (p/sh "chmod" "+x" (.getPath f))
    (.getAbsolutePath f)))

(defn- drawn [items] (mapv :label items))

(defn- read!
  "Refresh and wait until each of the `sources` (1 unless said) has answered:
  the state holds one entry a source, by its id. It once waited for a
  `:memories` key the state no longer has, so every read sat out its whole
  bound (byj-gly.442); the bound is 30 s, since the wait ends when the read
  does and six suites' load once outran 3 s (byj-gly.431)."
  [{:keys [refresh! state]} & [sources]]
  (reset! state {})
  (refresh!)
  (loop [n 0]
    (when (and (< (count @state) (or sources 1)) (< n 1500))
      (Thread/sleep 20)
      (recur (inc n)))))

(deftest the-section-lists-shows-and-mentions
  (let [dir (fresh-dir "list")
        config {:project-dir dir :working-dir dir
                :memory-list-cmd [(lister dir "echo '{\"retry-budget\": \"Retries stop at 3.\\nThen it asks.\", \"a-tag\": \"short\", \"schema_version\": 1}'")]}
        shown (atom nil)
        mv (memory-rail/views config {:show (fn [title text] (reset! shown [title text])) :home (fresh-dir "home")})
        view (:view mv)]
    (is (= "Memory · list.sh" (:title view)) "the store's program by name, not its path")
    (is (= ["  reading…"] (drawn ((:items view)))) "before the first read")
    (read! mv)
    (is (= ["  a-tag" "  retry-budget"] (drawn ((:items view)))) "one row a memory, by key, bd's schema_version left out")
    (let [row (second ((:items view)))]
      ((:act view) row)
      (is (= ["memory — retry-budget" "Retries stop at 3.\nThen it asks."] @shown) "Enter shows the whole of it")
      (is (= "retry-budget" ((:put view) row)) "@ mentions its key"))))

(deftest the-section-says-what-it-could-not-read
  (let [dir (fresh-dir "empty")]
    (testing "a store with nothing in it"
      (let [mv (memory-rail/views {:project-dir dir :memory-list-cmd [(lister dir "echo '{}'")]} {:home (fresh-dir "home")})]
        (read! mv)
        (is (= ["  no memories yet"] (drawn ((:items (:view mv))))))))
    (testing "a store that fails says why, on one line"
      (let [mv (memory-rail/views {:project-dir dir :memory-list-cmd [(lister dir "echo 'Error: no beads database found' ; echo 'more' ; exit 1")]}
                                  {:home (fresh-dir "home")})]
        (read! mv)
        (is (= ["  could not read: Error: no beads database found"] (drawn ((:items (:view mv))))))))))

(deftest a-bd-store-with-no-beads-says-init-sets-them-up
  ;; byj-gly.458: bd's "no beads database found" was the section's one row
  (let [dir (fresh-dir "no-beads")
        mv (memory-rail/views {:project-dir dir :memory-list-cmd ["bd" "memories" "--json" "--quiet"]}
                              {:home (fresh-dir "home")})]
    (is (= "Memory · beads" (:title (:view mv))))
    (read! mv)
    (is (= {:store {:unavailable true}} @(:state mv)) "bd is not run")
    (is (= ["  beads not available" "  /init sets it up"] (drawn ((:items (:view mv))))))))

(deftest no-memory-no-section
  (let [home (fresh-dir "home") dir (fresh-dir "none")]
    (is (nil? (memory-rail/views {:project-dir dir :memory? false :memory-list-cmd ["true"]} {:home home})) ":memory? false")
    (is (nil? (memory-rail/views {:project-dir dir :memory-list-cmd []} {:home home})) "no list command and no Claude Code memory")))

;; --- Claude Code's memory (byj-gly.422) ---------------------------------------------

(defn- claude-memory! [home project-dir file text]
  (let [d (memory-rail/claude-memory-dir project-dir home)]
    (.mkdirs d)
    (spit (io/file d file) text)
    d))

(deftest a-claude-code-memory-file-is-read-as-its-front-matter-says
  (is (= {:name "gh-accounts" :description "work-acct for acme, home-acct for personal" :type "user"
          :body "Use work-acct for acme/*."}
         (memory-rail/parse-memory-file
          "---\nname: gh-accounts\ndescription: work-acct for acme, home-acct for personal\nmetadata:\n  type: user\n---\n\nUse work-acct for acme/*.\n")))
  (is (= {:name nil :description nil :type nil :body "just text"} (memory-rail/parse-memory-file "just text\n"))
      "no front matter: all body")
  (let [home (fresh-dir "home") dir (fresh-dir "proj")
        d (claude-memory! home dir "b.md" "---\nname: second\ndescription: the second\n---\nbody b\n")]
    (spit (io/file d "a-file.md") "no front matter here\n")
    (spit (io/file d "MEMORY.md") "- [second](b.md) — the second\n")
    (is (= [["a-file" nil] ["second" "the second"]] (mapv (juxt :key :description) (memory-rail/claude-memories d)))
        "a file a memory, by its name else its file name; the MEMORY.md index is not one")
    (is (= "the second\n\nbody b" (:content (second (memory-rail/claude-memories d)))))))

(deftest each-source-is-a-folded-group
  (let [home (fresh-dir "home") dir (fresh-dir "both")
        _ (claude-memory! home dir "gh.md" "---\nname: gh-accounts\ndescription: two accounts\n---\nUse work-acct.\n")
        shown (atom nil)
        mv (memory-rail/views {:project-dir dir :memory-list-cmd [(lister dir "echo '{\"retry\": \"3\", \"tag\": \"x\"}'")]}
                              {:home home :show (fn [t x] (reset! shown [t x]))})
        view (:view mv)]
    (is (= "Memory" (:title view)) "several sources: the section is Memory")
    (read! mv 2)
    (is (= [["▸ list.sh" "2"] ["▸ Claude Code" "1"]] (mapv (juxt :label :hint) ((:items view))))
        "byj's store first, then Claude Code's, each folded with its count")
    ((:act view) (second ((:items view))))
    (let [items ((:items view))
          row (nth items 2)]
      (is (= ["▸ list.sh" "▾ Claude Code" "  gh-accounts"] (drawn items)) "Enter unfolds a source")
      ((:act view) row)
      (is (= "Claude Code memory — gh-accounts" (first @shown)))
      (is (re-find #"(?s)^two accounts\n\nUse work-acct\.\n\n.*gh\.md$" (second @shown)) "its text, then where it is")
      (is (re-find #"gh\.md$" ((:put view) row)) "@ mentions its file"))))

(deftest a-test-never-reads-the-developers-claude-memory
  ;; provenance P-40: as the default store is off under the runner, so is the
  ;; person's own ~/.claude, unless a test names a home of its own
  (let [was (System/getProperty "brainyard.memory-store")]
    (try
      (System/setProperty "brainyard.memory-store" "none")
      (is (not-any? #(= :claude (:id %)) (memory-rail/sources {:project-dir (System/getProperty "user.dir")} {}))
          "this repository's own Claude Code memory is not read")
      (finally (if was (System/setProperty "brainyard.memory-store" was) (System/clearProperty "brainyard.memory-store"))))))
