(ns brainyard.dev.no-tty-spike
  "Measurements behind RFC-009's decision on subprocesses and the terminal
  (byj-gly.296): can a child be started with NO controlling terminal on macOS,
  which has no setsid(1), so that nothing it runs — sudo, gpg's pinentry, ssh
  without BatchMode — can open /dev/tty and ask the person over the TUI
  (provenance P-32)? And what does that cost?

      tmux new-session 'jolt -A:dev -m brainyard.dev.no-tty-spike'

  It must run with a terminal — that is what is being taken away — so in a tmux
  pane, or a terminal, not a pipe. Prints one EDN map.

  The candidate: start the child through `perl -e 'setsid(); exec …'` —
  /usr/bin/perl ships with macOS — so the child is the leader of a new session
  with no terminal, and is the same process (exec keeps its pid).

  - `:tty`        the child opens /dev/tty, plain and wrapped
  - `:sudo`       `sudo -k true`: asks, or fails at once — and how long it took
  - `:pipes`      stdin given as a string comes back on stdout, wrapped
  - `:cancel`     a `process-task` over a tree of sleeps, cancelled: is any of it
                  left, plain and wrapped
  - `:cost-ms`    the median time of 30 runs of `true`, plain and wrapped
  - `:sid`        whether the wrapped child really leads a session of its own"
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.effect :as fx]))

(def wrapper
  "The command a child is started through to have no controlling terminal."
  ["perl" "-e" "use POSIX qw(setsid); setsid() >= 0 or die \"setsid: $!\\n\"; exec {$ARGV[0]} @ARGV or die \"exec $ARGV[0]: $!\\n\"" "--"])

(defn- run [wrapped? opts & cmd]
  (apply fx/sh (merge {:out :string :err :string :interactive? true} opts)
         (concat (when wrapped? wrapper) cmd)))

(defn- tty? [wrapped?]
  (str/trim (:out (run wrapped? {} "sh" "-c" "if (exec </dev/tty) 2>/dev/null; then echo opens; else echo none; fi"))))

(defn- timed [f]
  (let [t0 (System/nanoTime) r (f)] [r (quot (- (System/nanoTime) t0) 1000000)]))

(defn- sudo [wrapped?]
  ;; -k: ignore any cached credential for this one command, without clearing it
  (let [[r ms] (timed #(run wrapped? {:in ""} "sudo" "-k" "true"))]
    {:exit (:exit r) :ms ms :said (first (str/split-lines (str (:err r))))}))

(defn- marker [] (str "byj-notty-" (System/nanoTime)))

(defn- left-after-cancel [wrapped?]
  (let [m (marker)
        ;; a shell with two shells under it, each holding a sleep: three
        ;; processes carry the marker in their argv (a trailing `true` keeps
        ;; each inner shell from exec'ing its sleep)
        inner (str "sh -c 'sleep 300; true " m "'")
        cancel (fx/run (apply fx/process-task {:out :string :err :string :interactive? true}
                              (concat (when wrapped? wrapper)
                                      ["sh" "-c" (str inner " & " inner "; wait; true " m)]))
                       (fn [_]) (fn [_]))
        _ (Thread/sleep 800)
        before (count (str/split-lines (str/trim (:out (fx/sh {:out :string} "pgrep" "-f" m)))))]
    (cancel)
    (Thread/sleep 800)
    (let [after (str/trim (:out (fx/sh {:out :string} "pgrep" "-f" m)))]
      (fx/sh {:out :string} "pkill" "-f" m)
      {:running-before before :left-after (if (str/blank? after) 0 (count (str/split-lines after)))})))

(defn- median [xs] (nth (sort xs) (quot (count xs) 2)))

(defn- cost [wrapped?]
  (median (for [_ (range 30)] (second (timed #(run wrapped? {} "true"))))))

(defn run-spike []
  {:tty {:plain (tty? false) :wrapped (tty? true)}
   :sid {:plain (str/trim (:out (run false {} "sh" "-c" "ps -o sess= -o tty= -p $$")))
         :wrapped (str/trim (:out (run true {} "sh" "-c" "ps -o sess= -o tty= -p $$")))}
   :sudo {:wrapped (sudo true)}
   :pipes (:out (run true {:in "through the wrapper\n"} "cat"))
   :cancel {:plain (left-after-cancel false) :wrapped (left-after-cancel true)}
   :cost-ms {:plain (cost false) :wrapped (cost true)}})

(defn -main [& _]
  (pp/pprint (run-spike))
  (shutdown-agents))
