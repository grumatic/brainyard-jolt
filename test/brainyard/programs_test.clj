;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.programs-test
  "Measuring and tuning a predictor (RFC-021).

  Nothing here calls a live model. The compile tests use brainyard's own
  offline device — a fake LM that answers correctly only when at least N demos
  are in the prompt it was handed — so `a search picks the candidate that helps
  the student` and `zero-shot wins when demos do not help` are ASSERTED rather
  than assumed from a run someone paid for."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.eventlog :as el]
            [brainyard.evaluate :as ev]
            [brainyard.json :as json]
            [brainyard.optimize :as opt]
            [brainyard.predictions :as predictions]
            [brainyard.predictor :as predictor]
            [brainyard.programs :as programs]
            [brainyard.tools :as tools]))

(defn- fresh-dir [nm]
  (let [d (io/file "target/programs-test" nm)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getAbsolutePath d)))

(def ^:private spec
  {:id "user/double"
   :strategy :predict
   :instructions "Double the number."
   :inputs [[:n {:desc "A number"} :string]]
   :outputs [[:doubled {:desc "Twice it"} :string]]})

(defn- ex [n] (ev/example {:n (str n)} {:doubled (str (* 2 n))}))

;; --- datasets ------------------------------------------------------------------------

(deftest a-split-is-content-addressed-so-it-survives-the-dataset-growing
  (let [exs (mapv ex (range 60))
        {:keys [train val test]} (ev/split exs)]
    (testing "the three splits partition the set, roughly 60/20/20"
      (is (= 60 (+ (count train) (count val) (count test))))
      (is (< 25 (count train) 50)))
    (testing "order does not matter — the hash is of content, not position"
      (is (= (set train) (set (:train (ev/split (reverse exs)))))))
    (testing "adding an example never moves an existing one into another split"
      (let [grown (ev/split (conj exs (ex 999)))]
        (is (every? (fn [k] (every? (set (get grown k)) (get (ev/split exs) k)))
                    [:train :val :test]))))
    (testing "and the dataset hash names the content, not the order"
      (is (= (ev/dataset-hash exs) (ev/dataset-hash (reverse exs))))
      (is (not= (ev/dataset-hash exs) (ev/dataset-hash (conj exs (ex 999))))))))

(deftest a-dataset-is-built-from-what-the-agent-actually-did
  (let [dir (fresh-dir "dataset")
        config {:project-dir dir :working-dir dir :session-id "s1"}
        sink (el/sink config)
        call (fn [m] (sink :predictor/call (merge {:predictor-id "user/double"} m)))]
    (call {:inputs {:n "1"} :outputs {:doubled "2"} :usage {:input-tokens 5 :output-tokens 1}})
    (call {:inputs {:n "2"} :outputs {:doubled "4"}})
    (call {:inputs {:n "1"} :outputs {:doubled "2"}})                 ; duplicate
    (call {:inputs {:n "3"} :error "boom"})                           ; errored
    ;; this one used to be LOST: the prediction log clipped at 4000 and
    ;; records->examples dropped anything carrying the marker. The event log
    ;; clips nothing, so a long answer is an example like any other — the
    ;; single biggest effect of the unification (74 of 156 records, byj-gly.119)
    (call {:inputs {:n "4"} :outputs {:doubled (str (apply str (repeat 5000 "x")))}})
    (call {:inputs {:n "5"} :outputs {}})                             ; nothing produced

    (testing "the log is one record per line, and reads back"
      (is (= 6 (count (predictions/records config))))
      (is (= 6 (count (predictions/records config "user/double")))))

    (let [r (programs/build-dataset config "user/double" "silver" {})]
      (testing "only records that answered the question asked become examples"
        (is (= 3 (:examples r)) "the long one among them, now that nothing clips")
        (is (= {:error 1 :invalid 1 :clipped 0 :duplicate 1} (:skipped r))))
      (testing "and the dataset says its labels are the model's own output"
        (let [ds (programs/load-dataset config "user/double" "silver")]
          (is (= :prediction-log (:label-source ds)))
          (is (= [{:n "1"} {:n "2"} {:n "4"}] (map :inputs (:examples ds)))))))

    (testing "a predictor with no records is an error, not an empty dataset"
      (is (thrown-with-msg? Exception #"No usable prediction records"
                            (programs/build-dataset config "user/other" "x" {}))))))

