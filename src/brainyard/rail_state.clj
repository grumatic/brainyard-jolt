;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.rail-state
  "What a person left the rails as, kept per project across restarts
  (byj-gly.457): each rail's open?, view and width (`tui-frame/kept-rails`),
  and what each view folds. One file, `<project>/.brainyard/rails.edn`, which
  `.brainyard/*` keeps out of git:

    {:frame {:left {:open? true :view 0 :cols 30} :right {…}}
     :folds {:stack/folded #{\"Memory · beads\"} :issues/unfolded #{\"byj-gly\"} …}}

  Mechanism only: the renderer is handed what to restore and a fn to call
  when the frame changes, and a view a cell to keep its folds in (`keeper`),
  so neither knows the file. A file that is missing or unreadable is no state,
  and one that cannot be written is left as it was: a rail that opens closed
  is never worth an error."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn file
  "Where project `dir` keeps its rails."
  [dir]
  (io/file (str dir) ".brainyard" "rails.edn"))

(defn- read-file [f]
  (try (let [m (when (.exists (io/file f)) (edn/read-string (slurp f)))]
         (if (map? m) m {}))
       (catch Throwable _ {})))

(defn store
  "Project `dir`'s rails as last kept: `{:file f :state atom}`. One per run,
  shared by every session in its window, so one session's write does not
  undo another's."
  [dir]
  (let [f (file dir)]
    {:file f :state (atom (read-file f))}))

(defn- write! [{:keys [file state]}]
  (try (io/make-parents file)
       (spit file (pr-str @state))
       (catch Throwable _ nil)))

(defn frame
  "The rails' frame as kept: `{side {:open? :view :cols}}`, or nil."
  [store]
  (:frame @(:state store)))

(defn save-frame!
  "Keep `kept` as the rails' frame, writing only when it differs."
  [store kept]
  (when (not= kept (frame store))
    (swap! (:state store) assoc :frame kept)
    (write! store)))

(defn keeper
  "`(f k init)` → a cell, made by `cell`, holding what was kept under `k`
  (else `init`), and keeping each change to it: what a view is handed to keep
  its folds in. `k` is the view's own name for them, `:issues/unfolded`. A
  kept value not of `init`'s kind, a set or a boolean, is `init`. `watch`,
  `(watch c f)` calling `(f value)` as `c` changes, is how a change is heard:
  `add-watch` by default, for a plain atom; the renderer's reactive cells
  take `tui/on-change!`."
  [store cell & [watch]]
  (fn [k init]
    (let [v (get-in @(:state store) [:folds k] init)
          v (cond (set? init) (if (set? v) v init)
                  (boolean? init) (if (boolean? v) v init)
                  :else v)
          c (cell v)]
      ((or watch (fn [c f] (add-watch c ::kept (fn [_ _ _ new] (f new)))))
       c (fn [new]
           (when (not= new (get-in @(:state store) [:folds k]))
             (swap! (:state store) assoc-in [:folds k] new)
             (write! store))))
      c)))
