;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.hook-serve-test
  "`byj hook serve` and the command that asks it (RFC-012 \"Another agent's
  calls\", byj-ywp): the installed command run as the agent runs it, against
  a server answering by a fn of the test's, and with nothing there, when the
  fallback — a stand-in for byj that records how it was called — answers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.hook-serve :as hs]))

(defn- fresh-dir [name]
  (let [d (io/file "target/hook-serve-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- fresh-socket
  "A socket path short enough for `sun_path` (104 bytes on macOS), which a
  directory under the repository's target may not be."
  []
  (str "/tmp/byj-hs-" (subs (str (random-uuid)) 0 8) ".sock"))

(defn- stand-in
  "An executable in `dir` named `byj` that answers as the fallback would:
  it writes its arguments and its stdin beside itself and prints
  `{\"from\":\"fallback\"}`."
  [dir]
  (let [f (io/file dir "byj")]
    (spit f (str "#!/bin/sh\n"
                 "printf '%s\\n' \"$@\" > \"" dir "/args\"\n"
                 "cat > \"" dir "/stdin\"\n"
                 "printf '{\"from\":\"fallback\"}\\n'\n"))
    (.setExecutable f true)
    (str f)))

(defn- run-command
  "The installed command run as an agent runs it: `sh -c` of it, the payload
  on stdin."
  [command payload]
  (fx/sh {:in payload :out :string :err :string} "sh" "-c" command))

(def ^:private payload
  ;; quotes, a newline in a string, a non-ASCII character and a backslash:
  ;; what passing through the shell and curl must leave as it is
  "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"echo 'hi' \\\"there\\\"\\nls \\\\ é\"},\"cwd\":\"/p\"}")

(deftest the-command-asks-the-server-and-falls-back-to-byj
  (let [dir (fresh-dir "fallback")
        self (stand-in dir)
        sock (fresh-socket)
        log (str dir "/a log's dir")
        command (hs/client-command self log sock)]
    (is (str/starts-with? command "sh -c '") "one sh -c line, whoever runs it")
    (testing "nothing answers at the socket: byj answers, told where the server goes"
      (let [r (run-command command payload)]
        (is (zero? (:exit r)))
        (is (= "{\"from\":\"fallback\"}\n" (:out r)))
        (is (= payload (slurp (io/file dir "stdin"))) "the payload reaches the fallback whole")
        (is (= ["hook" "pretooluse" "--socket" sock "--log" log]
               (str/split-lines (slurp (io/file dir "args")))))))
    (testing "a server answers: it alone, with the payload and the log"
      (let [seen (atom nil)
            srv (hs/serve! {:socket sock :fingerprint (constantly :same)
                            :answer (fn [body log] (reset! seen [body log]) "{\"from\":\"server\"}")})]
        (try
          (io/delete-file (io/file dir "stdin") true)
          (let [r (run-command command payload)]
            (is (zero? (:exit r)))
            (is (= "{\"from\":\"server\"}\n" (:out r)))
            (is (= [payload log] @seen))
            (is (not (.exists (io/file dir "stdin"))) "the fallback did not run"))
          (finally ((:stop! srv))))))
    (io/delete-file sock true)))

(deftest the-server-says-nothing-for-no-opinion-and-refuses-what-it-does-not-serve
  (let [sock (fresh-socket)
        srv (hs/serve! {:socket sock :fingerprint (constantly :same) :answer (fn [_ _] nil)})
        curl (fn [& args] (:out (apply fx/sh {:out :string :err :string}
                                       "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}" "--unix-socket" sock args)))]
    (try
      (is (= "200" (curl "--data-binary" "{}" "http://byj/v1/pretooluse")))
      (is (= "" (:out (fx/sh {:out :string} "curl" "-sf" "--unix-socket" sock "--data-binary" "{}" "http://byj/v1/pretooluse"))))
      (is (= "200" (curl "http://byj/v1/ping")))
      (is (= "404" (curl "http://byj/v2/pretooluse")))
      (testing "a large payload sent after a 100-continue arrives whole"
        (let [seen (atom nil)
              big (str "{\"x\":\"" (str/join (repeat 300000 "abc")) "\"}")
              f (io/file (fresh-dir "big") "p.json")
              srv2-sock (fresh-socket)
              srv2 (hs/serve! {:socket srv2-sock :fingerprint (constantly :same) :answer (fn [b _] (reset! seen b) nil)})]
          (spit f big)
          (try
            (is (= "200" (:out (fx/sh {:out :string} "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}" "-H" "Expect: 100-continue"
                                      "--unix-socket" srv2-sock "--data-binary" (str "@" f) "http://byj/v1/pretooluse"))))
            (is (= big @seen))
            (finally ((:stop! srv2)) (io/delete-file srv2-sock true)))))
      (testing "one server to a socket"
        (is (= :live-owner (try (hs/serve! {:socket sock :answer (fn [_ _] nil)}) nil
                                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))
      (finally ((:stop! srv)) (io/delete-file sock true)))))

(deftest the-server-ends-when-idle-and-when-its-byj-is-upgraded
  (testing "idle"
    (let [sock (fresh-socket)
          srv (hs/serve! {:socket sock :fingerprint (constantly :same) :answer (fn [_ _] nil)
                          :idle-ms 200 :watch-ms 50})]
      (is (= :idle (deref (:done srv) 5000 :still-up)))
      (is (not (.exists (io/file sock))) "its socket is gone with it")))
  (testing "upgraded: a call that finds it so is sent to the fallback, the new byj, and the server ends"
    (let [dir (fresh-dir "upgraded")
          self (stand-in dir)
          sock (fresh-socket)
          version (atom 1)
          srv (hs/serve! {:socket sock :fingerprint #(deref version) :watch-ms 60000
                          :answer (fn [_ _] "{\"from\":\"server\"}")})
          command (hs/client-command self nil sock)]
      (try
        (is (= "{\"from\":\"server\"}\n" (:out (run-command command payload))))
        (reset! version 2)
        (is (= "{\"from\":\"fallback\"}\n" (:out (run-command command payload))))
        (finally ((:stop! srv)) (io/delete-file sock true)))))
  (testing "upgraded while no call comes: the watch ends it"
    (let [sock (fresh-socket)
          version (atom 1)
          srv (hs/serve! {:socket sock :fingerprint #(deref version) :watch-ms 50 :answer (fn [_ _] nil)})]
      (reset! version 2)
      (is (= :upgraded (deref (:done srv) 5000 :still-up))))))

(deftest the-byj-a-command-names-is-found-as-the-shell-finds-it
  (let [a (fresh-dir "path-a") b (fresh-dir "path-b")
        exe (io/file b "byj")]
    (spit exe "#!/bin/sh\n")
    (.setExecutable exe true)
    (spit (io/file a "byj") "not executable")
    (is (= (.getCanonicalPath exe) (str (hs/resolve-self "byj" (str a ":" b)))) "the first executable on the PATH")
    (is (nil? (hs/resolve-self "byj" a)))
    (is (= (.getCanonicalPath exe) (str (hs/resolve-self (str exe) ""))) "a path is itself")
    (let [before (hs/fingerprint "byj" b)]
      (spit exe "#!/bin/sh\n# a newer byj\n")
      (is (not= before (hs/fingerprint "byj" b)) "a new file at the same path is an upgrade"))))

(deftest a-request-head-is-read-as-curl-sends-it
  (is (= {:method "POST" :path "/v1/pretooluse"
          :headers {"host" "byj" "content-length" "12" "byj-log" "/a b:c"}}
         (hs/parse-head "POST /v1/pretooluse?x=1 HTTP/1.1\r\nHost: byj\r\nContent-Length: 12\r\nByj-Log: /a b:c"))
      "a header's value keeps its colons; the query is not the path")
  (is (nil? (hs/parse-head "garbage"))))