(deftest metrics-score-what-they-say
  (testing "exact-match normalizes case and space, and scores an unlabelled example 0"
    (let [m (ev/exact-match)]
      (is (= 1.0 (m {:labels {:a "Yes"}} {:outputs {:a " yes "}} nil)))
      (is (= 0.0 (m {:labels {:a "yes"}} {:outputs {:a "no"}} nil)))
      (is (= 0.0 (m {:labels {}} {:outputs {:a "yes"}} nil))
          "no labels must not score 1.0 — a silver set without labels would read as perfect")))
  (testing "set-f1 treats 'nothing' as an answer"
    (let [m (ev/set-f1 :e)]
      (is (= 1.0 (m {:labels {:e []}} {:outputs {:e []}} nil)))
      (is (= 0.0 (m {:labels {:e ["a"]}} {:outputs {:e []}} nil)))
      (is (= 0.5 (m {:labels {:e ["a" "b"]}} {:outputs {:e ["A" "c"]}} nil)))))
  (testing "schema-valid trusts a result that never said it was invalid"
    (is (= 1.0 ((ev/schema-valid) {} {:outputs {}} nil)))
    (is (= 0.0 ((ev/schema-valid) {} {:valid? false} nil))))
  (testing "the combinators"
    (is (= 0.75 ((ev/weighted [[(constantly 1.0) 3] [(constantly 0.0) 1]]) {} {} nil)))
    (is (= 0.0 ((ev/all-of (constantly 1.0) (constantly 0.4)) {} {} nil)))))

(deftest a-score-without-a-spread-is-not-a-result
  (testing "repeats report every pass, its spread and a confidence interval"
    (let [n (atom 0)
          flaky (fn [_] (swap! n inc) {:result {:outputs {:doubled (if (even? @n) "2" "9")}} :trace []})
          r (ev/evaluate flaky [(ex 1)] (ev/exact-match) :repeats 4)]
      (is (= 4 (:completed-repeats r)))
      (is (= [0.0 1.0 0.0 1.0] (:repeat-scores r)))
      (is (= 0.5 (:score r)))
      (is (pos? (:stddev r)))
      (is (= 2 (count (:ci95 r))))))

  (testing "compare-scores refuses to call brainyard's own measured difference real"
    ;; the numbers are brainyard's: one configuration scored 0.559 and 0.605 on
    ;; two passes of the same twelve examples
    (let [c (ev/compare-scores {:score 0.605 :stderr 0.04 :completed-repeats 3}
                               {:score 0.559 :stderr 0.04 :completed-repeats 3})]
      (is (false? (:better? c)))
      (is (true? (:tested? c)))
      (is (> (:margin c) (:diff c)) "the margin is wider than the difference")))

  (testing "a single pass has no spread, and says so rather than pretending"
    (let [c (ev/compare-scores {:score 0.9 :completed-repeats 1} {:score 0.5 :completed-repeats 1})]
      (is (true? (:better? c)))
      (is (false? (:tested? c)))
      (is (nil? (:margin c)))))

  (testing "a real difference does survive the test"
    (is (:better? (ev/compare-scores {:score 0.95 :stderr 0.01 :completed-repeats 5}
                                     {:score 0.20 :stderr 0.01 :completed-repeats 5}))))

  (testing "no spread on either side is arithmetic, not a test, and says so"
    ;; it used to answer :tested? true with a margin of 0.0, which crowned a
    ;; proposal 0.0286 ahead on the first real compile (byj-gly.57)
    (let [c (ev/compare-scores {:score 0.2571 :stderr 0.0 :completed-repeats 2}
                               {:score 0.2286 :stderr 0.0 :completed-repeats 2})]
      (is (false? (:tested? c)) "there was nothing to weigh")
      (is (false? (:paired? c))))))

(deftest a-comparison-is-paired-over-examples
  ;; The question is whether THESE examples went better, not whether two pass
  ;; means differ. A deterministic student has no pass spread at all, so the
  ;; per-pass test had nothing left to weigh and waved anything through.
  (testing "one example better out of many is not an improvement"
    (let [a {:score 0.26 :example-scores [1.0 1.0 0.0 0.0 1.0]}
          b {:score 0.23 :example-scores [1.0 1.0 0.0 0.0 0.0]}
          c (ev/compare-scores a b)]
      (is (true? (:paired? c)))
      (is (true? (:tested? c)))
      (is (false? (:better? c)))
      (is (> (:margin c) (:diff c)) "the margin is wider than one example's worth")))

  (testing "the same shape the first real compile produced is now refused"
    ;; zero-shot 0.2286 and proposal 0.2571 over 35 examples: a single example
    ;; of difference, which was crowned because both had stddev 0.0
    (let [base (vec (concat (repeat 8 1.0) (repeat 27 0.0)))
          better (assoc base 8 1.0)
          c (ev/compare-scores {:score 0.2571 :example-scores better}
                               {:score 0.2286 :example-scores base})]
      (is (false? (:better? c)))))

  (testing "every example agreeing is only a result once there are enough of them"
    ;; with no spread in the differences the t has no standard error to divide
    ;; by, so what is left is a sign test: n agreeing signs are two-sided
    ;; p = 2*0.5^n, which clears 0.05 first at n = 6
    (let [agree (fn [n] (ev/compare-scores {:score 1.0 :example-scores (vec (repeat n 1.0))}
                                           {:score 0.0 :example-scores (vec (repeat n 0.0))}))]
      (testing "four perfectly separated examples are p = 0.125 — not a result"
        (is (false? (:tested? (agree 4))))
        (is (true? (:better? (agree 4))) "still the bare comparison"))
      (testing "five is 0.0625, which is still not 0.05"
        (is (false? (:tested? (agree 5)))))
      (testing "six is 0.0156, and six examples agreeing IS a result"
        (is (true? (:tested? (agree 6))))
        (is (true? (:better? (agree 6)))))
      (testing "the direction still has to be a direction"
        (is (false? (:tested? (ev/compare-scores
                               {:score 0.5 :example-scores (vec (repeat 8 0.5))}
                               {:score 0.5 :example-scores (vec (repeat 8 0.5))})))))))

  (testing "identical scores are not an improvement, and nothing was tested"
    (let [c (ev/compare-scores {:score 0.5 :example-scores [1.0 0.0 1.0 0.0]}
                               {:score 0.5 :example-scores [1.0 0.0 1.0 0.0]})]
      (is (false? (:better? c)))
      (is (false? (:tested? c)))))

  (testing "evaluate hands the leaderboard what the pairing needs"
    (let [exs (mapv ex (range 4))
          r (ev/evaluate (fn [inputs] {:outputs {:doubled (str (* 2 (parse-long (:n inputs))))}})
                         exs (ev/exact-match) :repeats 1)]
      (is (= 4 (count (:example-scores r))))
      (is (contains? (set ev/leaderboard-row-keys) :example-scores)
          "and a row keeps it, or compare-scores never sees it"))))

