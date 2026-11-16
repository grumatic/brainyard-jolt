;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.profile
  "A project's profile (RFC-073, byj-71w.2): what the project is for, its
  scope and when it is done, and how far an agent goes on its own along each
  axis — filing, working, committing and closing beads, deciding, pushing.

  The profile is `<project>/.brainyard/profile.edn`, committed, and written
  only on the person's yes, by `init$profile` and `profile$edit`:
  `permission/profile-file` refuses every other tool's write to it in every
  mode, and byj's hook refuses Claude Code's and Codex's. The axes, their
  levels, the presets and the ceilings no profile goes above are data, in
  `resources/brainyard/profile/presets.edn`; this namespace merges and checks,
  and decides nothing.

  brainyard has no counterpart."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.schema :as schema]
            [malli.core :as m]
            [malli.error :as me]))

(def presets-resource "brainyard/profile/presets.edn")

(defn presets
  "The axes, levels, ceilings and presets, from `presets.edn`."
  []
  (edn/read-string (slurp (io/resource presets-resource))))

(defn profile-file
  "Where a project keeps its profile."
  [project-dir]
  (io/file (str project-dir) ".brainyard" "profile.edn"))

(defn profile-schema
  "The malli form a profile file is checked against, from `spec` (`presets`):
  a closed map, so a misspelled key is a problem rather than ignored."
  [{:keys [levels axes presets]}]
  (let [strings [:vector :string]]
    [:map {:closed true}
     [:profile/version [:= 1]]
     [:preset {:optional true} (into [:enum] (keys presets))]
     [:goal {:optional true} :string]
     [:scope {:optional true} [:map {:closed true} [:in {:optional true} strings] [:out {:optional true} strings]]]
     [:done {:optional true} strings]
     [:goal-epic {:optional true} :string]
     [:autonomy {:optional true} [:map-of (into [:enum] (map :id axes)) (into [:enum] levels)]]
     [:stop {:optional true} [:map {:closed true}
                              [:failures {:optional true} pos-int?]
                              [:usd {:optional true} [:and number? [:> 0]]]
                              [:issues-per-run {:optional true} pos-int?]]]]))

(defn- problems-of [spec data]
  (let [s (schema/schema (profile-schema spec))]
    (when-not (m/validate s data)
      (let [h (me/humanize (m/explain s data))]
        (if (map? h)
          (vec (for [[k v] (sort-by (comp str key) h)] (str (pr-str k) ": " (str/join "; " (flatten [v])))))
          [(str/join "; " (flatten [h]))])))))

(defn resolve-profile
  "`data`, a profile as read, with its preset merged under it and its
  ceilings applied: `:preset` and `:autonomy` hold every axis's level, and
  `:problems` says what was wrong. A profile that does not match the schema
  keeps what it says of its goal, scope and done criteria, but its autonomy
  is the default preset's whole and its stop limits are dropped: a profile
  that cannot be read grants nothing it might not have meant. An axis above its ceiling is held there."
  ([data] (resolve-profile (presets) data))
  ([spec data]
   (let [problems (problems-of spec data)
         preset (if (and (not problems) (:preset data)) (:preset data) (:default-preset spec))
         base (get-in spec [:presets preset :autonomy])
         wanted (if problems base (merge base (:autonomy data)))
         rank (zipmap (:levels spec) (range))
         over (for [[axis ceiling] (:ceilings spec)
                    :let [lvl (get wanted axis)]
                    :when (and lvl (> (rank lvl) (rank ceiling)))]
                [axis lvl ceiling])]
     (cond-> (assoc (cond (not (map? data)) {}
                          ;; a stop limit from a profile that does not
                          ;; match is not one to drive by
                          problems (dissoc data :stop)
                          :else data)
                    :preset preset
                    :autonomy (reduce (fn [a [axis _ ceiling]] (assoc a axis ceiling)) wanted over))
       (or (seq problems) (seq over))
       (assoc :problems (into (vec problems)
                              (for [[axis lvl ceiling] over]
                                (str (pr-str axis) " is " (pr-str lvl) ", above its ceiling "
                                     (pr-str ceiling) "; held at " (pr-str ceiling)))))))))

