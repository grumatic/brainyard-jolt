(ns brainyard.proposals
  "One review gate for every kind of self-improvement (byj-gly.249.1).

  Self-improvement drafts; a person decides. Skill distillation and refinement
  stage `SKILL.md` drafts (RFC-017), a compiled program stages predictor
  params (RFC-021), and tool distillation stages user tools (RFC-010). Each
  kind keeps its drafts where it always did and knows how to make one live.
  This namespace puts them behind one list, one id scheme and one
  accept/reject, so the session has a single `/proposals` and the model a
  single read-only `proposal$list` / `proposal$read`.

  An id is `KIND/NAME`: `skill/list-twice`, `tool/count-lines`,
  `program/react/think-act/1790…`. A bare name is accepted where no other
  kind has it.

  A kind is a map of fns over the run's config:
    :list    (fn [config]) → [{:name :summary :score :created-ts …}], pending only
    :read    (fn [config name]) → the proposal with its `:body` — the draft as
             a person reads it — or nil
    :accept  (fn [config name opts]) → a result map, `:error` on failure
    :reject  (fn [config name]) → a result map, `:error` on failure

  Accepting is never the model's: brainyard's rule that nothing it drafts
  goes live unreviewed, kept by not giving the proposer the key (RFC-017,
  RFC-021)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.programs :as programs]
            [brainyard.self-improve :as self-improve]
            [brainyard.tool-distill :as tool-distill]))

;; --- kinds -----------------------------------------------------------------------------

(def ^:private skill-kind
  {:list (fn [config]
           (for [{:keys [name kind score rationale created-ts]} (self-improve/list-proposals config)]
             {:name name :score score :created-ts created-ts
              :summary (str (clojure.core/name (or kind :distillation))
                            (when (seq rationale) (str " — " rationale)))}))
   :read (fn [config name]
           (when-let [p (self-improve/read-proposal config name)]
             (assoc p :body (str (:skill-md p)
                                 (when (seq (:evidence p)) (str "\n\nWhy it was revised:\n" (:evidence p)))))))
   ;; `user` puts a new skill in the person's skills rather than the project's
   :accept (fn [config name {:keys [scope]}]
             (let [r (self-improve/accept-proposal! config nil nil name scope)]
               (cond-> r
                 (not (:error r)) (assoc :said (str (if (= "update" (:op r)) "revised" "created")
                                                    " skill `" (:name r) "` (" (:scope r) ") at " (:path r))))))
   :reject (fn [config name] (self-improve/reject-proposal! config name))})

(defn- program-proposal-dirs
  "Every predictor's proposals directory under the project's programs root,
  as `[predictor-id dir]`. A predictor id may hold slashes (`react/think-act`),
  so the id is the path between the root and `proposals`."
  [config]
  (let [root (programs/programs-root config)
        rootp (str (.getCanonicalPath root) "/")]
    (when (.isDirectory root)
      (for [d (file-seq root)
            :when (and (.isDirectory d) (= "proposals" (.getName d)))
            :let [id (subs (.getCanonicalPath (.getParentFile d)) (count rootp))]
            :when (not (str/blank? id))]
        [id d]))))

(defn- split-program-name
  "`react/think-act/1790…` as `[predictor-id proposal-id]`."
  [name]
  (let [i (str/last-index-of (str name) "/")]
    (when (and i (pos? i)) [(subs name 0 i) (subs name (inc i))])))

