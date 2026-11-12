;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.context-test
  "Context budget and compaction, tiers 0 and 1 (RFC-014): what the prompt
  costs, what shrinks first, and what may never shrink."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
            [brainyard.context :as context]
            [brainyard.json :as json]))

(defn- fresh-dir [name]
  (let [d (io/file "target/context-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- big [n] (apply str (repeat n "x")))

(defn- read-result [path text]
  {:tool-name "read-file" :tool-args {:path path} :tool-result text})

(defn- record [i results & {:keys [thought] :or {thought "thinking"}}]
  {:iteration i :thought thought :channel "tool" :tool-results (vec results)})

(defn- ctx-with [dir & {:as cfg}]
  {:config (merge config/defaults {:working-dir dir :project-dir dir
                                   :max-context-tokens 3000 :max-output-tokens 200}
                  cfg)
   :zones {:session-context "tools listing"}})

(defn- mem [records] {:question "q" :turn-number 1 :iterations (vec records)})

(deftest measuring
  (is (= 3 (context/estimate-tokens "abcdefghij")) "chars/4, rounded up")
  (testing "budget is the window less the reply less the margin"
    (is (= 100800 (context/budget {:max-context-tokens 120000 :max-output-tokens 8000
                                   :context-budget-safety-ratio 0.10})))
    (is (= 172627 (context/budget config/defaults)) "the shipped default: 200000 − 8192, less 10%"))
  (testing "the prompt is the zones, the turn's text and the printed history"
    (is (= (+ (context/estimate-tokens "zone") (context/estimate-tokens "q")
              (context/estimate-tokens (pr-str [{:a 1}])))
           (context/prompt-tokens {:zones {:z "zone"}} {:question "q" :iterations [{:a 1}]})))))

(deftest inert-under-budget
  (testing "a prompt that fits is returned untouched — byte-identical, so prompt caching survives"
    (let [dir (fresh-dir "inert")
          records [(record 1 [(read-result "a.clj" (big 4000))])]
          {:keys [records' steps over-budget?] :as r}
          (-> (context/enforce (ctx-with dir :max-context-tokens 200000) (mem records))
              (clojure.set/rename-keys {:records :records'}))]
      (is (= records records'))
      (is (= [] steps))
      (is (false? over-budget?))
      (is (= (:before r) (:after r)))
      (is (not (.exists (io/file dir ".brainyard" "transcripts"))) "and nothing is spilled"))))

(deftest tier-1-elides-old-content-results
  (let [dir (fresh-dir "elide")
        records [(record 1 [(read-result "a.clj" (big 8000))])
                 (record 2 [(read-result "b.clj" (big 8000))])
                 (record 3 [{:tool-name "list-dir" :tool-args {} :tool-result "src/ test/"}])]
        {:keys [records steps before after over-budget?]}
        (context/enforce (ctx-with dir :keep-verbatim-iterations 1) (mem records))]
    (is (< after before))
    (is (false? over-budget?))
    (is (= [{:step :elided :n 1}] steps)) "it stops as soon as the prompt fits"
    (testing "the stub names the size and a readable file"
      (let [stub (get-in (first records) [:tool-results 0 :tool-result])
            path (second (re-find #"full text at (\S+);" stub))]
        (is (str/starts-with? stub "…[elided 8000 chars"))
        (is (= (big 8000) (slurp path)) "and the file holds the original")
        (is (str/includes? stub "grep it or read a line range") "without inviting a re-read that re-truncates")))
    (testing "reasoning, tool names and arguments survive — they are the plan"
      (is (= ["thinking" "thinking" "thinking"] (map :thought records)))
      (is (= ["read-file" "read-file" "list-dir"] (map #(get-in % [:tool-results 0 :tool-name]) records)))
      (is (= [{:path "a.clj"} {:path "b.clj"} {}] (map #(get-in % [:tool-results 0 :tool-args]) records))))
    (testing "an elided result says so in the transcript the model reads"
      (is (true? (get-in (first records) [:tool-results 0 :elided]))))
    (testing "the last :keep-verbatim-iterations records are untouched"
      (is (= "src/ test/" (get-in (last records) [:tool-results 0 :tool-result])))
      (is (= (big 8000) (get-in (nth records 1) [:tool-results 0 :tool-result]))
          "and nothing beyond what the budget demanded"))
    (testing "small navigation results are never elided: the stub would cost as much"
      (is (nil? (get-in (last records) [:tool-results 0 :elided]))))))

(deftest superseded-reads-go-first
  (let [dir (fresh-dir "superseded")
        records [(record 1 [(read-result "same.clj" (big 6000))])
                 (record 2 [(read-result "other.clj" (big 6000))])
                 (record 3 [(read-result "same.clj" (big 6000))])
                 (record 4 [{:tool-name "list-dir" :tool-args {} :tool-result "."}])]
        out (context/enforce (ctx-with dir :max-context-tokens 4800 :keep-verbatim-iterations 2)
                             (mem records))
        steps (:steps out)
        kept (:records out)]
    (is (= :superseded (:step (first steps))) "the stale read of a re-read path is the first to go")
    (is (true? (get-in (first kept) [:tool-results 0 :elided])))
    (testing "the newest read of that path is still verbatim"
      (is (= (big 6000) (get-in (nth kept 2) [:tool-results 0 :tool-result]))))
    (testing "and it can be switched off"
      (is (= [:elided] (map :step (:steps (context/enforce (ctx-with dir :max-context-tokens 4800
                                                                     :keep-verbatim-iterations 2
                                                                     :elide-superseded-reads? false)
                                                           (mem records)))))))))

(deftest dropping-is-the-last-resort
  (let [dir (fresh-dir "drop")
        records (vec (for [i (range 1 6)] (record i [(read-result (str i ".clj") (big 9000))])))
        {:keys [records steps over-budget?]}
        (context/enforce (ctx-with dir :max-context-tokens 1200 :keep-verbatim-iterations 2) (mem records))]
    (is (= [:elided :dropped] (map :step steps)) "elide everything old before dropping anything")
    (is (= 2 (count records)) "down to the verbatim floor, never below")
    (is (= [4 5] (map :iteration records)) "and the floor is the most recent")
    (is (true? over-budget?) "reported rather than hidden when even that does not fit")))

(defn- nav-records
  "Records whose results are too small to elide (navigation, 1500 chars each):
  tier 1 can do nothing with them, which is where tier 2 earns its place."
  [n]
  (vec (for [i (range 1 (inc n))]
         (record i [{:tool-name "list-dir" :tool-args {:path (str "d" i)} :tool-result (big 1500)}]))))

(deftest tier-2-summarizes-before-dropping
  (let [dir (fresh-dir "summary")
        records (nav-records 8)
        seen (atom nil)
        cfg #(ctx-with dir :max-context-tokens 1400 :keep-verbatim-iterations 2)
        run (fn [rs summarize] (context/enforce (cfg) (mem rs) summarize))]
    (testing "what tier 1 cannot shrink becomes one summary record, and nothing is dropped"
      (let [{:keys [records steps over-budget?]} (run records (fn [span] (reset! seen span) "listed d1 to d6; all x"))]
        (is (= [{:step :summarized :n 6}] steps))
        (is (= [0 7 8] (map :iteration records)) "the summary first, then the verbatim floor")
        (is (= {:iteration 0 :channel "summary" :thought "listed d1 to d6; all x" :tool-results []}
               (first records))
            "brainyard's summary record")
        (is (= [1 2 3 4 5 6] (map :iteration @seen)) "the summarizer saw the span, oldest first")
        (is (false? over-budget?))))
    (testing "no summary, or one no shorter than the span, is no summary: records are dropped as before"
      (doseq [summarize [(constantly nil) (constantly " ") (constantly (big 20000))]]
        (let [{:keys [records steps]} (run records summarize)]
          (is (= [:dropped] (map :step steps)))
          (is (not= "summary" (:channel (first records)))))))
    (testing "a summary alone before the floor is not summarized again"
      (let [calls (atom 0)
            rs (into [(context/summary-record "earlier")] (subvec records 6))
            {:keys [steps]} (run rs (fn [_] (swap! calls inc) "x"))]
        (is (zero? @calls))
        (is (not-any? #{:summarized} (map :step steps)))))
    (testing "an earlier summary at the head does not stop the records after it going (byj-gly.227)"
      ;; the summarizer failing this time: before, the drop loop stopped at the
      ;; summary and left all seven records, over budget
      (let [rs (into [(context/summary-record "earlier")] (subvec records 1))
            {:keys [records steps]} (run rs (constantly nil))]
        (is (= "summary" (:channel (first records))) "the summary stays")
        (is (= [0 7 8] (map :iteration records)) "and what was after it, older than the floor, goes")
        (is (some #{:dropped} (map :step steps)))))
    (testing "when even the floor does not fit, the summary stays: dropping it would not help"
      (let [{:keys [records over-budget?]}
            (context/enforce (ctx-with dir :max-context-tokens 1400 :keep-verbatim-iterations 2)
                             (mem (conj (vec (butlast records)) (record 8 [(read-result "8.clj" (big 9000))])))
                             (constantly "short"))]
        (is (= [0 7 8] (map :iteration records)))
        (is (true? over-budget?))))))

(deftest tier-2-runs-the-nodes-predictor-unless-turned-off
  (let [dir (fresh-dir "summary-leaf")
        records (nav-records 8)
        asked (atom [])
        leaf (fn [summary?]
               (let [ctx (agent/make-ctx {:lm {:provider :mock :format :mock
                                               :responses (fn [msgs] (swap! asked conj msgs)
                                                            (json/write-str {:summary "we listed eight dirs of x"}))}
                                          :config (merge {:working-dir dir :project-dir dir
                                                          :max-context-tokens 1400 :max-output-tokens 200
                                                          :keep-verbatim-iterations 2}
                                                         (when-not summary? {:compaction-summary? false}))})
                     ;; the counters :react/init starts a turn with
                     st (atom (assoc (mem records) :usage {:input-tokens 0 :output-tokens 0 :llm-calls 0
                                                           :cache-read-tokens 0 :cache-write-tokens 0}))]
                 (context/enforce-action (assoc ctx :st-memory st :zones {}
                                                :node {:id :react/budget :predictor "react/compact"}))
                 @st))]
    (testing "turned off: no call, and records are dropped as they were before tier 2"
      (is (not= "summary" (:channel (first (:iterations (leaf false))))))
      (is (empty? @asked)))
    (testing "on by default, the node's predictor writes the summary, and its call is counted"
      (let [m (leaf true)]
        (is (= [0 7 8] (map :iteration (:iterations m))))
        (is (= "we listed eight dirs of x" (:thought (first (:iterations m)))))
        (is (= 1 (count @asked)))
        (is (str/includes? (str (last @asked)) "d6") "it was shown the span")
        (is (= 1 (get-in m [:usage :llm-calls])))))))

(deftest a-summary-keeps-its-identifiers-apart-from-its-prose
  ;; byj-gly.383: in 250 words the summary kept about a quarter of what the
  ;; later iterations used; the identifiers now come apart from the prose
  (is (= "we listed x\n\nsrc/a.clj:12\nfoo-bar" (context/summary-text {:summary " we listed x " :kept "src/a.clj:12\nfoo-bar"})))
  (is (= "we listed x" (context/summary-text {:summary "we listed x"})) "a reply without them is the prose alone")
  (is (nil? (context/summary-text {:summary " " :kept ""})))
  (let [dir (fresh-dir "summary-kept")
        ctx (agent/make-ctx {:lm {:provider :mock :format :mock
                                  :responses (fn [_] (json/write-str {:summary "we listed eight dirs of x"
                                                                      :kept "x/d1\nx/d6"}))}
                             :config {:working-dir dir :project-dir dir
                                      :max-context-tokens 1400 :max-output-tokens 200
                                      :keep-verbatim-iterations 2}})
        st (atom (assoc (mem (nav-records 8)) :usage {:input-tokens 0 :output-tokens 0 :llm-calls 0
                                                      :cache-read-tokens 0 :cache-write-tokens 0}))]
    (context/enforce-action (assoc ctx :st-memory st :zones {} :node {:id :react/budget :predictor "react/compact"}))
    (is (= "we listed eight dirs of x\n\nx/d1\nx/d6" (:thought (first (:iterations @st))))
        "the record the agent reads next holds both")))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(deftest the-loop-budgets-and-says-so
  (let [dir (fresh-dir "loop")
        ;; read-file never passes the cap now, whatever it is asked (byj-gly.190),
        ;; so what overflows is a grep of all 3000 lines
        _ (spit (io/file dir "big.txt") (str/join "\n" (repeat 3000 (big 19))))
        events (atom [])
        replies (atom [(reply {:tool-calls [{:tool-name "grep" :tool-args {:pattern "." :path "big.txt" :max-results 3000}}]})
                       (reply {:tool-calls [{:tool-name "grep" :tool-args {:pattern "." :path "big.txt" :max-results 3000}}]})
                       (reply {:tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}]})
                       (reply {:answer "done"})])
        prompts (atom [])
        r (agent/ask "q" {:lm {:provider :mock :format :mock
                               :responses (fn [msgs] (swap! prompts conj (:content (last msgs)))
                                            (let [[x] (swap-vals! replies #(vec (rest %)))] (first x)))}
                          :config {:working-dir dir :project-dir dir
                                   :max-context-tokens 14500 :max-output-tokens 1000
                                   :keep-verbatim-iterations 1 :rebudget-every-n-iter 1
                                   ;; tiers 0 and 1 are what this watches; tier 2
                                   ;; would summarize the elided record away
                                   :compaction-summary? false
                                   ;; the window is sized against a line per tool —
                                   ;; each with its schema-written Returns line
                                   ;; since byj-gly.199: 9000 → 11500; the
                                   ;; family listing (byj-gly.188) fits it whole.
                                   ;; 11500 → 12500 when the skill-writing and
                                   ;; proposal tools (byj-gly.246) met the longer
                                   ;; Composing Tools section (byj-gly.245).
                                   ;; 12500 → 13500 for the session and team
                                   ;; tools (byj-gly.253.3, .253.5); 13500 → 14500
                                   ;; for schedule$* (byj-gly.325, .331)
                                   :tool-listing-by-family? false
                                   ;; nothing here shares: share$* (byj-gly.362)
                                   ;; would take the room the window is sized for
:share-tools? false
                                   ;; nor the person's own skills, which the
                                   ;; prompt would list (byj-gly.467): run
                                   ;; alone, outside the runner's empty home
                                   :skills? false}
                          :on-event (fn [e d] (swap! events conj [e d]))})]
    (is (= "done" (:answer r)))
    (testing "tier 0 capped the result, tier 1 elided the older one"
      (is (str/includes? (last @prompts) "…[elided"))
      (is (= 3000 (count (str/split-lines (slurp (io/file dir "big.txt"))))) "…and the file on disk is untouched")
      (is (str/includes? (nth @prompts 1) "[truncated")
          "the cap is 16000 chars now, not 64000")
      (is (re-find #"…\[truncated: lines \d+–\d+ of \d+ are not shown here \(\d+ of \d+ chars\) — the whole of it is \S+it1-grep\.txt\. Read them with read-file \{:path \\?\"\S+it1-grep\.txt\\?\" :offset \d+ :limit 200\}"
                   (nth @prompts 1))
          "and the cut says how much, where the whole of it went, and how to page it"))
    (testing "the budget is reported as an event"
      (let [budgeted (filter #(= :agent.context/budgeted (first %)) @events)]
        (is (seq budgeted))
        (is (= #{:phase :iteration :total-tokens :after-tokens :budget :compactions :over-budget?}
               (set (keys (second (first budgeted))))))
        (is (some #(seq (:compactions (second %))) budgeted) "and it says what it did")))
    (testing "the spilled text is readable for the rest of the session"
      ;; the run names its own directory, so find it rather than guess it
      (let [f (first (filter #(.isFile %) (file-seq (io/file dir ".brainyard" "transcripts"))))]
        (is (some? f))
        (is (every? #(< 60000 (count (slurp %))) (filter #(.isFile %) (file-seq (io/file dir ".brainyard" "transcripts"))))
            "every spill is the WHOLE result — tier 1 eliding it later points at that file, not a copy of the cut")))))

(deftest a-spilled-collection-is-a-line-an-entry
  (let [dir (fresh-dir "cap-lines")
        cfg {:working-dir dir :project-dir dir :session-id "s-lines"}
        result (pr-str (vec (for [i (range 400)] {:id (str "tool-" i) :description (big 40)})))
        {:keys [text spilled]} (context/cap-result cfg 1 1 "list-tools" result 2000)
        lines (str/split-lines (slurp spilled))]
    (is (not (str/includes? result "\n")) "the result the kernel prints is one line")
    (is (< 300 (count lines)) "the spill has a line per entry, so a line range reads a slice")
    (is (= (read-string result) (read-string (slurp spilled))) "and says exactly what the result said")
    (testing "the model is shown whole lines of it, and told which ones it was not"
      (let [[_ from to total] (re-find #"lines (\d+)–(\d+) of (\d+) are not shown" text)
            [from to total] (map parse-long [from to total])
            [shown-head] (str/split text #"\n…\[truncated")
            shown-tail (last (str/split text #"or grep it\]\n"))]
        (is (= (count lines) total))
        (is (= (str/join "\n" (take (dec from) lines)) shown-head) "the head is lines 1 to from-1, exactly")
        (is (= (str/join "\n" (drop to lines)) shown-tail) "the tail is the lines after `to`, exactly")
        (is (str/includes? text (str ":offset " from " :limit 200}")) "and the read that starts where the head stopped")))))

(deftest a-result-that-is-one-long-string-still-shows-it
  ;; a bash result's :output is one line of the pretty-printed spill; whole
  ;; lines showed the model its short keys and none of the output (byj-gly.217)
  (let [dir (fresh-dir "cap-one-string")
        cfg {:working-dir dir :project-dir dir :session-id "s-one"}
        out (str/join "\n" (for [i (range 800)] (str "line " i " " (big 30))))
        result (pr-str {:status "completed" :exit-code 0 :output out})
        {:keys [text spilled]} (context/cap-result cfg 1 1 "bash" result 16000)]
    (is (some? spilled))
    (is (str/includes? text "line 0 ") "the output's head is shown")
    (is (str/includes? text "line 799 ") "and its tail")
    (is (< 12000 (count text) 16500) "the budget is spent on the output, not wasted")
    (is (str/includes? text "the whole of it is") "and it says where the rest is")))

(deftest a-result-past-the-cap-is-spilled-whole-and-cut-at-both-ends
  (let [dir (fresh-dir "cap")
        cfg {:working-dir dir :project-dir dir :session-id "s-cap"}
        text (str "HEAD" (apply str (repeat 1000 "x")) "TAIL:count=42")]
    (testing "a result that fits is left as it is"
      (is (= {:text "short"} (context/cap-result cfg 1 1 "bash" "short" 100))))
    (let [{t :text path :spilled} (context/cap-result cfg 2 3 "list-tools" text 200)]
      (testing "past the cap: the head and the tail, and the cut says how much and where the rest went"
        (is (str/starts-with? t "HEAD"))
        (is (str/ends-with? t "TAIL:count=42") "what ends a result — a count — survives")
        (is (str/includes? t (str "…[truncated " (- (count text) 200) " of " (count text) " chars — the whole of it is " path ", one line of " (count text) " chars. Read it with read-file")))
        (is (str/includes? path "turn-2") )
        (is (str/ends-with? path "it3-list-tools.txt")))
      (testing "the spill is the whole of it"
        (is (= text (slurp path)))))
    (testing "tier 1 eliding a spilled result points at that spill rather than overwriting it"
      (let [{:keys [text spilled]} (context/cap-result cfg 1 1 "read-file" (big 30000) 16000)
            records [(record 1 [{:tool-name "read-file" :tool-args {:path "a"} :tool-result text :spilled spilled}])
                     (record 2 [(read-result "b" "small")])]
            {:keys [records]} (context/reduce-history (assoc cfg :keep-verbatim-iterations 1 :min-elide-chars 100)
                                                      ;; under budget once the old one is elided
                                                      1 records #(boolean (:elided (first (:tool-results (first %))))))]
        (is (str/includes? (:tool-result (first (:tool-results (first records)))) spilled))
        (is (= 30000 (count (slurp spilled))) "still the whole text")))))

(deftest a-spill-never-writes-over-another
  ;; tier 0 spilled A to it5-read-file.txt; tier 1 then elided B, a second
  ;; read-file in the same iteration, under the same name — and A's marker
  ;; pointed at B's text (byj-gly.219)
  (let [dir (fresh-dir "spill-over")
        cfg {:working-dir dir :project-dir dir :session-id "s-over"}
        {a-path :spilled} (context/cap-result cfg 1 5 "read-file" (apply str (repeat 20000 "A")) 16000)
        b-path (#'context/elide-result cfg 1 5 {:tool-name "read-file" :tool-result (apply str (repeat 5000 "B"))})
        b-path (second (re-find #"full text at ([^;]+);" (:tool-result b-path)))]
    (is (str/starts-with? (slurp a-path) "AAAA") "A's spill still holds A")
    (is (not= a-path b-path))
    (is (str/starts-with? (slurp b-path) "BBBB") "and B has a file of its own")))

(deftest a-stub-on-a-cut-result-says-the-whole-size
  ;; tier 1 said "elided 16000 chars" of a result whose spill held 60000 —
  ;; the cut, not the whole, to a model planning how to page it (byj-gly.233)
  (let [dir (fresh-dir "stub-size")
        cfg {:working-dir dir :project-dir dir :session-id "s-stub"}
        {:keys [text spilled]} (context/cap-result cfg 1 1 "read-file" (apply str (repeat 60000 "z")) 16000)
        r (#'context/elide-result cfg 1 1 {:tool-name "read-file" :tool-result text :spilled spilled :full-chars 60000})]
    (is (str/includes? (:tool-result r) "elided 60000 chars"))))

(deftest spill-directories-do-not-collide-across-runs
  (testing "two runs in one project each spill to their own directory"
    (let [dir (fresh-dir "collide")
          run-a (:config (agent/make-ctx {:config {:working-dir dir :project-dir dir}}))
          run-b (:config (agent/make-ctx {:config {:working-dir dir :project-dir dir}}))]
      (is (not= (:session-id run-a) (:session-id run-b)))
      (is (not= (str (context/transcript-dir run-a 1)) (str (context/transcript-dir run-b 1))))
      (testing "so one run's stub never resolves to another run's text"
        (let [a (context/spill! run-a 1 3 "read-file" "run A content")
              b (context/spill! run-b 1 3 "read-file" "run B content")]
          (is (not= a b))
          (is (= "run A content" (slurp a)))
          (is (= "run B content" (slurp b)))))
      (testing "and the same run keeps one directory per turn"
        (is (= (str (context/transcript-dir run-a 1)) (str (context/transcript-dir run-a 1))))
        (is (not= (str (context/transcript-dir run-a 1)) (str (context/transcript-dir run-a 2))))))))

(deftest a-loop-can-decline-to-budget
  (testing ":enable-context-budget false is a no-op, and so is a tree without the nodes"
    (let [dir (fresh-dir "off")
          records [(record 1 [(read-result "a.clj" (big 40000))])]
          st (atom (mem records))
          ctx (assoc (ctx-with dir :enable-context-budget false) :st-memory st :node {})]
      (is (nil? (context/enforce-action ctx)))
      (is (= records (:iterations @st))))))
