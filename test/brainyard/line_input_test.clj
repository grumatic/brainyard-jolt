;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.line-input-test
  "The inline session's line editing (byj-gly.315.12): what each key does to
  the line and what it echoes, with no terminal."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.line-input :as li]))

(defn- keys->
  "Feed `ks` (strings, one key each) through `step`: the final state and all
  that was echoed."
  [ks]
  (reduce (fn [[st out] k] (let [[st' e] (li/step st k)] [st' (str out e)])) [{:buf []} ""] ks))

(defn- chars-of [s] (map str (seq s)))

(deftest keys-edit-the-line
  (testing "typing, and return ends the line"
    (let [[st out] (keys-> (concat (chars-of "hello") ["\r"]))]
      (is (= "hello" (:line st)))
      (is (= "hello\n" out))))
  (testing "a line far past MAX_CANON (1024) is whole"
    (is (= 1500 (count (:line (first (keys-> (concat (repeat 1500 "y") ["\n"]))))))))
  (testing "backspace takes the last character back, by its width on screen"
    (let [[st out] (keys-> (concat (chars-of "abcX") ["\u007f"] (chars-of "d") ["\r"]))]
      (is (= "abcd" (:line st)))
      (is (= "abcX\b \bd\n" out)))
    (let [[st out] (keys-> (concat (chars-of "한글") ["\u007f" "\r"]))]
      (is (= "한" (:line st)))
      (is (= "한글\b \b\b \b\n" out) "a Hangul syllable is two columns"))
    (is (= "" (:line (first (keys-> ["\u007f" "\r"])))) "nothing to take back"))
  (testing "ctrl-U clears the line; ctrl-W takes back a word"
    (is (= "new" (:line (first (keys-> (concat (chars-of "old") ["\u0015"] (chars-of "new") ["\r"]))))))
    (is (= "one " (:line (first (keys-> (concat (chars-of "one two ") ["\u0017" "\r"])))))))
  (testing "an arrow key's escape sequence is swallowed, not typed"
    (is (= "ab" (:line (first (keys-> ["a" "\u001b" "[" "D" "b" "\r"])))))
    (is (= "ab" (:line (first (keys-> ["a" "\u001b" "O" "A" "b" "\r"]))))))
  (testing "ctrl-D on an empty line is the end of input; on a typed one it is nothing"
    (is (:eof (first (keys-> ["\u0004"]))))
    (is (= "x" (:line (first (keys-> ["x" "\u0004" "\r"]))))))
  (testing "ctrl-Q is the end of input on any line (byj-j5v)"
    (is (:eof (first (keys-> ["\u0011"]))))
    (is (:eof (first (keys-> ["x" "\u0011"])))))
  (testing "another control character is not text"
    (is (= "ab" (:line (first (keys-> ["a" "\u0007" "b" "\r"])))))))

(deftest a-parked-turn-wakes-an-idle-prompt
  ;; byj-gly.315.15: through a reader, .ready was true before anything was
  ;; typed, so the prompt never polled, and a timer's turn waited for the
  ;; person's next line. Driven in tmux, a real terminal.
  (when (zero? (:exit (fx/sh {:out :string :err :string} "sh" "-c" "command -v tmux")))
    (let [label (str "byj-wake-" (random-uuid))
          ;; outside this repository: inside it, the session's project is this
          ;; repository, and its channel socket lands in its .brainyard/sessions
          dir (.getCanonicalPath (doto (java.io.File. (System/getProperty "java.io.tmpdir") (str "byj-line-input-" (System/nanoTime))) .mkdirs))
          tmux (fn [& args] (apply fx/sh {:out :string :err :string :env (dissoc (into {} (System/getenv)) "TMUX")}
                                   "tmux" "-L" label args))
          screen #(:out (tmux "capture-pane" "-p" "-J" "-S" "-" "-t" "t"))
          until (fn [pred ms] (loop [t 0] (cond (pred (screen)) true (> t ms) false :else (do (Thread/sleep 250) (recur (+ t 250))))))]
      (try
        (tmux "new-session" "-d" "-s" "t" "-x" "200" "-y" "50"
              (str "jolt run:byj -p mock --inline --permission-mode auto-approve --no-persist -C " dir "; sleep 60"))
        (is (until #(str/includes? % "you>") 30000) "the session is up")
        (tmux "send-keys" "-t" "t" "-l" ":task$wakeup {:delay-ms 1500 :note \"timer\"}")
        (tmux "send-keys" "-t" "t" "Enter")
        (is (until #(str/includes? % "(mock) [task-complete]") 20000)
            "the parked turn ran with nothing typed")
        (testing "and a line typed after it is read as before"
          (tmux "send-keys" "-t" "t" "-l" "abcX")
          (tmux "send-keys" "-t" "t" "BSpace")
          (tmux "send-keys" "-t" "t" "-l" "d")
          (tmux "send-keys" "-t" "t" "Enter")
          (is (until #(str/includes? % "(mock) abcd") 10000)))
        (finally (tmux "kill-server"))))))

(deftest bytes-are-read-as-utf-8
  (is (= [1 2 3 4 1] (map li/utf8-length [0x61 0xC3 0xED 0xF0 0x80])) "a stray continuation byte is taken alone")
  (is (= "한" (li/decode [0xED 0x95 0x9C])))
  (is (= "é" (li/decode [0xC3 0xA9])))
  (is (= "a" (li/decode [0x61]))))
