;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.cli-help
  "The `byj` CLI's help, in brainyard's `by` layout (its cli-matic
  pages): `byj -h` names the commands, `byj COMMAND -h` a command's
  subcommands, grouped, or its options, and `byj COMMAND SUB -h` one
  subcommand's arguments and what it does.

  The words are data, `resources/brainyard/cli/help.edn`; this lays them out,
  a description wrapped to the width under its own column."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def spec (delay (edn/read-string (slurp (io/resource "brainyard/cli/help.edn")))))

(def ^:dynamic *spec*
  "The words laid out: byj's, or another program's CLI over byj's that binds
  its own, in the same layout."
  spec)

(def width
  "Where a description wraps: a terminal's 100 columns, less a margin."
  96)

(defn- wrap
  "`s` as lines of at most `w` characters, broken at spaces — never inside
  brackets, so an option and its argument stay together, `[--lm LM]`."
  [s w]
  (loop [words (re-seq #"\[[^\]]*\]\S*|\S+" (str s)) line "" out []]
    (if-let [x (first words)]
      (cond (str/blank? line) (recur (rest words) x out)
            (<= (+ (count line) 1 (count x)) w) (recur (rest words) (str line " " x) out)
            :else (recur (rest words) x (conj out line)))
      (if (str/blank? line) out (conj out line)))))

(defn- hang
  "`head` then `s`, wrapped, its later lines under where `s` starts: a long
  usage reads as one, its options not under the command."
  [head s]
  (let [pad (apply str (repeat (inc (count head)) " "))
        ls (wrap s (max 30 (- width (count pad))))]
    (cons (str/trimr (str head " " (first ls))) (map #(str pad %) (rest ls)))))

(defn- rows
  "Two columns: each `[left right]` with the right wrapped beside a left
  padded to the widest left (up to `cap`); a left wider than that has its
  description start on the next line."
  [pairs cap]
  (let [lw (min cap (reduce max 0 (map (comp count first) pairs)))
        pad (apply str (repeat (+ 3 lw 2) " "))]
    (mapcat (fn [[l r]]
              (let [rs (wrap r (max 30 (- width (count pad))))]
                (if (> (count l) lw)
                  (cons (str "   " l) (map #(str pad %) rs))
                  (cons (str "   " l (apply str (repeat (- lw (count l)) " ")) "  " (first rs))
                        (map #(str pad %) (rest rs))))))
            pairs)))

(defn- options-lines [ks]
  ;; a long-only flag under the others' long names, as `by` lines them up
  (rows (for [k ks :let [{:keys [flags doc]} (get-in @*spec* [:options k])] :when flags]
          [(if (str/starts-with? flags "--") (str "    " flags) flags) doc])
        30))

(defn- command [name]
  (some #(when (or (= name (:name %)) (some #{name} (:aliases %))) %) (:commands @*spec*)))

(defn- sub-of [cmd name]
  (some #(when (= name (:name %)) %) (mapcat :subs (:subcommands cmd))))

(defn- page [& sections]
  (str/join "\n" (concat (apply concat (interpose [""] (remove empty? sections))) [""])))

(defn top
  "`byj -h`: the commands, and the options every command takes."
  [version]
  (let [{:keys [name summary usage commands global]} @*spec*]
    (page (cons "NAME:" (hang (str " " name " -") summary))
          ["USAGE:" (str " " usage)]
          (when version ["VERSION:" (str " " version)])
          (cons "COMMANDS:" (rows (for [c commands] [(:name c) (:summary c)]) 12))
          (cons "GLOBAL OPTIONS:" (options-lines global))
          [(str " " name " COMMAND -h shows a command's subcommands and options.")])))

(defn command-page
  "`byj COMMAND -h`: its subcommands, grouped, or its options."
  [cmd]
  (let [{:keys [name]} @*spec*
        {:keys [summary usage subcommands options]} cmd
        full (str name " " (:name cmd))]
    (if (seq subcommands)
      (page (cons "NAME:" (hang (str " " full " -") summary))
            ["USAGE:" (str " " full " SUBCOMMAND [arguments…] [options]")]
            (apply concat
                   (interpose [""]
                              (for [{:keys [group subs]} subcommands]
                                (cons (if group (str (str/upper-case group) ":") "COMMANDS:")
                                      (rows (for [s subs] [(:name s) (:summary s)]) 18)))))
            [(str " " full " SUBCOMMAND -h shows its arguments.")])
      (page (cons "NAME:" (hang (str " " full " -") summary))
            ["USAGE:" (str " " (or usage (str full " [options]")))]
            (cons "OPTIONS:" (options-lines (concat options (:global @*spec*))))))))

(defn sub-page
  "`byj COMMAND SUB -h`: its arguments, and what it does in full."
  [cmd sub]
  (let [full (str (:name @*spec*) " " (:name cmd) " " (:name sub))]
    (page (cons "NAME:" (hang (str " " full " -") (:summary sub)))
          (cons "USAGE:" (mapcat #(hang (str " " full) %) (str/split (str (:args sub)) #" \| ")))
          (when (:doc sub) (cons "DESCRIPTION:" (map #(str " " %) (wrap (:doc sub) (dec width))))))))

(defn text
  "The help `args` ask for: the words before the first option name a command
  and a subcommand; the top page where they name none, and the command's
  page, with a line saying so, where the subcommand is not one of its own."
  [args version]
  (let [[c s] (take-while #(not (str/starts-with? % "-")) args)
        cmd (some-> c command)
        sub (when (and cmd s) (sub-of cmd s))]
    (cond sub (sub-page cmd sub)
          (and cmd s (seq (:subcommands cmd))) (str "no subcommand " s " in " (:name cmd) "\n\n" (command-page cmd))
          cmd (command-page cmd)
          :else (top version))))

(defn subcommands
  "Every subcommand the help names, by command: what a test holds the
  dispatch to."
  []
  (into {} (for [c (:commands @*spec*) :when (:subcommands c)]
             [(:name c) (set (map :name (mapcat :subs (:subcommands c))))])))
