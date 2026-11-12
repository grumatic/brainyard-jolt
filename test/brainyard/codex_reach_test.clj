;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.codex-reach-test
  "A live Codex session as a target of the session tools (byj-gly.430.5,
  RFC-049): listed as codex:<thread>, one in this run's tmux typed into, one
  anywhere else reached by `codex queue`, each answered from its rollout, and
  the session tools routing codex: names to them. On the stand-in for Codex
  (test/fixtures/fake-codex), a Codex home and a tmux server of the test's
  own; `codex queue` stood in for by typing the line into the session's own
  terminal, which is what it makes Codex do."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent-host :as ah]
            [brainyard.channel :as channel]
            [brainyard.codex-host :as host]
            [brainyard.codex-reach :as reach]
            [brainyard.codex-sessions :as cxs]
            [brainyard.effect :as fx]))

(defn- fresh-dir [nm]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-codex-reach-" nm "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(def fake (.getCanonicalPath (io/file "test/fixtures/fake-codex")))

(defn- first-turn!
  "Ask the session something, so it has a rollout and is listed (a Codex
  session is placed by its rollout's directory)."
  [id home]
  (fx/run!! (host/type-line id "hello" {:home home}) 15000)
  (fx/run!! (host/await-settled id {:home home :settle-ms 200}) 15000))

(defn- tool [config nm] (:fn (first (filter #(= nm (:name %)) (channel/commands config)))))

(deftest a-codex-session-in-byjs-tmux-is-typed-into-and-answers
  (when (ah/tmux?)
    (let [label (str "byj-cxr-" (random-uuid))
          home (fresh-dir "home") project (fresh-dir "project")
          config {:project-dir project :working-dir project :session-id "byj-me" :codex-home home}]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (let [id (:id (:ok (fx/run!! (host/ready (host/start! {:dir project :home home :codex fake}) {:home home}) 30000)))]
            (first-turn! id home)
            (testing "listed as codex:<thread>, reached through this run's tmux"
              (let [[s :as ss] (reach/sessions config {:home home})]
                (is (= 1 (count ss)))
                (is (= {:session-id (str "codex:" id) :kind "codex" :status "idle" :reachable? true :via :tmux}
                       (select-keys s [:session-id :kind :status :reachable? :via])))
                (is (= (:session-id s) (:session-id (reach/find-session config (subs id 0 6) {:home home})))
                    "by a prefix of its thread")))
            (testing "asked: typed, and its answer read from the rollout"
              (let [s (reach/find-session config (str "codex:" id) {:home home})]
                (is (= {:status :answered :answer "echo: list the files" :session-id (str "codex:" id)}
                       (fx/run-value!! (reach/ask-task config s "list the files" 20000 {:home home}))))))
            (testing "the session tools route codex: names here"
              (is (some #(= (str "codex:" id) (:session-id %)) (:sessions ((tool config "session$list") {} nil))))
              (is (= :idle (:status ((tool config "session$status") {:session (str "codex:" id)} nil))))
              (is (= "echo: and the docs"
                     (:answer ((tool config "session$ask") {:session (str "codex:" id) :question "and the docs" :timeout-ms 20000} nil)))))
            (testing "a brief too long to type is a file the line points at"
              (let [s (reach/find-session config (str "codex:" id) {:home home})
                    r (fx/run-value!! (reach/ask-task config s (str/join "\n" ["step one" "step two"]) 20000 {:home home}))]
                (is (str/starts-with? (:answer r) "echo: Read "))
                (is (str/includes? (:answer r) "/.brainyard/handovers/handover-")))))
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(deftest a-codex-session-anywhere-else-is-queued-to
  (when (ah/tmux?)
    (let [label (str "byj-cxq-" (random-uuid))
          home (fresh-dir "home") project (fresh-dir "project")
          config {:project-dir project :working-dir project :session-id "byj-me" :codex-home home}
          ;; Codex as a person runs it in their own tmux: not byj-cx-<thread>
          _ (fx/sh {:out :string :err :string :env (assoc (into {} (System/getenv)) "CODEX_HOME" home)}
                   "tmux" "-L" label "new-session" "-d" "-s" "work" "-x" "160" "-y" "40" "-c" project
                   "env" (str "CODEX_HOME=" home) fake)
          queued (atom [])
          ;; `codex queue` makes Codex run the line as its next turn: the
          ;; stand-in takes it from its terminal
          queue! (fn [_ id line _]
                   (swap! queued conj [id line])
                   (fx/sh {:out :string :err :string} "tmux" "-L" label "send-keys" "-t" "work" "-l" "--" line)
                   (Thread/sleep 200)
                   (fx/sh {:out :string :err :string} "tmux" "-L" label "send-keys" "-t" "work" "Enter"))]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (let [id (loop [n 0]
                     (or (first (filter #(cxs/live? home %)
                                        (map #(str/replace (.getName %) #"\.lock$" "")
                                             (remove #(str/starts-with? (.getName %) ".")
                                                     (or (.listFiles (io/file home "thread-writer-locks")) [])))))
                         (when (< n 50) (Thread/sleep 100) (recur (inc n)))))]
            ;; its first turn, so it has a rollout and is this project's
            (queue! nil id "hello" nil)
            (loop [n 0] (when (and (nil? (cxs/rollout-file home id)) (< n 50)) (Thread/sleep 100) (recur (inc n))))
            (Thread/sleep 800)
            (let [[s] (reach/sessions config {:home home})]
              (is (= [(str "codex:" id) :queue true] ((juxt :session-id :via :reachable?) s))
                  "not in byj's tmux: reached by codex queue")
              (is (= {:status :answered :answer "echo: run the tests" :session-id (str "codex:" id)}
                     (fx/run-value!! (reach/ask-task config s "run the tests" 20000 {:home home :queue!* queue!}))))
              (is (= [id "run the tests"] (last @queued)))))
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))
