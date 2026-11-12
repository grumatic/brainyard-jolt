;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.auth-test
  "/login and /logout (RFC-006, byj-gly.311): the targets, where each stands,
  and what signing in does — on a stand-in claude and codex on a PATH of the
  test's own, the terminal \"handed over\" to a fn that runs the command."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.auth :as auth]
            [brainyard.effect :as fx]))

(defn- fresh-dir [name]
  (let [d (io/file "target/auth-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- tool! [bin name script]
  (let [f (io/file bin name)]
    (spit f (str "#!/bin/bash\n" script "\n"))
    (.setExecutable f true)))

(defn- fake-clis
  "claude: `auth status --json` says loggedIn as a marker file has it, `auth
  login` makes the marker, `auth logout` removes it. codex: `login status`
  exits 0 only when signed in."
  [bin]
  (tool! bin "claude" (str "M=" bin "/claude.on\n"
                           "case \"$1 $2\" in\n"
                           "  'auth status') [ -f $M ] && echo '{\"loggedIn\": true}' || echo '{\"loggedIn\": false}';;\n"
                           "  'auth login') touch $M;;\n"
                           "  'auth logout') rm -f $M;;\n"
                           "esac"))
  (tool! bin "codex" (str "M=" bin "/codex.on\n"
                          "case \"$1 $2\" in\n"
                          "  'login status') [ -f $M ];;\n"
                          "  'login ') touch $M;;\n"
                          "esac")))

(deftest the-targets-are-the-clis-and-the-api-key-providers
  (let [ids (set (map :id (auth/targets)))]
    (is (every? ids ["claude" "codex" "gh" "bedrock"]) "the CLIs, from targets.edn")
    (is (every? ids ["anthropic" "openai" "openrouter"]) "every provider that reads an API key")
    (is (not (ids "claude-code")) "a provider with a login of its own is not an API-key target"))
  (testing "found by id or alias, any case"
    (is (= "claude" (:id (auth/find-target "Claude-Code"))))
    (is (= "gh" (:id (auth/find-target "github"))))
    (is (nil? (auth/find-target "nope"))))
  (testing "aws is asked for the profile in the environment"
    (is (= ["aws" "sso" "login" "--profile" "dev"]
           (auth/argv (auth/find-target "bedrock") :login {"AWS_PROFILE" "dev"})))
    (is (= ["aws" "sso" "login"] (auth/argv (auth/find-target "bedrock") :login {})))))

(deftest signing-in-runs-the-clis-own-login-with-the-terminal
  (let [bin (fresh-dir "bin")
        ran (atom [])
        ;; the front end's seam: here, the command run on the test's PATH
        hand (fn [argv _env] (swap! ran conj argv)
               (apply fx/sh {:out :string :err :string} (str bin "/" (first argv)) (rest argv)))
        opts {:path bin :env {} :hand-terminal hand}
        claude (auth/find-target "claude")]
    (fake-clis bin)
    (testing "where each stands, by the CLI's own word"
      (is (= :not-signed-in (auth/status claude opts)))
      (is (= :cli-missing (auth/status (auth/find-target "gh") opts)))
      (is (= :signed-in (auth/status (auth/find-target "openai") {:env {"OPENAI_API_KEY" "sk-x"}})))
      (is (= :not-signed-in (auth/status (auth/find-target "openai") {:env {}}))))
    (testing "/login claude: its login run with the terminal, and where it stands after"
      (is (= "claude: claude auth login ran — now signed in." (auth/login! claude opts)))
      (is (= [["claude" "auth" "login"]] @ran)))
    (testing "signed in already: said, nothing run"
      (reset! ran [])
      (is (str/starts-with? (auth/login! claude opts) "Already signed in to claude."))
      (is (empty? @ran)))
    (testing "/logout claude: its logout run"
      (is (= "claude: claude auth logout ran — now not signed in." (auth/logout! claude opts))))
    (testing "codex by exit status"
      (is (= "codex: codex login ran — now signed in." (auth/login! (auth/find-target "codex") opts))))
    (testing "no terminal to hand over (headless): the command is said, not run"
      (reset! ran [])
      (is (= "Run: claude auth login" (auth/login! claude (dissoc opts :hand-terminal))))
      (is (empty? @ran)))
    (testing "a CLI that is not installed is said how to install"
      (is (str/starts-with? (auth/login! (auth/find-target "gh") opts) "gh is not installed")))
    (testing "an API key is said where to put, never written, never read out"
      (let [said (auth/login! (auth/find-target "openai") {:env {} :hand-terminal hand})]
        (is (str/includes? said "OPENAI_API_KEY"))
        (is (str/includes? said "~/.brainyard/.env"))
        (is (str/includes? said "Nothing here writes a key")))
      (is (not (str/includes? (auth/overview {:path bin :env {"OPENAI_API_KEY" "sk-secret"}}) "sk-secret"))))
    (testing "the overview: every target, where it stands"
      (let [o (auth/overview {:path bin :env {}})]
        (is (re-find #"(?m)^  claude +not signed in" o))
        (is (re-find #"(?m)^  codex +signed in" o))
        (is (re-find #"(?m)^  gh +not installed" o))))))
