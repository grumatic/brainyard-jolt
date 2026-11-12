;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.claude-usage-test
  "Claude Code's sign-in and its subscription's windows (byj-gly.324), with
  the credentials, the endpoint and the clock all seams: nothing here reads a
  keychain or calls the network."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.claude-usage :as cu]
            [brainyard.json :as json]))

(defn- fresh-dir [n]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "cu-" n "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(def ^:private blob
  (json/write-str {:claudeAiOauth {:accessToken "tok" :expiresAt 1000 :refreshTokenExpiresAt 2000
                                   :subscriptionType "max"}}))

(deftest the-windows-are-read-by-shape-and-a-null-one-is-absent
  (let [ls (cu/limits {:five_hour {:utilization 7.6 :resets_at "2026-10-09T15:00:00Z"}
                       :seven_day nil
                       :seven_day_opus {:utilization 40 :scope {:model {:display_name "Opus"}}}
                       :nimbus_quill {:used_percentage 3}
                       :seven_day_sonnet nil
                       :extra_usage {:utilization 99}
                       :odd "x"})]
    (is (= [["Session" 8] ["Opus" 40] ["Nimbus quill" 3]] (map (juxt :label :percent) ls))
        "the session first by its own name; a model's by its display name, else its key; null and extra usage left out")
    (is (= "2026-10-09T15:00:00Z" (:resets-at (first ls)))))
  (testing "utilization is already a percentage, and is bounded"
    (is (= [1 100] (map :percent (cu/limits {:five_hour {:utilization 1.0} :seven_day {:percent 140}})))))
  (testing "epoch seconds and milliseconds both become a time"
    (is (= ["2026-10-09T00:00:00Z" "2026-10-09T00:00:00Z"]
           (map :resets-at (cu/limits {:five_hour {:utilization 1 :resets_at 1791504000}
                                       :seven_day {:utilization 1 :resets_at 1791504000000}}))))))

(deftest the-credentials-are-read-in-claude-codes-order
  (let [dir (fresh-dir "creds")
        env {"CLAUDE_CONFIG_DIR" dir}
        asked (atom [])
        secret (fn [svc] (swap! asked conj svc) (when (= svc "Claude Code-credentials") blob))]
    (testing "no file: the scoped keychain item, then the legacy one"
      (is (= {:access-token "tok" :expires-at 1000 :refresh-expires-at 2000 :plan "max"}
             (cu/read-oauth {:env env :read-secret secret})))
      (is (= (cu/keychain-services dir) @asked))
      (is (re-matches #"Claude Code-credentials-[0-9a-f]{8}" (first @asked)) "scoped by a hash of the directory"))
    (testing "the file first, and no keychain asked"
      (reset! asked [])
      (spit (io/file dir ".credentials.json") (str/replace blob "\"tok\"" "\"filetok\""))
      (is (= "filetok" (:access-token (cu/read-oauth {:env env :read-secret secret}))))
      (is (empty? @asked)))
    (testing "a blob with no access token is not a sign-in"
      (is (nil? (cu/parse-blob "{\"claudeAiOauth\":{}}")))
      (is (nil? (cu/parse-blob "not json"))))))

(deftest the-snapshot-is-kept-and-the-endpoint-is-not-pressed
  (cu/reset!*)
  (let [t (atom 0)
        calls (atom 0)
        answer (atom {:status 200 :body (json/write-str {:five_hour {:utilization 92}})})
        opts {:read-oauth (constantly {:access-token "tok" :plan "max"}) :home (fresh-dir "home")
              :now #(deref t) :fetch (fn [_] (swap! calls inc) @answer)}]
    (is (= [["Session" 92]] (map (juxt :label :percent) (:limits (cu/usage opts)))))
    (is (= "Session 92%" (cu/warning)) "at 90% or more, the status line says so")
    (testing "within five minutes: the snapshot, no call"
      (reset! t 60000)
      (cu/usage opts)
      (is (= 1 @calls)))
    (testing "a failure keeps the last reading, and says why"
      (reset! t (+ cu/ttl-ms 1))
      (reset! answer {:status 401})
      (let [u (cu/usage opts)]
        (is (= [92] (map :percent (:limits u))))
        (is (str/includes? (:error u) "token rejected"))))
    (testing "a 429's Retry-After is honoured, even by a forced refresh"
      (reset! answer {:status 429 :headers {"retry-after" "120"}})
      (cu/usage (assoc opts :force? true))
      (let [n @calls]
        (reset! t (+ @t 60000))
        (is (= [92] (map :percent (:limits (cu/usage (assoc opts :force? true)))))
            "the last reading stands")
        (is (= n @calls) "no call inside it")))
    (testing "not signed in: no call, and unknown is not zero"
      (cu/reset!*)
      (reset! calls 0)
      (let [u (cu/usage (assoc opts :read-oauth (constantly nil)))]
        (is (false? (:logged-in? u)))
        (is (zero? @calls))
        (is (nil? (cu/warning)))))
    (testing "the endpoint unreachable: unknown, with why"
      (cu/reset!*)
      (let [u (cu/usage (assoc opts :fetch (fn [_] (throw (ex-info "connect timed out" {})))))]
        (is (empty? (:limits u)))
        (is (= ["  unknown — usage endpoint unreachable: connect timed out"] (vec (cu/limit-lines u))))))
    (cu/reset!*)))
