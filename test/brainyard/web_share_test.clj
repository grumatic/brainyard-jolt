(ns brainyard.web-share-test
  "Sharing a session in the browser through ttyd (byj-gly.22): the command it
  builds, the credential, what decides the options, and the child that does
  not share again — all pure; the share itself is checked live (RFC-028)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.main :as main]
            [brainyard.web-share :as ws]))

(deftest the-ttyd-command-is-what-the-options-say
  (testing "the defaults: localhost, 7681, the origin checked, writable, auth"
    (is (= ["ttyd" "-i" "127.0.0.1" "-p" "7681" "-O" "-c" "by:pw" "-W" "--" "byj" "run"]
           (ws/build-ttyd-argv {:credential "by:pw" :child-argv ["byj" "run"]}))))
  (testing "read-only drops -W; a client cap, once, another bind and port"
    (is (= ["ttyd" "-i" "0.0.0.0" "-p" "0" "-O" "-c" "u:p" "-m" "3" "-o" "--" "x"]
           (ws/build-ttyd-argv {:bind "0.0.0.0" :port 0 :credential "u:p" :writable? false
                                :max-clients 3 :once? true :child-argv ["x"]})))))

(deftest auth-is-always-on
  (let [c (ws/resolve-credential {})]
    (is (= "by" (:user c)))
    (is (re-matches #"[0-9a-f]{12}" (:pass c)) "a password generated when none is given")
    (is (:generated? c)))
  (is (= {:user "alice" :pass "s3cret" :credential "alice:s3cret" :generated? false}
         (ws/resolve-credential {:user " alice " :pass "s3cret"}))))

(deftest a-flag-beats-its-variable-which-beats-the-default
  (let [env {"BY_WEB_PORT" "9000" "BY_WEB_READONLY" "1" "BY_WEB_BIND" "0.0.0.0"}]
    (is (= {:tmux? false :port 7681 :bind "127.0.0.1" :user nil :pass nil :writable? true :max-clients 0 :once? false}
           (ws/options {} (constantly nil))))
    (is (= [9000 "0.0.0.0" false] ((juxt :port :bind :writable?) (ws/options {} env))))
    (is (= [8080 "127.0.0.1"] ((juxt :port :bind) (ws/options {:web-port "8080" :web-bind "127.0.0.1"} env))))))

(deftest the-child-runs-the-session-and-does-not-share-again
  (is (ws/wanted? {:web true} (constantly nil)))
  (is (ws/wanted? {} {"BY_WEB" "1"}))
  (is (not (ws/wanted? {:web true} {"BY_WEB_CHILD" "1"})) "the child ttyd started")
  (is (not (ws/wanted? {} (constantly nil))))
  (testing "its arguments are the launcher's without the --web flags"
    (is (= ["-p" "mock" "-m" "x" "--inline"]
           (ws/strip-flags ["--web" "-p" "mock" "--web-port" "7799" "-m" "x" "--web-readonly" "--web-pass" "pw" "--inline"]))))
  (testing "this program: BY_WEB_SELF, else the binary it was started from, else the checkout's"
    (is (= ["bb" "tui"] (:argv (ws/self-argv {"BY_WEB_SELF" " bb tui "} "/x/byj"))))
    (is (= ["/home/me/.local/bin/byj"] (:argv (ws/self-argv {} "/home/me/.local/bin/byj"))))
    (is (= ["jolt" "-m" "brainyard.main"] (:argv (ws/self-argv {} "/opt/homebrew/bin/scheme"))))))

(deftest the-banner-says-how-to-reach-it-and-warns-past-localhost
  (let [cred {:user "by" :pass "abc" :generated? true}]
    (let [b (ws/banner {:bind "127.0.0.1" :port 7681 :writable? true} cred)]
      (is (str/includes? b "http://127.0.0.1:7681"))
      (is (str/includes? b "by / abc   (generated)"))
      (is (str/includes? b "writable · a fresh session for each viewer (--web-tmux shares one) · localhost only")
          "ttyd starts its command once per connection: two viewers, two sessions (measured)")
      (is (str/includes? b "ssh -L 7681:127.0.0.1:7681")))
    (let [b (ws/banner {:bind "0.0.0.0" :port 80 :writable? false :tmux? true} cred {:attach "tmux -L s attach -t brainyard"})]
      (is (str/includes? b "read-only · one session, shared (tmux) · bound to 0.0.0.0"))
      (is (str/includes? b "⚠ bound beyond localhost"))
      (is (str/includes? b "Local:  tmux -L s attach -t brainyard")))))

(deftest the-flags-are-parsed
  (is (= {:web true :web-port "7799" :web-readonly true :web-pass "pw"}
         (select-keys (main/parse-args ["run" "--web" "--web-port" "7799" "--web-readonly" "--web-pass" "pw"])
                      [:web :web-port :web-readonly :web-pass]))))
