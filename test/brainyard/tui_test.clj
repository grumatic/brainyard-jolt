(ns brainyard.tui-test
  "The full-screen renderer (RFC-028), driven onto an in-memory screen.

  Every test here runs with no tty: `glimmer-tui.core/attach!` points the
  backend at a grid, so a whole interactive UI is mounted, framed, typed into
  and answered in the suite. That is the property the toolkit was chosen for —
  a renderer whose invariants cannot name an enforcer is one nobody can keep
  honest."
  (:require [brainyard.claude-usage :as claude-usage]
            [brainyard.agent]
            [brainyard.toolshelf]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.permission :as permission]
            [brainyard.session :as session]
            [brainyard.tui :as tui]
            [brainyard.tui-frame :as frame]
            [glimmer-tui.core :as g]
            [glimmer-tui.keys]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.text :as text]
            [glimmer-tui.widget :as w]
            [glimmer.backend]
            [glimmer.core :as ui]
            [glimmer.ratom]))

(defn- mounted
  "A renderer on a `cols` x `rows` grid, framed once. Returns the renderer with
  the screen, ready to be typed into."
  [{:keys [cols rows] :or {cols 44 rows 12} :as opts}]
  (let [r (tui/make opts)
        root (w/node :window {})
        screen (scr/buffer-screen cols rows)]
    (g/attach! root screen)
    (ui/mount root :window [(:view r)])
    (g/frame!)
    (tui/measure! r)
    (g/frame!)
    (assoc r :screen screen)))

(defn- screen-text [{:keys [screen]}]
  (str/join "\n" (scr/lines screen)))

(defn- style-of
  "The style the screen painted `needle` with. Text alone cannot tell an
  emphasis that arrived from one that was dropped — both read the same."
  [{:keys [screen]} needle]
  (first (for [[y line] (map-indexed vector (scr/lines screen))
               :let [x (str/index-of line needle)]
               :when x]
           (scr/style-at screen x y))))

(defn- type!
  "Type `s`, a frame between keystrokes the way the loop paints one — the focus
  ring is recomputed while painting, so a test that types blind is typing at
  whatever the tree looked like before its first character."
  [s]
  (doseq [c s] (g/press! (int c)) (g/frame!)))

(defn- enter!
  "Press return the way a person does: after a pause. Keystrokes in a test are
  microseconds apart, which is what a PASTE looks like (`tui/paste-gap-ms`), so
  an Enter with no pause before it is a pasted line break by definition."
  []
  (Thread/sleep (inc tui/paste-gap-ms))
  (g/press! 10)
  (g/frame!))

(defn- awaited
  "Wait up to 2 s for `pred`, the way a driver waits for a kernel thread that
  blocks on a seam. Returns false on a timeout rather than hanging the suite."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 2) (recur))))))

(defn- gate-painted?
  "Frame until the permission modal's buttons are on screen AND hold the focus.
  The gate cell is set on the kernel's thread, and with no event loop running
  glimmer re-renders inline on the thread that changed the ratom, while the
  test frames on its own. A frame snapshots the tree twice — once for the
  focus ring, once for layout — so one taken while the kernel is still
  building the modal can paint the buttons yet keep the ring it had before
  them, the transcript and the input, or none at all; a key pressed then goes
  there (provenance P-30, byj-gly.396). The next frame puts it right.
  `needle` is text the modal shows, \"deny\" unless said."
  [r & [needle]]
  (awaited #(do (g/frame!)
                (and (str/includes? (screen-text r) (or needle "deny"))
                     (= :button (:tag (last (w/path-to (g/tree) (g/focus-id)))))))))

;; --- text ----------------------------------------------------------------------------

