(ns brainyard.emacs-test
  "byj and the person's Emacs (RFC-067, byj-gly.459): which Emacs is reached,
  the one call path whose arguments never enter the elisp it runs, and the
  emacs$* tools. Against a stand-in emacsclient, and once against a real
  Emacs daemon of the test's own, on a socket in a temporary directory —
  never the person's server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.changes :as changes]
            [brainyard.coach :as coach]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.emacs :as emacs]
            [brainyard.hookbox :as hookbox]
            [brainyard.json :as json]
            [brainyard.literate :as literate]
            [brainyard.session :as session]
            [brainyard.terminal :as terminal]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-emacs-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- stand-in
  "An emacsclient that keeps its argv and its arguments file in `dir`, and
  answers with `answer` written to the form's output file — the last string
  literal in it — then exits `exit`, saying `err`."
  [dir {:keys [answer exit err] :or {exit 0}}]
  (let [f (io/file dir "emacsclient")]
    (spit f (str "#!/bin/sh\n"
                 "printf '%s\\n' \"$@\" > " (terminal/string-literal (str dir "/argv")) "\n"
                 "form=\"$4\"\n"
                 "out=$(printf '%s' \"$form\" | sed -E 's/.*\"([^\"]*)\"\\)\\)$/\\1/')\n"
                 "args=$(printf '%s' \"$form\" | sed -E 's/.*\"([^\"]*)\" \"[^\"]*\"\\)\\)$/\\1/')\n"
                 "cp \"$args\" " (terminal/string-literal (str dir "/args.json")) "\n"
                 (when answer (str "printf '%s' " (terminal/string-literal answer) " > \"$out\"\n"))
                 (when err (str "echo " (terminal/string-literal err) " >&2\n"))
                 "exit " exit "\n"))
    (fx/sh {:out :string :err :string} "chmod" "+x" (.getPath f))
    (.getPath f)))

(deftest which-emacs-is-the-projects-unless-named
  (let [dir (fresh-dir "which")
        tag (terminal/project-tag dir)]
    (is (= (str "byj-" tag) (emacs/server {:project-dir dir})) "nothing said: the project's own daemon")
    (is (= (str "byj-" tag) (emacs/server {:project-dir dir :emacs-server "project"})))
    (is (= (str "byj-" tag) (emacs/server {:project-dir dir :emacs-server "  "})))
    (is (= "server" (emacs/server {:project-dir dir :emacs-server "server"})) "the person's own, only when named")
    (is (emacs/project-server? {:project-dir dir}))
    (is (not (emacs/project-server? {:project-dir dir :emacs-server "server"})))
    (is (= "project" (get-in config/config-items [:emacs-server :default])))
    (is (contains? (get-in config/config-items [:tool-ask-effects :default]) :editor/eval)
        "emacs$eval is asked about by default")
    (is (contains? (get-in config/config-items [:tool-ask-effects :default]) :editor/execute)
        "and so is running an org block"))
  (testing "where emacsclient looks for a server's socket"
    (is (= [(io/file "/s/sock")] (emacs/socket-files "/s/sock")) "a path is itself")
    (is (= [(io/file "/run/u/emacs" "srv") (io/file "/t" "emacs501" "srv") (io/file "/tmp" "emacs501" "srv")]
           (emacs/socket-files "srv" {:env {"XDG_RUNTIME_DIR" "/run/u" "TMPDIR" "/t"} :uid "501"})))
    (is (= [(io/file "/tmp" "emacs501" "srv")] (emacs/socket-files "srv" {:env {} :uid "501"})))))

(deftest byj-el-is-installable-for-an-init
  (let [dir (fresh-dir "install")
        home (fresh-dir "install-home")
        path (emacs/install-el! {:project-dir dir} {:home home})]
    (is (= (str (io/file home ".brainyard" "emacs" "byj.el")) path))
    (is (= (slurp (io/resource "brainyard/emacs/byj.el")) (slurp path)))
    (is (str/includes? (slurp path) "(provide 'byj)") "an init can require it")
    (let [t (emacs/install-el-text path)]
      (is (str/includes? t (str "(add-to-list 'load-path \"" (io/file home ".brainyard" "emacs") "\")")))
      (is (str/includes? t "(require 'byj)"))
      (is (str/includes? t "'byj-command-map")))
    (spit (doto (io/file dir ".brainyard" "emacs" "byj.el") io/make-parents) ";; mine\n(provide 'byj)\n")
    (is (= ";; mine\n(provide 'byj)\n" (slurp (emacs/install-el! {:project-dir dir} {:home home})))
        "the project's own, as byj loads it")))

(deftest a-newer-byj-el-replaces-the-op-table
  ;; byj-gly.480: byj-ops was a defvar, so an Emacs that had loaded an older
  ;; byj.el kept that one's op table when byj loaded a newer one, and every op
  ;; added since was "byj.el has no op ...". Skipped where Emacs is not
  ;; installed; a batch Emacs of its own, never the person's.
  (when (terminal/installed? "emacs")
    (let [dir (fresh-dir "reload")
          el (str (io/file dir "byj.el"))]
      (spit el (slurp (io/resource "brainyard/emacs/byj.el")))
      (let [r (fx/sh {:out :string :err :string :dir dir} "emacs" "-Q" "--batch"
                     "-l" el
                     "--eval" "(setq byj-ops '((status . byj-op-status)))"
                     "-l" el
                     "--eval" "(princ (if (assq 'habits byj-ops) \"habits\" \"none\"))")]
        (is (= "habits" (str/trim (str (:out r))))
            (str "an older op table is replaced on load: " (:err r)))))))

