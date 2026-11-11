(ns brainyard.dev.tui-spike
  "Measurements behind RFC-028: can glimmer + glimmer-tui supply the six seams
  RFC-027 hands a UI, and what does a terminal renderer written that way cost?

      jolt -A:dev -m brainyard.dev.tui-spike

  RFC-027 decided WHERE a UI runs — in the kernel's process, reached through
  `session/run`'s six function arguments. It did not decide what the renderer is
  written WITH, and brainyard's answer (raw ANSI: alt-screen, a DECSTBM scroll
  region, sticky live blocks, hand-decoded input) is not the only one available
  on jolt. This spike asks the questions that decide it:

  1. Does the toolkit load and register under this jolt at all? It binds
     ncursesw through jolt.ffi, and jolt before 0.7.24 exported its own ncurses
     symbols, which broke `initscr` on terminals that work everywhere else.
  2. Can the six seams be supplied by a component tree? Five are feeds and can
     be anything; `:permission-fn` is a CALL — a request goes out, the calling
     fiber blocks, an answer comes back, and there is no timeout. A UI that
     cannot answer it is not a UI for this kernel, so it is driven here for
     real, from a thread that blocks the way a turn does.
  3. What does a streamed reply cost? `:on-chunk` fires per token. If each chunk
     repaints, a fast stream is a fast repaint loop, so the question is how many
     RENDERS N chunk writes produce.
  4. What does a frame cost as a transcript grows? A session's scrollback is one
     widget per line and `:scroll` does not virtualise, so this is the number
     that decides whether the transcript must be windowed.
  5. Which brainyard behaviours does the widget set NOT give us for free?
     Wrapping and pasting are measured rather than assumed.

  Prints raw numbers rather than verdicts, so a conclusion can be re-derived
  rather than trusted. Headless throughout: no tty, no terminal, no display —
  everything paints onto an in-memory grid, which is itself one of the findings.

  Note on threads. With no event loop running, glimmer renders synchronously on
  whatever thread wrote the cell (`glimmer.core/on-gui`), which is what makes a
  headless drive deterministic. Under the real loop, `loop-running?` is set and
  the same write is deferred onto the loop thread through the backend's
  `:schedule`. Probe 3 installs its own `:schedule` to measure that path without
  a terminal."
  (:require [clojure.string :as str]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer-tui.core :as tui]
            [glimmer-tui.curses :as curses]
            [glimmer-tui.keys :as keys]
            [glimmer-tui.layout :as layout]
            [glimmer-tui.render :as render]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.widget :as w]))

(defn- ms [t0] (/ (double (- (System/nanoTime) t0)) 1e6))

(defn- fmt [x] (format "%.2f" (double x)))

(defn- median [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

(defn- show-screen [screen]
  (doseq [l (scr/lines screen)]
    (println (str "      |" l "|"))))

(defn- wait-for
  "Block the driving thread until `pred` holds, up to 2 s. The kernel side of
  this spike runs on its own thread and blocks like a turn does, so the driver
  has to wait for it to arrive rather than assume it has."
  [what pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline)
        (do (println (str "   TIMED OUT waiting for " what)) false)
        :else (do (Thread/sleep 2) (recur))))))

;; --- 1. does it load here ---------------------------------------------------------

(defn probe-load
  "Requiring glimmer-tui.core binds ncursesw and registers the backend. Both can
  fail on a machine rather than in the code, so both are reported before
  anything else runs."
  []
  (println "\n1. LOAD — glimmer-tui under this jolt")
  (println (format "   %-34s %s" "jolt version" (or (System/getProperty "jolt.version")
                                                    (System/getProperty "java.version")
                                                    "unknown")))
  (println (format "   %-34s %s" "registered backend" (:name (b/current))))
  (println (format "   %-34s %s" "backend installed?" (b/installed?)))
  (println (format "   %-34s %s" "usable-terminal? (no tty here)" (tui/usable-terminal?)))
  (println "   usable-terminal? is the check that must gate `ui/run`: initscr")
  (println "   reports failure by calling exit(), so a UI that starts on a")
  (println "   headless box does not throw, it takes the process with it."))

;; --- 2. the six seams -------------------------------------------------------------

