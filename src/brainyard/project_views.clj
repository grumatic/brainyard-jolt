(ns brainyard.project-views
  "The right rail's views of a project (byj-gly.380): its files, and its
  source — the changes, the commits and the branch graph, as sections of one
  view (byj-gly.397) — read from git, as views the full screen's frame shows
  (`brainyard.tui-frame`, RFC-028 \"The frame\").

  Mechanism only (RFC-001): what is read and how it is parsed. When it is
  read is the caller's — `refresh!` on the rail coming into sight, at a turn's
  end, after a tool batch — and it never polls. A read runs as a Task off the
  render loop (RFC-009): every git call is a `process-task`, so a read
  cancelled by the next one takes its processes with it, and the views only
  ever read the last answer from a cell.

  A directory that is not a repository shows its files from a walk of it, and
  says it has no changes or commits to show because it is not a repository."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [ebb.core :as m]))

(def max-files
  "The most paths a files view holds: a rail is for finding your way, not for
  listing a monorepo, and a walk of a home directory must end."
  5000)

(def max-commits
  "The newest commits on the current branch the Commits section lists: it
  shares one list with the graph below it, which holds the older ones too."
  30)

(def max-graph
  "The most commits the Graph section draws."
  60)

(def max-graph-scan
  "The most commits read to draw the map of the branches from: a read is one
  `git log`, 34 ms for this repository's 685."
  3000)

(defn- git
  "A Task of git's output in `dir`, or nil when it failed."
  [dir & args]
  (m/sp (let [r (m/? (apply fx/process-task {:dir (str dir) :out :string :err :string :in ""} "git" args))]
          (when (zero? (:exit r)) (str (:out r))))))

(defn- lines [s] (vec (remove str/blank? (str/split-lines (str s)))))

(defn parse-status
  "`git status --porcelain=v1` as `[{:path :state}]`: the two-letter state
  trimmed (`M`, `MM`, `??`, `A`), and a rename's new path."
  [out]
  (vec (for [l (lines out)
             :when (>= (count l) 4)]
         {:state (str/trim (subs l 0 2))
          :path (let [p (subs l 3)] (if-let [[_ to] (re-find #" -> (.+)$" p)] to p))})))

(defn parse-log
  "`git log --format=%h%x09%H%x09%s` as `[{:short :hash :subject}]`."
  [out]
  (vec (for [l (lines out)
             :let [[short hash subject] (str/split l #"\t" 3)]
             :when hash]
         {:short short :hash hash :subject (or subject "")})))

(defn parse-graph
  "`git log --format=%H%x09%h%x09%P%x09%D%x09%s` as `[{:hash :short :parents
  :refs :subject}]`, newest first (byj-gly.398)."
  [out]
  (vec (for [l (lines out)
             :let [[hash short parents refs subject] (str/split l #"\t" 5)]
             :when short]
         {:hash hash :short short
          :parents (vec (remove str/blank? (str/split (str parents) #" ")))
          :refs (or refs "") :subject (or subject "")})))

;; --- the graph, laid out in lanes (byj-gly.398) -------------------------------------

(defn branch-map
  "`commits` (`parse-graph`, newest first) cut to a map of the branches: a
  commit a branch or tag points at, a merge, one history forks at, the
  newest and a root, each with its parents rewritten to the nearest kept
  ancestor along each line. A line of commits between them is left out, so a
  history that is linear for its last hundred commits still shows where its
  branches left and joined. git's own `--simplify-by-decoration` drops a merge
  unless both sides carry a name, so a branch merged into main looked as if it
  were main (found live, byj-gly.398)."
  [commits]
  (let [by-hash (into {} (map (juxt :hash identity)) commits)
        children (frequencies (mapcat :parents commits))
        newest (:hash (first commits))
        keep? (fn [{:keys [hash refs parents]}]
                (or (= hash newest) (not (str/blank? refs)) (not= 1 (count parents))
                    (< 1 (get children hash 0))))
        ;; along a line of commits each with one parent and one child, to
        ;; the first kept one; nil when the line leaves what was read
        kept-along (fn [h] (loop [h h seen 0]
                             (when-let [c (and (< seen (count commits)) (by-hash h))]
                               (if (keep? c) h (recur (first (:parents c)) (inc seen))))))]
    (vec (for [c commits :when (keep? c)]
           (assoc c :parents (vec (distinct (keep kept-along (:parents c)))))))))

(def lane-colors
  "A lane's colour, by the order lanes are opened in."
  [:cyan :magenta :yellow :green :blue :red])

(def ^:private box
  "The character for a cell joined up, down, left and right as its set says."
  {#{:u :d} "│" #{:l :r} "─" #{:u :l} "╯" #{:u :r} "╰" #{:d :l} "╮" #{:d :r} "╭"
   #{:u :d :l} "┤" #{:u :d :r} "├" #{:u :l :r} "┴" #{:d :l :r} "┬" #{:u :d :l :r} "┼"
   #{:u} "│" #{:d} "│" #{:l} "─" #{:r} "─"})

(defn lanes
  "`commits` (`parse-graph`, newest first, a child before its parents) each
  with `:cells`, the graph drawn before it: `[[text colour] …]`, two columns a
  lane — the lane's own character, then what joins it to the next. One row a
  commit, as lazygit and VS Code draw it, not git's rows of drawing alone: a
  commit is ●, a lane going on │, a branch leaving or joining bends into its
  lane (╮ ╯ ╭ ╰) and crosses others (┼). Each lane keeps the colour it was
  opened with. A pure function: tested without git."
  [commits]
  (loop [[c & more] commits, lanes [], opened 0, out []]
    (if-not c
      out
      (let [h (:hash c)
            free (fn [ls] (or (first (keep-indexed (fn [i l] (when (nil? l) i)) ls)) (count ls)))
            at (first (keep-indexed (fn [i l] (when (= h (:hash l)) i)) lanes))
            [col lanes opened] (if at
                                 [at lanes opened]
                                 (let [i (free lanes)]
                                   [i (assoc (if (< i (count lanes)) lanes (conj lanes nil)) i
                                             {:hash h :color (lane-colors (mod opened (count lane-colors)))})
                                    (inc opened)]))
            color-of (fn [ls i] (:color (get ls i)))
            ;; the commit takes its lane's colour, as it was when it got there
            commit-color (color-of lanes col)
            joining (vec (keep-indexed (fn [i l] (when (and (not= i col) (= h (:hash l))) i)) lanes))
            ;; cells: index → {:dirs #{} :color}; gaps: index → colour, for
            ;; the column after a lane that a line runs through
            cells (into {} (keep-indexed (fn [i l] (when (and l (not= i col) (not (some #{i} joining)))
                                                     [i {:dirs #{:u :d} :color (:color l)}]))
                                         lanes))
            span (fn [[cells gaps] from to dir color]
                   ;; a line from the commit's lane to lane `to`, which it
                   ;; enters going `dir` (:u from above, :d below)
                   (let [[lo hi] (sort [from to])
                         cells (update cells to (fn [x] {:dirs (conj (or (:dirs x) #{}) dir (if (> to from) :l :r))
                                                         :color (or (:color x) color)}))
                         cells (reduce (fn [cs k] (update cs k (fn [x] {:dirs (into (or (:dirs x) #{}) [:l :r])
                                                                        :color (or (:color x) color)})))
                                       cells (range (inc lo) hi))
                         gaps (reduce #(assoc %1 %2 color) gaps (range lo hi))]
                     [cells gaps]))
            [cells gaps] (reduce (fn [acc j] (span acc col j :u (color-of lanes j))) [cells {}] joining)
            [p1 & ps] (:parents c)
            lanes (reduce #(assoc %1 %2 nil) lanes joining)
            lanes (assoc lanes col (when p1 {:hash p1 :color (color-of lanes col)}))
            [lanes cells gaps opened]
            (reduce (fn [[ls cells gaps opened] p]
                      (if-let [j (first (keep-indexed (fn [i l] (when (and (not= i col) (= p (:hash l))) i)) ls))]
                        (let [[cells gaps] (span [cells gaps] col j :d (color-of ls j))]
                          [ls cells gaps opened])
                        (let [j (free (assoc ls col :taken))
                              color (lane-colors (mod opened (count lane-colors)))
                              ls (assoc (if (< j (count ls)) ls (conj ls nil)) j {:hash p :color color})
                              [cells gaps] (span [cells gaps] col j :d color)]
                          [ls cells gaps (inc opened)])))
                    [lanes cells gaps opened] ps)
            lanes (loop [ls lanes] (if (and (seq ls) (nil? (peek ls))) (recur (pop ls)) ls))
            width (inc (reduce max col (keys cells)))
            row (vec (mapcat (fn [i]
                               (let [{:keys [dirs color]} (get cells i)
                                     ch (cond (= i col) "●" dirs (box dirs " ") :else " ")]
                                 [[ch (if (= i col) commit-color color)]
                                  [(if (contains? gaps i) "─" " ") (get gaps i)]]))
                             (range width)))]
        (recur more lanes opened (conj out (assoc c :cells row :lane col)))))))

(defn- walk
  "Paths under `dir`, relative to it, sorted, without dot-directories: what a
  directory that is not a repository shows."
  [dir]
  (let [root (io/file (str dir))
        base (inc (count (.getPath root)))]
    (->> (tree-seq #(and (.isDirectory %) (or (= % root) (not (str/starts-with? (.getName %) ".")))) #(sort-by (fn [f] (.getName f)) (.listFiles %)) root)
         (filter #(.isFile %))
         (map #(subs (.getPath %) base))
         (remove #(str/starts-with? % "."))
         (take max-files)
         vec)))

(defn read-task
  "A Task of what the rail shows for `dir`: `{:repo? :top :files :changes
  :commits :graph :ahead :upstream?}`, or for a directory that is not a repository
  `{:repo? false :files}`. Sequential, so it parks on one process at a time.
  A change's path is the repository's, from `:top`, as git status says it."
  [dir]
  (m/sp
   (if (m/? (git dir "rev-parse" "--is-inside-work-tree"))
     (let [top (some-> (m/? (git dir "rev-parse" "--show-toplevel")) str/trim)
           files (some-> (m/? (git dir "ls-files" "--cached" "--others" "--exclude-standard")) lines)
           changes (some-> (m/? (git dir "status" "--porcelain=v1" "-uall")) parse-status)
           commits (some-> (m/? (git dir "log" (str "-n" max-commits) "--format=%h%x09%H%x09%s")) parse-log)
           ;; a map of the branches (byj-gly.398); beads' Dolt refs are not branches
           graph (some-> (m/? (git dir "log" "--exclude=refs/remotes/*/__dolt*" "--all"
                                   "--topo-order" (str "-n" max-graph-scan)
                                   "--format=%H%x09%h%x09%P%x09%D%x09%s"))
                         parse-graph branch-map (->> (take max-graph)) vec lanes)
           ahead (m/? (git dir "rev-list" "@{u}..HEAD"))]
       {:repo? true
        :top top
        :files (vec (take max-files (sort (distinct files))))
        :changes (or changes [])
        :commits (or commits [])
        :graph (or graph [])
        :upstream? (some? ahead)
        :ahead (set (lines ahead))})
     {:repo? false :files (m/? (fx/task-of #(walk dir)))})))

;; --- what a row stands for (byj-gly.381) ------------------------------------------------

(defn diff-task
  "A Task of a change's diff, `{:path :state}` from `parse-status`, in the
  repository at `top`: against HEAD, staged and unstaged together; a new file
  that git does not track yet, as the whole of it added. \"\" when git has
  nothing to say."
  [top {:keys [path state]}]
  (m/sp
   (if (= "??" state)
     ;; --no-index exits 1 when the two differ, which here they always do
     (str (:out (m/? (fx/process-task {:dir (str top) :out :string :err :string :in ""}
                                      "git" "diff" "--no-index" "--" "/dev/null" path))))
     (or (m/? (git top "diff" "HEAD" "--" path))
         ;; a repository with no commit yet has no HEAD to compare with
         (m/? (git top "diff" "--cached" "--" path))
         ""))))

(defn show-task
  "A Task of a commit as `git show --stat --patch` says it, or \"\"."
  [dir hash]
  (m/sp (or (m/? (git dir "show" "--stat" "--patch" hash)) "")))

(defn relative
  "`path`, a path in the repository at `top`, as one from `dir`: what a person
  in `dir` would write after `@`. Unchanged when `dir` is the top or is not
  under it."
  [top dir path]
  (let [canon #(try (.getCanonicalPath (io/file (str %))) (catch Throwable _ (str %)))
        top (canon top) dir (canon dir)]
    (if (and top (not= top dir) (str/starts-with? dir (str top "/")))
      (let [sub (str (subs dir (inc (count top))) "/")]
        (if (str/starts-with? path sub) (subs path (count sub)) (str (canon (io/file top path)))))
      path)))

;; --- the file tree, folded ----------------------------------------------------------

(defn tree-rows
  "The rows of `paths` as a tree whose directories are folded unless in
  `open`: `[{:label :hint :dir :path}]`, a directory first among its
  siblings, marked ▸ or ▾ and counting the files under it."
  [paths open]
  (let [tree (reduce (fn [t p] (assoc-in t (conj (str/split p #"/") ::file) p)) {} paths)
        count-files (fn count-files [node] (reduce + (for [[k v] node] (if (= ::file k) 1 (count-files v)))))
        rows (fn rows [node prefix depth]
               (let [kids (dissoc node ::file)
                     dirs (sort (keep (fn [[k v]] (when (seq (dissoc v ::file)) k)) kids))
                     files (sort (keep (fn [[k v]] (when (empty? (dissoc v ::file)) k)) kids))
                     pad (apply str (repeat depth "  "))]
                 (concat
                  (mapcat (fn [d]
                            (let [path (str prefix d)
                                  open? (contains? open path)]
                              (cons {:label (str pad (if open? "▾ " "▸ ") d "/") :hint (str (count-files (get kids d)))
                                     :dir path}
                                    (when open? (rows (get kids d) (str path "/") (inc depth))))))
                          dirs)
                  (map (fn [f] {:label (str pad "  " f) :path (str prefix f)}) files))))]
    (vec (rows tree "" 0))))

;; --- the views ----------------------------------------------------------------------

(def source-sections
  "The Source view's sections, in order (byj-gly.397)."
  [[:changes "Changes"] [:commits "Commits"] [:graph "Graph"]])

(defn source-rows
  "The Source view's rows for a read `s`, the sections in `folded` folded:
  each section a heading with its count, then its rows — a change
  `{:path :state}`, a commit `{:hash :short}`, in Commits or in Graph, whose
  rows carry `:segments`, the lanes in their colours (byj-gly.398). The
  commits not on the upstream are marked ↑, in both."
  [s folded]
  (let [ahead (fn [hash] (when (contains? (:ahead s) hash) "↑"))
        rows {:changes (if (empty? (:changes s))
                         [{:label "  nothing changed" :style {:dim true}}]
                         (mapv (fn [{:keys [path state]}] {:label (str "  " path) :hint state :path path :state state})
                               (:changes s)))
              :commits (mapv (fn [{:keys [short hash subject]}]
                               {:label (str "  " short " " subject) :hint (ahead hash) :hash hash :short short})
                             (:commits s))
              :graph (mapv (fn [{:keys [cells short hash refs subject lane]}]
                             (let [refs (when-not (str/blank? refs) (str " (" refs ")"))
                                   segments (vec (concat [["  " nil]]
                                                         (map (fn [[t c]] [t (when c {:color c})]) cells)
                                                         [[short {:dim true}]]
                                                         (when refs [[refs {:color (get-in cells [(* 2 lane) 1]) :bold true}]])
                                                         [[(str " " subject) nil]]))]
                               {:label (apply str (map first segments)) :segments segments
                                :hint (ahead hash) :hash hash :short short}))
                           (:graph s))}
        counts {:changes (count (:changes s)) :commits (count (:commits s)) :graph (count (:graph s))}]
    (vec (mapcat (fn [[k title]]
                   (let [open? (not (contains? folded k))]
                     (cons {:label (str (if open? "▾ " "▸ ") title) :hint (str (counts k)) :section k
                            :style {:bold true}}
                           (when open? (rows k)))))
                 source-sections))))

(defn views
  "The two views for project `dir`, and the `refresh!` that rereads it:
  `{:views [files source] :refresh! (fn []) :state cell}`. `cell`
  makes the state the views read from — the renderer's reactive atom, so an
  answer arriving repaints the rail; a plain atom by default. `kept`, when
  given, makes the cells a view keeps its folds in instead, `(kept k init)`,
  so they outlast a restart (`rail-state/keeper`, byj-gly.457). A refresh while
  one is still reading cancels it: the newest question is the one worth an
  answer.

  Source holds the changes, the commits and the branch graph, each a section
  whose heading Enter folds (byj-gly.397); `/right changes`, `commits` or
  `graph` name it (`:aliases`).

  What a row does is the caller's to carry out (byj-gly.381): Enter on a file
  calls `open` with its absolute path; on a change or a commit it reads the
  diff off the loop thread and calls `show` with a title and the text. `@`
  puts `@path` into the input (each view's `:put`)."
  [dir & [{:keys [cell kept open show] :or {cell atom}}]]
  (let [state (cell {:reading? true})
        unfolded ((or kept (fn [_ x] (cell x))) :files/unfolded #{})
        folded ((or kept (fn [_ x] (cell x))) :source/folded #{})
        running (atom nil)
        refresh! (fn []
                   (swap! state assoc :reading? true)
                   (some-> @running (apply []))
                   (reset! running
                           (fx/run-detached
                            ;; a read cancelled by the next is not a failure, and
                            ;; one that failed is a line in the rail: reported
                            ;; as a detached failure, it was printed over the
                            ;; full screen (found live, byj-gly.381)
                            (m/sp (try (let [r (m/? (read-task dir))]
                                         (reset! state (assoc r :reading? false)))
                                       (catch Throwable e
                                         (when-not (fx/cancelled? e)
                                           (reset! state {:reading? false :error (or (ex-message e) (str e))})))))
                            :project-views)))
        waiting (fn [s] (cond (:error s) [{:label (str "  could not read: " (:error s))}]
                              (:reading? s) (when (nil? (:repo? s)) [{:label "  reading…"}])
                              (false? (:repo? s)) [{:label "  not a git repository"}]))
        abs (fn [p] (.getCanonicalPath (io/file (str dir) p)))
        ;; a diff is read off the loop thread, and shown when it is in
        background! (fn [title task]
                      (when show
                        (fx/run-detached (m/sp (show title (try (m/? task)
                                                                (catch Throwable e
                                                                  (if (fx/cancelled? e) (throw e) (str "git: " (ex-message e)))))))
                                         :project-views-show)))
        files {:id "files" :title "File"
               :items (fn [] (let [s @state]
                               (or (when (:error s) [{:label (str "  could not read: " (:error s))}])
                                   (when (and (:reading? s) (nil? (:files s))) [{:label "  reading…"}])
                                   (tree-rows (:files s) @unfolded))))
               :act (fn [{d :dir path :path}]
                      (cond d (swap! unfolded #(if (contains? % d) (disj % d) (conj % d)))
                            (and path open) (open (abs path))))
               :put (fn [{:keys [path]}] (when path (str "@" path)))
               :on-show refresh!}
        source {:id "source" :title "Source" :aliases ["changes" "commits" "graph"]
                :items (fn [] (let [s @state] (or (waiting s) (source-rows s @folded))))
                :act (fn [{:keys [section path hash short] :as row}]
                       (cond section (swap! folded #(if (contains? % section) (disj % section) (conj % section)))
                             path (when-let [top (:top @state)]
                                    (background! (str "git diff — " path) (diff-task top (select-keys row [:path :state]))))
                             hash (background! (str "git show — " short) (show-task dir hash))))
                :put (fn [{:keys [path]}]
                       (when path (str "@" (relative (:top @state) dir path))))
                :on-show refresh!}]
    {:views [files source] :refresh! refresh! :state state}))
