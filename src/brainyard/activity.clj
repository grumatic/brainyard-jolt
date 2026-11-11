(ns brainyard.activity
  "The right rail's Activity view (byj-gly.411): a live feed of what the
  session is doing, by turn — its iterations, its tool calls with how long
  each took, the subagents it dispatched and their calls, background tasks,
  the auto-mode classifier's decisions, retries after a model error, and a
  hook's block. brainyard's Mode B activity pane, in the rail rather than in
  a tmux pane (byj-gly.98 keeps what needs tmux).

  It is RFC-026's events read back, never a second account of the run: the
  caller hands each event to `record` (main does, from the session's
  on-event), and `rows` draws the feed. Mechanism only (RFC-001): what an
  event becomes is a pure function of the event and the feed so far."
  (:require [clojure.string :as str]
            [brainyard.clock :as clock]))

(def max-rows
  "The most rows the feed keeps; the oldest go first."
  500)

(def ^:private max-result-chars
  "How much of a tool's result a row keeps, for Enter to show."
  20000)

(defn- clock-label [ms offset]
  (let [{:keys [hour minute]} (clock/civil ms (or offset 0))]
    (format "%02d:%02d" hour minute)))

(defn elapsed-label
  "`12ms`, `4.2s`, `3m05s`."
  [ms]
  (when ms
    (let [ms (long ms)]
      (cond (< ms 1000) (str ms "ms")
            (< ms 60000) (format "%.1fs" (/ ms 1000.0))
            :else (format "%dm%02ds" (quot ms 60000) (quot (mod ms 60000) 1000))))))

(defn- cost-label [usage]
  (when-let [c (:cost usage)] (when (pos? c) (format "$%.2f" (double c)))))