(def ^:private transcript (r/atom []))
(def ^:private prompt     (r/atom nil))   ; :read-input is waiting on this
(def ^:private draft      (r/atom ""))    ; what the entry holds
(def ^:private ask        (r/atom nil))   ; :permission-fn is waiting on this

(def ^:private pending (clojure.core/atom {}))  ; seam -> promise the UI delivers

(defn- session-ui
  "The shape an agent TUI actually has: a scrolling transcript, an input line,
  and a modal gate that appears in front of both. Nothing here knows what a
  turn is — it renders cells and calls back."
  []
  [:vbox {}
   [:scroll {:vexpand true}
    (into [:vbox {}] (for [line @transcript] [:label {:label line}]))]
   [:entry {:placeholder (or @prompt "")
            :autofocus true
            :on-change #(reset! draft %)
            :on-activate #(when-let [p (:input @pending)]
                            (swap! pending dissoc :input)
                            (reset! prompt nil)
                            (deliver p @draft))}]
   (when-let [req @ask]
     [:overlay {:anchor :center :modal true}
      [:frame {:label "permission" :border :double :padding 1}
       [:vbox {:spacing 1}
        [:label {:label (str "run " (:tool req) "?")}]
        [:label {:label (str "  " (:command req)) :dim true}]
        [:hbox {:spacing 2 :halign :center}
         [:button {:label "allow" :autofocus true
                   :on-click #(let [p (:ask @pending)]
                                (swap! pending dissoc :ask)
                                (reset! ask nil)
                                (deliver p {:allowed true}))}]
         [:button {:label "deny"
                   :on-click #(let [p (:ask @pending)]
                                (swap! pending dissoc :ask)
                                (reset! ask nil)
                                (deliver p {:denied true :reason "user declined"}))}]]]]])])

(defn- seam-fns
  "The six seams, as a UI would supply them to `session/run`. Three write cells,
  two block on a promise the UI delivers, and one is an atom the UI reads."
  []
  {:emit       (fn [_kind text] (swap! transcript conj text))
   :on-event   (fn [event _data] (swap! transcript conj (str "· " (name event))))
   :on-chunk   (fn [_field text]
                 (swap! transcript (fn [t] (conj (pop (vec t)) (str (peek (vec t)) text)))))
   :read-input (fn [p]
                 (let [prom (promise)]
                   (swap! pending assoc :input prom)
                   (reset! prompt p)
                   @prom))
   :permission-fn (fn [req]
                    (let [prom (promise)]
                      (swap! pending assoc :ask prom)
                      (reset! ask req)
                      @prom))
   :cancellers (clojure.core/atom {})})

