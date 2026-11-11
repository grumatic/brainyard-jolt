(ns brainyard.terminal-test
  "The right rail's Terminal view (byj-gly.412): the catalogue and its layers,
  and programs started in tmux, found again by a later view, opened and
  stopped. Against a tmux server of the test's own (`tmux -L`), never the
  person's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent-host :as ah]
            [brainyard.claude-host :as host]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.permission :as permission]
            [brainyard.terminal :as terminal]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-terminal-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(deftest the-catalogue-is-data-in-layers
  (let [dir (fresh-dir "catalogue")
        home (fresh-dir "home")]
    (.mkdirs (io/file home ".brainyard"))
    (spit (io/file home ".brainyard" "terminal.edn") "[{:name \"htop\" :hidden true}]")
    (.mkdirs (io/file dir ".brainyard"))
    (spit (io/file dir ".brainyard" "terminal.edn")
          "[{:name \"emacs\" :command [\"emacs\" \"-nw\" \"-Q\"]} {:name \"repl\" :command [\"python3\"] :description \"A Python REPL\"}]")
    (let [c (terminal/catalogue dir {:home home})
          names (mapv :name c)]
      (is (= "shell" (first names)) "the shipped entries, in order")
      (is (= ["emacs" "-nw" "-Q"] (:command (first (filter #(= "emacs" (:name %)) c)))) "a project's replaces one by name, in place")
      (is (= "Emacs, a frame on the project's own daemon" (:description (first (filter #(= "emacs" (:name %)) c)))) "keeping what it does not say")
      (is (= "repl" (last names)) "a new one goes last")
      (is (not (some #{"htop"} names)) "and :hidden takes one away")))
  (is (= ["/bin/zsh" "-c" "x"] (terminal/expand ["${SHELL:-/bin/sh}" "-c" "x"] {"SHELL" "/bin/zsh"})))
  (is (= ["/bin/sh"] (terminal/expand ["${SHELL:-/bin/sh}"] {}))))

(deftest a-programs-session-is-named-for-its-project
  (let [a (terminal/session-name "/work/my app" "emacs")]
    (is (re-matches #"byj-term-my-app-[0-9a-f]{4}-emacs" a) "made safe for tmux")
    (is (not= a (terminal/session-name "/other/my app" "emacs")) "two projects of one name differ")))

(deftest programs-outlive-the-view-that-started-them
  (let [label (str "byj-term-test-" (System/nanoTime))
        dir (fresh-dir "tmux")
        entry {:name "sleeper" :command ["sleep" "300"]}]
    (binding [ah/*tmux* ["tmux" "-L" label]]
      (try
        (is (= {} (terminal/running dir)) "nothing yet, and no server")
        (is (nil? (terminal/start! dir entry)))
        (testing "a later reader finds it again by its name, with when it started"
          (let [r (terminal/running dir)]
            (is (= ["sleeper"] (keys r)))
            (is (pos? (:since (get r "sleeper"))))))
        (testing "another project's programs are not this one's"
          (is (= {} (terminal/running (fresh-dir "other")))))
        (testing "opening it attaches to its session"
          (is (= ["tmux" "-L" label "attach-session" "-t" (terminal/session-name dir "sleeper")]
                 (:argv (terminal/attach-plan dir entry true nil)))))
        (testing "the view: Enter opens it, ctrl+x twice stops it"
          (let [handed (atom nil)
                said (atom [])
                ;; outside tmux whatever runs the suite (byj-gly.455)
                {:keys [view state]} (terminal/views dir {:hand! (fn [argv _] (reset! handed argv))
                                                         :say! #(swap! said conj %)
                                                         :pane (constantly nil)
                                                         :prefix (constantly nil)})]
            (reset! state {:entries [entry] :tmux? true :installed #{"sleeper"} :running (terminal/running dir)})
            (let [row (first (filter :running? ((:items view))))]
              (is (= "  ● sleeper" (:label row)))
              ((:act view) row)
              (is (= "attach-session" (nth @handed 3)) "the terminal handed to the attach")
              (is ((:on-key view) row {:type :ctrl :ch \x}))
              (is (= ["ctrl+x again stops sleeper"] @said) "once arms it")
              (is (contains? (terminal/running dir) "sleeper") "and stops nothing")
              ((:on-key view) row {:type :ctrl :ch \x})
              (is (= "stopped sleeper" (last @said)))
              (is (= {} (terminal/running dir)) "twice stops it")
              (is (nil? ((:on-key view) row {:type :char :ch \q})) "another key is not the view's"))))
        (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server"))))))

(deftest emacs-is-a-frame-on-the-projects-own-daemon
  ;; byj-gly.451: emacsclient, so the buffers outlive the frame and a file
  ;; can be opened in it without the terminal
  (let [dir (fresh-dir "emacs")
        emacs (first (filter #(= "emacs" (:name %)) (terminal/catalogue dir {:home (fresh-dir "home")})))
        sock (str "byj-" (terminal/project-tag dir))]
    (is (= ["emacsclient" "-t" "-a" "" "-s" sock] (terminal/expand (:command emacs) (terminal/vars dir)))
        "-a '' starts the daemon, named for the project, when it is not up")
    (is (= ["emacsclient" "-n" "-s" sock "+12:1" "/p/a.clj"]
           (terminal/expand (:open-file emacs) (terminal/vars dir {"FILE" "/p/a.clj" "LINE" "12" "COLUMN" nil})))
        "a column not given is the first"))
  (testing "what an entry reads: what it is given, then the project's tag, then the environment"
    (let [dir (fresh-dir "vars")]
      (is (= (terminal/project-tag dir) ((terminal/vars dir) "BYJ_PROJECT")))
      (is (= "x" ((terminal/vars dir {"BYJ_PROJECT" "x"}) "BYJ_PROJECT")))
      (is (nil? ((terminal/vars dir {"HOME" nil}) "HOME")) "given as nothing is nothing, never the environment's")
      (is (= (System/getenv "HOME") ((terminal/vars dir) "HOME"))))))

(deftest a-file-opens-in-a-running-program-that-says-how
  ;; byj-gly.451: a clicked path goes to the running program, the terminal
  ;; left to byj; with none running the caller opens it its own way
  (let [label (str "byj-term-test-" (System/nanoTime))
        dir (fresh-dir "open-file")
        home (fresh-dir "home")
        opened (io/file dir "opened.txt")]
    (.mkdirs (io/file dir ".brainyard"))
    (spit (io/file dir ".brainyard" "terminal.edn")
          (pr-str [{:name "rec" :command ["sleep" "300"]
                    :open-file ["sh" "-c" "printf '%s' \"$1\" > opened.txt" "sh" "${FILE}:${LINE:-1}:${COLUMN:-1}"]}
                   {:name "broken" :command ["sleep" "300"] :open-file ["sh" "-c" "echo no >&2; exit 3"]}]))
    (binding [ah/*tmux* ["tmux" "-L" label]]
      (try
        (is (nil? (terminal/open-file! dir "/p/a.clj" 12 nil {:home home})) "nothing runs: not this one's to open")
        (is (nil? (terminal/start! dir {:name "rec" :command ["sleep" "300"]})))
        (testing "running, it opens there, in the project directory, and says so"
          (is (= "opened /p/a.clj:12 in rec" (terminal/open-file! dir "/p/a.clj" 12 nil {:home home})))
          (is (= "/p/a.clj:12:1" (slurp opened))))
        (testing "with no line, the first"
          (terminal/open-file! dir "/p/b.clj" nil nil {:home home})
          (is (= "/p/b.clj:1:1" (slurp opened))))
        (testing "a program that fails to open it says why"
          (terminal/stop! dir "rec")
          (terminal/start! dir {:name "broken" :command ["sleep" "300"]})
          (is (= "could not open /p/a.clj in broken: no" (terminal/open-file! dir "/p/a.clj" 3 nil {:home home}))))
        (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server"))))))

(deftest a-var-may-be-had-as-a-string-literal
  ;; byj-gly.453: an argv that is code, as elisp, takes a path quoted
  (let [dir (fresh-dir "literal")
        v (terminal/vars dir {"OUT" "/t/a \"b\" \\c"})]
    (is (= "\"/t/a \\\"b\\\" \\\\c\"" (v "OUT_STRING")))
    (is (= (terminal/string-literal (terminal/project-tag dir)) (v "BYJ_PROJECT_STRING")))
    (is (nil? (v "NO_SUCH_VAR_HERE_STRING")) "nothing is no literal")
    (is (= ["(f \"/t/a \\\"b\\\" \\\\c\")"] (terminal/expand ["(f ${OUT_STRING})"] v)))))

(deftest after-the-tools-a-running-program-hears-of-it
  ;; byj-gly.453: the emacs entry reverts what the agent replaced
  (let [label (str "byj-term-test-" (System/nanoTime))
        dir (fresh-dir "after-tools")
        home (fresh-dir "home")
        log (io/file dir "after.txt")]
    (.mkdirs (io/file dir ".brainyard"))
    (spit (io/file dir ".brainyard" "terminal.edn")
          (pr-str [{:name "rec" :command ["sleep" "300"] :after-tools ["sh" "-c" "echo ran >> after.txt"]}
                   {:name "broken" :command ["sleep" "300"] :after-tools ["sh" "-c" "echo no >&2; exit 3"]}
                   {:name "stuck" :command ["sleep" "300"] :after-tools ["sleep" "30"]}
                   {:name "idle" :command ["sleep" "300"] :after-tools ["sh" "-c" "echo idle >> after.txt"]}]))
    (binding [ah/*tmux* ["tmux" "-L" label]
              terminal/*editor-timeout-ms* 300]
      (try
        (is (= {:ran [] :failed {}} (terminal/after-tools dir {:home home})) "nothing runs: nothing hears")
        (doseq [n ["rec" "broken" "stuck"]] (terminal/start! dir {:name n :command ["sleep" "300"]}))
        (let [{:keys [ran failed]} (terminal/after-tools dir {:home home})]
          (is (= ["rec"] ran) "each running one with :after-tools, in the project directory")
          (is (= "ran\n" (slurp log)) "and never one that does not run")
          (is (= "no" (get failed "broken")))
          (is (re-find #"timed out after 300 ms" (get failed "stuck")) "a stuck one is given up on"))
        (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server"))))))

(deftest a-running-editor-says-what-the-person-is-looking-at
  ;; byj-gly.453: terminal$context
  (let [label (str "byj-term-test-" (System/nanoTime))
        dir (fresh-dir "context")
        home (fresh-dir "home")
        catalogue (fn [ctx] (spit (io/file dir ".brainyard" "terminal.edn")
                                  (pr-str [{:name "ed" :command ["sleep" "300"] :context ctx}])))]
    (.mkdirs (io/file dir ".brainyard"))
    (catalogue ["sh" "-c" "printf '%s' '{\"file\":\"/p/a.clj\",\"line\":3,\"column\":5,\"modified\":false,\"region\":\"(defn f [])\"}' > \"$1\"" "sh" "${OUT}"])
    (binding [ah/*tmux* ["tmux" "-L" label]
              terminal/*editor-timeout-ms* 2000]
      (try
        (is (nil? (terminal/context! dir {:home home})) "none runs: nil, the tool says so")
        (terminal/start! dir {:name "ed" :command ["sleep" "300"]})
        (is (= {:program "ed" :context {:file "/p/a.clj" :line 3 :column 5 :modified false :region "(defn f [])"}}
               (terminal/context! dir {:home home})))
        (testing "a long selection is clipped to its start"
          (catalogue ["sh" "-c" "printf '{\"region\":\"%s\"}' \"$(head -c 20000 /dev/zero | tr '\\0' x)\" > \"$1\"" "sh" "${OUT}"])
          (let [r (:region (:context (terminal/context! dir {:home home})))]
            (is (= (inc terminal/max-context-chars) (count r)))
            (is (str/ends-with? r "x…"))))
        (testing "what is not an answer says so"
          (catalogue ["sh" "-c" "echo not json > \"$1\"" "sh" "${OUT}"])
          (is (= {:error "ed said what is not a JSON object"} (terminal/context! dir {:home home})))
          (catalogue ["true"])
          (is (= {:error "ed said nothing"} (terminal/context! dir {:home home})))
          (catalogue ["sh" "-c" "echo gone >&2; exit 1"])
          (is (= {:error "could not ask ed: gone"} (terminal/context! dir {:home home}))))
        (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))
  (testing "the tool, with no editor running, says how to have one"
    ;; a tmux of its own, with no server: never the person's
    (binding [ah/*tmux* ["tmux" "-L" (str "byj-term-test-none-" (System/nanoTime))]]
      (let [tool (first (filter #(= "terminal$context" (:name %)) (terminal/commands {:project-dir (fresh-dir "none")})))]
        (is (re-find #"for Emacs, emacs\$context" (:error ((:fn tool) {} nil))))))))

(defn- wait-for
  "Poll `pred` every 50 ms for up to `ms`: its value, or nil."
  [ms pred]
  (loop [left ms]
    (or (pred) (when (pos? left) (Thread/sleep 50) (recur (- left 50))))))

(deftest inside-tmux-a-program-opens-beside-byj
  ;; byj-gly.452: Enter handed the whole terminal to the attach, and the
  ;; session could not be seen beside the program
  (let [outer-l (str "byj-outer-test-" (System/nanoTime))
        inner-l (str "byj-term-test-" (System/nanoTime))
        dir (fresh-dir "split")
        entry {:name "sleeper" :command ["sleep" "300"]}
        n (terminal/session-name dir "sleeper")
        o (fn [& args] (apply fx/sh {:out :string :err :string} "tmux" "-L" outer-l args))]
    (testing "the plan: beside byj from inside tmux, unless the entry says to attach"
      (is (= "%1" (:split (terminal/attach-plan dir entry true nil "%1"))))
      (is (nil? (:split (terminal/attach-plan dir (assoc entry :open :attach) true nil "%1"))))
      (is (nil? (:split (terminal/attach-plan dir entry true nil nil))) "outside tmux, the terminal as before"))
    (binding [ah/*tmux* ["tmux" "-L" inner-l]
              terminal/*outer-tmux* ["tmux" "-L" outer-l]]
      (try
        ;; byj's own pane, in the tmux it runs inside
        (o "new-session" "-d" "-s" "byj" "-x" "200" "-y" "50" "sleep" "300")
        (let [pane (str/trim (:out (o "display-message" "-p" "-t" "byj" "#{pane_id}")))
              handed (atom nil)
              said (atom [])
              {:keys [view state]} (terminal/views dir {:hand! (fn [argv _] (reset! handed argv))
                                                       :say! #(swap! said conj %)
                                                       :pane (constantly pane)
                                                       :prefix (constantly "C-b")})
              panes #(str/split-lines (str/trim (str (:out (o "list-panes" "-s" "-t" "byj" "-F" "#{pane_id} #{@byj-term}")))))
              row (fn [pred] (first (filter pred ((:items view)))))]
          (reset! state {:entries [entry] :tmux? true :installed #{"sleeper"} :running {}})
          (testing "Enter starts it and opens it in a pane split beside byj's, the terminal kept"
            ((:act view) (row :installed?))
            (is (nil? @handed) "nothing handed the terminal")
            (is (= 2 (count (panes))))
            (is (= (str " " n) (subs (second (panes)) (str/index-of (second (panes)) " "))) "the new pane says what it shows")
            (is (str/starts-with? (last @said) "sleeper opened beside"))
            (is (wait-for 5000 #(seq (str/trim (str (:out (fx/sh {:out :string :err :string} "tmux" "-L" inner-l "list-clients"))))))
                "attached from that pane"))
          (testing "Enter again selects that pane, never a second"
            (reset! state {:entries [entry] :tmux? true :installed #{"sleeper"} :running (terminal/running dir)})
            ((:act view) (row :running?))
            (is (= 2 (count (panes))))
            (is (= "sleeper beside, selected · C-b o switches · C-b C-b d closes" (last @said))
                "the hint names the prefix it was given, never the suite's own tmux"))
          (testing "an entry that says :open :attach is handed the terminal, as outside tmux"
            (reset! state {:entries [(assoc entry :open :attach)] :tmux? true :installed #{"sleeper"} :running (terminal/running dir)})
            ((:act view) (row :running?))
            (is (= "attach-session" (nth @handed 3)))
            (is (= 2 (count (panes)))))
          (testing "a split that fails says why, and the terminal is handed over instead"
            (reset! handed nil)
            (let [{:keys [view state]} (terminal/views dir {:hand! (fn [argv _] (reset! handed argv))
                                                           :say! #(swap! said conj %)
                                                           :pane (constantly "%999")
                                                           :prefix (constantly nil)})]
              (reset! state {:entries [entry] :tmux? true :installed #{"sleeper"} :running (terminal/running dir)})
              ((:act view) (first (filter :running? ((:items view)))))
              (is (str/starts-with? (last @said) "could not open sleeper beside byj"))
              (is (= "attach-session" (nth @handed 3)))))
          (testing "the program stopped, its pane closes with the attach"
            (terminal/stop! dir "sleeper")
            (is (wait-for 5000 #(= 1 (count (panes)))))))
        (finally
          (fx/sh {:out :string :err :string} "tmux" "-L" outer-l "kill-server")
          (fx/sh {:out :string :err :string} "tmux" "-L" inner-l "kill-server"))))))

(deftest the-rows-say-what-runs-and-what-can
  (let [rows (terminal/rows {:entries [{:name "emacs" :description "Emacs" :command ["emacs"]}
                                       {:name "btop" :description "Processes" :command ["btop"]}]
                             :tmux? true :installed #{"emacs"} :running {"emacs" {:since 1000}}}
                            (+ 1000 (* 3 3600) (* 12 60)))]
    (is (= ["▾ Running" "  ● emacs" "▾ Start" "  ○ emacs" "  · btop"] (mapv :label rows)))
    (is (= "3h12m" (:hint (second rows))) "how long it has run")
    (is (= "not installed" (:hint (last rows))))
    (is (= [:running :start] (keep :section rows)) "Tab goes between them"))
  (testing "with no tmux, it says how a program runs"
    (is (str/includes? (:label (first (terminal/rows {:entries [] :tmux? false :installed #{} :running {}} 0)))
                       "no tmux: runs in the foreground"))))

(deftest the-agent-works-a-program-through-its-tools
  ;; byj-gly.417: start, type into, read and stop a program — this project's,
  ;; on a tmux server of the test's own
  (let [label (str "byj-term-tools-" (System/nanoTime))
        dir (fresh-dir "tools")
        _ (.mkdirs (io/file dir ".brainyard"))
        _ (spit (io/file dir ".brainyard" "terminal.edn") "[{:name \"sh\" :command [\"sh\"] :description \"A plain shell\"}]")
        cmds (into {} (map (juxt :name identity)) (terminal/commands {:project-dir dir}))
        call (fn [n args] ((:fn (get cmds n)) args {}))]
    (binding [ah/*tmux* ["tmux" "-L" label]]
      (try
        (testing "list: the catalogue, installed and not running"
          (let [sh (first (filter #(= "sh" (:name %)) (:programs (call "terminal$list" {}))))]
            (is (= {:name "sh" :running false :installed true :command "sh" :description "A plain shell"} sh))))
        (is (= {:started "sh"} (call "terminal$start" {:name "sh"})))
        (is (= "sh is already running" (:error (call "terminal$start" {:name "sh"}))))
        (is (re-find #"no program nope" (:error (call "terminal$start" {:name "nope"}))) "only what the catalogue names")
        (testing "send types a line and Enter; capture reads the screen"
          (is (= {:sent "sh"} (call "terminal$send" {:name "sh" :text "echo stocktake-$((40+2))"})))
          (let [screen (loop [n 0] (let [s (:screen (call "terminal$capture" {:name "sh"}))]
                                     (if (or (str/includes? (str s) "stocktake-42") (> n 40)) s (do (Thread/sleep 50) (recur (inc n))))))]
            (is (str/includes? screen "stocktake-42") "the shell ran it")))
        (testing "keys by their tmux names"
          (is (= {:sent "sh"} (call "terminal$send" {:name "sh" :keys ["C-c"]}))))
        (is (= "give :text or :keys" (:error (call "terminal$send" {:name "sh"}))))
        (testing "list says it runs, for how long"
          (let [sh (first (filter #(= "sh" (:name %)) (:programs (call "terminal$list" {}))))]
            (is (:running sh))
            (is (integer? (:uptime-s sh)))))
        (is (= {:stopped "sh"} (call "terminal$stop" {:name "sh"})))
        (is (= "sh is not running" (:error (call "terminal$stop" {:name "sh"}))))
        (is (re-find #"not running" (:error (call "terminal$send" {:name "sh" :text "x"}))))
        (is (= "sh is not running" (:error (call "terminal$capture" {:name "sh"}))))
        (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server"))))))

(deftest typing-into-a-program-asks-first
  ;; byj-gly.417: terminal$send runs whatever a shell is given, so the default
  ;; :tool-ask-effects asks about it; reading and listing do not ask
  (let [dir (fresh-dir "asking")
        cfg (assoc config/defaults :project-dir dir :working-dir dir :permission-mode :ask-each-time)
        ctx (agent/make-ctx {:config cfg :lm {:provider :mock :format :mock}})]
    (is (contains? (:tool-ask-effects config/defaults) :terminal/input))
    (is (every? #(contains? @(:tools ctx) %) ["terminal$list" "terminal$start" "terminal$stop" "terminal$send" "terminal$capture"]))
    (is (= :ask (:verdict (permission/tool-verdict "terminal$send" ctx))))
    (is (= :allow (:verdict (permission/tool-verdict "terminal$capture" ctx))))
    (is (= :allow (:verdict (permission/tool-verdict "terminal$list" ctx))))
    (testing "a subagent has none: a program is the session's"
      (is (not-any? #(str/starts-with? % "terminal$")
                    (keys @(:tools (agent/make-ctx {:config (assoc cfg :agent-instance-id "explore/x-1")
                                                    :lm {:provider :mock :format :mock}}))))))
    (testing ":terminal-tools? false offers none"
      (is (not-any? #(str/starts-with? % "terminal$")
                    (keys @(:tools (agent/make-ctx {:config (assoc cfg :terminal-tools? false)
                                                    :lm {:provider :mock :format :mock}}))))))))

(deftest tmux-sessions-are-read-as-tmux-3-2-prints-them
  ;; byj-gly.450: tmux 3.2 and 3.3 (Ubuntu 22.04, Debian bookworm) print a
  ;; tab in a -F format as _, so a line split on tabs was one field and no
  ;; session was found there; a stand-in prints the format as they do
  (let [bin (fresh-dir "tmux32")
        mine (fresh-dir "mine")
        tmux (str bin "/tmux")]
    (spit tmux (str "#!/bin/sh\n"
                    "# tmux 3.2's list-sessions: its -F format filled in, a tab printed as _\n"
                    "while [ $# -gt 0 ]; do case \"$1\" in -F) fmt=$2; shift 2;; *) shift;; esac; done\n"
                    "line() { printf '%s\\n' \"$fmt\" | sed -e \"s|#{session_name}|$1|\" -e \"s|#{session_path}|$2|\" -e \"s|#{session_created}|$3|\" | tr '\\t' '_'; }\n"
                    "line " (host/tmux-name "in-project") " " mine " 1790000000\n"
                    "line " (terminal/session-name mine "sleeper") " " mine " 1790000001\n"))
    (.setExecutable (io/file tmux) true)
    (binding [ah/*tmux* [tmux]]
      (is (= #{"in-project"} (host/running-in mine)) "an agent's session, by its directory")
      (is (= {"sleeper" {:since 1790000001}} (terminal/running mine)) "a program's, with when it started"))
    (testing "a path holding the separator's colon is read whole, last"
      (is (= ["byj-cc-x" "/a:b/c"] (ah/tmux-fields "byj-cc-x:|:/a:b/c" 2))))))
