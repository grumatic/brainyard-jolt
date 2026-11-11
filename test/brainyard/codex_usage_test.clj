(ns brainyard.codex-usage-test
  "Codex's usage read from its rollouts (byj-gly.430.7, RFC-049): a session's
  running totals and what they cost by the :openai price table — nil for a
  model it does not price — the plan's limits as Codex last reported them,
  drawn as Claude Code's are, and a footer word at 90%; and agent$codex's
  over-budget from what its delegation spent. Lines shaped as Codex 0.163
  writes them, in a Codex home of the test's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.codex-agent :as cxa]
            [brainyard.codex-sessions :as cxs]
            [brainyard.codex-usage :as usage]
            [brainyard.json :as json]))

(defn- fresh-dir [nm]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-codex-usage-" nm "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- token-count [input cached output percent]
  (json/write-str {:timestamp "t" :type "event_msg"
                   :payload {:type "token_count"
                             :info {:total_token_usage {:input_tokens input :cached_input_tokens cached
                                                        :output_tokens output :reasoning_output_tokens 3}}
                             :rate_limits {:limit_id "codex" :plan_type "plus"
                                           :primary {:used_percent percent :window_minutes 300 :resets_at 1795331109}
                                           :secondary {:used_percent 12.4 :window_minutes 10080 :resets_at 1795331109}}}}))

(defn- rollout! [home id model & counts]
  (let [f (io/file home "sessions" "2026" "10" "23" (str "rollout-2026-10-23T07-00-00-" id ".jsonl"))]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" (concat [(json/write-str {:timestamp "t" :type "session_meta" :payload {:id id :cwd "/p"}})
                                          (json/write-str {:timestamp "t" :type "turn_context" :payload {:model model}})]
                                         counts))
                 "\n"))
    f))

(deftest a-sessions-totals-and-their-cost
  (let [home (fresh-dir "home")
        f (rollout! home "t1" "gpt-5.4" (token-count 1000 400 100 10.0) (token-count 17320 0 434 20.0))]
    (is (= {:model "gpt-5.4" :input 17320 :cached 0 :output 434 :reasoning 3}
           (dissoc (usage/session-usage f) :rate-limits))
        "the newest running total")
    (testing "priced by the :openai table, cached reads at their own rate"
      (is (< (Math/abs (- 0.04981 (usage/usage-cost {} (usage/session-usage f)))) 1e-6))
      (is (< (Math/abs (- 0.113682 (usage/usage-cost {} {:model "gpt-5.4" :input 272808 :cached 255488 :output 434}))) 1e-6)))
    (is (nil? (usage/usage-cost {} {:model "gpt-6-luna" :input 10 :cached 0 :output 1})) "a model the table does not price: unknown")
    (is (nil? (usage/session-usage (io/file home "nope.jsonl"))))))

(deftest the-plans-limits-as-codex-last-reported-them
  (let [home (fresh-dir "limits")]
    (is (nil? (usage/limits home)) "nothing reported yet")
    (let [home (fresh-dir "limits2")]
      (rollout! home "t1" "gpt-5.4" (token-count 10 0 1 93.6))
      (let [l (usage/limits home)]
        (is (= "plus" (:plan l)))
        (is (= [["5-hour" 94] ["Weekly" 12]] (map (juxt :label :percent) (:limits l))))
        (is (str/starts-with? (first (usage/limit-lines l)) "  5-hour  ")))
      (is (= "Codex 5-hour 94%" (usage/warning home)) "a window at 90% or more is a footer word"))))

(deftest a-delegation-past-its-cap-is-over-budget
  (let [home (fresh-dir "budget") project (fresh-dir "project")
        config {:project-dir project :codex-home home :codex-max-cost-usd 0.01}]
    (.mkdirs (cxa/records-dir config))
    (spit (io/file (cxa/records-dir config) "t9.edn") (pr-str {:session-id "t9" :baseline-cost-usd 0.0 :updated-at 1}))
    (rollout! home "t9" "gpt-5.4" (token-count 17320 0 434 1.0))
    (let [r (cxa/await-turn config {:session-id "t9" :timeout-ms 2000})]
      (is (str/starts-with? (:over-budget r) "yes: $0.05 spent, the cap is $0.01") (pr-str r))
      (is (< (Math/abs (- 0.04981 (:cost-usd (cxa/read-record config "t9")))) 1e-6)))
    (testing "a model the table does not price is never over"
      (rollout! home "t8" "gpt-6-luna" (token-count 17320 0 434 1.0))
      (spit (io/file (cxa/records-dir config) "t8.edn") (pr-str {:session-id "t8" :updated-at 1}))
      (is (= "no" (:over-budget (cxa/await-turn config {:session-id "t8" :timeout-ms 2000})))))))
