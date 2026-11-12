;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

;; What a terminal's input method sends, and what the screen then holds.
;;
;; Ghostty drew typed Korean right; Orca broke the box around the field. Run
;; this in the terminal that breaks, type a few Korean syllables into the
;; field (and one cmd+v), then ctrl+q:
;;
;;   jolt -A:test -e '(load-file "dev/brainyard/dev/ime_probe.clj")'
;;
;; It is the analytics report's modal, with every input event the field gets
;; written to /tmp/ime-probe.log as it arrives — its type, its key or text and
;; each character's code point — and, after each, the field's text. Compare
;; the log with what the screen showed.
(require '[brainyard.tui :as tui]
         '[clojure.string :as str]
         '[glimmer.core :as ui]
         '[glimmer.ratom]
         '[glimmer-tui.core :as g]
         '[glimmer-tui.widget :as w])

(def log-file "/tmp/ime-probe.log")
(spit log-file (str "ime-probe " (java.time.Instant/now) " TERM=" (System/getenv "TERM")
                    " TERM_PROGRAM=" (System/getenv "TERM_PROGRAM") "\n"))

(defn- cps [s] (str/join " " (map #(format "U+%04X" (int %)) (str s))))

(let [spec (w/spec-for :text-area)]
  (w/register-widget! :text-area
                      (assoc spec :key (fn [wid e ctx]
                                         (let [r ((:key spec) wid e ctx)]
                                           (spit log-file
                                                 (str (System/currentTimeMillis) " "
                                                      (pr-str (dissoc e :raw))
                                                      (when-let [t (or (:text e) (some-> (:ch e) str))] (str "  [" (cps t) "]"))
                                                      "  -> " (pr-str (get-in @wid [:state :value])) "\n")
                                                 :append true)
                                           r)))))

(let [evaling (glimmer.ratom/atom {:title "ime probe" :at 0 :focus :reports :modal {:text "" :format :md}
                                   :reports {:reports [] :default-instruction "Type Korean here, then ctrl+q." :est-usd 0 :model "-"}
                                   :data {:status ["the page behind the modal"] :summary []
                                          :tasks [{:line "100 done cleanly  A" :detail ["# A"] :text "A"}]}})
      view (#'tui/eval-page-view {:size (atom {:w 100 :h 30})} evaling
                                 {:back! identity :moved! (fn [_ _]) :open-text! identity :report-new! (fn [_ _])})]
  (g/after! 30 #(#'tui/terminal-input! false))
  (ui/run (fn [] [view]) :quit-keys #{"ctrl+q"}))

(println (str "logged to " log-file))
