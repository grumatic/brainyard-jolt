;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

;; How far apart do keystrokes arrive when they are PASTED, against when they
;; are typed? The answer decides whether a renderer can tell one from the other
;; without bracketed paste, which is what `brainyard.tui/paste-gap-ms` is
;; (byj-gly.97, upstream byj-7rz).
;;
;;     jolt -A:dev -e '(load-file "dev/brainyard/dev/paste_probe.clj")'
;;
;; It needs a terminal AND something to type into it, so it is a load-file
;; script rather than a namespace: drive it from a pty that writes a multi-line
;; block in one go, waits, then sends a character, a pause and a return. The
;; gaps land in /tmp/paste-gaps.edn. Measured on this machine:
;;
;;     pasted   chars 1-7 ms apart, the newline inside the paste 1-2 ms after
;;     typed    chars 413 ms apart, the return 608 ms after
;;
;; Not a namespace and not in test-order on purpose: it takes over the terminal
;; and quits itself after 12 s.
(require '[glimmer.core :as ui] '[glimmer.ratom :as r] '[glimmer-tui.core :as tui])

(def events (atom []))
(def last-t (atom (System/nanoTime)))

(defn- mark [kind]
  (let [now (System/nanoTime)]
    (swap! events conj [kind (quot (- now @last-t) 1000000)])
    (reset! last-t now)))

(def draft (r/atom ""))

(defn app []
  [:vbox {}
   [:label {:label "paste probe"}]
   [:entry {:text @draft :autofocus true
            :on-change (fn [t] (reset! draft t) (mark :char))
            :on-activate (fn [] (mark :enter))}]])

(ui/run app :tick-ms 40 :quit-keys #{"ctrl+q"} :auto-quit-ms 12000)
(spit "/tmp/paste-gaps.edn" (pr-str @events))
