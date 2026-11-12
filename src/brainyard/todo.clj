;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.todo
  "Durable work state (RFC-023) — brainyard's `common/todo.clj` reduced to the
  part a workflow cannot supply itself.

      <project-dir>/.brainyard/todos/<slug>.md

  brainyard has a todo-agent that authors a list and an exec-agent that drives
  it, handing each other dossiers. Here the driving is a behaviour tree
  (RFC-022) and the authoring is judgement, so neither needs to be an agent.
  What a tree genuinely cannot do is REMEMBER: its blackboard dies with the
  turn, and RFC-016 persists a session's turns, not its blackboard. So a list
  that outlives the turn is the whole of this layer.

  The file is markdown with checkboxes, as brainyard's is, because a person
  edits it too — reordering items, striking one out, adding one the model
  missed. An item may name how it should be done:

      # Add a retry budget

      - [x] [edit] add the counter to llm.clj
      - [ ] [edit] thread it through with-retry
      - [ ] [check] run the suite

  The tag is advisory. A workflow routes on it; nothing enforces it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.config :as config]))

;; --- the file ---------------------------------------------------------------------

(def ^:private slug-re #"[A-Za-z0-9_-][A-Za-z0-9_.-]*")

(defn- check-slug! [slug]
  (when-not (and (string? slug) (re-matches slug-re (str slug)))
    (throw (ex-info (str "invalid todo slug: " (pr-str slug)) {:slug slug})))
  slug)

(defn todos-dir [config]
  (io/file (config/canonical-dir (or (:project-dir config) (:working-dir config) "."))
           ".brainyard" "todos"))

(defn todo-file [config slug] (io/file (todos-dir config) (str (check-slug! slug) ".md")))

(defn list-slugs [config]
  (let [d (todos-dir config)]
    (if-not (.isDirectory d)
      []
      (->> (.listFiles d)
           (map #(.getName %))
           (filter #(str/ends-with? % ".md"))
           (mapv #(subs % 0 (- (count %) 3)))
           sort vec))))

;; --- the shape ---------------------------------------------------------------------

(def ^:private item-re #"^\s*-\s*\[([ xX])\]\s*(?:\[([A-Za-z0-9_-]+)\]\s*)?(.*)$")

(defn parse
  "Markdown → `{:title :plan :items [{:index :done? :via :text}]}`. A line that
  is not a checkbox is kept only as the title and the plan reference;
  everything else about the file is the person's, and rewriting preserves it.

  `:plan` is what the checklist came FROM — brainyard's dossier carries the same
  thing as `source.plan_dossier`, and it is the one part of that chain a resumed
  run genuinely needs: without it the gate at the end has nothing to judge
  against (RFC-023, `byj-gly.67`)."
  [text]
  (let [lines (str/split-lines (str text))
        title (or (some #(when (str/starts-with? % "# ") (str/trim (subs % 2))) lines) "")
        plan (some #(when (str/starts-with? % "Plan: ") (str/trim (subs % 6))) lines)
        items (loop [[l & more] lines, i 0, out []]
                (if (nil? l)
                  out
                  (if-let [[_ box via txt] (re-matches item-re l)]
                    (recur more (inc i)
                           (conj out (cond-> {:index i
                                              :done? (not= " " box)
                                              :text (str/trim txt)}
                                       via (assoc :via via))))
                    (recur more i out))))]
    (cond-> {:title title :items items}
      plan (assoc :plan plan))))

(defn render
  "`{:title :plan :items}` → markdown."
  [{:keys [title plan items]}]
  (str "# " title "\n\n"
       (when plan (str "Plan: " plan "\n\n"))
       (str/join "\n" (for [{:keys [done? via text]} items]
                        (str "- [" (if done? "x" " ") "] "
                             (when via (str "[" via "] "))
                             text)))
       "\n"))

(defn read-todo [config slug]
  (let [f (todo-file config slug)]
    (when (.isFile f)
      (let [todo (assoc (parse (slurp f)) :slug slug :path (.getPath f))
            left (count (remove :done? (:items todo)))]
        ;; :has-work is a BOOLEAN on purpose. A tree branches on truthiness, and
        ;; 0 is truthy in Clojure — a count would resume a finished list.
        (assoc todo :remaining left :has-work (pos? left))))))

(defn- write-todo! [config slug todo]
  (let [f (todo-file config slug)]
    (.mkdirs (.getParentFile f))
    (spit f (render todo))
    (.getPath f)))

(defn- item->map
  "An item as the model may give it: a string, or a map with `:text`/`:via`."
  [x]
  (cond
    (string? x) {:text (str/trim x) :done? false}
    (map? x) (cond-> {:text (str/trim (str (or (:text x) (get x "text")))) :done? false}
               (or (:via x) (get x "via")) (assoc :via (str (or (:via x) (get x "via")))))
    :else {:text (str/trim (str x)) :done? false}))

(defn create!
  "Write a list, replacing any list under that slug. `plan` is what it came
  from — a path or a short reference — and is what a resumed run judges against."
  ([config slug title items] (create! config slug title items nil))
  ([config slug title items plan]
   (let [items (vec (remove #(str/blank? (:text %)) (map item->map items)))]
     (when (empty? items)
       (throw (ex-info "a todo needs at least one item" {:slug slug})))
     {:slug slug
      :path (write-todo! config slug (cond-> {:title (or title slug) :items items}
                                       (not (str/blank? (str plan))) (assoc :plan (str/trim (str plan)))))
      :count (count items)})))

(defn add!
  "Append items to an existing list."
  [config slug items]
  (if-let [todo (read-todo config slug)]
    (let [added (vec (remove #(str/blank? (:text %)) (map item->map items)))
          all (into (vec (:items todo)) added)]
      {:slug slug :path (write-todo! config slug (assoc todo :items all))
       :added (count added) :count (count all)})
    {:error (str "no todo " slug)}))

(defn mark!
  "Mark one item done — by index, or by enough of its text to be unique.

  brainyard's exec-agent flips a checkbox by matching the item's text and warns
  against a drifting numeric index; both are offered here, and an ambiguous
  match is refused rather than guessed."
  [config slug {:keys [index text]}]
  (if-let [{:keys [items] :as todo} (read-todo config slug)]
    (let [hits (cond
                 (some? index) (filter #(= index (:index %)) items)
                 (not (str/blank? (str text)))
                 (filter #(str/includes? (str/lower-case (:text %))
                                         (str/lower-case (str/trim (str text)))) items)
                 :else [])]
      (cond
        (empty? hits) {:error (str "no item matching " (pr-str (or index text)) " in " slug)}
        (> (count hits) 1) {:error (str (count hits) " items match " (pr-str text)
                                        " — say more of the text, or pass an index")
                            :matches (mapv :text hits)}
        :else
        (let [hit (first hits)
              items' (mapv #(if (= (:index %) (:index hit)) (assoc % :done? true) %) items)]
          {:slug slug :path (write-todo! config slug (assoc todo :items items'))
           :done (:text hit)
           :remaining (count (remove :done? items'))})))
    {:error (str "no todo " slug)}))

(defn next-item
  "The first item not yet done, or `{:exhausted true}`. The step a workflow
  repeats on."
  [config slug]
  (if-let [{:keys [items]} (read-todo config slug)]
    (let [left (count (remove :done? items))]
      (if-let [it (first (remove :done? items))]
        (cond-> {:index (:index it) :text (:text it) :exhausted false :remaining left}
          (:via it) (assoc :via (:via it)))
        {:exhausted true :index nil :text nil :remaining 0}))
    {:error (str "no todo " slug)}))

;; --- commands ----------------------------------------------------------------------

(def ^:private error-schema [:map [:error :string]])

(def ^:private item-schema
  [:map [:index :int] [:done? :boolean] [:via {:optional true} :string] [:text :string]])

(defn commands
  "`todo$*` — the durable half of an implementation workflow (RFC-023)."
  [config]
  (when (:todos? config true)
    [{:name "todo$create" :kind :command
      :description (str "Write a checklist that OUTLIVES this turn, under .brainyard/todos/<slug>.md."
                        " Use it when a task has more steps than one turn should carry: the list is"
                        " what lets a later turn pick up where this one stopped. An item may name how"
                        " it should be done with `via`.")
      :input-schema [:map [:slug [:string {:desc "File name, e.g. retry-budget."}]]
                          [:title {:optional true} :string]
                          [:items [:vector {:desc "Strings, or maps {text, via}."} [:or :string :map]]]
                          [:plan {:optional true} [:string {:desc "What this came from — a path or short reference. A resumed run judges against it."}]]]
      :output-schema [:or [:map [:slug :string] [:path :string] [:count :int]] error-schema]
      :effects #{:fs/write}
      :fn (fn [{:keys [slug title items plan]} ctx]
            (try (create! (:config ctx) slug title items plan)
                 (catch Throwable e {:error (ex-message e)})))}

     {:name "todo$list" :kind :command
      :description "List the checklists this project has, with how much of each is left."
      :input-schema [:map]
      :output-schema [:map [:todos [:vector [:map [:slug :string] [:title :string] [:count :int] [:remaining :int]]]]]
      :effects #{:fs/read}
      :fn (fn [_ ctx]
            (let [config (:config ctx)]
              {:todos (vec (for [s (list-slugs config)
                                 :let [t (read-todo config s)]]
                             {:slug s :title (:title t) :count (count (:items t))
                              :remaining (count (remove :done? (:items t)))}))}))}

     {:name "todo$read" :kind :command
      :description "Read a checklist: every item, its index, whether it is done, and its `via` tag."
      :input-schema [:map [:slug :string]]
      :output-schema [:or [:map [:slug :string] [:title :string] [:plan {:optional true} :string] [:path :string]
                        [:remaining :int] [:has-work :boolean] [:items [:vector item-schema]]]
                 error-schema]
      :effects #{:fs/read}
      :fn (fn [{:keys [slug]} ctx]
            (try (or (read-todo (:config ctx) slug) {:error (str "no todo " slug)})
                 (catch Throwable e {:error (ex-message e)})))}

     {:name "todo$next" :kind :command
      :description (str "The first item not yet done. Returns :exhausted true when the list is"
                        " finished, which is how a loop over a checklist knows to stop.")
      :input-schema [:map [:slug :string]]
      :output-schema [:or {:desc ":index and :text are nil once :exhausted"}
                 [:map [:index [:maybe :int]] [:text [:maybe :string]] [:via {:optional true} :string]
                  [:exhausted :boolean] [:remaining :int]]
                 error-schema]
      :effects #{:fs/read}
      :fn (fn [{:keys [slug]} ctx]
            (try (next-item (:config ctx) slug)
                 (catch Throwable e {:error (ex-message e)})))}

     {:name "todo$done" :kind :command
      :description (str "Mark one item done, by index or by enough of its text to be unique."
                        " Mark it only when there is evidence it IS done — a checklist that lies"
                        " is worse than no checklist, because the next turn believes it.")
      :input-schema [:map [:slug :string]
                          [:index {:optional true} :int]
                          [:text {:optional true} [:string {:desc "Enough of the item to be unique."}]]]
      :output-schema [:or {:desc ":matches lists the items an ambiguous text matched"}
                 [:map [:slug :string] [:done :string] [:remaining :int] [:path :string]]
                 [:map [:error :string] [:matches {:optional true} [:vector :string]]]]
      :effects #{:fs/write}
      :fn (fn [{:keys [slug index text]} ctx]
            (try (mark! (:config ctx) slug {:index index :text text})
                 (catch Throwable e {:error (ex-message e)})))}

     {:name "todo$add" :kind :command
      :description "Append items to a checklist — what a step turned out to need that the plan missed."
      :input-schema [:map [:slug :string]
                          [:items [:vector [:or :string :map]]]]
      :output-schema [:or [:map [:slug :string] [:added :int] [:count :int] [:path :string]] error-schema]
      :effects #{:fs/write}
      :fn (fn [{:keys [slug items]} ctx]
            (try (add! (:config ctx) slug items)
                 (catch Throwable e {:error (ex-message e)})))}]))
