;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tui
  "The full-screen renderer (RFC-028): a component tree over glimmer-tui
  supplying the six seams RFC-027 hands a UI, and knowing nothing else about
  the kernel. brainyard's `bases/agent-tui` in Mode A — one process, one
  renderer, no daemon, and no tmux side channel (`byj-gly.98`).

  Mechanism only (RFC-001). What the agent DOES is a tree and what the model
  reads is a prompt resource; what this decides is where a line goes on a
  screen.

  `make` builds a renderer — the cells it draws from, the component to mount,
  and the seams — and holds nothing global, so a test makes its own and drives
  it onto an in-memory screen with no tty. `run!` is the edge that hands the
  terminal over: glimmer-tui's loop blocks the thread it is given, and two of
  the seams block the thread the kernel runs on, so they cannot be one thread.

  RFC-028's rules are each a line here you could otherwise mistake for a style
  choice:

  1. The kernel writes cells from its own thread and glimmer defers the
     re-render onto the loop thread. Nothing in a seam paints.
  2. No handler blocks. `read-input` and the permission ask park a promise and
     wait on it from the KERNEL's thread; the handler only delivers it.
  3. Only what fits on the screen is mounted, because layout walks every
     mounted node while painting is bounded by the viewport.
  4. `run!` starts only on a usable terminal — `initscr` reports failure by
     calling `exit()`.
  5. ctrl-c is a key here, not a signal: it cancels the turn, and ctrl-q quits."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [brainyard.claude-usage :as claude-usage]
            [brainyard.codex-usage :as codex-usage]
            [brainyard.clipboard :as clipboard]
            [brainyard.events :as events]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.links :as links]
            [brainyard.toolshelf :as toolshelf]
            [brainyard.permission :as permission]
            [brainyard.share :as share]
            [glimmer-tui.core :as tui]
            [glimmer-tui.curses :as curses]
            [glimmer-tui.ffi :as ffi]
            [glimmer-tui.keys :as k]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.text :as text]
            [glimmer-tui.widget :as w]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [jolt.ffi :as jffi]
            [brainyard.children :as children]
            [brainyard.effect :as fx]
            [brainyard.trajectory :as trajectory]
            [brainyard.tui-frame :as frame]))

;; --- what requiring glimmer costs, and what it must not ------------------------------
;;
;; glimmer.ratom rebinds clojure.core/deref, reset! and swap! process-wide
;; (alter-var-root) so that `@` tracks a reactive cell while a component
;; renders, delegating everything that is not a cell to the host fn. Its deref
;; once had ONE arity, and `(deref ref timeout-ms timeout-val)` is how every
;; blocking wait in this repository gives up: requiring the renderer broke 85
;; assertions in namespaces that never mention it (provenance P-18), and this
;; namespace put the arity back. glimmer v0.1.3 forwards both arities itself
;; (reported from here as `byj-mal`), so the restoration is gone (byj-gly.271);
;; the whole suite, run in one image, is still what would see it come back.

;; --- the wheel -----------------------------------------------------------------------
;;
;; glimmer-tui's `scroll!` moves whatever `:scroll` container is under the
;; pointer, and does nothing when there is none. The transcript windows itself —
;; only a screenful of rows is ever mounted (RFC-028 rule 3) — so it is not one,
;; and the wheel reached nothing. `wheel!` falls through: a notch no container
;; took goes to whoever the renderer says should have it. It used to be a
;; rebinding of `scroll!`; the only caller is this namespace's own reader, so it
;; is a function of its own now (byj-gly.271).

(defonce ^:private wheel-to
  ;; (fn [x y lines]) or nil — set by the mounted renderer
  (atom nil))

(defn wheel!
  "A wheel notch of `lines` at cell (x, y): the `:scroll` container under it,
  else the renderer on screen. True when something moved."
  [x y lines]
  (or (tui/scroll! x y lines)
      (boolean (when-let [f @wheel-to] (f x y lines)))))

;; --- a fault in a handler is a line, not the end ---------------------------------
;;
;; One exception from an :on-key body, a timer thunk, a queued re-render or a
;; paint used to end `ui/run`, and `run!`'s finally then cancelled the turn and
;; ended the input (byj-gly.214, byj-gly.236). This namespace contained them by
;; rebinding five glimmer-tui vars; glimmer-tui v0.2.8 guards each phase itself
;; and hands the fault to `run`'s `:on-error` (jolt-lang/glimmer-tui#12, reported
;; from here), each queued re-render and each due timer on its own, a failed
;; repaint still marked painted. So `run!` passes `on-error` and the rebindings
;; are gone (byj-gly.266). With no handler — a test driving keys by hand — a
;; fault is thrown, as before.

