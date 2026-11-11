(ns brainyard.tool-distill-test
  "Tool distillation's mechanism (byj-gly.249.2): what a tool$run body is,
  when it is worth a judge, how a draft is proved, and how a proposal is kept."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.json :as json]
            [brainyard.proposals :as proposals]
            [brainyard.session :as session]
            [brainyard.self-improve :as si]
            [brainyard.tool-distill :as td]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file "target/tool-distill-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- spit-in [dir path content]
  (let [f (io/file dir path)] (.mkdirs (.getParentFile f)) (spit f content)))

(defn- turn [question & runs]
  {:question question :answer "done" :terminated-by :answer-channel
   :trajectory-iterations
   [{:iteration 1
     :tool-results (vec (for [{:keys [body args result error?]} runs]
                          (cond-> {:tool-name "tool$run" :tool-args {:body body :args (or args {})} :tool-result result}
                            error? (assoc :tool-error? true))))}]})

(def count-docs "(fn [_] (count (:entries (list-dir {:path \"docs\"}))))")
(def count-src "(fn [_] (count (:entries (list-dir {:path \"src\"}))))")

(deftest a-body-has-a-shape
  (is (= (td/body-shape count-docs) (td/body-shape count-src)) "the same thing done to other data")
  (is (not= (td/body-shape count-docs) (td/body-shape "(fn [_] (:entries (list-dir {:path \"docs\"})))")))
  (is (nil? (td/body-shape "(fn [_] (oops")) "a body that does not read has none")
  (testing "a #(…) body has one shape however often it is read (byj-gly.426)"
    ;; the reader names %'s parameter p__N#, N counting every one it has read:
    ;; two runs of the same body were never of one shape, and no #(…) body
    ;; was ever distilled
    (let [defs (fn [path] (str "(fn [_] (count (filter #(clojure.string/starts-with? % \"def \") "
                               "(clojure.string/split-lines (:content (read-file {:path \"" path "\"}))))))"))]
      (is (= (td/body-shape (defs "greet/cli.py")) (td/body-shape (defs "tests/test_cli.py"))))
      (is (= (td/body-shape "(fn [_] (map #(+ %1 %2) [1] [2]))") (td/body-shape "(fn [_] (map #(+ %1 %2) [3] [4]))")))
      (is (not= (td/body-shape "(fn [_] (map #(+ %1 %2) [1] [2]))") (td/body-shape "(fn [_] (map #(+ %2 %1) [1] [2]))"))
          "%1 and %2 are kept apart"))))

(deftest only-a-run-that-worked-in-a-turn-that-answered-is-a-candidate
  (let [ok (turn "how many docs" {:body count-docs :result "2"} {:body count-src :result "{:error \"Invalid Tool Lisp\"}"})]
    (is (= ["2"] (map :result (td/candidates 1 ok))) "a refused body is no candidate")
    (is (= 1 (:turn-number (first (td/candidates 1 ok)))))
    (is (empty? (td/candidates 2 (assoc ok :terminated-by :max-iterations-exhausted)))
        "nor is one in a turn that did not answer")))

(deftest a-judge-is-called-when-a-body-repeats-or-composes
  (let [reg (toolbox/registry tools/builtin-tools)
        c1 (td/candidates 1 (turn "q1" {:body count-docs :result "2"}))
        c2 (td/candidates 2 (turn "q2" {:body count-src :result "3"}))]
    (is (nil? (td/auto-signal reg c1 {})) "one run of one tool is no procedure")
    (is (= :repeated-body (td/auto-signal reg (concat c1 c2) {})))
    (is (= :multi-tool-body
           (td/auto-signal reg (td/candidates 3 (turn "q3" {:body "(fn [_] (map #(read-file {:path %}) (:entries (list-dir {:path \".\"}))))" :result "()"})) {})))
    (is (str/includes? (td/candidates-text (concat c1 c2)) "=== run 2 (turn 2) — q2 ==="))))

(deftest a-draft-is-proved-before-it-is-staged
  (let [dir (fresh-dir "prove")
        reg (toolbox/registry tools/builtin-tools)
        ctx {:config {:working-dir dir :project-dir dir}}
        _ (do (spit-in dir "docs/a.md" "a") (spit-in dir "docs/b.md" "b"))
        record {:name "count-entries" :description "How many entries a directory has."
                :input-schema [:map [:dir :string]] :output-schema :int
                :body "(fn [{:keys [dir]}] (count (:entries (list-dir {:path dir}))))"}
        sources [{:result "2"}]]
    (testing "it compiles, only reads, and gives back what the run gave"
      (let [p (td/prove reg ctx record [{:run 1 :args {:dir "docs"}}] sources)]
        (is (= :verified (:status p)) (pr-str (dissoc p :tool)))
        (is (td/stage? p))))
    (testing "a draft that does not reproduce its run is not staged"
      (let [p (td/prove reg ctx record [{:run 1 :args {:dir "docs"}}] [{:result "3"}])]
        (is (= :mismatch (:status p)))
        (is (not (td/stage? p)))))
    (testing "one that could write is staged unproved, never run for the judge"
      (let [p (td/prove reg ctx (assoc record :body "(fn [{:keys [dir]}] (write-file {:path (str dir \"/x\") :content \"x\"}))")
                        [{:run 1 :args {:dir "docs"}}] sources)]
        (is (= :unproved (:status p)))
        (is (contains? (:effects p) :fs/write))
        (is (not (.exists (io/file dir "docs/x"))))
        (is (td/stage? p))))
    (testing "one that does not compile is said to"
      (is (= :invalid (:status (td/prove reg ctx (assoc record :body "(fn [_] (slurp \"x\"))") [] sources)))))))

