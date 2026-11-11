(ns brainyard.tui-frame-test
  "The full screen's frame (byj-gly.123): how wide each slot is, and a rail's
  own state — pure, so no screen is needed."
  (:require [clojure.test :refer [deftest is testing]]
            [brainyard.tui-frame :as frame]))

(def ^:private views
  [{:id "files" :title "Files" :items (fn [] (mapv #(hash-map :label (str "src/f" % ".clj") :hint "M") (range 30)))}
   {:id "commits" :title "Commits" :items (fn [] [])}])

(deftest the-centre-keeps-its-columns
  (let [closed (frame/closed-rails)
        open (fn [& sides] (reduce #(assoc-in %1 [%2 :open?] true) closed sides))]
    (testing "both rails closed: the whole width is the centre's, as before rails existed"
      (is (= {:center 80 :overlay nil} (frame/layout 80 closed nil))))
    (testing "a rail with room beside the centre takes its columns and a separator"
      (is (= {:center 73 :left 26 :overlay nil} (frame/layout 100 (open :left) :left)))
      (is (= {:center 80 :overlay :left} (frame/layout 80 (open :left) :left))
          "80 columns has no room for one: 53 would be left, under the minimum")
      (is (= {:center 98 :left 26 :right 34 :overlay nil} (frame/layout 160 (open :left :right) nil))))
    (testing "two that would squeeze the centre: the one with the keys stays beside it"
      (is (= {:center 85 :right 34 :overlay nil} (frame/layout 120 (open :left :right) :right)))
      (is (= {:center 93 :left 26 :overlay nil} (frame/layout 120 (open :left :right) nil))))
    (testing "no room beside it at all: the rail draws over the centre, which keeps the width"
      (is (= {:center 70 :overlay :right} (frame/layout 70 (open :right) :right)))
      (is (= {:center 50 :overlay :left} (frame/layout 50 (open :left :right) nil))))
    (testing "the minimum is the centre's, never less"
      (doseq [w (range 40 200) rails [(open :left) (open :right) (open :left :right)] f [nil :left :right]]
        (let [{:keys [center overlay]} (frame/layout w rails f)]
          (is (or overlay (>= center frame/center-min-cols)) (str w " " rails)))))))

(deftest opening-a-rail-closes-the-other-only-when-both-do-not-fit
  (let [left-open (assoc-in (frame/closed-rails) [:left :open?] true)]
    (is (false? (get-in (frame/open-rail left-open :right 120) [:left :open?])))
    (is (true? (get-in (frame/open-rail left-open :right 160) [:left :open?])))
    (is (= 1 (get-in (frame/open-rail (frame/closed-rails) :right 160 1) [:right :view]))
        "a view named opens the rail on it")))

(deftest a-rail-keeps-its-own-mark-and-window
  (let [r (:left (frame/closed-rails))]
    (is (= {:at 0 :top 0} (select-keys (frame/move r -1 30 10) [:at :top])) "not above the first")
    (is (= {:at 12 :top 3} (select-keys (frame/move r 12 30 10) [:at :top])) "the window follows the mark")
    (is (= {:at 29 :top 20} (select-keys (frame/move r 99 30 10) [:at :top])) "not past the last")
    (is (= 0 (frame/view-index views "FILES")))
    (is (= 1 (frame/view-index views "commits")))
    (is (nil? (frame/view-index views "nope")))))

(deftest a-rail-shows-its-views-and-its-items
  (let [lines (frame/rail-lines views {:view 0 :at 1 :top 0} 20 6 true)]
    (is (= 6 (count lines)) "no more rows than it has")
    (is (every? #(= 20 (count (first %))) lines) "every row its exact width, so a repaint erases what was there")
    (is (= "[Files] Commits" (clojure.string/trimr (ffirst lines))) "the view shown is in brackets")
    (is (= {:reverse true} (second (nth lines 3))) "the mark, reversed while the rail has the keys")
    (is (clojure.string/ends-with? (first (nth lines 3)) " M") "a hint at the right edge")
    (is (= {:bold true} (second (nth (frame/rail-lines views {:view 0 :at 1 :top 0} 20 6 false) 3)))
        "bold without them"))
  (is (clojure.string/includes? (first (nth (frame/rail-lines views {:view 1} 20 6 false) 2)) "(nothing here)")))

(deftest views-stack-as-sections
  (let [acted (atom [])
        a {:title "Project" :items (fn [] [{:label "▸ Agents" :group :agents}])
           :act #(swap! acted conj [:a %]) :on-show #(swap! acted conj :a-shown)}
        b {:title "Sessions" :items (fn [] [{:label "one" :id 1} {:label "two" :id 2}])
           :act #(swap! acted conj [:b %]) :mark (fn [items] 1)}
        s (frame/stack "demo" [a b])
        items ((:items s))]
    (is (= ["▾ Project" "▸ Agents" "▾ Sessions" "one" "two"] (mapv :label items)) "each under its title, in order, open")
    (is (= 4 ((:mark s) items)) "the mark where the sessions view puts it, in the whole list")
    ((:act s) (nth items 1))
    ((:act s) (nth items 4))
    (is (= [[:a {:label "▸ Agents" :group :agents}] [:b {:label "two" :id 2}]] @acted)
        "a row does what its own view does with it")
    ((:on-show s))
    (is (= :a-shown (last @acted)))
    (is (= {:bold true :color :cyan} (second (nth (frame/rail-lines [s] {:at 1} 20 8 false) 2)))
        "a heading keeps its own style")
    (testing "a heading folds its section and unfolds it (byj-gly.456)"
      (reset! acted [])
      ((:act s) (nth items 0))
      (let [items ((:items s))]
        (is (= ["▸ Project" "▾ Sessions" "one" "two"] (mapv :label items)) "Project's rows gone, its heading ▸")
        (is (= 3 ((:mark s) items)) "the mark still on the session, its index as the list is now")
        (is (= [0 1] (frame/section-stops s)) "a folded heading is still a stop")
        ((:act s) (nth items 1))
        (is (= ["▸ Project" "▸ Sessions"] (mapv :label ((:items s)))) "both folded")
        (is (nil? ((:mark s) ((:items s)))) "a folded view's mark puts the mark nowhere")
        ((:act s) (first ((:items s))))
        (is (= ["▾ Project" "▸ Agents" "▸ Sessions"] (mapv :label ((:items s)))) "unfolded again, the other kept folded"))
      (is (= [] @acted) "folding acts on no view"))))

(deftest the-rails-are-kept-as-they-were-left
  ;; byj-gly.457: open?, view and width, nothing of the mark
  (let [rails (-> (frame/closed-rails)
                  (frame/open-rail :left 200)
                  (frame/resize :left 6 200)
                  (assoc-in [:left :at] 7)
                  (assoc-in [:right :view] 2))]
    (is (= {:left {:open? true :view 0 :cols 32} :right {:open? false :view 2}} (frame/kept-rails rails))))
  (let [saved {:left {:open? true :view 0 :cols 40} :right {:open? true :view 1 :cols 50}}
        counts {:left 1 :right 3}]
    (is (= {:left {:open? true :view 0 :at 0 :top 0 :cols 40} :right {:open? true :view 1 :at 0 :top 0 :cols 50}}
           (frame/restore-rails (frame/closed-rails) saved 200 counts))
        "both, where both fit beside the centre")
    (is (= [true false] (map #(get-in (frame/restore-rails (frame/closed-rails) saved 120 counts) [% :open?]) [:left :right]))
        "the left first, where only one fits")
    (is (= [false false] (map #(get-in (frame/restore-rails (frame/closed-rails) saved 80 counts) [% :open?]) [:left :right]))
        "none that would be drawn over the centre: it would cover the input")
    (is (= 0 (get-in (frame/restore-rails (frame/closed-rails) (assoc-in saved [:right :view] 5) 200 counts) [:right :view]))
        "a view the side no longer has is its first")
    (is (false? (get-in (frame/restore-rails (frame/closed-rails) saved 200 {:left 1 :right 0}) [:right :open?]))
        "a side with no views stays closed")
    (is (= frame/min-rail-cols (get-in (frame/restore-rails (frame/closed-rails) (assoc-in saved [:left :cols] 3) 200 counts) [:left :cols]))
        "a width within the bounds")
    (is (= (frame/closed-rails) (frame/restore-rails (frame/closed-rails) nil 200 counts)) "nothing kept: as they start")))

(deftest a-stacks-folds-are-its-sections-titles
  ;; byj-gly.457: kept by title, so a section that comes or goes leaves the others'
  (let [kept-in (atom {})
        kept (fn [k init] (let [c (atom (get @kept-in k init))]
                            (add-watch c ::k (fn [_ _ _ n] (swap! kept-in assoc k n)))
                            c))
        a {:title "Project" :items (fn [] [{:label "x"}])}
        b {:title "Issues" :items (fn [] [{:label "y"}])}
        c {:title "Memory" :items (fn [] [{:label "z"}])}
        s (frame/stack "p" [a b c] {:kept kept})]
    ((:act s) (nth ((:items s)) 4))                     ; Memory's heading
    (is (= {:stack/folded #{"Memory"}} @kept-in))
    (let [s2 (frame/stack "p" [a c] {:kept kept})]
      (is (= ["▾ Project" "x" "▸ Memory"] (mapv :label ((:items s2)))) "Issues gone, Memory still folded"))))

(deftest a-row-may-come-in-pieces
  ;; byj-gly.398: the graph's lanes, each in its colour, fitted to the rail
  (let [segs [["  " nil] ["●" {:color :cyan}] ["─" {:color :magenta}] ["╮" {:color :magenta}] [" abc1234 a long subject" nil]]
        v [{:id "g" :title "Graph" :items (fn [] [{:label "first"} {:label "  ●─╮ abc1234 a long subject" :segments segs :hint "↑"}])}]
        [_ _ plain row] (frame/rail-lines v {:view 0 :at 0 :top 0} 20 6 true)
        [_ _ _ marked] (frame/rail-lines v {:view 0 :at 1 :top 0} 20 6 true)]
    (is (nil? (get plain 2)) "a row without pieces is one style")
    (is (= [["  " nil] ["●" {:color :cyan}] ["─" {:color :magenta}] ["╮" {:color :magenta}] [" abc1234 a lo" nil] [" ↑" nil]]
           (get row 2))
        "the pieces cut to the row, the hint after them")
    (is (= 20 (reduce + (map (comp count first) (get row 2)))) "and exactly as wide as the rail")
    (is (= [{:reverse true} nil] [(second marked) (get marked 2)]) "the marked row is one reversed style")
    (testing "a hint in its own colour, as an issue's blocker is red (⊣ id)"
      (let [v [{:id "g" :title "Graph" :items (fn [] [{:label "first"}
                                                     {:label "  ○ a title" :segments [["  ○ a title" nil]]
                                                      :hint "⊣ byj-1" :hint-style {:color :red}}])}]
            [_ _ _ row] (frame/rail-lines v {:view 0 :at 0 :top 0} 24 6 true)]
        (is (= [" ⊣ byj-1" {:color :red}] (last (get row 2))))))))

(deftest a-rail-is-resized-within-bounds
  ;; byj-gly.399
  (let [open (fn [& sides] (reduce #(assoc-in %1 [%2 :open?] true) (frame/closed-rails) sides))]
    (is (= 30 (frame/cols-of (frame/resize (open :left) :left 4 120) :left)))
    (is (= {:center 89 :left 30 :overlay nil} (frame/layout 120 (frame/resize (open :left) :left 4 120) :left))
        "the layout gives it the width it was given")
    (is (= frame/min-rail-cols (frame/cols-of (frame/resize (open :left) :left -100 120) :left)))
    (is (= 59 (frame/cols-of (frame/resize (open :left) :left 100 120) :left))
        "beside the centre: as wide as leaves the centre its 60")
    (is (= 64 (frame/cols-of (frame/resize (open :left :right) :left 100 160) :left))
        "with the other rail open, both beside it: 160 less the centre's 60 and the right's 35")
    (is (= 79 (frame/cols-of (frame/resize (open :left) :left 100 80) :left))
        "too wide to sit beside: drawn over the centre, up to the screen less a column")
    (is (= 24 (frame/cols-of (frame/resize (open :left) :left -2 80) :left)) "and narrowed from where it is")))

(deftest a-rails-separator-is-found-and-dragged
  (let [open (fn [& sides] (reduce #(assoc-in %1 [%2 :open?] true) (frame/closed-rails) sides))]
    (testing "the separator is the column between a rail and the centre"
      (is (= :left (frame/separator-at 120 (open :left) nil 26)))
      (is (nil? (frame/separator-at 120 (open :left) nil 25)) "the rail's own last column is not it")
      (is (= :right (frame/separator-at 120 (open :right) nil 85)) "120 less the right's 34 and a column")
      (is (nil? (frame/separator-at 120 (frame/closed-rails) nil 26)) "a closed rail has none"))
    (testing "a rail over the centre has one only while it is drawn, with the keys"
      (is (= :left (frame/separator-at 80 (open :left) :left 26)))
      (is (nil? (frame/separator-at 80 (open :left) nil 26))))
    (testing "a drag puts the inner edge where it was let go, within resize's bounds"
      (is (= 40 (frame/cols-of (frame/drag-edge (open :left) :left 40 120) :left)))
      (is (= 39 (frame/cols-of (frame/drag-edge (open :right) :right 80 120) :right)))
      (is (= 59 (frame/cols-of (frame/drag-edge (open :left) :left 100 120) :left)) "the centre keeps its 60")
      (is (= frame/min-rail-cols (frame/cols-of (frame/drag-edge (open :left) :left 3 120) :left))))))

(deftest a-click-finds-the-rail-and-what-it-is-on
  ;; byj-gly.449
  (let [open (fn [& sides] (reduce #(assoc-in %1 [%2 :open?] true) (frame/closed-rails) sides))
        views [{:id "files" :title "Files" :items (fn [] (mapv #(hash-map :label (str %)) (range 10)))}
               {:id "source" :title "Source" :items (fn [] [])}]]
    (testing "a rail's own columns, never its separator or the centre"
      (is (= [:left 0] (frame/rail-at 120 (open :left) nil 0)))
      (is (= [:left 25] (frame/rail-at 120 (open :left) nil 25)))
      (is (nil? (frame/rail-at 120 (open :left) nil 26)) "the separator")
      (is (nil? (frame/rail-at 120 (open :left) nil 60)) "the centre")
      (is (= [:right 0] (frame/rail-at 120 (open :right) nil 86)) "past the right's separator at 85")
      (is (= [:right 33] (frame/rail-at 120 (open :right) nil 119)))
      (is (nil? (frame/rail-at 120 (frame/closed-rails) nil 3)) "a closed rail is not there"))
    (testing "a rail over the centre is there only while it is drawn, with the keys"
      (is (= [:left 3] (frame/rail-at 80 (open :left) :left 3)))
      (is (nil? (frame/rail-at 80 (open :left) nil 3))))
    (testing "the header's names, the shown one in brackets: [Files] Source"
      (let [rail {:open? true :view 0 :at 0 :top 0}]
        (is (= {:view 0} (frame/click-target views rail 10 0 0)) "its bracket")
        (is (= {:view 0} (frame/click-target views rail 10 0 6)))
        (is (nil? (frame/click-target views rail 10 0 7)) "the space between")
        (is (= {:view 1} (frame/click-target views rail 10 0 8)))
        (is (= {:view 1} (frame/click-target views rail 10 0 13)))
        (is (nil? (frame/click-target views rail 10 0 14)) "past the names")
        (is (nil? (frame/click-target [(first views)] rail 10 0 1)) "one view's title is no choice")))
    (testing "the rule is nothing; an item's row is that item, from the window's top"
      (is (nil? (frame/click-target views {:view 0 :top 0} 10 1 3)))
      (is (= {:item 0} (frame/click-target views {:view 0 :top 0} 10 2 3)))
      (is (= {:item 7} (frame/click-target views {:view 0 :top 4} 10 5 3)))
      (is (nil? (frame/click-target views {:view 0 :top 4} 10 9 3)) "past the items")
      (is (nil? (frame/click-target views {:view 0 :top 0} 10 10 3)) "past the rail")
      (is (nil? (frame/click-target views {:view 1 :top 0} 10 2 3)) "a view with none"))))

;; byj-gly.449: the wheel moves the window, never the mark
(deftest the-wheel-moves-a-rails-window-and-not-its-mark
  (is (= {:at 0 :top 3} (frame/scroll {:at 0 :top 0} 3 30 12)))
  (is (= 0 (:top (frame/scroll {:at 5 :top 2} -9 30 12))) "never above the first item")
  (is (= 18 (:top (frame/scroll {:at 5 :top 2} 99 30 12))) "never past where the last fills the window")
  (is (= 0 (:top (frame/scroll {:at 0 :top 0} 3 5 12))) "items that fit do not scroll"))

(deftest tab-stops-at-each-section-of-the-view-shown
  ;; byj-gly.404, byj-gly.406: a view's section headings, never another view
  (let [files {:id "files" :title "Files" :items (fn [] [{:label "a"} {:label "b"}])}
        source {:id "source" :title "Source"
                :items (fn [] [{:label "▾ Changes" :section :changes} {:label "x"}
                               {:label "▾ Commits" :section :commits} {:label "y"} {:label "z"}])}
        views [files source]]
    (is (= [0 2] (frame/section-stops source)))
    (is (= [] (frame/section-stops files)))
    (is (= {:view 1 :at 2 :top 0} (frame/next-section {:view 1 :at 0 :top 0} views 1 10)))
    (is (= {:view 1 :at 0 :top 0} (frame/next-section {:view 1 :at 3 :top 0} views 1 10)) "wrapping, in the same view")
    (is (= {:view 1 :at 0 :top 0} (frame/next-section {:view 1 :at 1 :top 0} views -1 10))
        "shift+Tab: back to the heading of the section the mark is in")
    (is (= {:view 0 :at 1 :top 0} (frame/next-section {:view 0 :at 1 :top 0} views 1 10)) "a view with no sections: nowhere to go")
    (is (= {:view 1 :at 2 :top 2} (frame/next-section {:view 1 :at 0 :top 0} views 1 1)) "the window follows the mark")
    (testing "a stack's headings are its sections"
      (is (= [0 2] (frame/section-stops (frame/stack "p" [{:title "Project" :items (fn [] [{:label "x"}])}
                                                          {:title "Sessions" :items (fn [] [{:label "y"}])}])))))))

(deftest each-view-has-a-letter
  ;; byj-gly.406: the first letter of its title no earlier view took
  (is (= [\f \s] (frame/view-keys [{:title "Files"} {:title "Source"}])))
  (is (= [\f \o \l] (frame/view-keys [{:title "Files"} {:title "Folders"} {:title "Fol"}])))
  (is (= 1 (frame/view-for-key [{:title "Files"} {:title "Source"}] \S)) "either case")
  (is (nil? (frame/view-for-key [{:title "Files"} {:title "Source"}] \x)))
  (testing "the header draws each letter underlined, the shown view in brackets"
    (let [[[text style segments]] (frame/rail-lines [{:title "Files" :items (fn [] [])} {:title "Source" :items (fn [] [])}]
                                                   {:view 1 :at 0 :top 0} 20 4 true)
          hl {:bold true :color :cyan}]
      (is (= "Files [Source]" (clojure.string/trimr text)))
      (is (= [["F" (assoc hl :underline true)] ["iles" hl] [" " hl]
              ["[" hl] ["S" (assoc hl :underline true)] ["ource" hl] ["]" hl] ["      " nil]]
             segments)))))

(deftest a-following-mark-is-resolved-from-the-items-as-they-are
  ;; byj-gly.405
  (let [items (atom [{:id "a"} {:id "me"}])
        views [{:items #(deref items) :mark (fn [its] (first (keep-indexed #(when (= "me" (:id %2)) %1) its)))}]]
    (is (= 1 (:at (frame/resolve-mark views {:view 0 :at 0 :top 0 :follow-mark? true} 10))))
    (swap! items #(into [{:id "x"} {:id "y"}] %))
    (is (= 3 (:at (frame/resolve-mark views {:view 0 :at 1 :top 0 :follow-mark? true} 10))) "the items grew above it")
    (is (= 1 (:at (frame/resolve-mark views {:view 0 :at 1 :top 0} 10))) "not following: as it is")))

(deftest a-hint-never-takes-the-row
  ;; byj-gly.412: a long hint left its label no room at all
  (let [v [{:title "T" :items (fn [] [{:label "  ○ btop" :hint "Processes, CPU, memory, disks and network"}])}]
        [_ _ [text]] (frame/rail-lines v {:view 0 :at 9 :top 0} 34 4 false)]
    (is (clojure.string/starts-with? text "  ○ btop"))
    (is (= 34 (count text)))))
