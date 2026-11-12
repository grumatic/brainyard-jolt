;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.tui-verify
  "Drive the full-screen renderer (RFC-028) through a REAL session and read back
  what a person would see. brainyard's `docs/tui/nrepl-verify.md` in the port's
  own shape.

      jolt -A:dev -m brainyard.dev.tui-verify        # print the frames
      clj-nrepl-eval -p 7899 '(brainyard.dev.tui-verify/report)'
      clj-nrepl-eval -p 7899 '(brainyard.dev.tui-verify/live)'    # a real model

  brainyard verifies a live TUI with two channels: nREPL to reload and inspect,
  and `tmux send-keys` for input, because its renderer paints the terminal it
  was given and nothing else can read it back. Here the nREPL is both channels:
  glimmer-tui paints through a swappable screen, so `attach!` points the
  backend at an in-memory grid, `press!` types into it and `screen/lines` says
  what is on it. No tmux, no tty, no second process.

  What this checks that `brainyard.tui-test` does not: the real session loop is
  what drives the renderer. The tests call the seams directly, which proves the
  renderer answers them; this runs `session/run`, so the tree, the turn, tool
  dispatch, the slash commands and the renderer are all the live ones, wired
  the way `main` wires them.

  A scripted model by default: it calls a tool on its first call and answers on
  its second, which is what puts something real in the live block. The CLI's
  `-p mock` answers directly and never calls anything, so it cannot show that
  path at all.

  `live` runs the same drive against a real provider, which is the only way to
  see what a model's own markdown, tool choices and pauses look like on the
  screen. It spends the user's quota — run it to confirm a fix, a few times,
  not in a loop (AGENTS.md)."
  (:require [clojure.string :as str]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.permission :as permission]
            [brainyard.session :as session]
            [brainyard.tui :as tui]
            [glimmer-tui.core :as g]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.widget :as w]
            [glimmer.core :as ui]))

(defn- wait-for
  "Wait for `pred`, answering the permission gate while waiting. A real model
  asks before it runs anything the config gates, and a driver that only waits
  would wait forever — the seam blocks the turn with no timeout by design
  (RFC-027)."
  [what pred & {:keys [timeout-ms on-gate gate]
                :or {timeout-ms 8000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      ;; paint every poll: nothing else does while this waits, and a predicate
      ;; that reads the SCREEN would otherwise be reading the frame before the
      ;; thing it is waiting for
      (g/frame!)
      (cond
        (pred) true
        (and gate @gate) (do (when on-gate (on-gate))
                             (g/frame!)
                             (g/press! 10)                ; Enter on "allow once"
                             (g/frame!)
                             (recur))
        (> (System/currentTimeMillis) deadline) (do (println "TIMED OUT:" what) false)
        :else (do (Thread/sleep 5) (recur))))))

(defn- type-line!
  "Type `s` and press return, painting between keystrokes the way the loop does
  — the focus ring is recomputed while painting, so typing blind types at the
  tree as it was before the first character.

  The pause before the return is the point at which a driver stops being a
  paste: a newline within `tui/paste-gap-ms` of a character IS a pasted one
  (byj-gly.97), and a driver types faster than any terminal pastes."
  [s]
  (doseq [c s] (g/press! (int c)) (g/frame!))
  (Thread/sleep (inc tui/paste-gap-ms))
  (g/press! 10)
  (g/frame!))

(defn- scripted-llm
  "Tool call, then answer. The answer is markdown, because that is what a model
  writes and what the renderer has to read."
  []
  (let [calls (atom 0)]
    (fn [_messages]
      (if (= 1 (swap! calls inc))
        (json/write-str {:reasoning "I should look at the directory first."
                         :tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}]
                         :answer ""})
        (json/write-str {:reasoning "Now I can answer."
                         :tool-calls []
                         :answer (str "# What is here\n\nOne **file**, called "
                                      "`notes.txt`.\n\n```\nhello\n```")})))))

