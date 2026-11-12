;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.memory-test
  "Memory (RFC-020): a store is a command, it is never injected, and a project
  without one simply has no memory tools."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.memory :as memory]
            [brainyard.tools :as tools]
            [jolt.process :as p]))

(defn- fresh-dir [name]
  (let [d (io/file "target/memory-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- script!
  "A fake store: a script that prints its arguments back, so a test can see
  exactly what the tool ran without depending on any store being installed."
  [dir name body]
  (let [f (io/file dir name)]
    (spit f (str "#!/bin/bash\n" body "\n"))
    (p/sh "chmod" "+x" (.getPath f))
    (.getAbsolutePath f)))

(defn- store-config
  "Config whose three memory commands are the given scripts."
  [dir & {:keys [recall read store]}]
  {:working-dir dir :project-dir dir
   :memory-recall-cmd (when recall [recall])
   :memory-read-cmd (when read [read])
   :memory-store-cmd (when store [store])})

(defn- call [ctx n args] (tools/call-tool (get @(:tools ctx) n) args ctx))

;; --- the store is a command -----------------------------------------------------------

(deftest a-store-is-a-command
  (let [dir (fresh-dir "cmd")
        recall (script! dir "recall.sh" "echo \"searched for: $1\"")
        read (script! dir "read.sh" "echo \"entry $1: the content\"")
        store (script! dir "store.sh" "echo \"stored [$*]\"")
        config (store-config dir :recall recall :read read :store store)
        ctx (agent/make-ctx {:config config})]
    (testing "the three verbs reach the command, with what the model gave them"
      (is (= {:memories "searched for: retry budget\n"}
             (call ctx "memory$recall" {:query "retry budget"})))
      (is (= {:key "auth-jwt" :memory "entry auth-jwt: the content\n"}
             (call ctx "memory$read" {:key "auth-jwt"})))
      (is (= {:stored "stored [it uses JWT --key auth-jwt]\n"}
             (call ctx "memory$remember" {:content "it uses JWT" :key "auth-jwt"})))
      (is (= {:stored "stored [no key given]\n"}
             (call ctx "memory$remember" {:content "no key given"}))))

    (testing "blank input is refused before the command runs"
      (is (= {:error "query is required"} (call ctx "memory$recall" {:query "  "})))
      (is (= {:error "key is required"} (call ctx "memory$read" {:key ""})))
      (is (= {:error "content is required"} (call ctx "memory$remember" {:content " "}))))

    (testing "a store that fails is reported, not thrown"
      (let [failing (script! dir "fail.sh" "echo 'the store is unhappy' >&2; exit 3")
            ctx (agent/make-ctx {:config (store-config dir :recall failing)})]
        (is (str/includes? (:error (call ctx "memory$recall" {:query "x"})) "the store is unhappy"))))

    (testing "and what it prints is capped"
      (let [big (script! dir "big.sh" "head -c 20000 /dev/zero | tr '\\0' 'x'")
            ctx (agent/make-ctx {:config (assoc (store-config dir :recall big) :memory-max-chars 200)})
            r (call ctx "memory$recall" {:query "x"})]
        (is (< (count (:memories r)) 300))
        (is (str/includes? (:memories r) "truncated"))))))

(defn- memory-tools [ctx]
  (set (filter #(str/starts-with? % "memory$") (keys @(:tools ctx)))))

(deftest a-project-without-a-store-has-no-memory-tools
  (let [dir (fresh-dir "absent")]
    (testing "a command that is not on PATH leaves the STORE tools unregistered"
      (let [ctx (agent/make-ctx {:config (assoc (store-config dir)
                                                :memory-recall-cmd ["byj-no-such-store"])})]
        (is (false? (memory/available? (:config ctx))))
        (is (= #{"memory$history"} (memory-tools ctx))
            "history reads this project's own snapshots, so it needs no store at all")))

    (testing ":memory? false removes the layer, history included"
      (let [recall (script! dir "r.sh" "echo hi")
            ctx (agent/make-ctx {:config (assoc (store-config dir :recall recall) :memory? false)})]
        (is (empty? (memory-tools ctx)))))

    (testing "and one verb can be switched off on its own"
      (let [recall (script! dir "r2.sh" "echo hi")
            ctx (agent/make-ctx {:config (assoc (store-config dir :recall recall)
                                                :memory-store-cmd [])})]
        (is (some? (get @(:tools ctx) "memory$recall")))
        (is (nil? (get @(:tools ctx) "memory$remember")))))))

;; --- what it costs the prompt ---------------------------------------------------------

(deftest memory-is-never-injected
  (let [dir (fresh-dir "prompt")
        recall (script! dir "recall.sh" "echo 'SECRET-MEMORY-CONTENT'")
        with (agent/make-ctx {:config (store-config dir :recall recall)})
        without (agent/make-ctx {:config (assoc (store-config dir :recall recall) :memory? false)})
        zone #(:session-context (:zones %))]
    (testing "no memory reaches the prompt — brainyard renders a ## Recalled Memory block every turn"
      (is (not (str/includes? (zone with) "SECRET-MEMORY-CONTENT")))
      (is (not (str/includes? (zone with) "Recalled Memory"))))
    (testing "the whole layer costs a line per verb, and only while a store exists"
      (is (str/includes? (zone with) "`memory$recall`"))
      (is (< (- (count (zone with)) (count (zone without))) 900)
          "three tools at one line each, not a memory dump"))))

;; --- what search does NOT do --------------------------------------------------------

(deftest search-has-no-memory-section
  (let [dir (fresh-dir "search")
        _ (spit (io/file dir "retry-notes.txt") "x")
        recall (script! dir "recall.sh" "echo \"memory hit for: $1\"")
        ctx (agent/make-ctx {:config (store-config dir :recall recall)})
        r (call ctx "search" {:query "retry"})]
    (testing "an external store returns TEXT, and text cannot be told apart from a polite miss"
      (is (nil? (:memories r))
          "folding it in pasted `No memories matching x` into every search")
      (is (= ["retry-notes.txt"] (:project-files r)) "the sections that can count their hits are unchanged"))
    (testing "memory$recall is the way in, and says what it found either way"
      (is (= {:memories "memory hit for: retry\n"} (call ctx "memory$recall" {:query "retry"}))))))

;; --- what this project has already been asked -----------------------------------------

(deftest earlier-sessions-are-the-record-that-was-already-being-kept
  (let [dir (fresh-dir "history")
        write! (fn [id turns at]
                 (let [f (io/file dir ".brainyard" "sessions" id "session.edn")]
                   (.mkdirs (.getParentFile f))
                   (spit f (pr-str {:schema-version 1 :id id :created-at at :updated-at at
                                    :turn-count (count turns) :previous-turns turns}))))
        _ (write! "older" [{:question "how does the retry budget work?"
                            :answer "three attempts, then the turn aborts"}] 1000)
        _ (write! "newer" [{:question "where is the retry budget enforced?"
                            :answer "repair-action counts consecutive failures"}
                           {:question "unrelated" :answer "nothing to do with it"}] 2000)
        _ (write! "current" [{:question "retry budget again" :answer "this session"}] 3000)
        config {:working-dir dir :project-dir dir :session-id "current"}
        ctx (agent/make-ctx {:config config})]

    (testing "a turn from an earlier session is found by every token of the query"
      (let [r (call ctx "memory$history" {:query "retry budget"})]
        (is (= 2 (:count r)))
        (is (= ["where is the retry budget enforced?" "how does the retry budget work?"]
               (map :question (:turns r)))
            "newest first")
        (is (= "repair-action counts consecutive failures" (:answer (first (:turns r)))))
        (is (= "newer" (:session (first (:turns r)))))))

    (testing "the session you are in is excluded — its turns are already in front of you"
      (is (not-any? #(= "current" (:session %))
                    (:turns (call ctx "memory$history" {:query "retry budget"})))))

    (testing "every token must appear, and a query of only short words finds nothing"
      (is (= 0 (:count (call ctx "memory$history" {:query "retry elephant"}))))
      (is (= 0 (:count (call ctx "memory$history" {:query "a b"})))))

    (testing "and it is bounded"
      (is (= 1 (:count (call ctx "memory$history" {:query "retry budget" :limit 1}))))
      (is (= {:error "query is required"} (call ctx "memory$history" {:query "  "}))))

    (testing "nothing was captured to make this work: the snapshots are RFC-016's"
      (is (= #{"older" "newer" "current"}
             (set (map #(.getName %) (.listFiles (io/file dir ".brainyard" "sessions")))))
          "no extra store, no second copy of a turn"))))

(deftest a-store-runs-in-the-sessions-project
  (let [dir (fresh-dir "where")
        pwd (script! dir "pwd.sh" "echo \"ran in $(pwd -P)\"")
        ctx (agent/make-ctx {:config (store-config dir :recall pwd :store pwd)})]
    (testing "the store's command runs in the session's project, wherever the process started (byj-gly.255)"
      (is (= {:memories (str "ran in " (.getCanonicalPath (io/file dir)) "\n")}
             (call ctx "memory$recall" {:query "x"}))))))


;; --- what a person curates: /memory (byj-gly.327) ------------------------------------

(deftest a-person-lists-rewrites-and-deletes-through-the-store
  (testing "the listing is JSON: an object of key to content, or an array of {key, value}"
    (is (= [{:key "a/b" :content "two"} {:key "z" :content "one"}]
           (memory/parse-listing "{\"z\": \"one\", \"a/b\": \"two\"}"))
        "by key; a key with a / is kept whole")
    (is (= [{:key "k" :content "v"} {:key "m" :content "c"}]
           (memory/parse-listing "[{\"key\": \"k\", \"value\": \"v\"}, {\"key\": \"m\", \"content\": \"c\"}]")))
    (is (= [] (memory/parse-listing "{}")))
    (is (= [{:key "k" :content "v"}] (memory/parse-listing "{\"k\": \"v\", \"schema_version\": 1}"))
        "bd's schema_version is not a memory")
    (is (nil? (memory/parse-listing "Memories (3):"))))
  (testing "a search is every word, in the key or the content, any case"
    (let [ms [{:key "test-cadence" :content "Run the RELEVANT namespaces"} {:key "jolt-keyword-at" :content "reader splits"}]]
      (is (= ["test-cadence"] (map :key (memory/matching ms "relevant test"))))
      (is (= 2 (count (memory/matching ms ""))))))
  (let [dir (fresh-dir "curate")
        log (.getAbsolutePath (io/file dir "log"))
        lister (script! dir "list.sh" "echo '{\"retry\": \"budget is 3\"}'")
        forget (script! dir "forget.sh" (str "echo \"forget [$*]\" >> " log "; echo forgot"))
        store (script! dir "store.sh" (str "echo \"store [$*]\" >> " log "; echo stored"))
        config (assoc (store-config dir :store store) :memory-list-cmd [lister] :memory-forget-cmd [forget])]
    (is (= {:memories [{:key "retry" :content "budget is 3"}]} (memory/list-memories config)))
    (is (= "forgot\n" (:out (memory/forget! config "retry"))))
    (is (= "stored\n" (:out (memory/rewrite! config "retry" "  budget is 4\n"))))
    (is (= ["forget [retry]" "store [budget is 4 --key retry]"] (str/split-lines (slurp log)))
        "the key to forget; the content trimmed, under the same key")
    (testing "a store that cannot list or delete says so"
      (is (str/includes? (:error (memory/list-memories (assoc config :memory-list-cmd []))) "cannot be listed"))
      (is (str/includes? (:error (memory/forget! (assoc config :memory-forget-cmd []) "x")) "cannot delete"))
      (is (str/includes? (:error (memory/list-memories (assoc config :memory-list-cmd [(script! dir "text.sh" "echo plain")])))
                         "not JSON")))))

;; --- which memories a question is about (byj-gly.335) ---------------------------------

(deftest a-question-finds-the-memories-it-shares-rare-words-with
  (let [ms [{:key "live-tui-checks-use-tmux" :content "Drive the built TUI with tmux, not pyte."}
            {:key "test-cadence" :content "Run the relevant test namespaces, not the whole test suite."}
            {:key "clj-edit-balanced" :content "Keep every edit to a clj file balanced; the TUI's file was emptied once."}]]
    (testing "words: lower-case, three letters or more, no stopwords, stemmed"
      (is (= ["retry" "budget" "test"] (memory/keywords "What is the retries budget for the tests?")))
      (is (= [] (memory/keywords "can you do it?"))))
    (testing "a rarer shared word counts for more, and the best comes first"
      (is (= ["live-tui-checks-use-tmux" "clj-edit-balanced"]
             (map :key (memory/rank ms "check the TUI with tmux" {}))))
      (is (= ["tui" "tmux"] (:matched (first (memory/rank ms "check the TUI with tmux" {}))))))
    (testing "the thresholds: words in common, a score, a share of the best"
      (is (= ["live-tui-checks-use-tmux"] (map :key (memory/rank ms "check the TUI with tmux" {:min-terms 2}))))
      (is (= ["live-tui-checks-use-tmux"] (map :key (memory/rank ms "check the TUI with tmux" {:min-share 0.6}))))
      (is (empty? (memory/rank ms "tmux" {:min-score 3})) "one word in common is not enough when it scores low")
      (is (= 1 (count (memory/rank ms "check the TUI with tmux" {:max 1})))))
    (testing "nothing to match on matches nothing; excluded keys are left out"
      (is (empty? (memory/rank ms "commit this" {})))
      (is (empty? (memory/rank ms "" {})))
      (is (= ["clj-edit-balanced"] (map :key (memory/rank ms "check the TUI with tmux" {:exclude ["live-tui-checks-use-tmux"]})))))))

(deftest the-suite-never-reaches-the-developers-store
  ;; the test runner sets this for every run (byj-gly.382); set here too, so the
  ;; test says the same standalone
  (let [was (System/getProperty "brainyard.memory-store")]
    (try
      (System/setProperty "brainyard.memory-store" "none")
      (is (nil? (memory/cmd {} :memory-list-cmd)) "the shipped default is off")
      (is (nil? (memory/cmd {:memory-list-cmd ["bd" "memories" "--json"]} :memory-list-cmd))
          "and off as a loaded config carries it, from the schema's default")
      (is (= ["./my-store" "list"] (memory/cmd {:memory-list-cmd ["./my-store" "list"]} :memory-list-cmd))
          "a store a test names is its own")
      (is (false? (memory/available? {})) "and none is looked for on PATH")
      (finally
        (if was (System/setProperty "brainyard.memory-store" was) (System/clearProperty "brainyard.memory-store"))))))
