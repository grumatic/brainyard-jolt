;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

;; Does a view that throws while rendering end the TUI? (byj-gly.236)
;;
;;     jolt -A:dev -e '(load-file "dev/brainyard/dev/render_fault_probe.clj")'
;;
;; Needs a terminal and something to type into it, so it is a load-file script
;; rather than a namespace: drive it from a pty that waits, types `x`, waits, and
;; sends ctrl-q. `x` turns the view into one whose render throws. The outcome
;; lands in /tmp/render-fault.log:
;;
;;     fault: render blew up          the fault was said, once
;;     loop: quit normally            ctrl-q still worked afterwards
;;
;; or, when the fault escapes glimmer-tui's loop,
;;
;;     loop: ended by render blew up  the session would be over
;;
;; The throw happens in a re-render the loop DEFERS: a cell changes, glimmer
;; queues the component through the backend's :schedule, and glimmer-tui's
;; private drain! runs the queue at the top of the next tick — guarded since
;; glimmer-tui v0.2.8, which hands the fault to :on-error (byj-gly.266).
(require '[brainyard.tui]
         '[glimmer.core :as ui]
         '[glimmer.ratom :as r]
         '[glimmer-tui.keys :as k])

(def ^:private log "/tmp/render-fault.log")
(spit log "")
(defn- note [s] (spit log (str s "\n") :append true))

(def ^:private state (r/atom :ok))

(defn- view []
  (if (= :boom @state)
    (throw (ex-info "render blew up" {}))
    [:vbox {:on-key (fn [e] (when (k/match? e "x") (reset! state :boom) true))}
     [:label {:label "press x to break the view, ctrl-q to quit"}]
     [:button {:label "focus" :autofocus true}]]))

;; brainyard.tui's run! hands glimmer-tui an :on-error that says the fault in
;; the session (byj-gly.266); here it is noted instead
(try
  (ui/run (fn [] [view]) :tick-ms 30 :quit-keys #{"ctrl+q"}
          :on-error (fn [t _phase] (note (str "fault: " (ex-message t)))))
  (note "loop: quit normally")
  (catch Throwable t (note (str "loop: ended by " (ex-message t)))))