(deftest what-a-failing-call-costs-the-run
  (let [exs (mapv ex (range 4))
        prog (fn [e] (fn [_] (throw e)))]
    (testing "a malformed reply is the program being wrong: it scores 0 and the run goes on"
      (let [r (ev/evaluate (prog (ex-info "bad json" {:kind :malformed})) exs (ev/exact-match))]
        (is (= 0.0 (:score r)))
        (is (= 4 (:errors r)))
        (is (nil? (:stopped r)))))
    (testing "a fatal failure stops the run instead of burning the rest of the dataset"
      (let [r (ev/evaluate (prog (ex-info "usage cap" {:fatal? true})) exs (ev/exact-match))]
        (is (= :fatal (:stopped r)))
        (is (= 1 (:attempted r)))))
    (testing "a spend cap stops it too, and reports what it spent"
      (let [r (ev/evaluate (fn [_] {:result {:outputs {:doubled "x"}}
                                    :trace [{:usage {:input-tokens 1 :output-tokens 1}}]})
                           exs (ev/exact-match) :max-calls 2)]
        (is (= :budget (:stopped r)))
        (is (= 2 (:calls r)))))
    (testing "a metric that throws scores 0 rather than failing the run"
      (let [r (ev/evaluate (fn [_] {:result {:outputs {:doubled "2"}} :trace []})
                           [(ex 1)] (fn [_ _ _] (throw (ex-info "metric broke" {}))))]
        (is (= 0.0 (:score r)))
        (is (nil? (:stopped r)))))))

;; --- optimizers -------------------------------------------------------------------

(deftest optimizers-propose-demos-and-apply-nothing
  (let [train (mapv ex (range 6))]
    (testing "labeled-few-shot takes k labelled examples and calls no model"
      (let [{:keys [params report]} (opt/labeled-few-shot "user/double" train :k 3)]
        (is (= 3 (count (get-in params ["user/double" :demos]))))
        (is (= 6 (:available report)))
        (is (every? #(= :labeled (get-in % [:source :kind]))
                    (get-in params ["user/double" :demos])))))

    (testing "a seeded shuffle is reproducible, and different seeds differ"
      (is (= (opt/shuffle-seeded (range 10) 7) (opt/shuffle-seeded (range 10) 7)))
      (is (not= (opt/shuffle-seeded (range 10) 7) (opt/shuffle-seeded (range 10) 8))))

    (testing "bootstrap keeps only the teacher's traces that PASSED the metric"
      (let [teacher (fn [{:keys [n]}]
                      (let [i (parse-long n)
                            out {:doubled (if (even? i) (str (* 2 i)) "wrong")}]
                        {:result {:outputs out}
                         :trace [{:predictor-id "user/double" :inputs {:n n} :outputs out}]}))
            {:keys [params report]} (opt/bootstrap-few-shot teacher train (ev/exact-match)
                                                            :predictor-id "user/double")]
        (is (= 3 (count (get-in params ["user/double" :demos]))) "0, 2 and 4 passed")
        (is (= 3 (:passed report)))
        (is (= 6 (:attempted report)) "the teacher ran once per example, not once per trial")
        (is (= [1.0 0.0 1.0 0.0 1.0 0.0] (:scores report))
            "every teacher score is reported, so a bad teacher is visible")))

    (testing "a teacher that fails outright is reported, not thrown"
      (let [{:keys [report]} (opt/bootstrap-few-shot (fn [_] (throw (ex-info "no" {})))
                                                     train (ev/exact-match) :predictor-id "user/double")]
        (is (= 6 (:errors report)))
        (is (= 0 (:passed report)))))))