(deftest a-verdict-becomes-a-draft
  (is (= :draft-ok (td/outcome 0.7 {:reusable true :score 0.9 :proposed-name "count-entries" :body "(fn [_] 1)"
                                    :input-schema "[:map]" :output-schema ":int" :replay-args "[]"})))
  (is (= :below-threshold (td/outcome 0.7 {:reusable true :score 0.5 :proposed-name "x" :body "(fn [_] 1)"})))
  (is (= :unreadable-draft (td/outcome 0.7 {:reusable true :score 0.9 :proposed-name "x" :body "(fn [_] 1)"
                                            :input-schema "[:map [:a"})))
  (is (= {:record {:name "x" :description "d" :body "(fn [_] 1)" :input-schema [:map]
                   :output-schema [:or [:map [:n :int]] [:map [:error-message :string]]]}
          :replay-args [{:run 1 :args {}}]}
         (td/draft {:proposed-name "x" :description "d" :body "(fn [_] 1)" :input-schema "[:map]"
                    :output-schema "[:or [:map [:n :int]] [:map [:error-message :string]]]" :replay-args "[{:run 1 :args {}}]"}))
      "a schema as EDN text is read as the form")
  (testing "prose for the output is not a draft (byj-gly.263)"
    (let [v {:reusable true :score 0.9 :proposed-name "x" :body "(fn [_] 1)" :input-schema "[:map]" :replay-args "[]"
             :output-schema "{:projects [{:project string}]} or {:error-message string}"}]
      (is (= :prose-output-schema (td/outcome 0.7 v)))
      (is (nil? (td/draft v))))
    (is (= :draft-ok (td/outcome 0.7 {:reusable true :score 0.9 :proposed-name "x" :body "(fn [_] 1)"
                                      :input-schema "[:map]" :output-schema "" :replay-args "[]"}))
        "a blank one is none")))

(deftest a-proposal-waits-at-the-gate-and-becomes-a-user-tool
  (let [dir (fresh-dir "stage")
        config {:working-dir dir :project-dir dir}
        record {:name "count-entries" :description "How many entries a directory has."
                :input-schema [:map [:dir :string]] :output-schema :int
                :body "(fn [{:keys [dir]}] (count (:entries (list-dir {:path dir}))))"}]
    (td/write-proposal! config {:record record :score 0.9 :rationale "counted twice"
                                :proof {:status :verified :runs [{:run 1 :matched? true}]}
                                :sources [{:turn-number 3 :body "…" :result "2"}]})
    (testing "it is listed with every other kind, by tool/NAME, and shown with its proof"
      (is (= ["tool/count-entries"] (map :id (proposals/list-all config))))
      (let [body (:body (proposals/read-one config "tool/count-entries"))]
        (is (str/includes? body "replayed 1 run(s)"))
        (is (str/includes? body "from: turn 3"))
        (is (str/includes? body "(count (:entries"))))
    (testing "a name a skill proposal has too must be said in full"
      (si/write-proposal! config {:name "count-entries" :skill-md "# x" :score 0.9})
      (is (str/includes? (:error (proposals/resolve-id config "count-entries")) "skill/count-entries, tool/count-entries")))
    (testing "accepted, it is written where tool$create writes, and loads as a user tool"
      (is (nil? (:error (proposals/accept! config "tool/count-entries" {}))))
      (let [reg (toolbox/registry tools/builtin-tools)
            {:keys [loaded]} (toolbox/load-user-tools! reg (toolbox/tools-dir dir))]
        (spit-in dir "docs/a.md" "a")
        (is (= ["user$tool$count-entries"] loaded))
        (is (= 1 (tools/call-tool (get @reg "user$tool$count-entries") {:dir "docs"} {:config config})))))
    (testing "and it is no longer pending; a second with that name does not replace it"
      (is (= ["skill/count-entries"] (map :id (proposals/list-all config))))
      (td/write-proposal! config {:record record :score 0.9 :proof {:status :verified}})
      (is (str/includes? (:error (proposals/accept! config "tool/count-entries" {})) "exists")))))

