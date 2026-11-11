(ns brainyard.schedule
  "Prompts set to a time: a cron, a moment, or a delay (byj-gly.325) —
  brainyard's `agent/common/schedule.clj`, its names and its store
  (`<project>/.brainyard/schedule/<id>/spec.edn`, `:id :title :prompt :cron
  :fire-at :enabled :next-fire :last-run :last-status :created`).

  Where it differs (divergence `schedule-fires-into-the-session`): brainyard
  runs a due job as a separate agent run on a daemon thread and writes its
  answer to a file. Here a job is a turn of the session that made it
  (`:session`) — the prompt typed as if by the person, between turns, as
  Claude Code's `/loop` and its cron tasks are: a front end asks
  `ready-turn!` when it is idle, beside a parked turn's wakeup. So a job runs
  only while its session is open, and a recurring one expires after
  `recurring-ttl-ms` (Claude Code's seven days) unless told otherwise.

  Cron is five fields, in local time. jolt's java.time knows only UTC
  (provenance P-10), so the local offset is the OS's, as `brainyard.clock`
  keeps it — asked again every few minutes, so a daylight-saving change is
  noticed within them (byj-gly.382)."
  (:require [clojure.edn :as edn]
            [brainyard.clock :as clock]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [clojure.set]))

(def recurring-ttl-ms
  "How long a recurring job lives unless told otherwise: Claude Code's seven days."
  (* 7 24 3600 1000))

;; --- cron: min hour day-of-month month day-of-week ---------------------------------------------

(def ^:private bounds [[0 59] [0 23] [1 31] [1 12] [0 6]])

(defn- parse-part
  "One comma-separated part of a field: `*`, `n`, `a-b`, with an optional `/step`."
  [part lo hi]
  (let [[range-s step-s] (str/split part #"/" 2)
        step (if step-s (parse-long step-s) 1)
        [a b] (cond (= "*" range-s) [lo hi]
                    (str/includes? range-s "-") (mapv parse-long (str/split range-s #"-" 2))
                    :else (let [n (parse-long range-s)] [n (if step-s hi n)]))]
    (when (and a b step (pos? step) (<= lo a b hi))
      (set (range a (inc b) step)))))

(defn parse-cron
  "A 5-field cron as five sets of allowed values, or nil when it is not one. A
  day-of-week 7 is Sunday, as 0."
  [s]
  (let [fields (str/split (str/trim (str s)) #"\s+")]
    (when (= 5 (count fields))
      (let [sets (map (fn [f [lo hi]]
                        (let [hi (if (= [0 6] [lo hi]) 7 hi)
                              parts (map #(parse-part % lo hi) (str/split f #","))]
                          (when (every? some? parts) (apply clojure.set/union parts))))
                      fields bounds)]
        (when (every? seq sets)
          (let [[m h dom mon dow] (vec sets)]
            {:minute m :hour h :dom dom :month mon
             :dow (cond-> dow (contains? dow 7) (-> (disj 7) (conj 0)))
             :dom-any? (= "*" (nth fields 2)) :dow-any? (= "*" (nth fields 4))}))))))

(defn- local-offset-s [] (or (clock/offset-seconds) 0))

(defn- civil
  "Days since 1970-01-01 as `[year month day]` — Hinnant's civil_from_days:
  jolt's java.time has no ZoneOffset to do it with."
  [days]
  (let [z (+ days 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y (+ yoe (* era 400))
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (if (< mp 10) (+ mp 3) (- mp 9))]
    [(if (<= m 2) (inc y) y) m d]))

(defn- local-fields
  "`ms` as local `{:minute :hour :dom :month :dow}`, at `offset-s` from UTC
  (Sunday 0)."
  [ms offset-s]
  (let [secs (+ (Math/floorDiv (long ms) 1000) offset-s)
        days (Math/floorDiv secs 86400)
        sod (- secs (* days 86400))
        [_ month dom] (civil days)]
    {:minute (quot (mod sod 3600) 60) :hour (quot sod 3600) :dom dom :month month
     ;; 1970-01-01 was a Thursday
     :dow (mod (+ days 4) 7)}))

(defn matches?
  "Does local minute `ms` match `cron`? Day of month and day of week are
  either one, as cron's are, when both are restricted."
  [cron ms & [offset-s]]
  (let [{:keys [minute hour dom month dow]} (local-fields ms (or offset-s (local-offset-s)))
        day? (cond (and (:dom-any? cron) (:dow-any? cron)) true
                   (:dom-any? cron) (contains? (:dow cron) dow)
                   (:dow-any? cron) (contains? (:dom cron) dom)
                   :else (or (contains? (:dom cron) dom) (contains? (:dow cron) dow)))]
    (and (contains? (:minute cron) minute) (contains? (:hour cron) hour)
         (contains? (:month cron) month) day?)))

(defn next-fire-after
  "The first whole minute after `ms` that `cron` matches, within a year and a
  day, or nil."
  [cron ms & [offset-s]]
  (let [start (* 60000 (inc (quot ms 60000)))]
    (loop [t start n 0]
      (cond (> n (* 366 24 60)) nil
            (matches? cron t offset-s) t
            :else (recur (+ t 60000) (inc n))))))

;; --- an interval as a cron: Claude Code's /loop -----------------------------------------------

(defn interval->cron
  "`5m`, `2h`, `1d`, `30s` as `{:cron :every-text :rounded}` — Claude Code's
  /loop rules: seconds round up to a minute; minutes that do not divide 60, and
  hours that do not divide 24, round to the nearest that does, and say so.
  nil when it is not an interval."
  [s]
  (when-let [[_ n u] (re-matches #"(\d+)([smhd])" (str/trim (str s)))]
    (let [n (parse-long n)
          nearest (fn [x opts] (apply min-key #(Math/abs (- % x)) opts))
          mins (case u "s" (max 1 (long (Math/ceil (/ n 60.0)))) "m" n "h" (* 60 n) "d" (* 1440 n))]
      (when (pos? n)
        (cond
          (< mins 60) (let [m (nearest mins [1 2 3 4 5 6 10 12 15 20 30])]
                        {:cron (if (= 1 m) "* * * * *" (str "*/" m " * * * *")) :every-text (str m " min")
                         :rounded (when (not= m mins) (str s " → " m "m"))})
          (< mins 1440) (let [h (nearest (quot (+ mins 30) 60) [1 2 3 4 6 8 12])]
                          {:cron (if (= 1 h) "0 * * * *" (str "0 */" h " * * *")) :every-text (str h " h")
                           :rounded (when (not= (* 60 h) mins) (str s " → " h "h"))})
          :else (let [d (max 1 (quot mins 1440))]
                  {:cron (if (= 1 d) "0 0 * * *" (str "0 0 */" d " * *")) :every-text (str d " d")
                   :rounded (when (not= (* 1440 d) mins) (str s " → " d "d"))}))))))

(defn delay-ms
  "`20m`, `90s`, `2h` or a number of ms as ms, or nil."
  [x]
  (cond (number? x) (long x)
        :else (when-let [[_ n u] (re-matches #"(\d+)\s*([smhd]?)" (str/trim (str x)))]
                (* (parse-long n) (case u "s" 1000 "m" 60000 "h" 3600000 "d" 86400000 "" 1)))))

;; --- the store, brainyard's layout ---------------------------------------------------------------

(defn root [project] (io/file (str project) ".brainyard" "schedule"))
(defn- spec-file [project id] (io/file (root project) (str id) "spec.edn"))
(def ^:private id-re #"^[a-z0-9][a-z0-9-]*$")
(defn valid-id? [id] (boolean (and (string? id) (re-matches id-re id))))

(defn write-spec!
  "Write `spec` (with an `:id`) whole, through a temporary file."
  [project {:keys [id] :as spec}]
  (when-not (valid-id? id) (throw (ex-info (str "invalid schedule id: " (pr-str id)) {:id id})))
  (let [f (spec-file project id)
        tmp (io/file (.getParentFile f) "spec.edn.tmp")]
    (io/make-parents f)
    (spit tmp (pr-str spec))
    (.renameTo tmp f)
    spec))

(defn read-spec [project id]
  (let [f (spec-file project id)]
    (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))))

(defn specs
  "Every job of `project`, oldest first."
  [project]
  (->> (or (.listFiles (root project)) [])
       (filter #(.isDirectory ^java.io.File %))
       (keep #(read-spec project (.getName ^java.io.File %)))
       (sort-by :created)
       vec))

(defn remove! [project id]
  (when (valid-id? id)
    (let [d (io/file (root project) id)]
      (when (.isDirectory d)
        (doseq [f (reverse (file-seq d))] (.delete ^java.io.File f))
        true))))

(defn- gen-id [title now]
  (let [slug (-> (str (or (not-empty (str title)) "job")) str/lower-case
                 (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-+|-+$)" ""))
        slug (if (str/blank? slug) "job" slug)]
    (str (subs slug 0 (min 24 (count slug))) "-" now)))

(defn add!
  "A job of session `session` in `project`: `:prompt`, and `:cron` (a
  recurring one), or `:at` (epoch ms) or `:in` (a delay: `20m`, ms) for a
  one-shot. A recurring job expires after `recurring-ttl-ms` unless
  `:expires? false`. The spec, or `{:error}`."
  [project {:keys [prompt cron at in title session expires?] :as opts} & [now]]
  (let [now (or now (System/currentTimeMillis))
        parsed (when-not (str/blank? (str cron)) (parse-cron cron))
        wait (when (some? in) (delay-ms in))]
    (cond
      (str/blank? (str prompt)) {:error ":prompt is required"}
      (and (str/blank? (str cron)) (nil? at) (nil? in)) {:error "give :cron \"m h dom mon dow\" (recurring), or :at epoch-ms or :in a delay like \"20m\" (once)"}
      (and (not (str/blank? (str cron))) (nil? parsed))
      {:error (str "invalid :cron '" cron "' — five fields: minute hour day-of-month month day-of-week")}
      (and (some? in) (not (and wait (pos? wait)))) {:error (str "invalid :in '" in "' — a delay like 90s, 20m, 2h, or ms")}
      :else
      (let [nxt (cond parsed (next-fire-after parsed now) at (long at) :else (+ now wait))
            spec (cond-> {:id (gen-id (or title prompt) now)
                          :title (or (not-empty (str title)) (subs (str prompt) 0 (min 80 (count (str prompt)))))
                          :prompt (str prompt) :enabled true :next-fire nxt
                          :last-run nil :last-status nil :created now}
                   parsed (assoc :cron (str/trim (str cron)))
                   (not parsed) (assoc :fire-at nxt)
                   session (assoc :session (str session))
                   (and parsed (not (false? expires?))) (assoc :expires-at (+ now recurring-ttl-ms)))]
        (write-spec! project spec)))))

(defn set-enabled!
  "Enable or disable job `id`, its next fire worked out again when enabled."
  [project id on? & [now]]
  (if-let [s (read-spec project id)]
    (let [now (or now (System/currentTimeMillis))
          s' (cond-> (assoc s :enabled on?)
               (and on? (:cron s)) (assoc :next-fire (some-> (parse-cron (:cron s)) (next-fire-after now))))]
      (write-spec! project s'))
    {:error (str "no schedule '" id "'")}))

(defn run-now!
  "Make job `id` due now: it is the session's next turn when the session is
  idle. Its schedule after that is unchanged."
  [project id & [now]]
  (if-let [s (read-spec project id)]
    (write-spec! project (assoc s :enabled true :run-now true :next-fire (or (:next-fire s) (or now (System/currentTimeMillis)))))
    {:error (str "no schedule '" id "'")}))

(defn- mine? [s session] (or (nil? (:session s)) (nil? session) (= (str session) (:session s))))

(defn due?
  [s now]
  (and (:enabled s) (or (:run-now s) (and (number? (:next-fire s)) (<= (:next-fire s) now)))))

(declare watch-due!)

(defn- ready-job!
  "The first due prompt job of `session` (not a watch), advanced or removed."
  [project session now]
  (do
    ;; the one due longest first: a run-now, then the earliest fire
    (when-let [s (first (sort-by (juxt #(if (:run-now %) 0 1) #(or (:next-fire %) 0) #(or (:created %) 0))
                                 (filter #(and (not= :watch (:kind %)) (mine? % session) (due? % now)) (specs project))))]
      (if (:cron s)
        (write-spec! project (-> s (dissoc :run-now) (assoc :last-run now :last-status :fired
                                                            :next-fire (if (:run-now s) (:next-fire s)
                                                                           (some-> (parse-cron (:cron s)) (next-fire-after now))))))
        (remove! project (:id s)))
      (:prompt s))))

(defn ready-turn!
  "The turn to run next for `session` in `project`, at `now` — or nil. A front
  end asks this while it waits for input, as it asks for a parked turn's
  wakeup. First a watch that fired (what it saw is news), then the due prompt
  job (`ready-job!`). Due watches are probed in the background (`run-bg`),
  never here: the TUI asks from its drawing thread. Anything past
  `:expires-at` is removed instead."
  [project session & [now run-bg]]
  (let [now (or now (System/currentTimeMillis))
        run-bg (or run-bg #(fx/run-detached (fx/task-of %) :watch-probe))]
    (doseq [s (specs project) :when (and (:expires-at s) (> now (:expires-at s)) (mine? s session))]
      (remove! project (:id s)))
    ;; a watch that fired is told first: what it saw is news (byj-gly.331)
    (if-let [w (first (filter #(and (= :watch (:kind %)) (mine? % session) (:pending %)) (specs project)))]
      (do (if (:once w) (remove! project (:id w)) (write-spec! project (dissoc w :pending)))
          (:pending w))
      (do (watch-due! project session now run-bg)
          (ready-job! project session now)))))

;; --- watches: brainyard's watch$add, firing a turn (byj-gly.331) ------------------------------

(def watch-ttl-ms
  "How long a watch lives unless `:for` says: a day."
  (* 24 3600 1000))

(def probe-timeout-ms 20000)

(defn probe!
  "Look once: a `:shell` probe runs `:cmd` (sh -c, in the project) and keeps
  its exit and its trimmed output; a `:file` probe, the file's modification
  time. `{:value :exit}` or `{:error}`. Blocks up to `probe-timeout-ms`."
  [project {:keys [type cmd path]}]
  (case (keyword (or type :shell))
    :shell (let [r (fx/run!! (fx/process-task {:dir (str project) :out :string :err :string :in ""} "sh" "-c" (str cmd))
                             probe-timeout-ms)]
             (cond (:timeout r) {:error (str "no answer in " (quot probe-timeout-ms 1000) " s")}
                   (:err r) {:error (str (ex-message (:err r)))}
                   :else (let [o (str/trim (str (get-in r [:ok :out])))]
                           {:value (subs o (max 0 (- (count o) 4000))) :exit (get-in r [:ok :exit])})))
    :file (let [f (io/file (str path))
                f (if (.isAbsolute f) f (io/file (str project) (str path)))]
            (if (.exists f) {:value (str (.lastModified f)) :exit 0} {:value nil :exit 1}))
    {:error (str "unknown probe type: " (pr-str type))}))

(defn- as-num [v] (when (some? v) (let [s (str v)] (or (parse-long s) (parse-double s)))))

(defn predicate-met?
  "Should a watch fire, `obs` the fresh observation and `prev` the last —
  brainyard's ops: `:changed` (the default), `:matches` (`:re`), `:zero-exit`,
  `:nonzero-exit`, `:increased`, `:threshold` (`:cmp` `:value`). A first
  observation is the baseline for `:changed` and `:increased`: brainyard's
  fired on it, so a watch for a change fired at once."
  [when-spec prev obs]
  (let [op (keyword (or (:op when-spec) :changed))
        v (:value obs) pv (:value prev)]
    (case op
      :changed (and (some? prev) (not= v pv))
      :zero-exit (zero? (or (:exit obs) 1))
      :nonzero-exit (not (zero? (or (:exit obs) 0)))
      :matches (boolean (and v (:re when-spec) (re-find (re-pattern (str (:re when-spec))) v)))
      :increased (let [n (as-num v) p (as-num pv)] (boolean (and n p (> n p))))
      :threshold (let [n (as-num v) t (as-num (:value when-spec))]
                   (boolean (and n t (case (keyword (or (:cmp when-spec) :gt))
                                       :gt (> n t) :ge (>= n t) :lt (< n t) :le (<= n t) :eq (== n t) false))))
      false)))

(def ^:private watch-templates
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/watch.edn")))))

(defn- fill [t m] (reduce-kv (fn [t k v] (str/replace t (str "{" (name k) "}") (str v))) t m))

(defn- why-text [{:keys [op re cmp value]}]
  (case (keyword (or op :changed))
    :changed "what it saw changed" :matches (str "it matched /" re "/")
    :zero-exit "it exited 0" :nonzero-exit "it exited non-zero" :increased "the number rose"
    :threshold (str "the number is " (name (or cmp :gt)) " " value) (str op)))

(defn fired-text
  "The turn a watch that fired wakes its session with (`prompts/watch.edn`)."
  [{:keys [id title probe note once] :as spec} obs]
  (let [t @watch-templates
        seen (let [v (str (:value obs))] (if (> (count v) 1500) (str "…" (subs v (- (count v) 1500))) v))]
    (fill (:fired t) {:id id :title (or title id) :why (why-text (:when spec))
                      :probe (if (= :file (keyword (or (:type probe) :shell))) (str "file " (:path probe)) (str "$ " (:cmd probe)))
                      :seen (if (str/blank? seen) "(nothing)" (str seen (when (some? (:exit obs)) (str "  [exit " (:exit obs) "]"))))
                      :note (if (str/blank? (str note)) "" (str "\nYour note: " note))
                      :after (fill (if (false? once) (:again t) (:once t)) {:id id})})))

(defn add-watch!
  "A watch of session `session`: a `:cmd` run, or a `:path` looked at, every
  `:every` (\"30s\", default 30 s, at least 5 s) or on `:cron`, firing when
  `:when` holds (`{:op …}`, default `:changed`). `:once` (default true) stops
  it after it fires; it lives `:for` (default a day). The spec, or `{:error}`."
  [project {:keys [cmd path every cron title note once for session] :as opts} & [now]]
  (let [now (or now (System/currentTimeMillis))
        w (or (:when opts) {:op :changed})
        every-ms (when-not cron (max 5000 (or (delay-ms every) 30000)))
        parsed (when cron (parse-cron cron))
        life (or (delay-ms for) watch-ttl-ms)]
    (cond
      (and (str/blank? (str cmd)) (str/blank? (str path))) {:error "give :cmd (a command to run) or :path (a file to look at)"}
      (and cron (nil? parsed)) {:error (str "invalid :cron '" cron "'")}
      (and (= :matches (keyword (:op w))) (str/blank? (str (:re w)))) {:error ":matches needs :re"}
      :else
      (write-spec! project
                   (cond-> {:id (gen-id (or title cmd path) now) :kind :watch
                            :title (or (not-empty (str title)) (str (or cmd path)))
                            :probe (if (str/blank? (str cmd)) {:type :file :path (str path)} {:type :shell :cmd (str cmd)})
                            :when (update w :op #(keyword (or % :changed)))
                            :enabled true :once (not (false? once))
                            :next-fire (if parsed (next-fire-after parsed now) now)
                            :created now :expires-at (+ now life)
                            :last-run nil :last-status nil :last-observation nil}
                     every-ms (assoc :every every-ms)
                     cron (assoc :cron (str/trim cron))
                     (not (str/blank? (str note))) (assoc :note (str note))
                     session (assoc :session (str session)))))))

(defn run-watch!
  "Probe watch `id` once, now, and keep what it saw: when its predicate holds,
  the turn it fires with waits in `:pending` for the session's next idle
  moment (`ready-turn!`). Blocks while it probes; run it off the poll."
  [project id & [now-fn]]
  (when-let [s (read-spec project id)]
    (let [obs (probe! project (:probe s))
          now ((or now-fn #(System/currentTimeMillis)))
          fire? (and (nil? (:error obs)) (predicate-met? (:when s) (:last-observation s) obs))
          s' (cond-> (-> (read-spec project id)    ; as it is now: removed meanwhile is gone
                         (assoc :probing nil :last-run now
                                :last-status (cond (:error obs) :error fire? :fired :else :ok)
                                :last-observation (when-not (:error obs) (select-keys obs [:value :exit]))))
               fire? (assoc :pending (fired-text s obs) :last-fired now))]
      (when (read-spec project id) (write-spec! project s'))
      s')))

(defn- watch-due!
  "Start the probe of each due watch of `session` in the background, its next
  look set first, so the poll that found it never waits on it."
  [project session now run-bg]
  (doseq [s (specs project)
          :when (and (= :watch (:kind s)) (mine? s session) (:enabled s) (not (:pending s))
                     (or (nil? (:probing s)) (> (- now (:probing s)) (* 2 probe-timeout-ms)))
                     (number? (:next-fire s)) (<= (:next-fire s) now))]
    (write-spec! project (assoc s :probing now
                                :next-fire (if (:cron s)
                                             (some-> (parse-cron (:cron s)) (next-fire-after now))
                                             (+ now (:every s 30000)))))
    (run-bg #(run-watch! project (:id s)))))

(defn watches-since
  "The enabled watches of `session` in `project` set at or after `since`
  (epoch ms): what a turn armed to wake it."
  [project session since]
  (filterv #(and (= :watch (:kind %)) (:enabled %) (mine? % session) (>= (or (:created %) 0) (or since 0)))
           (specs project)))

;; --- as text ---------------------------------------------------------------------------------

(defn- when-text [ms now]
  (when ms
    (let [mins (quot (- ms now) 60000)]
      (cond (neg? mins) "due" (< mins 1) "in under a minute" (< mins 120) (str "in " mins " min")
            (< mins 2880) (str "in " (quot mins 60) " h " (mod mins 60) " min") :else (str "in " (quot mins 1440) " d")))))

(defn listing
  "Every job of `project` a line each — id, when it fires, how often, how long
  it lives, its prompt — and what a person must know: jobs fire only while
  their session is open, between turns."
  [project session & [now]]
  (let [now (or now (System/currentTimeMillis))
        ss (filter #(mine? % session) (specs project))]
    (str/join "\n"
              (concat
               (if (empty? ss)
                 ["No scheduled prompts or watches. /loop [interval] <prompt> sets one; schedule$add and schedule$watch too."]
                 (for [{:keys [id cron enabled next-fire expires-at prompt kind probe every last-status] :as sp} ss
                       :let [what (if (= :watch kind)
                                    (str "watch " (if (= :file (:type probe)) (str "file " (:path probe)) (str "$ " (:cmd probe)))
                                         " · when " (name (get-in sp [:when :op] :changed)))
                                    (str prompt))]]
                   (str "  " id "  " (if enabled (or (when-text next-fire now) "—") "off")
                        "  " (cond cron (str "cron " cron) every (str "every " (quot every 1000) " s") :else "once")
                        (when expires-at (str "  · expires " (when-text expires-at now)))
                        (when (and (= :watch kind) last-status) (str "  · last " (name last-status)))
                        "\n      " (subs what 0 (min 100 (count what))))))
               ["" "Jobs fire only while this session is open, between turns; a recurring one expires after 7 days. /schedule remove ID stops one."]))))

;; --- the commands: brainyard's schedule$* -------------------------------------------------------

(defn- project-of [ctx] (let [c (:config ctx)] (or (:project-dir c) (:working-dir c) ".")))
(defn- session-of [ctx] (some-> (:config ctx) :session-id str))
(def ^:private err [:map [:error :string]])
(def ^:private id-arg [:map [:id [:string {:desc "The job's id, as schedule$list gives it."}]]])

(defn- summary [s]
  (select-keys s [:id :kind :title :prompt :probe :when :every :once :cron :fire-at :enabled :next-fire
                  :expires-at :last-run :last-status :last-observation]))

(defn commands
  "`schedule$add|list|remove|enable|disable|run-now` — brainyard's names. A
  job fires as a turn of this session (`ready-turn!`), so only a session's
  top-level agent has them; `schedule$run-due`, brainyard's ticker pass, has
  no counterpart, as the front end's idle poll is that pass."
  [_config]
  [{:name "schedule$add" :kind :command
    :description (str "Set a prompt to run as a turn of this session later: on a cron (recurring), at a time, or after"
                      " a delay (once). It fires only while this session is open, between turns, as if typed;"
                      " a recurring one expires after 7 days. To pace a loop yourself, add a one-shot with :in"
                      " and the same prompt, once per iteration.")
    :input-schema [:map
                   [:prompt [:string {:desc "What to run, as the person would type it — a question, or a /command."}]]
                   [:cron {:optional true} [:string {:desc "Recurring: 5-field cron in local time, 'min hour day-of-month month day-of-week', e.g. '*/10 * * * *' or '0 9 * * 1-5'."}]]
                   [:in {:optional true} [:string {:desc "Once, after a delay: '90s', '20m', '2h'."}]]
                   [:at {:optional true} [:int {:desc "Once, at this epoch-ms."}]]
                   [:title {:optional true} [:string {:desc "A short label; it seeds the id."}]]]
    :output-schema [:or [:map [:id :string] [:next-fire [:maybe :int]] [:expires-at {:optional true} :int] [:note :string]] err]
    :effects #{:fs/write}
    :fn (fn [{:keys [prompt cron in at title]} ctx]
          (cond
            (nil? (:wakeups ctx))
            {:error "nothing here runs a later turn — this is a one-shot run; schedule from a session"}
            (not= "root" (or (:agent-instance-id (:config ctx)) "root"))
            {:error "schedule$add is for the top-level agent only: a subagent's turn ends before a job could fire"}
            :else
            (let [r (add! (project-of ctx) {:prompt prompt :cron cron :in in :at at :title title :session (session-of ctx)})]
              (if (:error r)
                r
                (cond-> {:id (:id r) :next-fire (:next-fire r)
                         :note "It runs as a turn of this session when due, if the session is idle then."}
                  (:expires-at r) (assoc :expires-at (:expires-at r)))))))}
   {:name "schedule$watch" :kind :command
    :description (str "Watch something in the background and be woken with a turn when it changes — Claude Code's"
                      " Monitor. Give :cmd (run every :every, its output and exit compared) or :path (a file's"
                      " modification time). It fires when :when holds: changed (the default; the first look is"
                      " the baseline), matches (:re against the output), zero-exit, nonzero-exit, increased,"
                      " threshold (:cmp :value). Use it to follow a long job — a task$run, a server, a CI run, a"
                      " log — instead of waiting with task$wait or polling in this turn: end the turn, and the"
                      " watch brings you back. :once (default true) stops it after it fires; it lives :for"
                      " (default 1 day). Fires only while this session is open, between turns.")
    :input-schema [:map
                   [:cmd {:optional true} [:string {:desc "A shell command to run each look, in the project, e.g. 'tail -n 5 build.log' or 'test -f out/done'."}]]
                   [:path {:optional true} [:string {:desc "Or a file whose modification time is watched."}]]
                   [:when {:optional true} [:enum {:desc "When it fires (default changed)."} "changed" "matches" "zero-exit" "nonzero-exit" "increased" "threshold"]]
                   [:re {:optional true} [:string {:desc "For matches: a regex against the command's output."}]]
                   [:cmp {:optional true} [:enum {:desc "For threshold: gt (default) ge lt le eq."} "gt" "ge" "lt" "le" "eq"]]
                   [:value {:optional true} [:string {:desc "For threshold: the number to compare with."}]]
                   [:every {:optional true} [:string {:desc "How often to look: '10s', '1m' (default 30s, at least 5s)."}]]
                   [:once {:optional true} [:boolean {:desc "Stop after it fires (default true); false keeps watching."}]]
                   [:for {:optional true} [:string {:desc "How long it lives: '30m', '2h' (default 1 day)."}]]
                   [:title {:optional true} [:string {:desc "A short label; it seeds the id."}]]
                   [:note {:optional true} [:string {:desc "A line to yourself, given back when it fires."}]]]
    :output-schema [:or [:map [:id :string] [:next-fire :int] [:expires-at :int] [:note :string]] err]
    :command-params [:cmd]
    :effects #{:process :dynamic}
    :fn (fn [{:keys [cmd path re cmp value every once for title note] w :when} ctx]
          (cond
            (nil? (:wakeups ctx))
            {:error "nothing here runs a later turn — this is a one-shot run; watch from a session"}
            (not= "root" (or (:agent-instance-id (:config ctx)) "root"))
            {:error "schedule$watch is for the top-level agent only: a subagent's turn ends before a watch could fire"}
            :else
            (let [r (add-watch! (project-of ctx)
                                {:cmd cmd :path path :every every :once once :for for :title title :note note
                                 :session (session-of ctx)
                                 :when (cond-> {:op (keyword (or w "changed"))}
                                         re (assoc :re re) cmp (assoc :cmp (keyword cmp)) value (assoc :value value))})]
              (if (:error r)
                r
                {:id (:id r) :next-fire (:next-fire r) :expires-at (:expires-at r)
                 :note (str "Watching. End this turn; you will be woken with a turn when it fires"
                            (when (:once r) ", once") ". schedule$remove " (:id r) " stops it.")}))))}
   {:name "schedule$list" :kind :command
    :description "This session's scheduled prompts and watches: id, cron, once or what it watches, next fire, expiry, last run."
    :input-schema [:map]
    :output-schema [:map [:schedules [:vector :any]]]
    :effects #{:fs/read}
    :fn (fn [_ ctx] {:schedules (mapv summary (filter #(mine? % (session-of ctx)) (specs (project-of ctx))))})}
   {:name "schedule$remove" :kind :command
    :description "Remove a scheduled prompt — how a loop is stopped."
    :input-schema id-arg :output-schema [:or [:map [:removed :string]] err] :effects #{:fs/write}
    :fn (fn [{:keys [id]} ctx] (if (remove! (project-of ctx) id) {:removed id} {:error (str "no schedule '" id "'")}))}
   {:name "schedule$enable" :kind :command
    :description "Enable a scheduled prompt, its next fire worked out again."
    :input-schema id-arg :output-schema [:or [:map [:id :string] [:enabled :boolean]] err] :effects #{:fs/write}
    :fn (fn [{:keys [id]} ctx] (let [r (set-enabled! (project-of ctx) id true)] (if (:error r) r {:id id :enabled true})))}
   {:name "schedule$disable" :kind :command
    :description "Disable a scheduled prompt; it is kept, and does not fire."
    :input-schema id-arg :output-schema [:or [:map [:id :string] [:enabled :boolean]] err] :effects #{:fs/write}
    :fn (fn [{:keys [id]} ctx] (let [r (set-enabled! (project-of ctx) id false)] (if (:error r) r {:id id :enabled false})))}
   {:name "schedule$run-now" :kind :command
    :description "Run a scheduled prompt at the session's next idle moment; its schedule after that is unchanged."
    :input-schema id-arg :output-schema [:or [:map [:id :string] [:queued :boolean]] err] :effects #{:fs/write}
    :fn (fn [{:keys [id]} ctx] (let [r (run-now! (project-of ctx) id)] (if (:error r) r {:id id :queued true})))}])