(deftest a-call-puts-no-argument-into-the-elisp-it-runs
  (let [dir (fresh-dir "call")
        home (fresh-dir "home")
        cfg {:project-dir dir :session-id "s-1"}
        evil "\")) (shell-command \"touch pwned\") ((\""]
    (binding [emacs/*emacsclient* (stand-in dir {:answer "{\"ok\": {\"value\": \"42\"}}"})]
      (is (= {:ok {:value "42"}} (emacs/call! cfg "eval" {:code evil} {:home home})))
      (let [[s server _ form] (str/split-lines (slurp (io/file dir "argv")))]
        (is (= "-s" s))
        (is (= (str "byj-" (terminal/project-tag dir)) server))
        (is (not (str/includes? form "pwned")) "what the model wrote is in the arguments file, not the form")
        (is (str/includes? form "(byj-call 'eval ") "the op, from byj's own set")
        (is (str/includes? form (str "byj-" (:version (emacs/el-file cfg {:home home})) ".el")) "byj.el loaded by its version"))
      (let [args (slurp (io/file dir "args.json"))]
        (is (str/includes? args "pwned") "the code went as data")
        (is (str/includes? args "\"s-1\"") "with the session that asked")))
    (testing "byj.el is written under the home given, once a version"
      (let [{:keys [file]} (emacs/el-file cfg {:home home})]
        (is (str/starts-with? file (str home "/.brainyard/emacs/byj-")))
        (is (= (slurp (io/resource "brainyard/emacs/byj.el")) (slurp file)))))
    (testing "a project's byj.el replaces the shipped one"
      (.mkdirs (io/file dir ".brainyard" "emacs"))
      (spit (io/file dir ".brainyard" "emacs" "byj.el") ";; the project's\n")
      (is (= ";; the project's\n" (slurp (:file (emacs/el-file cfg {:home home}))))))
    (testing "an op not byj's is refused before Emacs is asked"
      (is (= {:error "no op shell"} (emacs/call! cfg "shell" {} {:home home}))))
    (testing "what goes wrong is an answer"
      (binding [emacs/*emacsclient* (stand-in dir {:answer "{\"error\": \"No file /x\"}"})]
        (is (= {:error "No file /x"} (emacs/call! cfg "show" {:file "/x"} {:home home}))))
      (binding [emacs/*emacsclient* (stand-in dir {})]
        (is (= {:error "Emacs said nothing"} (emacs/call! cfg "status" {} {:home home}))))
      (binding [emacs/*emacsclient* (stand-in dir {:answer "[1]"})]
        (is (= {:error "Emacs said what is not a JSON object"} (emacs/call! cfg "status" {} {:home home}))))
      (binding [emacs/*emacsclient* (stand-in dir {:exit 1 :err "can't find socket"})]
        (is (re-find #"^could not ask Emacs \(byj-.*\): can't find socket$"
                     (:error (emacs/call! cfg "status" {} {:home home}))))))))

(deftest the-tools-are-offered-while-emacs-is-up
  (let [dir (fresh-dir "tools")
        home (fresh-dir "home")
        tmp (fresh-dir "tmp")
        opts {:env {"TMPDIR" tmp} :uid "7" :home home}
        cfg {:project-dir dir}
        names #(set (map :name (emacs/commands cfg opts)))
        tool (fn [n] (first (filter #(= n (:name %)) (emacs/commands cfg opts))))]
    (testing "down: emacs$status alone, saying how to start it"
      (is (= #{"emacs$status"} (names)))
      (let [r ((:fn (tool "emacs$status")) {} {})]
        (is (false? (:up r)))
        (is (re-find #"Terminal view" (:how r)) "the project's daemon: the Terminal view starts it"))
      (is (nil? (emacs/context! cfg opts)) "@here asks another editor")
      (is (nil? (emacs/open-file! cfg "/p/a.clj" 3 nil opts)) "a click is opened another way"))
    (let [sock (io/file tmp "emacs7" (emacs/server cfg))]
      (io/make-parents sock)
      (spit sock "")
      (testing "up: the rest, and what each may change"
        (is (= #{"emacs$status" "emacs$context" "emacs$read" "emacs$show" "emacs$eval" "emacs$note" "emacs$edit"
                 "emacs$org-outline" "emacs$org-tangle" "emacs$org-execute" "emacs$xref" "emacs$diagnostics"
                 "emacs$describe" "emacs$habits" "emacs$suggest" "emacs$lessons" "emacs$features"} (names)))
        (is (= #{:editor/display} (:effects (tool "emacs$note"))) "a note changes only the screen")
        (is (= #{:editor/edit} (:effects (tool "emacs$edit"))) "an edit changes a buffer, unsaved, and is not asked about")
        (is (= {:error "give :notes, or :clear"} ((:fn (tool "emacs$note")) {} {})))
        (is (= {:error "give :old, the text to replace"} ((:fn (tool "emacs$edit")) {:file "a" :old "" :new "x"} {})))
        (is (= #{} (:effects (tool "emacs$read"))) "reading is not asked")
        (is (= #{} (:effects (tool "emacs$org-outline"))))
        (is (= #{} (:effects (tool "emacs$xref"))) "what Emacs knows is read, not asked about")
        (is (= #{} (:effects (tool "emacs$diagnostics"))))
        (is (= #{} (:effects (tool "emacs$describe"))) "what this Emacs says about itself is read, not asked about")
        (is (= #{} (:effects (tool "emacs$habits"))) "what the person ran, read; a tip kept in their Emacs's memory")
        (is (= #{} (:effects (tool "emacs$lessons"))))
        (is (= #{} (:effects (tool "emacs$features"))) "what the person's Emacs has, read")
        (is (= #{:editor/display} (:effects (tool "emacs$suggest"))) "a card on their screen; they run it")
        (is (= {:error "give :command"} ((:fn (tool "emacs$suggest")) {:command " "} {})))
        (is (= ["help" "moving"] (take 2 (map :name (:lessons ((:fn (tool "emacs$lessons")) {} {})))))
            "the shipped lessons, in teaching order")
        (is (re-find #"no lesson nope; there are help, moving" (:error ((:fn (tool "emacs$lessons")) {:name "nope"} {}))))
        (is (= {:error "give one of :key, :command, :prefix, :apropos or :modes"} ((:fn (tool "emacs$describe")) {} {})))
        (is (= {:error "give one of :key, :command, :prefix, :apropos or :modes"}
               ((:fn (tool "emacs$describe")) {:key "C-c" :command "find-file"} {}))
            "one question at a time")
        (is (= {:error "give :identifier, or :line (and :column)"} ((:fn (tool "emacs$xref")) {:file "a.clj"} {})))
        (is (= #{:editor/display} (:effects (tool "emacs$show"))))
        (is (= #{:editor/eval} (:effects (tool "emacs$eval"))))
        (is (= #{:editor/execute :process} (:effects (tool "emacs$org-execute"))))
        (is (= #{:fs/write} (:effects (tool "emacs$org-tangle"))))
        (is (every? #(= [:file] (:path-params (tool %))) ["emacs$read" "emacs$show" "emacs$edit" "emacs$org-outline" "emacs$org-tangle" "emacs$org-execute"
                                                     "emacs$xref" "emacs$diagnostics" "emacs$describe" "emacs$features"])
            "a file a tool names is checked by the path gate")
        (is (= {:error "give :name or :line"} ((:fn (tool "emacs$org-execute")) {:file "a.org"} {})))
        (is (= {:error "give :file or :buffer"} ((:fn (tool "emacs$read")) {} {})))
        (is (= {:error "give :code"} ((:fn (tool "emacs$eval")) {:code " "} {}))))
      (testing "the person's own server is named, and says so when it is down"
        (let [r ((:fn (first (emacs/commands {:project-dir dir :emacs-server "nobody-here"} opts))) {} {})]
          (is (= "nobody-here" (:server r)))
          (is (re-find #"server-start" (:how r))))))))

(deftest emacs-lessons-are-data-a-project-and-a-person-may-add-to
  (let [dir (fresh-dir "lessons")
        home (fresh-dir "lessons-home")
        shipped (emacs/lessons {:project-dir dir} {:home home})]
    (is (= ["help" "moving" "searching" "editing" "buffers-and-windows" "files-and-projects" "org"] (map :name shipped)))
    (is (every? #(and (:title %) (:summary %) (seq (:steps %))) shipped) "each with a title, a summary and steps")
    (is (every? #(seq (:commands %)) (mapcat :steps shipped)) "each step names the commands that show it done")
    (is (not-any? #(re-find #"\b(?:C|M)-[a-z]" (:text %)) shipped) "commands, never keys: those are the person's Emacs's")
    (is (= {:title "By words" :commands ["forward-word" "backward-word"]} (first (:steps (second shipped)))))
    (spit (doto (io/file home ".brainyard" "emacs" "lessons" "macros.org") io/make-parents)
          "#+title: Keyboard macros\n* Record one\n:PROPERTIES:\n:COMMANDS: kmacro-start-macro kmacro-end-macro\n:END:\n")
    (spit (doto (io/file dir ".brainyard" "emacs" "lessons" "moving.org") io/make-parents)
          "#+title: Our way of moving\n* Jump\n:PROPERTIES:\n:COMMANDS: avy-goto-char\n:END:\n")
    (let [ls (emacs/lessons {:project-dir dir} {:home home})]
      (is (= "Our way of moving" (:title (second ls))) "the project's replaces the shipped one of its name")
      (is (= {:name "macros" :title "Keyboard macros" :steps [{:title "Record one" :commands ["kmacro-start-macro" "kmacro-end-macro"]}]}
             (select-keys (last ls) [:name :title :steps]))
          "the person's own, after the shipped ones"))))

(deftest at-visible-and-at-trail-say-where-the-person-has-been
  (let [v (session/places-text :visible {:visible [{:file "/p/a.clj" :start 1 :end 40 :line 12 :selected true}
                                                   {:buffer "*scratch*" :start 1 :end 5 :line 2 :selected false}]})]
    (is (str/starts-with? v "## @visible"))
    (is (str/includes? v "- `/p/a.clj`, lines 1–40, cursor at line 12 — the window they are in"))
    (is (str/ends-with? v "- `*scratch*`, lines 1–5, cursor at line 2") "a buffer visiting no file, by name"))
  (let [t (session/places-text :trail {:trail [{:file "/p/b.clj" :line 3 :minutes-ago 4} {:file "/p/c.clj" :line 9}]})]
    (is (str/starts-with? t "## @trail"))
    (is (str/includes? t "- `/p/b.clj` at line 3, 4 min ago"))
    (is (str/ends-with? t "- `/p/c.clj` at line 9")))
  (is (str/includes? (session/places-text :trail nil) "could not be read (the Emacs this session reaches is not up)"))
  (is (str/includes? (session/places-text :visible {:error "boom"}) "## @visible\n\nThe person wrote @visible"))
  (is (re-find #"\(boom\)" (session/places-text :visible {:error "boom"})))
  (testing "the turn is told, beside @here's, when the question mentions them"
    (let [dir (fresh-dir "trail")
          st (atom {:input "what was I reading? @trail"})]
      (session/editor-context {:st-memory st :config {:project-dir dir :working-dir dir} :node {}})
      (is (str/starts-with? (:editor-context @st) "## @trail\n\nThe person wrote @trail"))
      (is (str/includes? (:editor-context @st) "not up") "no Emacs: said, not guessed")
      (reset! st {:input "no mention here"})
      (session/editor-context {:st-memory st :config {:project-dir dir :working-dir dir} :node {}})
      (is (nil? (:editor-context @st))))))

(deftest a-turn-is-told-to-emacs
  (testing "a turn's start: busy, its number, its question's first line, short"
    (is (= {:state "busy" :turn 3 :question "fix the build"}
           (emacs/turn-args :session.turn/start {:turn 3 :question "fix the build\nand more"})))
    (is (= 61 (count (:question (emacs/turn-args :session.turn/start {:turn 1 :question (apply str (repeat 80 "x"))}))))))
  (testing "its end: how long, and how it ended when not by answering"
    (is (= {:state "idle" :elapsed-ms 1200 :files 0} (emacs/turn-args :session.turn/end {:terminated-by :answer-channel :elapsed-ms 1200}))
        "answered: nothing more to say")
    (is (= {:state "idle" :elapsed-ms 1 :files 3} (emacs/turn-args :session.turn/end {:terminated-by :goal-achieved :elapsed-ms 1 :files-changed 3}))
        "the files it changed, kept for a review")
    (is (= {:state "idle" :elapsed-ms 5 :files 0 :ended "cancelled"} (emacs/turn-args :session.turn/end {:terminated-by :cancelled :elapsed-ms 5}))))
  (is (nil? (emacs/turn-args :agent.tools/results {})) "nothing else is told")
  (testing "nothing without a session id, or with the tools off"
    (is (nil? (emacs/turn! {:project-dir "/nowhere"} :session.turn/start {:turn 1 :question "q"})))
    (is (nil? (emacs/turn! {:project-dir "/nowhere" :session-id "s" :emacs-tools? false} :session.turn/start {:turn 1 :question "q"})))))

(deftest at-here-says-where-in-an-org-file
  (let [text (session/editor-context-text
              {:program "emacs"
               :context {:file "/p/notes.org" :line 12 :column 3 :modified false
                         :org {:heading ["Build" "Tangling"]
                               :block {:language "clojure" :name "deps" :line 10}}}})]
    (is (str/includes? text "- Under the org heading: Build / Tangling"))
    (is (str/includes? text "- In the `deps` src block of clojure that starts at line 10"))
    (is (str/includes? text "- File: `/p/notes.org`")))
  (is (not (str/includes? (session/editor-context-text {:program "emacs" :context {:file "/p/a.clj" :line 1 :column 1}})
                          "org heading"))
      "outside org, nothing about it"))

(deftest a-real-emacs-answers-each-op
  ;; Skipped where Emacs is not installed. A daemon of the test's own, on a
  ;; socket path of its own (`--daemon=PATH`), killed at the end.
  (when (terminal/installed? "emacs")
    (let [dir (fresh-dir "real")
          home (fresh-dir "home")
          ;; short: a socket's path is held to ~104 bytes, which the
          ;; temporary directory's own nearly fills on macOS; in a directory
          ;; of its own, mode 700 and no symlink, or Emacs will not serve it
          sock-dir (io/file (.getCanonicalPath (io/file "/tmp")) (str "byj-e-" (System/nanoTime)))
          _ (.mkdirs sock-dir)
          _ (fx/sh {:out :string :err :string} "chmod" "700" (.getPath sock-dir))
          sock (str (io/file sock-dir "e"))
          cfg {:project-dir dir :emacs-server sock :session-id "s-real"}
          f (io/file dir "a.txt")]
      (spit f "one\ntwo\nthree\n")
      (fx/sh {:out :string :err :string :dir dir} "emacs" "-Q" (str "--daemon=" sock))
      (try
        (is (emacs/reachable? cfg) "its socket is where it was named")
        (let [s (:ok (emacs/call! cfg "status" {} {:home home}))]
          (is (string? (:emacs s)))
          (is (= sock (:server s)))
          (is (some? (:byj-el s)) "byj.el loaded, its version set"))
        (testing "eval: every form, the last value printed"
          (is (= {:ok {:value "3"}} (emacs/call! cfg "eval" {:code "(setq byj-test-x 1)\n(+ byj-test-x 2)"} {:home home})))
          (is (re-find #"Symbol.s value as variable is void|void-variable"
                       (str (:error (emacs/call! cfg "eval" {:code "no-such-var-here"} {:home home}))))
              "an elisp error is an answer"))
        (testing "read: the buffer as Emacs has it, unsaved edits included"
          (emacs/call! cfg "eval" {:code (str "(with-current-buffer (find-file-noselect " (terminal/string-literal (str f)) ")"
                                              " (goto-char (point-max)) (insert \"four\\n\"))")}
                       {:home home})
          (let [r (:ok (emacs/call! cfg "read" {:file (str f)} {:home home}))]
            (is (true? (:modified r)))
            (is (= "one\ntwo\nthree\nfour\n" (:text r)) "what the disk does not have yet")
            (is (= "one\ntwo\nthree\n" (slurp f)))
            (is (= [{:file (str f) :modified true}] (:buffers (:ok (emacs/call! cfg "status" {} {:home home}))))
                "status names the project's buffers and which hold unsaved edits"))
          (is (= "two\nthree\n" (:text (:ok (emacs/call! cfg "read" {:file (str f) :start 2 :end 3} {:home home})))))
          (let [g (io/file dir "b.txt")]
            (spit g "disk\n")
            (is (= {:visited false :text "disk\n"}
                   (select-keys (:ok (emacs/call! cfg "read" {:file (str g)} {:home home})) [:visited :text]))
                "a file no buffer visits, from the disk")))
        (testing "show and context: the place shown is the place the person is"
          (is (= 2 (:line (:ok (emacs/call! cfg "show" {:file (str f) :line 2 :end 3 :message "here"} {:home home})))))
          (let [c (emacs/context! cfg {:home home})]
            (is (= "emacs" (:program c)))
            (is (= (str f) (get-in c [:context :file])))
            (is (= 2 (get-in c [:context :line])))))
        (testing "revert: a buffer whose file changed and that holds no edits"
          (let [g (io/file dir "c.txt")]
            (spit g "old\n")
            (emacs/call! cfg "eval" {:code (str "(find-file-noselect " (terminal/string-literal (str g)) ")")} {:home home})
            (Thread/sleep 1100)
            (spit g "new\n")
            (is (= [(str g)] (:reverted (:ok (emacs/call! cfg "revert" {} {:home home}))))
                "the modified a.txt is left as it is")))
        (testing "notes: at the end of their lines, listed for next-error, cleared"
          (let [r (:ok (emacs/call! cfg "note" {:notes [{:file (str f) :line 2 :text "why two?" :kind "question"}
                                                        {:file (str f) :line 3 :text "fine\nreally" :kind "good"}]}
                                    {:home home}))]
            (is (= {:added 2 :notes 2 :buffer "*byj-notes*"} r)))
          (let [listed (:value (:ok (emacs/call! cfg "eval" {:code "(with-current-buffer \"*byj-notes*\" (buffer-string))"} {:home home})))]
            (is (str/includes? listed (str f ":2: question: why two?")))
            (is (str/includes? listed (str f ":3: good: fine really")) "a note is one line"))
          (is (= "t" (:value (:ok (emacs/call! cfg "eval" {:code "(eq next-error-last-buffer (get-buffer \"*byj-notes*\"))"} {:home home}))))
              "M-g n walks them")
          (is (= {:cleared 2 :notes 0} (:ok (emacs/call! cfg "notes-clear" {:file (str f)} {:home home})))))
        (testing "edit: once, one undo step, unsaved, the person's own edits kept"
          (is (= {:file (str f) :line 2 :modified true}
                 (:ok (emacs/call! cfg "edit" {:file (str f) :old "two" :new "TWO"} {:home home}))))
          (is (= "one\nTWO\nthree\nfour\n" (:text (:ok (emacs/call! cfg "read" {:file (str f)} {:home home})))))
          (is (= "one\ntwo\nthree\n" (slurp f)) "nothing saved")
          (emacs/call! cfg "eval" {:code (str "(with-current-buffer (get-file-buffer " (terminal/string-literal (str f)) ") (undo-only))")} {:home home})
          (is (= "one\ntwo\nthree\nfour\n" (:text (:ok (emacs/call! cfg "read" {:file (str f)} {:home home}))))
              "one undo takes the change back, and only it")
          (is (re-find #"2 times, not once" (:error (emacs/call! cfg "edit" {:file (str f) :old "t" :new "T"} {:home home}))))
          (is (re-find #"0 times, not once" (:error (emacs/call! cfg "edit" {:file (str f) :old "nowhere" :new "x"} {:home home})))))
        (testing "literate org: the outline, a run, a tangle checked and refused over unsaved edits"
          (let [org (io/file dir "lit.org")
                ctx {:config {:allowed-dirs [dir]}}
                tools (emacs/commands cfg {:home home})
                tool (fn [n] (:fn (first (filter #(= n (:name %)) tools))))]
            (spit org (str "#+PROPERTY: header-args :tangle yes :comments link\n"
                           "* Code\n#+name: add\n#+begin_src emacs-lisp\n(defun lit-add (a b) (+ a b))\n<<helper>>\n#+end_src\n\n"
                           "* Checks\n#+name: check\n#+begin_src emacs-lisp :tangle no\n(+ 1 2)\n#+end_src\n"))
            (let [o ((tool "emacs$org-outline") {:file "lit.org"} nil)]
              (is (= ["Code" "Checks"] (map :title (:headings o))))
              (is (= [{:name "add" :tangle (str (io/file dir "lit.el")) :noweb ["helper"]}
                      {:name "check" :tangle nil :noweb []}]
                     (map #(select-keys % [:name :tangle :noweb]) (:blocks o)))
                  "the inherited :tangle, resolved; none for :tangle no"))
            (let [w ((tool "emacs$org-tangle") {:file "lit.org"} ctx)]
              (is (= [(str (io/file dir "lit.el"))] (:written w)))
              (is (= (.getCanonicalPath org) (literate/tangled-from (io/file dir "lit.el")))
                  "what org wrote, the guard knows as tangled"))
            (is (re-find #"outside the directories" (:error ((tool "emacs$org-tangle") {:file "lit.org"} {:config {:allowed-dirs ["/nowhere"]}}))))
            (let [e ((tool "emacs$org-execute") {:file "lit.org" :name "check"} nil)]
              (is (= "3" (:value e)))
              (is (str/includes? (:results e) ": 3"))
              (is (true? (:modified e)) "results in the buffer, not saved"))
            (is (re-find #"unsaved edits" (:error ((tool "emacs$org-tangle") {:file "lit.org"} ctx)))
                "org would save the buffer to tangle it")
            (is (re-find #"unsaved edits" (:error ((tool "emacs$org-execute") {:file "lit.org" :name "check" :save true} nil))))
            (is (not (str/includes? (slurp org) "RESULTS")) "nothing the person had not saved was saved")))
        (testing "what Emacs knows: xref and diagnostics, and which backend said so"
          (let [tools (emacs/commands cfg {:home home})
                tool (fn [n] (:fn (first (filter #(= n (:name %)) tools))))
                el (io/file dir "k.el")
                txt (io/file dir "k.txt")]
            (spit el "(defun k-one () 1)\n(defun k-two () (k-one))\n")
            (spit txt "plain\n")
            (emacs/call! cfg "eval" {:code (str "(with-current-buffer (find-file-noselect " (terminal/string-literal (str el)) ") (eval-buffer))")} {:home home})
            (let [r ((tool "emacs$xref") {:file "k.el" :identifier "k-one" :line 2} nil)]
              (is (= "elisp" (:backend r)))
              (is (= "definitions" (:kind r)))
              (is (= [{:file (str el) :line 1 :column 1}] (map #(select-keys % [:file :line :column]) (:places r)))
                  "elisp's place, known by a marker, as a line and column")
              (is (str/includes? (:text (first (:places r))) "k-one")))
            (is (= 1 (:line (first (:places ((tool "emacs$xref") {:file "k.el" :line 2 :column 18} nil)))))
                "by line and column: the name there")
            (is (re-find #"searches the project" (:error ((tool "emacs$xref") {:file "k.el" :identifier "k-one" :references true} nil)))
                "no project to search: said, never a prompt the person must answer")
            (let [proj (io/file dir "proj")
                  pel (io/file proj "p.el")]
              (.mkdirs proj)
              (fx/sh {:out :string :err :string :dir (str proj)} "git" "init" "-q" ".")
              (spit pel "(defun p-one () 1)\n(defun p-two () (p-one))\n")
              (let [r ((tool "emacs$xref") {:file (str pel) :identifier "p-one" :references true} nil)]
                (is (= "references" (:kind r)))
                (is (= [[1 8] [2 18]] (map (juxt :line :column) (:places r))) "both uses, by a search of the project, columns from 1")
                (is (every? #(= (str pel) (:file %)) (:places r)) "the project's files alone, not the load-path's")
                (is (re-find #"project" (str (:scope r))))))
            (is (re-find #"k-one is not on line 3" (:error ((tool "emacs$xref") {:file "k.el" :identifier "k-one" :line 3} nil))))
            (let [r ((tool "emacs$xref") {:file "k.txt" :line 1} nil)]
              (is (nil? (:backend r)))
              (is (re-find #"no xref backend" (:note r)) "none is not nothing found"))
            (let [r ((tool "emacs$diagnostics") {:file "k.txt"} nil)]
              (is (nil? (:backend r)))
              (is (re-find #"no flymake or flycheck" (:note r))))
            (emacs/call! cfg "eval" {:code (str "(with-current-buffer (find-file-noselect " (terminal/string-literal (str el)) ")"
                                                " (setq-local flymake-diagnostic-functions '(elisp-flymake-checkdoc)) (flymake-mode 1) (flymake-start))")}
                         {:home home})
            (let [r (loop [n 0]
                      (let [r ((tool "emacs$diagnostics") {:file "k.el"} nil)]
                        (if (or (seq (:diagnostics r)) (< 40 n)) r (do (Thread/sleep 50) (recur (inc n))))))]
              (is (= "flymake" (:backend r)))
              (is (= ["elisp-flymake-checkdoc"] (:checkers r)))
              (is (seq (:diagnostics r)) "checkdoc's notes")
              (is (every? #(= "note" (:severity %)) (:diagnostics r)))
              (is (= (:total r) (count (:diagnostics r))))
              (is (every? #(pos? (:line %)) (:diagnostics r))))))
        (testing "describe: what this Emacs says about itself, from the buffer's own keymaps"
          (let [tool (fn [n] (:fn (first (filter #(= n (:name %)) (emacs/commands cfg {:home home})))))
                d (io/file dir "d.el")
                describe (tool "emacs$describe")]
            (spit d "(defun d-one () 1)\n")
            (emacs/call! cfg "eval" {:code (str "(with-current-buffer (find-file-noselect " (terminal/string-literal (str d)) ") (abbrev-mode 1))")} {:home home})
            (let [r (describe {:file "d.el" :key "C-M-x"} nil)]
              (is (= "emacs-lisp-mode" (:mode r)) "the buffer's modes answer")
              (is (= "eval-defun" (:command r)) "emacs-lisp-mode's own key")
              (is (string? (:doc r))))
            (is (= "find-file" (:command (describe {:file "a.txt" :key "C-x C-f"} nil))))
            (let [r (describe {:file "d.el" :key "C-c z z"} nil)]
              (is (nil? (:command r)))
              (is (re-find #"runs nothing" (:note r)) "a key bound to nothing, said"))
            (let [r (describe {:file "d.el" :command "eval-defun"} nil)]
              (is (true? (:exists r)))
              (is (true? (:interactive r)))
              (is (some #{"C-M-x"} (:keys r)) "where it is bound, here"))
            (let [r (describe {:file "d.el" :command "no-such-command-here"} nil)]
              (is (false? (:exists r)))
              (is (re-find #"not be installed" (:note r))))
            (let [r (describe {:file "d.el" :prefix "C-x"} nil)]
              (is (some #{{:key "C-x C-f" :command "find-file"}} (:keys r)) "the keys after a prefix")
              (is (= (:total r) (count (:keys r)))))
            (is (true? (:prefix (describe {:file "d.el" :key "C-x"} nil))) "a prefix key says so")
            (is (re-find #"not a prefix" (:note (describe {:file "d.el" :prefix "C-c z z"} nil))))
            (let [r (describe {:file "d.el" :apropos "eval defun"} nil)]
              (is (some #(and (= "eval-defun" (:command %)) (some #{"C-M-x"} (:keys %))) (:commands r)))
              (is (every? #(and (str/includes? (:command %) "eval") (str/includes? (:command %) "defun")) (:commands r))
                  "every word in each name"))
            (let [r (describe {:file "d.el" :modes true} nil)]
              (is (some #{"abbrev-mode"} (:minor r)) "the minor modes on in that buffer"))))
        (testing "habits: what the person ran, kept only while they let it be, and the tips taken up"
          (let [tool (fn [n] (:fn (first (filter #(= n (:name %)) (emacs/commands cfg {:home home})))))
                habits (tool "emacs$habits")
                ran (fn [cmd n & [via-mx]]
                      (emacs/call! cfg "eval" {:code (str "(dotimes (_ " n ") (let ((this-command '" cmd ") (real-this-command '"
                                                         (if via-mx "execute-extended-command" cmd) ")) (byj--observe)))")}
                                   {:home home}))]
            (let [r (habits {} nil)]
              (is (false? (:on r)))
              (is (re-find #"M-x byj-observe-mode" (:note r)) "off until the person turns it on"))
            (emacs/call! cfg "eval" {:code "(byj-observe-mode 1)"} {:home home})
            (ran "next-line" 12)
            (ran "goto-line" 1 true)
            (ran "self-insert-command" 30)
            (is (= {:tips 1} (habits {:tip "goto-line" :why "jump to a line by its number"} nil)))
            (ran "goto-line" 2)
            (let [r (habits {} nil)]
              (is (true? (:on r)))
              (is (= 45 (:commands-run r)))
              (is (= [{:command "next-line" :runs 1 :longest 12}] (filter #(= "next-line" (:command %)) (:runs r)))
                  "a small move run many times in a row")
              (is (some #(and (= "goto-line" (:command %)) (some #{"M-g g"} (:keys %))) (:mx-with-keys r))
                  "run through M-x, though it has a key")
              (is (not-any? #(str/includes? (:command %) "self-insert") (:top r)) "typing is not a habit to list")
              (is (= [{:command "goto-line" :why "jump to a line by its number" :used-since 2}]
                     (map #(dissoc % :minutes-ago) (:tips r)))
                  "the tip, and how often its command ran after it"))
            (is (= "nil" (:value (:ok (emacs/call! cfg "eval" {:code "(seq-some (lambda (e) (aref e 1)) (seq-filter (lambda (e) (eq (aref e 0) 'self-insert-command)) byj--observed))"} {:home home}))))
                "no key kept for typing: what was typed is never kept")
            (let [own (fn [cmd keys] (:value (:ok (emacs/call! cfg "eval" {:code (str "(cl-letf (((symbol-function 'this-command-keys-vector) (lambda () " keys ")))"
                                                                                   " (byj--own-key '" cmd "))")} {:home home}))))]
              (is (= "\"M-g g\"" (own "goto-line" "(kbd \"M-g g\")")) "a key that is the command's own binding is kept")
              (is (= "nil" (own "self-insert-command" "(vector ?a)")) "typing is not")
              (is (= "nil" (own "goto-line" "(vector ?s ?e ?c ?r ?e ?t)")) "nor keys that are not the command's binding, such as text typed"))
            (emacs/call! cfg "eval" {:code "(byj-observe-mode -1)"} {:home home})
            (is (= "nil" (:value (:ok (emacs/call! cfg "eval" {:code "byj--observed"} {:home home})))) "off forgets it all")))
        (testing "suggest: a command put in front of the person, its keys looked up there"
          (let [suggest (:fn (first (filter #(= "emacs$suggest" (:name %)) (emacs/commands cfg {:home home}))))
                r (suggest {:command "goto-line" :why "Jump to a line by its number"} nil)]
            (is (= "*byj-howto*" (:buffer r)))
            (is (some #{"M-g g"} (:keys r)))
            (let [card (:value (:ok (emacs/call! cfg "eval" {:code "(with-current-buffer \"*byj-howto*\" (buffer-substring-no-properties (point-min) (point-max)))"} {:home home})))]
              (is (str/includes? card "Jump to a line by its number"))
              (is (str/includes? card "M-g g"))
              (is (str/includes? card "Run goto-line") "a button the person presses"))
            (is (re-find #"No command no-such-cmd" (:error (suggest {:command "no-such-cmd"} nil))))))
        (testing "features and places: what the person's Emacs has, and where they have been"
          (let [features (:fn (first (filter #(= "emacs$features" (:name %)) (emacs/commands cfg {:home home}))))
                r (features {:features ["org" "no-such-feature-x"] :packages ["org"] :programs ["sh" "no-such-program-x"] :file "d.el"} nil)]
            (is (string? (:emacs r)))
            (is (= [{:name "org" :loadable true} {:name "no-such-feature-x" :loadable false}] (map #(select-keys % [:name :loadable]) (:features r))))
            (is (= [true false] (map #(some? (:path %)) (:programs r))) "where a program is on exec-path, or that it is not")
            (is (= "emacs-lisp-mode" (:mode r)))
            (is (contains? r :eglot-server) "the server eglot would start, named or null"))
          (let [p (emacs/places! cfg {:visible true :trail true} {:home home})]
            (is (some #(true? (:selected %)) (:visible p)) "the window the person is in")
            (is (every? #(pos? (:line %)) (:visible p)))
            (is (seq (:trail p)))
            (is (every? #(string? (:file %)) (:trail p)) "files they visited, most recent first")))
        (testing "turn: the session's state in the mode line, an older word ignored"
          (let [line (fn [] (:value (:ok (emacs/call! cfg "eval" {:code (str "(with-current-buffer (find-file-noselect " (terminal/string-literal (str f)) ")"
                                                                                 " (substring-no-properties (or (byj--mode-line-text) \"\")))")}
                                                       {:home home}))))]
            (is (= {:state "busy" :turn 3} (:ok (emacs/call! cfg "turn" {:state "busy" :turn 3 :question "q" :at 100} {:home home}))))
            (is (= "\" byj⏳3\"" (line)))
            (is (= "t" (:value (:ok (emacs/call! cfg "eval" {:code "(and (member 'byj--mode-line-construct global-mode-string) t)"} {:home home}))))
                "in the mode line of every buffer, saying something only in the project's")
            (emacs/call! cfg "turn" {:state "idle" :elapsed-ms 4000 :files 2 :at 300} {:home home})
            (is (= "\" byj✓3 ✎2\"" (line)) "the files it changed, kept for a review")
            (is (= {:ignored true} (:ok (emacs/call! cfg "turn" {:state "busy" :turn 2 :at 200} {:home home})))
                "a start sent before the end that arrived first")
            (is (= "\" byj✓3 ✎2\"" (line)))
            (is (str/includes? (:value (:ok (emacs/call! cfg "eval" {:code "(with-current-buffer (messages-buffer) (buffer-string))"} {:home home})))
                               "byj: turn 3 done (4s), 2 files changed")
                "its end said in the echo area")
            (emacs/call! cfg "turn" {:state "idle" :ended "cancelled" :elapsed-ms 0 :at 400} {:home home})
            (is (str/includes? (:value (:ok (emacs/call! cfg "eval" {:code "(with-current-buffer (messages-buffer) (buffer-string))"} {:home home})))
                               "byj: turn 3 ended: cancelled"))
            (is (= "\"\"" (:value (:ok (emacs/call! cfg "eval" {:code "(with-temp-buffer (setq default-directory \"/\") (or (byj--mode-line-text) \"\"))"} {:home home}))))
                "outside the project, nothing")))
        (testing "Emacs asks byj: the session that called, else a live one, the answer in *byj*"
          (let [bin (io/file home "byj")
                log (io/file home "byj.log")
                other (io/file home "elsewhere" "f.el")
                ;; with no session of its own, so the one remembered stays
                el (fn [code] (:value (:ok (emacs/call! (dissoc cfg :session-id) "eval" {:code code} {:home home :timeout-ms 20000}))))
                ;; the commands' processes waited for, their sentinels run
                settle "(let ((n 0)) (while (and (< n 200) (seq-some (lambda (p) (string-prefix-p \"byj-ask\" (process-name p))) (process-list))) (accept-process-output nil 0.05) (setq n (1+ n))))"
                ;; a call a record, an argument a unit: a question may hold newlines
                lines (fn [] (mapv #(str/split % #"\x1f") (remove str/blank? (str/split (slurp log) #"\x1e"))))
                ;; the buffer's text: eval's value is it printed, as a literal
                in (fn [file code] (some-> (el (str "(progn (with-current-buffer (find-file-noselect " (terminal/string-literal (str file)) ")"
                                            " (goto-char (point-max)) (forward-line -1) " code ") " settle
                                            " (with-current-buffer byj-buffer (buffer-substring-no-properties (point-min) (point-max))))")) read-string))]
            (spit bin (str "#!/bin/sh\n"
                           "for a in \"$@\"; do printf '%s\\037' \"$a\"; done >> " (terminal/string-literal (str log)) "\n"
                           "printf '\\036' >> " (terminal/string-literal (str log)) "\n"
                           "case \"$*\" in\n"
                           "  *'session list'*) printf '[{\"session-id\":\"s-live\",\"live?\":true}]' ;;\n"
                           "  *'--attach s-gone'*) printf '{\"success\":false,\"error\":\"no live session s-gone\"}'; exit 1 ;;\n"
                           "  *'emacs changes'*) cat " (terminal/string-literal (str home "/changes.json")) " ;;\n"
                           "  *) printf '{\"success\":true,\"answer\":\"it is one\"}' ;;\n"
                           "esac\n"))
            (fx/sh {:out :string :err :string} "chmod" "+x" (.getPath bin))
            (io/make-parents other)
            (spit other "(defun other-f () 1)\n")
            (el (str "(setq byj-program " (terminal/string-literal (str bin)) ")"))
            (testing "hello: the session that calls is the one asked"
              (emacs/register! (assoc cfg :session-id "s-hello") {:home home})
              (is (= "s-hello" (loop [n 0]
                                 (let [v (el (str "(gethash " (terminal/string-literal dir) " byj--sessions)"))]
                                   (if (or (= "\"s-hello\"" v) (< 100 n)) (read-string v) (do (Thread/sleep 50) (recur (inc n))))))))
              (is (= {:server sock} (:ok (emacs/call! cfg "hello" {} {:home home}))))
              (is (= "\"s-real\"" (el (str "(gethash " (terminal/string-literal dir) " byj--sessions)")))
                  "a byj with no session leaves the one remembered"))
            (let [shown (in f "(byj-ask \"what is this?\")")
                  [args] (lines)]
              (is (= ["-C" (str dir "/") "ask" "--attach" "s-real" "--json" "-t" "300" "-q" "what is this?"] args)
                  "the session byj called from, a turn over its channel")
              (is (str/includes? shown "› what is this?"))
              (is (str/includes? shown "‹ s-real · what is this?\nit is one") "the answer shown, with the question it answers"))
            (in f "(byj-check)")
            (is (re-find #"^Check my work in .*a\.txt \(I am at line 4\).* @here$" (last (last (lines))))
                "an Emacs byj reaches: @here reads it")
            (in other "(byj-explain)")
            (let [[listed asked] (take-last 2 (lines))]
              (is (= ["session" "list" "--live" "--json"] (drop 2 listed)) "no session called from there: a live one")
              (is (= "s-live" (nth asked 4)))
              (is (str/includes? (last asked) "(defun other-f () 1)") "the defun at point, in the question")
              (is (not (str/includes? (last asked) "@here")) "an Emacs byj does not reach is not @here's"))
            (let [shown (in f (str "(puthash " (terminal/string-literal dir) " \"s-gone\" byj--sessions) (byj-hint)"))
                  tail (take-last 3 (lines))]
              (is (= ["s-gone" "--live" "s-live"] (map #(nth % 4) tail)) "a session not live: forgotten, a live one asked once")
              (is (re-find #"‹ s-live · I am stuck in .*\nit is one$" (str/trim shown))))
            (is (= "1" (el "(progn (setq byj--notes nil) (byj-notes) (with-current-buffer byj-notes-buffer (if (string-match-p \"notes: 0\" (buffer-string)) 1 0)))"))
                "byj-notes lists them")
            (is (= "t" (el "(eq (lookup-key byj-command-map \"a\") 'byj-ask)")) "the map, bound by the person")
            (testing "explain-error: the newest error, a backtrace before the messages"
              (in f "(with-current-buffer (get-buffer-create \"*Backtrace*\") (insert \"Debugger entered--Lisp error: (void-variable byj-nope)\\n\")) (byj-explain-error)")
              (let [q (last (last (lines)))]
                (is (str/starts-with? q "Explain this error from my Emacs's *Backtrace*: "))
                (is (str/includes? q "(void-variable byj-nope)") "its text in the question")
                (is (str/includes? q "emacs$describe") "keys checked in this Emacs before they are named"))
              (in f "(kill-buffer \"*Backtrace*\") (message \"byj-boom-here\") (byj-explain-error)")
              (let [q (last (last (lines)))]
                (is (str/starts-with? q "Explain this error from my Emacs's *Messages*: "))
                (is (str/includes? q "byj-boom-here") "else the end of *Messages*"))
              (is (= "t" (el "(eq (lookup-key byj-command-map \"x\") 'byj-explain-error)"))))
            (testing "handed over from where it is: a region, unsaved edits, a TODO, a checklist step"
              (let [g (io/file dir "h.txt")
                    gl (terminal/string-literal (str g))]
                (spit g "alpha\nbeta TODO make it gamma\n")
                (el (str "(with-current-buffer (find-file-noselect " gl ") (goto-char (point-min)) (set-mark (point)) (forward-line 1) (activate-mark)"
                         " (byj-do-region \"upcase it\") " settle ")"))
                (let [q (last (last (lines)))]
                  (is (str/starts-with? q (str "In " g ", lines 1 to 2: upcase it\n")))
                  (is (str/includes? q "emacs$edit"))
                  (is (str/ends-with? q "alpha\n") "the region's text"))
                (el (str "(with-current-buffer (find-file-noselect " gl ") (deactivate-mark) (goto-char (point-max)) (forward-line -1) (byj-todo-at-point) " settle ")"))
                (is (re-find #"^Do this, from .*h\.txt at line 2: beta TODO make it gamma" (last (last (lines)))))
                (is (re-find #"No unsaved edits" (str (:error (emacs/call! (dissoc cfg :session-id) "eval" {:code (str "(with-current-buffer (find-file-noselect " gl ") (byj-review-unsaved))")} {:home home})))))
                (el (str "(with-current-buffer (find-file-noselect " gl ") (insert \"x\") (byj-review-unsaved) " settle ")"))
                (is (str/starts-with? (last (last (lines))) (str "Review my unsaved changes in " g ":")))))
            (testing "the checklist: the todo file itself, a step ticked there saved and told"
              (let [todo (io/file dir ".brainyard" "todos" "coach-moving.md")]
                (spit (doto todo io/make-parents) "# moving\n\n- [ ] by words\n- [ ] to a line\n")
                (el (str "(with-current-buffer (find-file-noselect " (terminal/string-literal (str f)) ") (byj-todo))"))
                (is (= "t" (el (str "(with-current-buffer (get-file-buffer " (terminal/string-literal (str todo)) ") (and byj-todo-mode auto-revert-mode t))")))
                    "kept in step with what byj writes")
                (el (str "(progn (with-current-buffer (get-file-buffer " (terminal/string-literal (str todo)) ") (goto-char (point-min)) (search-forward \"by words\") (byj-todo-toggle)) " settle ")"))
                (is (= "# moving\n\n- [x] by words\n- [ ] to a line\n" (slurp todo)) "ticked and saved")
                (is (= "I did this step of the checklist coach-moving: by words. Check it if you can, and say what is next."
                       (last (last (lines)))))))
            (testing "how: the question asks for the command shown, the person to run it"
              (in f "(byj-how \"jump to a line\")")
              (let [q (last (last (lines)))]
                (is (str/starts-with? q "In my Emacs, how do I jump to a line? "))
                (is (str/includes? q "emacs$suggest")))
              (is (= "t" (el "(eq (lookup-key byj-command-map \"?\") 'byj-how)"))))
            (testing "review: a turn's git snapshots shown by vc (magit when there is one), a line commented on and sent back"
              (let [r (io/file dir "r.txt")
                    rl (terminal/string-literal (str r))
                    cfg {:project-dir dir :working-dir dir :session-id "s-live"}]
                (fx/sh {:dir dir :out :string :err :string} "git" "init" "-q")
                (spit r "x\nold\n")
                (changes/begin-turn! cfg 2 "change r")
                (spit r "x\nNEW\n")
                (is (= 1 (changes/end-turn! cfg)))
                (let [{:keys [before after] :as d} (changes/diff cfg "s-live")]
                  (spit (io/file home "changes.json") (json/write-str d))
                  (el (str "(with-current-buffer (find-file-noselect " rl ") (byj-review))"))
                  (is (some #(= ["emacs" "changes" "s-live" "--json"] (drop 2 %)) (lines)) "the session's last turn that changed files")
                  (is (= "(\"diff-mode\" t)" (el "(with-current-buffer \"*byj-review*\" (list (symbol-name major-mode) byj-review-mode))"))
                      "no magit here: vc's diff, with byj-review-mode over it")
                  (is (str/includes? (el "(with-current-buffer \"*byj-review*\" (buffer-string))") "-old\n+NEW") "git's diff of the two snapshots")
                  (el "(with-current-buffer \"*byj-review*\" (goto-char (point-min)) (search-forward \"+NEW\") (byj-review-comment \"why NEW?\"))")
                  (el (str "(progn (with-current-buffer \"*byj-review*\" (byj-review-send)) " settle ")"))
                  (let [q (last (last (lines)))]
                    (is (str/starts-with? q (str "I reviewed what turn 2 changed (r.txt; `git diff " before " " after "` shows it).")))
                    (is (str/includes? q "About `+NEW` in r.txt (@@ -1,2 +1,2 @@): why NEW?"))
                    (is (str/includes? q "emacs$read") "the files read as they are now")
                    (is (= "s-live" (nth (last (lines)) 4)) "to the session whose turn it was"))
                  (is (= "nil" (el "byj--review-said")) "sent once")
                  (is (= (pr-str [(str before ".." after) "t"])
                         (el (str "(progn (provide 'magit)"
                                  " (defun magit-diff-range (range &rest _) (setq byj--test-range range) (get-buffer-create \"*magit-stub*\"))"
                                  " (with-current-buffer (find-file-noselect " rl ") (byj-review))"
                                  " (prog1 (vector byj--test-range (format \"%s\" (with-current-buffer \"*magit-stub*\" byj-review-mode)))"
                                  " (fmakunbound 'magit-diff-range) (setq features (delq 'magit features))))")))
                      "with magit: the turn's range in magit-diff-range"))
                (is (= "t" (el "(eq (lookup-key byj-command-map \"r\") 'byj-review)")))))))
        (finally
          (fx/sh {:out :string :err :string} "emacsclient" "-s" sock "--eval" "(kill-emacs)")
          (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath sock-dir)))))))

(deftest a-file-goes-to-emacs-absolute-and-a-tangle-is-checked-first
  (let [dir (fresh-dir "abs")
        home (fresh-dir "home")
        tmp (fresh-dir "tmp")
        opts {:env {"TMPDIR" tmp} :uid "7" :home home}
        cfg {:project-dir dir :working-dir dir}
        sock (io/file tmp "emacs7" (emacs/server cfg))
        tool (fn [n] (first (filter #(= n (:name %)) (emacs/commands cfg opts))))]
    (io/make-parents sock)
    (spit sock "")
    (testing "a relative path is the working directory's, not Emacs's"
      (binding [emacs/*emacsclient* (stand-in dir {:answer "{\"ok\": {}}"})]
        ((:fn (tool "emacs$read")) {:file "src/a.clj"} {})
        (is (= (str dir "/src/a.clj") (:file (json/read-str (slurp (io/file dir "args.json"))))))))
    (testing "targets outside the writable directories: refused, and Emacs never asked to tangle"
      (binding [emacs/*emacsclient* (stand-in dir {:answer "{\"ok\": {\"targets\": [\"/etc/byj-x.sh\"]}}"})]
        (let [r ((:fn (tool "emacs$org-tangle")) {:file "lit.org"} {:config {:allowed-dirs [dir]}})]
          (is (re-find #"^refused: it would write outside the directories tools may write: /etc/byj-x.sh" (:error r)))
          (is (str/includes? (last (str/split-lines (slurp (io/file dir "argv")))) "(byj-call 'org-tangle-targets ")
              "the last thing asked was where it would write"))))
    (testing "what the check says"
      (is (nil? (emacs/tangle-problem cfg {:config {:allowed-dirs [dir]}} [(str dir "/a.el") (str dir "/out/b.sh")])))
      (is (re-find #"login or a key" (emacs/tangle-problem cfg {:config {:allowed-dirs ["/x"] :denied-paths ["/x/.ssh"]}} ["/x/.ssh/config"]))))))

(deftest a-tangled-file-is-written-in-its-org-file
  (let [dir (fresh-dir "guard")
        org (io/file dir "lit.org")
        out (io/file dir "lit.el")
        plain (io/file dir "plain.el")
        guard (fn [tool args & [cfg]]
                (literate/tangled-file-guard {:tool-name tool :args args}
                                             {:config (merge {:working-dir dir} cfg)}
                                             {:tools #{"write-file" "update-file"}}))]
    (spit org "* x\n")
    (spit out (str ";; [[file:lit.org::*x][x:1]]\n(defun f () 1)\n;; x:1 ends here\n"))
    (spit plain "(defun g () 2)\n;; see [[file:lit.org::*x][x]]\n")
    (is (= (.getCanonicalPath org) (literate/tangled-from out)) "its link comments name its source")
    (is (nil? (literate/tangled-from plain)) "a link with no block end is not a tangle")
    (is (nil? (literate/tangled-from org)) "an org file is a source, never output")
    (let [r (guard "update-file" {:path "lit.el"})]
      (is (= :replace (:result r)))
      (is (str/includes? (get-in r [:replacement :error]) (str "tangled from " (.getCanonicalPath org))))
      (is (str/includes? (get-in r [:replacement :error]) "emacs$org-tangle")))
    (is (nil? (guard "read-file" {:path "lit.el"})) "reading it is not held")
    (is (nil? (guard "write-file" {:path "plain.el"})))
    (is (nil? (guard "write-file" {:path "lit.el"} {:literate-guard? false})) "off, it is written as any file")
    (.delete org)
    (is (nil? (guard "write-file" {:path "lit.el"})) "its org file gone, nothing to send the change to"))
  (is (some #(= "tangled-file-guard" (:id %)) (map hookbox/load-builtin ["tangled-file-guard"]))
      "the spec loads, its :impl known"))

(deftest coaching-is-kept-in-the-session-until-stopped
  (let [dir (fresh-dir "coach")
        cfg {:project-dir dir :working-dir dir :session-id "s-coach"}
        templates (clojure.edn/read-string (slurp (io/resource "brainyard/prompts/coach.edn")))]
    (is (re-find #"no session id" (:error (coach/set-stance! {:project-dir dir} {:goal "x"}))) "nowhere to keep it")
    (is (nil? (coach/stance cfg)) "off until set")
    (is (re-find #"give :goal" (:error (coach/set-stance! cfg {}))))
    (let [m (coach/set-stance! cfg {:goal "Add a --json flag to session list"})]
      (is (= {:goal "Add a --json flag to session list" :level "practitioner" :subject "code" :slug "coach-add-a-json-flag-to-session"}
             (dissoc m :started-at)) "practitioner unless asked, the code unless Emacs")
      (is (= m (coach/stance cfg)) "kept in the session's own directory")
      (is (.isFile (io/file dir ".brainyard" "sessions" "s-coach" "coach.edn"))))
    (is (= "beginner" (:level (coach/set-stance! cfg {:goal "learn transducers" :level "beginner"}))))
    (testing "every turn is told the goal and how to coach while it is on"
      (let [st (atom {})]
        (session/coach-context {:st-memory st :config cfg})
        (let [t (:coach-context @st)]
          (is (str/starts-with? t "## Coaching: learn transducers"))
          (is (str/includes? t (get-in templates [:levels :beginner])) "the level's words")
          (is (str/includes? t "`coach-learn-transducers`") "the checklist's name")
          (is (str/includes? t "emacs$note") "how to leave findings"))))
    (testing "Emacs itself coached from a lesson, checked by what was run"
      (is (= "emacs" (:subject (coach/set-stance! cfg {:goal "moving around" :subject "emacs"}))))
      (let [st (atom {})]
        (session/coach-context {:st-memory st :config cfg})
        (let [t (:coach-context @st)]
          (is (str/starts-with? t "## Coaching Emacs: moving around"))
          (is (str/includes? t "emacs$lessons"))
          (is (str/includes? t "emacs$habits") "a step checked by the commands they ran")
          (is (str/includes? t "emacs$describe :command") "keys looked up, never remembered")))
      (is (= "code" (:subject (coach/set-stance! cfg {:goal "x" :subject "cooking"}))) "an unknown subject is the code's")
      (spit (coach/file cfg) (pr-str {:goal "older" :level "beginner" :slug "coach-older"}))
      (let [st (atom {})]
        (session/coach-context {:st-memory st :config cfg})
        (is (str/starts-with? (:coach-context @st) "## Coaching: older") "a stance kept before subjects is the code's"))
      (coach/set-stance! cfg {:goal "learn transducers" :level "beginner"}))
    (is (= {:stopped "learn transducers"} (coach/set-stance! cfg {:stop true})))
    (is (nil? (coach/stance cfg)))
    (let [st (atom {:coach-context "old"})]
      (session/coach-context {:st-memory st :config cfg})
      (is (nil? (:coach-context @st)) "off, a turn is told nothing"))
    (is (= {:stopped nil :note "no coaching was on"} (coach/set-stance! cfg {:stop true})))
    (is (= ["coach$set"] (map :name (coach/commands cfg))))))