(defn- emphasised
  "The runs the screen painted with an inline style, as `[text style]`.

  This is the evidence that `**bold**` arrived AS bold: the text of a frame
  cannot tell an emphasis that was rendered from one that was dropped, since
  both read the same once the markers are gone (`byj-gly.101`)."
  [screen]
  (let [[cols rows] (scr/size screen)]
    (vec (for [y (range rows)
               run (partition-by #(select-keys (or (scr/style-at screen % y) {}) [:bold :fg])
                                 (range cols))
               :let [style (select-keys (or (scr/style-at screen (first run) y) {}) [:bold :fg])
                     text (str/trim (apply str (map #(scr/text-at screen % y 1) run)))]
               :when (and (seq style) (seq text))]
           [text style]))))

(defn verify
  "Run a turn, a slash command and a quit through the renderer. Returns
  `{:frames [[label lines] …] :final … :thread-finished?}`.

  Options: `:lm` (the scripted model by default), `:question`, `:timeout-ms`,
  the screen's `:cols`/`:rows`, and `:rails` — views for the frame's rails
  (byj-gly.123), which the drive then opens, moves in and closes. The turn is over when the status bar says
  so rather than when some phrase appears — a real model writes its own words.

  The session gets its own `:project-dir` under `/tmp`: a turn leaves a
  trajectory where it runs (RFC-025), and a verification that writes into the
  repository is a verification nobody can run twice. The permission gate is the
  renderer's own overlay, answered by the driver — with a real model this is the
  seam that would otherwise hang the turn forever."
  ([] (verify {}))
  ([{:keys [lm question timeout-ms cols rows rails]
     :or {question "what is in this directory"
          timeout-ms 8000 cols 74 rows 16}}]
   (let [dir (str "/tmp/by-tui-verify-" (System/currentTimeMillis))
         _ (do (.mkdirs (java.io.File. dir))
               (spit (str dir "/notes.txt") "hello"))
         r (tui/make {:commands session/commands :rails rails})
         root (w/node :window {})
         screen (scr/buffer-screen cols rows)
         frames (atom [])
         gates (atom 0)
         snap! (fn [label] (g/frame!) (swap! frames conj [label (vec (scr/lines screen))]) nil)
         gate (:gate (:cells r))
         status (:status (:cells r))
         final (atom nil)
         ;; a non-blank answer: `emit` writes a blank line before the answer
         ;; itself, and counting that one catches the frame between the two
         answers #(count (filter (fn [e] (and (#{:answer :heading} (:kind e)) (seq (:text e))))
                                 @(:lines (:cells r))))
         answers-before (atom 0)
         session-thread
         (Thread. (fn []
                    (reset! final
                            (session/run (merge (tui/seams r)
                                                {:lm (or lm {:provider :mock :format :mock
                                                             :responses (scripted-llm)})
                                                 ;; the gate as main wires it: the
                                                 ;; overlay is the ask channel and
                                                 ;; permission/ owns the policy
                                                 :permission-fn (permission/asking-permission-fn (:ask r))
                                                 :config {:working-dir dir :project-dir dir
                                                          :max-iterations 6
                                                          :persist-session? false}})))))
         waiting (fn [what pred]
                   (wait-for what pred
                             :timeout-ms timeout-ms
                             :gate gate
                             :on-gate #(when (= 1 (swap! gates inc))
                                         (snap! "a permission ask, answered by the driver"))))]
     (g/attach! root screen)
     (ui/mount root :window [(:view r)])
     (g/frame!)
     (tui/measure! r)
     (snap! "an empty session")

     (.start session-thread)
     (waiting "the session to ask for input" #((:waiting-for-input? r)))
     ;; counted BEFORE the question goes in: a mock turn can be over by the
     ;; time the mid-turn frame is taken, and a count taken after it would be
     ;; waiting for an answer that has already landed
     (reset! answers-before (answers))
     (type-line! question)
     (waiting "the turn to start" #(:running? @status))

     ;; caught while the turn is still running: this is the frame that says
     ;; whether an unverbose session shows anything at all while it works. A
     ;; model that answers without calling anything ends the wait instead.
     (waiting "a tool call to reach the block"
              #(or (seq (:calls @(:iter (:cells r)))) (not (:running? @status))))
     (snap! "mid-turn: the live block")

     ;; NOT merely "not running": the session emits :session.turn/end before it
     ;; emits the answer, so a snap taken on the status alone catches the frame
     ;; one line before the thing the turn was for
     (waiting "the answer to land" #(and (not (:running? @status)) (> (answers) @answers-before)))
     (snap! "after the turn")

     (doseq [c "/us"] (g/press! (int c)) (g/frame!))
     (snap! "a half-typed command")
     ;; a pause first, as a person's: an Enter this close to the last key is
     ;; a pasted line break (tui/paste-gap-ms), not a choice
     (Thread/sleep (inc tui/paste-gap-ms))
     (g/press! 10)                                    ; Enter takes the completion
     (g/frame!)
     (Thread/sleep (inc tui/paste-gap-ms))
     (g/press! 10)
     (waiting "the usage line" #(some (fn [l] (str/includes? l "turn(s)")) (scr/lines screen)))
     (snap! "after /usage")

     (when rails
       (g/press! 12) (g/frame!)                       ; ctrl+l
       (snap! "ctrl+l: the left rail, with the keys")
       (g/press! 258) (g/frame!)
       (snap! "↓ in the rail")
       (g/press! 27) (g/frame!)                       ; esc: the keys back
       (g/press! 20) (g/frame!)                       ; ctrl+t
       (snap! "esc, then ctrl+t: the right rail")
       (g/press! 20) (g/frame!)
       (type-line! "/left")
       (snap! "ctrl+t closed it; /left opened the left, the keys in the input"))

     (type-line! "/quit")
     (.join session-thread timeout-ms)
     {:frames @frames
      :emphasised (emphasised screen)
      :final (select-keys @final [:turn-count :usage])
      :gates @gates
      :thread-finished? (not (.isAlive session-thread))
      :dir dir})))

(defn report
  "`verify`, printed as frames. Read them — the point of this is what a person
  would have seen, and an assertion cannot tell you a screen is unreadable."
  ([] (report {}))
  ([opts]
   (let [{:keys [frames final gates emphasised thread-finished?]} (verify opts)]
     (doseq [[label lines] frames]
       (println (str "\n── " label " " (apply str (repeat (max 0 (- 60 (count label))) "─"))))
       (doseq [l lines] (println (str "│" l))))
     (println "\nemphasis painted as emphasis:" (pr-str emphasised))
     (println "final:" (pr-str final))
     (println "permission asks answered:" gates)
     (println "session thread finished:" thread-finished?)
     :ok)))

(defn live
  "The same drive against a real provider — `claude-code` on haiku by default.

  This is the only way to see a model's own markdown, its own tool choices and
  its own pauses on the screen; a scripted model shows the renderer working on
  text someone wrote to make it look good. It SPENDS QUOTA: run it to confirm a
  fix, a few times, never in a loop (AGENTS.md)."
  ([] (live {}))
  ([{:keys [provider model question timeout-ms]
     :or {provider :claude-code model "haiku"
          question "list the files in this directory, then tell me what is here"
          timeout-ms 240000}}]
   (report {:lm (llm/create-lm provider #(System/getenv %) {:model model})
            :question question
            :timeout-ms timeout-ms
            :rows 22})))

(defn -main [& _]
  (report)
  (System/exit 0))
