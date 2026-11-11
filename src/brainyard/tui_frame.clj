(ns brainyard.tui-frame
  "The full screen's frame (RFC-028 \"The frame\", byj-gly.123): three slots —
  a left rail, the centre, a right rail — and the rules for how wide each is.

  The centre is today's whole screen and always shown. Each rail starts hidden
  and holds views the CALLER passes in, the way `:commands` and `:completions`
  are passed (RFC-001): the frame does not know what a session, a file or a
  commit is. A view is

      {:id \"files\" :title \"Files\"
       :items (fn [] [{:label \"src/a.clj\" :hint \"M\"} …])   ; cheap: reads the caller's state;
                                                               ; an item's :segments [[text style] …],
                                                               ; when it has them, are its label in
                                                               ; pieces styled apart (byj-gly.398), and
                                                               ; its :hint-style the hint's colour
       :act   (fn [item] …)                                    ; Enter or a click on an item; optional
       :mark  (fn [items] index)                               ; where the mark goes when it is shown; optional
       :on-show (fn [] …)                                      ; it is coming into sight: reread; optional
       :aliases [\"changes\" …]                               ; other names `view-index` finds it by; optional
       :on-key (fn [item event] …)}                            ; a key the view takes for itself on the
                                                               ; marked item, truthy when it did; optional

  `:items` is called while the screen paints, so it must not block: whatever
  reads files or git does so elsewhere, under `via blk` (RFC-009), and leaves
  its answer where `:items` reads it.

  Everything here is a pure function of the screen's width and the rails'
  state, so the widths are argued in `dev/brainyard/dev/frame_spike.clj` and
  tested without a terminal."
  (:require [clojure.string :as str]
            [glimmer-tui.text :as text]))

(def center-min-cols
  "The narrowest the centre may be made by a rail beside it. Below this a
  transcript line holds a few words and a code block wraps on every line; the
  spike's frames at 80 columns put it here (byj-gly.123)."
  60)

(def rail-cols
  "How wide each rail is: the left holds a short list (sessions), the right a
  path or a commit's subject, which needs more."
  {:left 26 :right 34})

(def min-rail-cols
  "The narrowest a rail may be made (byj-gly.399): a heading and a short name."
  16)

(def resize-step
  "How many columns one ← or → moves a rail's edge (byj-gly.404; a
  shift+arrow before)."
  2)

(defn cols-of
  "How wide `side`'s rail is in `rails`: what it was resized to, else its
  default (`rail-cols`)."
  [rails side]
  (or (get-in rails [side :cols]) (rail-cols side)))

(def sides [:left :right])

(defn other [side] (if (= side :left) :right :left))

(defn closed-rails
  "Both rails hidden, each on its first view at its top."
  []
  {:left {:open? false :view 0 :at 0 :top 0}
   :right {:open? false :view 0 :at 0 :top 0}})

(defn- fits-beside? [screen-w widths]
  ;; a rail beside the centre takes its width and a column for the separator
  (>= (- screen-w (reduce + 0 (map inc widths))) center-min-cols))

(defn layout
  "Where everything goes on a screen `screen-w` wide, for `rails` as
  `closed-rails` shapes them and `focus` the rail the keys go to (or nil):
  `{:left w :right w :overlay side :center w}`. A rail beside the centre has
  its width; a rail with no room beside it draws over the centre (`:overlay`),
  and only one may — the focused one, else the left. Both rails closed is the
  whole width for the centre: the screen as it was before rails existed."
  [screen-w rails focus]
  (let [open (filterv #(get-in rails [% :open?]) sides)
        beside (cond
                 (fits-beside? screen-w (map #(cols-of rails %) open)) open
                 ;; both open and both do not fit: the one the keys are in
                 (and (= 2 (count open)) focus (fits-beside? screen-w [(cols-of rails focus)])) [focus]
                 (and (= 2 (count open)) (fits-beside? screen-w [(cols-of rails :left)])) [:left]
                 :else [])
        overlay (when (and (seq open) (empty? beside))
                  (or (some #{focus} open) (first open)))]
    (cond-> {:center (- screen-w (reduce + 0 (map #(inc (cols-of rails %)) beside)))
             :overlay overlay}
      (some #{:left} beside) (assoc :left (cols-of rails :left))
      (some #{:right} beside) (assoc :right (cols-of rails :right)))))

(defn open-rail
  "`rails` with `side` open — on view `view` when one is named — and the other
  closed when the two would squeeze the centre below its minimum on a screen
  `screen-w` wide (byj-gly.123)."
  [rails side screen-w & [view]]
  (let [rails (cond-> (assoc-in rails [side :open?] true)
                view (assoc-in [side :view] view)
                view (assoc-in [side :at] 0)
                view (assoc-in [side :top] 0))]
    (if (and (get-in rails [(other side) :open?])
             (not (fits-beside? screen-w (map #(cols-of rails %) sides))))
      (assoc-in rails [(other side) :open?] false)
      rails)))

(defn kept-rails
  "What of `rails` is kept across restarts (byj-gly.457): each side's
  open?, view and width — not its mark or window, which follow what the
  rail lists then, nor which rail has the keys, which start in the input."
  [rails]
  (into {} (for [side sides]
             [side (cond-> {:open? (boolean (get-in rails [side :open?]))
                            :view (or (get-in rails [side :view]) 0)}
                     (get-in rails [side :cols]) (assoc :cols (get-in rails [side :cols])))])))

(defn restore-rails
  "`rails` as `saved` (`kept-rails`) left them, on a screen `screen-w` wide
  where `side` has `(view-counts side)` views: each side's width, within
  `min-rail-cols` and the screen, and its view when it still has that many;
  open only where it has views and is drawn beside the centre, left first.
  A rail that would be drawn over the centre, or not at all, opens closed:
  over it, it would cover the input without the keys."
  [rails saved screen-w view-counts]
  (let [sized (reduce (fn [rs side]
                        (let [{:keys [view cols]} (get saved side)
                              n (get view-counts side 0)]
                          (cond-> (assoc-in rs [side :open?] false)
                            (and (int? cols) (pos? screen-w)) (assoc-in [side :cols] (-> cols (min (dec screen-w)) (max min-rail-cols)))
                            (and (int? view) (< -1 view n)) (assoc-in [side :view] view))))
                      rails sides)]
    (reduce (fn [rs side]
              (let [tried (assoc-in rs [side :open?] true)]
                (if (and (get-in saved [side :open?])
                         (pos? (get view-counts side 0))
                         (get (layout screen-w tried nil) side))
                  tried
                  rs)))
            sized sides)))

(defn resize
  "`rails` with `side`'s rail `delta` columns wider (narrower when negative)
  on a screen `screen-w` wide (byj-gly.399). Never under `min-rail-cols`. A
  rail beside the centre never so wide that the centre, with the other rail
  when it is open, drops under `center-min-cols`; one already too wide for
  that, drawn over the centre, up to the screen less a column — widening it
  must not first shrink it to what fits beside."
  [rails side delta screen-w]
  (let [other-w (if (get-in rails [(other side) :open?]) (inc (cols-of rails (other side))) 0)
        beside (- screen-w center-min-cols other-w 1)
        most (if (<= (cols-of rails side) beside) beside (max min-rail-cols (dec screen-w)))]
    (assoc-in rails [side :cols] (-> (+ (cols-of rails side) delta) (min most) (max min-rail-cols)))))

(defn- drawn-rails
  "The rails drawn on a screen `screen-w` wide, `{side width}`: one beside the
  centre, and one over it only while it has the keys (`focus`)."
  [screen-w rails focus]
  (let [lay (layout screen-w rails focus)
        ov (:overlay lay)]
    (cond-> {}
      (:left lay) (assoc :left (:left lay))
      (:right lay) (assoc :right (:right lay))
      (and ov (= focus ov)) (assoc ov (cols-of rails ov)))))

(defn separator-at
  "Which rail's separator — the column between it and the centre — is at
  column `x` on a screen `screen-w` wide, for `rails` and `focus` as `layout`
  takes them, or nil. A rail beside the centre has it just past its width
  from its own edge; a rail drawn over the centre, which is drawn only while
  it has the keys, has it on the side that faces the centre."
  [screen-w rails focus x]
  (some (fn [[side w]] (when (= x (if (= :left side) w (- screen-w w 1))) side))
        (drawn-rails screen-w rails focus)))

(defn rail-at
  "Which rail is drawn at column `x` on a screen `screen-w` wide, for `rails`
  and `focus` as `layout` takes them, and the column within it: `[side col]`,
  or nil — the centre, a separator, a rail that is not drawn. What a click
  lands on (byj-gly.449)."
  [screen-w rails focus x]
  (some (fn [[side w]]
          (let [from (if (= :left side) 0 (- screen-w w))]
            (when (and (<= from x) (< x (+ from w))) [side (- x from)])))
        (drawn-rails screen-w rails focus)))

(defn- header-view-at
  "Which view's name in a rail's header is at column `col`, drawn as
  `rail-lines` draws it — the names one space apart, the shown one in
  brackets — or nil: between names, past them, or a header that is one
  view's title alone."
  [views shown col]
  (when (< 1 (count views))
    (loop [i 0 from 0]
      (when (< i (count views))
        (let [w (+ (text/width (str (:title (nth views i)))) (if (= i shown) 2 0))]
          (if (< col (+ from w))
            (when (<= from col) i)
            (recur (inc i) (+ from w 1))))))))

(declare resolve-mark)

(defn click-target
  "What a click on row `row`, column `col` of a rail `h` high hits, the rail
  drawn as `rail-lines` draws it: `{:view i}` for a view's name in the
  header, `{:item i}` for an item's row, or nil — the rule, a row past the
  items, the header beside its names (byj-gly.449)."
  [views rail h row col]
  (let [{:keys [view top]} (resolve-mark views rail (max 1 (- h 2)))
        v (get views (or view 0))
        n (count (or (some-> (:items v) (apply [])) []))]
    (cond
      (zero? row) (when-let [i (header-view-at views (or view 0) col)] {:view i})
      (< 1 row h) (let [i (+ (or top 0) (- row 2))] (when (< i n) {:item i}))
      :else nil)))

(defn drag-edge
  "`rails` with `side`'s separator dragged to column `x` of a screen
  `screen-w` wide: the rail as wide as puts its inner edge there, within
  `resize`'s bounds — the mouse's way to what ← and → do a step at a time."
  [rails side x screen-w]
  (let [want (if (= :left side) x (- screen-w x 1))]
    (resize rails side (- want (cols-of rails side)) screen-w)))

(defn view-index
  "Which of `views` `name` names, by :id, :title or one of its :aliases — the
  names of what a view holds, as Source holds Changes (byj-gly.397) — ignoring
  case, or nil."
  [views name]
  (let [n (some-> name str str/trim str/lower-case)]
    (when-not (str/blank? n)
      (first (keep-indexed (fn [i v] (when (or (= n (str/lower-case (str (:id v))))
                                                 (= n (str/lower-case (str (:title v))))
                                                 (some #(= n (str/lower-case (str %))) (:aliases v)))
                                        i))
                           views)))))

(defn move
  "A rail's state after moving its mark by `step` among `n` items in a window
  of `rows` rows, the window following the mark."
  [{:keys [at top] :as rail} step n rows]
  (let [at (max 0 (min (dec (max 1 n)) (+ (or at 0) step)))
        rows (max 1 rows)
        top (cond (< at (or top 0)) at
                  (>= at (+ (or top 0) rows)) (inc (- at rows))
                  :else (or top 0))]
    (assoc rail :at at :top (max 0 top))))

(defn scroll
  "A rail's state with its window `step` rows further down (up when
  negative) among `n` items in `rows` rows, the mark left where it is: the
  wheel over a rail (byj-gly.449). Never above the first item, nor past
  where the last fills the window's last row."
  [{:keys [top] :as rail} step n rows]
  (assoc rail :top (-> (+ (or top 0) step) (min (max 0 (- n (max 1 rows)))) (max 0))))

(defn section?
  "Whether `item` heads a section: a stack's heading (`stack`), or an item a
  view marks with `:section`, as Source marks Changes, Commits and Graph."
  [item]
  (boolean (or (::section item) (:section item))))

(defn section-stops
  "Where Tab stops in `view`: the index of each section heading among its
  items (byj-gly.404). A view with none, Files, has none."
  [view]
  (vec (keep-indexed (fn [i it] (when (section? it) i))
                     (or (some-> (:items view) (apply [])) []))))

(defn next-section
  "A rail's state with its mark on the next section heading (`step` 1, Tab)
  or the previous one (-1, shift+Tab) of the view it shows, wrapping, its
  window following the mark through `rows` rows. Never another view: views
  have their letters (`view-keys`, byj-gly.406)."
  [{:keys [view at top] :as rail} views step rows]
  (let [stops (section-stops (get views (or view 0)))
        here (or at 0)
        i (if (pos? step)
            (or (first (filter #(> % here) stops)) (first stops))
            (or (last (filter #(< % here) stops)) (last stops)))]
    (if (nil? i)
      rail
      (let [rows (max 1 rows)
            top (or top 0)
            top (cond (< i top) i (>= i (+ top rows)) (inc (- i rows)) :else top)]
        (assoc rail :at i :top (max 0 top))))))

(defn view-keys
  "Each view's key, by position: the first letter of its title that no view
  before it has taken, lower case — f for Files, s for Source — or nil when
  every letter is (byj-gly.406)."
  [views]
  (loop [[v & more] views taken #{} out []]
    (if-not v
      out
      (let [k (first (remove taken (filter #(re-matches #"[a-z]" (str %)) (str/lower-case (str (:title v))))))]
        (recur more (cond-> taken k (conj k)) (conj out k))))))

(defn view-for-key
  "Which of `views` the typed character `ch` names (`view-keys`), or nil."
  [views ch]
  (when ch
    (let [ch (first (str/lower-case (str ch)))]
      (first (keep-indexed (fn [i k] (when (= k ch) i)) (view-keys views))))))

(defn stack
  "Several views shown as one, each a section under its own title, one after
  another in the list the mark moves through (byj-gly.384): the left rail's
  Project above its Sessions. A row does what its own view does with it;
  the mark starts where the first view with a `:mark` puts it; showing the
  stack shows every section. A section heading folds or unfolds its section
  (Enter, a click), marked ▾ open or ▸ folded, as Source's headings are
  (byj-gly.456); every section starts open. A folded section's rows are
  not in the list, so the mark passes over them and a view's `:mark` that
  is among them puts the mark nowhere. What is folded is the sections'
  titles, so a section that comes or goes leaves the others' folds as they
  were. `cell` makes the fold state — the renderer's reactive atom, so a
  fold repaints the rail; a plain atom by default — or `kept`, `(kept
  :stack/folded #{})`, one kept across restarts (`rail-state/keeper`,
  byj-gly.457)."
  [title views & [{:keys [cell kept] :or {cell atom}}]]
  (let [folded ((or kept (fn [_ init] (cell init))) :stack/folded #{})
        tagged (fn [] (let [shut @folded]
                        (vec (mapcat (fn [i v]
                                       (let [open? (not (contains? shut (str (:title v))))]
                                         (cons {:label (str (if open? "▾ " "▸ ") (:title v)) ::section i
                                                :style {:bold true :color :cyan}}
                                               (when open? (map #(assoc % ::view i) ((:items v)))))))
                                     (range) views))))
        own (fn [item] (when-let [i (::view item)] [(nth views i) (dissoc item ::view)]))
        fold! (fn [i] (let [t (str (:title (nth views i)))]
                        (swap! folded #(if (contains? % t) (disj % t) (conj (set %) t)))))]
    {:id "stack" :title title
     :items tagged
     :act (fn [item]
            (if-let [i (::section item)]
              (fold! i)
              (when-let [[v it] (own item)] (some-> (:act v) (apply [it])))))
     :put (fn [item] (when-let [[v it] (own item)] (some-> (:put v) (apply [it]))))
     :mark (fn [items]
             (some (fn [[i v]]
                     (when-let [mark (:mark v)]
                       (let [at (keep-indexed (fn [k it] (when (= i (::view it)) k)) items)
                             j (when (seq at) (mark (mapv #(dissoc (nth items %) ::view) at)))]
                         (when j (nth (vec at) j nil)))))
                   (map-indexed vector views)))
     :on-show (fn [] (doseq [v views] (some-> (:on-show v) (apply []))))}))

(defn- fit-segments
  "`segments` cut to `w` columns and padded to them, the last that fits
  truncated."
  [segments w]
  (loop [[[t style] & more] segments, room w, out []]
    (cond
      (or (nil? t) (<= room 0)) (cond-> out (pos? room) (conj [(apply str (repeat room " ")) nil]))
      (<= (text/width t) room) (recur more (- room (text/width t)) (conj out [t style]))
      :else (conj out [(text/truncate t room) style]))))

(defn resolve-mark
  "`rail` with its mark where its view's `:mark` puts it among the items as
  they are now, while the rail follows that (`:follow-mark?`, set when the
  view comes into sight) — or as it is. A section that reads after the rail
  opened, Project or Issues, changes how many rows come before the session's,
  and an index taken once had the mark land rows off (byj-gly.405)."
  [views rail rows]
  (let [v (get views (or (:view rail) 0))]
    (if-let [mark (and (:follow-mark? rail) (:mark v))]
      (let [items (vec (or (some-> (:items v) (apply [])) []))]
        (if-let [at (mark items)]
          (merge rail (select-keys (move (assoc rail :at 0 :top 0) at (count items) rows) [:at :top]))
          rail))
      rail)))

(defn rail-lines
  "What a rail `w` wide and `h` high shows: `[[text style] …]`, h of them at
  most. A header naming its views, the shown one in brackets, then its items from
  the window's top, the marked one reversed while the rail has the keys. An
  item with `:segments` that is not marked is `[text style segments]`, the
  segments fitted to the row: a row in more than one colour (byj-gly.398). A
  rail following its view's mark is drawn with the mark resolved now
  (`resolve-mark`)."
  [views rail w h focused?]
  (let [{:keys [view at top]} (resolve-mark views rail (max 1 (- h 2)))]
  (let [view (or view 0)
        v (get views view)
        items (vec (or (some-> (:items v) (apply [])) []))
        fit (fn [s] (text/pad (text/truncate (str s) w) w))
        head-style {:bold true :color (if focused? :cyan :default)}
        ;; the view shown is the one in brackets; each view's letter, the key
        ;; that shows it, underlined (byj-gly.406)
        header-segments (when (< 1 (count views))
                          (let [ks (view-keys views)]
                            (vec (butlast
                                  (mapcat (fn [i x k]
                                            (let [t (str (:title x))
                                                  at-k (when k (str/index-of (str/lower-case t) (str k)))
                                                  [b* m* a*] (if at-k
                                                               [(subs t 0 at-k) (subs t at-k (inc at-k)) (subs t (inc at-k))]
                                                               [t nil nil])
                                                  open? (= i view)]
                                              (concat (when open? [["[" head-style]])
                                                      (when (seq b*) [[b* head-style]])
                                                      (when m* [[m* (assoc head-style :underline true)]])
                                                      (when (seq a*) [[a* head-style]])
                                                      (when open? [["]" head-style]])
                                                      [[" " head-style]])))
                                          (range) views ks)))))
        header (if header-segments
                 (apply str (map first header-segments))
                 (str (:title v)))
        body-h (max 0 (- h 2))
        body (for [[i {:keys [label hint hint-style segments]}] (map-indexed vector (take body-h (drop (or top 0) items)))
                   :let [idx (+ (or top 0) i)
                         ;; a hint never takes more than half the row: a long
                         ;; one left its label no room at all (byj-gly.412)
                         hint (some-> hint str not-empty (text/truncate (max 1 (quot w 2))))
                         room (- w (if hint (inc (text/width hint)) 0))
                         s (str (text/pad (text/truncate (str label) (max 1 room)) (max 1 room))
                                (when hint (str " " hint)))]]
               (if (and segments (not= idx at))
                 [(fit s) (or (:style (nth items idx)) {})
                  ;; a hint in its own colour where the row asks for one
                  (fit-segments (concat (fit-segments segments (max 1 room)) (when hint [[(str " " hint) hint-style]])) w)]
                 [(fit s) (cond (and focused? (= idx at)) {:reverse true}
                                (= idx at) {:bold true}
                                :else (or (:style (nth items idx)) {}))]))]
    (vec (concat [(cond-> [(fit header) head-style]
                    header-segments (conj (fit-segments header-segments w)))
                  [(fit (apply str (repeat w "─"))) {:dim true}]]
                 (if (and (empty? items) (pos? body-h))
                   [[(fit "  (nothing here)") {:dim true}]]
                   body))))))
