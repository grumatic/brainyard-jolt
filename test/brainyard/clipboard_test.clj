(ns brainyard.clipboard-test
  "Copying to the user's clipboard — brainyard's clipboard.clj, ported."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.clipboard :as clipboard]))

(deftest osc52-carries-utf8-as-base64
  (is (= "\u001b]52;c;aMOpIPCfmIA=\u0007" (clipboard/osc52 "hé 😀")))
  (testing "the source limit is whole base64 groups inside xterm's 74994"
    (is (<= (* 4 (/ clipboard/osc52-max-source-bytes 3)) 74994))))

(deftest a-copy-that-cannot-happen-says-why
  (is (= {:ok? false :via nil :bytes 0 :verified? false :detail "nothing to copy"} (clipboard/copy! "  ")))
  (let [big (apply str (repeat (inc clipboard/osc52-max-source-bytes) "x"))
        piped (atom [])]
    (with-redefs [clipboard/tmux? (constantly false)
                  clipboard/remote? (constantly false)
                  clipboard/native-argv (constantly ["pbcopy"])
                  clipboard/pipe! (fn [argv _] (swap! piped conj argv) true)]
      (testing "the size limit is OSC 52's: a native copy takes any size (byj-gly.213)"
        (is (= {:ok? true :via :pbcopy :bytes (count big) :verified? true} (clipboard/copy! big)))))
    (with-redefs [clipboard/tmux? (constantly false)
                  clipboard/remote? (constantly true)]
      (let [r (clipboard/copy! big)]
        (is (false? (:ok? r)))
        (is (str/starts-with? (:detail r) "too large for the terminal's clipboard"))))
    (testing "a tmux without -w fills its own buffer, and does not say copied"
      (with-redefs [clipboard/tmux? (constantly true)
                    clipboard/pipe! (fn [argv _] (not (some #{"-w"} argv)))]
        (let [r (clipboard/copy! "hi")]
          (is (= :tmux-buffer (:via r)))
          (is (false? (:verified? r)))
          (is (not (str/starts-with? (clipboard/describe r) "copied"))))))))

(deftest describe-says-copied-only-when-it-knows
  (is (= "copied 5 bytes · pbcopy" (clipboard/describe {:ok? true :via :pbcopy :bytes 5 :verified? true})))
  (is (= "sent 5 bytes to the terminal's clipboard · osc52"
         (clipboard/describe {:ok? true :via :osc52 :bytes 5 :verified? false})))
  (is (= "not copied: nothing to copy" (clipboard/describe {:ok? false :detail "nothing to copy"}))))