(deftest the-binary-runs-what-is-rebound
  ;; provenance P-24: a release `jolt build` direct-links calls between defs, so
  ;; a var another namespace alter-var-roots is rebound for `jolt run` and the
  ;; suite, and never for the binary. The rebindings here — glimmer-tui's
  ;; reader, keys, timers and repaint, clojure.core/deref — did nothing in it,
  ;; and every mouse report was typed into the input.
  ;; byj-gly.272: the last of them, the reader, went upstream, and the binary
  ;; is direct-linked again — so a rebinding added later must bring the flag back
  (let [scan #(for [[_ v] (re-seq #"alter-var-root\s+#'([\w.\-]+/[^\s)]+)" %)] v)
        rebound (for [f (file-seq (io/file "src")) :when (str/ends-with? (.getName f) ".clj")
                      v (scan (slurp f))]
                  v)
        binary (get-in (edn/read-string (slurp "deps.edn")) [:tasks 'build:byj])]
    (is (= ["tui/read-event!"] (scan "(alter-var-root #'tui/read-event! f)")) "non-vacuous: the scan finds one")
    (is (or (empty? rebound) (str/includes? (str binary) "--no-direct-link"))
        (str "the build:byj task must build --no-direct-link while src rebinds " (vec (distinct rebound))))
    (is (empty? rebound) "and nothing is rebound now")))

(deftest ncurses-starts-from-an-entry-whose-rep-it-gets-right
  ;; byj-gly.269: ncurses before 20170729 uses `rep` past 255, so a rule of ─
  ;; came out one glyph long in Ghostty, whose entry declares it
  (is (= 20150808 (tui/ncurses-patch "ncurses 6.0.20150808")))
  (is (= 20240427 (tui/ncurses-patch "ncurses 6.5.20240427")))
  (is (nil? (tui/ncurses-patch "")))
  (let [entries {"xterm-ghostty" :rep "xterm-256color" :plain "xterm" :plain}
        entry #(get entries % :absent)]
    (is (= "xterm-256color" (tui/start-term "xterm-ghostty" 20150808 entry))
        "the old ncurses and an entry with rep: a plain xterm one instead")
    (is (= "xterm-ghostty" (tui/start-term "xterm-ghostty" 20240427 entry))
        "an ncurses that gets rep right keeps the terminal's own")
    (is (= "xterm-256color" (tui/start-term "xterm-256color" 20150808 entry))
        "an entry without rep is kept")
    (is (= "xterm-ghostty" (tui/start-term "xterm-ghostty" nil entry))
        "a version that does not read is not guessed at")
    (is (= "xterm" (tui/start-term "xterm-kitty" 20150808
                                   #(get (merge entries {"xterm-kitty" :rep "xterm-256color" :rep}) % :absent)))
        "the first plain entry, past one that declares rep too")
    (is (= "xterm-kitty" (tui/start-term "xterm-kitty" 20150808 {"xterm-kitty" :rep}))
        "and the terminal's own when there is none")))

(deftest tools-are-browsed-with-each-ones-spec-beside-it
  ;; byj-gly.273.1: every tool, filtered by name before description, the marked
  ;; one's arguments, effects, verdict and both calls in a pane — beside the
  ;; list when there is room, under it when not
  (let [rows (brainyard.toolshelf/rows (brainyard.agent/make-ctx {:config {:working-dir "." :project-dir "."}
                                                                   :lm {:provider :mock :format :mock}}))]
    (testing "wide: the pane beside the list"
      (let [r (mounted {:cols 130 :rows 34})]
        ((:present r) :tools {:rows rows})
        (g/frame!)
        (let [lines (scr/lines (:screen r))
              title (first (filter #(str/includes? % "tools · ") lines))]
          (is (str/includes? title (str "tools · " (count rows) " · type to filter")))
          (is (str/includes? title "│ ") "the pane starts on the title's row, beside it")
          (is (some #(and (str/includes? % "› ") (str/includes? % "│")) lines) "the marked row and the pane share lines")
          (is (some #(str/includes? % "Call directly:") lines)))))
    (testing "narrow and filtered: a name first, no stray heading, the pane under the list"
      (let [r (mounted {:cols 80 :rows 40})
            c (:cells r)]
        ((:present r) :tools {:rows rows :query "grep"})
        (g/frame!)
        (let [lines (vec (scr/lines (:screen r)))
              t (first (keep-indexed #(when (str/includes? %2 "tools · \"grep\"") %1) lines))]
          (is (str/includes? (nth lines (inc t)) "›   grep ") "grep, first, straight under the title")
          (is (some #(str/includes? % "   Arguments:") lines))
          (is (not-any? #(str/includes? % " │ ") lines) "no column: the pane is under the list"))
        (testing "enter drafts the model's call, and closes the picker"
          (g/press! 10)
          (g/frame!)
          (is (= "/grep " @(:draft c)))
          (is (nil? @(:picker c))))
        (testing "tab drafts the direct call, its first argument named"
          ((:present r) :tools {:rows rows :query "grep"})
          (g/frame!)
          ;; tab as the terminal delivers it: the reader hands it on as
          ;; tui/tab-key, which glimmer-tui's focus ring does not take
          (g/press! tui/tab-key)
          (g/frame!)
          (is (= ":grep :pattern " @(:draft c)))
          (is (nil? @(:picker c))))))
    (testing "/tools NAME opens on that tool"
      (let [r (mounted {:cols 80 :rows 40})]
        ((:present r) :tools {:rows rows :select "write-file"})
        (g/frame!)
        (is (= "write-file" (:id (tui/picker-selected @(:picker (:cells r))))))))))

(deftest a-filtered-tools-picker-keeps-the-input-on-screen
  ;; byj-gly.275: the rows were reserved for the list alone — two matches — and
  ;; the pane beside it ran past them; with the transcript full, the input line
  ;; and the footer went off the bottom of the screen
  (let [rows (brainyard.toolshelf/rows (brainyard.agent/make-ctx {:config {:working-dir "." :project-dir "."}
                                                                   :lm {:provider :mock :format :mock}}))]
    (doseq [[cols h] [[150 24] [150 40] [80 30]]]
      (testing (str cols "x" h)
        (let [r (mounted {:cols cols :rows h})]
          (doseq [i (range 80)] ((:say! r) :info (str "line " i)))
          ((:present r) :tools {:rows rows :query "grep"})
          (g/frame!)
          (let [lines (vec (scr/lines (:screen r)))]
            (is (some #(str/includes? % "tools · \"grep\"") lines) "the picker is on screen")
            (is (some #(str/starts-with? (str/triml %) "❯") lines) "and so is the input line")
            (is (str/includes? (peek lines) "esc closes") "and the footer, on the last row")))))))

(deftest a-tool-and-its-arguments-are-completed
  ;; byj-gly.273.4: brainyard completes a tool's name after / and : and stops at
  ;; the first space; this completes its arguments too, from the rows the
  ;; session hands over
  (let [rows (brainyard.toolshelf/rows (brainyard.agent/make-ctx {:config {:working-dir "." :project-dir "."}
                                                                   :lm {:provider :mock :format :mock}}))
        r (mounted {:cols 80 :rows 20 :commands brainyard.session/commands})
        c (:cells r)
        menu #(mapv first (:items @(:menu c)))]
    (is (true? ((:present r) :tool-rows {:rows rows})))
    (testing "after / the tools come after the commands"
      (type! "/to")
      (is (= "/todo" (first (menu))))
      (is (some #{"/tools"} (menu)))
      (is (some #{"/tool$create"} (menu)) "a tool, after them"))
    (testing "after : the tools alone, then a tool's arguments, required first"
      (reset! (:draft c) "")
      (type! ":gre")
      (is (= [":grep"] (menu)))
      (enter!)
      (is (= ":grep " @(:draft c)) "taken")
      (is (= [":pattern" ":path" ":max-results" ":include-exts"] (menu)) "its arguments offered at once")
      (type! "pattern x :ma")
      (is (= [":max-results"] (menu)) "the ones not given, by what is typed"))
    (testing "an enum argument's values"
      (reset! (:draft c) "")
      (type! ":skills$import :path x :scope ")
      (is (= ["project" "user"] (menu))))
    (testing "a command typed exactly has no menu, not even its tools: Enter runs it"
      ;; byj-gly.327, live: /memory offered memory$history, and Enter took it
      (reset! (:draft c) "")
      (type! "/memory")
      (is (empty? (menu)))
      (type! "$")
      (is (some #{"/memory$history"} (menu)) "the tools, once the line is not the command"))))

(deftest a-direct-call-is-drawn-as-a-models-call-is
  ;; byj-gly.273.3: `:<tool> …` comes back through :present :tool-call and is
  ;; drawn as a tool block — the call, its time, the result under it
  (let [r (mounted {:cols 70 :rows 16})]
    (is (true? ((:present r) :tool-call {:tool-name "grep" :tool-args {:pattern "TODO"}
                                         :tool-result (pr-str {:matches ["a.txt:2:two TODO"] :total 1})
                                         :tool-error? false :elapsed-ms 12})))
    (g/frame!)
    (let [text (screen-text r)]
      (is (str/includes? text "⏺ grep(pattern=TODO)"))
      (is (str/includes? text "a.txt:2:two TODO")))))

(deftest wrapping-is-ours-because-a-label-breaks-only-on-newlines
  (testing "a paragraph breaks on spaces"
    (is (= ["one two" "three four" "five six"] (tui/wrap "one two three four five six" 12))))
  (testing "a word wider than the screen is cut rather than hidden"
    (is (= ["supercalif" "ragilistic" "word"] (tui/wrap "supercalifragilistic word" 10))))
  (testing "newlines are kept, and a short line is left alone"
    (is (= ["a" "b"] (tui/wrap "a\nb" 40)))
    (is (= ["short"] (tui/wrap "short" 40)))))

;; --- the screen ----------------------------------------------------------------------

(deftest the-transcript-shows-its-tail-and-scrolls-back
  (let [r (mounted {:cols 30 :rows 10})
        {:keys [emit]} r]
    (dotimes [i 40] (emit :info (str "line " i)))
    (g/frame!)
    (testing "what is on screen is the end of the transcript"
      (is (str/includes? (screen-text r) "line 39"))
      (is (not (str/includes? (screen-text r) "line 10"))))
    (testing "page up walks back, page down follows again"
      (g/press! {:type :page-up})
      (g/frame!)
      (is (str/includes? (screen-text r) "line 32"))
      (is (not (str/includes? (screen-text r) "line 39")))
      (g/press! {:type :page-down})
      (g/frame!)
      (is (str/includes? (screen-text r) "line 39")))
    (testing "only what fits is mounted — a frame costs the screen, not the history"
      (let [labels (filter #(= :label (:tag %)) (w/walk (g/tree)))]
        (is (< (count labels) 20)
            (str "40 lines of transcript mounted " (count labels) " widgets"))))))

(deftest paging-costs-the-screen-not-the-transcript
  ;; byj-gly.132: page keys queued up and answered slowly. The transcript was a
  ;; widget per styled run, laid out again from its markdown on every frame, and
  ;; re-rendered by the spinner's tick eight times a second while idle
  (let [r (mounted {:cols 60 :rows 14})
        table "| a | b | c |\n|---|---|---|\n| 1 | **2** | `3` |\n| 4 | 5 | 6 |"
        layouts (:layouts (:cells r))]
    (dotimes [i 12] ((:emit r) :answer (str "answer " i "\n\n" table)))
    (g/frame!)
    (testing "the transcript is one widget, however many styles its rows carry"
      (is (= 1 (count (filter #(= :transcript (:tag %)) (w/walk (g/tree))))))
      (is (< (count (filter #(= :label (:tag %)) (w/walk (g/tree)))) 12)))
    (testing "the spinner's tick does not lay the transcript out again"
      (let [before @layouts]
        (dotimes [_ 5] (swap! (:tick (:cells r)) inc) (g/frame!))
        (is (= before @layouts))))
    (testing "but paging does, which is what a page key is for"
      (let [before @layouts]
        (g/press! {:type :page-up})
        (g/frame!)
        (is (> @layouts before))))
    (testing "and what it paints is still the transcript, styles and all"
      (g/press! {:type :page-down})
      (g/frame!)
      (is (str/includes? (screen-text r) "answer 11"))
      (is (:bold (style-of r "2 ")))
      (is (= :green (:fg (style-of r "3")))))))

(deftest an-entry-is-laid-out-once-per-width
  (let [lines (atom [{:id 1 :kind :answer :text "| a | b |\n|---|---|\n| 1 | 2 |"}])
        rows-of (tui/rows-cache lines 500)
        entry (first @lines)
        first-time (rows-of entry 40)]
    (is (identical? first-time (rows-of entry 40)) "the second page over it reuses the first layout")
    (is (not (identical? first-time (rows-of entry 30))) "a new width lays it out again")
    (testing "what is still live has no id, and is laid out fresh every time"
      (let [live {:kind :answer :text "streaming"}]
        (is (not (identical? (rows-of live 40) (rows-of live 40))))))))

(deftest a-problem-is-shown-whether-or-not-anyone-asked-and-a-trace-is-not
  ;; one renderer at a time: glimmer-tui has a single attached app, so a second
  ;; `mounted` takes the screen over and the first one's stops being painted
  (testing "without -v: the problem, and not the turn narrating itself"
    (let [quiet (mounted {:cols 60 :rows 8})]
      ((:on-event quiet) :tools/user-tool-skipped {:file "broken.edn"})
      ((:on-event quiet) :agent.iteration/start {:iteration 2})
      (g/frame!)
      (is (str/includes? (screen-text quiet) "user tool skipped"))
      (is (not (str/includes? (screen-text quiet) "iteration")))))
  (testing "with -v: the narration too, named in full"
    ;; an event the renderer has no view of its own for — an iteration or a tool
    ;; call becomes the live block instead, which is shown either way
    (let [loud (mounted {:cols 60 :rows 8 :verbose true})]
      ((:on-event loud) :agent.answer/evaluated {:verdict :ok})
      (g/frame!)
      (is (str/includes? (screen-text loud) "agent.answer/evaluated")))))

(deftest a-subscription-window-near-its-limit-is-shown-beside-the-counts
  ;; byj-gly.324: from the last snapshot only — a frame never calls the network
  (claude-usage/reset!*)
  (try
    (let [r (mounted {:cols 80 :rows 9})]
      ((:on-event r) :session.turn/start {:turn 1 :question "hi"})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "⚠")) "no snapshot: nothing")
      (claude-usage/usage {:read-oauth (constantly {:access-token "t"}) :home "/nonexistent"
                           :fetch (constantly {:status 200 :body "{\"seven_day\":{\"utilization\":93}}"})})
      ;; the row is drawn again when the session's counts move — the next call —
      ;; not when the snapshot lands, which is not a reactive value
      ((:on-event r) :predictor/call {:usage {:input-tokens 1 :output-tokens 1}})
      (g/frame!)
      (is (str/includes? (screen-text r) "⚠ Weekly 93%")))
    (finally (claude-usage/reset!*))))

(deftest the-status-bar-counts-what-the-turn-spends
  (let [r (mounted {:cols 70 :rows 9})]
    ((:on-event r) :session.turn/start {:turn 3 :question "hi"})
    ((:on-event r) :predictor/call {:usage {:input-tokens 120 :output-tokens 7}})
    ((:on-event r) :task/created {:id "task-1"})
    (g/frame!)
    (let [text (screen-text r)]
      (is (str/includes? text "turn 3"))
      (is (str/includes? text "1 call"))
      (is (str/includes? text "tokens 120/7"))
      (is (str/includes? text "1 task")))
    ((:on-event r) :task/completed {:id "task-1" :status :completed})
    ((:on-event r) :session.turn/end {:terminated-by :answer})
    (g/frame!)
    (is (not (re-find #"(thinking|working)…" (screen-text r)))
        "and an idle session has no spinner line")))

(deftest an-iteration-says-what-it-is-doing-without--v
  ;; the live block: brainyard keeps one per iteration and freezes it when the
  ;; next starts. Without it an unverbose session shows nothing while it works.
  ;; A call is a bullet, `name(argument)`, with the start of its output under
  ;; it — and no numbered header above them.
  (let [r (mounted {:cols 60 :rows 12})]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "cat deps.edn"}}
                                                    {:tool-name "read-file" :tool-args {:path "src/x.clj"}}]})
    (g/frame!)
    (testing "the calls are on screen while they run, with what they are doing"
      (let [text (screen-text r)]
        (is (not (str/includes? text "iteration")))
        (is (str/includes? text "⏺ bash(command=cat deps.edn)"))
        (is (str/includes? text "⏺ read-file(path=src/x.clj)"))
        (is (:dim (style-of r "⏺ bash")) "a call still running has a dim bullet")))

    (testing "and their outcome when it lands — a failure reads as one"
      ((:on-event r) :agent.tools/results {:results [{:tool-name "bash" :tool-result "four deps"}
                                                     {:tool-name "read-file" :tool-result "no such file"
                                                      :tool-error? true}]})
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "  ⎿  four deps"))
        (is (str/includes? text "  ⎿  no such file"))
        (is (= :green (:fg (style-of r "⏺ bash"))))
        (is (= :red (:fg (style-of r "⏺ read-file"))) "a failure reads as one")))

    (testing "the next iteration freezes the block into the transcript"
      ((:on-event r) :agent.iteration/start {:iteration 2})
      (g/frame!)
      (is (str/includes? (screen-text r) "bash(command=cat deps.edn)"))
      (is (= {:n 2 :calls [] :results []} @(:iter (:cells r)))
          "and the block that is live is the new iteration's"))

    (testing "an iteration that called nothing says nothing"
      ((:on-event r) :session.turn/end {:terminated-by :answer})
      ((:on-event r) :agent.iteration/start {:iteration 3})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "iteration 3"))))))

(deftest an-iterations-thought-stays-above-its-calls
  ;; byj-gly.280.3: streamed, the thought vanished once the reply landed; it is
  ;; what the model says the last result told it and why these calls
  (let [r (mounted {:cols 70 :rows 16})
        thought "The grep found no caller, so reading deps.edn next."]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.llm/chunk {:field :reasoning :text "The grep found"})
    ((:on-event r) :agent.think/reasoning {:reasoning thought})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "read-file" :tool-args {:path "deps.edn"}}]})
    (g/frame!)
    (testing "the parsed thought replaces the streamed one, above the calls, dim"
      (let [lines (scr/lines (:screen r))
            at #(first (keep-indexed (fn [i l] (when (str/includes? l %) i)) lines))]
        (is (= 1 (count (filter #(str/includes? % "The grep found") lines))) "said once, not streamed and parsed both")
        (is (< (at "The grep found no caller") (at "read-file(path=deps.edn)")))
        (is (:dim (style-of r "The grep found no caller")))
        ;; byj-gly.281: behind a bullet, as a call and an answer are
        (is (some #(str/starts-with? % "⏺ The grep found no caller") lines))))
    (testing "and it stays when the block freezes"
      ((:on-event r) :agent.iteration/start {:iteration 2})
      (g/frame!)
      (is (str/includes? (screen-text r) thought)))
    (testing "an iteration that answers shows no thought: the answer speaks"
      ((:on-event r) :agent.think/reasoning {:reasoning "The result is in, so answering."})
      ((:emit r) :answer "deps.edn has four deps.")
      (g/frame!)
      (is (not (str/includes? (screen-text r) "The result is in")))
      (is (str/includes? (screen-text r) "deps.edn has four deps.")))
    (testing "a subagent's thought is its own, and the next iteration's reasoning starts afresh"
      ((:on-event r) :agent.think/reasoning {:reasoning "child thinks" :subagent-id "sa-1"})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "child thinks")) "not in the root's view")
      ((:on-event r) :agent.iteration/start {:iteration 3})
      ((:on-event r) :agent.llm/chunk {:field :reasoning :text "fresh"})
      (is (= {:field :reasoning :text "fresh"} @(:live (:cells r)))))))

(deftest a-recovered-call-replaces-the-thought-written-under-the-refusal
  ;; byj-gly.339: inside claude -p the model called bash natively, was refused,
  ;; and wrote "the bash tool is not bound"; the call was recovered and run,
  ;; and that thought sat above it
  (let [r (mounted {:cols 90 :rows 16})]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.think/reasoning {:reasoning "The bash tool is not bound in this session."})
    ((:on-event r) :agent.tools/recovered {:tool-calls [{:tool-name "bash" :tool-args {:command "ls"}}]
                                          :thought "Called tools natively; the CLI refused them."})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "ls"}}]})
    (g/frame!)
    (is (not (str/includes? (screen-text r) "not bound")))
    (is (str/includes? (screen-text r) "Called tools natively; the CLI refused them."))))

(deftest a-long-result-is-counted-and-the-answer-lands-below-it
  (let [r (mounted {:cols 50 :rows 16})]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "ls"}}]})
    ((:on-event r) :agent.tools/results {:results [{:tool-result "a\nb\nc\nd\ne"}]})
    ;; the answer arrives while that block is still the live one
    ((:emit r) :answer "done")
    (g/frame!)
    (let [lines (vec (drop-while #(not (str/includes? % "bash(command=ls)")) (scr/lines (:screen r))))]
      (testing "the first lines of a result, and a count of the rest"
        (is (= ["⏺ bash(command=ls) · 9 chars" "  ⎿  a" "     b" "     c" "     … +2 lines · click to show them"] (subvec lines 0 5))))
      (testing "a long one-line result is one row, cut at the margin, not a dozen wrapped ones"
        (let [r2 (mounted {:cols 40 :rows 16})
              ;; cut short by its cap, so it does not read back as data and is
              ;; shown as it came — one line, clipped
              long-line (str (pr-str {:answer (apply str (repeat 20 "word ")) :subagent-id "probe/amber-1"})
                             "…[truncated 900 chars]")]
          ((:on-event r2) :agent.iteration/start {:iteration 1})
          ((:on-event r2) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "x"}}]})
          ((:on-event r2) :agent.tools/results {:results [{:tool-result long-line}]})
          (g/frame!)
          (let [ls (vec (drop-while #(not (str/includes? % "bash(command=x)")) (scr/lines (:screen r2))))]
            (is (str/starts-with? (ls 1) "  ⎿  {:answer"))
            (is (str/ends-with? (str/trimr (ls 1)) "…"))
            (is (<= (count (str/trimr (ls 1))) 40))
            (is (not (str/includes? (ls 2) "word")) "and nothing of it spills onto the next row"))))
      (testing "what the turn did is above what it said"
        (is (= "⏺ done" (last (remove #(or (str/blank? %) (str/includes? % "tokens "))
                                       (take-while #(not (str/starts-with? % "───")) lines)))))))))

(deftest token-counts-carry-their-unit
  (is (= ["0" "999" "1.00K" "3.14K" "999.49K" "1.00M" "1.12M" "12.35M"]
         (mapv tui/token-count [nil 999 1000 3141 999490 999500 1123456 12345678])))
  (testing "the session's tokens, then the last call's"
    (is (= "turn 3 · 4 calls · tokens 1.12M/3.14K (last call: 250.00K/512)"
           (tui/usage-line {:turn 3 :calls 4 :input-tokens 1123456 :output-tokens 3141 :last-call [250000 512]})))
    (is (= "turn 0 · 0 calls · tokens 0/0" (tui/usage-line {:turn 0 :calls 0}))
        "no call yet, no last call")))

(deftest the-input-sits-between-two-rules-under-what-is-running
  (let [r (mounted {:cols 70 :rows 12})]
    ;; a full transcript, so a blank row above the spinner is padding and not
    ;; merely the transcript running out
    ((:emit r) :answer (str/join "\n" (map #(str "line " %) (range 20))))
    ((:on-event r) :session.turn/start {:turn 2})
    ((:on-event r) :predictor/call {:usage {:input-tokens 10621 :output-tokens 1367}})
    (g/frame!)
    (let [lines (vec (scr/lines (:screen r)))
          n (count lines)
          rule? #(str/starts-with? % "────")]
      (testing "from the bottom: the footer, a rule, the input, a rule, the usage"
        (is (str/includes? (lines (- n 1)) "ctrl-c cancel"))
        (is (rule? (lines (- n 2))))
        (is (str/starts-with? (lines (- n 3)) "❯ "))
        (is (rule? (lines (- n 4))))
        (is (str/ends-with? (str/trimr (lines (- n 5))) "turn 2 · 1 call · tokens 10.62K/1.37K (last call: 10.62K/1.37K)")
            "the turn, the calls and the usage, right-aligned above the input")
        (is (str/blank? (lines (- n 6))) "with a blank row over it, apart from what is above (byj-gly.284)"))
      (testing "and a running turn is a spinner line: how long, and what it has written"
        (is (re-find #"working… \(\d+\.\ds · ↓ 1\.4k tokens\)" (lines (- n 7))))
        (is (str/blank? (lines (- n 8))) "with a blank row between it and the transcript")
        (is (= "  line 19" (lines (- n 9))) "and the transcript's last line right above that")
        (is (= :yellow (:fg (style-of r "working…"))))))))

(deftest a-finished-turn-says-how-long-and-a-long-one-recaps
  (testing "a duration and a clock, as a person says them"
    (is (= "<1s" (tui/duration-str 400)))
    (is (= "12s" (tui/duration-str 12400)))
    (is (= "7m 13s" (tui/duration-str 433000)))
    (is (= "1h 4m" (tui/duration-str 3840000)))
    (is (= "6:23 PM" (tui/clock-str "2026-09-24T18:23:05+0900")))
    (is (= "12:05 AM" (tui/clock-str "2026-09-24T00:05:00+0900")))
    (is (nil? (tui/clock-str nil))))
  (let [r (mounted {:cols 60 :rows 16})]
    ((:on-event r) :session.turn/start {:turn 2})
    ;; the session says the turn ended BEFORE it emits the answer
    ((:on-event r) :session.turn/end {:elapsed-ms 433000 :ended-at "2026-09-24T18:23:05+0900"})
    ((:emit r) :answer "committed")
    ((:on-event r) :session.turn/recap {:turn 2 :recap "We made the TUI look right."
                                        :next-step "Commit it when you say so."})
    (g/frame!)
    (let [lines (vec (remove str/blank? (take-while #(not (str/includes? % "tokens ")) (scr/lines (:screen r)))))]
      (testing "under the answer: how long it took, and when it ended"
        (is (= "⏺ committed" (lines 0)))
        (is (= "✻ Brewed for 7m 13s · done 6:23 PM" (lines 1)))
        (is (:dim (style-of r "✻ Brewed"))))
      (testing "then the recap, wrapped under its own mark"
        (is (= "※ recap: We made the TUI look right. Commit it when you say" (lines 2)))
        (is (= "  so." (lines 3)))))))

(deftest a-resumed-session-shows-its-thoughts-and-its-suggestion
  ;; byj-gly.280.4: the thought above a call, and the model's next-user-prompt
  ;; in the empty input line, came back with neither
  (let [r (mounted {:cols 70 :rows 16 :suggestions? true})]
    ((:on-event r) :session/resumed {:id "s" :next-user-prompt "what is in notes.txt?"
                                     :transcript [[:you "what is here?"]
                                                  [:thought "Listing it first, to see what is here."]
                                                  [:tool {:tool-name "list-dir" :tool-args {:path "."} :tool-result "notes.txt"}]
                                                  [:answer "first answer"]]})
    (g/frame!)
    (let [lines (scr/lines (:screen r))
          at #(first (keep-indexed (fn [i l] (when (str/includes? l %) i)) lines))]
      (is (some #(str/starts-with? % "⏺ Listing it first") lines) "the thought, behind its bullet")
      (is (< (at "Listing it first") (at "list-dir(path=.)")) "above its call")
      (is (:dim (style-of r "Listing it first"))))
    (is (= "what is in notes.txt?" @(:suggestion (:cells r))) "offered again")))

(deftest a-long-resumed-session-costs-the-screen-not-the-history
  (let [transcript (vec (mapcat (fn [i] [[:you (str "question " i)]
                                         [:answer (str "## Answer " i "\n- one\n- two\n\nA paragraph long enough to wrap "
                                                       "once or twice at this width, " i ".")]])
                                (range 300)))
        whole (tui/resumed-entries transcript 1000000)]
    (testing "only the window is built, and it is the tail of what a whole replay would show"
      (is (= 200 (count (tui/resumed-entries transcript 200))))
      (is (= (take-last 200 whole) (tui/resumed-entries transcript 200)))
      (is (= {:kind :answer :text ""} (peek (tui/resumed-entries transcript 200))) "the air under it, last"))
    (testing "a short transcript is all there, in order"
      (is (= whole (tui/resumed-entries transcript (count whole))))
      (is (nil? (tui/resumed-entries [] 200))))
    (let [laid (atom 0)
          entry-rows @#'tui/entry-rows]
      (with-redefs [tui/entry-rows (fn [e w] (swap! laid inc) (entry-rows e w))]
        (let [r (mounted {:cols 60 :rows 20 :max-lines 2500})
              c (:cells r)]
          ((:on-event r) :session/resumed {:id "s" :transcript transcript})
          (g/frame!)
          (g/frame!)
          (testing "the whole window is kept for scrolling back, in one change"
            (is (= (min 2500 (count whole)) (count @(:lines c))))
            (is (str/includes? (screen-text r) "Answer 299")))
          (testing "but a frame lays out only what reaches the screen (byj-gly.250)"
            (is (< @laid 40) (str @laid " entries laid out for a 20-row screen")))
          (testing "paging back lays out a page more, not the history"
            (let [before @laid]
              (g/press! {:type :page-up})
              (g/frame!)
              (is (pos? @(:offset c)))
              (is (< (- @laid before) 40))))
          (testing "and the top is still the top"
            (reset! (:offset c) 0)
            (is (= (tui/max-offset c) (tui/scroll-offset c 1000000)))
            (is (= 7 (tui/scroll-offset c 7)))
            (is (= 0 (tui/scroll-offset c -5)))))))))

(deftest a-resumed-session-is-on-the-screen-again
  (let [r (mounted {:cols 60 :rows 18})]
    ((:on-event r) :session/resumed
     {:id "s1" :turn-count 6 :usage {:llm-calls 11 :input-tokens 900 :output-tokens 80}
      :transcript [[:you "list agents"]
                   [:tool {:tool-name "bash" :tool-args {:command "bd list"} :tool-result "16 open"}]
                            [:answer "## Agents\n- **none** running"]
                            [:recap "recap: We listed the agents."]
                            [:info "history cleared"]]})
    ((:emit r) :info "↻ resumed s1 · 1 turn(s)")
    (g/frame!)
    (let [lines (vec (remove str/blank? (take-while #(not (str/includes? % "tokens ")) (scr/lines (:screen r)))))]
      (is (= ["❯ list agents" "⏺ bash(command=bd list)" "  ⎿  16 open" "⏺ Agents" "  • none running" "※ recap: We listed the agents."
              "history cleared" "↻ resumed s1 · 1 turn(s)"]
             lines)
          "each line as it was first shown, and the resume line under them")
      (let [all (vec (scr/lines (:screen r)))
            at (first (keep-indexed #(when (str/includes? %2 "↻ resumed") %1) all))]
        (is (str/blank? (all (dec at))) "with an empty line above it"))
      (is (:bold (style-of r "none")) "an answer is read as markdown again")
      (is (:dim (style-of r "※ recap")))
      (testing "Up walks back through what was asked before the resume"
        (is (= ["list agents"] @(:history (:cells r))))
        (g/press! {:type :up})
        (is (= "list agents" @(:draft (:cells r)))))
      (testing "and the counts carry on from where the session was"
        (is (str/includes? (screen-text r) "turn 6 · 11 calls"))
        (is (str/includes? (screen-text r) "tokens 900/80"))))))

(defn- row-of
  "The screen row `needle` is on, or nil."
  [r needle]
  (first (keep-indexed #(when (str/includes? %2 needle) %1) (scr/lines (:screen r)))))

(deftest a-path-or-a-url-under-a-click-opens
  ;; byj-gly.270: brainyard's clickable links — detected at click time on the
  ;; row, a wrapped one recovered from its entry, opened by the :open-link seam
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-tui-links-" (System/nanoTime)))
        _ (do (.mkdirs (io/file d "src")) (spit (io/file d "src" "a.clj") "x"))
        opened (atom [])
        r (mounted {:cols 60 :rows 12 :working-dir (.getPath d)
                    :open-link (fn [t base] (swap! opened conj [t base]) (str "opened " (:text t)))})
        click-on! (fn [needle k]
                    (let [y (row-of r needle)
                          x (+ k (str/index-of (nth (scr/lines (:screen r)) y) needle))]
                      (g/click! x y)
                      (g/frame!)))]
    ((:say! r) :info "failed at src/a.clj:12:3, see https://x.dev/y.")
    (g/frame!)
    (testing "a path at its line and column"
      (click-on! "src/a.clj" 2)
      (is (= [{:kind :file :path "src/a.clj" :line 12 :col 3} (.getPath d)]
             (update (first @opened) 0 select-keys [:kind :path :line :col])))
      (is (= "opened src/a.clj:12:3" @(:flash (:cells r))) "and the footer says what opened"))
    (testing "a URL, without the sentence's full stop"
      (click-on! "https://x.dev" 3)
      (is (= "https://x.dev/y" (:text (first (second @opened))))))
    (testing "what a click would open is underlined, and nothing else is"
      (is (:underline (style-of r "src/a.clj:12:3")))
      (is (:underline (style-of r "https://x.dev/y")))
      (is (not (:underline (style-of r "failed")))))
    (testing "a URL the wrap split opens whole, from either row"
      (reset! opened [])
      ((:say! r) :info (str "docs: https://example.dev/" (apply str (repeat 60 "a")) "/end"))
      (g/frame!)
      (click-on! "/end" 1)
      (is (= (str "https://example.dev/" (apply str (repeat 60 "a")) "/end") (:text (ffirst @opened)))))
    (testing "a click on no link opens nothing"
      (reset! opened [])
      (click-on! "failed" 1)
      (is (empty? @opened)))))

(deftest an-open-sessions-children-are-listed-from-its-buffers
  ;; byj-gly.298.2: who dispatched each child, what it was asked, how it ended
  (let [r (mounted {:cols 70 :rows 20})
        entry {:renderer r}]
    ((:on-event r) :agent.subagent/dispatch {:id "plan/a-1" :agent "plan" :owner "root" :question "plan it"})
    ((:on-event r) :agent.subagent/dispatch {:id "explore/b-2" :agent "explore" :owner "plan/a-1" :question "look"})
    ((:on-event r) :agent.subagent/result {:id "explore/b-2" :agent "explore" :answer "found" :terminated-by :answer-channel})
    (let [[plan explore] (sort-by :id #(compare %2 %1) (tui/session-children entry))]
      (is (= ["plan/a-1" "root" "plan it" 1] ((juxt :id :owner :question :turns) plan)))
      (is (= ["explore/b-2" "plan/a-1" "found" true false] ((juxt :id :owner :answer :success? :live?) explore))))
    ((:on-event r) :agent.subagent/ask {:id "explore/b-2" :agent "explore" :question "and the tests?"})
    (is (= ["look" 2] ((juxt :question :turns) (first (filter #(= "explore/b-2" (:id %)) (tui/session-children entry)))))
        "a follow-up is another turn; the first question stays")))

(deftest a-workflow-step-is-drawn-as-a-call-with-its-progress
  ;; byj-gly.299: a workflow tree's steps were invisible, and agent$claude's
  ;; minutes of waiting with them
  (let [r (mounted {:cols 90 :rows 20})
        child #(assoc % :subagent-id "claude/navy-1" :subagent "claude")]
    ((:on-event r) :session.turn/start {:turn 1})
    ((:on-event r) :agent.tree/step-start {:node :impl/plan :kind :predictor :name "todo/from-plan" :args {:plan "Saved plan: p.md"}})
    (g/frame!)
    (is (str/includes? (screen-text r) "todo/from-plan(plan=Saved plan: p.md)") "a step in the turn: a call")
    ((:on-event r) :agent.tree/step-end {:node :impl/plan :kind :predictor :name "todo/from-plan" :result "todo-slug retry · items 2"})
    (g/frame!)
    (is (str/includes? (screen-text r) "todo-slug retry · items 2") "and what it kept")
    (testing "in a subagent's view: running, its progress under it, then its result in place of it"
      ((:on-event r) :agent.subagent/dispatch {:id "claude/navy-1" :agent "claude" :question "fix it"})
      ((:on-event r) :agent.tree/step-start (child {:node :cc/await :kind :tool :name "claude$await" :args {:session-id "cc-1"}}))
      (doseq [l ["Bash(python3 -m unittest)" "Edit(mathx.py)"]]
        ((:on-event r) :agent.tree/step-progress (child {:line l})))
      (tui/show-child! r "claude/navy-1" "claude" nil)
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "claude$await(session-id=cc-1)"))
        (is (str/includes? text "Bash(python3 -m unittest)"))
        (is (str/includes? text "Edit(mathx.py)")))
      ((:on-event r) :agent.tree/step-end (child {:node :cc/await :kind :tool :name "claude$await" :result "settled done · round 1"}))
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "settled done · round 1"))
        (is (not (str/includes? text "Edit(mathx.py)")) "the progress gives way to the result")))))

(deftest a-running-tool-shows-what-it-prints
  ;; byj-gly.364: a bash call printed a login's code at once and the screen
  ;; showed nothing until it returned; its newest lines go under the call
  ;; that printed them, not under the batch's other calls still to finish
  (let [r (mounted {:cols 90 :rows 20})]
    ((:on-event r) :session.turn/start {:turn 1})
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "byj-share login"}}
                                                    {:tool-name "bash" :tool-args {:command "echo later"}}]})
    (doseq [l ["Open https://relay.example/device?code=ABCD-1234" "and enter the code ABCD-1234"]]
      ((:on-event r) :agent.tool/output {:tool-name "bash" :line l :call 0}))
    (g/frame!)
    (let [text (screen-text r)]
      (is (str/includes? text "Open https://relay.example/device?code=ABCD-1234") "while it runs")
      (is (= 1 (count (re-seq #"and enter the code ABCD-1234" text))) "under its own call only"))
    ((:on-event r) :agent.tool/output {:tool-name "bash" :line "later" :call 1})
    (g/frame!)
    (is (not (str/includes? (screen-text r) "and enter the code")) "the next call's lines take over")
    ((:on-event r) :agent.tools/results {:results [{:tool-name "bash" :tool-result "{:status \"completed\"}"}
                                                   {:tool-name "bash" :tool-result "{:status \"completed\" :output \"later\"}"}]})
    (g/frame!)
    (is (not (str/includes? (screen-text r) "│ later")) "and give way to the results"))
  (testing "the lines are kept per call, the newest few"
    (let [it (reduce tui/with-output nil (map (fn [i] {:line (str "l" i) :call 0}) (range 10)))]
      (is (= ["l6" "l7" "l8" "l9"] (:progress it)))
      (is (= {:progress ["x"] :progress-call 1} (select-keys (tui/with-output it {:line "x" :call 1}) [:progress :progress-call]))))))

(deftest a-subagent-has-a-transcript-of-its-own
  ;; byj-gly.138: a child's tool calls and reply were drawn in the root's
  ;; transcript as though the caller had made them
  (let [r (mounted {:cols 70 :rows 20})
        child #(assoc % :subagent-id "probe/amber-1" :subagent "probe")]
    ((:on-event r) :session.turn/start {:turn 1})
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "agent$probe" :tool-args {:question "look in src"}}]})
    ((:on-event r) :agent.subagent/dispatch {:id "probe/amber-1" :agent "probe" :question "look in src"})
    ((:on-event r) :agent.iteration/start (child {:iteration 1}))
    ((:on-event r) :agent.tools/calls (child {:tool-calls [{:tool-name "read-file" :tool-args {:path "src/x.clj"}}]}))
    ((:on-event r) :agent.tools/results (child {:results [{:tool-result "(ns x)"}]}))
    ((:on-event r) :agent.llm/chunk (child {:field :answer :text "it is x"}))
    (g/frame!)
    (testing "the child's work is not drawn in the root's transcript"
      (is (str/includes? (screen-text r) "⏺ agent$probe(question=look in src)"))
      (is (not (str/includes? (screen-text r) "read-file")))
      (is (not (str/includes? (screen-text r) "it is x"))))
    (testing "a click on the child's running row puts its transcript on the screen"
      (g/click! 4 (row-of r "▸ probe"))
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "❯ look in src"))
        (is (str/includes? text "⏺ read-file(path=src/x.clj)"))
        (is (str/includes? text "⏺ it is x") "its reply as it streams")
        (is (not (str/includes? text "agent$probe")) "and the root's own calls are not")
        (is (str/includes? text "▸ probe/amber-1 · what you type asks it · esc to return"))))
    (testing "and the focus is back on the input line, so typing still types"
      (type! "hi")
      (is (= "hi" @(:draft (:cells r)))))
    (testing "Esc puts the root's transcript back"
      (g/press! {:type :escape})
      (g/frame!)
      (is (str/includes? (screen-text r) "⏺ agent$probe(question=look in src)"))
      (is (not (str/includes? (screen-text r) "esc to return"))))
    (testing "once the child has answered, the call that started it opens it"
      ((:on-event r) :agent.subagent/result {:id "probe/amber-1" :agent "probe" :answer "**x** is the one"})
      ((:on-event r) :agent.tools/results {:results [{:tool-result (pr-str {:answer "x" :subagent-id "probe/amber-1"})}]})
      ((:on-event r) :session.turn/end {})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "▸ probe ·")) "its running row is gone")
      (g/click! 4 (row-of r "agent$probe"))
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "⏺ read-file(path=src/x.clj)") "its tool block was frozen in its own transcript")
        (is (str/includes? text "⏺ x is the one"))
        (is (:bold (style-of r "x is")))))
    (testing "a click on a line that opens nothing leaves the view alone"
      (g/press! {:type :escape})
      (g/frame!)
      (g/click! 2 0)
      (g/frame!)
      (is (nil? @(:viewing (:cells r)))))))

(deftest a-line-typed-in-a-childs-view-asks-that-child
  ;; byj-gly.344: a line typed while a child's transcript was on screen went
  ;; to the root's next turn, out of sight of the person watching the child
  (testing "the line, as the call it is sent as"
    (is (= ":agent-registry$ask {:id \"probe/amber-1\", :question \"and in \\\"test\\\"?\"}"
           (tui/ask-line "probe/amber-1" "and in \"test\"?")))
    (is (nil? (tui/ask-line nil "hi")) "the root's transcript: the root's line")
    (is (nil? (tui/ask-line "probe/amber-1" "/help")) "a command stays the root's")
    (is (nil? (tui/ask-line "probe/amber-1" ":bash :command \"ls\"")) "so does a direct call"))
  (let [r (mounted {:cols 90 :rows 20})
        c (:cells r)
        child #(assoc % :subagent-id "probe/amber-1" :subagent "probe")
        send! (fn [line] (reset! (:draft c) line) ((:submit! r)))]
    ((:on-event r) :session.turn/start {:turn 1})
    ((:on-event r) :agent.subagent/dispatch {:id "probe/amber-1" :agent "probe" :question "look in src"})
    ((:on-event r) :agent.iteration/start (child {:iteration 1}))
    (tui/show-child! r "probe/amber-1" "probe" nil)
    (g/frame!)
    (testing "the input and the footer say where a line goes"
      (is (str/includes? (screen-text r) "ask probe/amber-1 · esc returns to the root"))
      (is (str/includes? (screen-text r) "what you type asks it")))
    (testing "while the child works, the ask waits, and its transcript says so"
      (send! "and in test?")
      (g/frame!)
      (is (= [(tui/ask-line "probe/amber-1" "and in test?")] @(:queue c)))
      (is (str/includes? (screen-text r) "queued — asks probe/amber-1 when it is idle")))
    (testing "a command typed there is still the root's"
      (send! "/status")
      (is (= "/status" (peek @(:queue c)))))
    (testing "an ask that cannot reach the child says why in the child's transcript"
      ((:present r) :tool-call {:tool-name "agent-registry$ask" :tool-args {:id "probe/amber-1" :question "and in test?"}
                                :tool-result (pr-str {:error-message "Instance not found: probe/amber-1"}) :tool-error? true})
      (g/frame!)
      (is (str/includes? (screen-text r) "✗ not asked: Instance not found: probe/amber-1")))
    (testing "Esc: the root's transcript, and lines are the root's again"
      (g/press! {:type :escape})
      (g/frame!)
      (is (str/includes? (screen-text r) "ask something, or /help"))
      (send! "hi")
      (is (= "hi" (peek @(:queue c)))))))

(deftest a-long-result-folds-and-opens-in-an-editor
  ;; byj-gly.109: a long result was three lines and a count, and the rest was
  ;; nowhere to be seen
  (let [edited (atom [])
        r (mounted {:cols 60 :rows 22 :editor #(swap! edited conj %)})
        result (str/join "\n" (map #(str "line " %) (range 1 9)))]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "seq 8"}}]})
    ((:on-event r) :agent.tools/results {:results [{:tool-result result}]})
    ;; frozen into the transcript, where a fold can be opened
    ((:on-event r) :agent.iteration/start {:iteration 2})
    (g/frame!)
    (testing "folded, it says how much is hidden"
      (is (str/includes? (screen-text r) "… +5 lines · click to show them"))
      (is (not (str/includes? (screen-text r) "line 8"))))
    (testing "a click opens it in place"
      (g/click! 6 (row-of r "click to show them"))
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "line 4"))
        (is (str/includes? text "line 8"))
        (is (str/includes? text "▴ click to fold · ctrl-o opens it in $EDITOR"))
        ;; byj-gly.276: the line that folds it is the button; what it showed is text
        (is (:underline (style-of r "▴ click to fold")) "the click line is a target")
        (is (not (:underline (style-of r "line 8"))) "the rows it opened are not")
        ;; byj-gly.277: its text, not the indent before it
        (let [y (row-of r "click to fold")
              x (str/index-of (nth (scr/lines (:screen r)) y) "▴")]
          (is (not (:underline (scr/style-at (:screen r) (dec x) y))) "the indent is not underlined")
          (is (not (:underline (scr/style-at (:screen r) 0 y))) "nor the margin"))))
    (testing "and the focus is back on the input line"
      (type! "x")
      (is (= "x" @(:draft (:cells r)))))
    (testing "ctrl-o hands the whole result to the editor"
      (g/press! 15)                                   ; ctrl-o
      (is (= [result] @edited)))
    (testing "a second click folds it again"
      (g/click! 6 (row-of r "click to fold"))
      (g/frame!)
      (is (str/includes? (screen-text r) "… +5 lines"))
      (is (not (str/includes? (screen-text r) "line 8"))))))

(deftest ctrl-f-finds-a-line-in-the-transcript
  ;; byj-gly.110: the scrollback could be paged but not searched
  (let [r (mounted {:cols 60 :rows 14})
        c (:cells r)]
    (doseq [i (range 40)]
      ((:say! r) :info (cond (= i 5) "an old NEEDLE" (= i 30) "a newer needle" :else (str "line " i))))
    (type! "half a question")
    (g/frame!)
    (testing "ctrl-f turns the input line into a query, keeping the draft"
      (g/press! 6)                                    ; ctrl-f
      (g/frame!)
      (is (str/includes? (screen-text r) "find:"))
      (is (= "half a question" @(:draft c))))
    (testing "typing finds, ignoring case, and shows the newest match marked"
      (type! "needle")
      (g/frame!)
      (is (str/includes? (screen-text r) "1/2"))
      (is (str/includes? (screen-text r) "a newer needle"))
      (is (:reverse (style-of r "a newer needle"))))
    (testing "Enter goes to the older one, and down comes back"
      (enter!)
      (g/frame!)
      (is (str/includes? (screen-text r) "2/2"))
      (is (str/includes? (screen-text r) "an old NEEDLE"))
      (is (:reverse (style-of r "an old NEEDLE")))
      (g/press! {:type :down})
      (g/frame!)
      (is (str/includes? (screen-text r) "1/2")))
    (testing "a query nothing holds says so"
      (type! "zzz")
      (g/frame!)
      (is (str/includes? (screen-text r) "no match for \"needlezzz\"")))
    (testing "esc ends it: the draft is back and the view stays where it was"
      (let [offset @(:offset c)]
        (g/press! {:type :escape})
        (g/frame!)
        (is (nil? @(:search c)))
        (is (= "half a question" @(:draft c)))
        (is (= offset @(:offset c)))
        (is (not (str/includes? (screen-text r) "find:")))))))

(deftest the-glyph-widths-the-renderer-assumes
  ;; byj-gly.111: brainyard negotiates grapheme width with the terminal; the port
  ;; assumes glimmer-tui's. These are what tmux 3.6 measured for each glyph with
  ;; dev/glyph-probe.sh (2026-09-24), ZWJ sequences included. If a glimmer-tui
  ;; upgrade moves one, a row that was aligned is not — run the probe again.
  (is (= {"a" 1 "é" 1 "日本" 4 "✔" 1 "❤️" 2 "🇰🇷" 2 "👍🏽" 2 "👨‍👩‍👧" 2 "🧑‍💻" 2
          "⏺" 1 "⎿" 1 "❯" 1 "•" 1}
         (into {} (for [g ["a" "é" "日本" "✔" "❤️" "🇰🇷" "👍🏽" "👨‍👩‍👧" "🧑‍💻" "⏺" "⎿" "❯" "•"]]
                    [g (text/width g)])))))

(deftest the-display-level-is-read-as-it-changes
  (let [box (atom :normal)
        r (mounted {:cols 60 :rows 16 :display-box box})]
    (testing "normal: the turn's trace events are not shown"
      ((:on-event r) :agent.answer/evaluated {:verdict :ok})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "agent.answer/evaluated"))))
    (testing "verbose, set while running, shows the next one"
      (reset! box :verbose)
      ((:on-event r) :agent.answer/evaluated {:verdict :ok})
      (g/frame!)
      (is (str/includes? (screen-text r) "agent.answer/evaluated"))
      (is (not (str/includes? (screen-text r) ":verdict"))))
    (testing "debug says what the event carried"
      (reset! box :debug)
      ((:on-event r) :agent.answer/evaluated {:verdict :refused})
      (g/frame!)
      (is (str/includes? (screen-text r) ":verdict :refused")))
    (testing "quiet leaves out the block of tool calls, live and frozen"
      (reset! box :quiet)
      ((:on-event r) :agent.iteration/start {:iteration 1})
      ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "ls"}}]})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "bash(command=ls)")))
      ((:on-event r) :agent.iteration/start {:iteration 2})
      (reset! box :normal)
      (g/frame!)
      (is (not (str/includes? (screen-text r) "bash(command=ls)")) "nor was it written into the transcript"))))

(deftest the-editing-keys-stay-the-input-lines
  ;; byj-gly.140: ctrl-b, ctrl-u and ctrl-d were bound to scrolling on the
  ;; container, but the entry claims them first and a claimed key never
  ;; bubbles. They are the entry's: ctrl-u still clears to the start, and it
  ;; does not scroll
  (let [r (mounted {:cols 40 :rows 10})
        c (:cells r)]
    (doseq [i (range 30)] ((:say! r) :info (str "line " i)))
    (g/frame!)
    (type! "a draft")
    (g/press! 21)                                     ; ctrl-u
    (g/frame!)
    (is (= "" @(:draft c)) "ctrl-u deletes to the start of the line")
    (is (zero? @(:offset c)) "and does not scroll")
    (g/press! {:type :page-up})
    (is (pos? @(:offset c)) "pgup is what scrolls")))

(deftest queue-is-the-renderers-own-command
  ;; byj-gly.143: the queue lives in the renderer, so the renderer answers
  ;; /queue — before the line could itself be queued behind a running turn
  (testing "listing, and cancelling one or all"
    (is (= ["nothing is queued" []] (tui/queue-command [] "")))
    (is (= "2 queued, read in this order:\n  1. first\n  2. second"
           (first (tui/queue-command ["first" "second"] nil))))
    (is (= ["cancelled 1. first" ["second"]] (tui/queue-command ["first" "second"] "cancel 1")))
    (is (= ["cancelled 2 queued lines" []] (tui/queue-command ["a" "b"] "cancel all")))
    (is (= "no queued line 3 — /queue lists them" (first (tui/queue-command ["a"] "cancel 3"))))
    (is (str/starts-with? (first (tui/queue-command ["a"] "flush")) "usage:")))
  (let [r (mounted {:cols 60 :rows 16})
        c (:cells r)]
    ;; nobody is reading — a turn is running — so what is typed is queued
    (type! "look at src") (enter!)
    (type! "then run the tests") (enter!)
    (is (= ["look at src" "then run the tests"] @(:queue c)))
    (testing "/queue is answered at once, and is not queued itself"
      (type! "/queue") (enter!)
      (g/frame!)
      (is (str/includes? (screen-text r) "2 queued, read in this order:"))
      (is (= 2 (count @(:queue c)))))
    (testing "and cancelling takes the line out of the queue"
      (type! "/queue cancel 1") (enter!)
      (g/frame!)
      (is (= ["then run the tests"] @(:queue c)))
      (is (str/includes? (screen-text r) "cancelled 1. look at src")))
    (testing "it completes like any command"
      (is (some #(= "/queue" (first %)) (:commands c))))))

(deftest a-typed-line-stands-on-its-own-band
  (let [r (mounted {:cols 40 :rows 12})]
    ((:emit r) :answer "an earlier answer")
    ((:say! r) :you "a question long enough that it has to wrap onto a second row")
    (g/frame!)
    (let [lines (vec (scr/lines (:screen r)))
          y (row-of r "❯ a question")
          style-at #(scr/style-at (:screen r) %1 %2)]
      (testing "an empty row above it, for air"
        (is (str/blank? (lines (dec y)))))
      (testing "the band runs the whole row, not only under the text"
        (is (= 237 (:bg (style-at 5 y))))
        (is (= 237 (:bg (style-at 39 y))) "to the last column"))
      (testing "the mark is dim, and the text is not bold"
        (is (:dim (style-at 0 y)))
        (is (not (:bold (style-at 3 y)))))
      (testing "a wrapped line keeps its band and hangs under its text"
        (is (str/starts-with? (lines (inc y)) "  "))
        (is (= 237 (:bg (style-at 39 (inc y))))))
      (testing "and the row above the band has none"
        (is (nil? (:bg (style-at 5 (dec y)))))))))

(deftest a-message-of-many-lines-keeps-them-and-folds-a-long-one
  (let [r (mounted {:cols 50 :rows 24})]
    (testing "a few lines are all shown, indentation and all, each on the band"
      ((:say! r) :you "first line\n  indented second\nthird")
      (g/frame!)
      (let [y (row-of r "❯ first line")
            lines (vec (scr/lines (:screen r)))]
        (is (= "    indented second" (str/trimr (lines (inc y)))))
        (is (= "  third" (str/trimr (lines (+ 2 y)))))
        (is (= 237 (:bg (scr/style-at (:screen r) 49 (+ 2 y)))))))
    (testing "a long one shows what the field would, and folds the rest on the band"
      ((:say! r) :you (str/join "\n" (map #(str "frame " %) (range 12))))
      ((:emit r) :info "after it")                     ; freezes nothing, but ids the entries
      (g/frame!)
      (let [text (screen-text r)
            y (row-of r "… +7 lines")]
        (is (str/includes? text "frame 4"))
        (is (not (str/includes? text "frame 5")))
        (is (some? y))
        (is (= 237 (:bg (scr/style-at (:screen r) 49 y))) "the fold is the line's own, on its band")
        (g/click! 4 y)
        (g/frame!)
        (is (str/includes? (screen-text r) "frame 11") "and a click opens it")))))

(deftest the-wheel-goes-to-the-session-on-screen
  ;; with several sessions the wheel and the mouse went to whichever renderer
  ;; was BUILT last, not the one in front (byj-gly.207)
  (let [r1 (tui/make {}) r2 (tui/make {})
        root (w/node :window {})
        screen (scr/buffer-screen 40 10)
        show! (fn [r] (ui/mount root :window [(:view r)]) (g/frame!) (tui/measure! r) (g/frame!))]
    (g/attach! root screen)
    (doseq [r [r1 r2] i (range 30)] ((:say! r) :info (str "line " i)))
    (show! r1)
    (tui/wheel! 5 2 -3)
    (is (= 3 @(:offset (:cells r1))) "the one on screen scrolls")
    (is (zero? @(:offset (:cells r2))) "the other, built later, does not")
    (show! r2)
    (tui/wheel! 5 2 -3)
    (is (= 3 @(:offset (:cells r2))) "and when the other is shown, it is the one that scrolls")
    (is (= 3 @(:offset (:cells r1))))))

(deftest the-wheel-scrolls-the-transcript
  ;; glimmer-tui sends a notch to a :scroll container, and the transcript is not
  ;; one: wheel! falls through to it
  (let [r (mounted {:cols 40 :rows 10})
        c (:cells r)]
    (doseq [i (range 30)] ((:say! r) :info (str "line " i)))
    (g/frame!)
    (is (true? (tui/wheel! 5 2 -3)) "a notch up is taken")
    (is (= 3 @(:offset c)))
    (tui/wheel! 5 2 3)
    (is (zero? @(:offset c)) "and a notch down comes back")
    (tui/wheel! 5 2 3)
    (is (zero? @(:offset c)) "never below the bottom")))

(deftest scrolling-stops-at-the-top-and-a-spin-covers-ground
  (testing "a notch alone moves three rows, a burst moves further per notch"
    (is (= 3 (tui/wheel-step 1)))
    (is (= -6 (tui/wheel-step -2)))
    (is (= 18 (tui/wheel-step 3)) "three at once: twice as far each")
    (is (= -90 (tui/wheel-step -10)) "a fling: three times as far each"))
  (let [r (mounted {:cols 40 :rows 10})
        c (:cells r)]
    (doseq [i (range 20)] ((:say! r) :info (str "line " i)))
    (g/frame!)
    (let [top (tui/max-offset c)]
      (testing "the wheel stops at the first line, however far it is spun"
        (dotimes [_ 20] (tui/wheel! 5 2 -30))
        (is (= top @(:offset c)))
        (g/frame!)
        (is (str/includes? (screen-text r) "line 0") "and the first line is on the screen"))
      (testing "and so does page up"
        (dotimes [_ 5] (g/press! {:type :page-up}))
        (is (= top @(:offset c)))))))

(deftest what-a-click-would-do-is-underlined
  (let [r (mounted {:cols 60 :rows 14})
        result (str/join "\n" (map #(str "line " %) (range 8)))]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "seq"}}]})
    ((:on-event r) :agent.tools/results {:results [{:tool-result result}]})
    ((:on-event r) :agent.iteration/start {:iteration 2})
    (g/frame!)
    (is (:underline (style-of r "… +5 lines")) "a fold is a target")
    (is (not (:underline (style-of r "line 0"))) "the preview is not")))

(defn- byte-reader [s]
  (let [bytes (atom (vec (map #(bit-and % 0xff) (.getBytes (str s) "UTF-8"))))]
    (fn [] (let [[b] @bytes] (swap! bytes #(vec (rest %))) b))))

(defn- decoded
  "What glimmer-tui's reader makes of `s`, as the terminal would send it, with
  this renderer's paste timeout; the reads after `s` runs out come up dry."
  [s]
  (let [next-byte (byte-reader s)]
    (g/decode-input! (fn [_] (next-byte)) 0 tui/paste-byte-ms)))

(deftest sequences-the-terminal-sends-are-decoded-upstream
  ;; byj-gly.272: glimmer-tui's own reader since v0.2.11 (#15, #17); these are
  ;; the gaps this renderer used to fill itself, checked where they are now
  (testing "a bracketed paste, UTF-8 and all, up to its end marker"
    (is (= {:type :paste :text "héllo 안녕\nsecond line"}
           (select-keys (decoded "\u001b[200~héllo 안녕\rsecond line\u001b[201~") [:type :text :cut?]))))
  (testing "a paste that stalls is marked cut, so its rest is not sent (byj-gly.212)"
    (let [e (decoded "\u001b[200~cut\rshort")]
      (is (= "cut\nshort" (:text e)))
      (is (true? (:cut? e)))))
  (testing "a typed character outside ASCII is one key, not one per byte"
    (doseq [ch ["é" "한" "😀"]]
      (is (= ch (str (:ch (decoded ch)))) ch)))
  (testing "Tab and Shift+Tab are the keys this renderer's :on-key takes"
    (is (= (:type tui/tab-key) (:type (decoded "\t"))))
    (is (= (:type tui/shift-tab) (:type (glimmer-tui.keys/decode 353)))))
  (testing "an SGR report is the report this renderer acts on"
    (is (= {:button 65 :x 9 :y 4 :release? false} (tui/mouse-report (decoded "\u001b[<65;10;5M"))))
    (is (= {:button 32 :x 0 :y 0 :release? false} (tui/mouse-report (decoded "\u001b[<32;1;1M"))) "a drag")
    (is (true? (:release? (tui/mouse-report (decoded "\u001b[<0;1;1m")))))))

(deftest a-burst-of-mouse-reports-is-acted-on-once
  ;; byj-gly.272: glimmer-tui hands over one event and repaints before the next,
  ;; so a spin is held for coalesce-ms and acted on together
  (let [r (mounted {:cols 40 :rows 10})
        c (:cells r)]
    (doseq [i (range 30)] ((:say! r) :info (str "line " i)))
    (g/frame!)
    (dotimes [_ 3] (tui/on-mouse {:type :mouse :wheel :up :x 5 :y 2 :action :press}))
    (is (zero? @(:offset c)) "held, not yet acted on")
    (Thread/sleep (* 3 tui/coalesce-ms))
    (g/pump-timers!)
    (g/frame!)
    (is (= (tui/wheel-step -3) (- @(:offset c))) "three notches at once: a burst's step")))

(deftest a-bracketed-paste-goes-into-the-line-whole
  (let [r (mounted {:cols 60 :rows 12})
        c (:cells r)]
    (testing "a short one keeps its breaks as the field's marks"
      (g/press! {:type :paste :text "one\ntwo"})
      (is (= "one\ntwo" @(:draft c)) "a real line break, in a field of several lines"))
    (testing "a long one is a marker at once, and the model reads it all"
      (reset! (:draft c) "")
      (g/press! {:type :paste :text (str/join "\n" (map str (range 9)))})
      (is (= "[paste #1 +9 lines]" @(:draft c)))
      (is (= 9 (get-in @(:pastes c) [1 :lines]))))))

(deftest a-mouse-report-clicks-and-scrolls
  (let [r (mounted {:cols 60 :rows 14})
        c (:cells r)
        result (str/join "\n" (map #(str "line " %) (range 8)))]
    (doseq [i (range 30)] ((:say! r) :info (str "filler " i)))
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args {:command "seq"}}]})
    ((:on-event r) :agent.tools/results {:results [{:tool-result result}]})
    ((:on-event r) :agent.iteration/start {:iteration 2})
    (g/frame!)
    (testing "a left press is a click"
      (#'tui/act-on-mouse! {:button 0 :x 6 :y (row-of r "… +5 lines") :release? false})
      (#'tui/act-on-mouse! {:button 0 :x 6 :y (row-of r "… +5 lines") :release? true})
      (g/frame!)
      (is (str/includes? (screen-text r) "line 7") "the fold opened"))
    (testing "and a wheel notch scrolls the transcript, either way"
      (#'tui/act-on-mouse! {:button 64 :x 5 :y 2})
      (is (= 3 @(:offset c)))
      (#'tui/act-on-mouse! {:button 65 :x 5 :y 2})
      (is (zero? @(:offset c))))))

(deftest the-input-is-a-field-of-several-lines
  ;; byj-gly.154: a message of several lines is written and edited in place
  (testing "rows: each logical line, wrapped by cells, an empty one kept"
    (is (= [[0 5] [6 6] [7 10]] (tui/visual-rows "hello\n\nabc" 10)))
    (is (= [[0 4] [4 8] [8 9]] (tui/visual-rows "abcdefghi" 4)))
    (is (= [[0 2] [2 3]] (tui/visual-rows "한국어" 4)) "two wide glyphs fill four cells"))
  (let [r (mounted {:cols 40 :rows 20})
        c (:cells r)
        draft #(deref (:draft c))
        input-row #(row-of r "❯")
        alt-enter #(g/press! {:type :alt :base-type :enter})]
    (doseq [i (range 30)] ((:say! r) :info (str "line " i)))
    (type! "first") (alt-enter) (type! "second") (alt-enter) (type! "third")
    (g/frame!)
    (testing "each line is a row of the field, and the transcript gives up the rows"
      (is (= "first\nsecond\nthird" (draft)))
      (let [y (input-row) lines (vec (scr/lines (:screen r)))]
        (is (= "  second" (str/trimr (lines (inc y)))))
        (is (= "  third" (str/trimr (lines (+ 2 y)))))
        (is (str/starts-with? (lines (+ 3 y)) "────") "the rule under the input moved down with it")))
    (testing "up moves between rows, and edits land where the caret is"
      (g/press! {:type :up})
      (type! "X")
      (is (= "first\nseconXd\nthird" (draft)) "up keeps the column: the end of 'third' is column 5 of 'second'")
      (g/press! {:type :home})
      (type! ">")
      (is (= "first\n>seconXd\nthird" (draft)) "home is the start of THIS line")
      (g/press! 21)                                   ; ctrl-u
      (is (= "first\nseconXd\nthird" (draft)) "ctrl-u cuts to the start of the line"))
    (testing "up on the first row lets go, and the history has it"
      ((:submit! r))
      (type! "a\u0000")                               ; nothing: just a fresh line
      (reset! (:draft c) "")
      (g/frame!)
      (g/press! {:type :up})
      (g/frame!)
      (is (= "first\nseconXd\nthird" (draft)) "the whole message came back from the history"))
    (testing "an open menu gets the arrows, not the field"
      (reset! (:draft c) "")
      (g/frame!)
      (type! "/h")
      (reset! (:menu c) (tui/command-completions [["/help" "h"] ["/history" "h"]] "/h"))
      (g/frame!)
      (g/press! {:type :down})
      (is (= 1 (:at @(:menu c)))))))

(deftest a-long-message-scrolls-inside-the-field
  (let [r (mounted {:cols 30 :rows 24})
        c (:cells r)]
    (reset! (:draft c) (str/join "\n" (map #(str "row " %) (range 12))))
    (g/frame!)
    (let [text (screen-text r)]
      (is (= tui/input-max-rows (tui/input-rows @(:draft c) 28)))
      (is (str/includes? text "row 11") "the caret's row, at the end, is shown")
      (is (not (str/includes? text "row 3")) "and the field does not grow past its limit"))))

(deftest walking-the-history
  (let [r (mounted {:cols 50 :rows 14})
        c (:cells r)
        send! (fn [line] (reset! (:draft c) line) ((:submit! r)))]
    (testing "it keeps the newest 100"
      (doseq [i (range 110)] (send! (str "question " i)))
      (is (= 100 (count @(:history c))))
      (is (= "question 10" (first @(:history c)))))
    (testing "Up says where in the history you are, over the input"
      (g/frame!)
      (g/press! {:type :up})
      (g/press! {:type :up})
      (g/frame!)
      (is (= "question 108" @(:draft c)))
      (is (str/includes? (screen-text r) "── History 99/100"))
      (is (zero? (get-in @(first (filter #(= :text-area (:tag @%)) (tree-seq #(seq (:children @%)) #(:children @%) (:root @@#'g/app)))) [:state :cursor]))
          "the caret is at the start of the recalled line"))
    (testing "and back at the line being written, it is a plain rule again"
      (g/press! {:type :down})
      (g/press! {:type :down})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "History"))))))

(deftest a-recalled-paste-keeps-its-marker
  ;; the marker's number went up by one on every visit: the history held the
  ;; EXPANDED text, and the fold made a new marker of it each time
  (let [r (mounted {:cols 50 :rows 14})
        c (:cells r)]
    (g/press! {:type :paste :text (str/join "\n" (map #(str "frame " %) (range 10)))})
    (is (= "[paste #1 +10 lines]" @(:draft c)))
    ((:submit! r))
    (dotimes [_ 3]
      (g/frame!)
      (g/press! {:type :up})
      ((:settle-paste! r))
      (is (= "[paste #1 +10 lines]" @(:draft c)) "the same marker, every visit")
      (g/press! {:type :down}))
    (is (= 1 (count @(:pastes c))) "and no new paste was made of it")
    (testing "and a message typed over five lines is the person's, never folded"
      (reset! (:draft c) (str/join "\n" (map #(str "line " %) (range 7))))
      (Thread/sleep (inc tui/paste-gap-ms))
      ((:settle-paste! r))
      (is (str/starts-with? @(:draft c) "line 0\nline 1")))))

(deftest a-drag-selects-and-copies
  (testing "the text is what the rows show between the selection's ends"
    (let [rows [[[{:text "first line"}] :answer] [[{:text "⏺ "} {:text "second"}] :answer] [[{:text "third"}] :answer]]
          rect {:x 0 :y 0 :w 20 :h 3}]
      (is (= "line\n⏺ second\nthi" (tui/selection-text rows rect {:from [6 0] :to [2 2]})))
      (is (= "line\n⏺ second\nthi" (tui/selection-text rows rect {:from [2 2] :to [6 0]})) "dragged either way")
      (is (= "con" (tui/selection-text rows rect {:from [4 1] :to [6 1]})) "within one row, by cells: ⏺ and its space are 0 and 1")))
  (let [copied (atom nil)
        r (mounted {:cols 60 :rows 12 :copier (fn [t] (reset! copied t) {:ok? true :via :pbcopy :bytes (count t) :verified? true})})
        c (:cells r)
        mouse! (fn [b x y rel] (#'tui/act-on-mouse! {:button b :x x :y y :release? rel}))]
    ((:emit r) :answer "alpha beta gamma")
    ((:emit r) :answer "delta epsilon")
    (g/frame!)
    (let [y1 (row-of r "alpha") y2 (row-of r "delta")]
      (testing "a press and a drag select, and the selection is drawn reversed"
        (mouse! 0 2 y1 false)
        (mouse! 32 7 y2 false)      ; a motion report, the button still down
        (g/frame!)
        (is (= {:from [2 y1] :to [7 y2]} @(:selection c)))
        (is (:reverse (scr/style-at (:screen r) 4 y1))))
      (testing "the release copies it, and the footer says how"
        (mouse! 0 7 y2 true)
        (g/frame!)
        (is (= "alpha beta gamma\n\n⏺ delta" @copied))
        (is (str/includes? (screen-text r) (str "copied " (count @copied) " bytes · pbcopy"))))
      (testing "a press and a release where it was pressed is still a click"
        (reset! copied nil)
        (mouse! 0 2 y1 false)
        (mouse! 0 2 y1 true)
        (is (nil? @copied))
        (is (nil? @(:selection c)))))))

(deftest reports-that-arrive-together-are-acted-on-once
  (let [press {:button 0 :x 1 :y 1 :release? false}
        drag #(hash-map :button 32 :x % :y 1 :release? false)
        release {:button 0 :x 9 :y 1 :release? true}
        up {:button 64 :x 1 :y 1 :release? false}
        down {:button 65 :x 1 :y 1 :release? false}]
    (testing "a drag is its last position, between its press and its release"
      (is (= [press (drag 9) release] (tui/coalesce-reports [press (drag 2) (drag 5) (drag 9) release]))))
    (testing "a spin is one report of how many notches, opposite ones netted"
      (is (= [-3] (map :notches (tui/coalesce-reports [up up up]))))
      (is (= [1] (map :notches (tui/coalesce-reports [up down down])))))))

(deftest the-editing-keys-at-the-end-of-the-text-do-not-throw
  ;; ctrl-k at the end of a line joins the next one, by cutting the character
  ;; after the caret — and on an empty line there is none, which threw
  (let [r (mounted {:cols 40 :rows 10})
        c (:cells r)]
    (g/press! 11)                                     ; ctrl-k, on nothing
    (g/press! {:type :delete})
    (is (= "" @(:draft c)))
    (type! "abc")
    (g/press! 11)
    (g/press! {:type :delete})
    (is (= "abc" @(:draft c)) "at the end of a line with text, nothing to cut either")))

(deftest the-models-next-question-is-offered-in-the-input
  (let [r (mounted {:cols 60 :rows 12})
        c (:cells r)]
    ((:on-event r) :session.turn/start {:turn 1})
    ((:on-event r) :session.turn/end {:next-user-prompt "run the tests too"})
    (g/frame!)
    (testing "the empty input line shows it, dim, with how to take it beside it — brainyard's placeholder"
      (is (str/includes? (screen-text r) "❯ ↳ run the tests too  (→ to use)"))
      (is (:dim (style-of r "run the tests too"))))
    (testing "a mode entered while it waits says its own keys, not the suggestion's"
      (let [footer #(last (scr/lines (:screen r)))]
        (g/press! 6) (g/frame!)                       ; ctrl-f
        (is (str/includes? (footer) "type to find"))
        (g/press! {:type :escape}) (g/frame!)
        (reset! (:offset c) 3) (g/frame!)
        (is (str/includes? (footer) "pgdn to follow"))
        (reset! (:offset c) 0) (g/frame!)
        (is (str/includes? (footer) "ctrl-c cancels a turn") "and back to the tips after")))
    (testing "→ takes it into the line"
      (g/press! {:type :right})
      (is (= "run the tests too" @(:draft c))))
    (testing "a new turn clears it"
      (reset! (:draft c) "")
      ((:on-event r) :session.turn/start {:turn 2})
      (g/frame!)
      (is (str/includes? (screen-text r) "❯ ask something, or /help"))
      (g/press! {:type :right})
      (is (= "" @(:draft c)) "and → is only a cursor key again"))
    (testing "a call the person made between turns offers its own (/init, byj-gly.337.1)"
      ((:on-event r) :session/suggestion {:next-user-prompt "I want to build"})
      (g/frame!)
      (is (str/includes? (screen-text r) "❯ ↳ I want to build  (→ to use)"))))
  (testing "off, nothing is offered"
    (let [r (mounted {:cols 60 :rows 12 :suggestions? false})]
      ((:on-event r) :session.turn/end {:next-user-prompt "run the tests too"})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "run the tests too"))))))

(deftest the-footer-turns-through-the-help-tips
  (let [r (mounted {:cols 90 :rows 12})
        c (:cells r)
        footer #(last (scr/lines (:screen r)))]
    (is (str/ends-with? (str/trimr (footer)) (first tui/help-tips)) "a fresh session shows the first")
    (swap! (:tip-at c) inc) (g/frame!)
    (is (str/ends-with? (str/trimr (footer)) (second tui/help-tips)) "the loop's timer moves it on")
    (reset! (:tip-at c) (count tui/help-tips)) (g/frame!)
    (is (str/ends-with? (str/trimr (footer)) (first tui/help-tips)) "and round again"))
  (testing "every tip names a command this session has"
    (let [cmds (set (map first (concat session/commands tui/ui-commands)))]
      (doseq [tip tui/help-tips
              ;; a command starts a word: "pgup/pgdn" names two keys, not /pgdn
              [_ cmd] (re-seq #"(?:^|\s)(/[a-z-]+)" tip)]
        (is (contains? cmds cmd) (str cmd " in " (pr-str tip))))))
  (testing "a long suggestion is cut, and how to take it survives"
    (let [p (tui/suggestion-placeholder (apply str (repeat 40 "word ")) 40)]
      (is (str/starts-with? p "↳ word"))
      (is (str/ends-with? p "…  (→ to use)"))
      (is (<= (count p) 40)))))

(deftest the-conversation-is-named-on-the-inputs-rule
  (let [r (mounted {:cols 60 :rows 10})
        c (:cells r)
        rule #(let [lines (vec (scr/lines (:screen r)))] (lines (dec (row-of r "❯"))))]
    (g/frame!)
    (is (not (str/includes? (rule) "fix")) "no name before a recap has given one")
    ((:on-event r) :session.turn/recap {:recap "r" :next-step "n" :conversation-title "fix-drag-performance-and-crashes"})
    (g/frame!)
    (testing "a recap names it, right-aligned on the rule over the input"
      (is (str/ends-with? (str/trimr (rule)) "fix-drag-performance-and-crashes ──"))
      (is (str/starts-with? (rule) "────")))
    (testing "and the history's place stays on the left"
      (reset! (:history c) ["one" "two"])
      (g/frame!)
      (g/press! {:type :up})
      (g/frame!)
      (is (str/starts-with? (rule) "── History 2/2"))
      (is (str/includes? (rule) "fix-drag-performance-and-crashes")))
    (testing "a resumed session is named from its snapshot"
      ((:on-event r) :session/resumed {:id "s" :transcript [] :conversation-title "port-the-renderer"})
      (g/press! {:type :down})
      (g/frame!)
      (is (str/includes? (rule) "port-the-renderer")))))

(deftest what-is-in-flight-says-so-while-it-is
  (let [r (mounted {:cols 60 :rows 12})
        busy #(deref (:busy (:cells r)))]
    (testing "a model call in flight is a line with how long it has been going"
      ((:on-event r) :agent.llm/request {:predictor-id "react/think-act"})
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "react/think-act"))
        (is (re-find #"\d+\.\ds" text) "and an elapsed that ticks")))

    (testing "and it stops saying so when the reply lands"
      ((:on-event r) :agent.think/reasoning {:reasoning "r"})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "react/think-act"))))

    (testing "a background task and a subagent each get their own line"
      ((:on-event r) :task/created {:id "task-1" :name "bash: make build" :job-type "bash"})
      ((:on-event r) :agent.subagent/dispatch {:id "probe/amber-1" :agent "probe"})
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "bash: make build"))
        (is (str/includes? text "probe")))
      ((:on-event r) :task/completed {:id "task-1" :status :completed})
      ((:on-event r) :agent.subagent/result {:id "probe/amber-1"})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "bash: make build")))
      (is (empty? (:tasks (busy))))
      (is (empty? (:subagents (busy))) "the child that finished is the one taken off"))

    (testing "a follow-up to a live instance is a child turn, and has its line while it runs"
      ((:on-event r) :agent.subagent/ask {:id "probe/amber-1" :agent "probe"})
      (g/frame!)
      (is (str/includes? (screen-text r) "▸ probe"))
      ((:on-event r) :agent.subagent/result {:id "probe/amber-1" :agent "probe"})
      (g/frame!)
      (is (empty? (:subagents (busy)))))

    (testing "a turn that ends takes its thinking and its children with it"
      ((:on-event r) :agent.llm/request {:predictor-id "react/think-act"})
      ((:on-event r) :agent.subagent/dispatch {:id "probe/amber-2" :agent "probe"})
      ((:on-event r) :session.turn/end {:terminated-by :answer})
      (g/frame!)
      (is (nil? (:think (busy))))
      (is (empty? (:subagents (busy)))))

    (testing "a compaction is a line in the transcript, not a block: it is over"
      ((:on-event r) :agent.context/budgeted {:total-tokens 120000 :after-tokens 60000
                                              :compactions [:tier-1 :drop-oldest]})
      (g/frame!)
      (is (str/includes? (screen-text r) "120000 → 60000")))))

(deftest a-line-typed-while-a-turn-runs-is-queued-not-swallowed
  (let [r (mounted {:cols 60 :rows 10})]
    (type! "next question")
    (enter!)
    (testing "nothing was waiting for it, so it is held and said so"
      (is (= ["next question"] @(:queue (:cells r))))
      (is (str/includes? (screen-text r) "1 queued")))
    (testing "and the next prompt takes it without waiting for a person"
      (is (= "next question" ((:read-input r) "› ")))
      (is (empty? @(:queue (:cells r))))
      (g/frame!)
      (is (str/includes? (screen-text r) "❯ next question")))))

(deftest the-prompt-is-not-a-selection
  ;; glimmer-tui paints a FOCUSED widget in reverse video, which is right for a
  ;; button and wrong for the one widget that is focused always: it inverted the
  ;; whole bottom row for the whole session. The cursor says where the keys go.
  (let [r (mounted {:cols 40 :rows 8})]
    (g/frame!)
    (testing "the placeholder is muted, and nothing on the row is inverted"
      (let [st (style-of r "ask something")]
        (is (:dim st))
        (is (not (:reverse st)))))
    (testing "and neither is what you type"
      (type! "a question")
      (g/frame!)
      (let [st (style-of r "a question")]
        (is (not (:reverse st)))))
    (testing "the caret sits at the field, placeholder or no placeholder"
      ;; the renderer's half of it. What ncurses then DOES with the position is
      ;; the terminal's half and cannot be seen from here: glimmer-tui starts
      ;; with leaveok on, which made doupdate ignore the caret; since v0.2.6 its
      ;; screen clears it while a caret shows (byj-dx1, byj-gly.271). That half
      ;; is checked on a real terminal, not in this suite.
      (let [empty-r (mounted {:cols 40 :rows 6})
            cursor #(:cursor @(get (:screen %) :glimmer-tui.screen/state))]
        (g/frame!)
        (is (= [2 3] (cursor empty-r)) "column 2 is where '❯ ' ends, row 3 is the input line")
        (type! "hi")
        (g/frame!)
        (is (= [4 3] (cursor empty-r)) "and it follows what is typed")))
    ;; What a FOCUSED button looks like is not asserted here. Only the entry's
    ;; focus style changed, and a gate's focus is already proven where it
    ;; matters — pressing Enter answers the focused button
    ;; (`a-turn-crosses-the-boundary-with-no-terminal-at-all`). Asserting it on
    ;; the painted style would mean rendering from two threads at once, which is
    ;; what a test does when a blocked kernel thread writes a cell, and the
    ;; screen's style map interleaves when they race.
    ))

(deftest a-pasted-line-break-is-not-a-return
  ;; glimmer-tui does not decode bracketed paste, so a pasted newline arrives as
  ;; Enter. The clock tells them apart: measured under a pty, a pasted Enter
  ;; follows its character by 1–2 ms and a pressed one by 608 ms.
  (let [r (mounted {:cols 50 :rows 8})
        got (atom :unset)
        kernel (Thread. (fn [] (reset! got ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    (testing "a paste keeps its line breaks and sends nothing"
      (type! "line one")
      (g/press! 10)                                   ; the paste's own newline
      (type! "line two")
      (g/frame!)
      (is (= :unset @got) "nothing was submitted mid-paste")
      (is (str/includes? (screen-text r) "❯ line one\n  line two")
          "and the break is a second row of the field"))
    (testing "and a return pressed after it sends the lot, breaks and all"
      (enter!)
      (is (awaited #(not= :unset @got)))
      (is (= "line one\nline two" @got)))
    (.join kernel 2000)))

(deftest a-line-and-its-enter-in-one-burst-is-sent
  ;; `tmux send-keys "list all tools" Enter` left the line in the field: the
  ;; Enter came too soon after the text to be a press, so it was taken for a
  ;; pasted break, and the field waited for more (byj-10c)
  (let [r (mounted {:cols 50 :rows 8})
        got (atom :unset)
        kernel (Thread. (fn [] (reset! got ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    (type! "list all tools")
    (g/press! 10)
    (is (= :unset @got) "while the burst may still be arriving, nothing is sent")
    (Thread/sleep (inc tui/paste-gap-ms))
    ((:settle-paste! r))
    (is (awaited #(not= :unset @got)))
    (is (= "list all tools" @got) "once it has stopped, the line is sent without the break")
    (.join kernel 2000)))

(deftest up-walks-back-through-what-you-typed
  (let [r (mounted {:cols 50 :rows 8})
        draft #(deref (:draft (:cells r)))]
    (type! "first question") (enter!)
    (type! "second question") (enter!)
    (testing "up walks back, newest first, and stops at the oldest"
      (g/press! {:type :up})
      (is (= "second question" (draft)))
      (g/press! {:type :up})
      (is (= "first question" (draft)))
      (g/press! {:type :up})
      (is (= "first question" (draft)) "there is nothing older"))
    (testing "down walks forward, and past the newest is what you were typing"
      (g/press! {:type :down})
      (is (= "second question" (draft)))
      (g/press! {:type :down})
      (is (= "" (draft)) "the empty line the walk started from"))
    (testing "a line in progress is kept, and comes back"
      (type! "half a thou")
      (g/press! {:type :up})
      (is (= "second question" (draft)))
      (g/press! {:type :down})
      (is (= "half a thou" (draft))))
    (testing "typing leaves the walk, so up starts again from the end"
      (g/press! {:type :up})
      (is (= "second question" (draft)))
      (type! "x")
      (g/press! {:type :up})
      (is (= "second question" (draft))))
    (testing "asking the same thing twice is one thing to walk back to"
      (let [r2 (mounted {:cols 50 :rows 8})]
        (type! "again") (enter!)
        (type! "again") (enter!)
        (is (= ["again"] @(:history (:cells r2))))))))

(deftest a-long-paste-folds-to-a-marker
  ;; a hundred-line trace in a one-line field scrolls everything else away; what
  ;; you want to see is that it arrived. The field holds the marker, the model
  ;; reads the paste (brainyard's [paste #N +M lines]).
  (let [r (mounted {:cols 50 :rows 8})
        draft #(deref (:draft (:cells r)))
        got (atom :unset)
        kernel (Thread. (fn [] (reset! got ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    (testing "the first few breaks are marks, as before"
      (doseq [line ["one" "two"]]
        (doseq [ch line] (g/press! (int ch)))
        (g/press! 10))
      (is (str/includes? (draft) "one\ntwo\n")))
    (testing "and when the paste stops arriving, the lot folds to one marker"
      (doseq [line ["three" "four" "five" "six"]]
        (doseq [ch line] (g/press! (int ch)))
        (g/press! 10))
      (is (str/includes? (draft) "six\n") "still raw while it is still arriving")
      ;; the loop's tick is what notices a paste has ended; a test is the loop
      (Thread/sleep (inc tui/paste-gap-ms))
      ((:settle-paste! r))
      (is (re-matches #"\[paste #1 \+\d+ lines\]" (draft)) (str "field held " (pr-str (draft))))
      (is (str/includes? (draft) "+6 lines"))
      (g/frame!)
      (is (str/includes? (screen-text r) "[paste #1 +6 lines]")
          "and the row says so, rather than showing six lines of marks"))
    (testing "and what is submitted is the paste, not the marker"
      (enter!)
      (is (awaited #(not= :unset @got)))
      (is (= "one\ntwo\nthree\nfour\nfive\nsix" @got)))
    (.join kernel 2000)))

(deftest alt-enter-is-a-break-you-asked-for
  (let [r (mounted {:cols 50 :rows 8})
        draft #(deref (:draft (:cells r)))
        got (atom :unset)
        kernel (Thread. (fn [] (reset! got ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    (type! "first line")
    (g/press! {:type :alt :base-type :enter})
    (type! "second line")
    (is (= "first line\nsecond line" (draft)) "no pause needed: the break was asked for")
    (enter!)
    (is (awaited #(not= :unset @got)))
    (is (= "first line\nsecond line" @got))
    (.join kernel 2000)))

(deftest a-slash-command-completes-itself
  (let [r (mounted {:cols 60 :rows 10 :commands [["/quit" "end the session"]
                                                 ["/history" "show the conversation"]
                                                 ["/help" "this list"]]})]
    (type! "/h")
    (g/frame!)
    (testing "what the line could still become is on screen, and what it means"
      (let [text (screen-text r)]
        (is (str/includes? text "/history"))
        (is (str/includes? text "/help"))
        (is (not (str/includes? text "/quit")))))
    (testing "the arrows steer the menu while it is up, and the history when it is not"
      (g/press! {:type :down})
      (g/frame!)
      (is (= 1 (:at @(:menu (:cells r)))) "down moved the mark, not the history")
      (g/press! {:type :up})
      (is (= 0 (:at @(:menu (:cells r))))))
    (testing "the footer says which keys the menu answers to"
      (g/frame!)
      (is (str/includes? (screen-text r) "↑↓ · enter takes · esc closes")))
    (testing "Enter takes the one that is marked, and does not send the line"
      (let [sent (:queue (:cells r))]
        (enter!)
        (g/frame!)
        (is (= "/history " @(:draft (:cells r))))
        (is (empty? @sent) "nothing was sent")
        (is (nil? @(:menu (:cells r))))
        (is (not (str/includes? (screen-text r) "› /help"))
            "and a finished command has no menu under it")))
    (testing "Esc closes the menu and leaves the line as it was"
      (type! "x")
      (g/press! {:type :backspace})
      (reset! (:draft (:cells r)) "/h")
      (reset! (:menu (:cells r)) (tui/command-completions [["/help" "h"] ["/history" "h"]] "/h"))
      (g/frame!)
      (g/press! {:type :escape})
      (g/frame!)
      (is (nil? @(:menu (:cells r))))
      (is (= "/h" @(:draft (:cells r)))))))

(def ^:private some-rows
  [{:key :max-iterations :family :loop :type "integer" :value 20 :default 20 :source :default
    :shown "20" :edit "20" :writable? true :doc "Iteration ceiling for one turn."}
   {:key :recap? :family :session :type "boolean" :value true :default true :source :default
    :shown "true" :edit "true" :writable? true :doc "After a long turn, write a recap."}
   {:key :recap-after-ms :family :session :type "integer" :value 30000 :default 60000 :source :project
    :shown "30000" :edit "30000" :writable? true :changed? true :doc "How long a turn must run."}
   {:key :permission-mode :family :permissions :type "keyword" :value :auto :default :auto :source :default
    :shown ":auto" :edit "auto" :writable? false :why-not "a permission key" :doc "How the gate decides."}])

(deftest the-terminal-size-is-read-before-anything-is-laid-out
  ;; byj-gly.286: a resumed transcript was laid out at 80x24 and reflowed when
  ;; the loop learned the size — a page of different text, like another
  ;; session flashing past; `stty size` gives it before the first layout
  (is (= {:w 120 :h 30} (tui/parse-stty-size "30 120\n")))
  (is (nil? (tui/parse-stty-size "stty: stdin isn't a terminal")))
  (is (nil? (tui/parse-stty-size "0 0"))))

(deftest auto-modes-verdict-is-said-under-its-call
  ;; byj-gly.285.2: what the classifier decided sits under the call it decided
  (let [r (mounted {:cols 90 :rows 20})]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "read-file" :tool-args {:path "/etc/hosts"}}
                                                    {:tool-name "bash" :tool-args {:command "rm -rf ~/Documents"}}
                                                    {:tool-name "read-file" :tool-args {:path "/etc/passwd"}}]})
    ((:on-event r) :permission/classified {:verdict :allow :reason "reads a system file you asked about" :tool "read-file"})
    ((:on-event r) :permission/classified {:verdict :block :reason "deletes your Documents" :tool "bash"})
    ((:on-event r) :permission/classified {:verdict :block :reason "reads password data" :tool "read-file"})
    (g/frame!)
    (let [lines (vec (scr/lines (:screen r)))
          at #(first (keep-indexed (fn [i l] (when (str/includes? l %) i)) lines))]
      (is (= (inc (at "read-file(path=/etc/hosts)")) (at "✓ allowed by auto mode: reads a system file")))
      (is (= (inc (at "bash(command=rm -rf ~/Documents)")) (at "⛔ blocked by auto mode: deletes your Documents")))
      (is (= (inc (at "read-file(path=/etc/passwd)")) (at "⛔ blocked by auto mode: reads password data"))
          "the second call of a tool gets the second verdict")
      (is (:dim (style-of r "✓ allowed by auto mode")))
      (is (= :red (:fg (style-of r "⛔ blocked by auto mode")))))))

(deftest a-marked-settings-detail-is-wrapped-not-cut
  ;; byj-gly.283: the value, the doc and the note were cut at the screen edge,
  ;; so "…so this woul" was all a person read of why a key was not writable
  (let [dirs ["/tmp" "/var/folders/34/6jtbxnf95_1cz7sn47sk2qjw0000gn/T/" "/Users/someone/Projects/a-long-project-name" "/Users/someone/.brainyard"]
        why (str "a permission key, never written from inside a turn (RFC-012). A config file outlives the "
                 "session, so this would let a turn widen what every later turn may do: the END")
        rows [{:key :allowed-dirs :family :permissions :type "vector" :value dirs :default nil :source :default
               :shown (pr-str dirs) :edit "" :writable? false :why-not why
               :doc (str "Directories a tool may read and write, beyond the working dir. Unset means /tmp, "
                         "the system temp dir, the working dir and ~/.brainyard. The DOC END")}
              {:key :path-permission? :family :permissions :type "boolean" :value true :default true :source :default
               :shown "true" :edit "true" :writable? true :doc "Check paths."}]
        r (mounted {:cols 80 :rows 30})
        c (:cells r)]
    (doseq [i (range 60)] ((:say! r) :info (str "line " i)))
    ((:present r) :config {:rows rows})
    (g/press! {:type :up}) (g/frame!)
    (let [lines (vec (scr/lines (:screen r)))
          text (str/join "\n" lines)]
      (is (= :allowed-dirs (:id (tui/picker-selected @(:picker c)))))
      (is (str/includes? text "a-long-project-name") "the value is shown whole, wrapped")
      (is (str/includes? text "The DOC END") "the doc to its end")
      (is (str/includes? text "the END") "the note to its end")
      (is (every? #(<= (text/width %) 80) lines))
      (is (some #(str/starts-with? (str/triml %) "❯") lines) "and the input line is still on the screen"))))

(deftest config-opens-an-editor-grouped-by-family
  (let [r (mounted {:cols 90 :rows 30})
        c (:cells r)
        text #(screen-text r)]
    (type! "half-typed")
    (is ((:present r) :config {:rows some-rows}) "it says it showed them, so the session prints nothing")
    (g/frame!)
    (testing "the families head their settings; a switch is a dot, anything else its value"
      (is (str/includes? (text) "config · 4 · type to filter"))
      (is (re-find #"\n loop\s*\n › +max-iterations +20" (text)))
      (is (str/includes? (text) "● recap?"))
      (is (str/includes? (text) "Iteration ceiling for one turn.") "the marked one's doc")
      (is (str/includes? (text) "enter flips or edits")))
    (testing "the arrows move the mark, and what is typed filters"
      (g/press! {:type :down}) (g/frame!)
      (is (= :recap? (:id (tui/picker-selected @(:picker c)))))
      (type! "after") (g/frame!)
      (is (str/includes? (text) "\"after\" · 2 of 4") "a doc matches as well as a name")
      (type! "-ms") (g/frame!)
      (is (str/includes? (text) "\"after-ms\" · 1 of 4"))
      (is (= :recap-after-ms (:id (tui/picker-selected @(:picker c))))))
    (testing "Enter on a setting puts it in the line to edit, and closes the editor"
      (enter!)
      (is (nil? @(:picker c)))
      (is (= "/config recap-after-ms 30000" @(:draft c))))
    (testing "Enter on a switch flips it, quietly, and the editor stays open"
      (reset! (:draft c) "")
      ((:present r) :config {:rows some-rows}) (g/frame!)
      (g/press! {:type :down}) (g/frame!)
      (enter!)
      (is (= ["/config recap? false"] @(:queue c)) "the line the session reads next")
      (is (contains? @(:unsaid c) "/config recap? false") "and does not echo")
      (is (some? @(:picker c))))
    (testing "new rows from the session land in the open editor, the mark kept"
      ((:present r) :config {:rows (assoc-in some-rows [1 :value] false) :refresh? true}) (g/frame!)
      (is (str/includes? (text) "○ recap?"))
      (is (= :recap? (:id (tui/picker-selected @(:picker c))))))
    (testing "a setting that may not be written says why, and Enter sends nothing"
      (g/press! {:type :down}) (g/press! {:type :down}) (g/frame!)
      (reset! (:queue c) [])
      (enter!)
      (is (= [] @(:queue c)))
      (is (str/includes? (text) "not writable: a permission key")))
    (testing "Esc closes it and gives the draft back"
      (g/press! {:type :escape}) (g/frame!)
      (is (nil? @(:picker c)))
      (is (= "" @(:draft c)))))
  (testing "a refresh with no editor open shows nothing"
    (let [r (mounted {:cols 90 :rows 30})]
      (is (not ((:present r) :config {:rows some-rows :refresh? true})))
      (is (nil? @(:picker (:cells r)))))))

(deftest a-key-with-named-values-steps-through-them
  (let [r (mounted {:cols 90 :rows 20})
        c (:cells r)
        rows [{:key :output-style :family :session :type "keyword" :value :default :default :default
               :source :default :shown ":default" :edit "default" :writable? true
               :enum [:default :concise :explanatory] :doc "How much of a turn is shown."}]]
    ((:present r) :config {:rows rows}) (g/frame!)
    (is (str/includes? (screen-text r) "enter makes it concise"))
    (enter!)
    (is (= ["/config output-style concise"] @(:queue c)) "sent quietly, the editor kept open")
    (is (some? @(:picker c)))
    (testing "and from the last value back to the first"
      (is (= :default (:cycle (first (tui/config-items [(assoc (first rows) :value :explanatory)]))))))))

(deftest mcp-opens-a-picker-of-servers-and-their-tools
  (let [r (mounted {:cols 100 :rows 24})
        c (:cells r)
        rows [{:server "stub" :state :running :command "python3 stub.py" :problem nil
               :tools [{:id "mcp$stub$add" :tool "add" :description "Add two numbers" :hidden? false}
                       {:id "mcp$stub$echo" :tool "echo" :description "Echo a message" :hidden? true}]}
              {:server "weather" :state :failed :command "npx weather" :problem "mcp server weather: not found"
               :tools []}]]
    (is ((:present r) :mcp {:rows rows}))
    (g/frame!)
    (testing "a group per server, its tools folded under it"
      (is (str/includes? (screen-text r) "stub · running · 2 tool(s)"))
      (is (str/includes? (screen-text r) "› ▸ ● stub"))
      (is (not (str/includes? (screen-text r) "echo")) "folded: the tools are not listed")
      (is (str/includes? (screen-text r) "weather · failed")))
    (testing "→ opens the fold: each tool a line under it, behind an indented bullet, a hidden one marked"
      (g/press! {:type :right}) (g/frame!)
      (is (str/includes? (screen-text r) "› ▾ ● stub"))
      (is (str/includes? (screen-text r) "    - add"))
      (is (str/includes? (screen-text r) "    ○ echo")))
    (testing "Enter sends the line the row carries, quietly, and the picker stays"
      (enter!)
      (is (= ["/mcp stub stop"] @(:queue c)))
      (is (contains? @(:unsaid c) "/mcp stub stop"))
      (g/press! {:type :down}) (g/press! {:type :down}) (g/frame!)
      (enter!)
      (is (= "/mcp stub show echo" (last @(:queue c)))))
    (testing "← on a tool closes its fold and goes back to the server"
      (g/press! {:type :left}) (g/frame!)
      (is (= "stub" (:id (tui/picker-selected @(:picker c)))))
      (is (not (str/includes? (screen-text r) "echo"))))
    (testing "a typed filter finds a tool under a closed fold"
      (type! "echo") (g/frame!)
      (is (str/includes? (screen-text r) "○ echo"))
      (dotimes [_ 4] (g/press! 127)) (g/frame!))
    (testing "ctrl-r restarts the server a row belongs to"
      (g/frame!)
      (g/press! 18) (g/frame!)
      (is (= "/mcp stub restart" (last @(:queue c)))))
    (testing "a failed server says why"
      (g/press! {:type :down}) (g/frame!)
      (is (str/includes? (screen-text r) "not found")))))

(deftest the-mcp-picker-offers-the-catalogue
  (let [r (mounted {:cols 110 :rows 24})
        c (:cells r)
        rows [{:server "stub" :state :running :command "python3 stub.py" :tools []}]
        catalog [{:name "fetch" :description "Fetch a URL" :missing []}
                 {:name "github" :description "GitHub" :missing ["GITHUB_PERSONAL_ACCESS_TOKEN"]}]]
    ((:present r) :mcp {:rows rows :catalog catalog}) (g/frame!)
    (is (str/includes? (screen-text r) "catalogue — enter adds"))
    (testing "ctrl-x on a server removes it"
      (g/press! 24) (g/frame!)
      (is (= ["/mcp remove stub"] @(:queue c))))
    (testing "Enter on a catalogue entry adds it, and one that needs a variable says so"
      (g/press! {:type :down}) (g/frame!)
      (enter!)
      (is (= "/mcp add fetch" (last @(:queue c))))
      (g/press! {:type :down}) (g/frame!)
      (is (str/includes? (screen-text r) "needs GITHUB_PERSONAL_ACCESS_TOKEN — enter adds it disabled"))
      (g/press! 24) (g/frame!)
      (is (= "/mcp add fetch" (last @(:queue c))) "ctrl-x on a catalogue entry does nothing"))))

(deftest the-mcp-picker-lists-what-the-registry-has
  (let [r (mounted {:cols 120 :rows 26})
        c (:cells r)
        rows [{:server "stub" :state :running :command "python3 stub.py" :tools []}]]
    ((:present r) :mcp {:rows rows :catalog []}) (g/frame!)
    ((:present r) :mcp {:refresh? true
                        :registry [{:name "filesystem-mcp" :registry-name "io.github.x/filesystem-mcp" :version "1.3.0"
                                    :kind "npm" :description "Files" :missing []}
                                   {:name "filesystem" :registry-name "io.github.y/filesystem" :description "NuGet one"
                                    :unsupported "a nuget package, which this port cannot start"}]})
    (g/frame!)
    (testing "the hits are a group of their own, the servers kept"
      (is (str/includes? (screen-text r) "MCP registry — third-party, added disabled"))
      (is (str/includes? (screen-text r) "● stub")))
    (testing "Enter adds one by its full registry name; one that cannot start sends nothing"
      (g/press! {:type :down}) (g/frame!)
      (enter!)
      (is (= ["/mcp add io.github.x/filesystem-mcp"] @(:queue c)))
      (g/press! {:type :down}) (g/frame!)
      (is (str/includes? (screen-text r) "cannot start here: a nuget package"))
      (enter!)
      (is (= 1 (count @(:queue c)))))
    (testing "a later refresh of the servers keeps the registry's hits"
      ((:present r) :mcp {:rows rows :catalog [] :refresh? true}) (g/frame!)
      (is (str/includes? (screen-text r) "filesystem-mcp")))))

(deftest model-opens-a-picker-on-the-one-in-use
  (let [r (mounted {:cols 90 :rows 30})
        c (:cells r)
        models [{:provider :claude-code :model "opus" :description "Opus via CLI"}
                {:provider :claude-code :model "sonnet" :description "Sonnet via CLI"}
                {:provider :anthropic :model "claude-sonnet-5" :description "Sonnet 5"}]]
    ((:present r) :model {:current "claude-code/sonnet" :models models})
    (g/frame!)
    (is (str/includes? (screen-text r) "● sonnet"))
    (is (= "claude-code/sonnet" (:id (tui/picker-selected @(:picker c)))) "the mark starts on it")
    (g/press! {:type :down}) (g/frame!)
    (enter!)
    (is (nil? @(:picker c)))
    (is (= ["/model anthropic/claude-sonnet-5"] @(:queue c)) "sent as it would be typed, and echoed")
    (is (not (contains? @(:unsaid c) "/model anthropic/claude-sonnet-5")))))

(deftest the-footer-shows-the-permission-mode-and-shift-tab-cycles-it
  (let [box (atom :auto)
        r (mounted {:cols 90 :rows 12 :permission-mode-box box})
        footer #(last (scr/lines (:screen r)))]
    (testing "the mode the run started in, with how to change it"
      (is (str/starts-with? (footer) "  ▸▸ auto mode on (shift+tab to cycle)"))
      (is (= :yellow (:fg (style-of r "auto mode on")))))
    (testing "shift+tab steps through the modes, and the gates' box follows"
      (g/press! tui/shift-tab) (g/frame!)
      (is (= :auto-approve @box))
      (is (str/starts-with? (footer) "  ▸▸ auto-approve mode on (shift+tab to cycle)"))
      (g/press! tui/shift-tab) (g/frame!)
      (is (= :ask-each-time @box))
      (is (str/starts-with? (footer) "  ‖ ask-each-time mode on"))
      (is (not (str/includes? (footer) "shift+tab")))
      (g/press! tui/shift-tab) (g/frame!)
      (is (= :auto @box)))
    (testing "a change from elsewhere — an edited config — is shown too"
      (reset! box :deny-by-default) (g/frame!)
      (is (str/starts-with? (footer) "  ✕ deny-by-default mode on")))
    (testing "the terminal's shift+tab is the one that cycles, since glimmer-tui#15"
      (g/press! 353) (g/frame!)
      (is (= :auto @box) "deny-by-default is not in the cycle: the next is its first")))
  (testing "no box, no mode: a renderer nobody can choose a mode through shows none"
    (let [r (mounted {:cols 90 :rows 12})]
      (is (not (str/includes? (last (scr/lines (:screen r))) "mode on"))))))

(deftest a-one-line-map-result-is-laid-out
  (testing "a map reads as brainyard's name: value, strings bare, short things first (byj-gly.240)"
    (is (= "path: . · note: x\nentries: [{:name \"a.clj\", :size 120} {:name \"b\", :dir? true}]"
           (tui/pretty-result "{:path \".\" :entries [{:name \"a.clj\" :size 120} {:name \"b\" :dir? true}] :note \"x\"}")))
    (is (str/starts-with? (tui/pretty-result (pr-str {:a (vec (range 40))})) "a:\n  [0")
        "a collection too long for its key's line is laid out under it")
    (is (= "status: completed · exit-code: 0\noutput:\n  a\n  b"
           (tui/pretty-result (pr-str (array-map :output "a\nb" :status "completed" :exit-code 0))))
        "short values share a row and the text is last, so the first rows say what happened and how it starts")
    (is (= "a: 1 · b: [1,2]" (tui/pretty-result "{\"a\":1,\"b\":[1,2]}"))
        "JSON's values stay JSON: its keys are not read as EDN")
    (is (= "{}" (tui/pretty-result "{}")))
    (is (str/starts-with? (tui/pretty-result "[{:a 1} {:b 2}]") "[{:a 1}") "a vector is pprint's"))
  (testing "text inside a map is shown as its lines, not as one escaped string"
    (is (= "path: deps.edn · size: 42\ncontent:\n  {:paths [\"src\"]\n   :deps {}}"
           (tui/pretty-result (pr-str {:path "deps.edn" :size 42 :content "{:paths [\"src\"]\n :deps {}}\n"}))))
    (is (= "exit: 0\noutput:\n  a\n  b" (tui/pretty-result "{\"exit\":0,\"output\":\"a\\nb\"}"))
        "a JSON result's text too, keys as JSON names"))
  (testing "one cut short — by its cap, or by a resumed transcript's 400 chars — is broken by its punctuation"
    (is (= (str "[{:id \"flow$brief\",\n  :type \"command\",\n  :description \"one, two\"}\n"
                " {:id \"flow$check\",\n  :type \"comm")
           (tui/pretty-result "[{:id \"flow$brief\", :type \"command\", :description \"one, two\"} {:id \"flow$check\", :type \"comm")))
    (is (= "{\"a\":1,\n \"b\":[1,2,{\"c\":\"d, e\"}],\n \"z\":\"trunc"
           (tui/pretty-result "{\"a\":1,\"b\":[1,2,{\"c\":\"d, e\"}],\"z\":\"trunc"))
        "a comma in a string is text, and a short inner vector stays on its line"))
  (testing "anything else is left exactly as it came"
    (doseq [s ["plain prose" "line one\n{:a 1}" "(1 2 3)" "" "[not closed"]]
      (is (= s (tui/pretty-result s)) (pr-str s)))
    (is (= "{:a 1\n :b 2}" (tui/pretty-result "{:a 1\n :b 2}")) "a result already on several lines is its own layout"))
  (testing "a result arrives laid out, and the transcript shows its first lines"
    (let [r (mounted {:cols 60 :rows 16})]
      ((:on-event r) :agent.iteration/start {:iteration 1})
      ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}]})
      ((:on-event r) :agent.tools/results {:results [{:tool-result (pr-str {:path "." :entries (vec (for [i (range 8)] {:name (str "file-" i ".clj") :size (* 100 i)}))})}]})
      (g/frame!)
      (let [ls (vec (drop-while #(not (str/includes? % "list-dir")) (scr/lines (:screen r))))]
        (is (str/starts-with? (ls 1) "  ⎿  path: ."))
        (is (str/starts-with? (ls 2) "     entries:")) "one key a line, a long value under it"))))

(deftest the-model-is-named-on-the-footers-rule
  (let [box (atom nil)
        r (mounted {:cols 60 :rows 10 :lm {:provider :claude-code :model "opus"} :lm-box box})
        rule #(let [lines (vec (scr/lines (:screen r)))] (lines (- (count lines) 2)))]
    (testing "the run's model, right-aligned on the rule between the input and the footer"
      (is (str/starts-with? (rule) "────"))
      (is (str/ends-with? (str/trimr (rule)) " claude-code/opus ──")))
    (testing "a /model switch shows at once"
      (reset! box {:provider :anthropic :model "claude-sonnet-5"}) (g/frame!)
      (is (str/ends-with? (str/trimr (rule)) " anthropic/claude-sonnet-5 ──"))))
  (testing "no model known, a plain rule"
    (let [r (mounted {:cols 60 :rows 10})
          lines (vec (scr/lines (:screen r)))]
      (is (re-matches #"─+" (str/trimr (lines (- (count lines) 2))))))))

(deftest several-sessions-are-a-list
  (let [make (fn [] (tui/make {}))
        a (make) b (make) c (make) d (make)
        entries [{:id 1 :renderer a} {:id 2 :renderer b} {:id 3 :renderer c} {:id 4 :renderer d :ended? true}]]
    ((:say! a) :you "fix the drag")
    ((:emit a) :answer "Done: the drag keeps up now.")
    ((:say! b) :you "list tasks")
    (swap! (:status (:cells b)) assoc :running? true)
    (reset! (:gate (:cells c)) {:prompt ["permission: bash"] :choices []})
    (testing "each session stands where its renderer says"
      (is (= [:idle :working :needs-input :ended] (mapv tui/session-state entries))))
    (testing "a row is named by its recap, else its first question, and says the last thing it said"
      (let [row (tui/session-row (first entries) (System/currentTimeMillis))]
        (is (= "fix the drag" (:title row)))
        (is (= "Done: the drag keeps up now." (:last row))))
      (testing "a many-line answer is previewed by its first line, not its last (byj-gly.315.19)"
        ((:emit a) :answer "Tools:\n- ✓ git 2.50.1\n- skills: .claude/beads, .agents/beads")
        (is (= "Tools:" (:last (tui/session-row (first entries) (System/currentTimeMillis))))))
      (reset! (:title (:cells a)) "fix-drag-performance")
      (is (= "fix-drag-performance" (:title (tui/session-row (first entries) (System/currentTimeMillis))))))
    (testing "the list puts what needs a person first"
      (is (= [3 2 1 4] (mapv :id (tui/ordered-rows (map #(tui/session-row % 0) entries))))))
    (testing "it is drawn grouped, with an input line for a new session"
      (let [host {:entries (atom entries) :listing (atom {:at 0 :draft "" :from 1}) :tick (atom 0)
                  :size (atom {:w 90 :h 24}) :header {:title "brainyard" :detail "mock/mock · /tmp"}}
            opened (atom nil)
            view (#'tui/sessions-view host {:open! #(reset! opened %) :start! identity :end! identity
                                            :back! identity :moved! (fn [_ _])})
            root (w/node :window {})
            screen (scr/buffer-screen 90 24)]
        (g/attach! root screen)
        (ui/mount root :window [view])
        (g/frame!)
        (let [t (str/join "\n" (scr/lines screen))]
          (is (str/includes? t "1 awaiting input · 1 working · 1 waiting · 1 ended"))
          (is (str/includes? t "Your conversation moved to the background"))
          (is (< (str/index-of t "Needs input") (str/index-of t "Working") (str/index-of t "Waiting for you") (str/index-of t "Ended")))
          (is (str/includes? t "›✻ new session") "the first row is marked, named for want of a question")
          (is (str/includes? t "describe a task for a new session")))
        (g/press! 10) (g/frame!)
        (is (= 3 @opened) "enter on an empty line opens the marked session")))))

(deftest claude-codes-sessions-are-rows-of-their-own
  ;; byj-gly.287.2: the new-session row first, then the live — marked when it
  ;; runs in another terminal — then the saved
  (let [rows (tui/claude-session-rows {:live [{:id "a1" :title "fix-the-build" :status "busy" :updated-at 0}
                                              {:id "b2" :title nil :status "idle" :updated-at 0}]
                                       :saved [{:id "c3" :title "old work" :updated-at 0 :bytes 12500000}]}
                                      120000 #{"a1"})]
    (is (= [:claude-new :claude-live :claude-live :claude-saved] (map :state rows)))
    (is (= "new Claude Code session" (:title (first rows))) "the + is its glyph")
    (is (= ["busy" "idle · running in another terminal"] (map :last (filter #(= :claude-live (:state %)) rows))))
    (is (= [true false] (map :attachable? (filter #(= :claude-live (:state %)) rows))))
    (is (= "b2" (:title (nth rows 2))) "no title: its id")
    (is (= {:title "old work" :last "12.5 MB" :age "2m" :session-id "c3"}
           (select-keys (last rows) [:title :last :age :session-id])))))

(deftest a-session-agent-claude-drives-says-so-on-its-row
  ;; byj-gly.290.5: which Claude Code sessions agent$claude drives, for which
  ;; bead, and where each stands — one that stopped to ask marked for a person
  (let [rows (tui/claude-session-rows
              {:live [{:id "a1" :title "parser" :status "idle" :updated-at 0
                       :delegation {:bead "byj-9" :branch "bd/byj-9" :state "needs-person"}}
                      {:id "b2" :title "plain" :status "busy" :updated-at 0}]
               :saved [{:id "c3" :title "old" :updated-at 0 :bytes 1000000
                        :delegation {:branch "cc/x" :state "done" :pr-url "https://example.invalid/pull/1"}}]}
              0 #{"a1" "b2"})
        [_ a b c] rows]
    (is (= "idle · agent$claude byj-9 · needs you" (:last a)) "the bead before the branch")
    (is (= "busy" (:last b)) "a session no delegation drives says nothing of one")
    (is (= "1.0 MB · agent$claude cc/x · done · PR open" (:last c)))
    (is (= "needs-person" (get-in a [:delegation :state])) "the row keeps it, for the page to mark"))
  (is (nil? (tui/delegation-label nil)))
  (testing "one that stopped to ask is counted with the sessions awaiting input (byj-gly.290.8)"
    (is (= {:needs-input 2 :working 1 :claude-live 2}
           (tui/session-counts [{:state :needs-input} {:state :working}
                                {:state :claude-live :delegation {:state "needs-person"}}
                                {:state :claude-live :delegation {:state "working"}}]))))
  (is (= "agent$claude byj-1 · still working" (tui/delegation-label {:bead "byj-1" :state "timeout"}))))

(def ^:private kids-tree
  [{:id "plan/a-1" :owner "root" :at 0 :success? true :answer "Saved plan: p.md" :turns 2
    :children [{:id "explore/b-2" :owner "plan/a-1" :at 0 :success? false :question "read the tests" :children []}]}
   {:id "claude/navy-stork-1" :owner "root" :at 0
    :claude {:session-id "cc-1" :title "fix the parser" :state "needs-person" :bead "byj-9"} :children []}
   {:id "explore/c-3" :owner "root" :at 0 :live? true :question "where is it" :children []}])

(deftest a-sessions-subagents-are-rows-under-it
  ;; byj-gly.298.3: depth first, indented, capped, an agent$claude child
  ;; carrying its delegation
  (let [parent {:id [:saved "s-1"] :session-id "s-1" :state :saved}
        {:keys [rows more]} (tui/child-rows parent kids-tree 60000)]
    (is (= ["└ plan/a-1" "  └ explore/b-2" "└ claude/navy-stork-1" "└ explore/c-3"] (map :title rows)))
    (is (= 0 more))
    (is (= ["answered · 2 turns · Saved plan: p.md" "did not answer · read the tests"
            "→ Claude Code fix the parser · needs you" "working · where is it"]
           (map :last rows)))
    (is (every? #(= parent (:parent %)) rows) "each keeps its session, for Enter")
    (is (= "needs-person" (get-in (nth rows 2) [:delegation :state])) "so it is marked and counted as asking")
    (is (= "1m" (:age (first rows))))
    (testing "past the cap the rest are counted"
      (let [many (vec (for [i (range 8)] {:id (str "x/y-" i) :at i :success? true :children []}))
            {:keys [rows more]} (tui/child-rows parent many 10)]
        (is (= tui/max-child-rows (count rows)))
        (is (= (- 8 tui/max-child-rows) more))))))

(deftest the-list-puts-children-under-their-session-and-a-delegated-claude-session-in-its-group-too
  (let [host {:entries (atom [])
              :saved-rows (fn [_] [{:id [:saved "s-1"] :session-id "s-1" :state :saved :title "fix it" :last "" :age ""}
                                   {:id [:saved "s-2"] :session-id "s-2" :state :saved :title "other" :last "" :age ""}])
              :children-of (fn [row] (when (= "s-1" (:session-id row)) kids-tree))
              :claude-rows (fn [_] [{:id [:claude "cc-1"] :session-id "cc-1" :state :claude-live :title "fix the parser"}
                                    {:id [:claude "cc-2"] :session-id "cc-2" :state :claude-live :title "mine"}])}
        {:keys [rows]} (tui/list-rows host 0)]
    (is (= [[:saved "s-1"] [:child [:saved "s-1"] "plan/a-1"] [:child [:saved "s-1"] "explore/b-2"]
            [:child [:saved "s-1"] "claude/navy-stork-1"] [:child [:saved "s-1"] "explore/c-3"]
            [:saved "s-2"] [:claude "cc-1"] [:claude "cc-2"]]
           (map :id rows))
        "children right after their session; the Claude Code session a child drives is in its group too (byj-gly.304)")
    (is (= 1 (:needs-input (tui/session-counts (conj rows {:id [:claude "cc-1"] :session-id "cc-1" :state :claude-live
                                                           :delegation {:state "needs-person"}}))))
        "a delegation that asks is counted once, though its subagent's row and its session's row both say so")))

(deftest a-childs-transcript-is-rebuilt-from-its-record
  ;; byj-gly.298.4: a saved session's child, or one from before a resume, has
  ;; no buffer — its trajectory records are its transcript
  (let [r (mounted {:cols 80 :rows 24})
        recs [{:question "where is the parser?" :success true
               :iterations [{:n 1 :channel "tool" :thought "look in src"
                             :tools [{:name "read-file" :args {:path "src/p.clj"} :result "(ns p)"}]}
                            {:n 2 :channel "answer" :answer "It is in src/p.clj."}]}]]
    (is (= false (boolean (tui/show-child! r "explore/x-1" "explore" []))) "nothing recorded, nothing to show")
    (is (true? (tui/show-child! r "explore/x-1" "explore" recs)))
    (g/frame!)
    (let [text (screen-text r)]
      (is (str/includes? text "where is the parser?") "its question")
      (is (str/includes? text "read-file") "its tool call")
      (is (str/includes? text "It is in src/p.clj.") "its answer")
      (is (str/includes? text "▸ explore/x-1 · what you type asks it · esc to return") "shown as a child's transcript"))
    (is (= {:question "where is the parser?" :answer "It is in src/p.clj." :success? true :live? false}
           (select-keys (first (tui/session-children {:renderer r})) [:question :answer :success? :live?]))
        "the list says of it what its record says")
    (let [n (count (get-in @(:subs (:cells r)) ["explore/x-1" :lines]))]
      (tui/show-child! r "explore/x-1" "explore" recs)
      (is (= n (count (get-in @(:subs (:cells r)) ["explore/x-1" :lines]))) "a buffer it has is not rebuilt"))))

(deftest enter-on-a-child-row-opens-the-child
  (let [listing (glimmer.ratom/atom {:at 1 :draft ""})
        opened (atom nil)
        host {:entries (atom []) :listing listing :tick (atom 0) :size (atom {:w 90 :h 22})
              :header {:title "brainyard" :detail ""}
              :saved-rows (fn [_] [{:id [:saved "s-1"] :session-id "s-1" :state :saved :title "fix it" :last "" :age ""}])
              :children-of (fn [_] [{:id "explore/x-1" :at 0 :success? true :children []}])}
        view (#'tui/sessions-view host {:open! identity :start! identity :end! identity :resume! identity
                                        :back! identity :moved! identity :claude! identity
                                        :open-child! (fn [row] (reset! opened row) nil)})
        root (w/node :window {})
        screen (scr/buffer-screen 90 22)]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (g/press! 10)
    (is (= ["explore/x-1" "s-1"] [(get-in @opened [:child :id]) (get-in @opened [:parent :session-id])]))
    (is (not (contains? @listing :note))
        "nothing to say writes nothing: a note would bring back the list the child's session closed")))

(deftest an-agent-claude-subagent-opens-its-view-on-enter-and-its-claude-code-session-on-right
  ;; asked 2026-10-08: Enter is the subagent's view, as for any subagent; → is
  ;; the Claude Code session it drives
  (let [listing (glimmer.ratom/atom {:at 1 :draft ""})
        viewed (atom nil)
        attached (atom nil)
        host {:entries (atom []) :listing listing :tick (atom 0) :size (atom {:w 110 :h 22})
              :header {:title "brainyard" :detail ""}
              :saved-rows (fn [_] [{:id [:saved "s-1"] :session-id "s-1" :state :saved :title "fix it" :last "" :age ""}])
              :children-of (fn [_] [{:id "claude/silver-dolphin-1" :at 0 :children []
                                     :claude {:session-id "cc-1" :title "fix the parser" :state "working" :live? true}}])}
        view (#'tui/sessions-view host {:open! identity :start! identity :end! identity :resume! identity
                                        :back! identity :moved! identity :claude! identity
                                        :open-child! (fn [row] (reset! viewed row) nil)
                                        :claude-of! (fn [row] (reset! attached row) nil)})
        root (w/node :window {})
        screen (scr/buffer-screen 110 22)]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (is (str/includes? (str/join "\n" (scr/lines screen)) "→ its Claude Code session") "the footer says what → does")
    (g/press! {:type :right})
    (is (= "cc-1" (get-in @attached [:child :claude :session-id])) "→: its Claude Code session")
    (is (nil? @viewed))
    (g/press! 10)
    (is (= "claude/silver-dolphin-1" (get-in @viewed [:child :id])) "Enter: its view")))

(deftest a-list-window-keeps-the-mark-in-view
  (is (= 0 (tui/list-window 0 3 10 40)) "in view: it stays")
  (is (= 5 (tui/list-window 0 14 10 40)) "below: just far enough")
  (is (= 7 (tui/list-window 12 7 10 40)) "above: up to it")
  (is (= 30 (tui/list-window 35 39 10 40)) "never past the end")
  (is (= 0 (tui/list-window 4 2 10 5)) "a short list is all of it"))

(deftest a-long-session-list-is-a-window
  ;; byj-gly.287.2: the list ran past the screen and pushed its input line off;
  ;; now it is a window that follows the mark, with what is above and below
  ;; reactive, as run-many!'s is: the window follows the mark as it moves
  (let [listing (glimmer.ratom/atom {:at 0 :draft ""})
        opened (atom nil)
        host {:entries (atom []) :listing listing :tick (atom 0)
              :size (atom {:w 90 :h 22}) :header {:title "brainyard" :detail "mock/mock · /tmp"}
              :saved-rows (fn [_] (vec (for [i (range 12)] {:id [:saved (str "s" i)] :session-id (str "s" i) :state :saved
                                                           :title (str "saved " i) :last "" :age "1h"})))
              :claude-rows (fn [now] (tui/claude-session-rows {:live [{:id "cc1" :title "live-cc" :status "busy"}]
                                                              :saved (vec (for [i (range 8)] {:id (str "cs" i) :title (str "claude saved " i)}))}
                                                             now (constantly true)))}
        moved! (fn [d n] (swap! listing update :at #(max 0 (min (dec (max 1 n)) (+ (or % 0) d)))))
        view (#'tui/sessions-view host {:open! identity :start! identity :end! identity :resume! identity
                                        :back! identity :moved! moved! :claude! #(reset! opened %)})
        root (w/node :window {})
        screen (scr/buffer-screen 90 22)
        text #(str/join "\n" (scr/lines screen))]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (testing "the top of the list, with what is below counted, and the input on screen"
      (is (str/includes? (text) "›↺ saved 0"))
      (is (re-find #"↓ \d+ more" (text)))
      (is (str/includes? (text) "describe a task for a new session")))
    (testing "moving the mark past the bottom moves the window with it"
      (dotimes [_ 12] (g/press! {:type :down}) (g/frame!))
      (is (str/includes? (text) "›+ new Claude Code session"))
      (is (str/includes? (text) "Claude Code") "its heading comes into view with it")
      (is (re-find #"↑ \d+ more" (text)))
      (is (not (str/includes? (text) "saved 0"))))
    (testing "pgdn moves a page, the wheel three rows, and nothing is drawn past the screen"
      (g/press! {:type :page-down}) (g/frame!)
      (is (str/includes? (text) "›↺ claude saved"))
      (let [before (:at @listing)]
        (tui/wheel! 5 5 -3) (g/frame!)
        (is (= (- before 3) (:at @listing))))
      (is (= 22 (count (scr/lines screen)))))
    (testing "enter on a Claude Code row hands it to whoever runs them"
      (reset! listing {:at 12 :draft ""}) (g/frame!)
      (g/press! 10)
      (is (= :claude-new (:state @opened))))))

(deftest ctrl-g-unfolds-what-a-session-changed-under-its-row
  ;; byj-gly.326: every session's row, the brainyard ones and Claude Code's
  (let [listing (glimmer.ratom/atom {:at 0 :draft ""})
        asked (atom [])
        host {:entries (atom []) :listing listing :tick (atom 0)
              :size (atom {:w 90 :h 22}) :header {:title "brainyard" :detail ""}
              :saved-rows (fn [_] [{:id [:saved "s1"] :session-id "s1" :state :saved :title "saved one" :last "" :age "1h"}])
              :claude-rows (fn [now] (tui/claude-session-rows {:live [{:id "cc1" :title "live-cc" :status "idle" :dir "/p/wt"}]} now (constantly true)))}
        diff! (fn [row]
                (swap! asked conj [(:state row) (:session-id row) (:dir row)])
                (swap! listing assoc :diff {:id (:id row) :lines ["worktree /p/wt · bd/x, 1 commit(s) past main · 2 files changed"
                                                                  "  a.js | 3 ++-"]}))
        view (#'tui/sessions-view host {:open! identity :start! identity :end! identity :resume! identity
                                        :back! identity :moved! (fn [d n] (swap! listing update :at #(max 0 (min (dec n) (+ % d)))))
                                        :diff! diff!})
        root (w/node :window {})
        screen (scr/buffer-screen 90 22)
        text #(str/join "\n" (scr/lines screen))]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (is (str/includes? (text) "ctrl+g its diff") "the footer says so")
    (g/press! 7) (g/frame!)
    (is (= [[:saved "s1" nil]] @asked) "a brainyard session's row")
    (is (str/includes? (text) "     worktree /p/wt · bd/x, 1 commit(s) past main · 2 files changed"))
    (is (str/includes? (text) "       a.js | 3 ++-"))
    (testing "the mark still moves over rows only"
      (g/press! {:type :down}) (g/press! {:type :down}) (g/frame!)
      (g/press! 7) (g/frame!)
      (is (= [:claude-live "cc1" "/p/wt"] (last @asked)) "a Claude Code row, with its own directory"))))

(deftest ctrl-s-shares-a-live-claude-code-session-and-the-row-says-so
  ;; byj-1wv.3.14 (RFC-043): a live Claude Code row, not a saved one; the note
  ;; is what the sharer says, and the row is marked while it is shared — an
  ;; open byj session's row too (RFC-043, `shareable-row?` below)
  (let [listing (glimmer.ratom/atom {:at 0 :draft ""})
        shared (atom #{})
        asked (atom [])
        host {:entries (atom []) :listing listing :tick (atom 0)
              :size (atom {:w 90 :h 22}) :header {:title "brainyard" :detail ""}
              :saved-rows (fn [_] [{:id [:saved "s1"] :session-id "s1" :state :saved :title "saved one" :last "" :age "1h"}])
              :claude-rows (fn [now] (tui/claude-session-rows {:live [{:id "cc1" :title "live-cc" :status "idle" :dir "/p"}]} now (constantly true)))}
        share! (fn [row]
                 (swap! asked conj (:session-id row))
                 (swap! shared conj (:session-id row))
                 "sharing live-cc — opening the share…")
        view (#'tui/sessions-view host {:open! identity :start! identity :end! identity :resume! identity
                                        :back! identity :moved! (fn [d n] (swap! listing update :at #(max 0 (min (dec n) (+ % d)))))
                                        :share! share! :shared? #(contains? @shared (:session-id %))})
        root (w/node :window {})
        screen (scr/buffer-screen 90 22)
        text #(str/join "\n" (scr/lines screen))]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (testing "not on a brainyard session's row"
      (is (not (str/includes? (text) "ctrl+s shares it")))
      (g/press! 19) (g/frame!)
      (is (empty? @asked)))
    (testing "not on the new-session row either"
      (g/press! {:type :down}) (g/frame!)
      (g/press! 19) (g/frame!)
      (is (empty? @asked)))
    (testing "on a live Claude Code row: offered, shared, said, and marked"
      (g/press! {:type :down}) (g/frame!)
      (is (str/includes? (text) "ctrl+s shares it"))
      (g/press! 19) (g/frame!)
      (is (= ["cc1"] @asked))
      (is (str/includes? (text) "sharing live-cc — opening the share…"))
      (is (str/includes? (text) "shared · ")))))

(deftest ctrl-s-shares-an-open-session-of-either-kind-and-nothing-saved
  ;; RFC-043 (byj-1wv.8.4): an open byj session, whatever it is doing, and a
  ;; live Claude Code one; never a saved session, a subagent, or the row
  ;; that starts a new one
  (doseq [s [:claude-live :codex-live :needs-input :working :idle]]
    (is (tui/shareable-row? {:state s}) (str s)))
  (doseq [s [:saved :claude-saved :claude-new :codex-saved :codex-new :child :ended]]
    (is (not (tui/shareable-row? {:state s})) (str s))))

(deftest codexs-sessions-are-rows-of-their-own-after-claude-codes
  ;; byj-gly.430.4 (RFC-049): as Claude Code's — the new-session row when
  ;; Codex is installed, the live, marked when running in another terminal,
  ;; then the saved — in a group of their own after Claude Code's
  (let [rows (tui/codex-session-rows {:live [{:id "t1" :title "fix the parser" :status "busy" :updated-at 0}
                                             {:id "t2" :title nil :status "idle" :updated-at 0}]
                                      :saved [{:id "t3" :title "older work" :updated-at 0 :bytes 2500000}]}
                                     120000 #{"t1"} true)]
    (is (= [:codex-new :codex-live :codex-live :codex-saved] (map :state rows)))
    (is (= "new Codex session" (:title (first rows))))
    (is (= ["busy" "idle · running in another terminal"] (map :last (filter #(= :codex-live (:state %)) rows))))
    (is (= [true false] (map :attachable? (filter #(= :codex-live (:state %)) rows))))
    (is (= "t2" (:title (nth rows 2))) "no title: its thread")
    (is (= {:title "older work" :last "2.5 MB" :age "2m" :session-id "t3"}
           (select-keys (last rows) [:title :last :age :session-id])))
    (is (= [:codex-live :codex-saved] (map :state (tui/codex-session-rows {:live [{:id "t1"}] :saved [{:id "t3"}]} 0 nil false)))
        "Codex not installed: no row to start one")
    (is (= [:claude-saved :codex-new :codex-saved]
           (map :state (tui/ordered-rows [{:state :codex-saved} {:state :codex-new} {:state :claude-saved}])))
        "after Claude Code's group")))

(deftest enter-on-a-codex-row-hands-it-to-codex-open-and-ctrl-s-shares-a-live-one
  (let [listing (glimmer.ratom/atom {:at 0 :draft ""})
        opened (atom []) shared (atom [])
        host {:entries (atom []) :listing listing :tick (atom 0)
              :size (atom {:w 90 :h 22}) :header {:title "brainyard" :detail ""}
              :codex-rows (fn [now] (tui/codex-session-rows {:live [{:id "t1" :title "live-codex" :status "idle" :dir "/p"}]} now (constantly true) true))}
        view (#'tui/sessions-view host {:open! identity :start! identity :end! identity :resume! identity :back! identity
                                        :moved! (fn [d n] (swap! listing update :at #(max 0 (min (dec n) (+ % d)))))
                                        :codex! (fn [row] (swap! opened conj [(:state row) (:session-id row)]) nil)
                                        :share! (fn [row] (swap! shared conj (:session-id row)) "sharing live-codex")
                                        :shared? (constantly false)})
        root (w/node :window {})
        screen (scr/buffer-screen 90 22)
        text #(str/join "\n" (scr/lines screen))]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (is (str/includes? (text) "Codex") "its group's heading")
    (is (str/includes? (text) "new Codex session"))
    (testing "Enter on the new row"
      (g/press! 10) (g/frame!)
      (is (= [[:codex-new nil]] @opened)))
    (testing "the live row: Enter opens it, ctrl+s shares it"
      (g/press! {:type :down}) (g/frame!)
      (is (str/includes? (text) "ctrl+s shares it"))
      (g/press! 19) (g/frame!)
      (is (= ["t1"] @shared))
      (g/press! 10) (g/frame!)
      (is (= [[:codex-new nil] [:codex-live "t1"]] @opened)))))

(deftest left-on-an-empty-line-opens-the-list
  (let [opened (atom 0)
        r (mounted {:cols 90 :rows 12 :on-agents #(swap! opened inc)})]
    (is (str/includes? (last (scr/lines (:screen r))) "← for agents"))
    (g/press! {:type :left}) (g/frame!)
    (is (= 1 @opened))
    (type! "abc")
    (g/press! {:type :left}) (g/frame!)
    (is (= 1 @opened) "with text in the line, ← moves the caret")))

(deftest tab-takes-the-completion-and-keeps-the-input
  (let [r (mounted {:cols 60 :rows 14 :commands [["/config" "settings"] ["/clear" "start over"]]})
        c (:cells r)]
    (type! "/co")
    (g/press! tui/tab-key) (g/frame!)
    (is (= "/config " @(:draft c)) "the marked completion, as enter takes it")
    (is (nil? @(:menu c)))
    (testing "with no menu it does nothing, and what is typed next still lands"
      (g/press! tui/tab-key) (g/frame!)
      (type! "x")
      (is (= "/config x" @(:draft c))))))

(deftest the-menu-opens-above-the-input-which-does-not-move
  (let [r (mounted {:cols 60 :rows 14 :commands [["/help" "this list"] ["/history" "past lines"]]})
        input-row #(row-of r "❯")]
    (g/frame!)
    (let [before (input-row)]
      (type! "/h")
      (g/frame!)
      (let [lines (vec (scr/lines (:screen r)))
            help (row-of r "/help")
            rule-above (dec (input-row))]
        (is (= before (input-row)) "the input line stays on its row")
        (is (< help rule-above) "the completions are above the rule over the input")
        (is (str/starts-with? (lines rule-above) "────"))
        (is (str/includes? (lines (dec (count lines))) "esc closes") "and the footer is still the last row")))))

(deftest a-long-menu-scrolls-with-its-mark
  (let [items (vec (for [i (range 10)] [(str "/c" i) (str "command " i)]))]
    (testing "the window moves only as far as the mark needs"
      (let [m (reduce (fn [m _] (tui/scroll-menu m 1)) {:items items :at 0} (range 7))]
        (is (= 7 (:at m)))
        (is (= 2 (:top m)) "the mark is the window's last row")
        (is (= {:top 2 :above 2 :below 2} (select-keys (tui/menu-window m) [:top :above :below])))
        (let [m (reduce (fn [m _] (tui/scroll-menu m -1)) m (range 3))]
          (is (= 4 (:at m)))
          (is (= 2 (:top m)) "going back up inside the window does not move it"))))
    (testing "on screen, the marked item stays visible and the hidden ones are counted both ways"
      (let [r (mounted {:cols 60 :rows 16 :commands items})]
        (type! "/c")
        (g/frame!)
        (is (str/includes? (screen-text r) "↓ 4 more"))
        (dotimes [_ 8] (g/press! {:type :down}) (g/frame!))
        (let [text (screen-text r)]
          (is (str/includes? text "› /c8") "the mark is on the screen, not below it")
          (is (not (str/includes? text "/c0")))
          (is (str/includes? text "↑ 3 more · ↓ 1 more")))
        (enter!)
        (is (= "/c8 " @(:draft (:cells r))) "and Enter takes what is marked")))))

(deftest what-a-line-could-become-is-the-callers-to-say
  ;; the renderer completes the commands it was handed and nothing else: which
  ;; files exist, and which models, is not a renderer's business (RFC-001)
  (let [asked (atom [])
        r (mounted {:cols 60 :rows 10
                    :completions (fn [draft]
                                   (swap! asked conj draft)
                                   (when (str/ends-with? draft "@src/")
                                     {:items [["@src/one.clj" "a file"] ["@src/two.clj" "another"]]
                                      :from (str/index-of draft "@")}))})]
    (type! "read @src/")
    (g/frame!)
    (testing "the caller is asked per keystroke, not per frame — a completer may
              walk a directory, and a frame must not"
      (let [after-typing (count @asked)]
        (dotimes [_ 3] (g/frame!))
        (is (= after-typing (count @asked)))))
    (testing "and what it offers is on screen, steerable, and replaces the token"
      (is (str/includes? (screen-text r) "@src/one.clj"))
      (is (str/includes? (screen-text r) "a file"))
      (g/press! {:type :down})
      (enter!)
      (is (= "read @src/two.clj " @(:draft (:cells r)))))))

(deftest inline-markup-is-style-rather-than-punctuation
  (testing "a line with no markup is one run, so the common row is one widget"
    (is (= [{:text "plain prose"}] (tui/spans "plain prose"))))
  (testing "bold and code are runs with a style; the markers are gone"
    (is (= [{:text "a "}
            {:text "bold" :style {:bold true}}
            {:text " and "}
            {:text "code" :style {:color :green}}]
           (tui/spans "a **bold** and `code`"))))
  (testing "an unmatched marker is text — a model writing 2 * 3 means 2 * 3"
    (is (= [{:text "2 * 3 and __ alone"}] (tui/spans "2 * 3 and __ alone"))))
  (testing "a wrap keeps a run's style across the break"
    (is (= [[{:text "one" :style nil}
             {:text " two" :style {:bold true}}
             {:text " three" :style {:bold true}}]
            [{:text "four" :style nil}]]
           (tui/wrap-spans (tui/spans "one **two three** four") 14))))
  (testing "a boundary inside a word does not become a break"
    (is (= [[{:text "say" :style nil}
             {:text " bold" :style {:bold true}}
             {:text "s" :style nil}]]
           (tui/wrap-spans (tui/spans "say **bold**s") 40)))))

(deftest an-answer-is-read-as-the-markdown-it-is
  (let [r (mounted {:cols 60 :rows 14})]
    ((:emit r) :answer "# Heading\nprose\n```clojure\n(+ 1 2)\n```\ndone")
    (g/frame!)
    (let [text (screen-text r)]
      (is (str/includes? text "Heading"))
      (is (not (str/includes? text "#")) "a heading's marker is not text, as in brainyard's markdown-line")
      (is (:bold (style-of r "Heading")))
      (is (str/includes? text "│ (+ 1 2)") "a fenced line is a block, gutter and all")
      (is (str/includes? text "clojure") "the fence names its language")
      (is (not (str/includes? text "```")) "and the fence itself is never text")))
  (testing "inline markup reaches the screen as text, not as markers"
    (let [r (mounted {:cols 50 :rows 8})]
      ((:emit r) :answer "it contains **1 file** called `notes.txt`")
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "it contains 1 file called notes.txt"))
        (is (not (str/includes? text "**")))
        (is (not (str/includes? text "`"))))
      (testing "and the emphasis is on the screen as emphasis"
        ;; :fg, not :color — the widget resolves a colour prop before painting,
        ;; and what the screen is handed is the painter's style map
        (is (:bold (style-of r "1 file")))
        (is (= :green (:fg (style-of r "notes.txt"))))
        (is (not (:bold (style-of r "it contains")))))))
  (testing "a paragraph break keeps its row"
    (let [r (mounted {:cols 40 :rows 9})]
      ((:emit r) :answer "first\n\nsecond")
      (g/frame!)
      (let [lines (drop-while #(not (str/includes? % "first")) (scr/lines (:screen r)))]
        (is (= ["⏺ first" "" "  second"] (take 3 lines))
            "under one bullet, and indented to sit beside it"))))
  (testing "but a fenced line keeps its own characters"
    (let [r (mounted {:cols 50 :rows 8})]
      ((:emit r) :answer "```\nrun **this** verbatim\n```")
      (g/frame!)
      (is (str/includes? (screen-text r) "run **this** verbatim")))))

(deftest emphasis-links-and-the-asterisks-that-are-arithmetic
  (testing "single emphasis is drawn underlined — glimmer-tui has no italic"
    (is (= [{:text "one "} {:text "two" :style {:underline true}}]
           (tui/spans "one *two*")))
    (is (= [{:text "two" :style {:underline true}}] (tui/spans "_two_"))))
  (testing "a marker that does not flank a word is text"
    (is (= [{:text "2 * 3 * 4"}] (tui/spans "2 * 3 * 4")))
    (is (= [{:text "snake_case_name"}] (tui/spans "snake_case_name"))))
  (testing "markup nests, except inside code"
    (is (= [{:text "a " :style {:bold true}} {:text "b" :style {:color :green :bold true}}]
           (tui/spans "**a `b`**")))
    (is (= [{:text "*x*" :style {:color :green}}] (tui/spans "`*x*`"))))
  (testing "a link is its text, with the address kept beside it, dim"
    (is (= [{:text "see "}
            {:text "jolt" :style {:underline true :color :blue}}
            {:text " (https://x.io)" :style {:dim true}}]
           (tui/spans "see [jolt](https://x.io)")))
    (is (= [{:text "https://x.io" :style {:underline true :color :blue}}]
           (tui/spans "<https://x.io>")))))

(deftest a-long-word-after-others-starts-its-own-row
  ;; it used to be cut to the width less one, ignoring the words already on the
  ;; row, and the row overran the margin by their width
  (let [rows (tui/wrap-spans (tui/spans "ab supercalifragilistic") 10)]
    (is (every? #(<= (reduce + (map (comp count :text) %)) 10) rows))
    (is (= "absupercalifragilistic"
           (str/replace (apply str (map :text (apply concat rows))) " " "")))))

(deftest blocks-keep-their-own-margins
  (let [r (mounted {:cols 30 :rows 16})]
    ((:emit r) :answer (str "- a list item long enough to wrap twice over here\n"
                            "  2. nested\n"
                            "> quoted and long enough to need a second row\n"
                            "---\n"
                            "after"))
    (g/frame!)
    (let [lines (vec (drop-while #(not (str/includes? % "•")) (scr/lines (:screen r))))]
      (testing "a wrapped item hangs under its text, not under its bullet"
        (is (str/starts-with? (lines 0) "⏺ • a list"))
        (is (str/starts-with? (lines 1) "    "))
        (is (not (str/includes? (lines 1) "•"))))
      (testing "a nested item keeps its indent and its number"
        (is (some #(str/starts-with? % "    2. nested") lines)))
      (testing "every row of a quote carries the bar"
        (is (= 2 (count (filter #(str/starts-with? % "  ▎ ") lines)))))
      (testing "a thematic break is a rule the width of the screen"
        (is (some #(= (str "  " (apply str (repeat 28 "─"))) %) (take 6 lines))))
      (is (:dim (style-of r "quoted"))))))

(deftest a-pipe-table-is-laid-out-as-a-table
  (testing "the entry keeps the cells and the alignment the rule asked for"
    (let [[t] (tui/markdown "| a | b |\n|---|--:|\n| 1 | 22 |\n| x \\| y | 3 |")]
      (is (= :table (:kind t)))
      (is (= [["a" "b"] ["1" "22"] ["x | y" "3"]] (:rows t)))
      (is (= [:left :right] (:align t)))))
  (testing "a bar with no rule under it is only a bar"
    (is (= [:answer] (map :kind (tui/markdown "a | b")))))
  (let [r (mounted {:cols 40 :rows 12})]
    ((:emit r) :answer "| Name | N |\n|------|--:|\n| glimmer | 7 |\n| ebb | 12 |")
    (g/frame!)
    (let [lines (vec (drop-while #(not (str/includes? % "Name")) (scr/lines (:screen r))))]
      (testing "columns line up, the header is bold, and a rule divides them"
        (is (= "⏺ Name    │  N" (lines 0)))
        (is (= "  ────────┼───" (lines 1)))
        (is (= "  glimmer │  7" (lines 2)))
        (is (= "  ebb     │ 12" (lines 3)))
        (is (:bold (style-of r "Name"))))))
  (testing "a table wider than the screen wraps inside its columns and loses nothing"
    (let [r (mounted {:cols 24 :rows 14})]
      ((:emit r) :answer "| k | v |\n|---|---|\n| id | a value long enough that it cannot fit |")
      (g/frame!)
      (let [lines (scr/lines (:screen r))]
        (is (every? #(<= (count %) 24) lines))
        (is (str/includes? (str/replace (str/join " " lines) #"[\s│]+" " ")
                           "a value long enough that it cannot fit"))))))

(deftest a-reply-is-markdown-while-it-streams
  (let [r (mounted {:cols 40 :rows 10})]
    ((:on-event r) :agent.llm/chunk {:field :answer :text "## Plan\n- a **bold"})
    ((:on-event r) :agent.llm/chunk {:field :answer :text "** step"})
    (g/frame!)
    (let [text (screen-text r)]
      (is (str/includes? text "• a bold step"))
      (is (not (str/includes? text "##")))
      (is (:bold (style-of r "bold"))))))

(deftest the-status-bar-carries-what-a-turn-cost
  (let [r (mounted {:cols 74 :rows 8})]
    (testing "a turn priced at nothing takes no column — the mock prices at zero"
      ((:on-event r) :session.turn/end {:usage {:cost 0.0}})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "$"))))
    (testing "and what is spent accumulates"
      ((:on-event r) :session.turn/end {:usage {:cost 0.0123}})
      ((:on-event r) :session.turn/end {:usage {:cost 0.0002}})
      (g/frame!)
      (is (str/includes? (screen-text r) "$0.0125")))))

;; --- the seams -----------------------------------------------------------------------

(deftest a-turn-crosses-the-boundary-with-no-terminal-at-all
  ;; RFC-027's six seams, driven for real: the kernel runs on its own thread and
  ;; BLOCKS on the two that are calls, while the driver types and answers. A
  ;; renderer that cannot do this is not a renderer for this kernel.
  (let [r (mounted {:cols 46 :rows 14})
        permission-fn (permission/asking-permission-fn (:ask r))
        got (atom {})
        ;; the ask is a modal centred on the screen, so it may cover the row the
        ;; streamed reply is on: the kernel asks once the driver has seen it
        seen (promise)
        kernel (Thread.
                (fn []
                  (let [line ((:read-input r) "› ")]
                    (swap! got assoc :line line)
                    ((:on-event r) :session.turn/start {:turn 1 :question line})
                    (doseq [text ["reading " "deps.edn"]]
                      ((:on-event r) :agent.llm/chunk {:field :answer :text text}))
                    (deref seen 5000 nil)
                    (swap! got assoc :answer (permission-fn {:display "bash"
                                                             :pattern "bash"
                                                             :args {:command "cat deps.edn"}}))
                    ((:emit r) :answer "deps.edn lists four dependencies")
                    ((:on-event r) :session.turn/end {:terminated-by :answer})
                    (swap! got assoc :done true))))]
    (.start kernel)
    (testing "the input line waits for a person, and what they type reaches the kernel"
      (is (awaited #((:waiting-for-input? r))))
      (type! "read deps.edn")
      (enter!)
      (is (awaited #(:line @got)))
      (is (= "read deps.edn" (:line @got))))

    (testing "a streamed reply is on screen before the turn has finished"
      (is (awaited #(str/includes? (do (g/frame!) (screen-text r)) "reading deps.edn")))
      (deliver seen true))

    (testing "the permission ask blocks the turn until a button answers it"
      (is (awaited #(some? @(:gate (:cells r)))))
      (is (gate-painted? r) "the modal is on screen before a key goes to it")
      (is (str/includes? (screen-text r) "permission"))
      (is (str/includes? (screen-text r) "allow once"))
      (g/press! 10)                                   ; Enter on the focused "allow once"
      (is (awaited #(:answer @got)))
      (is (= {:allowed true} (:answer @got))))

    (testing "and the turn's answer lands in the transcript"
      (is (awaited #(:done @got)))
      (g/frame!)
      (let [text (screen-text r)]
        (is (str/includes? text "❯ read deps.edn"))
        (is (str/includes? text "deps.edn lists four"))))
    (.join kernel 2000)
    (is (not (.isAlive kernel)) "the kernel thread was never left blocked")))

;; a server that publishes files asks with a line a file: spaced a row apart they
;; ran past the screen, and the modal showed the first five in a box gone blank
(deftest a-long-ask-shows-its-lines-together-and-says-what-did-not-fit
  (let [files (for [i (range 26)] (str "  src/file-" i ".py"))
        prompt (into ["permission: Publish shop-starter to Share: 26 files"] files)]
    (testing "on a screen with room, every line, one under the other"
      (let [r (mounted {:cols 100 :rows 40})]
        (reset! (:gate (:cells r)) {:prompt prompt :choices [["allow once" "y"] ["deny" "n"]]})
        (is (gate-painted? r "src/file-25.py"))
        (let [rows (vec (scr/lines (:screen r)))
              at (fn [needle] (first (keep-indexed #(when (str/includes? %2 needle) %1) rows)))]
          (is (= 1 (- (at "file-1.py") (at "file-0.py"))) "no row between two lines")
          (is (= 25 (- (at "file-25.py") (at "file-0.py")))))))
    (testing "on a smaller one, as many as fit and how many more"
      (is (= prompt (tui/gate-lines prompt 40)))
      (let [shown (tui/gate-lines prompt 20)]
        (is (= 11 (count shown)))
        (is (= (take 10 prompt) (butlast shown)))
        (is (= "… 17 more lines" (last shown))))
      (is (= 2 (count (tui/gate-lines prompt 4))) "a line and the count, however small"))))

(deftest a-denied-gate-answers-the-turn-too
  (let [r (mounted {:cols 46 :rows 14})
        permission-fn (permission/asking-permission-fn (:ask r))
        answer (atom nil)
        kernel (Thread. (fn [] (reset! answer (permission-fn {:display "rm -rf /" :pattern "bash"}))))]
    (.start kernel)
    (is (awaited #(some? @(:gate (:cells r)))))
    (is (gate-painted? r) "the modal is on screen before a key goes to it")
    (g/press! 9)                                      ; Tab to "deny"
    (g/press! 10)
    (is (awaited #(some? @answer)))
    (is (:denied @answer) "Tab is the ask's, not a completion's (byj-gly.272)")
    (.join kernel 2000)))

(deftest a-permission-ask-takes-its-letters
  ;; byj-1wv.11: the menu line names letters; a key was the only way not to
  ;; answer by button
  (doseq [[key want] [[\y :allowed] [\n :denied]]]
    (let [r (mounted {:cols 46 :rows 14})
          permission-fn (permission/asking-permission-fn (:ask r))
          answer (atom nil)
          kernel (Thread. (fn [] (reset! answer (permission-fn {:display "rm -rf /" :pattern "bash"}))))]
      (.start kernel)
      (is (awaited #(some? @(:gate (:cells r)))))
      (is (gate-painted? r))
      (g/press! (int key))
      (is (awaited #(some? @answer)))
      (is (want @answer) (str key " answers " want))
      (.join kernel 2000))))

(deftest a-shared-session-shows-who-watches-and-asks-before-an-injected-turn
  ;; RFC-043: the footer, a line for a note and for who joined, and the
  ;; injected turn's approval in the gate's overlay
  (let [r (mounted {:cols 70 :rows 16})
        on-event (:on-event r)
        presence {:share "shr_1" :url "https://share.example/s/1" :paused false
                  :participants [{:user "usr_o" :name "Owner" :role :owner} {:user "usr_k" :name "Kim" :role :coach}]}]
    (on-event :session.share/presence presence)
    (on-event :session.share/note {:text "check the edge case" :from {:user "usr_k" :name "Kim" :role :coach}})
    (g/frame!)
    (let [s (screen-text r)]
      (is (str/includes? s "sharing 1 · 1 watching") "the footer counts the share, and who watches it, the owner apart")
      (is (str/includes? s "Kim (coach) joined the share"))
      (is (str/includes? s "✎ note from Kim (coach): check the edge case")))
    (on-event :session.share/presence (assoc presence :paused true))
    (g/frame!)
    (is (str/includes? (screen-text r) "sharing 1 · paused"))
    (testing "an injected turn's overlay has refuse focused: a stray Enter refuses (byj-1wv.11)"
      (let [answer (atom nil)
            t (Thread. #(reset! answer ((:approve r) {:who "Kim (coach)" :text "add a test" :timeout-ms 5000})))]
        (.start t)
        (is (awaited #(= "injected turn" (:label @(:gate (:cells r))))))
        (is (gate-painted? r "Kim (coach) wants to run:"))
        (g/press! 10)
        (is (awaited #(some? @answer)))
        (is (= :rejected @answer))
        (.join t 2000)))
    (testing "and y allows it, as a person tries first"
      (let [answer (atom nil)
            t (Thread. #(reset! answer ((:approve r) {:who "Kim (coach)" :text "add a test" :timeout-ms 5000})))]
        (.start t)
        (is (awaited #(= "injected turn" (:label @(:gate (:cells r))))))
        (is (gate-painted? r "Kim (coach) wants to run:"))
        (g/press! (int \y))
        (is (awaited #(some? @answer)))
        (is (= :approved @answer))
        (is (nil? @(:gate (:cells r))) "the overlay is down")
        (is (= "" @(:draft (:cells r))) "and the y did not land in the input")
        (.join t 2000)))
    (testing "and nobody answering in time is a refusal that takes the overlay down"
      (let [answer (atom nil)
            t (Thread. #(reset! answer ((:approve r) {:who "Kim (coach)" :text "x" :timeout-ms 150})))]
        (.start t)
        (is (awaited #(some? @answer)))
        (is (= :expired @answer))
        (is (nil? @(:gate (:cells r))))
        (.join t 2000)))
    (testing "/share stop takes the footer's mark away"
      (on-event :session.share/control {:action :stop})
      (g/frame!)
      (is (not (str/includes? (screen-text r) "sharing "))))
    (testing "the shares this process started by reference count, with nobody's presence to say so (byj-gly.428)"
      (reset! (:shares (:cells r)) [{:share "shr_2" :ref "claude:dd27" :watching []}
                                    {:share "shr_3" :ref "term:shell" :watching ["Kim (coach)"]}])
      (g/frame!)
      (is (str/includes? (screen-text r) "sharing 2 · 1 watching"))
      (reset! (:shares (:cells r)) [])
      (g/frame!)
      (is (not (str/includes? (screen-text r) "sharing "))))))

(deftest ctrl-c-cancels-the-turn-and-is-never-a-signal
  ;; Raw mode means ctrl-c arrives as a key. It is bound on the container, which
  ;; only ever sees it because the input line below has the focus and declines
  ;; it first (RFC-028 rule 5).
  (let [cancelled (atom 0)
        cancellers (atom {:turn #(swap! cancelled inc)})
        r (mounted {:cols 40 :rows 8 :cancellers cancellers})]
    (g/press! 3 #{"ctrl+q"})
    (is (= 1 @cancelled))
    (g/frame!)
    (is (str/includes? (screen-text r) "cancelling"))
    (testing "with no turn running there is nothing to cancel"
      (reset! cancellers {})
      (g/press! 3 #{"ctrl+q"})
      (is (= 1 @cancelled)))))

(deftest the-end-of-input-is-how-a-session-closes
  (let [r (mounted {:cols 40 :rows 8})
        line (atom :unset)
        kernel (Thread. (fn [] (reset! line ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    ((:end-input! r))
    (is (awaited #(not= :unset @line)))
    (is (nil? @line) "nil is what ctrl-q and a closed loop both come to")
    (.join kernel 2000)))

(deftest a-call-shows-every-argument-named
  ;; `list-tools(false)` hid that :detail true was asked for too
  (let [summary #((resolve 'brainyard.tui/call-summary) %)]
    (is (= "list-tools(grouped=false, detail=true)"
           (summary {:tool-name "list-tools" :tool-args (array-map :grouped false :detail true)})))
    (is (= "read-file(path=a.txt, offset=145, limit=200)"
           (summary {:tool-name "read-file" :tool-args (array-map :path "a.txt" :offset 145 :limit 200)})))
    (is (= "list-tools()" (summary {:tool-name "list-tools" :tool-args {}})))
    (is (= "bash(command=↓)" (summary {:tool-name "bash" :tool-args {:command "echo a\necho b"}}))
        "a value of several lines is under the call (byj-gly.240)")
    (is (= (str "write-file(path=f, content=" (apply str (repeat 200 "x")) " …)")
           (summary {:tool-name "write-file" :tool-args (array-map :path "f" :content (apply str (repeat 500 "x")))}))
        "and a long one cut")
    (is (= "grep(pattern=a, include-exts=[\"clj\" \"edn\"])"
           (summary {:tool-name "grep" :tool-args (array-map :pattern "a" :include-exts ["clj" "edn"])})))))

(deftest the-editor-gets-the-terminal-without-our-modes
  ;; less and vi read our SGR mouse reports as keys, and a paste into them
  ;; carried 200~/201~ (byj-gly.208)
  (let [before @@#'tui/terminal-modes]
    (try
      (with-out-str (#'tui/enable-modes! true))
      (let [[off on] (#'tui/handover)]
        (is (every? #(str/includes? off %) ["?1000l" "?1002l" "?1006l" "?2004l"]) "everything off before")
        (is (every? #(str/includes? on %) ["?1000h" "?2004h"]) "and what was on, back after"))
      (with-out-str (#'tui/enable-modes! false))
      (is (= "\033[?2004h" (second (#'tui/handover))) "a run without the mouse gets back only the paste")
      (finally (reset! @#'tui/terminal-modes before)))))

(deftest a-paste-goes-where-typing-would
  ;; it went to the end of the draft, not the caret; a picker dropped it; and
  ;; in find mode it went into the hidden draft (byj-gly.209, .210)
  (testing "at the caret, mid-line"
    (let [r (mounted {:cols 60 :rows 12}) c (:cells r)]
      (type! "fix the bug")
      (dotimes [_ 8] (g/press! {:type :left}) (g/frame!))
      (g/press! {:type :paste :text " the typo"}) (g/frame!)
      (is (= "fix the typo the bug" @(:draft c)))
      (testing "and it leaves a history walk, as typing does — Down no longer throws it away"
        (reset! (:walk c) {:at 0 :saved ""})
        (g/press! {:type :paste :text "!"}) (g/frame!)
        (is (nil? @(:walk c))))))
  (testing "into an open picker's filter, on one line"
    (let [r (mounted {:cols 90 :rows 30}) c (:cells r)]
      ((:present r) :config {:rows some-rows}) (g/frame!)
      (g/press! {:type :paste :text "recap\n"}) (g/frame!)
      (is (= "recap" (:query @(:picker c))))
      (is (str/includes? (screen-text r) "recap?") "and it filters")))
  (testing "into ctrl-f's query, not the draft waiting behind it"
    (let [r (mounted {:cols 60 :rows 12}) c (:cells r)]
      (type! "half a question")
      (g/press! 6) (g/frame!)
      (g/press! {:type :paste :text "needle"}) (g/frame!)
      (is (= "needle" (:query @(:search c))))
      (g/press! {:type :escape}) (g/frame!)
      (is (= "half a question" @(:draft c)) "and the draft is as it was"))))

(deftest a-registry-search-always-ends
  ;; with the registry off, the answer never reached the picker, whose title
  ;; said "searching the MCP registry…" until it closed; a failed search looked
  ;; like no hits (byj-gly.211)
  (let [r (mounted {:cols 100 :rows 20}) c (:cells r)]
    ((:present r) :mcp {:rows [] :catalog []}) (g/frame!)
    (swap! (:picker c) assoc :searching "fil") (g/frame!)
    (is (str/includes? (screen-text r) "searching the MCP registry for \"fil\""))
    ((:present r) :mcp {:registry [] :registry-error "the MCP registry is off (:mcp-registry? false)" :refresh? true})
    (g/frame!)
    (is (not (str/includes? (screen-text r) "searching the MCP registry")) "the search is over")
    (is (str/includes? (screen-text r) "the MCP registry is off") "and it says why nothing came")))

(deftest the-rest-of-a-cut-paste-is-not-sent
  ;; a bracketed paste that stalled arrives as keys after it; one line and an
  ;; Enter would then be sent as though typed — half a paste (byj-gly.212)
  (let [r (mounted {:cols 60 :rows 8})
        got (atom :unset)
        kernel (Thread. (fn [] (reset! got ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    (g/press! {:type :paste :text "first half " :cut? true})
    (type! "second half")
    (g/press! 10)
    (Thread/sleep (inc tui/paste-gap-ms))
    ((:settle-paste! r))
    (Thread/sleep 50)
    (is (= :unset @got) "nothing was sent")
    (is (str/includes? @(:draft (:cells r)) "first half second half") "it waits in the line")
    (enter!)
    (is (awaited #(not= :unset @got)) "and a press sends it")
    (.join kernel 2000)))

(deftest a-burst-is-judged-by-what-it-added
  ;; the settle step counted every break in the draft, so a message already
  ;; broken with alt+enter never went on a burst's Enter (byj-gly.213)
  (let [r (mounted {:cols 60 :rows 8})
        got (atom :unset)
        kernel (Thread. (fn [] (reset! got ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    (reset! (:draft (:cells r)) "first line\nsecond") (g/frame!)
    (Thread/sleep (inc tui/paste-gap-ms))
    (type! " and more")
    (g/press! 10)
    (Thread/sleep (inc tui/paste-gap-ms))
    ((:settle-paste! r))
    (is (awaited #(not= :unset @got)))
    (is (= "first line\nsecond and more" @got))
    (.join kernel 2000)))

(deftest a-selection-and-a-find-follow-the-rows
  ;; a selection named screen cells after rows streamed in under it, and a
  ;; match near the top scrolled past it (byj-gly.213)
  (let [r (mounted {:cols 40 :rows 10}) c (:cells r)]
    (doseq [i (range 30)] ((:say! r) :info (str "row " i)))
    (g/frame!)
    (testing "a row arriving under a selection clears it"
      (reset! (:selection c) {:from [0 1] :to [8 2]})
      (g/frame!)
      (is (some? @(:selection c)) "rendering alone does not")
      ((:say! r) :info "a new row")
      (g/frame!)
      (is (nil? @(:selection c))))
    (testing "a match near the top is shown without scrolling past it"
      (tui/search-for! c "row 0")
      (is (<= @(:offset c) (tui/max-offset c))))))

(deftest a-resumed-turn-leaves-the-draft-alone
  ;; it was written into the draft and submitted, which threw away what was
  ;; being typed and put the turn in Up-history (byj-gly.213)
  (let [r (mounted {:cols 60 :rows 8}) c (:cells r)
        got (atom :unset)
        kernel (Thread. (fn [] (reset! got ((:read-input r) "› "))))]
    (.start kernel)
    (is (awaited #((:waiting-for-input? r))))
    (type! "half typed")
    ((:send! r) "(the task finished)" false)
    (is (awaited #(not= :unset @got)))
    (is (= "(the task finished)" @got))
    (is (= "half typed" @(:draft c)) "what was being typed is still there")
    (is (not (some #{"(the task finished)"} @(:history c))) "and Up does not walk back to it")
    (.join kernel 2000)))

(deftest a-fault-in-a-handler-is-a-line-not-the-end
  ;; byj-gly.214, now glimmer-tui's own :on-error (v0.2.8, byj-gly.266): run!
  ;; hands it `on-error`, which says each fault once in the session
  (let [r (mounted {})
        errors #(filterv (fn [l] (= :error (:kind l))) @(:lines (:cells r)))
        root (w/node :window {})
        screen (scr/buffer-screen 20 4)]
    (g/attach! root screen (#'tui/on-error (constantly (:cells r))))
    (ui/mount root :window [:vbox {:on-key (fn [_] (throw (ex-info "the key broke" {})))}
                            [:button {:label "b" :autofocus true}]])
    (g/frame!)
    (testing "a timer that throws is said, and the next timer still fires"
      (let [fired (atom false)]
        (g/after! 0 #(throw (ex-info "the timer broke" {})))
        (g/after! 0 #(reset! fired true))
        (Thread/sleep 5)
        (g/pump-timers!)
        (g/pump-timers!)
        (is @fired)
        (is (= 1 (count (errors))))
        (is (str/includes? (:text (first (errors))) "the timer broke"))))
    (testing "a key handler that throws is said, and the same fault only once"
      (g/press! {:type :page-down})
      (g/press! {:type :page-down})
      (is (= 2 (count (errors))))
      (is (str/includes? (:text (last (errors))) "the key broke")))
    (testing "with no handler — no renderer running — a fault is thrown, as before"
      (g/attach! root screen)
      (g/frame!)
      (is (thrown? Throwable (g/press! {:type :page-down}))))))

(deftest a-view-that-throws-is-a-line-not-the-end
  ;; byj-gly.236. Once the loop runs, a cell write does not render: glimmer
  ;; queues the component through the backend's :schedule, and glimmer-tui's
  ;; drain! runs the queue at the top of the next tick — each one guarded since
  ;; v0.2.8, so one throwing render neither ends the session nor drops the ones
  ;; queued behind it (byj-gly.266). Driven through glimmer-tui's real queue.
  (let [r (mounted {})
        errors #(filterv (fn [l] (= :error (:kind l))) @(:lines (:cells r)))
        broken (glimmer.ratom/atom :ok)
        other (glimmer.ratom/atom 0)
        root (w/node :window {})
        screen (scr/buffer-screen 30 4)]
    (try
      (g/attach! root screen (#'tui/on-error (constantly (:cells r))))
      (ui/mount root :window
                [:vbox {}
                 [(fn [] (if (= :boom @broken)
                           (throw (ex-info "the view broke" {}))
                           [:label {:label "fine"}]))]
                 [(fn [] [:label {:label (str "other " @other)}])]])
      (g/frame!)
      (reset! glimmer.backend/loop-running? true)
      (reset! broken :boom)
      (swap! other inc)
      (testing "the throwing render is said, once, and the one behind it still runs"
        (#'g/drain!)
        (is (= 1 (count (errors))))
        (is (str/includes? (:text (first (errors))) "the view broke"))
        (reset! glimmer.backend/loop-running? false)
        (g/frame!)
        (is (some #(str/includes? % "other 1") (scr/lines screen))
            "the component queued after the broken one re-rendered"))
      (finally
        (reset! glimmer.backend/loop-running? false)))))

(deftest a-call-says-its-time-and-size-and-shows-its-script
  ;; brainyard's `→ bash: called → done, 1.2s, 312 chars` and its boxed `Call`
  ;; section, in this renderer's own shape (byj-gly.240)
  (let [r (mounted {:cols 60 :rows 20})
        script "cd src\ngrep -rn foo .\n| head\n| sort\n| uniq"]
    ((:on-event r) :agent.iteration/start {:iteration 1})
    ((:on-event r) :agent.tools/calls {:tool-calls [{:tool-name "bash" :tool-args (array-map :command script :timeout 60000)}]})
    (g/frame!)
    (let [ls (vec (drop-while #(not (str/includes? % "bash(")) (scr/lines (:screen r))))]
      (testing "the script is under the call, its first lines and a fold, while it runs"
        (is (= "⏺ bash(command=↓, timeout=60000)" (str/trimr (ls 0))) "no time yet: it has not finished")
        (is (= ["  ┊  cd src" "     grep -rn foo ." "     | head" "     … +2 lines · click to show them"]
               (mapv str/trimr (subvec ls 1 5))))
        (is (= :green (:fg (style-of r "grep -rn foo"))) "in the code colour")))
    ((:on-event r) :agent.tools/results
     {:results [{:tool-name "bash" :elapsed-ms 1234
                 :tool-result (pr-str (array-map :output "src/a.clj:1:foo\nsrc/b.clj:9:foo" :status "completed" :exit-code 0))}]})
    (g/frame!)
    (let [ls (vec (drop-while #(not (str/includes? % "bash(")) (scr/lines (:screen r))))]
      (testing "done, the line says how long it took and how much came back, dimmed"
        (is (= "⏺ bash(command=↓, timeout=60000) · 1.2s · 79 chars" (str/trimr (ls 0))))
        (is (:dim (style-of r "· 1.2s")))
        (is (:bold (style-of r "bash(command"))))
      (testing "and the result reads name: value, what happened and then how the output starts"
        (is (= ["  ⎿  status: completed · exit-code: 0" "     output:" "       src/a.clj:1:foo"]
               (mapv str/trimr (subvec ls 5 8))))))
    (testing "two text arguments are each named on their first row"
      (let [rows (#'tui/arg-entries {:tool-args (array-map :old "a\nb" :new "c\nd")})]
        (is (= ["old: a" "b" "new: c" "d"] (map :text rows)))))
    (testing "one line folded is one line"
      (is (= "… +1 line · click to show them" (#'tui/fold-text {:more 1})))
      (is (= "… +2 lines · click to show them" (#'tui/fold-text {:more 2}))))
    (testing "a time as brainyard says it, and a size"
      (is (= ["340ms" "1.2s" "2m 5s"] (map tui/call-time-str [340 1234 125000])))
      (is (= ["812 chars" "1.2k chars"] (map tui/chars-str [812 1234]))))))

(deftest the-list-offers-saved-sessions-to-resume
  (let [a (tui/make {})
        _ ((:say! a) :you "fix the drag")
        resumed (atom nil)
        host {:entries (atom [{:id 1 :renderer a :session-id "s-open"}])
              :listing (atom {:at 1 :draft ""}) :tick (atom 0)
              :size (atom {:w 90 :h 20}) :header {:title "brainyard" :detail "mock/mock · /tmp"}
              :saved-rows (fn [_] [{:id [:saved "s-old"] :session-id "s-old" :state :saved
                                     :title "port the renderer" :last "12 turn(s)" :age "3h"}])}
        view (#'tui/sessions-view host {:open! identity :start! identity :resume! #(reset! resumed %)
                                        :end! identity :back! identity :moved! (fn [_ _])})
        root (w/node :window {})
        screen (scr/buffer-screen 90 20)]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (let [t (str/join "\n" (scr/lines screen))]
      (testing "saved sessions come last, under their own heading (byj-gly.178)"
        (is (< (str/index-of t "Waiting for you") (str/index-of t "Saved — enter resumes one")))
        (is (str/includes? t "›↺ port the renderer"))
        (is (str/includes? t "12 turn(s)"))))
    (testing "enter on one resumes it"
      (g/press! 10) (g/frame!)
      (is (= "s-old" @resumed)))))

(deftest the-agent-pages-mouse-marks-and-opens-a-row-and-copies-a-drag
  ;; as on the eval page (pointer-mouse): a click on a row marks it, a click
  ;; on the marked row opens it as Enter does, and a drag selects the list's
  ;; text and the release copies it
  (let [listing (glimmer.ratom/atom {:at 0 :draft ""})
        resumed (atom nil)
        copied (atom nil)
        host {:entries (atom []) :listing listing :tick (atom 0) :size (atom {:w 90 :h 24})
              :header {:title "brainyard" :detail ""}
              :saved-rows (fn [_] [{:id [:saved "s-1"] :session-id "s-1" :state :saved :title "first one" :last "" :age ""}
                                   {:id [:saved "s-2"] :session-id "s-2" :state :saved :title "second one" :last "" :age ""}])}
        view (#'tui/sessions-view host {:open! identity :start! identity :end! identity :back! identity
                                        :resume! #(reset! resumed %)
                                        :moved! (fn [d n] (swap! listing update :at #(max 0 (min (dec n) (+ (or % 0) d)))))
                                        :copy! #(do (reset! copied %) {:ok? true :via :pbcopy :bytes (count %) :verified? true})})
        root (w/node :window {})
        screen (scr/buffer-screen 90 24)
        mouse! (fn [b x y rel] (@@#'tui/mouse-to {:button b :x x :y y :release? rel}) (g/frame!))
        y-of (fn [s] (first (keep-indexed #(when (str/includes? %2 s) %1) (scr/lines screen))))]
    (g/attach! root screen)
    (ui/mount root :window [view])
    (g/frame!)
    (testing "a click on another row marks it"
      (let [y (y-of "second one")]
        (mouse! 0 5 y false) (mouse! 0 5 y true)
        (is (= 1 (:at @listing)))
        (is (nil? @resumed) "and opens nothing")))
    (testing "a click on the marked row opens it, as Enter does"
      (let [y (y-of "second one")]
        (mouse! 0 5 y false) (mouse! 0 5 y true)
        (is (= "s-2" @resumed))))
    (testing "a drag selects the list's text and the release copies it"
      (let [y (y-of "first one")]
        (mouse! 0 0 y false)
        (mouse! 32 40 y false)
        (is (= {:from [0 y] :to [40 y]} (:selection @listing)))
        (mouse! 0 40 y true)
        (is (str/includes? (str @copied) "first one"))
        (is (some? (:flash @listing)) "what the copy did is kept to say")
        (is (str/includes? (str/join "\n" (scr/lines screen)) "copied"))))
    (testing "the next press drops the selection and the word of the copy"
      (mouse! 0 1 1 false)
      (is (not (contains? @listing :selection)))
      (is (not (contains? @listing :flash)))
      (mouse! 0 1 1 true))))

;; --- /memory and the bell (byj-gly.327, .328) -------------------------------------------

(deftest memory-opens-a-picker-that-edits-and-asks-before-it-deletes
  (let [r (mounted {:cols 90 :rows 30})
        c (:cells r)
        text #(screen-text r)
        rows [{:key "auth-jwt" :content "Auth is JWT"}
              {:key "retry-budget" :content "Three retries, then stop.\nWhy: the API bills each."}]]
    (is ((:present r) :memory {:rows rows :query "retr"}) "it says it showed them")
    (g/frame!)
    (testing "the query is the filter; the marked one is shown whole"
      (is (= "retry-budget" (:id (tui/picker-selected @(:picker c)))))
      (is (str/includes? (text) "Why: the API bills each."))
      (is (str/includes? (text) "enter edits · ctrl-x deletes")))
    (testing "Enter edits it, quietly, and the picker stays for what comes back"
      (enter!)
      (is (= ["/memory edit retry-budget"] @(:queue c)))
      (is (contains? @(:unsaid c) "/memory edit retry-budget"))
      (is (some? @(:picker c))))
    (testing "ctrl-x asks; any key but y keeps it"
      (reset! (:queue c) [])
      (g/press! 24) (g/frame!)
      (is (str/includes? (text) "delete retry-budget? y/N"))
      (g/press! (int \n)) (g/frame!)
      (is (= [] @(:queue c)))
      (is (not (str/includes? (text) "y/N")))
      (is (= "retr" (:query @(:picker c))) "and the n was not typed into the filter"))
    (testing "y deletes"
      (g/press! 24) (g/frame!)
      (g/press! (int \y)) (g/frame!)
      (is (= ["/memory delete retry-budget"] @(:queue c))))
    (testing "the rows the session sends back replace the open picker's"
      ((:present r) :memory {:rows [(first rows)] :refresh? true}) (g/frame!)
      (is (= ["auth-jwt"] (mapv :id (:items @(:picker c))))))
    (testing "and a refresh with no picker open shows nothing"
      (g/press! {:type :escape}) (g/frame!)
      (is (not ((:present r) :memory {:rows rows :refresh? true}))))))

(deftest a-permission-ask-rings-the-terminal-once
  (testing "what each :attention-signal writes"
    (is (= "\u0007" (tui/attention-sequence :bell "t" false)))
    (is (= "\u0007" (tui/attention-sequence nil nil false)) "the default")
    (is (nil? (tui/attention-sequence :off "t" false)))
    (is (= "\u001b]9;brainyard: fix the build needs input\u0007" (tui/attention-sequence :osc9 "fix the build" false)))
    (is (= "\u001b]9;brainyard: a session needs input\u0007" (tui/attention-sequence "osc9" "" false)) "a string from the environment")
    (is (= "\u001b]9;brainyard: a b needs input\u0007\u0007" (tui/attention-sequence :both "a\u0007b" false))
        "a control character in the title cannot end the sequence")
    (is (= "\u001bPtmux;\u001b\u001b]9;brainyard: t needs input\u0007\u001b\\" (tui/attention-sequence :osc9 "t" true))
        "wrapped for tmux to pass on"))
  (let [rang (atom [])
        r (mounted {:cols 60 :rows 16 :attention :bell :signal! #(swap! rang conj %)})
        ask! #(future ((:ask r) "Allow write-file?\n[y]es [n]o"))]
    (let [a (ask!)]
      (is (awaited #(some? @(:gate (:cells r)))))
      (is (= ["\u0007"] @rang) "the ask opened: one ring")
      (is (gate-painted? r))
      (is (= ["\u0007"] @rang) "not again while it waits")
      (g/press! 10)
      (is (= "y" (deref a 2000 :timeout))))
    (let [a (ask!)]
      (is (awaited #(= 2 (count @rang))) "the next ask is a change again")
      (is (gate-painted? r))
      (g/press! 10)
      (deref a 2000 nil)))
  (testing "off, nothing"
    (let [rang (atom [])
          r (mounted {:cols 60 :rows 16 :attention :off :signal! #(swap! rang conj %)})
          a (future ((:ask r) "Allow?\n[y]es [n]o"))]
      (is (awaited #(some? @(:gate (:cells r)))))
      (is (gate-painted? r))
      (g/press! 10)
      (deref a 2000 nil)
      (is (= [] @rang)))))

;; --- the frame: a rail each side of the centre (byj-gly.123) ------------------------

(defn- rail-views [acted]
  {:left [{:id "sessions" :title "Sessions"
           :items (fn [] [{:label "this one"} {:label "review" :hint "2m"}])
           :act #(reset! acted (:label %))}]
   :right [{:id "files" :title "Files" :items (fn [] [{:label "src/a.clj" :hint "M"}])}
           {:id "commits" :title "Commits" :items (fn [] [{:label "44c7e98 light tier"}])}]})

(deftest a-hidden-cursor-waits-at-the-caret
  ;; byj-gly.401: hidden while a rail has the keys, the terminal cursor was left
  ;; where painting ended — the rail's separator, the status line — and a
  ;; terminal that draws it anyway (Orca) showed a block there
  (let [calls (atom [])
        r (tui/make {:rails (rail-views (atom nil))})
        root (w/node :window {})
        buf (scr/buffer-screen 100 14)
        screen (assoc buf :cursor! (fn [x y visible?] (swap! calls conj [x y visible?]) ((:cursor! buf) x y visible?)))
        c (:cells r)
        last-call #(do (reset! calls []) (g/frame!) (last @calls))]
    (g/attach! root screen)
    (ui/mount root :window [(:view r)])
    (g/frame!) (tui/measure! r) (g/frame!)
    (type! "hi")
    (let [[x y shown?] (last-call)]
      (is (true? shown?) "typing: the caret shows")
      (testing "ctrl+l: the rail has the keys, and the cursor is hidden AT the caret, not on the rail"
        (g/press! 12) (g/frame!)
        (is (= :left @(:rail-focus c)))
        (let [[hx hy hshown?] (last-call)]
          (is (false? hshown?))
          (is (= hy y) "on the input's row")
          (is (> hx 26) "beside the rail, where typing resumes, not on its separator"))))))

(deftest a-closed-rail-costs-nothing
  (testing "with both rails closed the screen is what it was before rails, frame for frame"
    (let [plain (mounted {:cols 100 :rows 14})
          railed (mounted {:cols 100 :rows 14 :rails (rail-views (atom nil))})]
      (is (= (screen-text plain) (screen-text railed)))
      (is (= {:w 100 :h 14} @(:size (:cells railed))) "the centre is the whole screen"))))

(deftest a-rail-opens-takes-the-keys-and-gives-them-back
  (let [acted (atom nil)
        r (mounted {:cols 100 :rows 14 :rails (rail-views acted)})
        c (:cells r)]
    (testing "ctrl+l opens the left rail with the keys, and the centre gives up its columns"
      (g/press! 12) (g/frame!)
      (is (str/starts-with? (first (scr/lines (:screen r))) "Sessions"))
      (is (= :left @(:rail-focus c)))
      (is (= 73 (:w @(:size c))) "the transcript wraps to what is left"))
    (testing "the arrows move its mark, Enter acts on it, and typing goes nowhere"
      (g/press! 258) (g/frame!)
      (type! "x")
      (is (= "" @(:draft c)) "a rail with the keys swallows a letter")
      (Thread/sleep (inc tui/paste-gap-ms)) (g/press! 10) (g/frame!)
      (is (= "review" @acted)))
    (testing "Esc gives the keys back; the rail stays open"
      (g/press! 27) (g/frame!)
      (is (nil? @(:rail-focus c)))
      (is (get-in @(:rails c) [:left :open?]))
      (type! "hi")
      (is (= "hi" @(:draft c))))
    (testing "ctrl+l again takes the keys; once more, closes it"
      (g/press! 12) (g/frame!)
      (is (= :left @(:rail-focus c)))
      (g/press! 12) (g/frame!)
      (is (not (get-in @(:rails c) [:left :open?])))
      (is (= {:w 100 :h 14} @(:size c)) "the centre has its columns back"))))

(deftest an-arrow-moves-a-rails-inner-edge
  ;; byj-gly.404: → widens the left rail and ← narrows it; the right rail the
  ;; other way, its edge facing the centre on its left (shift+arrows before,
  ;; byj-gly.399, which inside tmux on macOS never arrived)
  (let [r (mounted {:cols 120 :rows 14 :rails (rail-views (atom nil))})
        c (:cells r)
        width #(frame/cols-of @(:rails c) %)
        sep-col (fn [] (str/index-of (first (scr/lines (:screen r))) "│"))]
    (testing "the left rail"
      (g/press! 12) (g/frame!)                        ; ctrl+l: the left rail, with the keys
      (is (= 26 (width :left)))
      (g/press! 261) (g/frame!)
      (is (= 28 (width :left)) "→ widens it")
      (is (= 28 (sep-col)) "its edge on the screen moved with it")
      (is (= 91 (:w @(:size c))) "and the centre gave up the columns")
      (g/press! 260) (g/press! 260) (g/frame!)
      (is (= 24 (width :left)) "← narrows it"))
    (testing "the right rail, the other way, the left keeping its own"
      (g/press! 27) (g/press! 12) (g/press! 12) (g/frame!) ; esc; ctrl+l takes the keys, then closes it
      (g/press! 20) (g/frame!)                        ; ctrl+t: the right rail
      (is (= 34 (width :right)))
      (g/press! 260) (g/frame!)
      (is (= 36 (width :right)) "← widens it")
      (g/press! 261) (g/press! 261) (g/frame!)
      (is (= 32 (width :right)) "→ narrows it")
      (is (= 0 (get-in @(:rails c) [:right :view])) "and switches no view")
      (is (= 24 (width :left))))
    (testing "a rail never squeezes the centre under its minimum, nor goes under its own"
      (dotimes [_ 40] (g/press! 260)) (g/frame!)
      (is (= frame/center-min-cols (:w @(:size c))))
      (dotimes [_ 60] (g/press! 261)) (g/frame!)
      (is (= frame/min-rail-cols (width :right))))))

(deftest a-rails-separator-is-dragged-with-the-mouse
  (let [r (mounted {:cols 120 :rows 14 :rails (rail-views (atom nil))})
        c (:cells r)
        width #(frame/cols-of @(:rails c) %)
        line #(first (scr/lines (:screen r)))
        mouse! (fn [b x y rel] (#'tui/act-on-mouse! {:button b :x x :y y :release? rel}))]
    (testing "the left rail's separator, pressed and dragged, moves its edge"
      (g/press! 12) (g/frame!) (g/press! 27) (g/frame!)  ; ctrl+l, then Esc: open, the keys in the input
      (is (= 26 (str/index-of (line) "│")))
      (mouse! 0 26 3 false)
      (mouse! 32 40 3 false)
      (g/frame!)
      (is (= 40 (width :left)))
      (is (= 40 (str/index-of (line) "│")) "the separator is where it was dragged")
      (is (= 79 (:w @(:size c))) "and the centre gave up the columns")
      (mouse! 0 40 3 true)
      (is (nil? @(:press c)) "the release lets go")
      (is (nil? @(:selection c)) "and a resize selects nothing"))
    (testing "a press off the separator still selects, and resizes nothing"
      (mouse! 0 50 3 false)
      (mouse! 32 55 3 false)
      (is (= 40 (width :left)))
      (is (some? @(:selection c)))
      (reset! (:press c) nil) (reset! (:selection c) nil))
    (testing "the right rail's, the other way, within bounds"
      (g/press! 12) (g/press! 12) (g/frame!)             ; ctrl+l takes the keys, then closes it
      (g/press! 20) (g/frame!)                           ; ctrl+t: the right rail
      (let [sep (str/last-index-of (line) "│")]
        (is (= 85 sep))
        (mouse! 0 sep 3 false)
        (mouse! 32 80 3 false)
        (mouse! 0 80 3 true)
        (g/frame!)
        (is (= 39 (width :right)))
        (mouse! 0 80 3 false)
        (mouse! 32 2 3 false)
        (mouse! 0 2 3 true)
        (g/frame!)
        (is (= frame/center-min-cols (:w @(:size c))) "never past what leaves the centre its 60")))))

(deftest a-click-on-a-rail-marks-and-acts-or-shows-a-view
  ;; byj-gly.449: the rails took only keys
  (let [acted (atom nil)
        r (mounted {:cols 120 :rows 14 :rails (rail-views acted)})
        c (:cells r)
        click! (fn [x y] (#'tui/act-on-mouse! {:button 0 :x x :y y :release? false})
                 (#'tui/act-on-mouse! {:button 0 :x x :y y :release? true})
                 (g/frame!))]
    (testing "a click on an item's row marks it and does what Enter does, the keys left in the input"
      (g/press! 12) (g/frame!) (g/press! 27) (g/frame!)   ; ctrl+l, then Esc: open, the keys in the input
      (click! 2 3)                                         ; the header, the rule, then "this one", "review"
      (is (= "review" @acted))
      (is (= 1 (get-in @(:rails c) [:left :at])))
      (is (nil? @(:rail-focus c)) "the keys stay where they were")
      (is (nil? @(:press c))))
    (testing "the rule and the rows past the items do nothing"
      (reset! acted nil)
      (click! 2 1)
      (click! 2 8)
      (is (nil? @acted))
      (is (= 1 (get-in @(:rails c) [:left :at]))))
    (testing "a click on a view's name in the header shows that view"
      (g/press! 20) (g/frame!)                             ; ctrl+t: the right rail, [Files] Commits
      (is (= 0 (get-in @(:rails c) [:right :view])))
      (click! (+ 86 8) 0)
      (is (= 1 (get-in @(:rails c) [:right :view])))
      (is (str/includes? (first (scr/lines (:screen r))) "[Commits]")))
    (testing "a click in the centre is the centre's"
      (reset! acted nil)
      (click! 30 3)
      (is (nil? @acted))
      (is (= 1 (get-in @(:rails c) [:right :view])))))
  (testing "at 80 columns the rail is drawn over the centre, and a click on it is the rail's"
    (let [acted (atom nil)
          r (mounted {:cols 80 :rows 14 :rails (rail-views acted)})
          c (:cells r)]
      (g/press! 12) (g/frame!)                             ; ctrl+l: drawn over the centre, with the keys
      (#'tui/act-on-mouse! {:button 0 :x 2 :y 2 :release? false})
      (#'tui/act-on-mouse! {:button 0 :x 2 :y 2 :release? true})
      (is (= "this one" @acted))
      (is (= :left @(:rail-focus c)) "it keeps the keys it had")
      (is (not (#'tui/rail-click! c 2 13)) "the footer's row is not the rail's"))))

(deftest the-wheel-over-a-rail-scrolls-the-rail
  ;; byj-gly.449: it scrolled the transcript wherever the pointer was
  (let [r (mounted {:cols 120 :rows 14
                    :rails {:left [{:id "many" :title "Many"
                                    :items (fn [] (mapv #(hash-map :label (str "item " %)) (range 30)))}]}})
        c (:cells r)
        rail #(get-in @(:rails c) [:left %])
        lines #(scr/lines (:screen r))]
    (g/press! 12) (g/frame!) (g/press! 27) (g/frame!)      ; ctrl+l, then Esc: open, the keys in the input
    (testing "a notch down over the rail moves its window and not its mark"
      (is (true? (tui/wheel! 2 5 3)))
      (g/frame!)
      (is (= 3 (rail :top)))
      (is (= 0 (rail :at)))
      (is (str/starts-with? (nth (lines) 2) "item 3"))
      (is (= 0 @(:offset c)) "the transcript did not scroll"))
    (testing "within the items, either way"
      (tui/wheel! 2 5 -9)
      (is (= 0 (rail :top)))
      (tui/wheel! 2 5 99)
      (is (= 18 (rail :top)) "30 items in the 12 rows of a 14-row rail"))
    (testing "the centre's wheel is the transcript's"
      (tui/wheel! 60 5 -3)
      (is (= 18 (rail :top))))
    (testing "a key brings the window back to the mark"
      (g/press! 12) (g/frame!)                             ; ctrl+l: the rail takes the keys
      (g/press! {:type :down}) (g/frame!)
      (is (= 1 (rail :at)))
      (is (= 1 (rail :top))))))

(deftest tab-moves-between-sections-and-a-letter-between-views
  ;; byj-gly.406: Tab stays in the view shown; a view's letter shows it, and
  ;; the view is told it is in sight, so it reads
  (let [shown (atom [])
        r (mounted {:cols 120 :rows 16
                    :rails {:right [{:id "files" :title "Files" :items (fn [] [{:label "a.clj"}])
                                     :on-show #(swap! shown conj :files)}
                                    {:id "source" :title "Source"
                                     :items (fn [] [{:label "▾ Changes" :section :changes} {:label "  a.clj"}
                                                    {:label "▾ Commits" :section :commits} {:label "  44c7e98 x"}])
                                     :on-show #(swap! shown conj :source)}]}})
        c (:cells r)
        at #(select-keys (get @(:rails c) :right) [:view :at])]
    (g/press! 20) (g/frame!)                          ; ctrl+t
    (is (= {:view 0 :at 0} (at)))
    (g/press! 9) (g/frame!)
    (is (= {:view 0 :at 0} (at)) "Tab in Files, which has no sections, stays")
    (g/press! (int \s)) (g/frame!)
    (is (= {:view 1 :at 0} (at)) "s: Source")
    (is (= :source (last @shown)) "which was told it is in sight")
    (is (str/includes? (first (scr/lines (:screen r))) "Files [Source]"))
    (g/press! 9) (g/frame!)
    (is (= {:view 1 :at 2} (at)) "Tab: its next section")
    (g/press! 9) (g/frame!)
    (is (= {:view 1 :at 0} (at)) "and round, staying in Source")
    (g/press! 353) (g/frame!)                         ; shift+Tab
    (is (= {:view 1 :at 2} (at)) "shift+Tab: back")
    (g/press! (int \F)) (g/frame!)
    (is (= {:view 0 :at 0} (at)) "F: Files, either case")
    (is (str/includes? (last (scr/lines (:screen r))) "F/S views") "the footer names the letters")
    (is (= "" @(:draft c)) "nothing typed, nothing completed")))

(deftest a-rails-mark-stays-on-its-session-while-sections-read
  ;; byj-gly.405: the mark's row was taken once, while Project still said
  ;; "reading…"; when it filled in, the mark was rows above the session
  (let [project (atom [{:label "  reading…"}])
        acted (atom nil)
        sessions {:id "sessions" :title "Sessions"
                  :items (fn [] [{:label "  other" :id "s1"} {:label "› this one" :id "s2"}])
                  :mark (fn [items] (first (keep-indexed #(when (= "s2" (:id %2)) %1) items)))
                  :act #(reset! acted (:id %))}
        r (mounted {:cols 120 :rows 20
                    :rails {:left [(frame/stack "repo" [{:id "project" :title "Project" :items (fn [] @project)}
                                                        sessions])]}})
        c (:cells r)
        marked #(get (vec ((:items (first (get-in c [:rail-views :left])))))
                     (:at (frame/resolve-mark (get-in c [:rail-views :left]) (get @(:rails c) :left) 20)))]
    (g/press! 12) (g/frame!)                          ; ctrl+l
    (is (= "› this one" (:label (marked))) "on the session as the rail opens")
    (reset! project [{:label "▸ Instructions" :hint "3"} {:label "▸ Agents" :hint "1"} {:label "▸ Skills" :hint "4"}])
    (g/frame!)
    (is (= "› this one" (:label (marked))) "and still on it once Project has read")
    (is (some #(and (str/includes? % "this one")) (scr/lines (:screen r))))
    (Thread/sleep (inc tui/paste-gap-ms)) (g/press! 10) (g/frame!)
    (is (= "s2" @acted) "Enter acts on the session the mark is drawn on")
    (testing "once a key has moved it, it follows the session no more"
      (g/press! 259) (g/frame!)                       ; ↑
      (let [at (:at (get @(:rails c) :left))]
        (is (= "  other" (:label (marked))))
        (is (not (:follow-mark? (get @(:rails c) :left))))
        (reset! project (conj @project {:label "▸ Tools" :hint "1"}))
        (g/frame!)
        (is (= at (:at (get @(:rails c) :left))) "it keeps the row it was moved to")))))

(deftest a-stacks-heading-folds-its-section
  ;; byj-gly.456: the left rail's headings did nothing, and a long section
  ;; pushed the ones under it out of sight
  (let [acted (atom nil)
        r (mounted {:cols 120 :rows 14
                    :rails {:left [(frame/stack "repo" [{:id "project" :title "Project"
                                                         :items (fn [] [{:label "  AGENTS.md"} {:label "  CLAUDE.md"}])}
                                                        {:id "sessions" :title "Sessions"
                                                         :items (fn [] [{:label "  this one" :id "s1"}])
                                                         :act #(reset! acted (:id %))}]
                                               {:cell tui/cell})]}})
        rail (fn [] (mapv #(str/trim (subs % 0 20)) (take 7 (scr/lines (:screen r)))))
        click! (fn [x y] (#'tui/act-on-mouse! {:button 0 :x x :y y :release? false})
                 (#'tui/act-on-mouse! {:button 0 :x x :y y :release? true})
                 (g/frame!))]
    (g/press! 12) (g/frame!)                          ; ctrl+l
    (is (= ["▾ Project" "AGENTS.md" "CLAUDE.md" "▾ Sessions" "this one"] (subvec (rail) 2 7)) "every section open")
    (is (= 0 (get-in @(:rails (:cells r)) [:left :at])) "the mark on the Project heading")
    (Thread/sleep (inc tui/paste-gap-ms)) (g/press! 10) (g/frame!)
    (is (= ["▸ Project" "▾ Sessions" "this one"] (subvec (rail) 2 5)) "Enter on a heading folds it, and the rail repaints")
    (is (nil? @acted) "and acts on no row")
    (click! 2 2)                                      ; the header, the rule, then Project
    (is (= ["▾ Project" "AGENTS.md" "CLAUDE.md" "▾ Sessions" "this one"] (subvec (rail) 2 7)) "a click on it unfolds it")
    (click! 2 5)
    (is (= ["▾ Project" "AGENTS.md" "CLAUDE.md" "▸ Sessions"] (subvec (rail) 2 6)) "a click folds another")
    (is (nil? @acted))))

(deftest the-rails-come-back-as-a-restart-left-them
  ;; byj-gly.457: put back once the screen's size is known, each open one
  ;; told it is in sight; what is kept is told on every change
  (let [shown (atom [])
        told (atom [])
        views {:left [{:id "sessions" :title "Sessions" :items (fn [] [{:label "one"}])
                       :on-show #(swap! shown conj :sessions)}]
               :right [{:id "files" :title "Files" :items (fn [] [{:label "a"}])}
                       {:id "source" :title "Source" :items (fn [] [{:label "b"}])
                        :on-show #(swap! shown conj :source)}]}
        r (mounted {:cols 140 :rows 14 :rails views
                    :rails-saved {:left {:open? true :view 0 :cols 30} :right {:open? true :view 1 :cols 40}}
                    :on-rails #(swap! told conj %)})
        c (:cells r)]
    (is (= {:open? true :view 0 :cols 30} (select-keys (:left @(:rails c)) [:open? :view :cols])))
    (is (= {:open? true :view 1 :cols 40} (select-keys (:right @(:rails c)) [:open? :view :cols])))
    (is (= #{:sessions :source} (set @shown)) "each rail it opens is told, as a key's would be")
    (is (nil? @(:rail-focus c)) "the keys stay in the input")
    (is (str/starts-with? (first (scr/lines (:screen r))) "Sessions"))
    (is (str/includes? (first (scr/lines (:screen r))) "[Source]"))
    (testing "once: a later resize puts nothing back"
      (swap! (:rails c) assoc-in [:left :open?] false)
      (tui/resize! c {:w 150 :h 14})
      (is (false? (get-in @(:rails c) [:left :open?]))))
    (testing "what is kept is told when it changes, and not when the mark moves"
      (let [n (count @told)]
        (swap! (:rails c) assoc-in [:right :at] 1)
        (is (= n (count @told)))
        (g/press! 12) (g/frame!)                      ; ctrl+l: open again
        (is (= {:open? true :view 0 :cols 30} (:left (last @told))))))))

(deftest a-key-ncurses-has-a-code-for-types-nothing
  ;; byj-gly.400: glimmer decodes ncurses' KEY_* codes it has no name for as
  ;; characters, and the field typed shift+→ (KEY_SRIGHT, 402) as ƒ
  (let [r (mounted {:cols 80 :rows 12})
        c (:cells r)]
    (type! "ab")
    (doseq [code [402 393 337 336]] (g/press! code))  ; shift+→ ← ↑ ↓
    (g/frame!)
    (is (= "ab" @(:draft c)))
    (testing "a character in that range, typed, still types: a terminal sends it as UTF-8"
      (g/press! {:type :char :ch \ƒ :code 0xC6})
      (g/frame!)
      (is (= "abƒ" @(:draft c))))
    (testing "nor into the find field, the other kind of field"
      (g/press! 6) (g/frame!)                         ; ctrl-f
      (type! "ne") (g/press! 402) (type! "w") (g/frame!)
      (is (str/includes? (screen-text r) "find: new"))
      (is (not (str/includes? (screen-text r) "ƒ"))))))

(deftest an-escape-run-glimmer-did-not-know-types-nothing
  ;; byj-gly.403: inside tmux on macOS, shift+→ is ESC [ 1 ; 2 C with no
  ;; terminfo name, and arrived as alt+[ then the characters 1 ; 2 C, typed.
  ;; glimmer-tui v0.2.14 decodes the run as the key it is, with its modifiers,
  ;; and drops one it has no name for (glimmer-tui#22, byj-gly.418): a field
  ;; lets such a key go, and types nothing
  (let [r (mounted {:cols 80 :rows 12})
        c (:cells r)
        keys! (fn [] (doseq [e [{:type :right :mods #{:shift}}    ; ESC [ 1 ; 2 C
                                {:type :left :mods #{:ctrl}}      ; ESC [ 1 ; 5 D
                                {:type :unknown :code 27}]]       ; ESC [ 9 ; 2 ~, unnamed
                       (g/press! e)))]
    (type! "ab")
    (keys!)
    (g/frame!)
    (is (= "ab" @(:draft c)) "shift+→, ctrl+← and an unnamed run type nothing")
    (type! "1;2C")
    (g/frame!)
    (is (= "ab1;2C" @(:draft c)) "the same characters a person types are text")
    (testing "a modified arrow is not the plain one: it does not move the caret"
      (type! "x")
      (g/frame!)
      (is (= "ab1;2Cx" @(:draft c)) "typing resumes where it was"))
    (testing "and in the find field, the other kind of field"
      (g/press! 6) (g/frame!)                         ; ctrl-f
      (type! "ne")
      (keys!)
      (type! "w")
      (g/frame!)
      (is (str/includes? (screen-text r) "find: new")))))

(deftest a-rail-command-never-reaches-the-session
  (let [r (mounted {:cols 140 :rows 14 :rails (rail-views (atom nil))})
        c (:cells r)]
    (type! "/right commits") (enter!)
    (is (get-in @(:rails c) [:right :open?]))
    (is (= 1 (get-in @(:rails c) [:right :view])) "on the view it named")
    (is (str/includes? (screen-text r) "Files [Commits]"))
    (is (nil? @(:rail-focus c)) "the keys stay in the input, where the command was typed")
    (is (empty? @(:queue c)) "not queued for the session")
    (is (not-any? #(= :you (:kind %)) @(:lines c)) "and not said in the transcript")
    (is (str/includes? (screen-text r) "44c7e98 light tier"))
    (type! "/right nope") (enter!)
    (is (str/includes? (screen-text r) "no right view nope — files, commits"))
    (type! "/right") (enter!)
    (is (not (get-in @(:rails c) [:right :open?])) "with no view named it closes")))

(deftest a-rail-with-no-room-draws-over-the-centre
  (let [r (mounted {:cols 70 :rows 12 :rails (rail-views (atom nil))})
        c (:cells r)]
    (g/press! 20) (g/frame!)
    (is (= :right @(:rail-focus c)))
    (is (= 70 (:w @(:size c))) "the centre keeps its width under it")
    (is (str/includes? (first (scr/lines (:screen r))) "[Files] Commits"))
    (testing "Esc gives the keys back, and the rail goes with them: it would cover the input"
      (g/press! 27) (g/frame!)
      (is (nil? @(:rail-focus c)))
      (is (not (get-in @(:rails c) [:right :open?])))
      (is (not (str/includes? (first (scr/lines (:screen r))) "Commits"))))
    (testing "opened by a command, it takes the keys all the same"
      (type! "/right") (enter!)
      (is (= :right @(:rail-focus c))))))

(deftest a-session-with-no-rails-says-so
  (let [r (mounted {:cols 100 :rows 12})
        c (:cells r)]
    (g/press! 12) (g/frame!)
    (is (nil? @(:rail-focus c)))
    (is (= "nothing for the left rail in this session" @(:flash c)))))

(deftest the-left-rail-is-the-windows-sessions
  (let [a (tui/make {}) b (tui/make {}) c (tui/make {})
        entries (atom [{:id 1 :renderer a} {:id 2 :renderer b} {:id 3 :renderer c :ended? true}])
        switched (atom nil)]
    ((:say! a) :you "fix the drag")
    ((:say! b) :you "list tasks")
    (swap! (:status (:cells b)) assoc :running? true)
    (let [view (tui/sessions-rail entries 1 #(reset! switched %))
          r (mounted {:cols 100 :rows 12 :rails {:left [view]}})
          lines #(vec (scr/lines (:screen r)))]
      (testing "one row a session, grouped as the list groups them, this one marked and the mark on it"
        (is (= ["  ● list tasks" "› ○ fix the drag" "  · new session"] (mapv :label ((:items view)))))
        (g/press! 12) (g/frame!)
        (is (str/starts-with? ((lines) 0) "Sessions"))
        (is (str/starts-with? ((lines) 3) "› ○ fix the drag"))
        (is (= 1 (get-in @(:rails (:cells r)) [:left :at])) "the mark starts on the session whose rail it is"))
      (testing "Enter on another switches to it; on this one, nothing"
        (Thread/sleep (inc tui/paste-gap-ms)) (g/press! 10) (g/frame!)
        (is (nil? @switched))
        (g/press! 259) (g/frame!)
        (Thread/sleep (inc tui/paste-gap-ms)) (g/press! 10) (g/frame!)
        (is (= 2 @switched)))
      (testing "the session switched to opens its own rail, its mark on itself"
        (let [cb (:cells b)
              b-view (tui/sessions-rail entries 2 identity)
              cb (assoc cb :rail-views {:left [b-view] :right []})]
          (tui/resize! cb {:w 120 :h 24})
          (tui/open-rail! cb :left)
          (is (get-in @(:rails cb) [:left :open?]))
          (is (= 0 (get-in @(:rails cb) [:left :at])) "list tasks is first, and is b")
          (is (nil? @(:rail-focus cb)) "beside the centre, the keys stay in its input")
          (swap! (:rails cb) assoc-in [:left :open?] false)
          (tui/resize! cb {:w 80 :h 24})
          (tui/open-rail! cb :left)
          (is (= :left @(:rail-focus cb)) "over it, at 80 columns, the rail has them"))))))

(deftest a-long-footer-never-pushes-a-rail-off-the-screen
  ;; the mode, its hint and the agents hint beside a rail's keys came to 114
  ;; columns, and a box is never narrower than its widest row: the right rail
  ;; was left five columns (byj-gly.380, found live)
  (let [r (mounted {:cols 120 :rows 12 :permission-mode-box (atom :auto) :on-agents identity
                    :rails (rail-views (atom nil))})
        lines #(vec (scr/lines (:screen r)))]
    (g/press! 20) (g/frame!)
    (is (str/includes? ((lines) 0) "│[Files] Commits") "the rail has its 34 columns")
    (is (every? #(<= (text/width (str/trimr %)) 120) (lines)))
    (is (str/includes? (last (lines)) "↑↓ · enter · tab sections · F/C views · ←→ width · esc returns") "the rail's keys stay")
    (is (str/includes? (last (lines)) "auto mode on") "the mode stays while there is room")
    (is (not (str/includes? (last (lines)) "shift+tab")) "its hint gives way first")))

(deftest a-shown-diff-is-a-folded-block-the-model-never-sees
  (let [r (mounted {:cols 100 :rows 30})
        c (:cells r)
        diff (str/join "\n" (concat ["diff --git a/a.txt b/a.txt" "@@ -1 +1,2 @@" " one" "+two" "-three"]
                                    (map #(str "+line " %) (range 20))))]
    (tui/show-block! c "git diff — a.txt" diff)
    (g/frame!)
    (let [t (screen-text r)]
      (is (str/includes? t "git diff — a.txt"))
      (is (str/includes? t "+two"))
      (is (str/includes? t "+13 lines · click to show them") "the rest folded, as a long result is"))
    (is (= {:fg :green} (select-keys (style-of r "+two") [:fg])))
    (is (= {:fg :red} (select-keys (style-of r "-three") [:fg])))
    (is (empty? @(:queue c)) "nothing for the session")))

(deftest a-footer-never-pushes-the-rail-by-its-spacing
  ;; byj-gly.412, seen live: with four views and no "@ mentions", the mode
  ;; just fit by a sum that left out the footer's spacing, and the rail was
  ;; drawn 2 columns off the screen
  (let [views (fn [t] {:id t :title t :items (fn [] [{:label "x"}])})
        r (mounted {:cols 120 :rows 12 :permission-mode-box (atom :auto)
                    :rails {:right [(views "File") (views "Source") (views "Activity") (views "Terminal")]}})
        lines #(vec (scr/lines (:screen r)))]
    (g/press! 20) (g/frame!)                          ; ctrl+t
    (is (= 85 (str/index-of ((lines) 0) "│")) "the separator where the rail's 34 columns put it")
    (is (every? #(<= (text/width (str/trimr %)) 120) (lines)))))

(deftest at-in-a-rail-mentions-the-marked-row
  (let [views {:right [{:id "files" :title "Files"
                        :items (fn [] [{:label "  a.clj" :path "src/a.clj"}])
                        :put (fn [{:keys [path]}] (str "@" path))}]}
        r (mounted {:cols 140 :rows 12 :rails views})
        c (:cells r)]
    (type! "look at")
    (g/press! 20) (g/frame!)
    (is (str/includes? (last (scr/lines (:screen r))) "@ mentions"))
    (g/press! (int \@)) (g/frame!)
    (is (= "look at @src/a.clj " @(:draft c)))
    (is (nil? @(:rail-focus c)) "and the keys are back in the input")))

(deftest ctrl-g-moves-the-calls-a-turn-waits-on-to-the-background
  ;; byj-gly.391: the renderer calls every release in :backgrounders, which
  ;; tasks/detachable fills while a turn waits on a tool call
  (let [r (mounted {:cols 100 :rows 12})
        c (:cells r)
        released (atom [])
        old (- (System/currentTimeMillis) 5000)]
    (testing "with nothing waiting, ctrl+g does nothing"
      (g/press! 7) (g/frame!)
      (is (nil? @(:flash c))))
    (testing "the busy line offers it once a call has waited a while"
      (swap! (:status c) assoc :running? true :since old)
      (swap! (:backgrounders c) assoc "bg1" {:tool "bash" :since (System/currentTimeMillis) :release #(swap! released conj :bash)})
      (swap! (:tick c) inc) (g/frame!)
      (is (not (str/includes? (screen-text r) "ctrl+g")) "not for a call that just started")
      (swap! (:backgrounders c) assoc "bg2" {:tool "agent$explore" :since old :release #(swap! released conj :explore)})
      (swap! (:tick c) inc) (g/frame!)
      (is (str/includes? (screen-text r) "ctrl+g runs it in the background")))
    (testing "ctrl+g releases every call it waits on, and says what moved"
      (g/press! 7) (g/frame!)
      (is (= #{:bash :explore} (set @released)))
      (is (= "moved bash, agent$explore to the background · /task lists it" @(:flash c))))))
