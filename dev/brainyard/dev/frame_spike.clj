(ns brainyard.dev.frame-spike
  "What the three-slot frame looks like (byj-gly.123): the real renderer, a
  short transcript in the centre, a rail each side, at 80, 120 and 160 columns.

      jolt -A:dev -m brainyard.dev.frame-spike

  The frame is `brainyard.tui-frame`: a left rail, the centre — the whole
  screen as it was — and a right rail, both rails hidden until toggled. What
  goes in a rail is the caller's (RFC-001); this fills them with plausible
  views so the shape can be read.

  Read the frames rather than the code. What they argued, and what
  `tui-frame` therefore says:

  - The centre's minimum is 60 columns. At 80 a rail beside it would leave 53
    (left, 26) or 45 (right, 34), where a sentence of the transcript is three
    or four words a row; so at 80 a rail draws OVER the centre, which keeps
    its width, and Esc or the toggle puts it away again.
  - At 120 one rail fits beside the centre (93 or 85 left), both do not
    (59), so opening the second closes the first.
  - At 160 both fit, leaving 98.
  - The rails are 26 and 34 wide: the left holds a short name per row
    (sessions), the right a path or a commit's subject, which needs more —
    at 24 a commit subject was its hash and two words."
  (:require [clojure.string :as str]
            [brainyard.tui :as tui]
            [glimmer-tui.core :as g]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.widget :as w]
            [glimmer.core :as ui]))

(def ^:private views
  {:left [{:id "sessions" :title "Sessions"
           :items (fn [] [{:label "› this one" :hint "now"} {:label "review the parser" :hint "3m"}
                          {:label "release notes" :hint "1h"}])}]
   :right [{:id "files" :title "Files"
            :items (fn [] [{:label "src/brainyard/tui.clj" :hint "M"} {:label "src/brainyard/tui_frame.clj" :hint "A"}
                           {:label "test/brainyard/tui_test.clj" :hint "M"}])}
           {:id "changes" :title "Changes" :items (fn [] [{:label "+212 −14 in 4 files"}])}
           {:id "commits" :title "Commits"
            :items (fn [] [{:label "44c7e98 Recaps run on the light model"} {:label "6a62259 Agent shares say what they reach"}])}]})

(defn- frame-at [cols rows keys]
  (let [r (tui/make {:rails views})
        root (w/node :window {})
        screen (scr/buffer-screen cols rows)]
    (g/attach! root screen)
    (ui/mount root :window [(:view r)])
    (g/frame!)
    (tui/measure! r)
    ((:emit r) :you "what changed in the renderer today?")
    ((:emit r) :answer "The frame has three slots now: a rail each side of the transcript, both hidden until ctrl+l or ctrl+t opens one. The centre keeps at least sixty columns; a rail with no room beside it draws over the centre instead.")
    (g/frame!)
    (doseq [k keys] (g/press! k) (g/frame!))
    (g/frame!)
    {:lines (scr/lines screen) :center (:w @(:size (:cells r)))}))

(defn- show [cols rows label keys]
  (let [{:keys [lines center]} (frame-at cols rows keys)]
    (println (str "\n── " cols "x" rows " · " label " · centre " center " "
                  (apply str (repeat (max 0 (- 40 (count label))) "─"))))
    (doseq [l lines] (println (str "│" (str/trimr l))))))

(defn -main [& _]
  (println "The three-slot frame on the real renderer. ctrl+l is the left rail, ctrl+t the right.")
  (doseq [cols [80 120 160]]
    (show cols 14 "both closed" [])
    (show cols 14 "left (ctrl+l)" [12])
    (show cols 14 "right (ctrl+t)" [20])
    (show cols 14 "left, then right" [12 27 20]))
  (println)
  (System/exit 0))