;; --- input the terminal sends as a sequence -------------------------------------------
;;
;; glimmer-tui decodes what the terminal sends as a sequence: an SGR mouse report,
;; a bracketed paste, a UTF-8 character. This namespace used to replace its reader
;; (`read-event!`) to do that, for gaps reported from here — UTF-8 read as Latin-1,
;; no mouse release or drag, Tab taken for the focus ring (jolt-lang/glimmer-tui#15),
;; and a paste over a slow link split with no way to tell (#17). v0.2.11 closes
;; all four, so the reader is glimmer-tui's own and this hands it hooks
;; (byj-gly.272): `:on-mouse` for every mouse event, `:paste-timeout-ms` for how
;; long a paste may stall, and the view's `:on-key` for Tab and Shift+Tab. With
;; nothing rebound, the binary no longer needs `--no-direct-link` to run what the
;; suite tested (provenance P-24); it keeps it as the smaller, faster build.
;;
;; brainyard decodes the mouse and the paste itself (`input.clj`) for the same
;; reasons: stock macOS ncurses has no button 5, so its own mouse handling is off
;; and the terminal is asked for SGR reports; and a bracketed paste is the text
;; between its markers, exactly, rather than a clock guessing which keys arrived
;; too close together to be typed.

(def ^:private enable-sequences
  ;; 1002: button-event tracking, so a drag reports where it has got to
  "\033[?1000h\033[?1002h\033[?1006h\033[?2004h")
(def ^:private disable-sequences "\033[?1000l\033[?1002l\033[?1006l\033[?2004l")

(defonce ^:private terminal-modes
  ;; the modes this run turned on — mouse reporting and bracketed paste, or
  ;; the paste alone — so a hand-over to an editor can turn them off and back
  (atom nil))

(defn- handover
  "What to send before handing the terminal to another program, and after
  taking it back: every mode off, then the ones this run had on."
  []
  [disable-sequences @terminal-modes])

(defn- enable-modes!
  "Turn on mouse reporting (when `mouse?`) and bracketed paste, and remember
  which."
  [mouse?]
  (let [seqs (if mouse? enable-sequences "\033[?2004h")]
    (reset! terminal-modes seqs)
    (print seqs)
    (flush)))

(def paste-byte-ms
  "How long a bracketed paste may pause between bytes before what arrived is
  taken as all of it. Inside a paste its end marker is known to be coming, so
  this is a stall, not a person: 20 ms — the wait for a key sequence — split
  a large paste over ssh, and the rest arrived as keys (byj-gly.212). Handed
  to glimmer-tui's reader as `:paste-timeout-ms`, whose own is 100; a paste
  still cut short arrives marked `:cut?` (glimmer-tui#17)."
  500)


;; Where terminfo spells kmous as `\E[<` — Ghostty's entries, Homebrew ncurses'
;; xterm-256color — keypad mode matches the start of every SGR report as
;; KEY_MOUSE, and with ncurses' mouse off it hands back one bare KEY_MOUSE for a
;; burst of reports and drops their bytes: in Ghostty a click, a drag and the
;; wheel did nothing (byj-gly.268). `define_key(NULL, KEY_MOUSE)` removes every
;; string bound to KEY_MOUSE, whatever this terminal's kmous is, so a report
;; arrives as the bytes the reader below decodes.
(jffi/defcfn ^:private define-key "define_key" [:pointer :int] :int)
(def ^:private key-mouse 409)

;; --- the terminfo entry ncurses starts with ------------------------------------
;;
;; macOS's ncurses is 6.0.20150808, and ncurses before 20170729 uses `rep`
;; (repeat the last character N times) for characters past 255 as well — it
;; prints one `─` and asks the terminal to repeat a byte, so a rule comes out one
;; glyph long, and every frame after it is painted over a screen that is not the
;; one ncurses thinks it is: short rules, a line twice, a label left behind
;; (byj-gly.269). Ghostty's xterm-ghostty declares `rep`; the xterm-256color
;; stock macOS ships does not. So where the loaded ncurses has the bug and the
;; terminal's entry declares `rep`, ncurses starts from a plain xterm entry
;; instead — the terminals that declare `rep` (Ghostty, kitty, WezTerm) are
;; xterm-compatible, and nothing here uses what their own entries add. TERM is
;; put back once ncurses has read it, so what the session runs sees the real one.

(def ^:private rep-fixed
  "The ncurses patch that stopped using `rep` past 255 (its NEWS, 20170729)."
  20170729)

(def ^:private rep-free-terms ["xterm-256color" "xterm"])

(defn ncurses-patch
  "ncurses' patch date from its version string — `ncurses 6.0.20150808` →
  20150808 — or nil."
  [version]
  (some-> (re-find #"\.(\d{8})\s*$" (str version)) second parse-long))

(defn start-term
  "The TERM ncurses should start with: `term`, unless ncurses at `patch` has the
  `rep` bug and `term`'s entry declares `rep` (`(entry t)` is :absent, :rep or
  :plain) — then the first of the plain xterm entries that exists without it,
  or `term` when none does."
  [term patch entry]
  (if (and patch (< patch rep-fixed) (= :rep (entry term)))
    (or (first (filter #(= :plain (entry %)) rep-free-terms)) term)
    term))

(jffi/defcfn ^:private curses-version "curses_version" [] :string)
(jffi/defcfn ^:private setupterm-named "setupterm" [:string :int :pointer] :int)
(jffi/defcfn ^:private tigetstr "tigetstr" [:string] :pointer)
(jffi/defcfn ^:private setenv "setenv" [:string :string :int] :int)

(defn- terminfo-entry
  "Whether terminfo has an entry for `term`, and whether it declares `rep`."
  [term]
  (let [err (jffi/alloc 4)]
    (try
      (jffi/write err :int 0 0)
      (cond (not (zero? (setupterm-named term 1 err))) :absent
            ;; 0: not in the entry; -1: not a string capability
            (#{0 -1} (jffi/address (tigetstr "rep"))) :plain
            :else :rep)
      (catch Throwable _ :absent)
      (finally (jffi/free err)))))

(defonce ^:private term-to-restore (atom nil))

(defn- start-term!
  "Point ncurses at the entry `start-term` picks, remembering the real TERM to
  put back once ncurses has read it (`terminal-input!`)."
  []
  (try
    (let [term (System/getenv "TERM")
          t (start-term term (ncurses-patch (curses-version)) terminfo-entry)]
      (when (and term (not= t term))
        (reset! term-to-restore term)
        (setenv "TERM" t 1)))
    (catch Throwable _ nil)))

(defn- restore-term!
  "Put back the TERM `start-term!` changed, once. Safe to call when it changed
  nothing."
  []
  (when-let [term @term-to-restore]
    (reset! term-to-restore nil)
    (try (setenv "TERM" term 1) (catch Throwable _ nil))))

(defn- terminal-input!
  "Take the terminal's input for what this decodes itself: no terminfo kmous
  left to take a report (byj-gly.268), then SGR mouse reports (when `mouse?`)
  and bracketed paste asked for. glimmer-tui's `start!` turns reporting on
  itself (1000 and 1006, since v0.2.7), so with the mouse off it is turned off
  here — the terminal keeps its own click-drag selection. ncurses has read TERM
  by now, so the real one goes back (`start-term!`)."
  [mouse?]
  (restore-term!)
  (if mouse?
    (define-key jffi/null key-mouse)
    (do (print "\033[?1000l\033[?1002l\033[?1006l") (flush)))
  (enable-modes! mouse?))

(def wheel-rows
  "Rows a wheel notch moves: 3, as glimmer-tui's own wheel does."
  3)

(defn wheel-step
  "Rows `notches` wheel notches move, arriving together. A burst — a fast spin,
  or a trackpad's fling — moves further per notch than one notch alone, so a
  quick spin covers ground while a single notch still moves three rows."
  [notches]
  (let [n (abs notches)]
    (* (if (neg? notches) -1 1) wheel-rows n (cond (>= n 8) 3 (>= n 3) 2 :else 1))))

(defonce ^:private mouse-to
  ;; (fn [{:keys [button x y release?]}]) for a press, a drag and a release —
  ;; set by the mounted renderer, which owns the selection
  (atom nil))

(defn- point-input!
  "Send the mouse and the wheel to `mouse-fn` and `wheel-fn` — the view being
  drawn; nil for a view that has neither. Called as a view renders, so they
  follow what is on screen, and a no-op when they already point there."
  [mouse-fn wheel-fn]
  (when-not (identical? @mouse-to mouse-fn) (reset! mouse-to mouse-fn))
  (when-not (identical? @wheel-to wheel-fn) (reset! wheel-to wheel-fn)))

(defn- act-on-mouse!
  "What an SGR report does: a left press clicks, a wheel notch scrolls. glimmer-tui
  reads 3 lines a notch; so does this."
  [{:keys [button x y release?] :as m}]
  (let [wheel (bit-and button 67)]                   ; 64/65, modifiers masked off
    (cond
      ;; :notches is signed, up negative; a report on its own is one notch
      (#{64 65} wheel) (let [n (or (:notches m) (if (= 64 wheel) -1 1))]
                         (when-not (zero? n) (wheel! x y (wheel-step n))))
      ;; the left button — pressed, dragged (32 is motion) or let go
      (zero? (bit-and button 3)) (if-let [f @mouse-to]
                                   (f m)
                                   (when (and (not release?) (zero? (bit-and button 32))) (tui/click! x y)))
      :else nil)))

(defn- wheel-of [m] (let [w (bit-and (:button m) 67)] (when (#{64 65} w) w)))
(defn- motion? [m] (and (not (wheel-of m)) (pos? (bit-and (:button m) 32)) (not (:release? m))))

(defn coalesce-reports
  "Mouse reports that arrived together, as the ones worth acting on: a run of
  wheel notches is ONE report carrying how many (`:notches`, signed by
  direction — opposite notches net out), and a run of drag motions is its LAST
  position. Presses and releases stay, in order. A drag reports every cell the
  pointer crosses and a spin every notch; acted on one by one, each is a
  repaint, and the screen fell behind the hand."
  [ms]
  (reduce (fn [acc m]
            (let [prev (peek acc)]
              (cond
                (and (wheel-of m) prev (wheel-of prev))
                (let [n (+ (:notches prev) (if (= 64 (wheel-of m)) -1 1))]
                  (conj (pop acc) (assoc prev :notches n :button (if (neg? n) 64 65))))
                (wheel-of m) (conj acc (assoc m :notches (if (= 64 (wheel-of m)) -1 1)))
                (and (motion? m) prev (motion? prev)) (conj (pop acc) m)
                :else (conj acc m))))
          []
          ms))

(defn mouse-report
  "A glimmer-tui mouse event (`keys/sgr-mouse`: `:button` 0-2 or nil, `:wheel`,
  `:action`, `:motion`) as the report this namespace acts on: SGR's button code
  — 64 and 65 the wheel, 32 added for motion — and `:release?`."
  [{:keys [button wheel x y action motion]}]
  {:button (cond (= :up wheel) 64
                 (= :down wheel) 65
                 :else (+ (or button 0) (if motion 32 0)))
   :x x :y y :release? (= :release action)})

(def coalesce-ms
  "How long a wheel notch or a drag motion waits for the rest of its burst.
  glimmer-tui's loop hands over one event and repaints before the next, so a
  spin acted on notch by notch was a repaint each and fell behind the hand; the
  reports that arrive within this are acted on together (`coalesce-reports`)."
  12)

(defonce ^:private held-reports (atom []))
(defonce ^:private flush-armed? (atom false))

(defn- flush-reports!
  "Act on the held reports, coalesced, in the order they came."
  []
  (reset! flush-armed? false)
  (let [[ms] (reset-vals! held-reports [])]
    (doseq [m (coalesce-reports ms)] (act-on-mouse! m))))

(defn on-mouse
  "glimmer-tui's `:on-mouse`: every mouse event is this renderer's. A press or a
  release is acted on at once, after any motion held before it, so a drag ends
  where it was let go; a wheel notch and a drag motion wait `coalesce-ms` for
  the rest of their burst. Claims the event, so the loop's own click and
  scroll do not act on it again."
  [event]
  (let [m (mouse-report event)]
    (swap! held-reports conj m)
    (if (or (wheel-of m) (motion? m))
      (when (compare-and-set! flush-armed? false true)
        (tui/after! coalesce-ms flush-reports!))
      (flush-reports!))
    true))

(def shift-tab
  "Shift+tab, as glimmer-tui decodes it. Since v0.2.9 it goes through the key
  dispatch before the loop would move the focus with it (glimmer-tui#15), so the
  view's `:on-key` takes it — the permission mode's cycle — and always claims
  it: moved off the input, the focus would land on the transcript, which takes
  clicks and ignores keys."
  {:type :back-tab})

(def tab-key
  "Tab, as glimmer-tui decodes it, offered to the view's `:on-key` before the
  loop moves the focus (glimmer-tui#15). It takes the marked completion, as a
  shell's Tab does, and is always claimed: the only other focusable widget is
  the transcript, and a Tab that moved the focus there left every key after it
  going nowhere — the screen looked hung."
  {:type :tab})

;; --- text --------------------------------------------------------------------------

(defn wrap
  "`s` broken to `width` columns — on spaces where it can be, mid-word where a
  word is wider than the screen. A `:label` breaks only on newlines (RFC-028
  rule 6) and a model answers in paragraphs, so the wrap is ours. Widths are
  grapheme clusters, so an emoji is one glyph two cells wide rather than two
  characters."
  [s width]
  (let [width (max 8 width)
        fit (fn [line]
              (if (<= (text/width line) width)
                [line]
                (loop [words (seq (str/split line #" ")) cur nil out []]
                  (if-let [w (first words)]
                    (cond
                      ;; a word nothing can fit: cut it at the margin and carry
                      ;; what is left on as though it were the next word
                      (> (text/width w) width)
                      (let [head (text/truncate w width)]
                        (recur (cons (text/drop-cells w (text/width head)) (next words))
                               nil
                               (cond-> out cur (conj cur) :always (conj head))))

                      (nil? cur) (recur (next words) w out)

                      (<= (text/width (str cur " " w)) width)
                      (recur (next words) (str cur " " w) out)

                      :else (recur (next words) w (conj out cur)))
                    (cond-> out cur (conj cur))))))]
    (vec (mapcat fit (str/split-lines (str s))))))

(def ^:private emphasis
  "The inline markers, longest first so `**` is never read as two `*`.
  glimmer-tui paints bold, dim and underline but has no italic attribute, so
  `*emphasis*` is drawn underlined — the way `man` and `less` draw it — rather
  than left as asterisks; brainyard's `apply-inline-md` uses the ANSI italic
  this toolkit cannot send. `~~struck~~` has no strike either, and reads dim."
  [["**" {:bold true}]
   ["__" {:bold true}]
   ["~~" {:dim true}]
   ["`"  {:color :green}]
   ["*"  {:underline true}]
   ["_"  {:underline true}]])

(defn- word-char? [s i]
  (and (< -1 i (count s)) (boolean (re-matches #"\w" (subs s i (inc i))))))

(defn- pair
  "`[open close]` for the first well-formed use of `marker` in `s`, or nil.

  Well-formed is CommonMark's flanking rule, cut down: what is inside is not
  empty and does not start or end with a space — a model writing `2 * 3 * 4`
  means multiplication — and an underscore neither opens nor closes inside a
  word, so `snake_case_name` is a name. Code is taken literally, spaces and all."
  [s marker]
  (let [n (count marker)
        code? (= "`" marker)
        ok? (fn [i close]
              (let [inner (subs s (+ i n) close)]
                (and (seq inner)
                     (or code?
                         (not (or (str/blank? (subs inner 0 1))
                                  (str/blank? (subs inner (dec (count inner)))))))
                     (or (not (str/starts-with? marker "_"))
                         (not (or (word-char? s (dec i)) (word-char? s (+ close n))))))))]
    (loop [from 0]
      (when-let [i (str/index-of s marker from)]
        (when-let [close (str/index-of s marker (+ i n))]
          (if (ok? i close) [i close] (recur (inc i))))))))

(def ^:private link-style {:underline true :color :blue})

(defn- link
  "The first `[text](url)` or `<https://…>` in `s`, as a candidate for `opening`."
  [s]
  (->> [(when-let [[m text url] (re-find #"\[([^\[\]]+)\]\(([^()\s]+)\)" s)]
          {:at (str/index-of s m) :len (count m) :inner text :url url :style link-style})
        (when-let [[m url] (re-find #"<(https?://[^<>\s]+)>" s)]
          {:at (str/index-of s m) :len (count m) :inner url :url url :style link-style
           :literal? true})]
       (remove nil?)
       (sort-by :at)
       first))

(defn- opening
  "The earliest piece of inline markup in `s` — `{:at :len :inner :style}`,
  with `:literal?` when what is inside is not read again and `:url` for a
  link — or nil. At the same index the earlier marker in `emphasis` wins."
  [s]
  (->> (concat
        (for [[marker style] emphasis
              :let [[i close] (pair s marker)]
              :when i]
          {:at i :len (- (+ close (count marker)) i) :style style
           :inner (subs s (+ i (count marker)) close)
           :literal? (= "`" marker)})
        (some-> (link s) vector))
       (sort-by :at)
       first))

(defn spans
  "A line as styled runs: `[{:text … :style …} …]`. Markup nests — `**a `b`**`
  is bold with code inside — except inside code, which means its own
  characters. A link keeps its address, dim beside the text, because a
  terminal cannot follow it and the reader may want to.

  A line with no markup comes back as ONE span, which is what keeps the common
  case a single widget — a repaint costs about half a millisecond per visible
  widget (`byj-3dw`), so a row split into five labels is five times the price
  of a row that did not need splitting."
  [line]
  (loop [s (str line) out []]
    (if-let [{:keys [at len inner style literal? url]} (opening s)]
      (let [before (subs s 0 at)
            inside (if literal?
                     [{:text inner :style style}]
                     (mapv #(update % :style merge style) (spans inner)))]
        (recur (subs s (+ at len))
               (cond-> out
                 (seq before) (conj {:text before})
                 :always (into inside)
                 (and url (not= url inner)) (conj {:text (str " (" url ")") :style {:dim true}}))))
      (cond-> out (seq s) (conj {:text s})))))

(defn- words
  "Spans to words, each a vector of runs. A span boundary INSIDE a word — the
  `s` of `**bold**s` — keeps both runs in the same word, so a wrap never breaks
  where the text does not; a boundary where the text had a space starts a new
  one."
  [spans]
  (first
   (reduce
    (fn [[acc glue?] {:keys [text style]}]
      (let [text (str text)
            parts (str/split text #" " -1)
            acc (reduce (fn [acc [i part]]
                          (cond
                            (empty? part) acc
                            ;; the first part continues the word before it,
                            ;; unless a space (or nothing) came first
                            (and (zero? i) glue? (seq acc))
                            (update acc (dec (count acc)) conj {:text part :style style})
                            :else (conj acc [{:text part :style style}])))
                        acc
                        (map-indexed vector parts))]
        [acc (and (seq text) (not (str/ends-with? text " ")))]))
    [[] false]
    spans)))

(defn- word-width [word] (reduce + 0 (map #(text/width (:text %)) word)))

(declare wrap-runs)

(defn wrap-spans
  "Wrap styled runs to `width`, returning rows of runs. The plain-text wrap in
  `wrap` is this without the styles; a word wider than the screen is cut at the
  margin the same way."
  [spans width]
  (wrap-runs spans (max 8 width)))

(defn- wrap-runs
  "`wrap-spans` with no floor under the width, for a table cell, which may be
  narrower than any screen. Two cells is the least that always makes progress:
  a wide glyph is two."
  [spans width]
  (let [width (max 2 width)]
    (loop [ws (seq (words spans)) row [] row-w 0 out []]
      (if-let [word (first ws)]
        (let [ww (word-width word)
              sep (if (seq row) 1 0)]
          (cond
            ;; a word nothing can fit starts a row of its own, the way `wrap`
            ;; does — cutting it to what is left of this one ignored the words
            ;; already on it and overran the margin by their width
            (and (> ww width) (seq row))
            (recur ws [] 0 (conj out row))

            ;; and on its own row, take what fits and carry the rest on
            (> ww width)
            (let [head-text (text/truncate (:text (first word)) width)
                  head (assoc (first word) :text head-text)
                  left (text/drop-cells (:text (first word)) (text/width head-text))
                  rest-word (cond-> (vec (rest word))
                              (seq left) (->> (into [(assoc (first word) :text left)])))]
              (recur (cons rest-word (rest ws))
                     []
                     0
                     (conj out (cond-> [] (seq head-text) (conj head)))))

            (<= (+ row-w sep ww) width)
            (recur (rest ws)
                   (into row (cond-> word
                               (seq row) (update 0 update :text #(str " " %))))
                   (+ row-w sep ww)
                   out)

            :else (recur ws [] 0 (conj out row))))
        (cond-> out (seq row) (conj row))))))

;; --- the cells ---------------------------------------------------------------------

(def ^:private kind-style
  "How each kind of line reads on screen. The kernel says what a line IS; this
  is the only place that says what it looks like (RFC-026)."
  ;; what was typed stands on a band of its own, the whole row wide, so a
  ;; person finds their own lines in a long transcript at a glance. 237 is
  ;; #3a3a3a in the xterm palette: a shade above a dark terminal's background
  {:you     {:bg 237}
   :answer  {}
   :heading {:bold true :color :cyan}
   :quote   {:dim true}
   :code    {:color :green}
   :rule    {:dim true}
   :info    {:dim true}
   :error   {:color :red}
   :problem {:color :yellow}
   :trace   {:dim true}
   :tool    {:color :blue}
   :call    {:bold true}
   :done    {:dim true}
   :fold    {:dim true}
   :recap   {:dim true}
   :busy    {:color :yellow}
   :reason  {:dim true :color :cyan}
   ;; a diff's lines (byj-gly.381)
   :added   {:color :green}
   :removed {:color :red}})

;; --- block markdown ------------------------------------------------------------------
;;
;; An entry is `{:kind :text}` and, where the block has a margin of its own,
;; `:lead` (the run in front of its first row) and `:hang` (in front of the rest)
;; — so a wrapped list item stays under its bullet, and a wrapped quote or code
;; line keeps its gutter. A table is `{:kind :table :rows :align}` and is laid
;; out when the width is known, because a column's width is a fact about the
;; screen, not about the answer.

(def ^:private gutter {:text "│ "})
(def ^:private quote-bar {:text "▎ " :style {:dim true}})

(defn- rule-line? [line]
  (boolean (re-matches #"\s{0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})" line)))

(defn- table-rule? [line]
  (and (str/includes? line "|")
       (boolean (re-matches #"\s*\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?\s*" line))))

(def ^:private escaped-bar
  "What `\\|` stands in for while a row is split on its bars."
  (str (char 0xE000)))

(defn- cells
  "A table row's cells, the outer bars dropped and `\\|` kept as a bar."
  [line]
  (let [t (str/replace (str/trim line) "\\|" escaped-bar)
        t (cond-> t (str/starts-with? t "|") (subs 1))
        t (cond-> t (str/ends-with? t "|") (subs 0 (dec (count t))))]
    (mapv #(str/trim (str/replace % escaped-bar "|")) (str/split t #"\|" -1))))

(defn- alignment [cell]
  (let [c (str/trim cell)]
    (cond (and (str/starts-with? c ":") (str/ends-with? c ":")) :center
          (str/ends-with? c ":") :right
          :else :left)))

(defn- quote-depth
  "`[depth body]` for a quoted line — `> > x` is two deep — or nil."
  [line]
  (when-let [[_ body] (re-matches #"\s{0,3}>\s?(.*)" line)]
    (loop [depth 1 body body]
      (if-let [[_ inner] (re-matches #">\s?(.*)" body)]
        (recur (inc depth) inner)
        [depth body]))))

(defn markdown
  "An answer as entries of kinds: a fenced block is `:code`, a heading is
  `:heading` with its `#`s gone, a list item is `:answer` behind its bullet, a
  quote is `:quote` behind its bar, `---` is `:hr`, a pipe table is `:table`,
  and everything else is `:answer`. A model writes markdown whether or not
  anyone asked it to, and brainyard's renderer reads it the same way
  (`render.clj` `markdown-line`) — without this a code block is prose that
  happens to be indented and a table is a row of bars."
  [text]
  (let [lines (vec (str/split-lines (str text)))
        n (count lines)]
    (loop [i 0 fenced? false out []]
      (if (>= i n)
        out
        (let [line (nth lines i)
              t (str/trim line)
              fence? (or (str/starts-with? t "```") (str/starts-with? t "~~~"))]
          (cond
            ;; the fence is not content. An opening one names its language if
            ;; it was given one; a closing one draws nothing, because the gutter
            ;; already says where the block ends — two rules around a one-line
            ;; block is more furniture than block
            fence? (recur (inc i) (not fenced?)
                          (let [lang (str/trim (subs t 3))]
                            (cond-> out
                              (and (not fenced?) (seq lang)) (conj {:kind :rule :text lang}))))
            fenced? (recur (inc i) true (conj out {:kind :code :text line :lead gutter :hang gutter}))

            ;; a header row is a table only when the next line is its rule;
            ;; the body is every line after that until one has no bar
            (and (str/includes? line "|") (< (inc i) n) (table-rule? (nth lines (inc i))))
            (let [body (take-while #(and (str/includes? % "|") (not (str/blank? %)))
                                   (subvec lines (+ i 2)))]
              (recur (+ i 2 (count body)) false
                     (conj out {:kind :table
                                :text (str/join "\n" (concat [line (nth lines (inc i))] body))
                                :rows (into [(cells line)] (map cells body))
                                :align (mapv alignment (cells (nth lines (inc i))))})))

            (rule-line? line) (recur (inc i) false (conj out {:kind :hr :text ""}))

            :else
            (recur (inc i) false
                   (conj out
                         (if-let [[_ _ body] (re-matches #"\s{0,3}(#{1,6})\s+(.*)" line)]
                           {:kind :heading :text (str/replace body #"\s+#+\s*$" "")}
                           (if-let [[depth body] (quote-depth line)]
                             (let [bar (update quote-bar :text #(apply str (repeat depth %)))]
                               {:kind :quote :text body :lead bar :hang bar})
                             (if-let [[_ indent marker body] (re-matches #"(\s*)([-*+]|\d{1,9}[.)])\s+(.*)" line)]
                               (let [bullet (if (re-matches #"[-*+]" marker) "•" marker)
                                     lead (str indent bullet " ")]
                                 {:kind :answer :text body
                                  :lead {:text lead :style {:color :cyan}}
                                  :hang {:text (apply str (repeat (text/width lead) " "))}})
                               {:kind :answer :text line})))))))))))

(def paste-gap-ms
  "A newline arriving this soon after the previous keystroke is PASTED, not
  pressed — the fallback for a terminal that does not bracket a paste. Where
  it does, glimmer-tui decodes `ESC [ 200 ~ … ESC [ 201 ~` (byj-gly.272)
  and the paste arrives whole; where it does not, a pasted line break is an
  ordinary Enter, and the clock tells the two apart — far enough apart that a
  threshold is not a guess. Measured under a pty with the real loop
  (`dev/paste_probe.clj` in the bead):

      pasted   chars 1–7 ms apart, the Enter inside the paste 1–2 ms after
      typed    chars 413 ms apart, the Enter 608 ms after

  40 ms is an order of magnitude above the paste and an order below the typing.
  A person cannot press Enter 40 ms after a letter; a terminal cannot help it.
  Re-measure with `dev/brainyard/dev/paste_probe.clj` rather than trusting this."
  40)

(def paste-collapse-lines
  "A paste longer than this collapses to a marker in the field. brainyard's
  number, and for its reason: a hundred-line stack trace in a one-line prompt
  scrolls everything else away, and what you want to see is that it arrived."
  5)

(defn paste-marker [id lines]
  (str "[paste #" id " +" lines " lines]"))

(def ^:private paste-marker-re #"\[paste #(\d+) \+(\d+) lines\]")

(defn expand-pastes
  "A submitted line with every marker replaced by what it stands for. The field
  holds the marker; the model reads the paste."
  [line pastes]
  (str/replace line paste-marker-re
               (fn [[marker id _]]
                 (or (get-in pastes [(parse-long id) :text]) marker))))

(def max-history
  "How many submitted lines Up walks back through: the newest 100. A window,
  not a ledger — the conversation itself is in the transcript and the session
  snapshot."
  100)

(def ^:private chrome-rows
  "Rows the transcript does not get: the blank row and the usage row under it,
  the rule above the input, the input line, the rule under it, and the footer."
  6)

(declare external-editor! open-link! hand-terminal!)

(def ui-commands
  "The commands this renderer answers itself, because what they are about is
  its own: they never reach the session (RFC-004's commands are tree leaves,
  and a leaf cannot see a renderer's state). brainyard's `/queue`, whose queue
  lives here — lines typed while a turn runs wait in the renderer until the
  session next asks for input (byj-gly.143)."
  [["/queue" "lines waiting for the turn to end; [cancel all|N]"]
   ;; the frame's rails (byj-gly.123): what you see, never the model's
   ["/left" "the left rail: open or close it (ctrl+l); [VIEW]"]
   ["/right" "the right rail: open or close it (ctrl+t); [VIEW]"]])

(defn queue-command
  "What `/queue ARGS` says about `queue`, and the queue it leaves: `[text
  queue]`. Listing numbers the lines from 1, the next to be read first;
  `cancel N` drops one and `cancel all` drops every one."
  [queue args]
  (let [[verb which] (str/split (str/trim (str args)) #"\s+")
        clip (fn [l] (let [l (str/replace l #"\s+" " ")] (if (> (count l) 70) (str (subs l 0 70) "…") l)))]
    (cond
      (str/blank? verb)
      [(if (empty? queue)
         "nothing is queued"
         (str (count queue) " queued, read in this order:"
              (apply str (map-indexed (fn [i l] (str "\n  " (inc i) ". " (clip l))) queue))))
       queue]

      (and (= "cancel" verb) (= "all" which))
      [(str "cancelled " (count queue) " queued line" (when (not= 1 (count queue)) "s")) []]

      (and (= "cancel" verb) (re-matches #"\d+" (str which)))
      (let [n (parse-long which)]
        (if (<= 1 n (count queue))
          [(str "cancelled " n ". " (clip (nth queue (dec n))))
           (vec (concat (subvec queue 0 (dec n)) (subvec queue n)))]
          [(str "no queued line " n " — /queue lists them") queue]))

      :else ["usage: /queue [cancel all|cancel N]" queue])))

(defn attention-sequence
  "What is written to the terminal when a session turns to Needs input
  (byj-gly.328), for `:attention-signal`: `:bell` a BEL, which a terminal or
  tmux in the background marks on its tab or window; `:osc9` a desktop
  notification saying which session (iTerm2, WezTerm, kitty, Windows Terminal,
  Ghostty), wrapped for tmux to pass on when `tmux?`; `:both`; `:off` nothing.
  Anything else is the default, `:bell`."
  [signal title tmux?]
  (let [bell "\u0007"
        ;; a control character in the title would end the sequence early
        text (apply str (map #(if (or (< (int %) 32) (= 127 (int %))) \space %)
                             (str "brainyard: " (if (str/blank? (str title)) "a session" (str title)) " needs input")))
        osc (str "\u001b]9;" text "\u0007")
        osc9 (if tmux?
               (str "\u001bPtmux;" (str/replace osc "\u001b" "\u001b\u001b") "\u001b\\")
               osc)]
    (case (keyword (str/replace (name (or signal :bell)) #"^:" ""))
      :off nil
      :osc9 osc9
      :both (str osc9 bell)
      bell)))

(defn- attention!
  "Say to the terminal, once, that this session now waits on a person — a
  permission ask has opened (byj-gly.328). The ask is one state, so one ask is
  one signal; the next ask, after this one is answered, is a change again."
  [c]
  (when-let [out (attention-sequence (:attention c) @(:title c) (boolean (System/getenv "TMUX")))]
    ((:signal! c) out)))

(defn- new-cells [{:keys [max-lines commands editor display-box verbose copier suggestions? permission-mode-box
                          lm lm-box on-agents working-dir mouse? open-link attention signal! rails rails-saved backgrounders]
                   :or {max-lines 500}}]
  {:lines     (r/atom [])       ; [{:kind :text}], the transcript as it was said
   :live      (r/atom nil)      ; {:field :text}, the reply as it streams
   :draft     (r/atom "")       ; what the input line holds
   :prompt    (r/atom nil)      ; the prompt :read-input is waiting with
   :gate      (r/atom nil)      ; {:prompt :choices :label}, a permission ask, or an injected turn's approval
   :share     (r/atom nil)      ; the share sidecar's last presence (RFC-043), while sharing
   :shares    (r/atom nil)      ; the shares this process started (`share/from-here`), read every share-every-ms
   :offset    (r/atom 0)        ; rows scrolled back from the bottom
   :iter      (r/atom nil)      ; {:n :reasoning :calls :results}, the live block
   :busy      (r/atom {})       ; what is in flight right now, keyed by what it is
   :queue     (r/atom [])       ; lines typed while a turn was running
   :history   (r/atom [])       ; what has been submitted, oldest first
   :pastes    (r/atom {})       ; id -> {:lines :text}, what a marker stands for
   :pasting   (atom false)      ; a paste the clock caught is arriving, and may be folded
   :paste-cut (atom false)      ; a bracketed paste stalled: what follows is its rest, not a line to send
   :selection (r/atom nil)      ; {:from [x y] :to [x y]}, screen cells the mouse has dragged over
   :press     (atom nil)        ; {:x :y} where the left button went down
   :flash     (r/atom nil)      ; a line for the footer, until the next press — what a copy did
   :suggestion (r/atom nil)     ; the model's next-user-prompt, offered in the empty input line
   :tip-at    (r/atom 0)        ; which help tip the footer shows, advanced every tip-every-ms
   ;; the session list, when this renderer is one of several (`run-many!`):
   ;; ← on an empty line opens it
   :on-agents on-agents
   :last-active (atom (System/currentTimeMillis)) ; when this session last said anything
   :title     (r/atom nil)      ; the conversation's name, from the last recap — shown on the input's rule
   :picker    (r/atom nil)      ; {:kind :title :items :query :at :top :draft} while /config or /model picks
   :unsaid    (atom #{})        ; lines the picker sent, which the transcript does not echo
   ;; the permission mode: the run's box, which the gates read, and a cell of
   ;; it to draw from. No box, no mode shown and nothing for shift+tab to do
   :mode-box  permission-mode-box
   :mode      (r/atom (some-> permission-mode-box deref))
   ;; the model the session is asking: the run's, until /model writes the box
   :lm-box    lm-box
   :model     (r/atom (some-> (or (some-> lm-box deref) lm) llm/lm-label not-empty))
   :suggestions? (not (false? suggestions?))
   :copier    (or copier clipboard/copy!)
   :menu      (r/atom nil)      ; {:items [[text doc]…] :from idx :at n} while completing
   :walk      (r/atom nil)      ; {:at index :draft saved} while walking history
   :size      (r/atom {:w 80 :h 24}) ; the CENTRE's size: the screen less any rail beside it
   :screen    (r/atom {:w 80 :h 24}) ; the whole terminal (`resize!`)
   ;; the frame's rails (byj-gly.123): their views are the caller's, as
   ;; :commands are; their state, and which one the keys are in, is ours
   :rail-views {:left (vec (:left rails)) :right (vec (:right rails))}
   :rails     (r/atom (frame/closed-rails))
   :rail-focus (r/atom nil)     ; :left or :right while a rail has the keys
   ;; the rails as a restart left them (`:rails-saved`, byj-gly.457), put
   ;; back the first time the screen's size is known (`resize!`)
   :rails-pending (atom rails-saved)
   :tick      (r/atom 0)        ; the spinner's frame
   :done      (r/atom nil)      ; {:turn :elapsed-ms :ended-at}, said under the answer
   :next-id   (atom 0)          ; a transcript entry's :id, what its laid-out rows are cached by
   :layouts   (atom 0)          ; how often the transcript was laid out — plain, so reading it tracks nothing
   :subs      (r/atom {})       ; subagent id -> {:agent :lines :live :iter :at}, each child's own transcript
   :viewing   (r/atom nil)      ; the subagent id whose transcript is on screen, or nil for the root's
   :unfolded  (r/atom #{})      ; the ids of the folds that are open
   :fold-at   (atom nil)        ; the fold touched last — what ctrl-o opens
   :search    (r/atom nil)      ; {:query :hits :at :draft} while ctrl-f is finding
   ;; how much of a turn is shown — the run's, which :output-style sets
   ;; (byj-gly.112); a renderer made without one gets its own from :verbose
   :display   (or display-box (atom (if verbose :verbose :normal)))
   ;; how a fold's whole text is shown outside the transcript; a test passes
   ;; its own, since the real one needs a terminal to hand over
   :editor    (or editor external-editor!)
   ;; what a click on a path or a URL does (byj-gly.270); a seam, as :editor is
   :open-link (or open-link open-link!)
   ;; the tools the session handed over, for completion (byj-gly.273.4)
   :tool-rows (atom [])
   :working-dir (or working-dir (System/getProperty "user.dir"))
   ;; marks promise a click; with the mouse off there is none to make
   :mouse?    (not (false? mouse?))
   ;; :attention-signal, and where it is written: the terminal, or a test's
   :attention (or attention :bell)
   ;; written on the render loop's thread, between frames: from the kernel's,
   ;; it could land inside an escape sequence a frame is writing
   :signal!   (or signal! (fn [out]
                            (let [write #(try (print out) (flush) (catch Throwable _ nil))]
                              (try (tui/after! 0 write) (catch Throwable _ (write))))))
   :status    (r/atom {:running? false :turn 0 :calls 0
                       :input-tokens 0 :output-tokens 0 :tasks 0 :cost nil})
   :max-lines max-lines
   ;; the tool calls the turn is waiting on, each with a release that ends
   ;; the wait now (tasks/detachable): ctrl+g calls them (byj-gly.391). The
   ;; session is handed the same atom, as it is :cancellers
   :backgrounders (or backgrounders (atom {}))
   ;; [[command doc] …] — what the input line completes; the caller's, because a
   ;; renderer does not know what a session's commands are (RFC-001)
   :commands (into (vec commands) ui-commands)})

(def ^:private you-mark
  "What stands in front of a line someone typed: the prompt's own mark, dim, so
  the text is what the eye reads."
  {:text "❯ " :style {:dim true}})

(defn you-entries
  "A typed line as the transcript shows it: an empty row, for air, then the
  line on its band behind the mark, wrapped under its own text. A message of
  several lines keeps them, and its indentation.

  One longer than `paste-collapse-lines` shows that many and folds the rest,
  as the field folds a paste into a marker: a pasted stack trace is the
  person's own words, and a hundred rows of it would push the answer it was
  asking about off the screen. The fold is the transcript's fold (a click opens
  it, ctrl-o hands it to the editor), drawn on the band as the line's own
  (byj-gly.150)."
  [line]
  (let [text (str/trim (str line))
        lines (str/split-lines text)]
    (if (<= (count lines) paste-collapse-lines)
      [{:kind :answer :text ""}
       {:kind :you :text text :lead you-mark :hang {:text "  "}}]
      (let [shown (take paste-collapse-lines lines)
            nonblank #(count (remove str/blank? %))]
        [{:kind :answer :text ""}
         {:kind :you :text (str/join "\n" shown) :lead you-mark :hang {:text "  "}}
         {:kind :fold :as :you :text text
          ;; the fold counts lines as it shows them, blank ones left out
          :skip (nonblank shown) :more (- (nonblank lines) (nonblank shown))
          :lead {:text "  "} :hang {:text "  "}}]))))

(defn- say!
  "Append a line, and drop the oldest past the window. A transcript longer than
  the session is what the trajectory is for (RFC-025); what is on screen is
  what a person can read."
  ([c kind text]
   (when (some? text)
     (if (= :you kind)
       ;; a typed line is two entries: the air above it, and the line itself
       (doseq [e (you-entries text)] (say! c e))
       (say! c {:kind kind :text (str text)}))))
  ([{:keys [lines max-lines next-id last-active]} entry]
   (some-> last-active (reset! (System/currentTimeMillis)))
   (swap! lines (fn [ls]
                  (let [ls (conj ls (assoc entry :id (swap! next-id inc)))]
                    (if (> (count ls) max-lines)
                      (subvec ls (- (count ls) max-lines))
                      ls))))))

(defn- say-all!
  "`say!` for many entries at once: one change to the transcript, so what is
  shown is laid out once rather than once per entry."
  [{:keys [lines max-lines next-id last-active]} entries]
  (when (seq entries)
    (some-> last-active (reset! (System/currentTimeMillis)))
    (swap! lines (fn [ls]
                   (let [ls (into ls (map #(assoc % :id (swap! next-id inc))) entries)]
                     (if (> (count ls) max-lines)
                       (subvec ls (- (count ls) max-lines))
                       ls))))))

(defn- fault-sayer
  "Where a fault is said: an :error line in the session `cells-of`
  names, each distinct fault once — a repeating timer that throws would
  otherwise say it five times a second (byj-gly.214), and a view that throws
  would say it on every change that re-renders it (byj-gly.236)."
  [cells-of]
  (let [said (atom #{})]
    (fn [t]
      (let [text (str "part of the screen failed, and the session carries on: "
                      (or (ex-message t) (str t)))]
        (when-let [c (cells-of)]
          (when-not (contains? @said text)
            (swap! said conj text)
            (say! c :error text)))))))
(defn- on-error
  "glimmer-tui's `:on-error` for a run: each fault said once in the session
  `cells-of` names (`fault-sayer`). Never throws — a handler that throws ends
  the loop, which is the one thing it is there to prevent."
  [cells-of]
  (let [say (fault-sayer cells-of)]
    (fn [t _phase] (try (say t) (catch Throwable _ nil)))))

(defn- grow-live
  "The reply so far with a streamed fragment added: the same field grows, a new
  one takes over."
  [live {:keys [field text]}]
  (if (= field (:field live))
    (update live :text str text)
    {:field field :text text}))

(defn- drop-reasoning
  "The live reply without its streamed reasoning: once the reply is parsed the
  thought is the block's, and left here the next iteration's reasoning would
  have grown onto it (byj-gly.280.3)."
  [live]
  (when-not (= :reasoning (:field live)) live))

(declare margin elbow)

(defn with-verdict
  "`iter` with the auto-mode classifier's verdict `data` (`:permission/classified`)
  on the call it was about: the first call of that tool with none yet. Calls run
  in order (divergence `parallel-tools-off`), so it is the one running now
  (byj-gly.285.2)."
  [iter data]
  (let [iter (or iter {:n 1 :calls [] :results []})
        taken (set (keys (:verdicts iter)))
        i (first (keep-indexed (fn [i c] (when (and (= (:tool-name c) (:tool data)) (not (taken i))) i))
                               (:calls iter)))]
    (cond-> iter i (assoc-in [:verdicts i] (select-keys data [:verdict :reason :cached?])))))

(defn- verdict-entry
  "The line under a call the auto-mode classifier decided: red when it blocked
  it, dim when it let it through. An unsure verdict is the person's prompt,
  which says it itself."
  [{:keys [verdict reason]}]
  (case verdict
    :block {:kind :error :text (str "⛔ blocked by auto mode: " reason) :margin margin
            :lead elbow :hang {:text "   "}}
    :allow {:kind :trace :text (str "✓ allowed by auto mode: " reason) :margin margin
            :lead elbow :hang {:text "   "}}
    nil))

(defn- with-thought [iter thought]
  (assoc (or iter {:n 1 :calls [] :results []}) :thought thought))

;; --- a subagent's own transcript (byj-gly.138) ----------------------------------------
;;
;; A child turn's events carry `:subagent-id` (RFC-018), and what a child does —
;; its reply as it streams, its tool calls — goes into a buffer of its own
;; rather than the root's transcript, where it read as the caller's work. A
;; click on the child's running row, or on the call that started it, puts its
;; buffer on the screen; Esc puts the root's back.

(declare block-lines answer-entries give-focus-back!)

(def max-subagent-buffers
  "How many children's transcripts are kept. The oldest goes first; a child
  that is being looked at is never the one dropped."
  20)

(def ^:private child-events
  "What a child turn says that belongs in ITS transcript rather than the
  root's. The rest — the spinner, the counts, a problem — is the session's."
  #{:agent.llm/chunk :agent.iteration/start :agent.tools/calls :agent.tools/results
    :agent.context/budgeted :agent.think/reasoning :agent.tools/recovered :permission/classified
    ;; a workflow's steps, as agent$claude's are (byj-gly.299)
    :agent.tree/step-start :agent.tree/step-progress :agent.tree/step-end
    ;; what a running tool prints (byj-gly.364)
    :agent.tool/output})

(def progress-rows
  "How many of a running step's progress lines it shows — the newest; the
  rest have scrolled by (byj-gly.299)."
  4)

(defn- step-iter
  "A workflow step as the live block's iteration: one call, its result to
  come (byj-gly.299)."
  [{:keys [node kind name args]}]
  {:n node :calls [(cond-> {:tool-name (str name) :tool-args args} kind (assoc :step-kind kind))]
   :results [] :step true})

(defn- with-progress [iter {:keys [line]}]
  (when iter (update iter :progress #(vec (take-last progress-rows (conj (vec %) (str line)))))))

(defn with-output
  "`iter` with a line a running tool printed (`:agent.tool/output`,
  byj-gly.364): kept as the newest few under the call that printed it — a
  line from another call of the batch starts its own."
  [iter {:keys [line call]}]
  (let [iter (or iter {:n 1 :calls [] :results []})]
    (if (= call (:progress-call iter))
      (with-progress iter {:line line})
      (assoc iter :progress [(str line)] :progress-call call))))

(defn- with-step-end [iter {:keys [result error]}]
  (when iter (-> iter (assoc :results [{:tool-result (str result) :tool-error? (boolean error)}]) (dissoc :progress))))

(defn- sub-say! [{:keys [subs next-id max-lines]} id entry]
  (swap! subs update-in [id :lines]
         (fn [ls] (let [ls (conj (vec ls) (assoc entry :id (swap! next-id inc)))]
                    (if (> (count ls) max-lines) (subvec ls (- (count ls) max-lines)) ls)))))

(defn- sub-freeze! [{:keys [subs] :as c} id]
  (doseq [entry (block-lines (get-in @subs [id :iter]))] (sub-say! c id entry))
  (swap! subs update id #(when % (assoc % :iter nil)))
  nil)

(defn- sub-start!
  "A child turn begins — a dispatch or a follow-up ask: its buffer, made if it
  is new, says the question, the way the root's says what was typed."
  [{:keys [subs viewing] :as c} {:keys [id agent question owner]}]
  (swap! subs (fn [m]
                ;; what the agent page lists of it (byj-gly.298.2): who
                ;; dispatched it, what it was first asked, how many turns
                (let [m (update m id #(cond-> (-> (or % {:lines []})
                                                  (assoc :agent agent :at (System/currentTimeMillis))
                                                  (update :turns (fnil inc 0)))
                                        owner (assoc :owner owner)
                                        (and (nil? (:question %)) (seq (str question))) (assoc :question (str question))))]
                  (if (<= (count m) max-subagent-buffers)
                    m
                    (let [oldest (->> (dissoc m id @viewing) (sort-by (comp :at val)) ffirst)]
                      (dissoc m oldest))))))
  (when (seq (str question)) (doseq [e (you-entries question)] (sub-say! c id e))))

(defn- sub-end!
  "A child turn ended: its block freezes and its answer lands, in its buffer."
  [{:keys [subs] :as c} {:keys [id answer terminated-by]}]
  (when (get @subs id)
    (sub-freeze! c id)
    (swap! subs update id assoc :live nil :answer answer :at (System/currentTimeMillis)
           :success? (trajectory/succeeded? (str answer) terminated-by))
    (when-not (str/blank? (str answer))
      (sub-say! c id {:kind :answer :text ""})
      (doseq [entry (answer-entries answer)] (sub-say! c id entry)))))

(declare pretty-results sized)

(defn- sub-event!
  "One of a child turn's own events, into the child's buffer."
  [{:keys [subs] :as c} id event data]
  (case event
    :agent.llm/chunk (swap! subs update-in [id :live] grow-live data)
    ;; the reply is in: the model call is over, and the streamed reasoning
    ;; becomes the block's thought
    :agent.think/reasoning (do (swap! (:busy c) dissoc :think)
                               (swap! subs update id #(when % (-> % (update :iter with-thought (:reasoning data))
                                                                  (update :live drop-reasoning)))))
    ;; the thought was written under the CLI's refusal: replaced (byj-gly.339)
    :agent.tools/recovered (swap! subs update id #(when % (update % :iter with-thought (:thought data))))
    :agent.iteration/start (do (sub-freeze! c id)
                               (swap! subs update-in [id :live] drop-reasoning)
                               (swap! subs assoc-in [id :iter] {:n (:iteration data) :calls [] :results []}))
    :agent.tree/step-start (do (sub-freeze! c id) (swap! subs assoc-in [id :iter] (step-iter data)))
    :agent.tree/step-progress (swap! subs update-in [id :iter] with-progress data)
    :agent.tool/output (swap! subs update-in [id :iter] with-output data)
    :agent.tree/step-end (swap! subs update-in [id :iter] with-step-end data)
    :agent.tools/calls (swap! subs update-in [id :iter] #(assoc (or % {:n 1}) :calls (vec (:tool-calls data))))
    :agent.tools/results (swap! subs update-in [id :iter] #(assoc (or % {:n 1}) :results (pretty-results (sized (:results data)))))
    :permission/classified (swap! subs update-in [id :iter] with-verdict data)
    :agent.context/budgeted
    (when (seq (:compactions data))
      (sub-say! c id {:kind :info :text (str "context compacted · " (:total-tokens data) " → " (:after-tokens data) " tokens")})))
  nil)

(defn- one-line
  "A tool result as the one line there is room for — cut in cells, not
  characters, so a wide glyph is never left half-drawn."
  [result width]
  (let [t (str/trim (or (first (str/split-lines (str result))) ""))]
    (if (> (text/width t) width) (str (text/truncate t width) "…") t)))

(def ^:private arg-chars
  "The longest a tool call's argument is shown in its header."
  200)

(defn- text-arg?
  "An argument that is text of its own — a bash script, a file's content: it
  has lines, and is shown under the call rather than in it (byj-gly.240)."
  [v]
  (and (string? v) (str/includes? (str/trim v) "\n")))

(defn- call-summary
  "A tool call as `name(key=value, key=value)`: every argument, named. Only
  the values once showed — `list-tools(false)` — and the call that asked for
  `:detail true` looked like the one that did not. A string is shown bare; a
  value of more than one line is `key=↓`, its lines under the call
  (`arg-entries`), where the first of them and `…` once hid the script that
  ran; a value longer than `arg-chars` is cut, since a row that wraps is fine
  but a file's whole content in the header is not."
  [{:keys [tool-name tool-args]}]
  (let [value (fn [v]
                (if (text-arg? v)
                  "↓"
                  (let [head (str/trim (str (first (str/split-lines (if (string? v) v (pr-str v))))))
                        cut? (> (count head) arg-chars)]
                    (str (if cut? (subs head 0 arg-chars) head) (when cut? " …")))))]
    (str tool-name "("
         (str/join ", " (for [[k v] tool-args] (str (if (keyword? k) (name k) k) "=" (value v))))
         ")")))

(def result-rows
  "How many lines of a tool's output the transcript shows under its call. The
  rest are counted: what a person wants from a result in passing is how it
  began, and the whole of it is in the trajectory (RFC-025)."
  3)

(def ^:private bullet
  "The mark in front of a tool call and an answer — a turn reads as a column of
  them, each thing it did or said one bullet."
  "⏺ ")

(def ^:private predictor-bullet
  "The mark in front of a workflow's predictor step (`:predictor/run`): a
  model call the tree makes with a signature of its own, which a person read
  as a tool the model had called when it wore a tool's bullet (byj-aro)."
  "◇ ")

(def ^:private margin
  "What a line under a bullet is indented by, so it reads as that bullet's."
  {:text "  "})

(def ^:private elbow {:text "⎿  " :style {:dim true}})

(defn- thought-entry
  "A thought as a transcript entry: behind a bullet, as brainyard's quiet
  render puts one (`● …`), in the thought's own dim colour, a wrapped line
  under it (byj-gly.281)."
  [text]
  {:kind :reason :text text :margin margin :bullet {:text bullet}})

(defn- text-block?
  "A string that is text rather than a value: it has lines of its own."
  [x]
  (and (string? x) (str/includes? x "\n")))

(def ^:private short-chars
  "A `name: value` this short shares a row with the next."
  40)

(def ^:private inline-chars
  "The longest a collection inside a map result stays on its key's line; a
  longer one is laid out under it."
  80)

(defn- lay-out
  "A value read back from a result, as rows. A map is brainyard's
  `render-kv-body`: `name: value`, a string bare, text — a file's `:content`,
  a command's `:output` — as its own lines under its name, never one escaped
  string (`\\n`, `\\\"`) no one can read. What is short comes first and text
  last, and the short values share rows — `status: completed · exit-code: 0`
  — so the few rows a result shows in passing say what happened AND how the
  output starts: one per key, they were all three rows, and the text was
  never seen without a click (byj-gly.240). Anything else is `pprint`'s
  layout, or JSON's for a JSON result."
  [v json?]
  (let [show (fn [x] (if json? (json/write-str x) (pr-str x)))
        laid (fn [x] (if json? (json/pretty-str x) (str/trimr (with-out-str (pprint/pprint x)))))
        under (fn [t] (str/join "\n" (map #(str "  " %) (str/split-lines t))))]
    (if (and (map? v) (seq v))
      (let [parts (for [[k x] (sort-by (fn [[_ x]] (cond (text-block? x) 2 (coll? x) 1 :else 0)) v)
                        :let [label (str (if (keyword? k) (name k) k) ":")]]
                    (cond
                      (text-block? x) (str label "\n" (under (str/trimr x)))
                      (string? x) (str label " " x)
                      (and (coll? x) (> (count (show x)) inline-chars)) (str label "\n" (under (laid x)))
                      :else (str label " " (show x))))
            short? #(and (not (str/includes? % "\n")) (<= (count %) short-chars))
            [shorts others] [(filter short? parts) (remove short? parts)]
            ;; the short ones packed into rows, in their order
            packed (reduce (fn [rows p]
                             (let [row (peek rows)]
                               (if (and row (<= (+ (count row) 3 (count p)) inline-chars))
                                 (conj (pop rows) (str row " · " p))
                                 (conj rows p))))
                           [] shorts)]
        (str/join "\n" (concat packed others)))
      (laid v))))

(defn reflow
  "A collection's printed text broken into lines WITHOUT reading it, for text
  that no longer reads back: a result cut short by its cap, or the first few
  hundred characters of one that a resumed transcript kept. A line ends after
  each comma of the outer collection and of a map directly inside it, and
  between the elements of the outer collection, each line indented by its
  depth — the shape pprint gives a list of maps — so the first rows say what the
  result was. Strings are left whole: a comma inside one is text."
  [s]
  (let [indent #(apply str (repeat % " "))
        {:keys [out]}
        (reduce
         (fn [{:keys [out open str? esc? brk?] :as st} ch]
           (cond
             str? (assoc st :out (conj out ch) :esc? (and (= ch \\) (not esc?))
                         :str? (not (and (= ch \") (not esc?))))
             (and brk? (or (Character/isWhitespace ch) (= ch \,)))
             (cond-> st (= ch \,) (update :out conj ch))
             :else
             (let [out (if brk? (conj out (str "\n" (indent (count open)))) out)
                   st (assoc st :brk? false :out out)]
               (cond
                 (= ch \") (assoc st :out (conj out ch) :str? true :esc? false)
                 (#{\[ \{ \(} ch) (assoc st :out (conj out ch) :open (conj open ch))
                 ;; closing an element of the outer collection: the next one
                 ;; starts a line, whether a comma or a space comes between
                 (#{\] \} \)} ch) (let [open (if (seq open) (pop open) open)]
                                     (assoc st :out (conj out ch) :open open :brk? (= 1 (count open))))
                 (and (= ch \,) (or (= 1 (count open))
                                    (and (= 2 (count open)) (= \{ (peek open)))))
                 (assoc st :out (conj out ch) :brk? true)
                 :else (assoc st :out (conj out ch))))))
         {:out [] :open [] :str? false :esc? false :brk? false}
         (seq s))]
    (apply str out)))

(defn pretty-result
  "A tool result laid out for reading. The kernel prints every result into one
  string (`pr-str` of whatever the tool returned), so a map arrives as one long
  line that the transcript cuts at the margin. A result that is ONE line and
  reads whole as a JSON or EDN map or vector is laid out (`lay-out`); one that
  starts like one but was cut short — by its cap, or by what a resumed
  transcript keeps — is broken into lines by its punctuation (`reflow`); prose
  and a file's own lines are left exactly as they came. Only a single line: a file read of an `.edn` is already
  laid out, comments and all, and reading it back would lose them."
  [s]
  (let [s (str s)
        t (str/trim s)]
    (or (when (and (not (str/includes? t "\n")) (#{\{ \[} (first t)))
          (or
           ;; JSON first: `{"a":1}` also reads as EDN, as a map to `:1`
           (when (#{\" \[ \{} (first (str/triml (subs t 1))))
             (try (let [v (json/read-str t)]
                    (when (coll? v) (lay-out v true)))
                  (catch Throwable _ nil)))
           ;; the whole string, or nothing: wrapped in a vector, one form
           ;; means nothing trailed it (a `…[truncated]` marker does)
           (try (let [forms (edn/read-string (str "[" t "]"))]
                  (when (and (= 1 (count forms)) (coll? (first forms)))
                    (lay-out (first forms) false)))
                (catch Throwable _ nil))
           ;; it does not read back — cut short — so it is broken into lines
           ;; by its punctuation instead, which needs only the start of it
           (reflow t)))
        s)))

(defn- pretty-results
  "Results with each one laid out by `pretty-result` — done once, as they
  arrive, since the live block is laid out again on every frame."
  [results]
  (mapv #(cond-> % (string? (:tool-result %)) (update :tool-result pretty-result)) results))

(defn- sized
  "Results as they arrive, each with its size in `:chars` — counted before it
  is laid out, and only here: a transcript read back holds the start of one."
  [results]
  (mapv #(cond-> % (string? (:tool-result %)) (assoc :chars (count (:tool-result %)))) results))

(defn- result-entries
  "What a call returned, as the rows under it: its first `result-rows` lines
  behind an elbow, and a count of the rest. Each line is one row, cut at the
  margin rather than wrapped (`:clip`): a result is often one long line — an
  agent's EDN map, minified JSON — and wrapped, three lines were a dozen rows
  of someone else's transcript (byj-gly.139)."
  [result error?]
  (let [lines (->> (str/split-lines (str/trim (str result))) (remove str/blank?) vec)
        shown (take result-rows lines)
        kind (if error? :error :trace)]
    (concat
     (if (seq shown)
       (map-indexed (fn [i l]
                      {:kind kind :text l :margin margin :clip true
                       :lead (if (zero? i) elbow {:text "   "}) :hang {:text "   "}})
                    shown)
       [{:kind :trace :text "(no output)" :margin margin :lead elbow :hang {:text "   "}}])
     (when (> (count lines) result-rows)
       ;; the rest, folded: a click shows it, ctrl-o hands it to $EDITOR
       ;; (byj-gly.109). It carries the whole result so the fold can open
       ;; without asking the kernel for anything
       [{:kind :fold :text (str/trim (str result)) :skip result-rows
         :more (- (count lines) result-rows)
         :margin margin :lead {:text "   "} :hang {:text "   "}}]))))

(def shown-rows
  "How many lines of a block the screen shows before folding the rest: a
  diff's header and first hunk, about (byj-gly.381)."
  12)

(defn- diff-kind [l]
  (cond (re-find #"^(\+\+\+|---|diff |index |commit |Author:|Date:)" l) :trace
        (str/starts-with? l "@@") :heading
        (str/starts-with? l "+") :added
        (str/starts-with? l "-") :removed
        :else :answer))

(defn block-entries
  "`text` as the transcript shows a block it was handed: a title, the first
  `shown-rows` lines — a diff's coloured as one — and the rest folded, as a
  long result is (byj-gly.109), a click or ctrl-o away."
  [title text]
  (let [lines (->> (str/split-lines (str text)) (remove str/blank?) vec)]
    (concat
     [{:kind :answer :text ""}
      {:kind :call :text (str title) :margin margin :bullet {:text bullet :style {:dim true}}}]
     (if (empty? lines)
       [{:kind :trace :text "(nothing to show)" :margin margin :lead elbow :hang {:text "   "}}]
       (map-indexed (fn [i l] {:kind (diff-kind l) :text l :margin margin :clip true
                               :lead (if (zero? i) elbow {:text "   "}) :hang {:text "   "}})
                    (take shown-rows lines)))
     (when (> (count lines) shown-rows)
       [{:kind :fold :text (str/join "\n" lines) :skip shown-rows
         :more (- (count lines) shown-rows)
         :margin margin :lead {:text "   "} :hang {:text "   "}}]))))

(def fold-rows
  "How much of a result an open fold shows before it says the rest is one
  ctrl-o away. The transcript is for reading in passing, and a 16000-character
  result is not."
  200)

(defn- fold-text
  "What a fold says: how much is hidden, or — open — the hidden lines and how
  to close it again."
  [{:keys [text skip more open?]}]
  (if-not open?
    (str "… +" more (if (= 1 more) " line" " lines") " · click to show them")
    (let [rest-of (->> (str/split-lines (str text)) (remove str/blank?) (drop skip))]
      (str/join "\n" (concat (take fold-rows rest-of)
                             (when (> (count rest-of) fold-rows)
                               [(str "… +" (- (count rest-of) fold-rows) " more")])
                             ["▴ click to fold · ctrl-o opens it in $EDITOR"])))))

(defn child-of
  "The subagent a tool call started or asked, if it did: an `agent$…` result
  names its instance as `:subagent-id`, and `agent-registry$ask` names it in its
  arguments. What a click on that call opens (byj-gly.138)."
  [{:keys [tool-name tool-args]} result]
  (let [n (str tool-name)]
    (cond
      (= "agent-registry$ask" n) (some-> (:id tool-args) str)
      (str/starts-with? n "agent$")
      ;; as the kernel printed it, or as the transcript laid it out
      (let [t (str (:tool-result result))]
        (or (second (re-find #":subagent-id \"([^\"]+)\"" t))
            (second (re-find #"(?:^|\n|· )subagent-id: (\S+)" t)))))))

(defn ask-line
  "What a line typed while subagent `viewing`'s transcript is on screen is
  sent as: the person's own `agent-registry$ask` of that subagent, so its
  work lands in the transcript they are watching rather than in a root turn
  (byj-gly.344). A command — `/…` or a direct call `:…` — stays the root's,
  as does every line while the root's transcript is showing: nil then."
  [viewing line]
  (when (and viewing (not (re-find #"^\s*[/:]" (str line))))
    (str ":agent-registry$ask " (pr-str {:id (str viewing) :question (str line)}))))

(def ^:private arg-lead {:text "┊  " :style {:dim true}})

(defn- arg-entries
  "A call's text arguments as rows under it, the way a result is shown: the
  first `result-rows` lines and a fold for the rest — brainyard boxes them as
  a `Call` section (byj-gly.240). Named on their first row only when there is
  more than one."
  [{:keys [tool-args]}]
  (let [texts (filter (comp text-arg? val) tool-args)
        named? (> (count texts) 1)]
    (mapcat (fn [[k v]]
              (let [lines (->> (str/split-lines (str/trim v)) (remove str/blank?) vec)
                    lines (cond-> lines named? (update 0 #(str (name k) ": " %)))]
                (concat
                 (map-indexed (fn [i l]
                                {:kind :code :text l :margin margin :clip true
                                 :lead (if (zero? i) arg-lead {:text "   "}) :hang {:text "   "}})
                              (take result-rows lines))
                 (when (> (count lines) result-rows)
                   [{:kind :fold :as :code :text (str/join "\n" lines) :skip result-rows
                     :more (- (count lines) result-rows)
                     :margin margin :lead {:text "   "} :hang {:text "   "}}]))))
            texts)))

(defn call-time-str
  "How long a call took, as brainyard's iteration block says it: 340ms, 1.2s,
  2m 5s."
  [ms]
  (cond (< ms 1000) (str ms "ms")
        (< ms 60000) (format "%.1fs" (/ ms 1000.0))
        :else (str (quot ms 60000) "m " (quot (mod ms 60000) 1000) "s")))

(defn chars-str
  "A result's size: 812 chars, 1.2k chars."
  [n]
  (if (< n 1000) (str n " chars") (format "%.1fk chars" (/ n 1000.0))))

(defn- call-note
  "What a finished call's line says after it: how long it took and how much
  it returned — brainyard's `done, 0.3s, 1.2k chars`, the outcome being the
  bullet's colour here (byj-gly.240). nil while it runs, and for a call read
  back from a transcript, which kept neither."
  [r]
  (let [parts (cond-> []
                (:elapsed-ms r) (conj (call-time-str (:elapsed-ms r)))
                (:chars r) (conj (chars-str (:chars r))))]
    (when (seq parts)
      {:text (str " · " (str/join " · " parts)) :style {:dim true :bold false}})))

(defn block-lines
  "The live block as transcript entries: what this iteration is doing, one
  bullet per call with the start of its output under it. It is shown while the
  iteration runs and FROZEN into the transcript when the next one starts, which
  is brainyard's sticky-block behaviour (`tui/renderer.md`) minus the
  redraw-in-place — a turn that has finished still says which tools it ran, and
  without this an unverbose session shows nothing at all while it works.

  No header per iteration: the calls say what the turn did, and a numbered rule
  above every one of them is furniture between a person and that.

  The iteration's thought goes above its calls, dim, and stays when the block
  freezes: it says what the last result told the model and why these calls
  (RFC-003 'The thought'). Streamed, it used to vanish once the reply landed.
  An iteration that called nothing shows none: that thought comes before an
  answer, and it says the result is in whatever it is asked to say
  (byj-gly.280.3)."
  [{:keys [calls results thought verdicts progress progress-call]}]
  (vec
   (concat
    (when (and (seq calls) (not (str/blank? (str thought))))
      [{:kind :answer :text ""} (thought-entry (str/trim (str thought)))])
    (mapcat (fn [i call]
             (let [r (nth results i nil)
                   error? (:tool-error? r)]
               (concat
                [{:kind :answer :text ""}
                 {:kind :call :text (call-summary call) :margin margin
                  :after (call-note r)
                  :opens (child-of call r)
                  :bullet {:text (if (= :predictor (:step-kind call)) predictor-bullet bullet)
                           :style (cond error? {:color :red}
                                        (some? r) {:color :green}
                                        :else {:dim true})}}]
                (arg-entries call)
                ;; what auto mode decided about it, under the call (byj-gly.285.2)
                (some-> (get verdicts i) verdict-entry vector)
                (when r (result-entries (:tool-result r) error?))
                ;; a running step's newest progress, under it (byj-gly.299);
                ;; a tool's, under the call that printed it (byj-gly.364)
                (when (and (nil? r) (seq progress) (or (nil? progress-call) (= i progress-call)))
                  (map-indexed (fn [j l] {:kind :trace :text l :margin margin :clip true
                                          :lead (if (zero? j) elbow {:text "   "}) :hang {:text "   "}})
                               progress)))))
           (range)
           calls))))

(defn answer-entries
  "An answer as transcript entries: its markdown, under one bullet, every row
  indented to sit beside it."
  [text]
  (let [entries (mapv #(assoc % :margin margin) (markdown text))
        first-line (or (first (keep-indexed (fn [i e] (when-not (str/blank? (:text e)) i)) entries)) 0)]
    (cond-> entries
      (seq entries) (assoc-in [first-line :bullet] {:text bullet}))))

(defn- said-entries
  "One transcript line of the kernel's (RFC-016) as the entries it was first
  shown as."
  [[kind text]]
  (case kind
    :you (you-entries text)
    :answer (cons {:kind :answer :text ""} (answer-entries text))
    ;; a tool iteration's thought, above its calls, as it was live (byj-gly.280.4)
    :thought [{:kind :answer :text ""} (thought-entry (str text))]
    :recap [{:kind :answer :text ""}
            {:kind :recap :text (str text) :lead {:text "※ "} :hang {:text "  "}}]
    ;; a tool call, drawn as it was live: the call, and its result
    :tool (block-lines {:calls [text] :results (pretty-results [text])})
    (when (some? text) [{:kind kind :text (str text)}])))

(defn resumed-entries
  "What a resumed session puts back on the screen: its transcript's lines as
  they were first shown, and the air under them — but only the newest `keep`
  entries, the window the transcript holds anyway (`:max-lines`).

  Built from the NEWEST line back, stopping once the window is full. A long
  session's transcript is thousands of entries — 350 lines of one ran to
  ~3,000 — and replaying them all built, laid out and then dropped every one
  that fell out of the window: 0.3–0.9 s of a resume, where the rest of it
  costs nothing measurable."
  [transcript keep]
  (when (seq transcript)
    (loop [lines (rseq (vec transcript)) acc [{:kind :answer :text ""}]]
      (if (or (empty? lines) (>= (count acc) keep))
        (vec (take-last keep acc))
        (recur (next lines) (into (vec (said-entries (first lines))) acc))))))

(def ^:private read-inline
  "The kinds read for inline markup — what the model wrote as prose. A tool
  line, a trace line or a line inside a fenced block means its own characters,
  backticks and asterisks included."
  #{:answer :heading :quote})

(defn- spaces [n] (apply str (repeat (max 0 n) " ")))

(defn- coalesce
  "Neighbouring runs of one style as one run: a row costs a widget per run
  (`byj-3dw`), and a gutter in front of plain code is no reason for two."
  [row]
  (reduce (fn [acc run]
            (let [prev (peek acc)]
              (if (and prev (= (:style prev) (:style run)))
                (conj (pop acc) (update prev :text str (:text run)))
                (conj acc run))))
          []
          row))

(defn- fit-widths
  "Column widths that fit in `avail` cells: each column its natural width if
  they all fit, and otherwise the widest cut down first, never below three —
  a narrow column of numbers keeps its numbers while the prose beside it wraps."
  [natural avail]
  (if (<= (reduce + natural) avail)
    natural
    (let [cap (loop [c (apply max natural)]
                (if (or (<= c 3) (<= (reduce + (map #(min % c) natural)) avail))
                  c
                  (recur (dec c))))]
      (mapv #(min % cap) natural))))

(defn- table-rows
  "A pipe table laid out for `width`: cells padded to their column and wrapped
  inside it, the header bold, a rule under it. brainyard's renderer leaves a
  table as the bars the model wrote, which is legible only when every row is
  the same length and nothing wraps."
  [{:keys [rows align]} width]
  (let [ncol (apply max 1 (map count rows))
        parsed (mapv (fn [row] (mapv #(spans (get row % "")) (range ncol))) rows)
        sep {:text " │ " :style {:dim true}}
        widths (fit-widths (vec (for [c (range ncol)]
                                  (apply max 1 (map #(word-width (nth % c)) parsed))))
                           (- width (* (count (:text sep)) (dec ncol))))
        cell (fn [runs c r]
               (let [gap (- (nth widths c) (word-width runs))]
                 (case (nth align c :left)
                   :right (into [{:text (spaces gap)}] runs)
                   :center (-> [{:text (spaces (quot gap 2))}]
                               (into runs)
                               (conj {:text (spaces (- gap (quot gap 2)))}))
                   ;; the last column needs no padding to reach anything
                   (cond-> runs (< c (dec ncol)) (conj {:text (spaces gap)})))))
        lay-out (fn [cells header?]
                  (let [wrapped (vec (map-indexed (fn [c runs]
                                                    (let [runs (cond->> runs
                                                                 header? (mapv #(update % :style merge {:bold true})))]
                                                      (or (seq (wrap-runs runs (nth widths c))) [[]])))
                                                  cells))
                        height (apply max (map count wrapped))]
                    (for [r (range height)]
                      [(coalesce (vec (apply concat (interpose [sep]
                                                               (for [c (range ncol)]
                                                                 (cell (vec (nth (nth wrapped c) r [])) c r))))))
                       :answer])))]
    (vec (concat (lay-out (first parsed) true)
                 [[[{:text (str/join "─┼─" (map #(apply str (repeat % "─")) widths))}] :rule]]
                 (mapcat #(lay-out % false) (rest parsed))))))

(defn- block-rows
  "One transcript entry as `[[runs kind] …]`, wrapped to `width` inside its own
  margin: a `:lead` in front of the first row and a `:hang` in front of the
  rest, so a wrapped list item stays under its bullet."
  [{:keys [kind text lead hang clip after] :as entry} width]
  (case kind
    :table (table-rows entry width)
    :hr [[[{:text (apply str (repeat (max 3 width) "─"))}] :rule]]
    ;; drawn as the entry it folds — a result's rest dim, a typed line's rest
    ;; on its band
    :fold (block-rows (assoc entry :kind (or (:as entry) :trace) :text (fold-text entry)) width)
    (let [lw (if lead (text/width (:text lead)) 0)
          body (cond
                 ;; a blank line is a paragraph break and keeps its row: spans
                 ;; of nothing are no runs, and no runs would swallow it
                 (str/blank? (str text)) [[{:text ""}]]
                 (read-inline kind) (wrap-spans (spans text) (- width lw))
                 ;; a styled tail — a call's time and size — wraps with the
                 ;; text it follows
                 after (wrap-runs [{:text text} after] (- width lw))
                 ;; one row, whatever it holds: the end of it is marked, not lost
                 clip (let [room (max 2 (- width lw))
                            t (first (str/split-lines (str text)))]
                        [[{:text (if (> (text/width t) room)
                                   (str (text/truncate t (dec room)) "…")
                                   t)}]])
                 :else (mapv (fn [l] [{:text l}]) (wrap text (- width lw))))
          rows (if lead
                 (map-indexed (fn [i row] (into [(if (zero? i) lead (or hang {:text (spaces lw)}))] row))
                              body)
                 body)]
      (mapv (fn [row] [(coalesce row) kind]) rows))))

(defn- entry-rows
  "`block-rows`, inside the entry's `:margin` — and with its `:bullet` in place
  of the margin on the first row, where it has one. The margin is outside
  everything else, so a list item in an answer hangs under its own text and
  the whole of it under the answer's bullet."
  [{:keys [margin bullet] :as entry} width]
  (if-not margin
    (block-rows entry width)
    (vec (map-indexed (fn [i [row kind]]
                        [(coalesce (into [(if (and bullet (zero? i)) bullet margin)] row)) kind])
                      (block-rows (dissoc entry :margin :bullet)
                                  (- width (text/width (:text margin))))))))

(defn- visible
  "The `rows` rows ending `offset` above the bottom, as `[[runs kind] …]`
  top-down.

  Walked from the END and stopped as soon as the screen is full, so what a
  frame costs is the screen rather than the history — layout measures every
  node that is mounted, whether or not it is in view (RFC-028 rule 3)."
  ([entries width rows offset] (visible entries width rows offset entry-rows))
  ([entries width rows offset rows-of]
   (let [need (+ rows offset)
         bottom-up (loop [i (dec (count entries)) acc []]
                     (if (or (neg? i) (>= (count acc) need))
                       acc
                       (recur (dec i)
                              (into acc (reverse (rows-of (nth entries i) width))))))]
     (->> bottom-up (drop offset) (take rows) reverse vec))))

(defn rows-cache
  "`entry-rows`, remembered per transcript entry and width. An entry in the
  transcript never changes once it is said, so parsing its markdown and
  wrapping it again on every frame is work done for nothing — and a frame that
  pages back through a long session was doing it for every row it passed
  (byj-gly.132). An entry is keyed by the `:id` `say!` gave it; what is still
  live (the streaming reply, the block in flight) has none and is laid out
  fresh. A width change forgets everything, and the cache is cut back to the
  transcript's own window when it outgrows it.

  A plain atom, deliberately: a reactive cell read while rendering would make
  every frame depend on it."
  [lines max-lines]
  (let [cache (atom {:w nil :rows {}})]
    (fn [entry width]
      (if-let [id (:id entry)]
        (let [{:keys [w rows]} @cache]
          (or (and (= w width) (get rows id))
              (let [laid (entry-rows entry width)]
                (swap! cache (fn [{cw :w crows :rows}]
                               (let [crows (if (= cw width) crows {})
                                     crows (if (> (count crows) (* 2 max-lines))
                                             (select-keys crows (keep :id @lines))
                                             crows)]
                                 {:w width :rows (assoc crows id laid)})))
                laid)))
        (entry-rows entry width)))))

;; --- the input line's own widget ----------------------------------------------------
;;
;; glimmer-tui marks a FOCUSED widget by painting it in reverse video
;; (`widget/style`), which is right for a button or a list row: they are focused
;; only sometimes, and the block of inverse says which one has the keys. The
;; input line is focused always — it is the only thing here that takes them — so
;; the same rule inverts the whole bottom row for the whole session, and the
;; placeholder with it. A prompt is not a selection.
;;
;; The cursor already says where the keys go, so `:plain-entry` is the built-in
;; entry with the reverse dropped at paint time, through a screen that strips
;; the attribute on its way past. The widget registry is open by design and the
;; rest of the spec — editing, the caret, the key bindings — is the built-in's,
;; unchanged.

(defn- unreversed
  "`screen` with the reverse-video attribute removed from whatever is painted
  onto it. The same shape as `screen/clip`: a map with one function replaced."
  [screen]
  (assoc screen :put! (fn [x y s style] (scr/put! screen x y s (dissoc style :reverse)))))

(defn- ncurses-key?
  "Whether `e` is one of ncurses' own key codes (KEY_MIN 0401 to KEY_MAX 0777)
  that glimmer has no name for and decodes as a character: shift+→ was
  KEY_SRIGHT 402, which the field typed as ƒ (byj-gly.400). glimmer-tui
  v0.2.14 names the shifted cursor and editing keys (402, 393, …) with
  `:mods`, and a modified key's escape run too (glimmer-tui#22, byj-gly.418);
  the rest of the range still arrives as characters. A character a person
  types never has such a code — a UTF-8 one arrives as its lead byte, 0xC2 to
  0xF4 — so this is a key, never text."
  [e]
  (and (= :char (:type e)) (integer? (:code e)) (<= 257 (:code e) 511)))

(def ^:private passed-through
  "Keys the input line lets go of, though glimmer-tui's entry would take them.
  ctrl-f is the entry's forward-char, which the right arrow also does, and it
  is brainyard's find (byj-gly.110); a key the focused widget claims never
  reaches the container that binds it."
  ["ctrl+f"])

(defonce ^:private plain-entry-registered
  (let [spec (w/spec-for :entry)]
    (w/register-widget! :plain-entry
                        (assoc spec
                               :paint (fn [screen rect props n ctx]
                                        ((:paint spec) (unreversed screen) rect props n ctx))
                               :key (fn [wid e ctx]
                                      (if (or (k/match? e passed-through)
                                              (ncurses-key? e)
                                              ;; ← and → on an empty line move between the
                                              ;; agent page and the eval page (byj-gly.288.38.2)
                                              (and (seq (get-in @wid [:props :pass-when-empty]))
                                                   (k/match? e (get-in @wid [:props :pass-when-empty]))
                                                   (str/blank? (get-in @wid [:state :value]))))
                                        false
                                        ((:key spec) wid e ctx)))))
    true))

;; --- a field of several lines (byj-gly.154) -------------------------------------------
;;
;; glimmer-tui has one text field, and it is one line: a message of several lines
;; was a row of `⏎` marks that could only be edited at its end. `:text-area` holds
;; real newlines, soft-wraps them by cells to the width the view gives it, grows up
;; to `input-max-rows` and then scrolls to keep the caret in sight. The bindings are
;; the entry's (readline's), line-wise, with Up and Down moving between rows — and
;; letting go of the key on the first and the last row, so the container walks the
;; history as it did, and steers a completion menu whenever one is up.

(def input-max-rows
  "How tall the input grows before it scrolls. Enough to see what you are
  writing; a paste longer than this is a marker anyway."
  8)

(defn- clusters-of [s] (text/clusters (str s)))

(defn visual-rows
  "`v` as the rows a field `width` cells wide shows: `[[start end] …]`, indices
  into `v`, one logical line (between newlines) wrapped by cells into as many
  rows as it needs. An empty line is one empty row."
  [v width]
  (let [v (str v) width (max 1 width)]
    (loop [line-start 0 acc []]
      (let [nl (str/index-of v "\n" line-start)
            line-end (or nl (count v))
            rows (loop [cs (clusters-of (subs v line-start line-end))
                        i line-start row-start line-start w 0 rs []]
                   (if-let [c (first cs)]
                     (let [cw (text/width c)]
                       (if (and (> (+ w cw) width) (> i row-start))
                         (recur cs i i 0 (conj rs [row-start i]))
                         (recur (rest cs) (+ i (count c)) row-start (+ w cw) rs)))
                     (conj rs [row-start line-end])))
            acc (into acc rows)]
        (if nl (recur (inc nl) acc) acc)))))

(defn- row-of-index
  "Which row of `rows` the caret at `c` is on: the last one starting at or
  before it."
  [rows c]
  (or (last (keep-indexed (fn [r [start _]] (when (<= start c) r)) rows)) 0))

(defn- index-at-column
  "The index in row `[start end]` of `v` nearest `col` cells from its left."
  [v [start end] col]
  (loop [cs (clusters-of (subs v start end)) i start w 0]
    (if-let [c (first cs)]
      (let [cw (text/width c)]
        (if (> (+ w cw) col) i (recur (rest cs) (+ i (count c)) (+ w cw))))
      end)))

(defn- area-window
  "The first row shown: the caret's row kept inside `input-max-rows`."
  [_rows caret-row]
  (max 0 (- caret-row (dec input-max-rows))))

(defn input-rows
  "How many rows the input takes for `draft` at `width`: its wrapped rows, at
  least one, at most `input-max-rows`."
  [draft width]
  (max 1 (min input-max-rows (count (visual-rows draft width)))))

(defn- area-value [state] (str (:value state "")))
(defn- area-caret [state] (min (:cursor state (count (area-value state))) (count (area-value state))))

(defn- area-edit [wid v c]
  (let [{:keys [props state]} @wid
        changed? (not= v (area-value state))]
    (swap! wid assoc :state (assoc state :value v :cursor (max 0 (min c (count v)))))
    (w/touch!)
    (when (and changed? (:on-change props)) ((:on-change props) v))
    true))

(defn- input-area
  "The mounted line field's widget, or nil."
  []
  (when-let [root (:root @@#'tui/app)]
    (first (filter #(= :text-area (:tag @%)) (tree-seq #(seq (:children @%)) #(:children @%) root)))))

(defn- area-move [wid c] (swap! wid assoc-in [:state :cursor] c) (w/touch!) true)

(defn- line-bounds
  "The logical line around index `c`: `[start end]`, without its newline."
  [v c]
  [(if-let [i (str/last-index-of v "\n" (max 0 (dec c)))] (if (< i c) (inc i) 0) 0)
   (or (str/index-of v "\n" c) (count v))])

(defn- insert-text
  "Text put in the field at the caret, line breaks as `\\n`; true either way —
  an empty clipboard pastes nothing."
  [wid t]
  (let [t (str/replace (str t) #"\r\n?" "\n")]
    (if (empty? t)
      true
      (let [v (area-value (:state @wid)) c (area-caret (:state @wid))]
        (area-edit wid (str (subs v 0 c) t (subs v c)) (+ c (count t)))))))

(defn- area-key
  "One key into the field: handled (true), or let go for the container (false)."
  [wid e]
  (let [{:keys [props state]} @wid
        v (area-value state)
        c (area-caret state)
        width (max 1 (:wrap-width props 40))
        rows (visual-rows v width)
        r (row-of-index rows c)
        col (text/width (subs v (first (nth rows r)) c))
        [ls le] (line-bounds v c)
        ;; clamped to the text: ctrl-k and delete at the very end asked for the
        ;; character after the last, and an empty line threw
        cut (fn [from to]
              (let [from (max 0 (min from (count v))) to (max 0 (min to (count v)))]
                (if (< from to) (area-edit wid (str (subs v 0 from) (subs v to)) from) true)))
        word-back (fn [i] (let [i (loop [i i] (if (and (pos? i) (#{\space \newline} (nth v (dec i)))) (recur (dec i)) i))]
                            (loop [i i] (if (and (pos? i) (not (#{\space \newline} (nth v (dec i))))) (recur (dec i)) i))))
        word-fwd (fn [i] (let [n (count v)
                               i (loop [i i] (if (and (< i n) (#{\space \newline} (nth v i))) (recur (inc i)) i))]
                           (loop [i i] (if (and (< i n) (not (#{\space \newline} (nth v i)))) (recur (inc i)) i))))]
    (cond
      (:hold? props) false
      (k/match? e passed-through) false
      ;; a paste: the terminal's own (cmd+v, bracketed), or ctrl+v read from
      ;; the clipboard — inserted at the caret, unless the container folds
      ;; pastes into markers itself (the session's prompt: `:pass-paste?`)
      (= :paste (:type e))
      (if (:pass-paste? props) false (insert-text wid (:text e)))
      (k/match? e "ctrl+v")
      (if (:pass-paste? props) false (insert-text wid (:text (clipboard/read-text))))
      (k/match? e ["alt+enter" "alt+return"]) (area-edit wid (str (subs v 0 c) "\n" (subs v c)) (inc c))
      ;; Up and Down move between rows, and let go at the edges — or whenever a
      ;; menu is up, which is the thing in front of you
      (k/match? e "up") (if (or (:menu? props) (zero? r)) false
                            (area-move wid (index-at-column v (nth rows (dec r)) col)))
      (k/match? e "down") (if (or (:menu? props) (= r (dec (count rows)))) false
                              (area-move wid (index-at-column v (nth rows (inc r)) col)))
      ;; ← on an empty line is the session list's, when there is one
      (and (k/match? e "left") (empty? v) (:agents? props)) false
      ;; ← and → in an empty line are a picker's, which folds with them
      (and (k/match? e ["left" "right"]) (empty? v) (:picker? props)) false
      (k/match? e "left") (area-move wid (max 0 (dec c)))
      ;; in an empty field, → takes the suggestion the placeholder is showing
      (and (k/match? e "right") (empty? v) (seq (:suggestion props)))
      (area-edit wid (:suggestion props) (count (:suggestion props)))
      (k/match? e "right") (area-move wid (min (count v) (inc c)))
      (k/match? e ["home" "ctrl+a"]) (area-move wid ls)
      (k/match? e ["end" "ctrl+e"]) (area-move wid le)
      (k/match? e "alt+b") (area-move wid (word-back c))
      (k/match? e "alt+f") (area-move wid (word-fwd c))
      (k/match? e "backspace") (cut (dec c) c)
      (k/match? e ["delete" "ctrl+d"]) (cut c (inc c))
      (k/match? e ["ctrl+w" "alt+backspace"]) (cut (word-back c) c)
      (k/match? e "alt+d") (cut c (word-fwd c))
      (k/match? e "ctrl+u") (cut ls c)
      (k/match? e "ctrl+k") (if (= c le) (cut c (inc c)) (cut c le))
      ;; a key, not text: let go, typed nowhere (byj-gly.400). A modified key
      ;; glimmer decodes, shift+→ as {:type :right :mods #{:shift}}, matches
      ;; none of the bindings above and is not printable: let go too
      (ncurses-key? e) false
      (k/printable? e) (area-edit wid (str (subs v 0 c) (k/char-of e) (subs v c)) (inc c))
      :else false)))

(def ^:private text-area-spec
  {:container :none
   :measure (fn [props state]
              {:w 1 :h (input-rows (area-value state) (:wrap-width props 40))})
   :init-state (fn [props] (let [t (str (or (:text props) ""))] {:value t :cursor (count t)}))
   :sync-state (fn [props state]
                 (let [incoming (:text props)]
                   (if (and (some? incoming) (not= (str incoming) (:value state)))
                     {:value (str incoming)
                      :cursor (if (:caret-start? props) 0 (count (str incoming)))}
                     state)))
   :paint (fn [screen rect props n _]
            (let [v (area-value (:state n))
                  {:keys [x y w h]} rect]
              (if (and (empty? v) (:placeholder props))
                (scr/put! screen x y (text/pad (str (:placeholder props)) w) {:dim true})
                (let [rows (visual-rows v w)
                      top (area-window rows (row-of-index rows (area-caret (:state n))))]
                  (doseq [[i [start end]] (map-indexed vector (take h (drop top rows)))]
                    (scr/put! screen x (+ y i) (text/pad (subs v start end) w) {}))))))
   :focusable? (fn [_] true)
   ;; the caret, shown; while a rail has the keys (:hold?), the caret still,
   ;; hidden there — [x y false], glimmer-tui v0.2.13 — so a terminal that
   ;; draws a hidden cursor draws it where typing resumes, not on a rail's
   ;; separator or the status line where painting ended (byj-gly.401)
   :cursor (fn [rect props state]
             (let [v (area-value state)
                   c (area-caret state)
                   rows (visual-rows v (:w rect))
                   r (row-of-index rows c)
                   top (area-window rows r)
                   at [(+ (:x rect) (text/width (subs v (first (nth rows r)) c))) (+ (:y rect) (- r top))]]
               (if (:hold? props) (conj at false) at)))
   :activate (fn [wid] (when-let [f (:on-activate (:props @wid))] (f)))
   :key (fn [wid e _] (boolean (area-key wid e)))})

(defonce ^:private text-area-registered
  (do (w/register-widget! :text-area text-area-spec) true))

(jffi/defcfn ^:private clearok "clearok" [:pointer :int] :int)

(defn external-editor!
  "Hand the terminal to `$VISUAL`, `$EDITOR` or `less` with `text` in a file,
  and take it back when it exits (byj-gly.109). brainyard's display blocks hand
  a collapsed body to the editor the same way.

  ncurses' own sequence: `endwin` gives the terminal back as it was, the next
  refresh takes it again, and `clearok` makes that refresh draw every cell —
  the editor left its own screen behind, and a diff against ours would keep it.
  This blocks the loop thread for as long as the editor runs, which is the one
  place RFC-028 rule 2 is broken on purpose: the terminal belongs to the
  editor meanwhile, and the kernel's writes wait in the cells until it returns.
  With no terminal to hand over — headless — it does nothing."
  [text]
  (when (:win @@#'tui/app)
    (let [f (java.io.File/createTempFile "brainyard-output-" ".txt")]
      (spit f (str text))
      (try (hand-terminal! (links/editor-argv (links/editor (System/getenv)) (.getPath f) nil nil))
           (finally (.delete f))))
    nil))

(def ^:private blank-alternate-screen
  "The alternate screen, entered and cleared. ncurses' endwin leaves it, and so
  does a program handed the terminal as it exits — `tmux attach` on ctrl-b d —
  and between the two the terminal shows its NORMAL screen: whatever was
  printed there before the TUI started, a transcript, for as long as the
  repaint takes (about 0.3 s, measured in tmux). Sent at both moments, a blank
  screen shows instead."
  "\033[?1049h\033[H\033[2J")

(defn- hand-terminal!
  "Run `argv` with the terminal, and take it back when it exits — what
  `external-editor!`, a clicked file and a Claude Code session (byj-gly.287.3)
  do. `env`, when given, is the whole environment it runs in. With no terminal
  to hand over — headless — it does nothing."
  [argv & [env]]
  (when-let [win (:win @@#'tui/app)]
      ;; the editor gets the terminal as a terminal: mouse reports and
      ;; bracketed paste left on sent less and vi SGR sequences as keys and
      ;; pasted 200~/201~ into the file (byj-gly.208)
      (try (print (first (handover))) (flush) (catch Throwable _ nil))
      (ffi/endwin)
      ;; not the normal screen while the program starts (byj-gly.305)
      (try (print blank-alternate-screen) (flush) (catch Throwable _ nil))
      (try
      (apply fx/sh (cond-> {:in :inherit :out :inherit :err :inherit} env (assoc :env env)) argv)
        (catch Throwable _ nil)
        (finally
          ;; nor once it has gone, until the repaint: first, before anything
          (try (print blank-alternate-screen) (flush) (catch Throwable _ nil))
          (try (some-> (second (handover)) print) (flush) (catch Throwable _ nil))
          (clearok win 1)
        (w/touch!)))))

(defonce ^:private loop-thread
  ;; the render loop's thread, recorded by a timer as the loop starts
  (atom nil))

(defn hand-over!
  "`hand-terminal!` for a caller outside this namespace: run `argv` with the
  terminal in `env` (nil: this one), and take it back when it is left.

  The program must have the terminal to itself, so it runs ON the render loop's
  thread, which stops reading keys and painting until it exits — as ctrl-o's
  editor does from a key. A caller on another thread — the session's, running
  `/memory edit` or `/login` — posts it there and waits: run from the
  session's thread, the loop went on painting over vim and reading the keys
  meant for it (byj-gly.327, seen live)."
  [argv env]
  (let [t @loop-thread]
    (if (and t @b/loop-running? (not= t (Thread/currentThread)))
      (let [p (promise)]
        (tui/after! 0 #(deliver p (try {:v (hand-terminal! argv env)} (catch Throwable e {:e e}))))
        ;; however long the program runs — but not past a loop that has quit
        ;; and will never run it
        (let [{:keys [v e]} (loop []
                              (let [r (deref p 500 ::waiting)]
                                (if (and (= ::waiting r) @b/loop-running?) (recur) (when (map? r) r))))]
          (when e (throw e))
          v))
      (hand-terminal! argv env))))

(defn open-link!
  "Open a clicked target (`brainyard.links`, byj-gly.270): a URL in the
  browser, a file in `$VISUAL`/`$EDITOR` at its line and column, the terminal
  handed over while it runs. Returns what to say on the footer, or nil when the
  click opened nothing — a path that resolves to no file."
  [{:keys [kind text path line col]} base-dir]
  (case kind
    :url (when (links/open-url! text) (str "opened " text))
    :file (when-let [f (links/resolve-file path base-dir)]
            (hand-terminal! (links/editor-argv (links/editor (System/getenv)) f line col))
            (str "opened " f (when line (str ":" line))))
    nil))

;; --- the view ----------------------------------------------------------------------

(def menu-rows
  "How many completions are shown at once. More than this and the list stops
  being something you can read at a glance; the rest are counted, not hidden."
  6)

(defn command-completions
  "What a half-typed `/word` could still become: `{:items [[text doc] …] :from
  index}`, where `from` is where in the line the replacement starts. Nothing
  for a line that is not a command, and nothing once it is one exactly — a menu
  under a finished command is noise.

  This is the renderer's only completer of its own, and it completes data it
  was handed (`:commands`). What ELSE a line could become — a path, a model —
  is the caller's to say, because knowing which files exist is not a renderer's
  business (RFC-001)."
  [commands draft]
  (let [d (str/trim (str draft))]
    (when (and (str/starts-with? d "/") (not (some #(= d (first %)) commands)))
      (when-let [items (seq (for [[cmd doc] commands :when (str/starts-with? cmd d)] [cmd doc]))]
        {:items (vec items) :from 0}))))

(defn- enum-values
  "An enum argument's values, from the type `schema/inputs` writes for one:
  `\"project\"|\"user\"` → [\"project\" \"user\"]; nil for any other."
  [type]
  (when (and (string? type) (str/starts-with? type "\""))
    (not-empty (mapv second (re-seq #"\"([^\"]*)\"" type)))))

(defn tool-completions
  "What a half-typed tool line could still become, from the rows `/tools`
  builds (`toolshelf/rows`, handed over through `:present :tool-rows`) —
  brainyard completes a tool's name after `/` and `:` and stops at the first
  space (its tui-menu-design §4 TODO: argument completion); this goes on
  (byj-gly.273.4):

  - `/gr` → the tools whose id starts so, after the commands, `/grep`;
  - `:gr` → the same, as `:grep`;
  - `:grep ` → the arguments not yet given, required first, as `:pattern`,
    each with its type and description; `:grep :pa` → those starting so;
  - `:scope ` after an enum argument → its values.

  `{:items [[text doc] …] :from i}` as `command-completions`, or nil."
  [rows draft]
  (let [d (str draft)]
    (or
    (when-let [[_ sigil word] (re-matches #"([/:])([^\s]*)" d)]
      (when-let [items (seq (for [{:keys [id description]} rows
                                  :when (and (str/starts-with? id word) (not= id word))]
                              [(str sigil id) (first (str/split-lines (str description)))]))]
        {:items (vec items) :from 0}))
    (when-let [[_ id rest] (re-matches #":([A-Za-z_][\w$.\-]*)\s+(.*)" d)]
      (when-let [{:keys [inputs]} (first (filter #(= id (:id %)) rows))]
        (let [tokens (toolshelf/tokenize rest)
              typing? (not (re-find #"\s$" d))
              done (if typing? (butlast tokens) tokens)
              ;; the word being typed is a key when an even number of words
              ;; come before it: key, value, key, value, key
              at-key? (even? (count done))
              given (set (map #(keyword (str/replace % #"^:" "")) (take-nth 2 done)))
              partial (if typing? (str (last tokens)) "")
              from (- (count d) (count partial))]
          (if at-key?
            (when-let [items (seq (for [{:keys [name type optional? desc]} (sort-by :optional? inputs)
                                        :let [k (str ":" (clojure.core/name name))]
                                        :when (and (not (given name))
                                                   (str/starts-with? k (if (str/starts-with? partial ":") partial (str ":" partial))))]
                                    [k (str type (when optional? " (optional)") (when desc (str " — " desc)))]))]
              {:items (vec items) :from from})
            (let [k (keyword (str/replace (str (last done)) #"^:" ""))
                  vals (enum-values (:type (first (filter #(= k (:name %)) inputs))))]
              (when-let [items (seq (for [v vals :when (str/starts-with? v partial)] [v ""]))]
                {:items (vec items) :from from})))))))))

(defn- merge-completions
  "Two completers' menus as one: items from the same place are listed
  together, the first's first; otherwise the first that has any."
  [a b]
  (cond (and a b (= (:from a) (:from b))) (update a :items into (:items b))
        :else (or a b)))

(defn accept-completion
  "`draft` with the completion at `from` replaced by `text`, and a space after
  it — a completion you have to type a space after is half a completion."
  [draft {:keys [from]} text]
  (str (subs (str draft) 0 (min from (count (str draft)))) text " "))

(defn- menu-height
  "Rows the completion menu is taking, including the line that counts the rest."
  [menu]
  (let [n (count (:items menu))]
    (if (zero? n) 0 (+ (min n menu-rows) (if (> n menu-rows) 1 0)))))

;; --- the picker: /config's editor and /model's list --------------------------------
;;
;; brainyard opens a picker over the prompt for `/model` and lists `/config` and
;; `/feature` as text. Here both are one picker, a mode of the input line like
;; ctrl-f: what is typed filters it, the arrows move its mark, Enter picks and
;; Esc puts the draft back. It takes its rows from the transcript, above the
;; input, as the menu does. The session hands it the rows (`:present`) and it
;; answers with the line a person would have typed — `/config recap? false`,
;; `/model anthropic/claude-sonnet-5` — so what a pick MEANS stays the command's.

(defn picker-window-rows
  "How many of a picker's lines are shown at once on a screen `h` rows high."
  [h]
  (max 4 (min 16 (- h chrome-rows 10))))

(defn- picker-matches?
  "Does every word of `query` appear in the item, any case?"
  [item query]
  (let [hay (str/lower-case (str (:group item) " " (:label item) " " (:value item) " " (:doc item)))]
    (every? #(str/includes? hay %) (str/split (str/lower-case (str/trim (str query))) #"\s+"))))

(defn- label-first
  "`xs` with the items whose label holds `query` before the rest, and flat —
  ungrouped — since a heading would then come round twice. What a picker with
  `:rank-label?` lists while it is filtered: `/tools` finds a tool by its name
  before a description that mentions the word (byj-gly.273.1; brainyard
  dropped description matching from its menu for being noisy)."
  [xs query]
  (let [q (str/lower-case (str/trim (str query)))
        named? #(str/includes? (str/lower-case (str (:label %))) q)]
    (map #(dissoc % :group) (concat (filter named? xs) (remove named? xs)))))

(defn picker-lines
  "What an open picker lists for its query: `[:group name]` before each group
  and `[:item item i]` for each item that matches, `i` counting those items."
  [{:keys [items query open rank-label?]}]
  (loop [xs (cond-> (filter #(and (picker-matches? % query)
                          ;; an item under a fold shows while its fold is open —
                          ;; or while a filter is typed, so a search still finds it
                          (or (nil? (:parent %)) (not (str/blank? (str query)))
                              (contains? (set open) (:parent %))))
                    items)
              (and rank-label? (not (str/blank? (str query)))) (label-first query))
         i 0 group ::none out []]
    (if-let [it (first xs)]
      (recur (rest xs) (inc i) (:group it)
             (cond-> out
               (and (not= group (:group it)) (:group it)) (conj [:group (:group it)])
               :always (conj [:item it i])))
      out)))

(defn picker-selected
  "The item under the picker's mark, or nil when nothing matches."
  [p]
  (some (fn [[kind it i]] (when (and (= :item kind) (= i (:at p))) it)) (picker-lines p)))

(defn picker-move
  "The picker with its mark moved `step` items, clamped, and its window moved
  just enough to keep the mark in view — with its group's heading, when the
  mark is the group's first item."
  [p step h]
  (let [lines (picker-lines p)
        n (count (filter #(= :item (first %)) lines))
        at (if (pos? n) (max 0 (min (dec n) (+ (or (:at p) 0) step))) 0)
        li (or (first (keep-indexed (fn [j [kind _ i]] (when (and (= :item kind) (= i at)) j)) lines)) 0)
        rows (picker-window-rows h)
        top (or (:top p) 0)
        want (if (and (pos? li) (= :group (first (nth lines (dec li))))) (dec li) li)
        top (cond (< want top) want
                  (>= li (+ top rows)) (- li (dec rows))
                  :else top)]
    (assoc p :at at :top (max 0 (min top (- (count lines) rows))))))

(def registry-pause-ms
  "How long typing in the /mcp picker must pause before the registry is asked —
  long enough that a word being typed is not searched letter by letter."
  700)

(def tool-pane-min-width
  "How wide the screen must be for `/tools`' info pane to sit beside the list."
  110)

(def tool-pane-narrow-rows
  "How many lines of the info pane show under the list on a narrow screen,
  its \"… /tools NAME\" line included."
  10)

(defn tool-pane-rows
  "How many lines `/tools`' info pane may take, its \"…\" line included:
  beside the list, as many as the list at its tallest — its title, its window
  and the line that counts the rest — else `tool-pane-narrow-rows`."
  [h wide?]
  (if wide? (+ 2 (picker-window-rows h)) tool-pane-narrow-rows))

(defn picker-detail-max-rows
  "The most lines picker `p` says about its marked item on a screen `h` rows
  high, the last cut with `…`: what is left under its title, the rows its list
  takes and its count, once the chrome and three rows of transcript are kept —
  at least 2, at most 10."
  [p h]
  (max 2 (min 10 (- h chrome-rows (min (count (picker-lines p)) (picker-window-rows h)) 2 3))))

(defn- picker-col
  "The widest label among the items `p` lists — what a row gives its label
  before the value, so what is left for the value."
  [p]
  (reduce max 0 (for [[kind it] (picker-lines p) :when (= :item kind)] (text/width (str (:label it))))))

(defn picker-detail
  "What a picker says under its list about item `it`, `w` wide, as
  `[{:text :style}]`: its value whole when its row cuts it (a row leaves the
  value what `col`, the widest label, does not take), then its doc, then its
  note — each wrapped rather than cut at the edge (byj-gly.283), at most
  `max-rows`."
  [it w col max-rows]
  (when it
    (let [width (max 8 (- w 4))
          v (str (:value it))
          lines (concat
                 (when (> (text/width v) (max 8 (- w col 9)))
                   (for [l (wrap v width)] {:text l :style {}}))
                 (for [l (wrap (str (:doc it)) width) :when (not (str/blank? l))] {:text l :style {:dim true}})
                 (for [l (wrap (str (:note it)) width) :when (not (str/blank? l))]
                   {:text l :style (if (:warn? it) {:color :red} {:dim true})}))
          n max-rows]
      (if (> (count lines) n)
        (conj (vec (take (dec n) lines))
              (update (nth lines (dec n)) :text #(str (text/truncate % (- width 2)) " …")))
        (vec lines)))))

(defonce ^:private detail-rows-cache (atom nil))

(defn- picker-detail-rows
  "The rows a picker's detail is given: as many as the tallest among what it
  lists now, so the list does not jump as the mark moves — remembered while
  the items, the filter and the width stay as they were."
  [p w h]
  (let [{:keys [items query open]} p
        c @detail-rows-cache]
    (if (and c (identical? items (:items c)) (= [query open w h] (:key c)))
      (:rows c)
      (let [col (picker-col p)
            rows (reduce max 0 (for [[kind it] (picker-lines p) :when (= :item kind)]
                                 (count (picker-detail it w col (picker-detail-max-rows p h)))))]
        (reset! detail-rows-cache {:items items :key [query open w h] :rows rows})
        rows))))

(defn picker-height
  "Rows an open picker takes: its title, its window, the line that counts the
  rest, and the detail about the marked item (`picker-detail-rows`). `/tools` has an info pane
  instead of the detail, beside the list or under it, and keeps its full
  height while a filter shortens the list: the rows were reserved for the list
  alone, the pane beside two matches ran past them, and a long transcript then
  pushed the input line off the screen (byj-gly.275)."
  [p h w]
  (cond
    (not p) 0
    (= :tools (:kind p)) (let [list-rows (+ 2 (picker-window-rows h))]
                           (if (>= w tool-pane-min-width)
                             (max list-rows (tool-pane-rows h true))
                             (+ list-rows (tool-pane-rows h false))))
    :else (+ 1 (max 1 (min (count (picker-lines p)) (picker-window-rows h))) 1 (picker-detail-rows p w h))))

(defn config-items
  "The session's config rows as picker items, grouped by family: a switch is
  `●`/`○` and Enter flips it, anything else Enter puts in the input to edit."
  [rows]
  (vec (for [{:keys [key value default source doc type family changed? writable? why-not shown edit enum]} rows
             :let [switch? (= "boolean" type)]]
         {:id key :group (some-> family name) :label (name key) :type type
          :on? (when switch? (boolean value))
          :value (if switch? (if value "on" "off") shown)
          :edit edit
          ;; a key with named values steps through them, as a switch flips
          :cycle (when (seq enum)
                   (let [i (or (first (keep-indexed #(when (= value %2) %1) enum)) -1)]
                     (nth enum (mod (inc i) (count enum)))))
          :changed? changed?
          :writable? writable?
          :doc doc
          :warn? (not writable?)
          :note (if writable?
                  (str (if changed? (str "set in " (name source) " · default " (pr-str default)) "the default")
                       " · enter " (cond switch? "flips it"
                                         (seq enum) (str "makes it " (name (let [i (or (first (keep-indexed #(when (= value %2) %1) enum)) -1)]
                                                                            (nth enum (mod (inc i) (count enum))))))
                                         :else "edits it")
                       (when changed? " · ctrl-r resets it"))
                  (str "not writable: " why-not))})))

(declare mcp-servers-items)

(defn tools-items
  "`/tools`' rows (`toolshelf/rows`) as picker items: a tool a line under its
  family, its description's first line beside its id, the row itself carried
  for the info pane (byj-gly.273.1)."
  [rows]
  (vec (for [{:keys [id family description] :as row} rows]
         {:id id :label id :group family :row row
          :value (first (str/split-lines (str description)))})))

(defn tool-pane-lines
  "The info pane of the marked tool, `width` columns wide: its spec
  (`toolshelf/spec-lines`), each line wrapped."
  [row width]
  (vec (mapcat #(if (str/blank? %) [""] (wrap % width)) (toolshelf/spec-lines row))))

(defn mcp-items
  "The MCP servers as picker items, a group each: the server first — Enter
  starts or stops it, ctrl-r restarts it, ctrl-x removes it — then its tools,
  where Enter hides or shows one. Then the catalogue's entries not yet added,
  where Enter adds one. Each item carries the `/mcp` line a pick sends."
  ([rows] (mcp-items rows nil nil))
  ([rows catalog] (mcp-items rows catalog nil))
  ([rows catalog registry]
   (into (into (mcp-servers-items rows)
         (for [{:keys [name description missing]} catalog]
           {:id (str "catalog:" name) :group "catalogue — enter adds" :label name :value (str description)
            :doc (str "/mcp add " name)
            :note (if (seq missing)
                    (str "needs " (str/join ", " missing) " — enter adds it disabled until then")
                    "enter adds it to the project config and starts it")
            :send (str "/mcp add " name)}))
         ;; what the registry had for the filter typed: third-party, so added
         ;; disabled, by its full name
         (for [{:keys [name registry-name version description kind unsupported configured? missing]} registry]
           (cond-> {:id (str "registry:" registry-name) :group "MCP registry — third-party, added disabled"
                    :label name :value (str description)
                    :doc (str registry-name (when version (str "@" version)) (when kind (str " · " kind)))
                    :warn? (boolean unsupported)
                    :note (cond unsupported (str "cannot start here: " unsupported)
                                configured? "already added"
                                (seq missing) (str "needs " (str/join ", " missing) " — enter adds it disabled")
                                :else "enter adds it, disabled until you enable it")}
             (not (or unsupported configured?)) (assoc :send (str "/mcp add " registry-name)))))))

(defn- mcp-servers-items
  [rows]
  (vec (mapcat
        (fn [{:keys [server state command problem tools]}]
          (let [running? (= :running state)
                group (str server " · " (name state) (when running? (str " · " (count tools) " tool(s)")))]
            (cons {:id server :group group :label server :value (name state)
                   :on? running?
                   ;; its tools fold under it: → opens, ← closes
                   :fold? (boolean (seq tools))
                   :doc (str command)
                   :warn? (boolean problem)
                   :note (or problem
                             (str "enter " (if running? "stops it" "starts it")
                                  (when running? " · ctrl-r restarts it")))
                   :send (str "/mcp " server " " (if running? "stop" "start"))
                   :reset (str "/mcp " server " restart")
                   :remove (str "/mcp remove " server)}
                  (for [{:keys [id tool description hidden?]} tools]
                    {:id id :group group :label tool :value (str description)
                     :parent server
                     ;; a tool is a line under its server, not a peer of it
                     :bullet (if hidden? "  ○ " "  - ")
                     :on? (not hidden?)
                     :doc id
                     :note (str "enter " (if hidden? "shows it to the model" "hides it from the model")
                                " · :hidden-tools in the project config")
                     :send (str "/mcp " server " " (if hidden? "show" "hide") " " tool)}))))
        rows)))

(defn model-items
  "The curated models as picker items, grouped by provider, the one in use marked."
  [{:keys [current models]}]
  (vec (for [{:keys [provider model description]} models
             :let [id (str (name provider) "/" model)]]
         {:id id :group (name provider) :label model :value (str description)
          :on? (when (= id current) true) :current? (= id current)
          :doc id
          :note (if (= id current) "in use" "enter switches, from the next turn")})))

(defn memory-items
  "The project's memories (`memory/list-memories`) as picker items: the key,
  its first line beside it, and the whole of it under the list (byj-gly.327)."
  [rows]
  (vec (for [{:keys [key content]} rows]
         {:id key :label key :value (first (str/split-lines (str content)))
          :doc (str content)
          :note "enter edits it in $EDITOR · ctrl-x deletes"})))

(def ^:private done-words
  "How a finished turn is said to have gone, one per turn in rotation — the
  spinner's words have the same job while it runs."
  ["Worked" "Baked" "Brewed" "Cooked" "Crafted" "Churned" "Simmered"])

(defn duration-str
  "`ms` as a person says it: 12s, 7m 13s, 1h 4m."
  [ms]
  (let [s (long (Math/round (/ (double (max 0 ms)) 1000.0)))]
    (cond (< ms 1000) "<1s"
          (< s 60) (str s "s")
          (< s 3600) (str (quot s 60) "m " (mod s 60) "s")
          :else (str (quot s 3600) "h " (quot (mod s 3600) 60) "m"))))

(defn clock-str
  "`2026-09-24T18:23:05+0900` as `6:23 PM`, or nil. The kernel reads the clock
  from the OS, because jolt's knows only UTC (provenance P-10); what it looks
  like is the renderer's call."
  [iso]
  (when-let [[_ h m] (re-find #"T(\d{2}):(\d{2})" (str iso))]
    (let [h (parse-long h)
          h12 (let [x (mod h 12)] (if (zero? x) 12 x))]
      (str h12 ":" m " " (if (< h 12) "AM" "PM")))))

(defn done-line
  "What is said under a finished turn's answer: how long it took, and when it
  ended by the person's clock — which, after a long turn, is the question."
  [{:keys [turn elapsed-ms ended-at]}]
  (str "✻ " (nth done-words (mod (or turn 0) (count done-words)))
       (when elapsed-ms (str " for " (duration-str elapsed-ms)))
       (when-let [t (clock-str ended-at)] (str " · done " t))))

(def ^:private think-words
  "What a turn is doing while it waits for a model, in words that change so a
  stalled spinner is distinguishable from a stalled turn. brainyard rotates its
  own list for the same reason."
  ["thinking" "considering" "weighing it up" "reading back" "deciding"])

(def ^:private spinner-glyphs
  "The mark in front of a running turn, turning over with the tick."
  ["·" "✢" "✳" "✶" "✻" "✽" "✻" "✶" "✳" "✢"])

(defn- elapsed-str [since now]
  (let [s (/ (- now since) 1000.0)]
    (if (< s 60) (format "%.1fs" s) (str (long (/ s 60)) "m " (long (mod s 60)) "s"))))

(defn- tokens-str
  "A token count the way a person reads one at a glance: 16.0k, not 16032."
  [n]
  (if (< n 1000) (str n) (format "%.1fk" (/ n 1000.0))))

(defn busy-lines
  "What is in flight, one line each: the turn, the background tasks, the
  subagents. brainyard keeps a sticky block per family above the prompt
  (`tui/renderer.md`); this is the same idea in the space this renderer has — a
  few rows that vanish when nothing is running.

  The turn's line is a turning glyph and a word, with how long the turn has
  been going and what it has written so far: the word says whether it is
  waiting on a model (and which predictor, when one is named) or working
  through what the model asked for.

  `tick` is not read, only passed: it is what makes a frame happen while these
  numbers change, since nothing else about them does."
  ([busy now tick] (busy-lines busy nil now tick))
  ([busy status now tick]
   (let [{:keys [think tasks subagents]} busy
         {:keys [running? since output-tokens turn-output-from]} status
         since (or since (:since think))]
     (vec
      (concat
       (when (or think running?)
         [{:kind :busy
           :text (str (nth spinner-glyphs (mod tick (count spinner-glyphs))) " "
                      (if think
                        (nth think-words (mod (quot tick 8) (count think-words)))
                        "working")
                      "… ("
                      (str/join " · "
                                (remove nil?
                                        [(when since (elapsed-str since now))
                                         (:predictor think)
                                         (let [out (- (or output-tokens 0) (or turn-output-from 0))]
                                           (when (pos? out) (str "↓ " (tokens-str out) " tokens")))
                                         ;; a call it waits on, long enough to be worth moving
                                         (when (:movable status) "ctrl+g runs it in the background")]))
                      ")")}])
       (for [[id {:keys [name since]}] (sort-by key tasks)]
         {:kind :tool :text (str "  ⚙ " (or name id) " · " (elapsed-str since now))})
       (for [[id {:keys [agent since]}] (sort-by key subagents)]
         {:kind :tool :text (str "  ▸ " (or agent id) " · " (elapsed-str since now))
          :opens id}))))))

(defn- busy-height
  "Rows what is in flight takes: its lines, and a blank one above them so they
  stand apart from the transcript rather than reading as its last line. None
  when nothing is running."
  [lines]
  (if (seq lines) (inc (count lines)) 0))

(def background-hint-ms
  "How long a tool call is waited on before the busy line offers ctrl+g:
  long enough that a quick call does not flash it (byj-gly.391)."
  2000)

(defn- busy-view [{:keys [busy status tick backgrounders]} open!]
  (fn []
    (let [now (System/currentTimeMillis)
          ;; a plain atom, read as the spinner's tick repaints this
          movable (count (filter #(>= (- now (:since %)) background-hint-ms) (vals (some-> backgrounders deref))))
          lines (busy-lines @busy (cond-> @status (pos? movable) (assoc :movable movable)) now @tick)]
      (into [:vbox {}]
            (concat
             (when (seq lines) [[:label {:label ""}]])
             (for [{:keys [kind text opens]} lines]
               (if opens
                 ;; a running child: click it to watch its own transcript
                 [:button (assoc (kind-style kind) :label text :brackets ["" ""]
                                 :on-click #(do (open! opens) (give-focus-back!)))]
                 [:label (assoc (kind-style kind) :label text)])))))))

(defn- cells-between
  "The part of `s` from cell `from` up to, not including, cell `to`."
  [s from to]
  (text/truncate (text/drop-cells (str s) (max 0 from)) (max 0 (- to from))))

(defn- ordered
  "A selection's two ends, first in reading order."
  [{:keys [from to]}]
  (let [[[x1 y1] [x2 y2]] [from to]]
    (if (or (< y1 y2) (and (= y1 y2) (<= x1 x2))) [from to] [to from])))

(defn- row-span
  "The cells `[from to)` of screen row `y` a selection covers, or nil."
  [sel y width]
  (let [[[x1 y1] [x2 y2]] (ordered sel)]
    (when (<= y1 y y2)
      [(if (= y y1) x1 0) (if (= y y2) (inc x2) width)])))

(defn selection-text
  "The text a selection covers in the transcript `rows` painted in `rect`: each
  row's text between the selection's ends, what was on the screen, the rows
  joined by newlines and their trailing spaces dropped."
  [rows {:keys [x y w h]} sel]
  (let [top (+ y (- h (count rows)))
        [[_ y1] [_ y2]] (ordered sel)]
    (->> (range (max y1 top) (inc (min y2 (+ top (dec (count rows))))))
         (keep (fn [row-y]
                 (when-let [[c1 c2] (row-span sel row-y (+ x w))]
                   (let [[runs] (nth rows (- row-y top))]
                     (str/trimr (cells-between (apply str (map :text runs)) (- c1 x) (- c2 x)))))))
         (str/join "\n"))))

(declare plain-text mark-runs text-span)

(def ^:private transcript-spec
  "The transcript as ONE widget: every visible row, painted run by run.

  It was a label per row and an hbox of labels wherever a row had more than one
  style, and a repaint costs about half a millisecond per widget (`byj-3dw`) —
  so a screen of tables was a hundred widgets and a frame took 40–70 ms, which
  is slower than the keys that ask for one (byj-gly.132). Here the tree holds
  one node whatever the transcript says, and painting it is a `put!` per run.

  Rows sit on the BOTTOM of the widget, the way a terminal fills: an empty
  session starts at the input line, not the top. Every cell of the rect is
  written, blank or not, because nothing clears the screen between frames."
  {:container :none
   :measure (fn [props _] {:w 0 :h (count (:rows props))})
   :paint (fn [screen rect props _ _]
            (let [{:keys [x y w h]} rect
                  rows (:rows props)
                  top (+ y (- h (count rows)))
                  blank (spaces w)]
              (dotimes [i (max 0 (- h (count rows)))]
                (scr/put! screen x (+ y i) blank {}))
              (doseq [[i [runs kind target]] (map-indexed vector rows)
                      :let [row-y (+ top i)
                            ;; what a click would open, underlined (byj-gly.270)
                            runs (if-let [m (:marks props)] (mark-runs runs (m (plain-text runs))) runs)
                            ;; what a click on it would do is underlined, as
                            ;; brainyard's clickable targets are — its text,
                            ;; not the margin and indent before it (byj-gly.277)
                            runs (if target (mark-runs runs (text-span (plain-text runs))) runs)
                            base (cond-> (kind-style kind)
                                   (= i (:mark props)) (assoc :reverse true))]
                      :when (>= row-y y)]
                (let [end (reduce (fn [col {:keys [text style]}]
                                    (let [t (str text)]
                                      (scr/put! screen col row-y t (w/style (merge base style)))
                                      (+ col (text/width t))))
                                  x
                                  runs)]
                  ;; the rest of the row in its own background, so a band
                  ;; runs the whole width and does not stop where the text does
                  (when (< end (+ x w))
                    (scr/put! screen end row-y (spaces (- (+ x w) end))
                              (select-keys (w/style base) [:bg])))
                  ;; what is selected, drawn over it in reverse
                  (when-let [[c1 c2] (some-> (:selection props) (row-span row-y (+ x w)))]
                    (let [line (str (apply str (map :text runs)) (spaces w))
                          c1 (max x c1) c2 (min (+ x w) c2)]
                      (when (< c1 c2)
                        (scr/put! screen c1 row-y (cells-between line (- c1 x) (- c2 x)) {:reverse true}))))))))})

(defn- give-focus-back!
  "Put the focus back on the input line. A click focuses what it lands on, and
  a transcript or a busy row holding the focus would swallow what is typed next."
  []
  (when-let [id (some #(when (#{:text-area :plain-entry} (:tag %)) (:id %)) (some-> (tui/tree) w/walk))]
    (tui/focus! id)))

(def ^:private clickable-transcript-spec
  (assoc transcript-spec
         ;; focusable only so a click can find it: `click!` hits focusable
         ;; widgets, and the focus goes straight back to the input line
         :focusable? (fn [_] true)
         :click (fn [_ x y {:keys [node]}]
                  (let [{:keys [rect props]} node
                        rows (:rows props)
                        top (+ (:y rect) (- (:h rect) (count rows)))
                        row (nth rows (- y top) nil)
                        opens (nth row 2 nil)]
                    (cond
                      ;; a fold or a child's row: what it does wins, as
                      ;; brainyard's block marker wins over a link
                      (and opens (:on-open props)) ((:on-open props) opens)
                      ;; else a path or a URL under the pointer (byj-gly.270)
                      (and row (:on-link props)) ((:on-link props) row (- x (:x rect))))
                    (give-focus-back!)))))

(defonce ^:private transcript-registered
  (do (w/register-widget! :transcript clickable-transcript-spec) true))

(defn- as-shown
  "An entry as it is drawn now: a fold knows whether it is open (and is cached
  apart from its closed self), and whatever a click on it does rides along as
  `:target` — the child it opens, or the fold it toggles."
  [unfolded {:keys [id kind opens] :as e}]
  (cond
    (and (= :fold kind) id)
    (let [open? (contains? unfolded id)]
      (assoc e :open? open? :id (if open? [id :open] id) :target [:fold id]))
    opens (assoc e :target opens)
    :else e))

(defn- target-rows
  "`rows-of`, with each row remembering what a click on it does, and — as
  metadata, which no comparison sees — the entry it was laid out from, which a
  clicked link is recovered against when the wrap split it (`links/recover-target`).
  An open fold's target is its last row alone, the `▴ click to fold` line: the
  rows it revealed are the result's own text, not a button, and underlined
  whole they read as one (byj-gly.276) — a path or URL in them is a link again."
  [rows-of]
  (fn [entry width]
    (let [t (:target entry)
          rows (rows-of entry width)
          last-i (dec (count rows))
          only-last? (and (= :fold (:kind entry)) (:open? entry))]
      (vec (map-indexed (fn [i r]
                          (with-meta (cond-> r (and t (or (not only-last?) (= i last-i))) (conj t))
                            {::entry entry}))
                        rows)))))

(defn- plain-text [runs] (apply str (map (comp str :text) runs)))

(defn- unwrapped
  "`entry` as the transcript draws it where nothing wraps, one line to a row —
  what a link found on one of its rows is widened against (byj-gly.270)."
  [entry]
  (when entry
    (str/join "\n" (map (comp plain-text first) (entry-rows entry 100000)))))

(defn- text-span
  "Where a row's printable text is, as `[[start end]]` into it — from its first
  character that is not blank to its last — or `[]` for a blank row."
  [plain]
  (let [start (count (take-while #(Character/isWhitespace %) plain))
        end (- (count plain) (count (take-while #(Character/isWhitespace %) (reverse plain))))]
    (if (< start end) [[start end]] [])))

(defn- mark-runs
  "`runs` with the characters `spans` covers — `[start end]` into their joined
  text — underlined, split where a span starts or ends inside a run."
  [runs spans]
  (if (empty? spans)
    runs
    (let [in? (fn [i] (some (fn [[a b]] (and (>= i a) (< i b))) spans))]
      (loop [rs runs, at 0, out []]
        (if-let [{:keys [text style] :as r} (first rs)]
          (let [t (str text)
                n (count t)
                cuts (sort (distinct (concat [0 n] (for [sp spans, c sp
                                                         :let [c (- c at)]
                                                         :when (< 0 c n)]
                                                     c))))
                pieces (for [[a b] (partition 2 1 cuts)]
                         (cond-> (assoc r :text (subs t a b))
                           (in? (+ at a)) (assoc :style (assoc style :underline true))))]
            (recur (rest rs) (+ at n) (into out pieces)))
          out)))))

(defn- link-marks
  "What the transcript underlines on a row, by its plain text: `links/mark-spans`
  against `base-dir`, remembered — rows repaint constantly and a row's text
  never changes, so the scan and the stat() run once a row. The mark is only a
  hint, so a stale one (a file since deleted) costs nothing: the click re-checks.
  Cleared whole when it outgrows 4096 rows."
  [base-dir]
  (let [cache (atom {})]
    (fn [plain]
      (if (str/blank? plain)
        []
        (or (get @cache plain)
            (let [spans (links/mark-spans plain base-dir)]
              (swap! cache #(assoc (if (> (count %) 4096) {} %) plain spans))
              spans))))))

(defn- view-entries
  "What the transcript on the screen is made of: the root's, or the child's
  being looked at, with its live block and its streaming reply. The children
  are read only while one is on screen, so a subagent's work never lays the
  root's transcript out again. Called while rendering and by search alike."
  [{:keys [lines live iter subs viewing unfolded display]}]
  (let [sub (when-let [id @viewing] (get @subs id))
        [lines live iter] (if sub
                            [(:lines sub) (:live sub) (:iter sub)]
                            [@lines @live @iter])
        ;; the answer is read as markdown while it streams, not only once it
        ;; lands — otherwise a reply is punctuation for as long as it takes to
        ;; arrive and changes shape at the end
        entries (cond-> (into lines (when-not (= :quiet @display) (block-lines iter)))
                  live (into (if (= :reasoning (:field live))
                               [(thought-entry (:text live))]
                               (answer-entries (:text live)))))]
    (mapv #(as-shown @unfolded %) entries)))

(defn- transcript-height
  "Rows the transcript has: the screen less the chrome, the menu and what is
  in flight — which take their rows from it, or the input line would be pushed
  off the screen. NOT the tick: it only turns the spinner, and reading it
  re-laid the whole transcript eight times a second (provenance P-19)."
  [{:keys [size menu busy status draft search picker]}]
  (max 1 (- (:h @size) chrome-rows (menu-height @menu) (picker-height @picker (:h @size) (:w @size))
            ;; the input's own rows past the first, while it holds several
            (if @search 0 (dec (input-rows @draft (- (:w @size) 2))))
            (busy-height (busy-lines @busy @status 0 0)))))

;; --- finding a line in the transcript (byj-gly.110) ---------------------------------
;;
;; brainyard's Ctrl-F, as a mode of the input line rather than a loop of its own:
;; a nested read loop would re-implement paste, the mouse and Ctrl-C and drift
;; from them (its own comment). The field holds the query while it is on, a
;; match is a laid-out row, and finding one is moving the offset to it.

(defn- row-text [[runs]] (apply str (map :text runs)))

(defn find-rows
  "The rows of `rows` (top-down) whose text holds `query`, ignoring case, as
  distances from the bottom — the offset's own unit — newest first."
  [rows query]
  (let [q (str/lower-case (str/trim (str query)))
        n (count rows)]
    (if (str/blank? q)
      []
      ;; walked from the bottom, so the newest match comes first
      (vec (for [from-bottom (range n)
                 :when (str/includes? (str/lower-case (row-text (nth rows (- n 1 from-bottom)))) q)]
             from-bottom)))))

(declare max-offset)

(defn- show-hit!
  "Scroll so match `at` sits in the middle of the transcript."
  [{:keys [search offset] :as c}]
  (when-let [k (get (:hits @search) (:at @search))]
    ;; never past the top: a match near it was put mid-screen with blank rows
    ;; above, and pgdn walked back through them (byj-gly.213)
    (reset! offset (min (max-offset c) (max 0 (- k (quot (transcript-height c) 2)))))))

(defn search-for!
  "Find `query` in the transcript on the screen and show the newest match."
  [{:keys [search size rows-of] :as c} query]
  (let [rows (vec (mapcat #(rows-of % (:w @size)) (view-entries c)))]
    (swap! search assoc :query query :hits (find-rows rows query) :at 0)
    (show-hit! c)))

(defn search-step!
  "The next match older (`1`) or newer (`-1`) than the one shown."
  [{:keys [search size rows-of] :as c} step]
  ;; found again first: rows that streamed in since the query moved every
  ;; match, and the old positions named other rows (byj-gly.213)
  (when-let [q (:query @search)]
    (let [rows (vec (mapcat #(rows-of % (:w @size)) (view-entries c)))]
      (swap! search assoc :hits (find-rows rows q))))
  (swap! search (fn [{:keys [hits at] :as s}]
                  (assoc s :at (if (seq hits) (max 0 (min (dec (count hits)) (+ at step))) 0))))
  (show-hit! c))

(defn- rows-back
  "The transcript's rows counted from the bottom, but no further than
  `enough`: what is past it is not laid out. A window of 2,500 entries costs
  ~0.8 s to lay out whole (byj-gly.250), and a scroll needs only to know
  whether there is a page more above it."
  [{:keys [size rows-of] :as c} enough]
  (let [w (:w @size)
        es (view-entries c)]
    (loop [i (dec (count es)) n 0]
      (if (or (neg? i) (>= n enough))
        n
        (recur (dec i) (+ n (count (rows-of (nth es i) w))))))))

(defn max-offset
  "How far back the transcript on the screen can be scrolled: its laid-out
  rows less the rows it is shown in. Every row is laid out once per width
  (`rows-cache`); this lays out all of them, so a scroll asks
  `scroll-offset` instead, which stops at the page it needs."
  [c]
  (max 0 (- (rows-back c Long/MAX_VALUE) (transcript-height c))))

(defn scroll-offset
  "`want` held to what there is to see — never past the top — laying the
  transcript out only as far back as `want` reaches, so paging back through a
  long session lays it out a page at a time."
  [c want]
  (let [want (max 0 want)
        h (transcript-height c)]
    (min want (max 0 (- (rows-back c (+ want h)) h)))))

(defn- transcript-view [{:keys [size offset layouts search rows-of selection viewing] :as cells} open!]
  (let [under (atom nil)
        ;; a path or a URL under a click opens (byj-gly.270): found on the row,
        ;; widened against the row's entry, opened by the :open-link seam
        link! (fn [row col]
                (let [plain (plain-text (first row))
                      t (some->> (links/column->index plain col text/width) (links/detect-at plain))
                      t (links/recover-target t (unwrapped (::entry (meta row))))]
                  (when-let [said (and t ((:open-link cells) t (:working-dir cells)))]
                    (reset! (:flash cells) said))))
        marks (when (:mouse? cells) (link-marks (:working-dir cells)))]
    (fn []
    (swap! layouts inc)
    ;; a selection is screen cells: rows streaming in under it, or another
    ;; transcript shown, would have its release copy other text (byj-gly.213).
    ;; Told apart by the transcript's shape — how many entries, the newest by
    ;; its id and length, the width — not by counting its rows: that laid out
    ;; every entry in the window on every frame, 2,500 of them on a resumed
    ;; session's first one (byj-gly.250)
    (let [es (view-entries cells)
          newest (peek es)
          now [@viewing (:w @size) (count es) (:id newest) (count (str (:text newest)))]]
      (when (and @selection @under (not= now @under)) (reset! selection nil))
      (reset! under now))
    (let [{:keys [w]} @size
          rows (transcript-height cells)
          shown (visible (view-entries cells) w rows @offset rows-of)
          ;; the match being looked at, as a row of what is shown
          mark (when-let [{:keys [hits at]} @search]
                 (when-let [k (get hits at)]
                   (let [i (- (count shown) 1 (- k @offset))]
                     (when (< -1 i (count shown)) i))))]
      [:transcript {:vexpand true :hexpand true :on-open open! :on-link link!
                    :marks marks
                    :rows shown :mark mark :selection @(:selection cells)}]))))

(defn token-count
  "A token count with its unit: 999, 3.14K, 1.12M."
  [n]
  (let [n (or n 0)]
    (cond (< n 1000) (str n)
          (< n 999500) (format "%.2fK" (/ n 1000.0))
          :else (format "%.2fM" (/ n 1000000.0)))))

(defn usage-line
  "What the session has done and spent, as one line: turns, calls, the tokens in
  and out — the session's, then the last call's, which is what says how big the
  context has grown — and the cost where there is one to state."
  [{:keys [turn calls input-tokens output-tokens last-call cost]}]
  (str "turn " turn " · " calls " call" (when (not= 1 calls) "s")
       " · tokens " (token-count input-tokens) "/" (token-count output-tokens)
       (when-let [[in out] last-call]
         (str " (last call: " (token-count in) "/" (token-count out) ")"))
       ;; a priced-at-zero turn is not worth a column: cost/of returns nothing
       ;; for a model no table names, and the mock's table prices everything at
       ;; zero — "$0.0000" reads like a broken counter rather than a free turn
       (when (pos? (or cost 0)) (str " · $" (format "%.4f" (double cost))))))

(defn- usage-view
  "What the session has done and spent, right-aligned above the input, with a
  blank row over it so it stands apart from the last line of the transcript
  (byj-gly.284)."
  [{:keys [status]}]
  (fn []
    [:vbox {}
     [:label {:label ""}]
     [:hbox {}
      [:label {:hexpand true :label ""}]
      ;; a subscription window at 90% or more, from the last snapshot only:
      ;; never a network call from a frame (byj-gly.324)
      (when-let [w (claude-usage/warning)]
        [:label {:label (str "⚠ " w " · ") :color :yellow}])
      ;; and a Codex window, as its rollouts last reported it (byj-gly.430.7)
      (when-let [w (codex-usage/warning)]
        [:label {:label (str "⚠ " w " · ") :color :yellow}])
      [:label {:label (usage-line @status) :dim true}]]]))

(defn mode-label
  "The permission mode as the footer shows it: `[glyph text color hint?]`, or nil
  for no mode. The hint is left off where cycling is not the likely next move —
  a person who chose to be asked each time has chosen."
  [mode]
  (case mode
    nil nil
    :auto ["▸▸" "auto mode on" :yellow true]
    :auto-approve ["▸▸" "auto-approve mode on" :magenta true]
    :ask-each-time ["‖" "ask-each-time mode on" nil false]
    :deny-by-default ["✕" "deny-by-default mode on" :red true]
    ["?" (str (name mode) " mode on") nil true]))

(def help-tips
  "What the footer's right side says when nothing more pressing is on — one at
  a time, a new one every `tip-every-ms`, so the keys and commands are found
  without reading /help. brainyard's `help_tips.clj` `static-tips`, with the
  port's own keys and commands: every tip names something that exists here.
  The first is the one a fresh session shows."
  ["ctrl-c cancels a turn · ctrl-q quits · /help lists commands"
   "alt+enter for a new line · a long paste folds into a marker"
   "↑↓ walk your earlier lines · ctrl-f finds in the transcript"
   "pgup/pgdn or the wheel scroll · drag to select, release to copy"
   "shift+tab cycles the permission mode"
   "/model switches the model · /config edits settings"
   "tab completes a command · → takes the suggestion"
   "/usage for tokens and cost · /capture saves the conversation"
   "/status shows the session, the model and the settings in force"
   "/task lists background work · /todo shows the checklist"
   "ctrl-g sends a long tool call to the background · the turn carries on"
   "/history reviews the chat · /compact makes room · /clear starts over"
   "click a ▸ subagent row for its transcript · esc returns"
   "click a folded result to open it · ctrl-o hands it to $EDITOR"
   "click an underlined path:line or URL to open it"
   "/queue lists what you typed while a turn ran"
   "/config output-style: default, concise or explanatory · /mcp lists servers"])

(def tip-every-ms
  "How long a help tip stays before the next one."
  10000)

(def share-every-ms
  "How often the footer reads byj-share's registry for the shares this
  process started: they start and stop by reference, from another session of
  the run or from byj-share itself, and people join them, none of which this
  session's own presence says."
  3000)

(defn- refresh-shares!
  "Read the shares this process started (`read`, `share/from-here` unless a
  test's) off the render loop, and put them in each of `cells`' `:shares` —
  unless the last read is still going. `reading` is the caller's flag."
  [reading cells & [read]]
  (when (compare-and-set! reading false true)
    (fx/run-detached
     (fx/task-of #(try (let [shares ((or read share/from-here))]
                         (doseq [c cells] (reset! (:shares c) shares)))
                       (catch Throwable _ nil)
                       (finally (reset! reading false))))
     :share-footer)))

(defn- footer-view
  "The row under the input: where the session is on the left, the keys that
  matter on the right — or, scrolled back, how to get back to the bottom.

  No wider than the centre: a box is never narrower than its widest row, so a
  footer wider than the centre pushed a rail beside it off the screen
  (byj-gly.380). A tip is the least of it, and is cut to what the left
  leaves; anything else on the right is what is in front of you, so it stays
  and the left gives way — its hints first, then the mode."
  [{:keys [status offset queue viewing subs search menu flash mode picker tip-at on-agents rail-focus rails rail-views size shares] share-cell :share}]
  (fn []
    (let [{:keys [tasks]} @status
          [glyph text color hint?] (mode-label @mode)
          queued (count @queue)
          ;; this session's share and the others this process started (RFC-043)
          sharing (share/footer (some-> share-cell deref) (some-> shares deref))
          found (when-let [{:keys [query hits at]} @search]
                  (cond (str/blank? query) "type to find · esc done"
                        (empty? hits) (str "no match for \"" query "\" · esc done")
                        :else (str (inc at) "/" (count hits) " · enter/↑ older · ↓ newer · esc done")))
          viewed (when-let [id @viewing] (or (:agent (get @subs id)) id))
          [right right-style]
          (cond
            @flash [@flash {:color :green}]
            (:confirm @picker) [(str "delete " (:confirm @picker) "? y/N") {:color :yellow}]
            @picker [(case (:kind @picker)
                       :config "↑↓ · enter flips or edits · ctrl-r resets · esc closes"
                       :mcp "↑↓ · → opens ← folds · enter starts, stops, hides, shows or adds · ctrl-r restarts · ctrl-x removes"
                       :tools "↑↓ · enter asks the model · tab calls it · esc closes"
                       :memory "↑↓ · type to search · enter edits · ctrl-x deletes · esc closes"
                       "↑↓ · enter switches · esc closes")
                     {:color :cyan}]
            (seq (:items @menu)) ["↑↓ · enter takes · esc closes" {:color :cyan}]
            ;; a rail has the keys (byj-gly.123): what they do there
            @rail-focus [(str "↑↓ · enter · tab sections"
                              ;; the letters that show the rail's views
                              (let [ks (frame/view-keys (get rail-views @rail-focus))]
                                (when (< 1 (count ks))
                                  (str " · " (str/join "/" (map #(str/upper-case (str %)) (remove nil? ks))) " views")))
                              " · ←→ width"
                              ;; a view whose rows can be mentioned says so
                              (when (:put (get (get rail-views @rail-focus) (get-in @rails [@rail-focus :view] 0)))
                                " · @ mentions")
                              " · esc returns")
                         {:color :cyan}]
            found [found {:color :yellow}]
            viewed [(str "▸ " @viewing " · what you type asks it · esc to return") {:color :cyan}]
            (pos? @offset) [(str "↑ " @offset " · pgdn to follow") {:color :yellow}]
            ;; nothing more pressing: a tip, which the loop turns every
            ;; tip-every-ms. How to take a suggestion is said beside the
            ;; suggestion, in the input, as brainyard's placeholder says it
            :else [(nth help-tips (mod @tip-at (count help-tips))) {:dim true ::tip true}])
          tip? (::tip right-style)
          right-style (dissoc right-style ::tip)
          w (:w @size)
          extras (str (when (pos? (or tasks 0)) (str " · " tasks " task" (when (not= 1 tasks) "s")))
                      (when (pos? queued) (str " · " queued " queued"))
                      (some->> sharing (str " · ")))
          mode-w (if text (text/width (str glyph " " text)) 0)
          hints-w (+ (if (and text hint?) (text/width " (shift+tab to cycle)") 0)
                     (if on-agents (text/width " · ← for agents") 0))
          left-w (+ 2 mode-w hints-w (text/width extras))
          ;; the footer is left, hexpand and right in an hbox spaced 2: its
          ;; width is left-w + 4 + right, which must not pass the centre's w
          right (text/truncate right (max 0 (if tip? (- w left-w 4) (- w 6))))
          ;; the left's two-column pad, and the hbox's spacing of 2 between
          ;; each of its three children: 2 short, a mode that just fit made the
          ;; footer 2 columns wider than the centre and pushed a rail off the
          ;; screen (byj-gly.412, seen live)
          room (- w 2 4 (text/width right) (text/width extras))
          mode? (<= mode-w room)
          hints? (<= (+ mode-w hints-w) room)]
      [:hbox {:spacing 2}
       [:hbox {}
        [:label {:label "  "}]
        (when (and text mode?)
          (if color
            [:label {:label (str glyph " " text) :color color}]
            [:label {:label (str glyph " " text) :dim true}]))
        (when (and text hint? hints?) [:label {:label " (shift+tab to cycle)" :dim true}])
        (when (and on-agents hints?) [:label {:label " · ← for agents" :dim true}])
        (when (pos? (or tasks 0))
          [:label {:label (str " · " tasks " task" (when (not= 1 tasks) "s")) :color :yellow}])
        (when (pos? queued)
          [:label {:label (str " · " queued " queued") :color :cyan}])
        ;; how many shares, and who is watching them (RFC-043)
        (when sharing
          [:label {:label (str " · " sharing) :color :magenta}])]
       [:label {:hexpand true :label ""}]
       [:label (merge {:label right} right-style)]])))

(defn- prompt-mark
  "The mark in front of the input line. The session asks with its own prompt
  (`you> `); a prompt is the kernel's word for \"your turn\", and the renderer
  draws that as `❯`. Any other prompt is a question, and is shown as asked."
  [prompt]
  (let [p (str/trim (str prompt))]
    (if (#{"" "you>" "›" ">" "❯"} p) "❯" p)))

(defn menu-window
  "Which items of a menu are on screen: `{:top :shown :above :below}`. The
  window holds `menu-rows` items starting at `:top`, which the arrows move only
  as far as the mark needs, so the list scrolls with the mark instead of the
  mark walking off the bottom of a list that stands still."
  [{:keys [items top]}]
  (let [n (count items)
        top (max 0 (min (or top 0) (- n menu-rows)))]
    {:top top
     :shown (take menu-rows (drop top items))
     :above top
     :below (max 0 (- n top menu-rows))}))

(defn scroll-menu
  "The menu with its mark moved by `step`, and its window moved just enough to
  keep the mark in view."
  [{:keys [items at top] :as m} step]
  (let [at (max 0 (min (dec (count items)) (+ (or at 0) step)))
        top (or top 0)
        top (cond (< at top) at
                  (>= at (+ top menu-rows)) (- at (dec menu-rows))
                  :else top)]
    (assoc m :at at :top top)))

(defn- menu-view
  "What the line could still become, just above the input, so the input line
  never moves when it opens or closes.

  brainyard's autocomplete is a menu you steer with the arrows and it completes
  commands, paths and model names (`autocomplete.clj`); this is that menu, with
  the item sets coming from whoever built the renderer."
  [{:keys [menu]}]
  (fn []
    (let [{:keys [at]} @menu
          {:keys [top shown above below]} (menu-window @menu)
          ;; the docs in one column, so the eye reads down them
          col (reduce max 0 (map #(text/width (str (first %))) shown))]
      ;; the menu is ALWAYS in the tree, empty when there is nothing to say: an
      ;; empty vbox is no rows, and children are matched by position — a row
      ;; that came and went would shift what follows it into its place
      (into [:vbox {}]
            (concat
             ;; the mark is an index into ALL the items, not into the window
             (for [[i [text doc]] (map-indexed vector shown)
                   :let [on? (= (+ top i) at)]]
               [:hbox {:spacing 2}
                [:label {:label (str (if on? "› " "  ") text (spaces (- col (text/width (str text)))))
                         :color (if on? :cyan :default) :bold on?}]
                [:label {:hexpand true :label (str doc) :dim true}]])
             (when (pos? (+ above below))
               [[:label {:label (str "  " (str/join " · " (remove nil? [(when (pos? above) (str "↑ " above " more"))
                                                                         (when (pos? below) (str "↓ " below " more"))])))
                         :dim true}]]))))))

(defn- input-rule-view
  "The rule over the input. While Up and Down are walking what was sent
  before, it says where in the history you are — `── History 53/55 ───` — so a
  recalled line is never mistaken for one being written. And at its right, the
  conversation's name, as the last recap gave it (`── fix-drag-performance ──`)."
  [{:keys [walk history size title]}]
  (fn []
    (let [w (:w @size)
          {:keys [at]} @walk
          n (count @history)
          left (when (and at (< at n)) (str " History " (inc at) "/" n " "))
          right (some->> @title (str " ") (#(str % " ")))
          ;; a name is cut only where it would crowd out the history's place
          ;; (`── History 100/100 ` and a little rule), so the rule stays a rule
          room (max 8 (- w 24))
          right (when right (if (> (text/width right) room)
                              (str (text/truncate right (- room 2)) "… ")
                              right))
          fill #(apply str (repeat (max 0 %) "─"))]
      (if-not (or left right)
        [:separator {}]
        (into [:hbox {}]
              (concat
               [[:label {:label "──" :dim true}]]
               (when left [[:label {:label left :dim true :bold true}]])
               [[:label {:hexpand true :dim true
                         :label (fill (- w 2 (text/width (str left)) (text/width (str right)) (if right 2 0)))}]]
               (when right [[:label {:label right :color :cyan :dim true}]
                            [:label {:label "──" :dim true}]])))))))

(def ^:private suggestion-suffix "  (→ to use)")

(defn suggestion-placeholder
  "The model's suggestion as the empty input line shows it — brainyard's
  `↳ <prompt>  (→ to use)`, how to take it said beside it. The prompt is cut to
  leave room for the suffix, since a line clipped at the right loses the suffix
  first and the → with it."
  [text w]
  (let [room (max 8 (- w 2 2 (text/width suggestion-suffix) 1))
        t (str/replace (str text) #"\s+" " ")]
    (str "↳ " (if (> (text/width t) room) (str (text/truncate t (dec room)) "…") t) suggestion-suffix)))

(defn- input-view
  "The prompt and the line being typed, between two rules — or, while ctrl-f is
  finding, the query."
  [{:keys [draft prompt search size menu walk history suggestion picker on-agents viewing rail-focus]} {:keys [activate! typed!]}]
  (fn []
    (let [finding (some-> @search :query)]
      (if finding
        [:hbox {:spacing 1}
         [:label {:label "find:" :bold true :color :yellow}]
         [:plain-entry {:text finding :hexpand true :autofocus true
                        :placeholder "a word in the transcript"
                        :on-change typed! :on-activate activate!}]]
        ;; the line being written, as many rows as it takes (byj-gly.154)
        [:hbox {:spacing 1}
         [:label {:label (prompt-mark @prompt) :bold true}]
         [:text-area {:text @draft :hexpand true :autofocus true
                      ;; a paste is the container's: a long one folds into a marker
                      :pass-paste? true
                      ;; a rail has the keys: the field lets every one go, and
                      ;; draws no caret (byj-gly.123)
                      :hold? (boolean @rail-focus)
                      ;; a recalled line starts with the caret at its start, so
                      ;; Up keeps walking back rather than into its rows
                      :caret-start? (boolean (some-> @walk :at (< (count @history))))
                      ;; the row is the mark, a space, then the field: a
                      ;; prompt longer than ❯ made the field narrower than it
                      ;; was measured at (byj-gly.213)
                      :wrap-width (- (:w @size) 1 (text/width (prompt-mark @prompt)))
                      ;; the arrows are the picker's too, while it is open
                      :menu? (boolean (or (seq (:items @menu)) @picker))
                      :agents? (boolean (and on-agents (not @picker)))
                      :picker? (boolean @picker)
                      :placeholder (cond @picker "type to filter"
                                         @viewing (str "ask " @viewing " · esc returns to the root")
                                         @suggestion (suggestion-placeholder @suggestion (:w @size))
                                         :else "ask something, or /help")
                      :suggestion (when-not @picker @suggestion)
                      :on-change typed! :on-activate activate!}]]))))

(def ^:private gate-chrome-rows
  "What the permission modal takes beside its question's lines: the frame's
  border and padding, the row between the question and the buttons, the
  buttons, and a row above and below it."
  9)

(defn gate-lines
  "The lines of a permission ask's question that fit a screen `h` rows high:
  all of them, or as many as fit and how many more there are. A publish's
  ask is a line a file (an MCP server's that publishes files), and spaced a row apart, or
  past the screen, they were cut to the first few in a box gone blank."
  [prompt h]
  (let [room (max 2 (- h gate-chrome-rows))
        n (count prompt)]
    (if (<= n room)
      prompt
      (conj (vec (take (dec room) prompt)) (str "… " (- n (dec room)) " more lines")))))

(defn- gate-view
  "The permission ask (RFC-012) as the modal overlay RFC-028 chose glimmer-tui
  for. The question and the answers are the ask channel's own: this renders
  them and hands back the letter a terminal would have read, so what an answer
  MEANS stays in `brainyard.permission` and a config is still only ever written
  by a person pressing a key."
  [{:keys [gate size]} answer!]
  (fn []
    ;; an empty box rather than nil when no ask is up: a component that renders
    ;; nothing renders an EMPTY TREE, and the reconciler has nothing to replace
    ;; the overlay with — the box is what takes its place (and takes no room)
    (if-let [{:keys [prompt choices label focus]} @gate]
      [:overlay {:anchor :center :modal true :on-close #(answer! "n")}
       [:frame {:label (or label "permission") :border :double :padding 1}
        (into [:vbox {:spacing 1}]
              (concat
               ;; the question's lines together, a row between them and the buttons
               [(into [:vbox {}] (for [line (gate-lines prompt (:h @size))] [:label {:label line}]))]
               [(into [:hbox {:spacing 2 :halign :center}]
                      (for [[i [label letter]] (map-indexed vector choices)]
                        [:button {:label label
                                  ;; the ask's safe answer when it names one:
                                  ;; an injected turn's refuse (byj-1wv.11)
                                  :autofocus (= i (or focus 0))
                                  :on-click #(answer! letter)}]))]))]]
      [:vbox {}])))

(defn- picker-view
  "An open picker, above the input: a title that says what it is and what the
  filter matched, the window of its lines, what is above and below it, and two
  lines about the marked item — its doc, and where its value came from or why
  it cannot be written."
  [{:keys [picker size]}]
  (fn []
    (if-let [{:keys [title items at top query] :as p} @picker]
      (let [full (:w @size)
            ;; /tools shows the marked tool beside its list when there is room,
            ;; else under it (byj-gly.273.1)
            pane? (= :tools (:kind p))
            wide? (and pane? (>= full tool-pane-min-width))
            w (if wide? (quot (* full 45) 100) full)
            rows (picker-window-rows (:h @size))
            lines (picker-lines p)
            n (count (filter #(= :item (first %)) lines))
            top (or top 0)
            shown (take rows (drop top lines))
            below (max 0 (- (count lines) top rows))
            sel (picker-selected p)
            ;; the rows on screen set the column, not the longest id in the list
            col (reduce max 0 (for [[kind it] shown :when (= :item kind)] (text/width (str (:label it)))))
            fit #(text/truncate (str %) (max 8 %2))]
        (let [listed
        (into [:vbox {}]
              (concat
               [[:label {:label (str title (cond
                                             (:searching p) (str " · searching the MCP registry for \"" (:searching p) "\"…")
                                             ;; a search that failed says so, rather than look like no hits
                                             (:registry-error p) (str " · " (:registry-error p)))
                                     " · " (if (str/blank? query)
                                                    (str (count items) " · type to filter")
                                                    (str "\"" query "\" · " n " of " (count items))))
                         :color :cyan :bold true}]]
               (if (empty? lines)
                 [[:label {:label "  nothing matches" :dim true}]]
                 (for [[kind it i] shown]
                   (if (= :group kind)
                     [:label {:label (str " " it) :bold true}]
                     (let [on? (= i at)
                           head (str (if on? " › " "   ")
                                     ;; a fold says whether it is open; what is
                                     ;; under one is indented, behind its own bullet
                                     (cond (:fold? it) (if (contains? (set (:open p)) (:id it)) "▾ " "▸ ")
                                           (:folds? p) "  "
                                           :else "")
                                     (or (:bullet it) (case (:on? it) true "● " false "○ " "  "))
                                     (:label it) (spaces (- col (text/width (str (:label it))))) "  ")]
                       [:hbox {}
                        [:label {:label head :color (if on? :cyan :default) :bold on? :dim (:warn? it)}]
                        [:label {:hexpand true
                                 :label (fit (:value it) (- w (text/width head)))
                                 :color (cond (:current? it) :green (:changed? it) :cyan :else :default)
                                 :dim (not (or on? (:changed? it) (:current? it)))}]]))))
               [[:label {:label (str "   " (str/join " · " (remove nil? [(when (pos? top) (str "↑ " top))
                                                                         (when (pos? below) (str "↓ " below " more"))])))
                         :dim true}]]
               ;; what the marked one is, wrapped, in the rows reserved for
               ;; the tallest (byj-gly.283)
               (when-not pane?
                 (let [h (:h @size)
                       d (picker-detail sel w (picker-col p) (picker-detail-max-rows p h))]
                   (concat (for [{:keys [text style]} d]
                             [:label (merge {:label (str "   " text)} style)])
                           (repeat (- (picker-detail-rows p w h) (count d)) [:label {:label ""}]))))))
              pane-w (if wide? (- full w 3) (- full 4))
              ;; beside the list: as tall as it; under it: a few lines, the rest
              ;; a /tools NAME away — the "…" line inside the cap, which
              ;; `picker-height` reserves (byj-gly.275)
              cap (tool-pane-rows (:h @size) wide?)
              spec (when-let [row (:row sel)] (tool-pane-lines row pane-w))
              cut? (> (count spec) cap)
              pane (when pane?
                     (into [:vbox {}]
                           (concat
                            (for [l (take (if cut? (dec cap) cap) spec)] [:label {:label (str (when-not wide? "   ") l)}])
                            (when cut?
                              [[:label {:label (str (when-not wide? "   ") "… /tools " (:id sel) " shows all of it") :dim true}]])
                            (when (nil? sel) [[:label {:label "   nothing marked" :dim true}]])
                            )))]
          (cond
            wide? [:hbox {} listed
                   [:label {:label (str/join "\n" (repeat (max (+ 2 (count shown)) (- (count pane) 2)) " │ ")) :dim true}]
                   pane]
            pane? [:vbox {} listed pane]
            :else listed)))
      [:vbox {}])))

(defn- footer-rule-view
  "The rule under the input: at its right, the model the session is asking —
  `── claude-code/opus ──` — as the rule over it carries the conversation's
  name, so what a turn will run on is on the screen, not a /model away."
  [{:keys [size model]}]
  (fn []
    (if-let [label (some->> @model (str " ") (#(str % " ")))]
      (let [w (:w @size)
            label (if (> (text/width label) (- w 6)) (str (text/truncate label (- w 8)) "… ") label)]
        [:hbox {}
         [:label {:hexpand true :dim true :label (apply str (repeat (max 0 (- w 2 (text/width label))) "─"))}]
         [:label {:label label :color :cyan :dim true}]
         [:label {:label "──" :dim true}]])
      [:separator {}])))

;; --- the frame's rails (byj-gly.123) --------------------------------------------------

(declare relayout!)
;; Three slots: a left rail, the centre — everything above — and a right rail.
;; Both rails start hidden, unless `:rails-saved` puts back how a restart
;; left them (byj-gly.457); what is in them is the caller's (`:rails` to
;; `make`, `brainyard.tui-frame`). Opening, closing and moving in one only
;; changes what is on the screen, so it is the renderer's own business and
;; never reaches the session, as /queue never does (byj-gly.143).

(defn- rail-body-rows
  "Rows a rail has for items: the screen's, less its header and its rule —
  and the footer's, which an overlaid rail leaves showing."
  [c]
  (max 1 (- (:h @(:screen c)) 3)))

(defn- set-rails! [c f]
  (swap! (:rails c) f)
  (relayout! c))

(defn- show-view!
  "`side`'s view is coming into sight: tell it (`:on-show`, where a view that
  reads files or git starts reading — byj-gly.380), and put the mark where it
  says (`:mark`, the session the rail is in — byj-gly.379), else at the top."
  [c side]
  (let [rail (get @(:rails c) side)
        v (get (get-in c [:rail-views side]) (:view rail 0))]
    (when-let [f (:on-show v)] (try (f) (catch Throwable _ nil)))
    (let [items (vec (or (some-> (:items v) (apply [])) []))
          at (or (some-> (:mark v) (apply [items])) 0)]
      ;; and it keeps following :mark until a key moves it: a section that
      ;; reads after this changes the row it is on (byj-gly.405)
      (set-rails! c #(update % side (fn [r] (-> (frame/move (assoc r :at 0 :top 0) at (count items) (rail-body-rows c))
                                               (assoc :follow-mark? (boolean (:mark v))))))))))

(defn- overlaid?
  "Whether `side`'s rail is drawn over the centre, for want of room beside it."
  [c side]
  (= side (:overlay (frame/layout (:w @(:screen c)) @(:rails c) (or @(:rail-focus c) side)))))

(defn- rail-edge-at
  "The rail whose separator is at screen column `x`, for a press to take hold
  of (`frame/separator-at`), or nil — never a rail drawn over the centre while
  an ask or a picker is in front, since it is not drawn then."
  [c x]
  (let [w (:w @(:screen c))
        focus @(:rail-focus c)]
    (when-let [side (frame/separator-at w @(:rails c) focus x)]
      (when-not (and (or @(:gate c) @(:picker c))
                     (= side (:overlay (frame/layout w @(:rails c) focus))))
        side))))

(defn- switch-view!
  "Show `side`'s view numbered `i`, its mark at the top, told it is in sight
  so it reads — a view's letter, or a click on its name."
  [c side i]
  (when (not= i (get-in @(:rails c) [side :view] 0))
    (set-rails! c #(update % side assoc :view i :at 0 :top 0))
    (show-view! c side)))

(defn- rail-under
  "The rail drawn at screen cell `x`,`y`, as `[side col box-h]` — the column
  within it and how high it is drawn — or nil: off the rails, below a rail
  drawn over the centre (it stops short of the footer), and a rail drawn
  over the centre while an ask or a picker is in front, since it is not
  drawn then (byj-gly.449)."
  [c x y]
  (let [{w :w h :h} @(:screen c)
        focus @(:rail-focus c)
        rails @(:rails c)
        overlay (:overlay (frame/layout w rails focus))]
    (when-let [[side col] (frame/rail-at w rails focus x)]
      (let [box-h (if (= side overlay) (dec h) h)]
        (when (and (< y box-h)
                   (not (and (= side overlay) (or @(:gate c) @(:picker c)))))
          [side col box-h])))))

(defn- rail-click!
  "A left click at screen cell `x`,`y`, when it is on a rail (byj-gly.449):
  on an item's row it marks the item and does what Enter does with it; on a
  view's name in the header it shows that view, as the view's letter does.
  The keys stay where they were. Truthy when the click was the rail's —
  anywhere on it, so the centre under a rail drawn over it never takes it;
  nil where `rail-under` finds no rail."
  [c x y]
  (when-let [[side col box-h] (rail-under c x y)]
    (let [views (get-in c [:rail-views side])
          rail (get @(:rails c) side)
          rows (max 1 (- box-h 2))
          {:keys [view item]} (frame/click-target views rail box-h y col)]
      (cond
        view (switch-view! c side view)
        item (let [v (get views (:view rail 0))
                   it (get (vec (or (some-> (:items v) (apply [])) [])) item)]
               ;; the window stays where it was drawn, the mark follows
               ;; the view's :mark no more, as after a key
               (set-rails! c #(update % side (fn [r] (-> (frame/resolve-mark views r rows)
                                                         (dissoc :follow-mark?)
                                                         (assoc :at item)))))
               (when-let [act (:act v)] (when it (act it)))))
      true)))

(defn- rail-wheel!
  "`lines` of wheel at screen cell `x`,`y`, when it is over a rail
  (byj-gly.449): the rail's window moves, its mark stays, and it follows the
  view's :mark no more, as after a key — else the next paint would put the
  window back. Truthy when it was the rail's, so the transcript does not
  scroll too."
  [c x y lines]
  (when-let [[side _ box-h] (rail-under c x y)]
    (let [views (get-in c [:rail-views side])
          rows (max 1 (- box-h 2))]
      (set-rails! c #(update % side (fn [r]
                                      (let [r (dissoc (frame/resolve-mark views r rows) :follow-mark?)
                                            v (get views (:view r 0))
                                            n (count (or (some-> (:items v) (apply [])) []))]
                                        (frame/scroll r lines n rows)))))
      true)))

(defn open-rail!
  "Open `side` — on its view numbered `view`, when given — and, with
  `focus?`, give it the keys. What the frame's keys and commands do, and what
  a caller does to open a rail in a renderer it is switching to (byj-gly.379)."
  [c side & {:keys [view focus?]}]
  (when (seq (get-in c [:rail-views side]))
    (when focus? (reset! (:rail-focus c) side))
    (set-rails! c #(frame/open-rail % side (:w @(:screen c)) view))
    ;; a rail drawn over the centre covers the input, so it has the keys —
    ;; typed blind behind it otherwise (found live at 80 columns)
    (when (overlaid? c side)
      (reset! (:rail-focus c) side)
      (relayout! c))
    (show-view! c side)))

(defn rail-open?
  "Whether `side`'s rail is on the screen — what a caller asks before it
  rereads a rail's views (byj-gly.380)."
  [c side]
  (boolean (get-in @(:rails c) [side :open?])))

(defn show-block!
  "Put `text` in the transcript under `title`, folded past its first lines
  (`block-entries`): a diff a rail asked for (byj-gly.381). The screen's
  alone — it is never said to the model nor saved with the session, so a
  resumed session does not have it. Safe from any thread, as `say!` is."
  [c title text]
  (doseq [e (block-entries title text)] (say! c e))
  (reset! (:offset c) 0))

(defn cell
  "A reactive atom: what a caller gives a rail's views to keep their state
  in, so an answer arriving repaints the rail."
  [x]
  (r/atom x))

(defn on-change!
  "Call `(f value)` whenever reactive cell `c` changes: `add-watch` for the
  renderer's cells, which glimmer's are not (byj-gly.457)."
  [c f]
  (r/-add-watch! c (fn [cell] (f (r/-value cell)))))

(defn rail-key!
  "ctrl+l or ctrl+t, for `side`: a closed rail opens with the keys; an open
  one takes the keys; the rail that has them closes, and the keys go back to
  the input."
  [c side]
  (cond
    (empty? (get-in c [:rail-views side]))
    (reset! (:flash c) (str "nothing for the " (name side) " rail in this session"))
    (not (get-in @(:rails c) [side :open?]))
    (open-rail! c side :focus? true)
    (not= side @(:rail-focus c))
    (do (reset! (:rail-focus c) side) (relayout! c))
    :else
    (do (reset! (:rail-focus c) nil) (set-rails! c #(assoc-in % [side :open?] false))))
  true)

(defn rail-command!
  "`/left` or `/right` with `arg`: no arg opens a closed rail or closes an open
  one; a view's name opens the rail on it. The keys stay in the input: a
  command was typed there. Returns a line to say, or nil when the screen says
  it."
  [c side arg]
  (let [views (get-in c [:rail-views side])
        open? (get-in @(:rails c) [side :open?])]
    (cond
      (empty? views) (str "the " (name side) " rail has nothing to show in this session")
      (str/blank? arg)
      (do (if open?
            (do (when (= side @(:rail-focus c)) (reset! (:rail-focus c) nil))
                (set-rails! c #(assoc-in % [side :open?] false)))
            (open-rail! c side))
          nil)
      :else
      (if-let [i (frame/view-index views arg)]
        (do (open-rail! c side :view i) nil)
        (str "no " (name side) " view " (str/trim arg) " — " (str/join ", " (map :id views)))))))

(defn- rail-focused-key!
  "A key while a rail has them: ↑ and ↓ move its mark, Tab and shift+Tab to
  the next or previous section of the view shown, a view's letter (F Files,
  S Source) shows that view (byj-gly.406), and ← and → move its inner edge —
  the left rail's right, the right rail's left (byj-gly.404; ←→ switched the
  view, and shift+arrows resized, before). Enter acts on the marked item,
  Esc gives the keys back. The rest are swallowed, except ctrl+q, which is
  the loop's."
  [c e]
  (let [side @(:rail-focus c)
        views (get-in c [:rail-views side])
        body (rail-body-rows c)
        ;; a key settles a mark that was following the view's :mark where
        ;; it is now, and it follows no more (byj-gly.405)
        rail (let [r (get @(:rails c) side)]
               (if (:follow-mark? r)
                 (let [settled (dissoc (frame/resolve-mark views r body) :follow-mark?)]
                   (swap! (:rails c) assoc side settled)
                   settled)
                 r))
        view (get views (:view rail 0))
        n (count (or (some-> (:items view) (apply [])) []))
        move! (fn [step] (set-rails! c #(update % side frame/move step n body)) true)]
    (cond
      ;; the keys back to the input — and a rail drawn over the centre goes
      ;; with them, or it would cover the input they went to
      (k/match? e "esc") (do (when (overlaid? c side) (swap! (:rails c) assoc-in [side :open?] false))
                             (reset! (:rail-focus c) nil)
                             (relayout! c)
                             true)
      (k/match? e "up") (move! -1)
      (k/match? e "down") (move! 1)
      (k/match? e "pgup") (move! (- body))
      (k/match? e "pgdn") (move! body)
      ;; Tab and shift+Tab: the next or previous section of this view
      (#{:tab :back-tab} (:type e))
      (do (set-rails! c #(update % side frame/next-section views (if (= :tab (:type e)) 1 -1) body))
          true)
      ;; a view's letter shows it, told it is in sight so it reads
      (and (k/printable? e) (frame/view-for-key views (k/char-of e)))
      (do (switch-view! c side (frame/view-for-key views (k/char-of e))) true)
      ;; ← and →: the edge that faces the centre goes the way the arrow points
      (k/match? e ["left" "right"])
      (let [delta (* frame/resize-step (if (= (k/match? e "right") (= :left side)) 1 -1))]
        (set-rails! c #(frame/resize % side delta (:w @(:screen c))))
        true)
      ;; `@`: the marked row as a mention in the input, and the keys back to it
      (and (k/match? e "@") (:put view))
      (let [text (when-let [it (get (vec ((:items view))) (:at rail 0))] ((:put view) it))]
        (when text
          (swap! (:draft c) (fn [d] (str d (when (and (seq d) (not (str/ends-with? d " "))) " ") text " ")))
          (when (overlaid? c side) (swap! (:rails c) assoc-in [side :open?] false))
          (reset! (:rail-focus c) nil)
          (relayout! c))
        true)
      (k/match? e ["enter" "return"])
      (do (when-let [act (:act view)]
            (when-let [it (get (vec ((:items view))) (:at rail 0))]
              (act it)))
          true)
      ;; a key the view takes for itself on the marked row — the Terminal
      ;; view's ctrl+x stops a program (byj-gly.412)
      (when-let [f (:on-key view)]
        (when-let [it (get (vec ((:items view))) (:at rail 0))]
          (f it e)))
      true
      (k/match? e "ctrl+q") nil
      :else true)))

(defn- rail-box
  "One rail, `w` wide and `h` high, as `frame/rail-lines` says."
  [c side w h]
  (into [:vbox {:width-request w}]
        (for [[t style segments] (frame/rail-lines (get-in c [:rail-views side]) (get @(:rails c) side) w
                                                   h (= side @(:rail-focus c)))
              :let [label (fn [t style] [:label (merge {:label t} (cond-> (or style {}) (:color style) (update :color #(when-not (= :default %) %))))])]]
          ;; a row in pieces, each its own colour: the graph's lanes (byj-gly.398)
          (if segments
            (into [:hbox {}] (map (fn [[t s]] (label t s)) segments))
            (label t style)))))

(defn- pointer-mouse
  "The left button, as a view's `mouse-fn` for `point-input!`: a press marks
  where, a drag selects from there, and the release copies what was selected —
  or, if it never moved, is the click it always was. The session's screen and
  the eval page both use it; what differs between them is passed in:

  - `:press`      an atom, where the button went down: `{:x :y}`, with `:rail`
                  while a rail's edge is held
  - `:selection`  `(fn [])` the selection, `{:from [x y] :to [x y]}` or nil
  - `:select!`    `(fn [sel])` the selection a drag has made
  - `:clear!`     `(fn [])` a press: no selection, and no word of a copy
  - `:copy!`      `(fn [sel])` a release after a drag: copy what `sel` covers
                  and say so
  - `:click!`     `(fn [x y])` a release that never moved
  - `:edge-at`    `(fn [x])` the rail whose separator is at column `x`, or nil;
                  absent, a press never takes hold of one
  - `:drag-edge!` `(fn [side x])` the held rail's edge dragged to column `x`"
  [{:keys [press selection select! clear! copy! click! edge-at drag-edge!]}]
  (fn [{:keys [button x y release?]}]
    (cond
      ;; a press on a rail's separator takes hold of its inner edge: the drag
      ;; moves it, as ← and → do a step at a time, and the release lets go
      ;; without a click
      (and (not release?) (zero? (bit-and button 32)))
      (let [side (when edge-at (edge-at x))]
        (reset! press (cond-> {:x x :y y} side (assoc :rail side)))
        (clear!))

      (:rail @press)
      (do (drag-edge! (:rail @press) x)
          (when release? (reset! press nil)))

      (not release?)
      (when-let [{px :x py :y} @press]
        (select! {:from [px py] :to [x y]}))

      :else
      (let [{px :x py :y} @press
            sel (selection)]
        (reset! press nil)
        (if (and sel (not= (:from sel) (:to sel)))
          (copy! sel)
          (when px (click! px py)))))))

(defn- app-view
  "The whole screen. ctrl-c and the scroll keys are bound on the container
  rather than on a widget, because a key reaches an ancestor by bubbling up
  from the focus — the input line, always focusable, is the descendant that
  makes that work (RFC-028 rule 5)."
  [{:keys [size offset draft] :as c}
   {:keys [activate! typed! walk! newline! answer! cancel!] :as handlers}]
  (let [;; a child's transcript on the screen, from where a click found it
        open! (fn [target]
                (if (vector? target)
                  ;; a fold: open or close it, and remember it for ctrl-o
                  (let [[_ id] target]
                    (reset! (:fold-at c) id)
                    (swap! (:unfolded c) #(if (contains? % id) (disj % id) (conj % id))))
                  (when (get @(:subs c) target)
                    (reset! (:viewing c) target)
                    (reset! (:offset c) 0))))
        ;; the fold ctrl-o opens: the one touched last, else the newest on
        ;; the screen's transcript
        fold-to-edit (fn []
                       (let [lines (if-let [id @(:viewing c)] (get-in @(:subs c) [id :lines]) @(:lines c))
                             folds (filter #(= :fold (:kind %)) lines)]
                         (or (some #(when (= (:id %) @(:fold-at c)) %) folds) (last folds))))
        transcript (transcript-view c open!)
        busy (busy-view c open!)
        usage (usage-view c)
        footer (footer-view c)
        menu (menu-view c)
        picker (picker-view c)
        input (input-view c handlers)
        input-rule (input-rule-view c)
        footer-rule (footer-rule-view c)
        gate (gate-view c answer!)
        page (fn [] (max 1 (- (:h @size) chrome-rows)))
        ;; never past the top of what there is to see: an offset beyond the
        ;; transcript's own rows showed an empty screen that kept scrolling
        scroll! (fn [d]
                  ;; a selection is screen cells: once the rows move under it,
                  ;; it would name other text
                  (reset! (:selection c) nil)
                  (swap! offset #(scroll-offset c (+ % d))))
        ;; the left button (pointer-mouse), over the transcript, with the
        ;; rails' edges to take hold of
        mouse-fn (pointer-mouse
                  {:press (:press c)
                   :selection #(deref (:selection c))
                   :select! #(reset! (:selection c) %)
                   :clear! (fn [] (reset! (:selection c) nil) (reset! (:flash c) nil))
                   ;; the transcript's text: the rails and the chrome are not
                   ;; part of what a drag copies
                   :copy! (fn [sel]
                            (let [node (some #(when (= :transcript (:tag %)) %) (some-> (tui/tree) w/walk))
                                  text (when node (selection-text (:rows (:props node)) (:rect node) sel))]
                              (reset! (:flash c) (clipboard/describe ((:copier c) text)))))
                   ;; a rail's rows are labels, which a click passes through:
                   ;; the rail is asked first (byj-gly.449)
                   :click! (fn [x y] (or (rail-click! c x y) (tui/click! x y)))
                   :edge-at #(rail-edge-at c %)
                   :drag-edge! (fn [side x] (set-rails! c #(frame/drag-edge % side x (:w @(:screen c)))))})
        paste! (:paste! handlers)
        ;; a wheel notch up is a negative number of lines, and scrolling up is
        ;; a bigger offset from the bottom
        wheel-fn (fn [x y lines]
                   ;; over a rail, the rail's window (byj-gly.449)
                   (or (rail-wheel! c x y lines) (scroll! (- lines)))
                   true)
        ;; while the menu is up the arrows steer it; otherwise they walk the
        ;; history. A shell does the same, and for the same reason: the menu is
        ;; the thing in front of you
        steer! (fn [step]
                 (when-let [{:keys [items]} @(:menu c)]
                   (when (seq items)
                     (swap! (:menu c) scroll-menu step)
                     true)))]
    (fn []
      ;; the mouse and the wheel go to the view on screen: set here, while
      ;; this renders, not when it was built — with several sessions they went
      ;; to whichever was built last (byj-gly.207)
      (point-input! mouse-fn wheel-fn)
      [:vbox {:on-key (fn [e]
                        (cond
                          (k/match? e "ctrl+c") (do (cancel!) true)
                          ;; ctrl+g: the tool calls the turn is waiting on go
                          ;; to the background now (byj-gly.391); the turn
                          ;; carries on, and the model is told who moved them
                          (and (k/match? e "ctrl+g") (seq @(:backgrounders c)))
                          (let [waiting (vals @(:backgrounders c))]
                            (doseq [{:keys [release]} waiting] (release))
                            (reset! (:flash c) (str "moved " (str/join ", " (distinct (map :tool waiting)))
                                                    " to the background · /task lists it"))
                            true)
                          ;; a rail's keys and the keys in a rail (byj-gly.123),
                          ;; while nothing else is in front: an ask's letters and
                          ;; a picker's arrows come first
                          (and (k/match? e ["ctrl+l" "ctrl+t"]) (nil? @(:gate c)) (nil? @(:picker c)))
                          (rail-key! c (if (k/match? e "ctrl+l") :left :right))
                          (and @(:rail-focus c) (nil? @(:gate c)) (nil? @(:picker c)))
                          (rail-focused-key! c e)
                          ;; an ask up: its letters answer it — y, n, and the
                          ;; permission ask's a, r, d — as the menu line said
                          ;; they would, and as a person tries first (byj-1wv.11)
                          (some #(k/match? e (second %)) (:choices @(:gate c)))
                          (do (answer! (second (first (filter #(k/match? e (second %)) (:choices @(:gate c))))))
                              true)
                          ;; a permission ask up: Tab and Shift+Tab are its own,
                          ;; moving between its choices — left to the loop,
                          ;; which moves the focus with them (byj-gly.272)
                          (and (#{:tab :back-tab} (:type e)) @(:gate c)) false
                          ;; the permission mode, for the next tool call on
                          ;; Tab completes, and never moves the focus off the input
                          (and (= :tab (:type e)) (= :tools (:kind @(:picker c))))
                          (do ((:pick-direct! handlers)) true)
                          (= :tab (:type e)) (do ((:take-completion! handlers)) true)
                          ;; ← on an empty line: the session list
                          (and (k/match? e "left") (:on-agents c) (str/blank? @draft)
                               (nil? @(:picker c)) (nil? @(:search c)))
                          (do ((:on-agents c)) true)
                          ;; claimed with or without a mode to cycle: unclaimed,
                          ;; the loop moves the focus with it
                          (= :back-tab (:type e))
                          (do (some-> (:mode-box c) (swap! permission/next-mode)) true)
                          ;; a bracketed paste, whole — before the picker's keys,
                          ;; which let nothing else through (byj-gly.210)
                          (= :paste (:type e)) (do (when (:cut? e) (reset! (:paste-cut c) true))
                                                   (paste! (:text e)) true)
                          ;; ctrl+v: the clipboard, as a paste is
                          (k/match? e "ctrl+v") (do (some-> (:text (clipboard/read-text)) not-empty paste!) true)
                          ;; an open picker has the arrows, Esc and its own reset
                          @(:picker c)
                          (cond
                            ;; a delete asked about takes the next key, whatever it is
                            (:confirm @(:picker c)) ((:confirm-picked! handlers) (k/match? e ["y" "Y"]))
                            (k/match? e "esc") (do ((:close-picker! handlers) true) true)
                            (k/match? e "up") (do (swap! (:picker c) picker-move -1 (:h @size)) true)
                            (k/match? e "down") (do (swap! (:picker c) picker-move 1 (:h @size)) true)
                            (k/match? e "pgup") (do (swap! (:picker c) picker-move (- (picker-window-rows (:h @size))) (:h @size)) true)
                            (k/match? e "pgdn") (do (swap! (:picker c) picker-move (picker-window-rows (:h @size)) (:h @size)) true)
                            (k/match? e "ctrl+r") (do ((:reset-picked! handlers)) true)
                            (k/match? e "ctrl+x") (do ((:remove-picked! handlers)) true)
                            ;; → opens the marked fold, ← closes it — or, on
                            ;; something under a fold, closes that and goes to it
                            (k/match? e "right")
                            (let [it (picker-selected @(:picker c))]
                              (when (:fold? it)
                                (swap! (:picker c) #(picker-move (update % :open (fnil conj #{}) (:id it)) 0 (:h @size))))
                              true)
                            (k/match? e "left")
                            (let [p @(:picker c)
                                  it (picker-selected p)
                                  target (if (:fold? it) (:id it) (:parent it))]
                              (when target
                                (let [p (update p :open (fnil disj #{}) target)
                                      at (or (first (keep (fn [[kind x i]] (when (and (= :item kind) (= target (:id x))) i))
                                                          (picker-lines p)))
                                             0)]
                                  (reset! (:picker c) (picker-move (assoc p :at at) 0 (:h @size)))))
                              true)
                            ;; the transcript's own keys are not the picker's, and
                            ;; nothing else may fall through to them
                            (k/match? e ["ctrl+f" "ctrl+o"]) true
                            :else nil)
                          ;; ctrl-f finds, as brainyard's does: a mode of the
                          ;; input line, whose draft waits until it is done
                          (k/match? e "ctrl+f")
                          (do (if @(:search c)
                                (search-step! c 1)
                                (reset! (:search c) {:query "" :hits [] :at 0 :draft @draft}))
                              true)
                          (and @(:search c) (k/match? e "esc"))
                          (do (reset! draft (:draft @(:search c)))
                              (reset! (:search c) nil)
                              true)
                          (and @(:search c) (k/match? e "up")) (do (search-step! c 1) true)
                          (and @(:search c) (k/match? e "down")) (do (search-step! c -1) true)
                          (k/match? e "ctrl+o") (do (when-let [f (fold-to-edit)] ((:editor c) (:text f))) true)
                          ;; Esc closes the menu first — before it leaves a
                          ;; search or a child's transcript, since the menu is
                          ;; the thing in front of you (the two clauses were in
                          ;; the other order, contradicting this, byj-gly.213)
                          (and @(:menu c) (k/match? e "esc")) (do (reset! (:menu c) nil) true)
                          ;; back to the root's transcript — unless a permission
                          ;; ask is up, whose Esc is its own
                          (and (k/match? e "esc") @(:viewing c) (nil? @(:gate c)))
                          (do (reset! (:viewing c) nil) (reset! (:offset c) 0) true)
                          (k/match? e ["alt+enter" "alt+return"]) (newline!)
                          (k/match? e "up") (or (steer! -1) (walk! -1))
                          (k/match? e "down") (or (steer! 1) (walk! 1))
                          ;; pgup and pgdn only: glimmer-tui's entry claims
                          ;; ctrl-b, ctrl-u and ctrl-d for editing, and a key the
                          ;; focused widget claims never bubbles here — the
                          ;; bindings that used them never fired (byj-gly.140)
                          (k/match? e "pgup") (do (scroll! (page)) true)
                          (k/match? e "pgdn") (do (scroll! (- (page))) true)
                          :else nil))}
       ;; the frame (byj-gly.123): four slots, always there, so a rail opening
       ;; does not shift the centre to another place among its siblings and
       ;; remount it — the input would lose its focus. An empty slot is zero
       ;; columns wide: with both rails closed this is the screen as it was
       (let [lay (frame/layout (:w @(:screen c)) @(:rails c) @(:rail-focus c))
             in-front? (or @(:gate c) @(:picker c))]
         [:hbox {:hexpand true :vexpand true}
          (if-let [w (:left lay)]
            [:hbox {} (rail-box c :left w (:h @(:screen c))) [:separator {:orientation :vertical}]]
            [:hbox {}])
          [:vbox {:hexpand true :vexpand true}
           [transcript]
           [busy]
           [usage]
           ;; the menu opens ABOVE the input, taking its rows from the transcript:
           ;; under it, the input line and the rules around it moved up a row for
           ;; every completion offered, and back down when one was taken
           [menu]
           [picker]
           [input-rule]
           [input]
           [footer-rule]
           [footer]
           [gate]]
          (if-let [w (:right lay)]
            [:hbox {} [:separator {:orientation :vertical}] (rail-box c :right w (:h @(:screen c)))]
            [:hbox {}])
          ;; no room beside the centre: the rail draws over it while it has
          ;; the keys — never over an ask or a picker, which are the thing in
          ;; front of you, nor over an input the keys are in
          (if-let [side (when (and (not in-front?) (= (:overlay lay) @(:rail-focus c))) (:overlay lay))]
            ;; with its separator on the side that faces the centre, and
            ;; short of the footer, which says what the keys do in it
            [:overlay {:anchor (if (= :left side) :top-left :top-right) :modal false
                       :width-request (inc (frame/cols-of @(:rails c) side)) :height-request (dec (:h @(:screen c)))}
             (if (= :left side)
               [:hbox {} (rail-box c side (frame/cols-of @(:rails c) side) (dec (:h @(:screen c)))) [:separator {:orientation :vertical}]]
               [:hbox {} [:separator {:orientation :vertical}] (rail-box c side (frame/cols-of @(:rails c) side) (dec (:h @(:screen c))))])]
            [:hbox {}])])])))

;; --- the seams ---------------------------------------------------------------------

(defn- asking
  "One half of a blocking seam: park `request` in `cell` and wait for what a
  handler delivers. The WAIT is on the kernel's thread — a handler that waited
  here would freeze the terminal, including the keystroke that was going to
  answer it (RFC-028 rule 2)."
  [cell pending key]
  (fn [request]
    (let [p (promise)]
      (swap! pending assoc key p)
      (reset! cell request)
      @p)))

(defonce ^:private gate-lock (Object.))

(defn- answering [cell pending key]
  (fn [answer]
    (when-let [p (get @pending key)]
      (swap! pending dissoc key)
      (reset! cell nil)
      (deliver p answer)
      true)))

(def ^:private gate-choices
  "The ask channel's answers as buttons, in its own letters (RFC-012).
  `remember` appears only when the prompt offered it, because only the channel
  knows whether there is a config it may write."
  [["allow once" "y"] ["deny" "n"] ["always" "a"] ["remember" "r"] ["deny always" "d"]])

(defn make
  "A renderer: `:view` to mount, the seams for `session/run`, `:ask` for the
  permission channel, and `:cells` for a test to look at.

  `:verbose` shows the turn narrating itself (RFC-026's `:trace` level);
  `:max-lines` is how much transcript is kept; `:cancellers` is the atom
  `session/cancel-turn!` reads."
  [{:keys [verbose cancellers completions] :as opts}]
  (let [c (new-cells opts)
        ;; one cache of laid-out rows, read by the view and by search alike
        c (assoc c :rows-of (target-rows (rows-cache (:lines c) (:max-lines c))))
        ;; whoever changes the mode — shift+tab here, or an edited config the
        ;; session applied — the footer shows the one in force
        _ (when-let [b (:mode-box c)] (add-watch b ::mode (fn [_ _ _ m] (reset! (:mode c) m))))
        ;; what is kept of the rails, told whenever it changes (byj-gly.457)
        _ (when-let [f (:on-rails opts)]
            (let [last-kept (atom (frame/kept-rails @(:rails c)))]
              (on-change! (:rails c) (fn [rails]
                                       (let [k (frame/kept-rails rails)]
                                         (when (not= k (first (reset-vals! last-kept k)))
                                           (try (f k) (catch Throwable _ nil))))))))
        ;; a /model switch shows on the footer's rule as soon as it is made
        _ (when-let [b (:lm-box c)]
            (add-watch b ::model (fn [_ _ _ lm] (when lm (reset! (:model c) (llm/lm-label lm))))))
        ;; what a half-typed line could become. The default completes the
        ;; commands this renderer was handed; a caller with more to offer —
        ;; paths, model names — passes its own (RFC-001: the renderer does not
        ;; know what exists, only how to show a choice)
        completions (or completions #(command-completions (:commands c) %))
        ;; and the tools the session handed over (byj-gly.273.4): after `/`
        ;; beside the commands, after `:` alone, and a tool's arguments
        ;; A line that is a command exactly gets no menu at all, the tools'
        ;; included: `/memory` offered the memory$ tools, and Enter took
        ;; /memory$history instead of running the command (byj-gly.327, live)
        completions (let [base completions]
                      (fn [d] (when-not (some #(= (str/trim (str d)) (first %)) (:commands c))
                                (merge-completions (base d) (tool-completions @(:tool-rows c) d)))))
        pending (atom {})
        ;; once the loop is gone nothing can answer a seam, so a seam called
        ;; after that must not park: it would block the session on a promise
        ;; nobody is left to deliver, and the summary would count nothing
        closed? (atom false)
        cancellers (or cancellers (atom {}))
        answer-input! (answering (:prompt c) pending :input)
        answer-gate! (answering (:gate c) pending :gate)
        ;; plain atoms, not cells: the time of the last keystroke changes on
        ;; every character, and the paste being folded is named by the marker in
        ;; the field — nothing renders either of them
        last-key (atom 0)
        ;; the draft as it was when the current burst of keys began: what a
        ;; burst added is what settle-paste! looks at (byj-gly.213)
        burst-base (atom "")
        remember! (fn [line]
                    ;; consecutive repeats collapse: asking the same thing twice
                    ;; in a row is one thing to walk back to, not two
                    (swap! (:history c)
                           (fn [h] (if (= line (peek h))
                                     h
                                     (let [h (conj h line)]
                                       (if (> (count h) max-history)
                                         (subvec h (- (count h) max-history))
                                         h))))))
        ;; Up and Down walk what was typed before. The input line declines both
        ;; (it is one line, so there is nowhere to move), which is what lets the
        ;; container have them; brainyard binds Shift+arrows because its field
        ;; is multi-line and the plain ones are cursor motion there.
        walk! (fn [step]
                (let [h @(:history c)
                      n (count h)]
                  (when (pos? n)
                    (let [{:keys [at draft]} (or @(:walk c) {:at n :draft @(:draft c)})
                          at' (max 0 (min n (+ at step)))]
                      (reset! (:walk c) {:at at' :draft draft})
                      ;; one past the end is where the line you were typing is
                      (reset! (:draft c) (if (= at' n) draft (nth h at')))
                      true))))
        ;; a line typed while a turn is running is not thrown away and is not
        ;; delivered into nothing: it QUEUES, and the next prompt takes it.
        ;; brainyard has the same queue; without one, typing ahead is silently
        ;; swallowed, which looks exactly like a dropped keystroke.
        submit! (fn []
                  (let [line (-> @(:draft c)
                                 (expand-pastes @(:pastes c))
                                 (str/replace #"\s+$" ""))]
                    (when-not (str/blank? line)
                      ;; what was in the FIELD, marker and all: a recalled paste
                      ;; is its marker again, which still expands when it is
                      ;; sent — remembering the expansion put ten lines back
                      ;; in the field, and the fold made a new marker of them,
                      ;; one number higher on every visit
                      (remember! (str/trimr @(:draft c)))
                      (reset! (:walk c) nil)
                      (reset! (:menu c) nil)
                      (reset! (:draft c) "")
                      (reset! (:offset c) 0)
                      (cond
                        (re-matches #"/queue(?:\s+(.*))?" line)
                        ;; the renderer's own command, answered here, before the
                        ;; line can be read or queued (byj-gly.143)
                        (let [[_ args] (re-matches #"/queue(?:\s+(.*))?" line)
                              [said q] (queue-command @(:queue c) args)]
                          (say! c :you line)
                          (reset! (:queue c) q)
                          (say! c :info said)
                          true)
                        ;; a rail's: it changes only what is on the screen, so
                        ;; neither the transcript nor the session hears of it
                        ;; unless there is something to say (byj-gly.123)
                        (re-matches #"/(left|right)(?:\s+(.*))?" line)
                        (let [[_ side args] (re-matches #"/(left|right)(?:\s+(.*))?" line)]
                          (when-let [said (rail-command! c (keyword side) args)]
                            (say! c :info said))
                          true)
                        :else
                        (let [viewing @(:viewing c)
                              asked (ask-line viewing line)
                              line (or asked line)]
                          (when asked
                            ;; the child's own transcript says the question
                            ;; when its turn starts; the root's does not echo
                            ;; the call it was sent as, only draws it
                            (swap! (:unsaid c) conj line)
                            (when (get-in @(:busy c) [:subagents viewing])
                              (sub-say! c viewing {:kind :info :text (str "queued — asks " viewing " when it is idle")})))
                          (or (answer-input! line)
                              (do (swap! (:queue c) conj line) true)))))))
        ;; A line break that arrived with a paste goes into the field as a mark.
        ;; The FOLD happens afterwards, when the paste has settled: while one is
        ;; arriving the loop never repaints, so the entry's own buffer is a
        ;; keystroke behind and the next character would overwrite a marker
        ;; written during it. `settle-paste!` runs on the loop's tick, which is
        ;; the first moment the paste is over and the field is still ours.
        ;; at the caret, through the field's own edit, as a paste goes — not
        ;; appended at the end of the draft (byj-gly.213)
        break! (fn []
                 (reset! (:pasting c) true)
                 (if-let [wid (input-area)]
                   (let [{:keys [state]} @wid v (area-value state) at (area-caret state)]
                     (area-edit wid (str (subs v 0 at) "\n" (subs v at)) (inc at)))
                   (swap! (:draft c) str "\n")))
        settle-paste! (fn []
                        ;; only a paste the clock caught, once it has stopped
                        ;; arriving: a message typed with alt+enter, or one
                        ;; recalled from the history, is the person's own lines
                        ;; and is left as it is — a recalled paste folded again
                        ;; into a new marker, one number higher each visit
                        (when (and @(:pasting c)
                                   (> (- (System/currentTimeMillis) @last-key) paste-gap-ms))
                          (reset! (:pasting c) false)
                          (let [draft @(:draft c)
                                cut? @(:paste-cut c)
                                _ (reset! (:paste-cut c) false)
                                breaks (count (re-seq #"\n" draft))
                                ;; the breaks THIS burst added: a line already
                                ;; broken with alt+enter is not a paste
                                base @burst-base
                                added (if (str/starts-with? draft base) (subs draft (count base)) draft)]
                            (cond
                              ;; one line and the Enter that ended it, in one
                              ;; burst — `tmux send-keys "text" Enter`, a fast
                              ;; typist, a pasted command — is a line sent, as a
                              ;; shell takes it; held as a break, the Enter
                              ;; left it waiting for another (byj-10c)
                              ;; — unless a bracketed paste was cut short: the
                              ;; burst is its rest, and sending it would send
                              ;; half a paste (byj-gly.212)
                              (and (= 1 (count (re-seq #"\n" added))) (str/ends-with? draft "\n") (not cut?))
                              (do (reset! (:draft c) (subs draft 0 (dec (count draft))))
                                  (submit!)
                                  true)

                              (>= breaks paste-collapse-lines)
                              (let [text draft
                                    id (inc (count @(:pastes c)))
                                    lines (count (str/split-lines text))]
                                (swap! (:pastes c) assoc id {:text text :lines lines})
                                (reset! (:draft c) (paste-marker id lines))
                                true)))))
        ;; Enter, or a line break that came in with a paste — the clock tells
        ;; them apart (`paste-gap-ms`), because the terminal does not. Alt+Enter
        ;; is the deliberate one: a break a person asked for, composing a
        ;; message across lines rather than pasting one.
        ;; the marked completion into the line. Enter does this while the menu
        ;; is up, as a shell's does — Tab is the loop's own focus key and
        ;; never reaches a binding
        take-completion! (fn []
                           (let [{:keys [items at] :as m} @(:menu c)]
                             (when-let [[text] (nth items (or at 0) nil)]
                               (reset! (:draft c) (accept-completion @(:draft c) m text))
                               ;; a direct call goes on to what comes next — the
                               ;; tool's arguments, a key's values (byj-gly.273.4);
                               ;; anything else is done
                               (reset! (:menu c) (when (str/starts-with? @(:draft c) ":")
                                                   (some-> (completions @(:draft c)) (assoc :at 0))))
                               true)))
        ;; a line for the session that nobody typed: a pick, sent as the line
        ;; a person would have typed for it. `quiet` keeps it out of the
        ;; transcript — a switch flipped in the editor says what it did, and
        ;; the command it was is noise
        send! (fn [line quiet?]
                (when quiet? (swap! (:unsaid c) conj line))
                (or (answer-input! line) (swap! (:queue c) conj line)))
        ;; the /mcp picker's filter, sent to the registry once typing pauses:
        ;; each keystroke supersedes the last, and only the newest is sent
        registry-seq (atom 0)
        search-registry-later! (fn [text]
                                 (let [q (str/trim (str text))
                                       n (swap! registry-seq inc)]
                                   (when (and (= :mcp (:kind @(:picker c))) (>= (count q) 2))
                                     (try
                                       (tui/after! registry-pause-ms
                                                   #(when (and (= n @registry-seq)
                                                               (= :mcp (:kind @(:picker c)))
                                                               (= q (str/trim (str (:query @(:picker c))))))
                                                      (swap! (:picker c) assoc :searching q)
                                                      (send! (str "/mcp registry " q) true)))
                                       (catch Throwable _ nil)))))
        close-picker! (fn [restore?]
                        (when-let [p @(:picker c)]
                          (reset! (:picker c) nil)
                          (reset! (:draft c) (if restore? (:draft p) ""))))
        open-picker! (fn [p]
                       (let [h (:h @(:size c))
                             current (or (first (keep-indexed #(when (:current? %2) %1) (:items p))) 0)]
                         (reset! (:menu c) nil)
                         (reset! (:picker c) (picker-move (assoc p :query "" :at 0 :top 0 :draft @(:draft c))
                                                          current h))
                         (reset! (:draft c) "")))
        pick! (fn []
                (let [{:keys [kind]} @(:picker c)
                      it (picker-selected @(:picker c))]
                  (cond
                    (nil? it) nil
                    (= :model kind) (do (close-picker! true) (send! (str "/model " (:id it)) false))
                    ;; a memory: into the editor, the picker kept for what comes back
                    (= :memory kind) (send! (str "/memory edit " (:id it)) true)
                    ;; a tool: its slash form in the line, for the model to call
                    (= :tools kind) (do (close-picker! false) (reset! (:draft c) (str "/" (:id it) " ")))
                    ;; a server or a tool: the line it carries, quietly, the
                    ;; picker kept open for the rows that come back
                    (= :mcp kind) (when-let [line (:send it)] (send! line true))
                    (not (:writable? it)) (reset! (:flash c) (:note it))
                    ;; a switch flips where it is, and the editor stays open
                    (some? (:on? it)) (send! (str "/config " (:label it) " " (not (:on? it))) true)
                    (:cycle it) (send! (str "/config " (:label it) " " (name (:cycle it))) true)
                    ;; anything else is typed: into the line, to edit and send
                    :else (do (close-picker! false)
                              (reset! (:draft c) (str "/config " (:label it) " " (:edit it)))))
                  true))
        ;; tab on a tool: its direct form in the line, the first argument it
        ;; must be given already named (byj-gly.273.1, .3)
        pick-direct! (fn []
                       (when-let [it (and (= :tools (:kind @(:picker c))) (picker-selected @(:picker c)))]
                         (let [ins (get-in it [:row :inputs])
                               ;; the first it must be given, else the first it takes
                               req (or (first (remove :optional? ins)) (first ins))]
                           (close-picker! false)
                           (reset! (:draft c) (str ":" (:id it) " " (when req (str ":" (name (:name req)) " ")))))))
        reset-picked! (fn []
                        (let [{:keys [kind]} @(:picker c)
                              it (picker-selected @(:picker c))]
                          (cond
                            (and (= :config kind) it (:changed? it) (:writable? it))
                            (send! (str "/config unset " (:label it)) true)
                            (and (= :mcp kind) (:reset it)) (send! (:reset it) true))))
        remove-picked! (fn []
                         (let [it (picker-selected @(:picker c))]
                           (cond
                             (and (= :mcp (:kind @(:picker c))) (:remove it)) (send! (:remove it) true)
                             ;; a memory is gone for good: asked first, y/N
                             (and (= :memory (:kind @(:picker c))) it) (swap! (:picker c) assoc :confirm (:id it)))))
        ;; the answer to that ask: y deletes, any other key keeps it
        confirm-picked! (fn [yes?]
                          (when-let [key (:confirm @(:picker c))]
                            (swap! (:picker c) dissoc :confirm)
                            (when yes? (send! (str "/memory delete " key) true))
                            true))
        activate! (fn []
                    (cond
                      @(:picker c) (pick!)
                      ;; Enter while finding goes to the older match; it is
                      ;; not a line for the session
                      @(:search c) (search-step! c 1)
                      ;; a newline inside a paste is a line break, never a choice
                      (< (- (System/currentTimeMillis) @last-key) paste-gap-ms) (break!)
                      (seq (:items @(:menu c))) (take-completion!)
                      :else (submit!)))
        newline! (fn [] (swap! (:draft c) str "\n") true)
        typed-line! (fn [text]
                 ;; a key after a pause starts a burst: remember what was there
                 (when (> (- (System/currentTimeMillis) @last-key) paste-gap-ms)
                   (reset! burst-base @(:draft c)))
                 (reset! last-key (System/currentTimeMillis))
                 ;; typing leaves the walk: what is in the field is now yours,
                 ;; and Up from here starts again from the end
                 (reset! (:walk c) nil)
                 (reset! (:draft c) text)
                 ;; the menu is computed HERE rather than while rendering: a
                 ;; completer may walk a directory, and a frame must not
                 (reset! (:menu c) (some-> (completions text) (assoc :at 0))))
        ;; a bracketed paste (glimmer-tui's :paste): whole, into the line. Long, it is a
        ;; marker at once — the same fold settle-paste! makes of one the clock
        ;; caught — and short, its line breaks are the field's own marks
        ;; while ctrl-f is finding, what is typed is the query
        typed! (fn [text]
                 (cond
                   ;; a delete asked about (byj-gly.327): the key typed is the
                   ;; answer, not the filter's — the field reaches here before
                   ;; the view's keys do, so here is where it is taken
                   (:confirm @(:picker c))
                   (let [before @(:draft c)
                         typed (if (str/starts-with? text before) (subs text (count before)) text)]
                     (reset! (:draft c) before)
                     (confirm-picked! (contains? #{"y" "Y"} typed)))
                   ;; while a picker is open, what is typed filters it
                   @(:picker c) (do (reset! (:draft c) text)
                                    (swap! (:picker c) #(picker-move (assoc % :query text :at 0 :top 0)
                                                                     0 (:h @(:size c))))
                                    (search-registry-later! text))
                   @(:search c) (search-for! c text)
                   :else (typed-line! text)))
        ;; a bracketed paste, as typing would put it (byj-gly.209, .210): into
        ;; a picker's filter or ctrl-f's query when one is open — on one line —
        ;; else into the line at the caret, folded to a marker when long. It
        ;; goes through the field's own edit, so its :on-change sees it as it
        ;; sees a key, and the history walk is left as typing leaves it
        paste! (fn [text]
                 (let [text (str text)
                       one-line #(str/replace (str/trim-newline %) #"\s*[\r\n]+\s*" " ")]
                   (cond
                     @(:search c) (typed! (str (:query @(:search c)) (one-line text)))
                     @(:picker c) (typed! (str @(:draft c) (one-line text)))
                     :else
                     (let [lines (count (str/split-lines text))
                           ins (if (> lines paste-collapse-lines)
                                 (let [id (inc (count @(:pastes c)))]
                                   (swap! (:pastes c) assoc id {:text text :lines lines})
                                   (paste-marker id lines))
                                 text)]
                       (if-let [wid (input-area)]
                         (let [{:keys [state]} @wid
                               v (area-value state)
                               at (area-caret state)]
                           (area-edit wid (str (subs v 0 at) ins (subs v at)) (+ at (count ins))))
                         (typed! (str @(:draft c) ins)))
                       (reset! (:menu c) nil)))))
        freeze-block! (fn []
                        (when-let [block (when-not (= :quiet @(:display c)) (block-lines @(:iter c)))]
                          (doseq [entry block] (say! c entry)))
                        (reset! (:iter c) nil))
        cancel! (fn []
                  (when-let [f (get @cancellers :turn)]
                    (say! c :info "cancelling the turn…")
                    (f)
                    true))
        bump (fn [k n] (swap! (:status c) update k (fnil + 0) n))]
    {:cells c
     :cancellers cancellers
     :backgrounders (:backgrounders c)
     :submit! submit!
     ;; a line nobody typed, delivered without touching the draft or the history
     :send! send!
     ;; a paste folds when it stops arriving, which only the loop can see
     :settle-paste! settle-paste!
     ;; a line the UI puts in the transcript itself — a queued -q question, say,
     ;; which no seam ever carried
     :say! (fn [kind text] (say! c kind text))
     ;; ctrl-q, a closed loop, end of input: all of them mean the same nil, and
     ;; every seam after it answers at once instead of waiting
     :end-input! (fn [] (reset! closed? true) (answer-input! nil))
     :cancel! cancel!
     :answer-gate! answer-gate!
     :waiting-for-input? (fn [] (some? (get @pending :input)))
     :view (app-view c {:activate! activate! :typed! typed! :walk! walk! :paste! paste!
                        :newline! newline! :answer! answer-gate! :cancel! cancel!
                        :close-picker! close-picker! :reset-picked! reset-picked! :remove-picked! remove-picked!
                        :confirm-picked! confirm-picked!
                        :pick-direct! pick-direct!
                        :take-completion! take-completion!})

     ;; kernel -> UI: what a command reports, shown this renderer's own way
     ;; (RFC-027). `/config` opens the editor and `/model` the picker; a
     ;; `:refresh?` is new rows for an editor that is open, and nothing if it
     ;; is not. True when it was shown, so the session prints nothing.
     :present (fn [kind data]
                (case kind
                  :config (let [items (config-items (:rows data))]
                            (if (:refresh? data)
                              (when (= :config (:kind @(:picker c)))
                                (swap! (:picker c) #(picker-move (assoc % :items items) 0 (:h @(:size c))))
                                true)
                              (do (open-picker! {:kind :config :title "config" :items items}) true)))
                  :model (do (open-picker! {:kind :model :title "model" :items (model-items data)}) true)
                  ;; the project's memories (byj-gly.327): a refresh after an
                  ;; edit or a delete, to an open picker only
                  :memory (let [items (memory-items (:rows data))
                                h (:h @(:size c))]
                            (if (:refresh? data)
                              (when (= :memory (:kind @(:picker c)))
                                (swap! (:picker c) #(picker-move (assoc % :items items) 0 h))
                                true)
                              (do (open-picker! {:kind :memory :title "memory" :items items})
                                  (when (seq (:query data))
                                    (reset! (:draft c) (:query data))
                                    (swap! (:picker c) #(picker-move (assoc % :query (:query data) :at 0 :top 0) 0 h)))
                                  true)))
                  ;; a tool the person called directly: drawn as a model's call is,
                  ;; its whole result under it, folded past its first rows
                  ;; (byj-gly.273.3)
                  :tool-call (do (say-all! c (block-lines {:calls [(select-keys data [:tool-name :tool-args])]
                                                           :results (pretty-results (sized [data]))}))
                                 ;; a person's ask that could not reach its
                                 ;; subagent says why where they are watching
                                 ;; (byj-gly.344): never sent to the root instead
                                 (when-let [id (and (:tool-error? data) (= "agent-registry$ask" (str (:tool-name data)))
                                                    (some-> (get-in data [:tool-args :id]) str))]
                                   (when (get @(:subs c) id)
                                     (sub-say! c id {:kind :error
                                                     :text (str "✗ not asked: "
                                                                (or (second (re-find #":error-message \"((?:[^\"\\]|\\.)*)\"" (str (:tool-result data))))
                                                                    (str (:tool-result data))))})))
                                 true)
                  ;; the tools to complete, kept rather than shown (byj-gly.273.4)
                  :tool-rows (do (reset! (:tool-rows c) (vec (:rows data))) true)
                  ;; every tool, the marked one's spec beside it (byj-gly.273.1)
                  :tools (let [{:keys [rows query select]} data
                               items (tools-items rows)
                               h (:h @(:size c))]
                           (open-picker! {:kind :tools :title "tools" :items items :rank-label? true})
                           (when (seq query)
                             (reset! (:draft c) query)
                             (swap! (:picker c) #(picker-move (assoc % :query query :at 0 :top 0) 0 h)))
                           (when-let [at (and select (first (keep-indexed #(when (= select (:id %2)) %1) items)))]
                             (swap! (:picker c) #(picker-move (assoc % :at at) 0 h)))
                           true)
                  :mcp (let [p @(:picker c)
                             ;; a refresh carries only what changed: servers after
                             ;; an action, registry hits after a search
                             keep #(if (contains? data %) (get data %) (get p %))
                             rows (keep :rows)
                             catalog (keep :catalog)
                             registry (keep :registry)
                             items (mcp-items rows catalog registry)]
                         (if (:refresh? data)
                           (when (= :mcp (:kind p))
                             (swap! (:picker c) #(picker-move (assoc % :items items :rows rows :catalog catalog
                                                                     :registry registry :searching nil
                                                                     :registry-error (:registry-error data))
                                                              0 (:h @(:size c))))
                             true)
                           (do (open-picker! {:kind :mcp :title "mcp" :items items :folds? true :open #{}
                                              :rows rows :catalog catalog :registry nil})
                               true)))
                  nil))

     ;; UI -> kernel. A line, or nil at the end of input — which is where ctrl-q
     ;; and a closed loop both come out.
     :read-input (fn [prompt]
                   (let [queued (first @(:queue c))
                         line (cond
                                queued (do (swap! (:queue c) #(vec (rest %))) queued)
                                @closed? nil
                                :else ((asking (:prompt c) pending :input) (str/trim (str prompt))))]
                     (cond
                       (str/blank? line) nil
                       ;; the picker's own line: said by what it does, not by itself
                       (contains? @(:unsaid c) line) (swap! (:unsaid c) disj line)
                       :else (say! c :you line))
                     line))

     ;; kernel -> UI, one door each (RFC-027)
     :emit (fn [kind text]
             (reset! (:live c) nil)
             (case kind
               ;; what the turn did goes above what it said: a block still
               ;; live when the answer lands would otherwise freeze under it
               :answer (do (freeze-block!)
                           (say! c :answer "")
                           (doseq [entry (answer-entries text)] (say! c entry))
                           (when-let [done @(:done c)]
                             (reset! (:done c) nil)
                             (say! c :answer "")
                             (say! c :done (done-line done))))
               :error (say! c :error text)
               (say! c :info text)))

     :on-event
     (fn [event data]
       (if-let [child (and (child-events event) (:subagent-id data))]
         (sub-event! c child event data)
       (case event
         ;; the reply as the provider produces it (RFC-006). Reasoning is worth
         ;; watching while the answer is still empty and the answer takes over
         ;; when it starts: the events say what a fragment IS, and which one is
         ;; worth showing is the viewer's call (byj-gly.56).
         :agent.llm/chunk
         (swap! (:live c) grow-live data)

         ;; the iteration, as a block that grows while it runs and is frozen
         ;; into the transcript when the next one starts (brainyard's sticky
         ;; live blocks). This is what an unverbose session shows while it
         ;; works: without it, a turn that runs six tools says nothing at all.
         ;; what is in flight, and for how long. The model call is bracketed by
         ;; the request going out and the reasoning coming back; a task and a
         ;; subagent by their own start and end (RFC-019, RFC-018). brainyard
         ;; keeps a sticky block per family for exactly these.
         :agent.llm/request (swap! (:busy c) assoc :think {:since (System/currentTimeMillis)
                                                           :predictor (:predictor-id data)})
         :agent.think/reasoning (do (swap! (:busy c) dissoc :think)
                                    (swap! (:iter c) with-thought (:reasoning data))
                                    (swap! (:live c) drop-reasoning))
         ;; the thought shown was written under the CLI's refusal of a call
         ;; that is now run: what happened instead (byj-gly.339)
         :agent.tools/recovered (swap! (:iter c) with-thought (:thought data))
         :task/created (do (bump :tasks 1)
                           (swap! (:busy c) assoc-in [:tasks (:id data)]
                                  {:name (or (:name data) (:job-type data))
                                   :since (System/currentTimeMillis)}))
         :task/completed (do (bump :tasks -1)
                             (swap! (:busy c) update :tasks dissoc (:id data)))
         :agent.subagent/dispatch (do (swap! (:busy c) assoc-in [:subagents (:id data)]
                                             {:agent (:agent data) :since (System/currentTimeMillis)})
                                      (sub-start! c data))
         ;; a follow-up to a live instance is a child turn too
         :agent.subagent/ask (do (swap! (:busy c) assoc-in [:subagents (:id data)]
                                        {:agent (:agent data) :since (System/currentTimeMillis)})
                                 (sub-start! c data))
         :agent.subagent/result (do (swap! (:busy c) update :subagents dissoc (:id data))
                                    (sub-end! c data))

         ;; a compaction is not a live block here: it is one event, after the
         ;; fact, and what a person wants from it is the number
         :agent.context/budgeted
         (when (seq (:compactions data))
           (say! c :info (str "context compacted · " (:total-tokens data) " → " (:after-tokens data)
                              " tokens · " (str/join ", " (map name (:compactions data))))))

         :agent.iteration/start (do (freeze-block!)
                                    (swap! (:live c) drop-reasoning)
                                    (reset! (:iter c) {:n (:iteration data) :calls [] :results []}))
         :agent.tools/calls (swap! (:iter c) #(assoc (or % {:n 1}) :calls (vec (:tool-calls data))))
         :agent.tools/results (swap! (:iter c) #(assoc (or % {:n 1}) :results (pretty-results (sized (:results data)))))
         ;; a workflow's steps — plan-act-eval's, a loop of the person's own —
         ;; drawn as calls (byj-gly.299)
         :agent.tree/step-start (do (freeze-block!) (reset! (:iter c) (step-iter data)))
         :agent.tree/step-progress (swap! (:iter c) with-progress data)
         ;; a running tool's newest lines, under its call (byj-gly.364)
         :agent.tool/output (swap! (:iter c) with-output data)
         :agent.tree/step-end (swap! (:iter c) with-step-end data)
         :permission/classified (swap! (:iter c) with-verdict data)

         ;; a resumed session: what was said before goes back on the screen,
         ;; each line as it was first shown. brainyard replays the tail of its
         ;; scrollback on resume; this replays the kernel's own transcript,
         ;; which is the same conversation under any front end (RFC-016)
         :session/resumed
         (do
          (when (seq (:conversation-title data)) (reset! (:title c) (:conversation-title data)))
          ;; the counts carry on from where the session was, not from zero
          (let [{:keys [turn-count usage]} data]
            (swap! (:status c) assoc
                   :turn (or turn-count 0)
                   :calls (or (:llm-calls usage) 0)
                   :input-tokens (or (:input-tokens usage) 0)
                   :output-tokens (or (:output-tokens usage) 0)
                   :cost (:cost usage)))
          ;; Up walks back through what was asked before the resume too: the
          ;; questions in the transcript, oldest first, the newest max-history
          ;; kept and a repeat collapsed, as remember! keeps them
          (reset! (:history c)
                  (->> (:transcript data)
                       (keep (fn [[kind text]] (when (= :you kind) (str/trimr (str text)))))
                       (remove str/blank?)
                       (partition-by identity)
                       (map first)
                       (take-last max-history)
                       vec))
          (say-all! c (resumed-entries (:transcript data) (:max-lines c)))
          ;; and what the model thought would be asked next, offered again
          (when (:suggestions? c)
            (reset! (:suggestion c) (not-empty (str/trim (str (:next-user-prompt data)))))))

         ;; a suggestion from a call the person made between turns (/init)
         :session/suggestion
         (when (:suggestions? c)
           (reset! (:suggestion c) (not-empty (str/trim (str (:next-user-prompt data))))))

         :session.turn/recap
         (let [{:keys [recap next-step cost conversation-title]} data]
           (when (seq conversation-title) (reset! (:title c) conversation-title))
           (when (pos? (or cost 0)) (swap! (:status c) update :cost (fnil + 0) cost))
           (say! c :answer "")
           (say! c {:kind :recap
                    :text (str "recap: " recap (when (seq next-step) (str " " next-step)))
                    :lead {:text "※ "} :hang {:text "  "}}))

         ;; a self-improvement judge (RFC-017) costs like any call; what it
         ;; staged, the session has already said as an :info line
         :session.skill/judged
         (when (pos? (or (:cost data) 0)) (swap! (:status c) update :cost (fnil + 0) (:cost data)))

         ;; a note for people (RFC-043): a line, and who sent it — never a turn
         :session.share/note
         (say! c :info (str "✎ note from " (share/who (:from data)) ": " (:text data)))

         ;; who is watching, as the share sidecar reports it: the footer, and a
         ;; line for each who joined or left
         :session.share/presence
         (let [before @(:share c)]
           (reset! (:share c) data)
           (doseq [line (share/changes before data)] (say! c :info line)))

         ;; /share stop: the footer no longer says it is shared
         :session.share/control
         (when (= :stop (some-> (:action data) keyword)) (reset! (:share c) nil))

         ;; a team member's answer: what it spent is the leader's (RFC-032)
         :session.member/spent
         (when (pos? (or (:cost data) 0)) (swap! (:status c) update :cost (fnil + 0) (:cost data)))

         :session.turn/start (do (reset! (:live c) nil)
                                 ;; a new turn makes the last one's suggestion stale
                                 (reset! (:suggestion c) nil)
                                 (swap! (:status c) #(assoc % :running? true :turn (or (:turn data) 0)
                                                            :since (System/currentTimeMillis)
                                                            :turn-output-from (:output-tokens %))))
         :session.turn/end (do (reset! (:live c) nil)
                               (swap! (:busy c) dissoc :think :subagents)
                               (freeze-block!)
                               ;; what the model thinks you might ask next, offered
                               ;; in the empty input line — brainyard's
                               ;; :agent.suggestion/next-user-prompt, for the root
                               ;; turn only: a child's is its caller's business
                               (when (:suggestions? c)
                                 (reset! (:suggestion c)
                                         (not-empty (str/trim (str (:next-user-prompt data))))))
                               ;; said under the answer, which the session
                               ;; emits AFTER this event
                               (reset! (:done c) {:turn (:turn @(:status c))
                                                  :elapsed-ms (or (:elapsed-ms data)
                                                                  (some->> (:since @(:status c))
                                                                           (- (System/currentTimeMillis))))
                                                  :ended-at (:ended-at data)})
                               ;; the turn's own usage is the priced one: a call
                               ;; is costed where the model that ran it is known
                               ;; (RFC-025), and nothing on the event stream
                               ;; carries a price before then
                               (when-let [spent (get-in data [:usage :cost])]
                                 (swap! (:status c) update :cost (fnil + 0) spent))
                               (swap! (:status c) assoc :running? false))
         ;; a call that is not the loop's own — a recap — says nothing else
         ;; when it is over, and its spinner must not outlive it
         :predictor/call (do (swap! (:busy c) dissoc :think)
                             (bump :calls 1)
                             (when-let [u (:usage data)]
                               (bump :input-tokens (or (:input-tokens u) 0))
                               (bump :output-tokens (or (:output-tokens u) 0))
                               (swap! (:status c) assoc :last-call
                                      [(or (:input-tokens u) 0) (or (:output-tokens u) 0)])))
         ;; everything else by its level: a problem is shown whether or not
         ;; anyone asked, the turn narrating itself only under -v, and a :quiet
         ;; event is a decision someone recorded — not an omission (RFC-026)
         (let [{:keys [level label]} (get events/catalogue event)]
           (cond
             (= :problem level)
             (say! c :problem (str "⚠ " (or label (subs (str event) 1))
                                   (when (seq data) (str ": " (pr-str data)))))
             (and (#{:verbose :debug} @(:display c)) (= :trace level))
             (say! c :trace (str "· " (or label (subs (str event) 1))
                                 ;; debug says what the event carried as well
                                 (when (and (= :debug @(:display c)) (seq data))
                                   (str " " (let [p (pr-str data)] (if (> (count p) 300) (str (subs p 0 300) "…") p))))))
             :else nil)))))

     ;; an injected turn's approval (RFC-043), in the gate's overlay: allow or
     ;; refuse, and a refusal when nobody answers in time. One ask at a time —
     ;; it waits for a permission ask already up, inside the same deadline
     :approve (fn [{:keys [who text timeout-ms]}]
                (when-not @closed?
                  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 120000))
                        p (promise)
                        req {:label "injected turn"
                             :prompt [(str who " wants to run:")
                                      (let [t (str/trim (str text))] (if (> (count t) 300) (str (subs t 0 300) "…") t))
                                      (str "allow it within " (quot (or timeout-ms 120000) 1000) " s, or it is refused")]
                             :choices [["allow" "y"] ["refuse" "n"]]
                             ;; refuse has the focus: a stray Space or Enter
                             ;; must not run a turn someone else wrote (byj-1wv.11)
                             :focus 1}
                        took? (loop []
                                (cond
                                  (locking gate-lock
                                    (when (nil? @(:gate c))
                                      (swap! pending assoc :gate p)
                                      (reset! (:gate c) req)
                                      true)) true
                                  (>= (System/currentTimeMillis) deadline) false
                                  :else (do (Thread/sleep 50) (recur))))]
                    (if-not took?
                      :expired
                      (do (attention! c)
                          (let [r (deref p (max 0 (- deadline (System/currentTimeMillis))) ::expired)]
                            (cond
                              (= ::expired r)
                              (do (locking gate-lock
                                    (when (identical? p (get @pending :gate))
                                      (swap! pending dissoc :gate)
                                      (reset! (:gate c) nil)))
                                  ;; answered as the deadline passed: the answer stands
                                  (let [late (deref p 0 ::expired)]
                                    (cond (= ::expired late) :expired (= "y" late) :approved :else :rejected)))
                              (= "y" r) :approved
                              :else :rejected)))))))

     ;; kernel -> UI -> kernel: the seam that decides everything (RFC-027), a
     ;; call that blocks the turn until a button answers it. Given to
     ;; `permission/asking-permission-fn` as its ask channel.
     :ask (fn [prompt]
            (if @closed?
              "n"                                    ; no one is there to allow it
              (let [lines (remove str/blank? (str/split-lines (str prompt)))
                    offers-remember? (str/includes? (str prompt) "[r]emember")]
                (attention! c)
                ((asking (:gate c) pending :gate)
                 {:prompt (vec (butlast lines))     ; the last line is the menu the buttons are
                  :choices (vec (cond->> gate-choices
                                  (not offers-remember?) (remove #(= "r" (second %)))))}))))}))

(defn seams
  "What `session/run` takes from a renderer. `:permission-fn` is not here: it is
  built from `:ask` by the caller, so the policy deciding what an answer means
  stays in `brainyard.permission` (RFC-012)."
  [renderer]
  (select-keys renderer [:read-input :emit :on-event :cancellers]))

(defn parse-stty-size
  "`stty size`'s answer — `\"30 120\"`, rows then columns — as `{:w :h}`, or nil."
  [s]
  (when-let [[_ h w] (re-find #"^\s*(\d+)\s+(\d+)" (str s))]
    (let [w (parse-long w) h (parse-long h)]
      (when (and (pos? w) (pos? h)) {:w w :h h}))))

(defn- terminal-size
  "The terminal's size before anything is drawn, from `stty size` on the
  controlling terminal — or nil. The loop learns it from its first laid-out
  frame, but a resumed session lays its transcript out before that, at 80×24,
  and the reflow to the real width once the size arrived redrew a long
  transcript as a different page: it looked like another session flashing
  past (byj-gly.286)."
  []
  (try (parse-stty-size (:out (fx/sh {:out :string :err :string} "sh" "-c" "stty size < /dev/tty")))
       (catch Throwable _ nil)))

(defn relayout!
  "Set the centre's size from the screen's and the rails' (byj-gly.123): the
  screen less any rail beside it. Everything that wraps reads `:size`, so a
  rail opening re-wraps the transcript to the columns left — and with both
  rails closed `:size` is the screen, as it was before rails existed."
  [cells]
  (let [{:keys [w h]} @(:screen cells)
        size {:w (:center (frame/layout w @(:rails cells) @(:rail-focus cells))) :h h}]
    (swap! (:size cells) #(if (= % size) % size))
    nil))

(defn- restore-rails!
  "The rails as `:rails-saved` left them (byj-gly.457), once, on a screen
  `w` wide: what `frame/restore-rails` puts back, and each rail it opens told
  it is coming into sight, as one a key opens is."
  [c w]
  (when-let [saved (when-let [p (:rails-pending c)] (first (reset-vals! p nil)))]
    (swap! (:rails c) frame/restore-rails saved w
           (into {} (for [side frame/sides] [side (count (get-in c [:rail-views side]))])))
    (doseq [side frame/sides :when (get-in @(:rails c) [side :open?])]
      (show-view! c side))))

(defn resize!
  "Tell the renderer the terminal is `{:w :h}`."
  [cells {:keys [w h] :as screen}]
  (when (and (pos? (or w 0)) (pos? (or h 0)))
    (swap! (:screen cells) #(if (= % screen) % {:w w :h h}))
    (restore-rails! cells w)
    (relayout! cells))
  nil)

(defn measure!
  "Tell the renderer how big the screen is, from the last laid-out frame. The
  loop calls this on a timer; a headless test calls it after `frame!`."
  [{:keys [cells]}]
  (when-let [rect (:rect (tui/tree))]
    (resize! cells (select-keys rect [:w :h])))
  nil)

;; --- the edge ----------------------------------------------------------------------

(defn usable-terminal?
  "Whether a full-screen UI can start: a tty, a TERM, and a terminfo entry for
  it. `initscr` reports failure by calling `exit()`, so an unguarded start on a
  headless box is not an exception to catch — it is the process ending
  (RFC-028 rule 4)."
  []
  (tui/usable-terminal?))

(defn run!
  "Hand the terminal to `renderer` and run `session-fn` — a thunk calling
  `session/run` with the seams — on another thread. Returns what the session
  returned, or rethrows what it threw, once the terminal has been given back.

  `ready-turn` is polled on the loop thread: a parked turn resumes because
  something ELSE finished (RFC-019), so it cannot wait for a person to type,
  and this timer is the loop's version of `waiting-reader`'s poll."
  [{:keys [view cells submit! send! waiting-for-input?] :as renderer} session-fn
   & {:keys [tick-ms ready-turn mouse?] :or {tick-ms 40 mouse? true}}]
  (let [result (atom nil)
        thrown (atom nil)
        worker (Thread. (fn []
                          (try (reset! result (session-fn))
                               (catch Throwable t (reset! thrown t))
                               ;; the session is over: take the terminal back
                               (finally (tui/quit!)))))
        root (fn []
               ;; a form-2 component: the outer fn runs once, on mount, so the
               ;; timers are armed once however often the tree re-renders. They
               ;; run ON the loop thread, the only one that may touch a widget.
               ;; ncurses' mouse off, and the terminal asked for what this
               ;; decodes itself: SGR mouse reports, unless mouse is turned off —
               ;; the terminal then keeps its own click-drag selection, brainyard's
               ;; :enable-mouse trade — and bracketed paste either way
               (tui/after! 30 #(do (reset! loop-thread (Thread/currentThread))
                                   (try (terminal-input! mouse?) (catch Throwable _ nil))))
               (tui/every! 120 (fn []
                                 (swap! (:tick cells) inc)
                                 ((:settle-paste! renderer))))
               (tui/every! 200 #(measure! renderer))
               (tui/every! tip-every-ms #(swap! (:tip-at cells) inc))
               (let [reading (atom false)]
                 (tui/every! share-every-ms #(refresh-shares! reading [cells])))
               (when ready-turn
                 (tui/every! 250 #(when (and (waiting-for-input?) (nil? @(:gate cells)))
                                    ;; delivered as a pick is: the draft being typed is
                                    ;; not overwritten, nor the turn put in Up-history
                                    ;; (byj-gly.213)
                                    (when-let [turn (ready-turn)]
                                      (send! turn false)))))
               (fn [] [view]))]
    ;; the real size before the session lays anything out (byj-gly.286)
    (when-let [s (terminal-size)] (resize! cells s))
    (.start worker)

    (try
      ;; the entry ncurses starts from: not one whose `rep` it gets wrong
      ;; (byj-gly.269)
      (start-term!)
      ;; nothing the session starts may ask on the terminal now (byj-gly.297)
      (fx/detach-children! true)
      ;; a fault in a key, a timer, a render or a paint is a line (byj-gly.266)
      (ui/run root :tick-ms tick-ms :quit-keys #{"ctrl+q"} :on-error (on-error (constantly cells))
              :on-mouse on-mouse :paste-timeout-ms paste-byte-ms)
      (finally
        (restore-term!)
        ;; left on, a mouse mode makes every later click in the shell print
        ;; escape codes; turned off whatever was turned on
        (try (print disable-sequences) (flush) (catch Throwable _ nil))
        ;; The loop is gone and the terminal is back, so nothing can answer a
        ;; seam any more. ctrl-q therefore means all three at once: deny what
        ;; the gate was asking, end the input, and cancel a turn still running —
        ;; otherwise the session sits on a promise nobody will ever deliver.
        ((:answer-gate! renderer) "n")
        (when-let [cancel (get @(:cancellers renderer) :turn)]
          (try (cancel) (catch Throwable _ nil)))
        ((:end-input! renderer))
        (.join worker 5000)
        ;; what it started without the terminal would not hear it close
        (fx/detach-children! false)
        (fx/destroy-owned!)))
    (when-let [t @thrown] (throw t))
    @result))

;; --- several sessions at once (byj-gly.108) ------------------------------------------
;;
;; brainyard holds several sessions in one TUI as tabs (`/session tabs|switch|new`).
;; This is Claude Code's shape instead (divergence `sessions-are-a-list`): one
;; session in the foreground, the rest carrying on in the background, and a LIST
;; of them that ← on an empty input line opens. Every session is a renderer of its
;; own (`make`) and a thread of its own, so a background turn keeps running and
;; keeps writing to its own transcript — the origin-session problem brainyard's
;; renderer doc spends a section on does not arise: nothing is ever routed to the
;; screen, the screen shows whichever renderer is in front.

(defn session-state
  "Where a session stands, for the list: `:needs-input` while a permission ask
  waits on a person, `:working` while a turn runs, `:idle` while it waits for
  its next line, `:ended` once it is over."
  [{:keys [renderer ended?]}]
  (let [c (:cells renderer)]
    (cond ended? :ended
          @(:gate c) :needs-input
          (:running? @(:status c)) :working
          :else :idle)))

(defn- age-label [ms]
  (let [s (quot (max 0 ms) 1000)]
    (cond (< s 60) (str s "s") (< s 3600) (str (quot s 60) "m") :else (str (quot s 3600) "h"))))

(defn session-row
  "One session as the list shows it: its state, its name — the recap's, else
  its first question — the last thing it said, and how long ago."
  [{:keys [id renderer] :as entry} now]
  (let [c (:cells renderer)
        lines @(:lines c)
        first-line #(first (remove str/blank? (str/split-lines (str %))))
        asked (some #(when (and (= :you (:kind %)) (not (str/blank? (:text %)))) (first-line (:text %))) lines)
        said? #(and (#{:answer :heading :recap :error} (:kind %)) (not (str/blank? (:text %))))
        ;; an answer is a line an entry, after an empty :answer that begins it:
        ;; its FIRST line, not its last — a report's last line said nothing of
        ;; it ("skills: .claude/beads, …", the sim's screen pass, byj-gly.315.19)
        start (some (fn [i] (when (let [e (nth lines i)] (and (= :answer (:kind e)) (str/blank? (:text e)))) i))
                    (range (dec (count lines)) -1 -1))
        said (or (when start (some #(when (said? %) (first-line (:text %))) (subvec lines (inc start))))
                 (some #(when (said? %) (first-line (:text %))) (rseq lines)))]
    {:id id
     :state (session-state entry)
     :title (or @(:title c) asked "new session")
     :last (or said "")
     :age (age-label (- now @(:last-active c)))}))

(defn child-entries
  "A subagent's turns, from its trajectory records, as its transcript's
  entries — what a buffer shows when it was not kept: a turn from before the
  session was resumed, or the child of a saved session (byj-gly.298.4). Each
  turn: its question, each step's thought and tool calls as the live view
  draws them (`block-lines`), its answer."
  [records]
  (vec (mapcat (fn [rec]
                 (concat (you-entries (str (:question rec)))
                         (mapcat (fn [it]
                                   (when (seq (:tools it))
                                     (block-lines {:thought (:thought it)
                                                   :calls (mapv (fn [t] {:tool-name (:name t) :tool-args (:args t)}) (:tools it))
                                                   :results (mapv (fn [t] {:tool-result (:result t) :tool-error? (:error? t)}) (:tools it))})))
                                 (:iterations rec))
                         (when-let [a (not-empty (trajectory/record-answer rec))]
                           (concat [{:kind :answer :text ""}] (answer-entries a)))))
               records)))

(defn show-child!
  "Put subagent `id`'s transcript on `renderer`'s screen, as a click on its row
  does. A buffer the renderer does not have is rebuilt from `records` (its
  trajectory records, oldest first). Returns true when it is showing."
  [renderer id agent records]
  (let [c (:cells renderer)]
    (when (and (nil? (get @(:subs c) id)) (seq records))
      ;; what the list says of it is the record's — a rebuilt buffer is
      ;; the live side of `children/merge-live`, and would otherwise hide it
      (let [lst (last records)]
        (swap! (:subs c) assoc id (cond-> {:agent agent :lines [] :at (or (:ts lst) (System/currentTimeMillis))
                                           :turns (count records) :question (:question (first records))
                                           :answer (trajectory/record-answer lst) :success? (boolean (:success lst))}
                                    (some :owner records) (assoc :owner (some :owner records)))))
      (doseq [e (child-entries records)] (sub-say! c id e)))
    (when (get @(:subs c) id)
      (reset! (:viewing c) id)
      (reset! (:offset c) 0)
      true)))

(defn session-children
  "An open session's subagents, live or ended, from its renderer's buffers
  (`children/from-buffers`, byj-gly.298.2)."
  [{:keys [renderer]}]
  (children/from-buffers @(:subs (:cells renderer))))

(def session-groups
  "The list's groups, in the order it shows them: what needs a person first."
  [[:needs-input "Needs input"] [:working "Working"] [:idle "Waiting for you"] [:ended "Ended"]
   ;; sessions saved from earlier runs, not open here: enter resumes one
   ;; (byj-gly.178)
   [:saved "Saved — enter resumes one"]
   ;; this project's Claude Code sessions (byj-gly.287.2, RFC-033): a row that
   ;; starts one, the ones running, then the saved — the new row and the live
   ;; ones under one heading
   [:claude-new "Claude Code"] [:claude-live nil]
   [:claude-saved "Claude Code — saved, enter resumes one"]
   ;; this project's Codex sessions, as Claude Code's (byj-gly.430.4, RFC-049)
   [:codex-new "Codex"] [:codex-live nil]
   [:codex-saved "Codex — saved, enter resumes one"]])

(defn- delegation-state-word [state]
  (case state
    "working" "working" "done" "done" "needs-person" "needs you"
    "waiting" "needs you" "timeout" "still working" "gone" "ended"
    state))

(defn delegation-label
  "What a row says of the agent$claude delegation that drives its session
  (`claude-agent/with-delegations`, byj-gly.290.5): its bead or branch, where it
  stands, and its pull request — or nil."
  [{:keys [bead branch state pr-url]}]
  (when (or bead branch state)
    (str "agent$claude"
         (some->> (or bead branch) (str " "))
         (some->> (some-> state delegation-state-word) (str " · "))
         (when pr-url " · PR open"))))

(def max-child-rows
  "How many of a session's children the list shows under it; the rest are
  counted (byj-gly.298.3)."
  5)

(defn- first-line [s] (some-> s str str/split-lines first str/trim not-empty))

(defn- child-last
  "What a child's row says: the Claude Code session it drives, or what it is
  doing, or how its last turn ended."
  [{:keys [claude live? success? question answer turns]}]
  (cond
    claude (str "→ Claude Code " (or (:title claude) (some-> (:session-id claude) (subs 0 8)))
                (some->> (some-> (:state claude) delegation-state-word) (str " · "))
                (when (:pr-url claude) " · PR open"))
    live? (str "working · " (first-line question))
    :else (str (if success? "answered" "did not answer")
               (when (and turns (> turns 1)) (str " · " turns " turns"))
               (some->> (first-line (or answer question)) (str " · ")))))

(defn child-rows
  "`parent`'s children (`children/tree`) as rows of the list, depth first, at
  most `max-child-rows`: `{:rows [...] :more n}`. A child row is `:state
  :child`, keeps its `:parent` row and `:child`, and an agent$claude child
  carries its delegation, so one that stopped to ask is marked and counted as
  awaiting input (byj-gly.298.3)."
  [parent kids now]
  (let [flat (letfn [(walk [c depth] (cons [c depth] (mapcat #(walk % (inc depth)) (:children c))))]
               (mapcat #(walk % 0) kids))
        shown (take max-child-rows flat)]
    {:rows (vec (for [[c depth] shown]
                  (cond-> {:id [:child (:id parent) (:id c)] :state :child :depth depth
                           :parent parent :child c
                           :title (str (apply str (repeat depth "  ")) "└ " (:id c))
                           :last (child-last c)
                           :age (if (number? (:at c)) (age-label (- now (:at c))) "")}
                    (:claude c) (assoc :delegation (select-keys (:claude c) [:bead :branch :state :pr-url])))))
     :more (max 0 (- (count flat) (count shown)))}))

(defn delegation-asks?
  "Has the agent$claude delegation on `row` stopped to ask a person? Marked
  on its row and counted with the sessions awaiting input (byj-gly.290.8)."
  [row]
  (contains? #{"needs-person" "waiting"} (get-in row [:delegation :state])))

(defn session-counts
  "How many rows stand where, for the list's header: by state, a delegation
  that stopped to ask counted as awaiting input — once, by its Claude Code
  session, though both its subagent's row and that session's row say so
  (byj-gly.304)."
  [rows]
  (let [asking (into #{} (keep #(when (delegation-asks? %)
                                  (or (get-in % [:child :claude :session-id]) (:session-id %) (:id %) %)))
                     rows)]
    (update (frequencies (map :state rows)) :needs-input (fnil + 0) (count asking))))

(defn shareable-row?
  "Can ctrl+s share this row's session (RFC-043)? An open byj session, or a
  live Claude Code or Codex one: a saved session has nothing to watch, and a
  subagent is shared with its parent."
  [row]
  (contains? #{:claude-live :codex-live :needs-input :working :idle} (:state row)))

(def codex-states
  "The states of the Codex rows (byj-gly.430.4)."
  #{:codex-new :codex-live :codex-saved})

(defn claude-session-rows
  "This project's Claude Code sessions as the list's rows (`claude-sessions/
  sessions`'s `{:live :saved}`): the row that starts one first, then the live —
  one running in another terminal says so, since it cannot be opened here —
  then the saved. `attachable?` (session id → bool) says which live ones can be
  opened here."
  [{:keys [live saved]} now attachable?]
  (let [age #(if (number? %) (age-label (- now %)) "")]
    (vec (concat
          [{:id [:claude-new] :state :claude-new :title "new Claude Code session" :last "" :age ""}]
          (for [{:keys [id title status updated-at delegation dir]} live
                :let [d (delegation-label delegation)]]
            (cond-> {:id [:claude id] :state :claude-live :session-id id :status status :dir dir
                     :attachable? (boolean (and attachable? (attachable? id)))
                     :title (or title id)
                     :last (str (or status "running")
                                (when-not (and attachable? (attachable? id)) " · running in another terminal")
                                (some->> d (str " · ")))
                     :age (age updated-at)}
              delegation (assoc :delegation delegation)))
          (for [{:keys [id title updated-at bytes delegation dir]} saved
                :let [d (delegation-label delegation)]]
            (cond-> {:id [:claude-saved id] :state :claude-saved :session-id id :dir dir
                     :title (or title id)
                     :last (str/join " · " (remove str/blank? [(if (number? bytes) (format "%.1f MB" (/ bytes 1e6)) "") d]))
                     :age (age updated-at)}
              delegation (assoc :delegation delegation)))))))

(defn codex-session-rows
  "This project's Codex sessions as the list's rows (`codex-host/with-running`'s
  `{:live :saved}`), as Claude Code's are (byj-gly.430.4): the row that starts
  one first — when `new?`, Codex being installed — then the live, one running
  in another terminal saying so, then the saved. `attachable?` (thread id →
  bool) says which live ones can be opened here."
  [{:keys [live saved]} now attachable? new?]
  (let [age #(if (number? %) (age-label (- now %)) "")]
    (vec (concat
          (when new? [{:id [:codex-new] :state :codex-new :title "new Codex session" :last "" :age ""}])
          (for [{:keys [id title status updated-at dir]} live]
            {:id [:codex id] :state :codex-live :session-id id :status status :dir dir
             :attachable? (boolean (and attachable? (attachable? id)))
             :title (or title id)
             :last (str (or status "running")
                        (when-not (and attachable? (attachable? id)) " · running in another terminal"))
             :age (age updated-at)})
          (for [{:keys [id title updated-at bytes dir]} saved]
            {:id [:codex-saved id] :state :codex-saved :session-id id :dir dir
             :title (or title id)
             :last (if (number? bytes) (format "%.1f MB" (/ bytes 1e6)) "")
             :age (age updated-at)})))))

(defn list-window
  "The first line of a list's window, `rows` high over `n` lines, that keeps
  line `at` in view, moving as little as it can from `top`."
  [top at rows n]
  (let [rows (max 1 rows)
        top (cond (< at top) at
                  (>= at (+ top rows)) (- at (dec rows))
                  :else top)]
    (max 0 (min top (max 0 (- n rows))))))

(defn ordered-rows
  "The rows in the order the list shows them, group by group."
  [rows]
  (vec (for [[state _] session-groups, r rows :when (= state (:state r))] r)))

(def ^:private state-glyph
  {:needs-input "!" :working "●" :idle "○" :ended "·"})

(defn sessions-rail
  "The left rail's view (byj-gly.379): the sessions open in this window, one
  row each — where it stands, its name, how long ago it last spoke — grouped
  as the list groups them, from the list's own rows (`session-row`). `own` is
  the session whose rail it is, marked `›` and where the mark starts; Enter
  on another hands it to `switch!`. The full list behind ← stays: it has room
  for each session's last line and its subagents, the rail has not."
  [entries own switch!]
  {:id "sessions" :title "Sessions"
   :items (fn []
            (let [now (System/currentTimeMillis)]
              (mapv (fn [{:keys [id state title age]}]
                      {:id id :label (str (if (= own id) "› " "  ") (state-glyph state " ") " " title) :hint age})
                    (ordered-rows (map #(session-row % now) @entries)))))
   :mark (fn [items] (or (first (keep-indexed #(when (= own (:id %2)) %1) items)) 0))
   :act (fn [{:keys [id]}] (when (and id (not= id own)) (switch! id)))})

(defn list-rows
  "The session list's rows, as `sessions-view` draws them and the mark moves
  over them: the sessions grouped by where they stand, each followed by its
  subagents (`child-rows`, byj-gly.298.3); the Claude Code rows, every one —
  a session an agent$claude subagent drives is listed under it and in the
  Claude Code group too (byj-gly.304). `{:parents :kids :with-kids :rows}` —
  `:rows` in order."
  [{:keys [entries saved-rows claude-rows codex-rows children-of]} now]
  (let [sessions (concat (map #(session-row % now) @entries)
                         (when saved-rows (saved-rows now)))
        kids (into {} (for [r sessions] [(:id r) (child-rows r (when children-of (children-of r)) now)]))
        parents (ordered-rows (concat sessions (when claude-rows (claude-rows now)) (when codex-rows (codex-rows now))))
        with-kids (fn [r] (cons r (:rows (kids (:id r)))))]
    {:parents parents :kids kids :with-kids with-kids :rows (vec (mapcat with-kids parents))}))

(declare copied-text)

(defn- sessions-view
  "The session list: a header, the sessions grouped by where they stand, and an
  input line that starts a new one."
  [{:keys [entries listing tick size header] :as host} {:keys [open! start! resume! end! back! moved! claude! codex! open-child! claude-of! diff! share! shared? copy!]
                                                     :or {copy! clipboard/copy!}}]
  (let [top (atom 0)
        ;; what the last frame drew, for a click to find its row: the lines
        ;; shown, the rows the mark moves over, the mark, the draft, and
        ;; Enter's own fn
        drawn (atom nil)
        remember-enter (fn [f] (swap! drawn assoc :activate! f) f)
        ;; the left button (pointer-mouse), as on the eval page: a drag
        ;; selects the list's text and the release copies it, kept in
        ;; `listing`; a click on a row marks it, and on the marked row opens
        ;; it as Enter does. The list's lines start under the six header
        ;; lines and the "more" line; the page has no rails to take hold of.
        mouse-fn (pointer-mouse
                  {:press (atom nil)
                   :selection #(:selection @listing)
                   :select! #(swap! listing assoc :selection %)
                   :clear! #(swap! listing dissoc :selection :flash)
                   :copy! (fn [sel]
                            (let [t (copied-text (some-> (tui/tree) w/walk) sel)]
                              (swap! listing assoc :flash (clipboard/describe (copy! t)))))
                   :click! (fn [x y]
                             (let [{:keys [shown rows at draft activate!]} @drawn
                                   [kind r] (when (>= y 7) (nth shown (- y 7) nil))
                                   i (when (= :row kind)
                                       (first (keep-indexed #(when (= (:id %2) (:id r)) %1) rows)))]
                               (cond
                                 (nil? i) (tui/click! x y)
                                 (not= i at) (moved! (- i at) (count rows))
                                 (and activate! (str/blank? draft)) (activate!))))})]
  (fn []
    (let [t @tick
          now (System/currentTimeMillis)
          {:keys [kids parents with-kids rows]} (list-rows host now)
          {:keys [at draft from note diff selection flash]} @listing
          at (max 0 (min (or at 0) (dec (count rows))))
          w (:w @size)
          col (min 36 (reduce max 12 (map #(text/width (:title %)) rows)))
          counts (session-counts rows)
          selected (nth rows at nil)
          ;; the groups as lines — a blank and a heading before each, then its
          ;; rows — so a long list is a window over them (byj-gly.287.2)
          lines (vec (mapcat (fn [[state label]]
                               (let [rs (filter #(= state (:state %)) parents)]
                                 (when (seq rs)
                                   (concat (when label [[:blank] [:heading label]])
                                           (mapcat (fn [r]
                                                     (let [{:keys [more]} (kids (:id r))]
                                                       (concat (mapcat (fn [x] (cons [:row x]
                                                                                     ;; what ctrl+g unfolded under it (byj-gly.326)
                                                                                     (when (and diff (= (:id x) (:id diff)))
                                                                                       (map (fn [l] [:diff l]) (:lines diff)))))
                                                                       (with-kids r))
                                                               (when (and more (pos? more)) [[:more more]]))))
                                                   rs)))))
                             session-groups))
          at-line (or (first (keep-indexed (fn [i [k r]] (when (and (= :row k) (= (:id r) (:id selected))) i)) lines)) 0)
          ;; the rows the list has: the screen less the six header lines, the
          ;; two that count what is above and below, and the five under it
          room (max 3 (- (:h @size) 13))
          ;; a heading comes into view with its first row
          want (if (and (>= at-line 2) (= :heading (first (nth lines (dec at-line) nil)))) (- at-line 2) at-line)
          top-line (reset! top (list-window (if (< want @top) want @top) at-line room (count lines)))
          shown (subvec lines top-line (min (count lines) (+ top-line room)))
          below (max 0 (- (count lines) top-line room))
          page (max 1 (- room 2))]
      (swap! drawn assoc :shown shown :rows rows :at at :draft draft)
      ;; the wheel moves the mark through the list; the button selects and
      ;; copies, or picks a row (mouse-fn)
      (point-input! mouse-fn (fn [_ _ n] (moved! (if (neg? n) -3 3) (count rows)) true))
      (into [:vbox {:on-key (fn [e]
                              (cond
                                (k/match? e "up") (do (moved! -1 (count rows)) true)
                                (k/match? e "down") (do (moved! 1 (count rows)) true)
                                (k/match? e "pgup") (do (moved! (- page) (count rows)) true)
                                (k/match? e "pgdn") (do (moved! page (count rows)) true)
                                (k/match? e "esc") (do (back!) true)
                                ;; → on an agent$claude subagent: the Claude Code session it
                                ;; drives; Enter is its view, as any subagent's (byj-gly.298.4)
                                (and claude-of! (k/match? e "right") (str/blank? draft)
                                     (get-in selected [:child :claude]))
                                (do (when-let [note (claude-of! selected)] (swap! listing assoc :note note)) true)
                                ;; what the marked session changed (byj-gly.326)
                                (and diff! (k/match? e "ctrl+g"))
                                (do (when selected (diff! selected)) true)
                                ;; the row's session — an open byj one or a live
                                ;; Claude Code one — shared through the relay, or
                                ;; no longer (RFC-043)
                                (and share! (k/match? e "ctrl+s") (shareable-row? selected))
                                (do (swap! listing assoc :note (share! selected)) true)
                                (k/match? e "ctrl+x") (do (when-not (#{:saved :claude-new :claude-live :claude-saved :codex-new :codex-live :codex-saved :child} (:state selected))
                                                            (some-> selected :id end!))
                                                          true)
                                :else nil))}]
            (concat
             [[:copy-line {:selection selection :label (str " " (:title header)) :bold true}]
              [:copy-line {:selection selection :label (str " " (:detail header)) :dim true}]
              [:copy-line {:selection selection :label (str " " (get counts :needs-input 0) " awaiting input · "
                                   (get counts :working 0) " working · "
                                   (get counts :idle 0) " waiting · "
                                   (get counts :ended 0) " ended")
                       :dim true}]
              [:copy-line {:selection selection :label ""}]
              [:copy-line {:selection selection :label (if from
                                "Your conversation moved to the background — enter opens it · esc returns to it"
                                "enter opens a session · type a task below to start a new one")}]
              ;; what opening a Claude Code session had to say (byj-gly.287.3)
              [:copy-line {:selection selection :label (if flash (str " " flash) (str note)) :color (if flash :green :yellow)}]]
             [[:copy-line {:selection selection :label (if (pos? top-line) (str "   ↑ " top-line " more") "") :dim true}]]
             (for [[kind x] shown]
               (case kind
                 :blank [:copy-line {:selection selection :label ""}]
                 :heading [:copy-line {:selection selection :label x :bold true :dim true}]
                 :more [:copy-line {:selection selection :label (str "     … " x " more") :dim true}]
                 :diff [:copy-line {:selection selection :label (str "     " (if (> (text/width x) (- w 6)) (str (text/truncate x (- w 7)) "…") x)) :dim true}]
                 :row
                 (let [{:keys [id title last age state status attachable? delegation]} x
                       ;; a delegation that stopped to ask is a person's to answer
                       asks? (delegation-asks? x)
                       on? (= id (:id selected))
                       child (:child x)
                       glyph (case state
                               :child (cond (:live? child) (nth spinner-glyphs (mod t (count spinner-glyphs)))
                                            (and (not (:claude child)) (not (:success? child))) "×"
                                            :else "·")
                               :needs-input "✻"
                               :working (nth spinner-glyphs (mod t (count spinner-glyphs)))
                               :idle "•" :ended "·" :saved "↺"
                               :claude-new "+"
                               :claude-live (if (= "busy" status) (nth spinner-glyphs (mod t (count spinner-glyphs))) "○")
                               :claude-saved "↺"
                               :codex-new "+"
                               :codex-live (if (= "busy" status) (nth spinner-glyphs (mod t (count spinner-glyphs))) "○")
                               :codex-saved "↺"
                               " ")
                       room (max 8 (- w col 12 (count age)))]
                   [:hbox {}
                    [:copy-line {:selection selection :label (str (if on? "›" " ") (if asks? "✻" glyph) " ")
                             :color (if asks?
                                      :yellow
                                      (case state :needs-input :yellow :working :cyan :idle :green
                                            (:claude-live :codex-live) (if attachable? :cyan :default) :default))}]
                    [:copy-line {:selection selection :label (str (text/truncate title col) (spaces (- col (text/width (text/truncate title col)))) "  ")
                             :bold on? :color (if on? :cyan :default)
                             :dim (or (and (#{:claude-live :codex-live} state) (not attachable?))
                                      (and (= :child state) (not on?)))}]
                    [:copy-line {:selection selection :hexpand true :dim true
                             :label (let [last (if (and shared? (shared? x))
                                                 (str "shared · " last)
                                                 last)]
                                      (if (> (text/width last) room) (str (text/truncate last (dec room)) "…") last))}]
                    [:copy-line {:selection selection :label (str " " age) :dim true}]])))
             [[:label {:label (if (pos? below) (str "   ↓ " below " more") "") :dim true}]]
             [[:label {:vexpand true :label ""}]
              [:separator {}]
              [:hbox {:spacing 1}
               [:label {:label "❯" :bold true}]
               [:plain-entry {:text (or draft "") :hexpand true :autofocus true
                              :placeholder "describe a task for a new session"
                              :pass-when-empty ["left" "right"]
                              :on-change #(swap! listing assoc :draft %)
                              :on-activate (remember-enter (fn []
                                             (cond
                                               (not (str/blank? draft))
                                               (do (start! (str/trim draft))
                                                   (swap! listing assoc :draft ""))

                                               ;; a subagent's row: its transcript in
                                               ;; its session, or its Claude Code
                                               ;; session (byj-gly.298.4)
                                               (= :child (:state selected))
                                               (if open-child!
                                                 ;; a note only when there is one: the
                                                 ;; list has closed if the child opened
                                                 (when-let [note (open-child! selected)]
                                                   (swap! listing assoc :note note))
                                                 (let [p (:parent selected)]
                                                   (if (= :saved (:state p))
                                                     (resume! (:session-id p))
                                                     (some-> p :id open!))))

                                               (= :saved (:state selected))
                                               (resume! (:session-id selected))

                                               ;; a Claude Code row: started,
                                               ;; resumed or opened by whoever
                                               ;; runs them (byj-gly.287.3)
                                               (#{:claude-new :claude-live :claude-saved} (:state selected))
                                               (swap! listing assoc :note (some-> claude! (apply [selected])))

                                               ;; a Codex row, the same (byj-gly.430.4)
                                               (codex-states (:state selected))
                                               (swap! listing assoc :note (some-> codex! (apply [selected])))

                                               :else (some-> selected :id open!))))}]]
              [:separator {}]
              [:label {:label (str " enter opens · ↑↓ pgup/pgdn select · esc returns · ctrl+x ends"
                                   (when (and claude-of! (get-in selected [:child :claude])) " · → its Claude Code session")
                                   (when diff! " · ctrl+g its diff")
                                   (when (and share! (shareable-row? selected)) " · ctrl+s shares it")
                                   " · a task starts a new session")
                       :dim true}]]))))))

(defn- wrap-lines
  "`lines` wrapped to `w` cells, at a space where there is one; a wrapped
  line's continuation keeps its indent, and `hang` more (two spaces, unless
  given: \"\" for a paragraph, which reads flush left)."
  ([lines w] (wrap-lines lines w "  "))
  ([lines w hang]
   (let [w (max 10 w)]
     (vec (mapcat (fn [l]
                    (let [indent (re-find #"^\s*" l)]
                      (loop [l l out []]
                        (if (<= (text/width l) w)
                          (conj out l)
                          (let [cut (text/truncate l w)
                                cut (if (str/ends-with? cut "…") (subs cut 0 (dec (count cut))) cut)
                                sp (str/last-index-of cut " ")
                                at (if (and sp (> sp (+ 8 (count indent)))) sp (count cut))]
                            (recur (str indent hang (str/triml (subs l at))) (conj out (subs l 0 at))))))))
                  lines)))))

;; --- selecting and copying a page's text (the eval page, its modal) ------------------------

(def ^:private copy-line-spec
  "A label whose text a mouse drag can select: one line, painted as a label
  is, with what a selection covers of it drawn over in reverse. A label is
  glimmer-tui's and paints nothing of a selection; the transcript's own is
  `transcript-spec`."
  {:container :none
   :measure (fn [props _] {:w (text/width (str (:label props))) :h 1})
   :paint (fn [screen rect props _ _]
            (let [{:keys [x y w]} rect
                  line (str (:label props))
                  shown (text/truncate line w)]
              (scr/put! screen x y (str shown (spaces (- w (text/width shown)))) (w/style props))
              (when-let [[c1 c2] (some-> (:selection props) (row-span y (+ x w)))]
                (let [c1 (max x c1) c2 (min (+ x w) c2)]
                  (when (< c1 c2)
                    (scr/put! screen c1 y (cells-between (str line (spaces w)) (- c1 x) (- c2 x))
                              (assoc (w/style props) :reverse true)))))))})

(defonce ^:private copy-line-registered
  (do (w/register-widget! :copy-line copy-line-spec) true))

(defn copied-text
  "The text a selection covers in the `:copy-line`s of laid-out `nodes`: row
  by row, in reading order, each row's lines left to right; trailing spaces
  dropped."
  [nodes sel]
  (->> nodes
       (filter #(= :copy-line (:tag %)))
       (keep (fn [{:keys [rect props]}]
               (let [{:keys [x y w]} rect]
                 (when-let [[c1 c2] (row-span sel y (+ x w))]
                   (let [c1 (max x c1) c2 (min (+ x w) c2)]
                     (when (< c1 c2)
                       [y x (cells-between (str (:label props)) (- c1 x) (- c2 x))]))))))
       (sort-by (juxt first second))
       (partition-by first)
       (map #(str/trimr (apply str (map last %))))
       (str/join "\n")))

(defn run-many!
  "Hand the terminal to several sessions, one in the foreground. `spawn` makes
  one: `(spawn {:task :on-agents})` → `{:renderer :session-fn :ready-turn}`, the
  renderer made with `:on-agents` so ← opens the list, `:task` the first line it
  is to be asked (nil for the first session, which reads its own). Each session
  runs on a thread of its own; the loop owns this one. Returns each session's
  `{:result :thrown}`, first session first, once the terminal is back.

  A session that ends (/quit) leaves the list; the last one ending ends the
  loop. ctrl-q ends them all.

  The set a run had open can be brought back (byj-gly.178): `spawn`'s result
  may name its `:session-id`, and then
    :initial    the spawn opts of the sessions to start with, in order
                (default one, the run's own: `[{}]`); `{:resume id}` resumes one
    :front      which of them is in front (default the first)
    :on-change  `(f [session-id …] front-session-id)`, whenever the open
                sessions or the one in front change — what to record
    :saved      `(f)` → saved sessions `{:session-id :title :last :updated-at}`
                the list offers to resume; those already open are left out
    :claude-sessions  `(f)` → this project's Claude Code sessions, as
                `claude-sessions/sessions` returns them (byj-gly.287.2)
    :claude-open      `(f row)` — Enter on a Claude Code row: start, resume
                or open it (byj-gly.287.3); nil does nothing
    :claude-attachable?  `(f session-id)` — can that live one be opened here
    :codex-sessions  `(f)` → this project's Codex sessions, as
                `codex-host/with-running` returns them (byj-gly.430.4)
    :codex-open      `(f row)` — Enter on a Codex row: start, resume or open it
    :codex-attachable?  `(f thread-id)` — can that live one be opened here
    :codex-new?      a row that starts a new Codex session is offered: Codex
                is installed
    :child-records  `(f session-id child-id)` → that subagent's trajectory
                records, oldest first: what its transcript is rebuilt from when
                the session's renderer did not keep it (byj-gly.298.4)
    :session-diff  `(f kind session-id dir)` → what that session changed,
                `{:lines [..] :pager argv}` (byj-gly.326): ctrl+g on its row
                unfolds the lines under it, and again hands `:pager` the
                terminal; `dir` is a Claude Code row's own, else nil
    :children   `(f session-id live)` → that session's subagents as a tree
                (`children/tree`), `live` being what its renderer shows when it
                is open (byj-gly.298.3); nil lists only those
    :claude-share  `share/sharer`'s map: ctrl+s on a row shares its session
                through the relay, or stops — an open byj session (`byj:ID`)
                a live Claude Code one (`claude:ID`) or a live Codex one
                (`codex:ID`) (RFC-043);
                the row says it is shared"
  [spawn & {:keys [tick-ms mouse? header initial front on-change saved
                   claude-sessions claude-open claude-attachable?
                   codex-sessions codex-open codex-attachable? codex-new? children child-records session-diff claude-share]
            :or {tick-ms 40 mouse? true initial [{}] front 0}}]
  (let [entries (r/atom [])
        fg (r/atom nil)
        listing (r/atom nil)
        tick (r/atom 0)
        size (r/atom {:w 80 :h 24})
        next-id (atom 0)
        host {:entries entries :listing listing :tick tick :size size
              :header (or header {:title "brainyard" :detail ""})}
        ;; the host as finished below, for open-list!, which is defined first
        host-box (atom host)
        entry-of (fn [id] (some #(when (= id (:id %)) %) @entries))
        live? (fn [id] (let [e (entry-of id)] (boolean (and e (not (:ended? e))))))
        ;; the session a row shares through ctrl+s (RFC-043), or nil
        share-ref (fn [row]
                    (case (:state row)
                      :claude-live (str "claude:" (:session-id row))
                      :codex-live (str "codex:" (:session-id row))
                      (:needs-input :working :idle) (some->> (or (:session-id row) (some-> (:id row) entry-of :session-id))
                                                             (str "byj:"))
                      nil))
        focus! #(tui/after! 0 give-focus-back!)
        open-list! (fn [from]
                     (let [now (System/currentTimeMillis)
                           ;; the rows as the list draws them, children and all,
                           ;; so the mark lands on the session it came from
                           rows (:rows (list-rows @host-box now))
                           at (or (first (keep-indexed #(when (= from (:id %2)) %1) rows)) 0)]
                       (reset! fg nil)
                       (reset! listing {:at at :draft "" :from (when (live? from) from)})
                       (focus!)))
        open! (fn [id]
                (when (live? id)
                  (reset! listing nil)
                  (reset! fg id)
                  (focus!)))
        ended! (fn [id]
                 (swap! entries (fn [es] (mapv #(cond-> % (= id (:id %)) (assoc :ended? true)) es)))
                 (cond
                   (not-any? (complement :ended?) @entries) (tui/quit!)
                   (= id @fg) (open-list! nil)))
        start! (fn start! [spawn-opts]
                 (let [spawn-opts (if (map? spawn-opts) spawn-opts {:task spawn-opts})
                       id (swap! next-id inc)
                       {:keys [renderer session-fn ready-turn session-id]}
                       ;; the left rail is the window's sessions; Enter on one
                       ;; switches to it, whose rail opens on it (byj-gly.379)
                       (spawn (assoc spawn-opts :on-agents #(open-list! id)
                                     ;; session$start (byj-gly.386): another session in this
                                     ;; window with a first question, started on the loop
                                     ;; thread, as one from the list is, and not brought
                                     ;; to the front — the person switches when they will
                                     ;; or a saved one reopened beside it: /fork's (RFC-069)
                                     :start-session (fn [{:keys [task resume]}]
                                                      (let [p (promise)]
                                                        (tui/after! 0 #(deliver p (try (start! (cond-> {:task task} resume (assoc :resume resume)))
                                                                                       (catch Throwable e e))))
                                                        (let [r (deref p 15000 ::late)]
                                                          (cond (= ::late r) {:error "the window did not start it in time"}
                                                                (instance? Throwable r) {:error (str (ex-message r))}
                                                                :else {:session-id (str (:session-id (entry-of r)))}))))
                                     :rails {:left [(sessions-rail entries id
                                                                   (fn [to]
                                                                     (open! to)
                                                                     (some-> (entry-of to) :renderer :cells (open-rail! :left))))]}))
                       ;; the size the run knows, before the session lays out
                       ;; its transcript at the default (byj-gly.286)
                       _ (resize! (:cells renderer) @size)
                       result (atom nil)
                       thrown (atom nil)
                       worker (Thread. (fn []
                                         (try (reset! result (session-fn))
                                              (catch Throwable t (reset! thrown t))
                                              (finally (ended! id)))))]
                   (swap! entries conj {:id id :renderer renderer :ready-turn ready-turn
                                        :session-id session-id
                                        :worker worker :result result :thrown thrown})
                   (.start worker)
                   id))
        stop! (fn [{:keys [renderer]}]
                ((:answer-gate! renderer) "n")
                (when-let [cancel (get @(:cancellers renderer) :turn)]
                  (try (cancel) (catch Throwable _ nil)))
                ((:end-input! renderer)))
        end! (fn [id]
               (when-let [e (entry-of id)]
                 (if (:ended? e)
                   ;; an ended session is only a row: ctrl+x takes it off the list
                   (swap! entries (fn [es] (vec (remove #(= id (:id %)) es))))
                   (stop! e))))
        back! (fn [] (when-let [from (:from @listing)] (open! from)))
        moved! (fn [d n] (swap! listing update :at #(max 0 (min (dec (max 1 n)) (+ (or % 0) d)))))
        resume! (fn [sid] (let [id (start! {:resume sid})] (open! id) id))
        saved-rows (when saved
                     (fn [now]
                       (let [open (set (keep #(when-not (:ended? %) (:session-id %)) @entries))]
                         (vec (for [{:keys [session-id title last updated-at]} (saved)
                                    :when (not (open session-id))]
                                {:id [:saved session-id] :session-id session-id :state :saved
                                 :title (or title session-id) :last (or last "")
                                 :age (age-label (- now (or updated-at now)))})))))
        children-of (fn [row]
                      (let [e (when-not (:session-id row) (entry-of (:id row)))
                            sid (or (:session-id row) (:session-id e))
                            live (when e (session-children e))]
                        (if children
                          (when sid (children sid live))
                          (children/tree (or live [])))))
        host (assoc host :saved-rows saved-rows :children-of children-of
                    :claude-rows (when claude-sessions
                                   (fn [now] (claude-session-rows (claude-sessions) now claude-attachable?)))
                    :codex-rows (when codex-sessions
                                  (fn [now] (codex-session-rows (codex-sessions) now codex-attachable? codex-new?))))
        _ (reset! host-box host)
        ;; Enter on a subagent's row (byj-gly.298.4): an agent$claude child's
        ;; Claude Code session, attached or resumed; any other child's
        ;; transcript, in its session — opened, or resumed when saved — rebuilt
        ;; from its trajectory when the session's renderer did not keep it
        open-child! (fn [{:keys [parent child]}]
                      (let [sid (or (:session-id parent) (some-> (:id parent) entry-of :session-id))
                            eid (if (= :saved (:state parent))
                                  (resume! (:session-id parent))
                                  (do (open! (:id parent)) (:id parent)))
                            e (entry-of eid)
                            records (when (and sid child-records) (child-records sid (:id child)))]
                        (when-not (and e (show-child! (:renderer e) (:id child) (:agent child) records))
                          (str "nothing recorded of " (:id child) " to show"))))
        ;; → on an agent$claude subagent: its Claude Code session, attached
        ;; or resumed (RFC-033)
        claude-of! (when claude-open
                     (fn [{:keys [child]}]
                       (when-let [cl (:claude child)]
                         (claude-open {:state (if (:live? cl) :claude-live :claude-saved)
                                       :session-id (:session-id cl) :title (:title cl)}))))
        ;; ctrl+g on a row (byj-gly.326): what its session changed, under it;
        ;; again on the same row, the whole diff in the person's pager
        diff! (when session-diff
                (fn [row]
                  (let [row (if (= :child (:state row)) (:parent row) row)
                        [kind sid] (case (:state row)
                                     (:claude-live :claude-saved) [:claude-code (:session-id row)]
                                     (:codex-live :codex-saved) [:codex (:session-id row)]
                                     :saved [:brainyard (:session-id row)]
                                     (:claude-new :codex-new) nil
                                     [:brainyard (some-> (:id row) entry-of :session-id)])
                        shown (:diff @listing)]
                    (cond
                      (not sid) (swap! listing assoc :note "no session to diff yet")
                      (and (= (:id row) (:id shown)) (:pager shown))
                      (do (hand-terminal! (:pager shown) nil) (focus!))
                      :else
                      (let [d (try (session-diff kind sid (:dir row)) (catch Throwable e {:lines [(str "no diff: " (ex-message e))]}))]
                        (swap! listing assoc :diff (assoc d :id (:id row))))))))
        list-view (sessions-view host {:open! open! :start! start! :resume! resume! :end! end!
                                       :back! back! :moved! moved! :claude! claude-open :codex! codex-open
                                       :open-child! open-child! :claude-of! claude-of! :diff! diff!
                                       :share! (when claude-share
                                                 (fn [row]
                                                   (if-let [ref (share-ref row)]
                                                     ((:toggle! claude-share) ref (:title row) #(swap! listing assoc :note %))
                                                     "this row's session cannot be shared")))
                                       :shared? (when claude-share
                                                  (fn [row] (boolean (some-> (share-ref row) ((:shared? claude-share))))))})
        live (fn [] (remove :ended? @entries))
        root (fn []
               (tui/after! 30 #(do (reset! loop-thread (Thread/currentThread))
                                   (try (terminal-input! mouse?) (catch Throwable _ nil))))
               (tui/every! 120 (fn []
                                 (swap! tick inc)
                                 (when-let [r (some-> @fg entry-of :renderer)]
                                   (swap! (:tick (:cells r)) inc)
                                   ((:settle-paste! r)))))
               (tui/every! 200 (fn []
                                 (when-let [rect (:rect (tui/tree))]
                                   (let [{:keys [w h]} rect]
                                     (when (and (pos? (or w 0)) (pos? (or h 0)))
                                       (reset! size {:w w :h h})
                                       (doseq [e @entries]
                                         (resize! (:cells (:renderer e)) {:w w :h h})))))))
               (tui/every! tip-every-ms #(doseq [e @entries] (swap! (:tip-at (:cells (:renderer e))) inc)))
               ;; the shares are the process's: one read for every session
               (let [reading (atom false)]
                 (tui/every! share-every-ms #(refresh-shares! reading (map (comp :cells :renderer) @entries))))
               ;; the set open now, and which is in front: what a later run
               ;; resumes (byj-gly.178). Only while the loop runs — ctrl-q ending
               ;; every session afterwards is not the set being closed
               (when on-change
                 (let [last-set (atom ::none)
                       ;; with the list up nothing is in front: the one that
                       ;; was last is what comes back in front
                       last-front (atom nil)]
                   (tui/every! 500 (fn []
                                     (let [open (vec (keep #(when-not (:ended? %) (:session-id %)) @entries))
                                           _ (when-let [sid (some-> @fg entry-of :session-id)] (reset! last-front sid))
                                           in-front (when (some #{@last-front} open) @last-front)
                                           now [open in-front]]
                                       (when (not= now @last-set)
                                         (reset! last-set now)
                                         (on-change open in-front)))))))
               ;; a parked turn resumes in whichever session it belongs to,
               ;; in front or not (RFC-019)
               (tui/every! 250 (fn []
                                 (doseq [{:keys [renderer ready-turn]} (live)
                                         :when ready-turn
                                         :let [c (:cells renderer)]]
                                   (when (and ((:waiting-for-input? renderer)) (nil? @(:gate c)))
                                     (when-let [turn (ready-turn)]
                                       ((:send! renderer) turn false))))))
               (fn []
                 (cond
                   @listing
                   [list-view]
                   :else
                   (if-let [e (some-> @fg entry-of)]
                     [(:view (:renderer e))]
                     [:vbox {}]))))]
    (when-let [s (terminal-size)] (reset! size s))
    (let [ids (mapv start! initial)]
      (reset! fg (nth ids (min front (dec (count ids))))))
    ;; said in the session in front, or the first still running behind the list

    (try
      ;; the entry ncurses starts from: not one whose `rep` it gets wrong
      ;; (byj-gly.269)
      (start-term!)
      ;; nothing a session starts may ask on the terminal now (byj-gly.297)
      (fx/detach-children! true)
      ;; a fault is said in the session in front (byj-gly.266)
      (ui/run root :tick-ms tick-ms :quit-keys #{"ctrl+q"}
              :on-mouse on-mouse :paste-timeout-ms paste-byte-ms
              :on-error (on-error #(some-> (or (some-> @fg entry-of) (first (live))) :renderer :cells)))
      (finally
        (restore-term!)
        (try (print disable-sequences) (flush) (catch Throwable _ nil))
        ;; the loop is gone: every session still running is told so, as run!
        ;; tells its one — the gate denied, the turn cancelled, the input ended
        (doseq [e (live)] (stop! e))
        (doseq [e @entries] (.join ^Thread (:worker e) 5000))
        ;; what they started without the terminal would not hear it close
        (fx/detach-children! false)
        (fx/destroy-owned!)))
    (mapv (fn [{:keys [result thrown]}] {:result @result :thrown @thrown}) @entries)))