(defn read-profile
  "The project's profile, resolved (`resolve-profile`), with `:file` where it
  was read; nil when the project has none. A file that is not EDN resolves
  as an empty profile, so as the default preset, with the reason in
  `:problems`."
  [project-dir]
  (let [f (profile-file project-dir)]
    (when (and project-dir (.isFile f))
      (let [[data err] (try [(edn/read-string (slurp f)) nil]
                            (catch Throwable e [nil (or (ex-message e) (str e))]))]
        (assoc (if err
                 (update (resolve-profile {}) :problems #(vec (cons (str "it is not EDN: " err)
                                                                     (remove (fn [p] (str/starts-with? p ":profile/version")) %))))
                 (resolve-profile data))
               :file (.getPath f))))))

(defn level
  "The level `profile` sets `axis` to, or nil when there is no profile."
  [profile axis]
  (get-in profile [:autonomy axis]))

;; --- AGENTS.md's block (RFC-073 "Where agents read it", byj-71w.3) ------------------------

(def block-name
  "The marked block of AGENTS.md the profile is shown in."
  "BRAINYARD:profile")

(def block-resource "brainyard/profile/block.edn")

(defn block-words
  "The block's words, from `block.edn`."
  []
  (edn/read-string (slurp (io/resource block-resource))))

(defn- fill [s vars]
  (reduce-kv (fn [s k v] (str/replace s (str "{" (name k) "}") (str v))) (str s) vars))

(defn- money [n]
  (if (== n (Math/floor n)) (str (long n)) (str n)))

(defn bd-profile
  "The bd Agent Context Profile a profile amounts to: `:team-maintainer`
  when an agent commits its bead's work on its own, else `:conservative`."
  [profile]
  (if (= :act (level profile :git/commit)) :team-maintainer :conservative))

(defn render
  "The AGENTS.md block that shows `profile`, a resolved one (`read-profile`),
  from its marker line to its end marker. The same profile renders the same
  block, so a block that differs was changed by hand (`block-state`)."
  ([profile] (render profile (presets) (block-words)))
  ([profile spec words]
   (let [{:keys [goal goal-epic scope done stop preset]} profile
         axes (:axes spec)
         by-level (group-by #(level profile (:id %)) axes)
         items #(str/join "; " %)
         para (fn [& lines] (str/join "\n" (remove nil? lines)))
         sections
         [(:title words)
          (:source words)
          (fill (:preset words) {:preset (name preset)})
          (when (or goal goal-epic)
            (para (when goal (fill (:goal words) {:goal (str/trim goal)}))
                  (when goal-epic (fill (:goal-epic words) {:goal-epic goal-epic}))))
          (when (or (seq (:in scope)) (seq (:out scope)) (seq done))
            (para (when (seq (:in scope)) (fill (:in words) {:items (items (:in scope))}))
                  (when (seq (:out scope)) (fill (:out words) {:items (items (:out scope))}))
                  (when (seq done) (fill (:done words) {:items (items done)}))))
          (str/join "\n\n"
                    (for [lvl [:act :ask :never]
                          :let [as (get by-level lvl)]
                          :when (seq as)]
                      (str (get-in words [:levels lvl]) "\n"
                           (str/join "\n" (map #(str "- " (:covers %)) as)))))
          (when (seq stop)
            (fill (:stop words)
                  {:limits (str/join ", " (for [k [:failures :usd :issues-per-run]
                                                :let [n (get stop k)]
                                                :when n]
                                            (fill (get-in words [:stop-limits k])
                                                  {:n (if (= :usd k) (money n) n)})))}))
          (fill (get-in words [:bd (bd-profile profile)])
                {:push (get-in words [:push (level profile :git/push)] "")})]]
     (str "<!-- BEGIN " block-name " -->\n"
          (str/join "\n\n" (map str/trimr (remove str/blank? sections)))
          "\n<!-- END " block-name " -->"))))

(defn shown-block
  "The profile block AGENTS.md text `agents-md` holds, marker to marker, or nil."
  [agents-md]
  (let [t (str agents-md)
        b (str/index-of t (str "<!-- BEGIN " block-name " -->"))
        end (str "<!-- END " block-name " -->")
        e (when b (str/index-of t end b))]
    (when (and b e) (subs t b (+ e (count end))))))

(defn block-state
  "Whether AGENTS.md text `agents-md` shows `profile` (resolved, or nil for
  none): `:current` when its block is the profile's rendering, `:stale` when
  it differs, `:missing` when there is a profile and no block, `:orphan` when
  there is a block and no profile, nil when there is neither. The block is
  never read for what the profile says: the data is."
  [profile agents-md]
  (let [shown (shown-block agents-md)]
    (cond (and profile shown) (if (= shown (render profile)) :current :stale)
          profile :missing
          shown :orphan)))
