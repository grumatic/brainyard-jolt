;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.cli-help-test
  (:require [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.cli-help :as cli-help]
            [brainyard.main :as main]))

(defn- main-forms
  "Every top-level form of `brainyard.main`, read as data."
  []
  (with-open [r (java.io.PushbackReader. (io/reader "src/brainyard/main.clj"))]
    (binding [*read-eval* false]
      (loop [out []]
        (let [f (read {:eof ::eof} r)]
          (if (= ::eof f) out (recur (conj out f))))))))

(defn- dispatched
  "The subcommands a `*-command` fn's own `case` on its subcommand handles."
  [forms fn-name]
  (let [body (some #(when (and (seq? %) (= fn-name (second %))) %) forms)
        cases (filter #(and (seq? %) (= 'case (first %))
                            (contains? #{'sub '(str sub)} (second %)))
                      (tree-seq coll? seq body))
        c (first cases)]
    (set (for [[test _] (partition 2 (drop 2 c))
               s (if (seq? test) test [test])
               :when (string? s)]
           s))))

(deftest every-dispatched-subcommand-has-help
  ;; the help was a string by hand, and it said `flow` had subcommands its
  ;; reader could not tell from the rest; every one is a line of data now
  (let [forms (main-forms)
        helped (cli-help/subcommands)]
    ;; eval, exam and app are Share's (RFC-070 D4), byj-share's
    (doseq [[cmd f] {"programs" 'programs-command
                     "flow" 'flow-command "session" 'session-command
                     "emacs" 'emacs-command}]
      (testing cmd
        (let [d (apply clojure.set/union (map #(dispatched forms %) (if (vector? f) f [f])))]
          (is (seq d) "the dispatch was found")
          (is (= d (get helped cmd)) "the help names what the dispatch handles, no more"))))))

(deftest every-flag-the-help-names-is-parsed
  ;; byj-gly.438: programs eval's usage named --lm, which parse-args did not
  ;; know, so the flag the help offered was refused as an unknown option. A
  ;; usage line or an option's flags only: a :doc may name another program's
  (let [flags (->> (concat (for [c (:commands @cli-help/spec)
                                 s (cons (:usage c) (map :args (mapcat :subs (:subcommands c))))]
                             s)
                           (map :flags (vals (:options @cli-help/spec))))
                   (mapcat #(re-seq #"--[a-z][a-z0-9-]*" (str %)))
                   distinct)]
    (is (< 50 (count flags)) "the flags were found")
    (doseq [f flags]
      (is (not-any? #(str/includes? % "unknown option") (:errors (main/parse-args ["ask" f "x" "y"])))
          (str f " is named in the help but not parsed")))))

(deftest the-pages-are-brainyards-layout
  (testing "the top page names the commands, and no subcommand"
    (let [t (cli-help/text ["-h"] "v0.0.0")]
      (is (str/starts-with? t "NAME:\n byj - "))
      (doseq [s ["USAGE:" "VERSION:\n v0.0.0" "COMMANDS:" "GLOBAL OPTIONS:"]] (is (str/includes? t s)))
      (doseq [c ["ask" "run" "session" "agent" "programs" "flow" "update"]]
        (is (re-find (re-pattern (str "(?m)^   " c " ")) t)))
      (is (not (str/includes? t "undo-turn")) "no subcommand on the top page")
      (doseq [c ["eval" "exam" "app" "file"]]
        (is (not (re-find (re-pattern (str "(?m)^   " c " ")) t)) (str c " is Share's (RFC-070 D4)")))
      ;; a screen, not 130 lines: a line a command, `turns` the newest
      ;; (byj-gly.483, after `emacs`, byj-gly.462, `file`, byj-1wv.70.3,
      ;; `acp`, byj-g5n.4, and `agent`, byj-1wv.47), and
      ;; -p, -m and -C among the global options since they stand before the
      ;; command (byj-gly.420)
      (is (< (count (str/split-lines t)) 44) "a screen, not 130 lines")))
  (testing "a command with subcommands lists them"
    (let [t (cli-help/text ["turns" "-h"] nil)]
      (is (str/includes? t "COMMANDS:"))
      (is (re-find #"(?m)^   undo-turn +take back what one turn changed" t))
      (is (not (str/includes? t "OPTIONS:")))))
  (testing "a command without them shows its options, a long-only flag under the long names"
    (let [t (cli-help/text ["run" "-h"] nil)]
      (is (re-find #"(?m)^   -p, --provider NAME +openai" t))
      (is (re-find #"(?m)^       --resume-latest +continue" t))
      (is (not (str/includes? t "--question")) "ask's own options are not run's")))
  (testing "a subcommand: its forms, and what it does in full"
    (let [t (cli-help/text ["turns" "fork" "x" "--json" "-h"] nil)]
      (is (str/includes? t " byj turns fork SESSION [TURN [keep-files]] [--json]"))
      (is (str/includes? t "DESCRIPTION:"))
      (is (str/includes? t ":forked-from naming SESSION"))))
  (testing "an old name shows the command's page under its new one (byj-gly.435)"
    (is (str/includes? (cli-help/text ["sessions" "-h"] nil) " byj session SUBCOMMAND"))
    (is (str/includes? (cli-help/text ["agents" "-h"] nil) " byj agent [--name NAME] [--json]"))
    (is (str/includes? (cli-help/text ["sessions" "list" "-h"] nil) " byj session list [--live]")))
  (testing "a subcommand that is not one: said, then the command's page"
    (is (str/starts-with? (cli-help/text ["turns" "nosuch" "-h"] nil) "no subcommand nosuch in turns")))
  (testing "no line wider than a terminal"
    (doseq [args [["-h"] ["turns" "-h"] ["run" "-h"] ["ask" "-h"] ["turns" "rewind" "-h"] ["programs" "eval" "-h"]]
            l (str/split-lines (cli-help/text args "v0.2.0-110-gf08c23d (f08c23d, main)"))]
      (is (<= (count l) 100) (str (pr-str args) ": " l)))))