(defn- args-label
  "What a call was about, in a few words: the first value it was given that
  is a string — a path, a command, a pattern."
  [args]
  (let [v (some (fn [[_ v]] (when (string? v) v)) (if (map? args) args {}))]
    (when v (str/replace (str/trim v) #"\s+" " "))))

(defn- depth [data] (if (:subagent-id data) 1 0))

(defn- add-row [feed row]
  (let [id (inc (:last-id feed 0))
        rows (conj (vec (:rows feed)) (assoc row :id id :turn (:turn feed)))
        rows (if (> (count rows) max-rows) (subvec rows (- (count rows) max-rows)) rows)]
    (assoc feed :rows rows :last-id id
           ;; a turn none of whose rows is kept is forgotten too
           :turns (select-keys (:turns feed) (distinct (keep :turn rows))))))

(defn- settle-tool
  "The first running call of `name` at depth `d` in this turn, now ended."
  [feed name d result]
  (let [rows (vec (:rows feed))
        i (first (keep-indexed (fn [i r] (when (and (= :tool (:kind r)) (= :running (:status r))
                                                    (= name (:name r)) (= d (:depth r))
                                                    (= (:turn feed) (:turn r)))
                                           i))
                               rows))
        settled {:status (if (:tool-error? result) :failed :done)
                 :elapsed-ms (:elapsed-ms result)
                 :result (let [s (str (:tool-result result))]
                           (if (> (count s) max-result-chars) (str (subs s 0 max-result-chars) "…") s))}]
    (if i
      (assoc feed :rows (update rows i merge settled))
      (add-row feed (merge {:kind :tool :name name :args (:tool-args result) :depth d} settled)))))

(defn record
  "`feed` with event `e` (data `data`, at `now-ms`, the machine `offset`
  seconds east of UTC) recorded; an event the feed does not show leaves it
  as it was."
  [feed e data now-ms offset]
  (let [d (depth data)]
    (case e
      :session.turn/start
      (if (:subagent-id data)
        feed
        (let [n (or (:turn data) (inc (count (:turns feed))))]
          (-> feed
              (assoc :turn n)
              (assoc-in [:turns n] {:at (clock-label now-ms offset) :question (str (:question data))}))))
      :session.turn/end
      (if (:subagent-id data)
        feed
        (-> feed
            (update-in [:turns (:turn feed)] merge {:elapsed-ms (:elapsed-ms data) :usage (:usage data)})
            (add-row {:kind :end :depth 0 :terminated-by (:terminated-by data) :iterations (:iterations data)})))
      :agent.iteration/start (add-row feed {:kind :iteration :depth d :n (:iteration data)})
      :agent.tools/calls (reduce (fn [f c] (add-row f {:kind :tool :status :running :depth d
                                                         :name (:tool-name c) :args (:tool-args c)}))
                                 feed (:tool-calls data))
      :agent.tools/results (reduce (fn [f r] (settle-tool f (:tool-name r) d r)) feed (:results data))
      :agent.subagent/dispatch (add-row feed {:kind :dispatch :depth d :agent (:agent data)})
      :agent.subagent/result (add-row feed {:kind :answered :depth d :agent (:agent data) :usage (:usage data)})
      ;; a task by what it is — its name, or the tool that became it — not
      ;; by its id, which is most of a rail's width and says nothing
      :task/created (-> feed
                        (assoc-in [:tasks (:id data)] (str (:name data)))
                        (add-row {:kind :task :depth d :text (str (:name data) " started")}))
      :task/detached (-> feed
                         (assoc-in [:tasks (:id data)] (str (:tool data)))
                         (add-row {:kind :task :depth d
                                   :text (str (:tool data) (if (= :person (:by data)) " sent to the background" " to the background"))}))
      :task/completed (add-row feed {:kind :task :depth d :elapsed-ms (:elapsed-ms data)
                                     :text (str (get-in feed [:tasks (:id data)] "a task") " "
                                                (case (:status data) (:done :completed :ok nil) "ended" (name (:status data))))})
      :permission/classified (add-row feed {:kind :note :depth d
                                            :text (str "auto mode " (some-> (:verdict data) name) " " (or (:display data) (:tool data)))})
      :agent.repair/llm-error (add-row feed {:kind :warn :depth d :text (str "model error, retry " (:attempt data))})
      :agent.tools/blocked (add-row feed {:kind :warn :depth d :text (str "blocked: " (:reason data))})
      feed)))

(defn- row-item
  "One feed row as a rail item."
  [{:keys [kind status depth name args elapsed-ms n agent usage text terminated-by iterations] :as row}]
  (let [pad (apply str "  " (repeat depth "  "))]
    (case kind
      :tool {:label (str pad (case status :running "◐" :failed "✗" "✓") " " name
                         (when-let [a (args-label args)] (str " " a)))
             :hint (elapsed-label elapsed-ms)
             :style (case status :running {:color :cyan} :failed {:color :red} {})
             :row row}
      :iteration {:label (str pad "↻ iteration " n) :style {:dim true}}
      :dispatch {:label (str pad "⇢ agent$" agent) :style {:color :magenta}}
      :answered {:label (str pad "⇠ " agent " answered") :hint (cost-label usage) :style {:color :magenta}}
      :task {:label (str pad "⧗ " text) :hint (elapsed-label elapsed-ms)}
      :note {:label (str pad "⚖ " text) :style {:dim true}}
      :warn {:label (str pad "! " text) :style {:color :yellow}}
      :end {:label (str pad (if (#{:answer :answer-channel} terminated-by) "■ answered" (str "■ " (some-> terminated-by name)))
                        (when iterations (str " · " iterations (if (= 1 iterations) " iteration" " iterations"))))
            :style {:dim true}}
      {:label (str pad (str kind))})))

(defn rows
  "The feed as the rail's items: each turn a section heading — `Turn N ·
  HH:MM`, its duration and cost — then its rows, while it is open. The
  newest turn is open unless toggled shut; the others shut unless toggled
  open. Rows before the first turn, a direct call's, come under `Between
  turns`."
  [feed toggled]
  (let [groups (partition-by :turn (:rows feed))
        newest (:turn (last (:rows feed)))]
    (if (empty? groups)
      [{:label "  nothing yet: a turn's work shows here as it runs" :style {:dim true}}]
      (vec (mapcat (fn [g]
                     (let [n (:turn (first g))
                           k (or n ::between)
                           t (get-in feed [:turns n])
                           open? (not= (= n newest) (contains? toggled k))]
                       (cons {:label (str (if open? "▾ " "▸ ") (if n (str "Turn " n " · " (:at t)) "Between turns"))
                              :hint (str/join " " (remove nil? [(elapsed-label (:elapsed-ms t)) (cost-label (:usage t))]))
                              :section true :turn k}
                             (when open? (map row-item g)))))
                   groups)))))

(defn views
  "The Activity view and the `record!` that feeds it: `{:view v :record!
  (fn [e data]) :feed cell}`. Enter on a turn's heading folds or unfolds it,
  on a tool call shows `show` its arguments and result; the mark follows the
  newest row until a key moves it (`:mark`). `cell` as for
  `project-views/views`."
  [& [{:keys [cell show] :or {cell atom}}]]
  (let [feed (cell {:rows [] :turns {}})
        toggled (cell #{})]
    {:feed feed
     ;; never in the way of the event it reads: a fault here costs the row
     :record! (fn [e data]
                (try (swap! feed record e (if (map? data) data {}) (System/currentTimeMillis) (clock/offset-seconds))
                     (catch Throwable _ nil)))
     :view {:id "activity" :title "Activity"
            :items (fn [] (rows @feed @toggled))
            :mark (fn [items] (when (seq items) (dec (count items))))
            :act (fn [{:keys [turn row]}]
                   (cond turn (swap! toggled #(if (contains? % turn) (disj % turn) (conj % turn)))
                         (and row show (= :tool (:kind row)))
                         (show (str (:name row) (when-let [a (args-label (:args row))] (str " — " a)))
                               (str "arguments: " (pr-str (:args row))
                                    (when (:elapsed-ms row) (str "\ntook: " (elapsed-label (:elapsed-ms row))))
                                    (when (= :failed (:status row)) "\nfailed")
                                    "\n\n" (or (:result row) "(still running)")))))}}))