;; --- compile, offline ------------------------------------------------------------------

(defn- demo-sensitive-lm
  "brainyard's offline device: a model that answers correctly only once at
  least `n` demos appear in the system message it was given. Without it, a
  compile test can only assert that the machinery ran."
  [n]
  {:provider :mock :format :mock
   :responses (fn [messages]
                (let [sys (:content (first messages))
                      demos (count (re-seq #"Example \d+" (str sys)))
                      ;; the user message is "n: 3\n\nRespond with …", so take
                      ;; the field, not everything past the last colon
                      v (parse-long (second (re-find #"n: (\d+)" (str (:content (second messages))))))]
                  (json/write-str {:doubled (if (>= demos n) (str (* 2 v)) "wrong")})))})

(defn- write-predictor! [dir]
  (let [f (io/file dir ".brainyard" "predictors" "user" "double.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str spec))
    dir))

(defn- seed-dataset! [config n]
  (let [examples (mapv ex (range n))]
    (.mkdirs (.getParentFile (programs/dataset-file config "user/double" "d")))
    (spit (programs/dataset-file config "user/double" "d")
          (pr-str {:v 1 :predictor-id "user/double" :label-source :gold :examples examples}))
    examples))

(deftest demos-are-proposed-only-when-they-beat-no-demos
  (let [dir (write-predictor! (fresh-dir "compile-wins"))
        config {:project-dir dir :working-dir dir}
        _ (seed-dataset! config 30)
        r (programs/compile-predictor config "user/double" "d"
                                      {:lm (demo-sensitive-lm 1)
                                       :optimizer "labeled-few-shot"
                                       :metric "exact-match"
                                       :max-bootstrapped 4})]
    (testing "the student cannot answer without demos and can with them, so demos win"
      (is (= "proposal" (:best r)))
      (is (= 4 (:demos r)))
      (is (false? (:no-op r)))
      (is (= 0.0 (:zero-shot-score r)))
      (is (= 1.0 (:best-score r))))

    (testing "and nothing was applied — the params the runtime reads are untouched"
      (is (not (.isFile (programs/params-file-for config "user/double")))))

    (testing "the proposal is on disk, pending, with a dossier a person can read"
      (let [[p] (programs/list-proposals config "user/double")]
        (is (= "pending" (:status p)))
        (is (= (:proposal-id r) (:proposal-id p)))
        (let [review (slurp (:review p))]
          (is (str/includes? review "Leaderboard"))
          (is (str/includes? review "by programs accept user/double"))
          (is (str/includes? review "Example 1")
              "the dossier shows the literal prompt text, not a summary of it"))))))

(deftest zero-shot-winning-is-a-result-not-a-failure
  (let [dir (write-predictor! (fresh-dir "compile-noop"))
        config {:project-dir dir :working-dir dir}
        _ (seed-dataset! config 30)
        ;; this model needs no demos: it is already right
        r (programs/compile-predictor config "user/double" "d"
                                      {:lm (demo-sensitive-lm 0)
                                       :optimizer "labeled-few-shot"
                                       :metric "exact-match"})]
    (is (= "zero-shot" (:best r)))
    (is (true? (:no-op r)))
    (is (= 0 (:demos r)) "a proposal that changes nothing carries no demos")
    (testing "the losing candidate is kept beside it, so the decision is reviewable"
      (let [dir (io/file (programs/proposals-dir config "user/double") (:proposal-id r))]
        (is (.isFile (io/file dir "candidate.edn")))
        (is (str/includes? (slurp (io/file dir "REVIEW.md")) "No change proposed"))))))

(deftest a-proposal-is-installed-by-a-person-and-the-runtime-then-reads-it
  (let [dir (write-predictor! (fresh-dir "accept"))
        config {:project-dir dir :working-dir dir}
        _ (seed-dataset! config 30)
        r (programs/compile-predictor config "user/double" "d"
                                      {:lm (demo-sensitive-lm 1) :optimizer "labeled-few-shot"})
        pid (:proposal-id r)]

    (testing "accepting writes the params file RFC-005 already reads on every call"
      (let [{:keys [params-file previous]} (programs/accept-proposal! config "user/double" pid)]
        (is (nil? previous) "nothing was replaced the first time")
        (is (.isFile (io/file params-file)))
        (let [p (predictor/load-predictor "user/double" [(io/file dir ".brainyard" "predictors")])
              resolved (predictor/resolve-params p {:params-roots [(.getPath (programs/programs-root config))]})]
          (is (= 4 (count (:demos (:params resolved)))))
          (is (str/includes? (str (:source resolved)) "user/double.edn"))
          (is (= "labeled-few-shot" (get-in resolved [:params :compiled-by :optimizer]))
              "the file says what produced it"))))

    (testing "a proposal is accepted once"
      (is (thrown-with-msg? Exception #"is accepted" (programs/accept-proposal! config "user/double" pid))))

    (testing "a second acceptance keeps the file it replaced"
      (let [r2 (programs/compile-predictor config "user/double" "d"
                                           {:lm (demo-sensitive-lm 1) :optimizer "labeled-few-shot"
                                            :max-bootstrapped 2})
            {:keys [previous]} (programs/accept-proposal! config "user/double" (:proposal-id r2))]
        (is (some? previous))
        (is (= 4 (count (:demos (read-string (slurp previous))))) "the replaced params are recoverable")))

    (testing "rejecting keeps the files and only moves the status"
      (let [r3 (programs/compile-predictor config "user/double" "d"
                                           {:lm (demo-sensitive-lm 1) :optimizer "labeled-few-shot"})]
        (programs/reject-proposal! config "user/double" (:proposal-id r3))
        (is (= "rejected" (:status (first (programs/list-proposals config "user/double")))))
        (is (.isFile (io/file (programs/proposals-dir config "user/double") (:proposal-id r3) "params.edn")))))))

;; --- what the model may and may not reach -----------------------------------------------

(deftest accepting-a-proposal-is-not-a-tool
  (let [dir (fresh-dir "tools")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        names (set (keys @(:tools ctx)))]
    (testing "the model may measure and propose"
      (is (every? names ["program$list" "program$eval" "program$compile" "program$proposals"])))
    (testing "and may NOT install what it proposed"
      ;; a params file rewrites the system message of every later turn, so this
      ;; is the one command that stays a person's
      (is (not-any? #(str/includes? % "accept") names))
      (is (not-any? #(str/includes? % "reject") names))
      (is (nil? (get @(:tools ctx) "program$accept"))))
    (testing ":programs? false removes the family"
      (let [ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir :programs? false}})]
        (is (not-any? #(str/starts-with? % "program$") (keys @(:tools ctx))))))))

(deftest the-commands-report-a-failure-instead-of-throwing
  (let [dir (fresh-dir "cmd-errors")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))]
    (is (str/includes? (:error (call "program$eval" {:predictor-id "user/nope" :dataset "d"})) "nope"))
    (testing "an empty result says WHY it is empty, or it reads as a finding about the model"
      (is (str/includes? (:error (call "program$build-dataset" {:predictor-id "user/nope" :name "d"}))
                         "nothing has been recorded for it yet")
          "the log is on by default, so empty usually means no traffic — not a switch left off")
      (let [off (agent/make-ctx {:config {:working-dir dir :project-dir dir :event-log? false}})]
        (is (str/includes? (:error (tools/call-tool (get @(:tools off) "program$build-dataset")
                                                    {:predictor-id "user/nope" :name "d"} off))
                           "event log is off")
            "and when the log it reads is off, it says THAT — the records come from there now")))
    (is (= [] (:proposals (call "program$proposals" {:predictor-id "user/nope"}))))
    (testing "program$list names the metrics a command may use"
      (let [r (call "program$list" {})]
        (is (contains? (:metrics r) "exact-match"))
        (is (contains? (:metrics r) "set-f1"))))))

(deftest a-metric-is-named-and-an-unknown-one-says-what-exists
  (is (fn? (programs/resolve-metric "exact-match")))
  (is (fn? (programs/resolve-metric "set-f1:tags")))
  (is (thrown-with-msg? Exception #"unknown metric" (programs/resolve-metric "vibes")))
  (is (thrown-with-msg? Exception #"needs a field" (programs/resolve-metric "set-f1")))
  (testing "set-f1:<field>:<key> compares items by one key, so a tool-call set is scored by its names (byj-gly.125)"
    (let [ex {:labels {:tool-calls [{:tool-name "Read" :tool-args {:path "a"}} {:tool-name "grep" :tool-args {}}]}}
          out {:outputs {:tool-calls [{:tool-name "read" :tool-args {:path "b"}} {:tool-name "grep" :tool-args {:x 1}}]}}]
      (is (= 1.0 ((programs/resolve-metric "set-f1:tool-calls:tool-name") ex out nil)) "same names, other arguments")
      (is (= 0.0 ((programs/resolve-metric "set-f1:tool-calls") ex out nil)) "whole calls differ")))
  (testing "channel-choice: one bit, tool or answer"
    (let [m (programs/resolve-metric "channel-choice")
          tool {:labels {:tool-calls [{:tool-name "grep"}]}}
          answer {:labels {:tool-calls []}}]
      (is (= 1.0 (m tool {:outputs {:tool-calls [{:tool-name "other"}]}} nil)) "a tool for a tool, whichever")
      (is (= 0.0 (m tool {:outputs {:tool-calls [] :answer "done"}} nil)))
      (is (= 1.0 (m answer {:outputs {:answer "done"}} nil)))
      (is (= 0.0 ((programs/resolve-metric "channel-choice:items") {:labels {:items [1]}} {:outputs {}} nil))))))

;; --- what a demo is allowed to carry (byj-gly.58) -----------------------------------------

(deftest a-secret-never-reaches-a-demo
  ;; the chain this closes: a prediction record becomes an example, an example
  ;; becomes a demo, and a demo is pasted into the system message of every later
  ;; turn — which reaches the model provider. The log is the raw copy, and it is
  ;; off until a project asks for it.
  (let [dir (fresh-dir "redact-examples")
        config {:project-dir dir :working-dir dir :session-id "s1"}
        sink (el/sink config)]
    (sink :predictor/call {:predictor-id "user/deploy"
                           :inputs {:task "deploy with sk-abcdefghij0123456789"}
                           :outputs {:plan "used AKIAIOSFODNN7EXAMPLE"}})
    (sink :predictor/call {:predictor-id "user/deploy"
                           :inputs {:task "deploy the ordinary way"}
                           :outputs {:plan "no secrets here"}})

    (let [r (programs/build-dataset config "user/deploy" "silver" {})
          text (slurp (:path r))]
      (is (= 2 (:examples r)))
      (is (true? (:redacted r)) "and the summary says the dataset differs from the log")

      (testing "neither the inputs nor the outputs carry it"
        (is (not (str/includes? text "sk-abcdefghij0123456789")))
        (is (not (str/includes? text "AKIAIOSFODNN7EXAMPLE")))
        (is (= 2 (count (re-seq #"\[REDACTED\]" text)))))

      (testing "and everything else survives — this is redaction, not deletion"
        (is (str/includes? text "deploy with"))
        (is (str/includes? text "deploy the ordinary way"))
        (is (str/includes? text "no secrets here")))

      (testing "the log itself still holds what the turn did, unredacted and whole"
        (is (str/includes? (pr-str (predictions/records config)) "sk-abcdefghij0123456789"))))))

(deftest a-clean-dataset-does-not-claim-to-be-redacted
  (let [dir (fresh-dir "redact-clean")
        config {:project-dir dir :working-dir dir :session-id "s1"}
        sink (el/sink config)]
    (sink :predictor/call {:predictor-id "user/x" :inputs {:n "1"} :outputs {:y "2"}})
    (is (false? (:redacted (programs/build-dataset config "user/x" "silver" {}))))))

(deftest the-params-a-proposal-writes-are-redacted-too
  ;; not because the demos are unredacted — they came through records->examples —
  ;; but because this file becomes every later prompt, and an optimizer that
  ;; proposes an INSTRUCTION rather than demos reaches this line and not that one
  (let [dir (fresh-dir "redact-accept")
        config {:project-dir dir :working-dir dir}
        pdir (io/file (programs/proposals-dir config "user/x") "123")]
    (.mkdirs pdir)
    (spit (io/file pdir "status.edn") (pr-str {:status :pending :ts 123}))
    (spit (io/file pdir "params.edn")
          (pr-str {:instructions "call it with ghp_abcdefghijklmnopqrstuvwxyz01"
                   :demos [{:inputs {:a "1"} :outputs {:b "2"}}]}))

    (let [r (programs/accept-proposal! config "user/x" "123")
          text (slurp (:params-file r))]
      (testing "a proposal edited by hand between writing and accepting is still scrubbed"
        (is (not (str/includes? text "ghp_abcdefghijklmnopqrstuvwxyz01")))
        (is (str/includes? text "[REDACTED]"))
        (is (str/includes? text "call it with") "the instruction is kept, less the token"))
      (testing "and the demo survives intact"
        (is (str/includes? text ":demos"))))))

;; --- bootstrap-random-search (byj-gly.57) ----------------------------------------------

(deftest the-search-spends-the-diversity-bootstrap-few-shot-leaves
  (let [train (mapv ex (range 12))
        ;; every example passes, so the pool is large enough for the subsets to
        ;; actually differ — with a pool of 3 there is nothing to search over
        teacher (fn [{:keys [n]}]
                  (let [out {:doubled (str (* 2 (parse-long n)))}]
                    {:result {:outputs out}
                     :trace [{:predictor-id "user/double" :inputs {:n n} :outputs out}]}))
        {:keys [candidates report]}
        (opt/bootstrap-random-search teacher train (ev/exact-match)
                                     :predictor-id "user/double" :trials 4 :max-bootstrapped 3)]
    (testing "the teacher still runs once per example, not once per trial"
      (is (= 12 (:attempted report)) "the pool is built ONCE and shared — the budget decision"))
    (testing "trial 0 is exactly what bootstrap-few-shot would have picked"
      (let [bfs (opt/bootstrap-few-shot teacher train (ev/exact-match)
                                        :predictor-id "user/double" :max-bootstrapped 3)
            t0 (first (filter #(zero? (:trial %)) candidates))]
        (is (= (get-in bfs [:params "user/double" :demos])
               (get-in t0 [:params "user/double" :demos]))
            "so a search can never select worse than the optimizer it generalises")))
    (testing "and the other trials really are different draws"
      (let [sets (map #(get-in % [:params "user/double" :demos]) candidates)]
        (is (> (count (distinct sets)) 1) "non-vacuous: the trials are not all the same subset")
        (is (= (count sets) (count (distinct sets))) "duplicates collapse rather than being re-evaluated")))
    (testing "every candidate is a full subset"
      (is (every? #(= 3 (count (get-in % [:params "user/double" :demos]))) candidates)))))

(deftest a-search-puts-every-trial-on-the-leaderboard-and-shares-the-budget
  (let [dir (write-predictor! (fresh-dir "compile-search"))
        config {:project-dir dir :working-dir dir}
        _ (seed-dataset! config 30)
        ;; the teacher must PASS for there to be a pool to search over. With
        ;; demo-sensitive-lm 1 the teacher answers wrong without demos, the pool
        ;; is empty, and every trial draws the same nothing — which collapses to
        ;; one candidate, correctly, and would make this test vacuous.
        r (programs/compile-predictor config "user/double" "d"
                                      {:lm (demo-sensitive-lm 0)
                                       :optimizer "bootstrap-random-search"
                                       :metric "exact-match"
                                       :trials 3
                                       :teacher-baseline? false
                                       :max-bootstrapped 2})
        ;; the leaderboard lives in the written report, not the summary
        report (edn/read-string (slurp (io/file (programs/proposals-dir config "user/double")
                                                (:proposal-id r) "report.edn")))
        labels (map :candidate (:leaderboard report))]
    (testing "one row per trial, beside zero-shot"
      (is (= :zero-shot (first labels)))
      (is (every? #(re-matches #"proposal-\d+" (name %)) (rest labels))
          (str "got " (pr-str labels))))
    (testing "a winner is still chosen by select-best's rules, not the search's"
      (is (= "zero-shot" (:best r))
          "this student answers fine without demos, so no trial beats it — a RESULT, not a failure")
      (is (= 1.0 (:zero-shot-score r)))
      (is (= 3 (:trials report)) "and the report says how many were tried"))))

(deftest the-search-budget-is-divided-not-multiplied
  (let [dir (write-predictor! (fresh-dir "compile-search-budget"))
        config {:project-dir dir :working-dir dir}
        _ (seed-dataset! config 30)
        run (fn [optimizer extra]
              (programs/compile-predictor config "user/double" "d"
                                          (merge {:lm (demo-sensitive-lm 1)
                                                  :optimizer optimizer :metric "exact-match"
                                                  :teacher-baseline? false
                                                  :max-calls 12 :max-bootstrapped 2}
                                                 extra)))
        single (run "bootstrap-few-shot" {})
        search (run "bootstrap-random-search" {:trials 3})
        ;; :calls on the summary is already the total across every row
        calls :calls]
    (testing "a search does not cost N times a single proposal for the same :max-calls"
      (is (<= (calls search) (* 2 (calls single)))
          (str "single " (calls single) " vs search " (calls search)
               " — the cap is shared across rows, not applied to each")))))

;; --- the notice (byj-gly.57) -----------------------------------------------------------

(deftest the-pool-count-is-what-the-dataset-yields
  ;; predictions/usable? re-states records->examples' rules in another namespace
  ;; — programs requires predictions and not the reverse — so the two can drift.
  ;; This is what stops them.
  (let [dir (fresh-dir "pool-vs-dataset")
        config {:project-dir dir :working-dir dir :session-id "s1"}
        sink (el/sink config)
        rec (fn [m] (sink :predictor/call (merge {:predictor-id "user/double"} m)))]
    (rec {:inputs {:n "1"} :outputs {:doubled "2"}})                  ; usable
    (rec {:inputs {:n "2"} :outputs {:doubled "4"}})                  ; usable
    (rec {:inputs {:n "1"} :outputs {:doubled "2"}})                  ; duplicate
    (rec {:inputs {:n "3"} :outputs {}})                              ; nothing produced
    (rec {:inputs {:n "4"} :error "boom"})                            ; errored
    (let [records (predictions/records config "user/double")
          {:keys [examples]} (programs/records->examples records {})]
      (is (= 5 (count records)) "the log keeps what the dataset refuses")
      (is (= 2 (count examples)))
      (is (= (count examples) (predictions/pool-size config "user/double"))
          "the notice counts exactly what build-dataset would yield")))

  (testing "a record carrying the truncation marker is KEPT by both now — the
            marker means the agent trimmed its own iteration history for the
            context budget, so the model saw exactly that and answered it"
    (let [dir (fresh-dir "pool-agent-clip")
          config {:project-dir dir :working-dir dir :session-id "s1"}
          marked {:predictor-id "user/double" :inputs {:n "9"}
                  :outputs {:doubled (str "abc" predictions/clip-marker "900 chars]")}}]
      ((el/sink config) :predictor/call marked)
      (is (true? (predictions/clipped? (select-keys marked [:inputs :outputs])))
          "non-vacuous: it really does carry the marker")
      (is (= 1 (predictions/pool-size config "user/double")))
      (is (= 1 (count (:examples (programs/records->examples
                                  (predictions/records config "user/double") {})))))
      (testing "and :skip-clipped true still refuses it, for a source that truncates"
        (is (zero? (count (:examples (programs/records->examples
                                      (predictions/records config "user/double")
                                      {:skip-clipped true})))))))))
(defn- log-call [c data] ((el/sink c) :predictor/call data))

(deftest the-pool-is-read-from-the-event-log
  ;; predictions.edn is gone: events.log is lossless and already holds every
  ;; :predictor/call whole, so a second file recording the same thing less
  ;; completely had nothing to offer.
  (let [d (fresh-dir "pool-from-log")
        c {:project-dir d :working-dir d :session-id "s1"}]
    (log-call c {:predictor-id "user/double" :inputs {:n "1"} :outputs {:doubled "2"}})
    (log-call c {:predictor-id "user/double" :inputs {:n "2"} :outputs {:doubled "4"}})
    (log-call c {:predictor-id "user/other" :inputs {:n "9"} :outputs {:x "y"}})
    (testing "records come back oldest first, filtered by predictor"
      (let [rs (predictions/records c "user/double")]
        (is (= 2 (count rs)))
        (is (= [{:n "1"} {:n "2"}] (map :inputs rs)) "oldest first, as a dataset wants")
        (is (= "s1" (:session (first rs))) "and carry the session from the envelope")))
    (is (= 2 (predictions/pool-size c "user/double")))
    (is (= ["s1"] (predictions/sessions c)))))

(deftest a-pool-that-is-big-enough-says-so-once
  (let [d (fresh-dir "pool-notice")
        c {:project-dir d :working-dir d :session-id "s1" :prediction-notice-at 3}
        seen (atom [])
        notice (predictions/notice-sink c (fn [e dd] (swap! seen conj [e dd])))
        ;; the writer runs first in the real fan-out, and the notice counts the
        ;; file it wrote — so the test wires them in that order too
        call (fn [i] (let [dd {:predictor-id "user/double"
                               :inputs {:n (str i)} :outputs {:doubled (str (* 2 i))}}]
                       (log-call c dd)
                       (notice :predictor/call dd)))]
    (testing "nothing is said below the threshold"
      (call 1) (call 2)
      (is (empty? @seen)))
    (testing "and it is said when the pool reaches it"
      (call 3)
      (let [[e dd] (first (filter #(= :programs/dataset-ready (first %)) @seen))]
        (is (= :programs/dataset-ready e))
        (is (= "user/double" (:predictor-id dd)))
        (is (= 3 (:records dd)))))
    (testing "once, not every turn after"
      (call 4) (call 5)
      (is (= 1 (count (filter #(= :programs/dataset-ready (first %)) @seen)))))
    (testing "and the records are all still in the log"
      (is (= 5 (predictions/pool-size c "user/double"))))))

(deftest without-a-bus-there-is-no-notice
  (is (nil? (predictions/notice-sink {:project-dir "." :working-dir "."} nil))))

(deftest an-eval-is-stamped-with-the-model-its-calls-ran-on
  ;; params can choose the model, so the ambient one was the wrong thing to
  ;; stamp :sampling and :model from (byj-gly.229)
  (let [dir (fresh-dir "used-lm")
        p (predictor/compile-spec spec)
        ambient {:provider :mock :format :mock :model "mock-1"}
        _ (.mkdirs (io/file dir "user"))
        _ (spit (io/file dir "user" "double.edn") (pr-str {:lm "claude-code/haiku"}))
        used (#'programs/used-lm p {:lm ambient :params-roots [dir]} ambient)]
    (is (= :claude-code (:provider used)) "the params file's choice, not the ambient model")
    (is (true? (brainyard.llm/samples-freely? used)) "so a single pass is warned about")
    (is (= ambient (#'programs/used-lm p {:lm ambient :params-roots [(fresh-dir "no-params")]} ambient))
        "and with no params, the ambient one")))

(deftest an-old-dataset-is-read-in-the-new-call-shape
  ;; labels recorded as tool-name/tool-args scored every tool-call example
  ;; wrong against replies in name/args (byj-gly.233)
  (let [dir (fresh-dir "old-dataset")
        config {:working-dir dir :project-dir dir}
        f (#'programs/dataset-file config "react/think-act" "old")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str {:examples [{:inputs {:question "q"} :labels {:tool-calls [{:tool-name "t" :tool-args {:a 1}}]}}]}))
    (is (= [{:name "t" :args {:a 1}}]
           (get-in (programs/load-dataset config "react/think-act" "old") [:examples 0 :labels :tool-calls])))))