(defn probe-seams
  "Drive a turn's worth of the boundary headlessly: the kernel runs on its own
  thread and BLOCKS on the two seams that are calls, while the driver presses
  keys into the UI. If this deadlocks, the toolkit cannot host this kernel."
  []
  (println "\n2. THE SIX SEAMS — driven headlessly, kernel on its own thread")
  (reset! transcript ["brainyard-jolt session — /help for commands"])
  (reset! prompt nil) (reset! draft "") (reset! ask nil) (reset! pending {})
  (let [{:keys [emit on-event on-chunk read-input permission-fn cancellers]} (seam-fns)
        root (w/node :window {})
        screen (scr/buffer-screen 46 14)
        got (clojure.core/atom {})
        t0 (System/nanoTime)]
    (tui/attach! root screen)
    (ui/mount root :window [session-ui])
    (tui/frame!)
    (let [kernel
          (Thread.
           (fn []
             ;; a turn, as the kernel runs it
             (let [line (read-input "you> ")]                     ; UI -> kernel
               (swap! got assoc :line line)
               (emit :user (str "you> " line))                    ; kernel -> UI
               (on-event :turn/start nil)                         ; kernel -> UI
               (emit :assistant "by> ")
               (doseq [chunk ["reading " "deps.edn" " …"]]        ; kernel -> UI
                 (on-chunk :reply chunk))
               (let [answer (permission-fn {:tool "bash"          ; kernel -> UI -> kernel
                                            :command "cat deps.edn"})]
                 (swap! got assoc :answer answer)
                 (emit :assistant (str "by> permission: " (pr-str answer))))
               (swap! got assoc :cancellable? (some? @cancellers)))))]
      (.start kernel)
      ;; the driver is the terminal: it types, and it answers the gate
      (wait-for ":read-input to ask" #(some? (:input @pending)))
      (tui/frame!)
      (println "   after :read-input asked — the entry is waiting:")
      (show-screen screen)
      (doseq [c "read deps.edn"] (tui/press! (int c)))
      (tui/press! 10)                                             ; Enter
      (wait-for ":permission-fn to ask" #(some? (:ask @pending)))
      (tui/frame!)
      (println "   :permission-fn blocked the turn, the gate is up:")
      (show-screen screen)
      (tui/press! 10)                                             ; Enter on "allow"
      (.join kernel 2000)
      (tui/frame!)
      (println "   answered, the turn resumed:")
      (show-screen screen)
      (println (format "   %-34s %s" "line the kernel read" (pr-str (:line @got))))
      (println (format "   %-34s %s" "answer the gate returned" (pr-str (:answer @got))))
      (println (format "   %-34s %s ms" "whole round trip" (fmt (ms t0))))
      (println (format "   %-34s %s" "kernel thread finished?" (not (.isAlive kernel)))))))

;; --- 3. what a streamed reply costs -----------------------------------------------

(defn probe-stream-coalescing
  "`:on-chunk` fires per token. Under the real loop a cell write does not paint,
  it queues a re-render through the backend's :schedule — so the question is how
  many renders N chunks produce. Measured by installing a :schedule that counts
  instead of running, which is the loop's behaviour without a loop."
  []
  (println "\n3. STREAMING — renders per chunk, with the loop 'running'")
  (let [n 500
        renders (clojure.core/atom 0)
        queued (clojure.core/atom [])
        reply (r/atom "")
        root (w/node :window {})
        screen (scr/buffer-screen 40 6)
        component (fn [] (swap! renders inc) [:label {:label @reply}])]
    (tui/attach! root screen)
    (ui/mount root :window [component])
    (println (format "   %-34s %s" "renders after mount" @renders))
    ;; stand in for the event loop: defer work instead of running it
    (b/register! (assoc tui/backend :schedule (fn [work] (swap! queued conj work))))
    (reset! b/loop-running? true)
    (try
      (let [t0 (System/nanoTime)]
        (dotimes [i n] (swap! reply str (str i " ")))
        (let [write-ms (ms t0)
              q (count @queued)]
          (doseq [f @queued] (f))
          (println (format "   %-34s %s" (str "cell writes (" n " chunks)") n))
          (println (format "   %-34s %s" "re-renders queued" q))
          (println (format "   %-34s %s" "renders after draining" @renders))
          (println (format "   %-34s %s ms" "cost of the writes" (fmt write-ms)))
          (println "   A component re-renders once per drain, not once per chunk:")
          (println "   the watcher holds a pending flag until its render runs")
          (println "   (glimmer.core/make-rerender-watcher), so a stream repaints")
          (println "   at the loop's tick rate rather than the model's token rate.")))
      (finally
        (reset! b/loop-running? false)
        (b/register! tui/backend)))))

;; --- 4. what a frame costs as the transcript grows --------------------------------

(defn probe-frame-cost
  "One widget per transcript line, and :scroll does not virtualise: it gives its
  child the full height and shows a window onto it. A frame is therefore two
  different costs and they must be reported apart — measuring only the total
  hides which one the application can do something about:

    layout  walks every mounted node, so it is O(transcript)
    render  paints, and a node clipped out of the viewport is cheap, so it is
            O(visible)

  That split is the whole basis for windowing the transcript: the paint is a
  fixed price for the screen you have, and the growth is all in the lines you
  are not looking at."
  []
  (println "\n4. FRAME COST — one :label per transcript line, inside a :scroll (80x40)")
  (println (format "   %-10s %-12s %-12s %-12s %-12s"
                   "lines" "mount ms" "layout ms" "render ms" "frame ms"))
  (doseq [n [40 100 500 2000 5000]]
    (let [lines (mapv #(str "by> line " % " of the transcript") (range n))
          root (w/node :window {})
          screen (scr/buffer-screen 80 40)
          component (fn [] [:scroll {:vexpand true}
                            (into [:vbox {}] (for [l lines] [:label {:label l}]))])]
      (tui/attach! root screen)
      (let [t0 (System/nanoTime)
            inst (ui/mount root :window [component])
            mount-ms (ms t0)
            _ (tui/frame!)
            snap (w/snapshot root)
            layout-ms (median (repeatedly 5 #(let [t (System/nanoTime)]
                                               (layout/layout snap 80 40) (ms t))))
            tree (layout/layout snap 80 40)
            render-ms (median (repeatedly 5 #(let [t (System/nanoTime)]
                                               (render/render! screen tree {:focus-id nil})
                                               (ms t))))
            frame-ms (median (repeatedly 5 #(let [t (System/nanoTime)] (tui/frame!) (ms t))))]
        (println (format "   %-10s %-12s %-12s %-12s %-12s"
                         n (fmt mount-ms) (fmt layout-ms) (fmt render-ms) (fmt frame-ms)))
        (ui/unmount! inst root :window))))
  (println "   The paint is flat and the layout is linear, so a transcript kept")
  (println "   near the viewport costs what a short one does. The screen above")
  (println "   is an in-memory grid; probe 6 is the same measurement on a real")
  (println "   terminal, which is the number that matters."))

;; --- 5. what the widget set does not do -------------------------------------------

(defn probe-wrapping
  "brainyard's renderer wraps model output to the terminal width. A :label breaks
  on newlines only, so the wrap is the application's job — this shows what
  happens to a line longer than the screen."
  []
  (println "\n5a. WRAPPING — a 120-column answer on a 40-column screen")
  (let [long-line (str/join " " (repeat 20 "answer"))
        root (w/node :window {})
        screen (scr/buffer-screen 40 5)]
    (tui/attach! root screen)
    (ui/mount root :window [(fn [] [:label {:label long-line}])])
    (tui/frame!)
    (println (format "   %-34s %s" "label length" (count long-line)))
    (println (format "   %-34s %s" "rows the label occupied"
                     (count (remove str/blank? (scr/lines screen)))))
    (show-screen screen)))

(defn probe-paste
  "A paste arrives as its characters one at a time — bracketed paste is not
  decoded — so a newline inside pasted text is Enter. For a prompt box people
  paste logs and code into, that is the difference between one message and two."
  []
  (println "\n5b. PASTE — two lines pasted into the input")
  (let [activations (clojure.core/atom 0)
        text (r/atom "")
        root (w/node :window {})
        screen (scr/buffer-screen 40 4)]
    (tui/attach! root screen)
    (ui/mount root :window
              [(fn [] [:entry {:autofocus true
                               :on-change #(reset! text %)
                               :on-activate #(swap! activations inc)}])])
    (tui/frame!)
    (doseq [c "alpha\nbeta"] (tui/press! (int c)))
    (tui/frame!)
    (println (format "   %-34s %s" "pasted" (pr-str "alpha\nbeta")))
    (println (format "   %-34s %s" "on-activate fired" @activations))
    (println (format "   %-34s %s" "entry holds" (pr-str @text)))))

(defn probe-ctrl-c
  "The loop puts the terminal in raw mode, so SIGINT never happens: ctrl-c is a
  key code like any other. That is what lets ctrl-c cancel a TURN while ctrl-q
  quits, with no signal handler in the way.

  Measured twice, because the difference is a trap. A key is offered to the
  FOCUSED widget and then to its ancestors — so with nothing focusable on
  screen, an application binding on a container is never reached. A session UI
  always has its input line, but a modal that is only labels and no buttons
  would go deaf."
  []
  (println "\n5c. CTRL-C — a key, not a signal (raw mode)")
  (println (format "   %-34s %s" "(keys/decode 3)" (pr-str (keys/decode 3))))
  (doseq [[what focusable?] [["nothing focusable on screen" false]
                             ["an input line focused" true]]]
    (let [seen (clojure.core/atom [])
          root (w/node :window {})
          screen (scr/buffer-screen 40 4)]
      (tui/attach! root screen)
      (ui/mount root :window
                [(fn [] (cond-> [:vbox {:on-key (fn [e]
                                                  (when (keys/match? e "ctrl+c")
                                                    (swap! seen conj :cancel-turn)
                                                    true))}
                                 [:label {:label "running…"}]]
                          focusable? (conj [:entry {:autofocus true}])))])
      (tui/frame!)
      (tui/press! 3 #{"ctrl+q"})
      (println (format "   %-34s %s" what (pr-str @seen)))))
  (println "   With :quit-keys #{\"ctrl+q\"} the loop never claims ctrl-c, and a")
  (println "   key the focused widget declines bubbles to the container — so")
  (println "   `session/cancel-turn!` binds there, replacing with-sigint."))

;; --- 6. the same frame, on a real terminal ----------------------------------------

(defn probe-terminal-frame-cost
  "Everything above paints onto an in-memory grid. This is the same measurement
  against ncurses, and it only runs from a real terminal — which is the point:
  the headless path is what a test uses, and it is not what a user gets.

  Reported apart because the answer is not 'the same, but slower'. The FFI calls
  are not where the time goes."
  []
  (println "\n6. FRAME COST ON A REAL TERMINAL — ncurses, one screenful of labels")
  (if-not (tui/usable-terminal?)
    (println "   skipped: no tty here. Run this spike from a terminal for the numbers.")
    (let [out (clojure.core/atom [])]
      ;; nothing may print while the alt-screen is up: it would land in the frame
      (let [win (curses/start!)]
        (try
          (let [screen (curses/ncurses-screen win)
                [cols rows] (scr/size screen)
                root (w/node :window {})]
            (tui/attach! root screen)
            (ui/mount root :window
                      [(fn [] (into [:vbox {}]
                                    (for [i (range (dec rows))]
                                      [:label {:label (str "by> line " i " of the transcript")}])))])
            (tui/frame!)
            (let [snap (w/snapshot root)
                  tree (layout/layout snap cols rows)]
              (swap! out conj (format "   %-34s %sx%s, %s labels" "terminal" cols rows (dec rows)))
              (swap! out conj (format "   %-34s %s ms" "layout/layout"
                                      (fmt (median (repeatedly 10 #(let [t (System/nanoTime)]
                                                                     (layout/layout snap cols rows) (ms t)))))))
              (swap! out conj (format "   %-34s %s ms" "render/render!"
                                      (fmt (median (repeatedly 10 #(let [t (System/nanoTime)]
                                                                     (render/render! screen tree {:focus-id nil}) (ms t)))))))
              (swap! out conj (format "   %-34s %s ms" "scr/put! x rows (the FFI)"
                                      (fmt (median (repeatedly 10 #(let [t (System/nanoTime)]
                                                                     (dotimes [y (dec rows)]
                                                                       (scr/put! screen 0 y "by> line of text" nil))
                                                                     (ms t)))))))
              (swap! out conj (format "   %-34s %s ms" "scr/present! (doupdate)"
                                      (fmt (median (repeatedly 10 #(let [t (System/nanoTime)]
                                                                     (scr/present! screen) (ms t)))))))
              (swap! out conj (format "   %-34s %s ms" "tui/frame!"
                                      (fmt (median (repeatedly 10 #(let [t (System/nanoTime)]
                                                                     (tui/frame!) (ms t)))))))))
          (finally (curses/stop!))))
      (doseq [l @out] (println l))
      (println "   A repaint is the toolkit's own walk over the visible widgets,")
      (println "   not the terminal: put! and doupdate together are a rounding")
      (println "   error next to render!. It is a fixed price per frame, so the")
      (println "   loop's :tick-ms is what decides how much of a core a stream")
      (println "   costs — not how fast the model produces tokens."))))

(defn -main [& _]
  (println "glimmer + glimmer-tui as the TUI toolkit — measurements for RFC-028")
  (probe-load)
  (probe-seams)
  (probe-stream-coalescing)
  (probe-frame-cost)
  (probe-wrapping)
  (probe-paste)
  (probe-ctrl-c)
  (probe-terminal-frame-cost)
  (println "\ndone.")
  (System/exit 0))
