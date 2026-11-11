;; Do SGR mouse reports reach brainyard.tui's reader as bytes, whatever this
;; terminal's terminfo says? (byj-gly.268, provenance P-24)
;;
;; Needs a terminal and something to type into it, so it is a load-file script
;; rather than a namespace. Drive it from a pty that sends reports and a
;; keystroke; what arrived lands in /tmp/mouse-probe.edn:
;;
;;     (sleep 5; printf '\033[<0;10;5M\033[<0;10;5m\033[<32;11;5M\033[<64;11;5M'; sleep 0.3; printf 'ab') |
;;       TERM=xterm-ghostty TERMINFO=/Applications/Ghostty.app/Contents/Resources/terminfo \
;;       script -q /dev/null jolt -A:dev -e '(load-file "dev/brainyard/dev/mouse_probe.clj")'
;;
;; Right: :draft "ab" — every report decoded, none typed — and :raw starting
;; 27 91 60 (ESC [ <). Wrong, as before byj-gly.268 under Ghostty's kmous=\E[<:
;; :raw [409 97 98], one KEY_MOUSE for the whole burst and the reports gone. And
;; wrong as P-24 was: :draft holding `<0;10;5M…`, the reports typed.
;;
;; It exercises the reader, not the binary: P-24 was the binary alone, and is
;; checked by driving `target/byj run -p mock` the same way.
(require '[brainyard.tui] '[glimmer.core :as ui] '[glimmer.ratom :as r]
         '[glimmer-tui.core :as tui] '[glimmer-tui.curses :as curses])

(def ^:private out "/tmp/mouse-probe.edn")
(def ^:private raw (atom []))
(def ^:private events (atom []))
(def ^:private draft (r/atom ""))

(let [read-key curses/read-key]
  (alter-var-root #'curses/read-key
                  (fn [_] (fn [w t] (let [c (read-key w t)] (when c (swap! raw conj c)) c)))))
(let [read-event tui/read-event!]
  (alter-var-root #'tui/read-event!
                  (fn [_] (fn [w t] (let [e (read-event w t)] (when e (swap! events conj e)) e)))))

;; what brainyard.tui's run! does 30 ms in
(tui/after! 30 #(#'brainyard.tui/terminal-input! true))
(ui/run (fn [] [:vbox {} [:label {:label "mouse probe"}]
                [:entry {:text @draft :autofocus true :on-change #(reset! draft %)}]])
        :tick-ms 40 :quit-keys #{"ctrl+q"} :auto-quit-ms 9000)
(spit out (pr-str {:draft @draft :raw @raw :events @events}))