(def ^:private program-kind
  {:list (fn [config]
           (for [[id _] (program-proposal-dirs config)
                 {:keys [proposal-id status optimizer best best-score zero-shot-score]} (programs/list-proposals config id)
                 :when (= "pending" status)]
             {:name (str id "/" proposal-id) :score best-score
              :created-ts (parse-long (str (re-find #"^\d+" (str proposal-id))))
              :summary (str "params for " id " from " optimizer
                            (when best-score (str " — " best " scored " best-score
                                                  (when zero-shot-score (str " (zero-shot " zero-shot-score ")")))))}))
   :read (fn [config name]
           (when-let [[id pid] (split-program-name name)]
             (let [dir (io/file (programs/proposals-dir config id) pid)
                   review (io/file dir "REVIEW.md")]
               (when (.isDirectory dir)
                 {:name name :path (.getPath dir)
                  :body (if (.isFile review) (slurp review) "(no REVIEW.md)")}))))
   :accept (fn [config name _]
             (if-let [[id pid] (split-program-name name)]
               (try (let [r (programs/accept-proposal! config id pid)]
                      (assoc r :said (str "installed params for " id " at " (:params-file r)
                                          (when (:previous r) " (the old ones kept as previous.edn)"))))
                    (catch Throwable e {:error (ex-message e)}))
               {:error (str "not a program proposal: " name)}))
   :reject (fn [config name]
             (if-let [[id pid] (split-program-name name)]
               (try (programs/reject-proposal! config id pid) {:rejected name}
                    (catch Throwable e {:error (ex-message e)}))
               {:error (str "not a program proposal: " name)}))})

(def ^:private tool-kind
  {:list (fn [config]
           (for [{:keys [name score rationale created-ts proof]} (tool-distill/list-proposals config)]
             {:name name :score score :created-ts created-ts
              :summary (str (if (= :verified (:status proof)) "replayed" "NOT replayed — review the body")
                            (when (seq rationale) (str " — " rationale)))}))
   :read (fn [config name]
           (when-let [p (tool-distill/read-proposal config name)]
             (assoc p :body (tool-distill/proposal-text p))))
   :accept (fn [config name _]
             (let [r (tool-distill/accept-proposal! config name)]
               (cond-> r
                 (not (:error r)) (assoc :said (str "created user tool `user$tool$" name "` at " (:path r))))))
   :reject (fn [config name] (tool-distill/reject-proposal! config name))})

(defn kinds
  "Every kind of proposal, in the order they are listed."
  []
  (array-map :skill skill-kind :tool tool-kind :program program-kind))

;; --- one gate ------------------------------------------------------------------------

(defn list-all
  "Every pending proposal of every kind, newest first:
  `[{:id :kind :name :summary :score :created-ts}]`. A kind that cannot be read
  says so as a proposal of its own rather than hiding the rest."
  ([config] (list-all config nil))
  ([config only-kind]
   (->> (for [[k {:keys [list]}] (kinds)
              :when (or (nil? only-kind) (= (name k) (name only-kind)))
              p (try (list config)
                     (catch Throwable e [{:name "?" :summary (str "could not list: " (ex-message e))}]))]
          (assoc p :kind k :id (str (name k) "/" (:name p))))
        (sort-by #(- (or (:created-ts %) 0)))
        vec)))

(defn resolve-id
  "`[kind name]` for an id — `KIND/NAME`, or a bare name only one kind has —
  or `{:error …}`."
  [config id]
  (let [id (str/trim (str id))
        [head tail] (str/split id #"/" 2)
        k (keyword head)]
    (cond
      (str/blank? id) {:error "which proposal? /proposals lists them"}
      (and tail (contains? (kinds) k)) [k tail]
      :else (let [hits (filter #(= id (:name %)) (list-all config))]
              (case (count hits)
                1 [(:kind (first hits)) id]
                0 {:error (str "no proposal named '" id "' — /proposals lists them")}
                {:error (str "'" id "' names " (count hits) " proposals — say which: "
                             (str/join ", " (map :id hits)))})))))

(defn read-one
  [config id]
  (let [r (resolve-id config id)]
    (if (map? r)
      r
      (let [[k nm] r]
        (or (some-> ((:read (get (kinds) k)) config nm) (assoc :kind k :id (str (name k) "/" nm)))
            {:error (str "no " (name k) " proposal named '" nm "'")})))))

(defn accept!
  "Make a proposal live, by its kind's own path. Only a person gets here —
  the session's `/proposals accept`; no tool calls it."
  [config id opts]
  (let [r (resolve-id config id)]
    (if (map? r)
      r
      (let [[k nm] r]
        (assoc ((:accept (get (kinds) k)) config nm opts) :kind k :id (str (name k) "/" nm))))))

(defn reject!
  [config id]
  (let [r (resolve-id config id)]
    (if (map? r)
      r
      (let [[k nm] r]
        (assoc ((:reject (get (kinds) k)) config nm) :kind k :id (str (name k) "/" nm))))))

;; --- what the model may see ---------------------------------------------------------

(defn commands
  "`proposal$list` and `proposal$read`: what the model may see of what
  self-improvement proposed — brainyard's `skill-proposal$list`/`$read`, for
  every kind (divergence `one-proposals-gate`). Accepting and rejecting are
  not here: a gate the proposer can open is no gate."
  [config]
  [{:name "proposal$list" :kind :command
    :description (str "List what self-improvement proposed and a person has not yet decided on: skills, "
                      "user tools, predictor params — newest first. None is live until a person accepts it "
                      "(/proposals in the session).")
    :input-schema [:map [:kind {:optional true} [:string {:desc "only this kind: skill, tool or program"}]]]
    :output-schema [:map [:proposals [:vector [:map [:id :string] [:kind :keyword] [:name :string]
                                               [:summary {:optional true} [:maybe :string]]
                                               [:score {:optional true} [:maybe number?]]
                                               [:created-ts {:optional true} [:maybe :int]]]]]]
    :effects #{:fs/read}
    :fn (fn [{:keys [kind]} _] {:proposals (list-all config kind)})}
   {:name "proposal$read" :kind :command
    :description "Read one proposal by its id from proposal$list: the draft as it would go live, and why it was proposed."
    :input-schema [:map [:id [:string {:desc "the proposal's id, e.g. skill/list-twice"}]]]
    :output-schema [:or [:map [:id :string] [:kind :keyword] [:body [:maybe :string]]] [:map [:error :string]]]
    :effects #{:fs/read}
    :fn (fn [{:keys [id]} _] (read-one config id))}])
