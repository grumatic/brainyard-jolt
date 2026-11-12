;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.cancellation-test
  "The loop on effects (byj-gly.27, RFC-009 'Migration'): a turn is a Task,
  cancelling it stops the tree structurally, and what the turn started — an
  LLM call, a bash tool, the claude CLI — stops with it. A cancelled turn inside
  a session ends that turn, not the session."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.bt :as bt]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.session :as session]
            [ebb.core :as m]
            [jolt.process :as p]))

(defn- now [] (System/currentTimeMillis))

(defn- alive? [marker]
  (not (str/blank? (:out (p/sh "pgrep" "-f" marker)))))

(defn- wait-until
  "Poll `pred` every 20 ms for up to `ms`; true once it held."
  [ms pred]
  (let [deadline (+ (now) ms)]
    (loop []
      (cond (pred) true
            (> (now) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn- start
  "Run a task; returns [promise canceller]."
  [task]
  (let [out (promise)]
    [out (fx/run task #(deliver out {:ok %}) #(deliver out {:err %}))]))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(defn- mock-lm [f] {:provider :mock :format :mock :responses f})

;; --- the engine ---------------------------------------------------------------------

(deftest an-action-may-return-a-task
  (let [mem (atom {})
        ctx {:st-memory mem
             :registry {:t (fn [_] (m/sp (m/? (m/sleep 10)) (swap! mem assoc :ran true) :failure))}}]
    (is (= :failure (bt/tick ctx [:action {:fn :t}])) "the task's value is read like a returned status")
    (is (:ran @mem))))

(deftest cancelling-a-tick-stops-the-tree-and-is-not-an-error
  (let [mem (atom {})
        ctx {:st-memory mem
             :registry {:slow (fn [_] (Thread/sleep 10000))
                        :after (fn [_] (swap! mem assoc :after-ran true))}}
        [out cancel] (start (bt/tick-task ctx [:sequence {}
                                               [:action {:fn :slow}]
                                               [:action {:fn :after}]]))]
    (Thread/sleep 200)
    (let [t0 (now)]
      (cancel)
      (let [{:keys [err]} (deref out 3000 {:timeout true})]
        (is (fx/cancelled? err))
        (is (< (- (now) t0) 1000) "the blocked leaf was interrupted, not waited out")))
    (Thread/sleep 100)
    (is (not (:after-ran @mem)) "nothing after the cancelled node runs")
    (is (nil? (:bt/error @mem)) "a cancellation is not recorded as a node failure")))

(deftest a-cancellable-scope-cancels-only-its-child
  (let [mem (atom {})
        cancellers (atom {})
        ctx {:st-memory mem
             :cancellers cancellers
             :registry {:slow (fn [{:keys [st-memory]}]
                                (swap! st-memory assoc :started true)
                                (Thread/sleep 10000)
                                (swap! st-memory assoc :finished true))
                        :after (fn [_] (swap! mem assoc :outer-continued true))}}
        tree [:sequence {}
              [:scope {:cancellable :turn :out {:inner :*}
                       :on-cancel {:terminated-by :cancelled}}
               [:action {:fn :slow}]]
              [:action {:fn :after}]]
        [out _] (start (bt/tick-task ctx tree))]
    (is (wait-until 2000 #(contains? @cancellers :turn)) "the canceller is registered while the child runs")
    (Thread/sleep 100)
    ((get @cancellers :turn))
    (is (= {:ok :success} (deref out 3000 {:timeout true})) "the scope succeeds; the enclosing tree carries on")
    (is (:outer-continued @mem))
    (is (= {:started true :terminated-by :cancelled} (:inner @mem)) "the child stopped and got :on-cancel")
    (is (empty? @cancellers) "the canceller is removed once the child ends")))

(deftest a-cancellable-scope-still-fails-for-real-errors
  (let [ctx {:st-memory (atom {}) :cancellers (atom {}) :registry {}}]
    (is (thrown-with-msg? Exception #"unknown node kind"
                          (bt/tick ctx [:scope {:cancellable :turn} [:bogus {}]])))))

;; --- a turn ---------------------------------------------------------------------------

(deftest cancelling-a-turn-interrupts-the-llm-call
  (let [calls (atom 0)
        [out cancel] (start (agent/ask-task "q" {:lm (mock-lm (fn [_] (swap! calls inc) (Thread/sleep 10000)
                                                                (reply {:answer "late"})))
                                                 :config {:working-dir "."}}))]
    (is (wait-until 3000 #(pos? @calls)))
    (let [t0 (now)]
      (cancel)
      (is (fx/cancelled? (:err (deref out 3000 {:timeout true}))))
      (is (< (- (now) t0) 1000)))))

(deftest a-cancelled-turn-kills-its-bash-tool
  (let [marker (str "sleep 7." (rand-int 1000000))
        [out cancel] (start (agent/ask-task "q" {:lm (mock-lm (fn [_] (reply {:tool-calls [{:tool-name "bash"
                                                                                            :tool-args {:command marker}}]})))
                                                 :config {:working-dir "." :max-iterations 2}}))]
    (is (wait-until 5000 #(alive? marker)) "non-vacuous: the tool's child was running")
    (cancel)
    (is (fx/cancelled? (:err (deref out 3000 {:timeout true}))))
    (is (wait-until 2000 #(not (alive? marker))) "no child outlives the cancelled turn")))

(deftest a-cancelled-turn-stops-its-subagent
  ;; RFC-018: the dispatch waits on the child's task from the tool's own `blk`
  ;; thread, so cancelling the parent cancels the child — and the child's own
  ;; bash tool dies with it, two levels down.
  (let [marker (str "sleep 7." (rand-int 1000000))
        dir (io/file "target/cancellation-test/subagent")
        agent-dir (io/file dir ".brainyard" "agents" "slow")
        _ (.mkdirs agent-dir)
        _ (spit (io/file agent-dir "agent.edn") (pr-str {:instruction "You are the slow agent."
                                                         :tools ["bash"]}))
        reply-for (fn [msgs]
                    (if (str/includes? (:content (first msgs)) "You are the slow agent.")
                      (reply {:tool-calls [{:tool-name "bash" :tool-args {:command marker}}]})
                      (reply {:tool-calls [{:tool-name "agent$slow" :tool-args {:question "take your time"}}]})))
        [out cancel] (start (agent/ask-task "q" {:lm (mock-lm reply-for)
                                                 :config {:working-dir (.getPath dir)
                                                          :project-dir (.getPath dir)
                                                          :agent-roots [(.getPath (io/file dir ".brainyard" "agents"))]
                                                          :builtin-agents? false
                                                          :max-iterations 2}}))]
    (is (wait-until 5000 #(alive? marker)) "non-vacuous: the subagent's own tool was running")
    (cancel)
    (is (fx/cancelled? (:err (deref out 3000 {:timeout true}))))
    (is (wait-until 2000 #(not (alive? marker)))
        "no process under a cancelled turn's subagent outlives it")))

(deftest a-cancelled-turn-kills-the-claude-cli
  (let [dir (io/file "target/cancellation-test")
        script (io/file dir (str "fake-claude-" (rand-int 1000000)))
        _ (.mkdirs dir)
        _ (spit script "#!/bin/bash\ncat > /dev/null\nsleep 30\n")
        _ (p/sh "chmod" "+x" (.getPath script))
        path (.getAbsolutePath script)
        [out cancel] (start (agent/ask-task "q" {:lm {:provider :claude-code :format :claude-code
                                                      :cli-command path :timeout-ms 60000}
                                                 :config {:working-dir "."}}))]
    (is (wait-until 5000 #(alive? path)) "non-vacuous: the CLI was running")
    (cancel)
    (is (fx/cancelled? (:err (deref out 3000 {:timeout true}))))
    (is (wait-until 2000 #(not (alive? path))) "the CLI process is gone")
    (.delete script)))

;; --- a session ------------------------------------------------------------------------

(deftest a-cancelled-turn-does-not-end-the-session
  (let [cancellers (atom {})
        queue (atom ["slow question" "fast question"])
        out (atom [])
        in-llm (promise)
        result (future
                 (session/run {:lm (mock-lm (fn [[_ user]]
                                              (if (str/includes? (:content user) "slow question")
                                                (do (deliver in-llm true) (Thread/sleep 10000) (reply {:answer "late"}))
                                                (reply {:answer "fast answer"}))))
                               :config {:working-dir "." :persist-session? false}
                               :cancellers cancellers
                               :read-input (fn [_] (let [[l] @queue] (swap! queue #(vec (rest %))) l))
                               :emit (fn [kind text] (swap! out conj [kind text]))}))]
    (is (deref in-llm 5000 false))
    (is (true? (session/cancel-turn! cancellers)))
    (let [final (deref result 5000 :timed-out)]
      (is (not= :timed-out final) "the session finished instead of waiting out the slow call")
      (is (= [[:answer "*(Turn cancelled.)*"] [:answer "fast answer"]] @out))
      (is (= [:cancelled :answer-channel] (map :terminated-by (:previous-turns final))))
      (is (= 2 (:turn-count final))))
    (is (false? (session/cancel-turn! cancellers)) "no turn running, nothing to cancel")))

;; --- the provider layer ------------------------------------------------------------------

(deftest with-retry-is-an-effect
  (testing "retries a retryable failure and reports each wait"
    (let [n (atom 0)
          seen (atom [])]
      (is (= :ok (llm/with-retry {:max-retries 3 :base-delay-ms 5 :on-retry #(swap! seen conj (:attempt %))}
                   #(if (< (swap! n inc) 3) (throw (ex-info "5xx" {:retryable? true})) :ok))))
      (is (= [1 2] @seen))))
  (testing "a non-retryable failure is thrown at once"
    (let [n (atom 0)]
      (is (thrown-with-msg? Exception #"400"
                            (llm/with-retry {:base-delay-ms 5} #(do (swap! n inc) (throw (ex-info "400" {}))))))
      (is (= 1 @n))))
  (testing "a cancellation surfacing from the call ends it at once, as a cancellation"
    (let [n (atom 0)]
      (is (thrown? InterruptedException
                   (llm/with-retry {:base-delay-ms 5}
                     #(do (swap! n inc) (throw (InterruptedException. "cancelled"))))))
      (is (= 1 @n))))
  (testing "a cancel during the backoff ends the call at once"
    (let [[out cancel] (start (fx/task-of #(llm/with-retry {:base-delay-ms 10000}
                                             (fn [] (throw (ex-info "5xx" {:retryable? true}))))))]
      (Thread/sleep 200)
      (let [t0 (now)]
        (cancel)
        (is (fx/cancelled? (:err (deref out 3000 {:timeout true}))))
        (is (< (- (now) t0) 1000))))))
