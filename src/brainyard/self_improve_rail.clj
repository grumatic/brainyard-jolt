(ns brainyard.self-improve-rail
  "The left rail's Self-improvement section (byj-gly.425): whether the session
  distils tools and skills from what it did, as two toggles, and what that
  proposed, waiting for a person (`brainyard.proposals`).

  - **The toggles** are `:enable-tool-distillation` (RFC-010) and
    `:enable-skill-distillation` (RFC-017). Enter on one sends `/config KEY
    true|false` to the session as if it were typed (`send!`), so it is written
    to the project's config.edn and put in force exactly as `/config` writes
    anything — snapshot first, read back, its line in the transcript — and
    queued, as typing is, while a turn runs. The row flips at once; the config
    files are read again when the rail opens and when a turn ends.
  - **The proposals** are every pending one, of every kind — skills, tools,
    predictor params — newest first, with its score, under a `Proposals`
    heading with their count that Enter folds and unfolds. Enter on one shows the draft
    whole, with the `/proposals accept ID` and `reject ID` that decide it;
    `@` puts its id into the input. Accepting stays a command a person types:
    nothing the session drafts goes live unreviewed (RFC-017, RFC-021).

  Mechanism only (RFC-001): when it is read is the caller's (`refresh!`), and
  a read is a Task off the render loop, as `brainyard.issues` does."
  (:require [clojure.string :as str]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.proposals :as proposals]
            [ebb.core :as m]))

(def toggles
  "The keys the section toggles, in its order, with how a row names each."
  [[:enable-tool-distillation "tool distillation"]
   [:enable-skill-distillation "skill distillation"]])

(defn- read-config-fn
  "The config in force now, from the files, as a turn's start reads it again:
  a `/config` writes the project's file."
  [config]
  (fn []
    (:config (config/load-config {:working-dir (str (or (:working-dir config) (:project-dir config) "."))
                                  :project-dir (some-> (:project-dir config) str)}))))

(defn- score [s] (when (number? s) (format "%.2f" (double s))))

(defn rows
  "The section's rows for what was read: the toggles, flush, then the
  proposals under a heading with their count, folded unless `open?`, as an
  epic is under Issues."
  [{:keys [settings proposals error]} open?]
  (vec (concat
        (for [[k what] toggles
              :let [on? (true? (get settings k))]]
          {:label (str (if on? "[x] " "[ ] ") what)
           :hint (if (nil? settings) "" (if on? "on" "off"))
           :hint-style (if on? {:color :green} {:dim true})
           :toggle k :on? on?})
        [{:label (str (if open? "▾ " "▸ ") "Proposals")
          :hint (cond error "!" (nil? proposals) "" :else (str (count proposals)))
          :group :proposals}]
        (when open?
          (cond
            error [{:label (str "  could not read: " (first (str/split-lines (str error)))) :style {:dim true}}]
            (nil? proposals) [{:label "  reading…"}]
            (empty? proposals) [{:label "  none waiting" :style {:dim true}}]
            :else (for [p proposals]
                    {:label (str "  ◇ " (:id p)) :hint (or (score (:score p)) "") :proposal p}))))))

(defn views
  "The Self-improvement view over `config` and the `refresh!` that rereads
  it: `{:view v :refresh! (fn []) :state cell}`. `send!` delivers a line to
  the session as if typed; `show` is called with a title and text;
  `read-config` is the config in force now (the files', unless given);
  `cell` and `kept` as for `project-views/views`."
  [config & [{:keys [cell kept show send! read-config] :or {cell atom}}]]
  (let [read-config (or read-config (read-config-fn config))
        state (cell {})
        open? ((or kept (fn [_ x] (cell x))) :self-improve/open? false)
        running (atom nil)
        refresh! (fn []
                   (some-> @running (apply []))
                   (reset! running
                           (fx/run-detached
                            (m/sp (try (let [c (m/? (fx/task-of read-config))]
                                         (reset! state {:settings (select-keys c (map first toggles))
                                                        :proposals (m/? (fx/task-of #(proposals/list-all c)))}))
                                       (catch Throwable e
                                         (when-not (fx/cancelled? e)
                                           (swap! state assoc :error (or (ex-message e) (str e)))))))
                            :self-improve)))]
    {:refresh! refresh!
     :state state
     :view {:id "self-improvement" :title "Self-improvement"
            :items (fn [] (rows @state @open?))
            :act (fn [{:keys [toggle on? group proposal]}]
                   (cond
                     group (swap! open? not)
                     (and toggle send!)
                     (do (swap! state assoc-in [:settings toggle] (not on?))
                         (send! (str "/config " (name toggle) " " (not on?)) false))
                     (and proposal show)
                     (fx/run-detached
                      (m/sp (let [p (m/? (fx/task-of #(proposals/read-one (read-config) (:id proposal))))]
                              (show (str "proposal — " (:id proposal))
                                    (str (or (:error p) (:body p) (:summary proposal))
                                         "\n\n/proposals accept " (:id proposal) " · /proposals reject " (:id proposal)))))
                      :self-improve-show)))
            :put (fn [{:keys [proposal]}] (some-> proposal :id))
            :on-show refresh!}}))