;; --- the session runs it --------------------------------------------------------------

(defn- judge? [messages] (some #(str/includes? (str (:content %)) "hold ONE reusable tool") messages))

(defn- counting-session
  "Two turns, each counting a directory's entries with one tool$run body of the
  same shape — docs/, then src/ — and a judge that drafts `count-entries`
  from them, with `verdict` merged over its reply."
  [dir config & {:keys [verdict]}]
  (let [calls (atom [])
        pending (atom [["count docs" "docs"] ["count src" "src"]])
        step (atom 0)
        llm (fn [messages]
              (swap! calls conj (str/join "\n" (map :content messages)))
              (if (judge? messages)
                (json/write-str (merge {:reasoning "r" :reusable true :score 0.9 :proposed-name "count-entries"
                                        :description "How many entries a directory has."
                                        :input-schema "[:map [:dir :string]]" :output-schema ":int"
                                        :body "(fn [{:keys [dir]}] (count (:entries (list-dir {:path dir}))))"
                                        :replay-args "[{:run 1 :args {:dir \"docs\"}} {:run 2 :args {:dir \"src\"}}]"
                                        :rationale "Both runs counted a directory."}
                                       verdict))
                (let [[_ d] (first @pending)]
                  (if (even? (swap! step inc))
                    (do (swap! pending rest)
                        (json/write-str {:reasoning "r" :tool-calls [] :answer "counted"}))
                    (json/write-str {:reasoning "r" :answer ""
                                     :tool-calls [{:tool-name "tool$run"
                                                   :tool-args {:body (str "(fn [_] (count (:entries (list-dir {:path \"" d "\"}))))")}}]})))))
        out (atom [])
        events (atom [])]
    (spit-in dir "docs/a.md" "a") (spit-in dir "docs/b.md" "b")
    (spit-in dir "src/x.clj" "x") (spit-in dir "src/y.clj" "y") (spit-in dir "src/z.clj" "z")
    (let [queue (atom ["count docs" "count src"])]
      (session/run {:lm {:provider :mock :format :mock :responses llm}
                    :config (merge {:working-dir dir :project-dir dir :max-iterations 4
                                    :persist-session? false :recap? false}
                                   config)
                    :read-input (fn [_] (let [[l] @queue] (swap! queue #(vec (rest %))) l))
                    :emit (fn [kind text] (swap! out conj [kind text]))
                    :on-event (fn [ev data] (swap! events conj [ev data]))}))
    {:calls @calls :out @out
     :judged (map second (filter #(= :session.tool/judged (first %)) @events))}))

(deftest tool-distillation-is-off-until-asked-for
  (let [dir (fresh-dir "session-off")
        {:keys [calls]} (counting-session dir {})]
    (is (not-any? #(str/includes? % "hold ONE reusable tool") calls))
    (is (empty? (td/list-proposals {:project-dir dir})))))

(deftest a-body-run-twice-becomes-a-proved-proposal
  (let [dir (fresh-dir "session-on")
        {:keys [calls out judged]} (counting-session dir {:enable-tool-distillation true})]
    (testing "one judge, called when the second run repeated the first's shape"
      (is (= 1 (count (filter #(str/includes? % "hold ONE reusable tool") calls))))
      (is (= [{:outcome :staged :proof :verified :name "count-entries" :trigger :repeated-body :turns [1 2] :runs 2}]
             (map #(select-keys % [:outcome :proof :name :trigger :turns :runs]) judged))))
    (testing "the judge read both bodies and the language"
      (let [prompt (first (filter #(str/includes? % "hold ONE reusable tool") calls))]
        (is (str/includes? prompt "=== run 1 (turn 1) — count docs ==="))
        (is (str/includes? prompt "{:path \"src\"}"))
        (is (str/includes? prompt "Allowed functions: "))))
    (testing "staged, proved against both runs, and the person told"
      (is (= ["tool/count-entries"] (map :id (proposals/list-all {:project-dir dir}))))
      (is (= 2 (count (get-in (td/read-proposal {:project-dir dir} "count-entries") [:proof :runs]))))
      (is (some #(and (= :info (first %)) (str/includes? (second %) "tool proposal staged: `count-entries`")
                      (str/includes? (second %) "replayed 2 run(s) exactly")) out)))))

(deftest a-draft-that-does-not-reproduce-its-runs-is-not-staged
  (let [dir (fresh-dir "session-mismatch")
        {:keys [judged]} (counting-session dir {:enable-tool-distillation true}
                                           ;; replays run 2 on docs/: 2 entries, where the run found 3
                                           :verdict {:replay-args "[{:run 2 :args {:dir \"docs\"}}]"})]
    (is (= [:mismatch] (map :outcome judged)))
    (is (empty? (td/list-proposals {:project-dir dir})))))
