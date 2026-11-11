(ns brainyard.clock
  "The local wall-clock time, for what a turn tells the model and records.

  jolt's java.time knows only UTC (provenance P-10), so the local time was
  asked of the OS with `date` — twice a turn, once for the environment zone
  and once for the turn's end, 514 processes in one run of the suite
  (byj-gly.382). The OS is asked for its UTC offset instead, at most every
  `offset-ttl-ms`, and the date is worked out here from the epoch: the offset
  is the one thing only the OS knows, and it changes twice a year."
  (:require [clojure.string :as str]
            [brainyard.effect :as fx]))

(def offset-ttl-ms
  "How long an offset is trusted before the OS is asked again: short enough
  that a daylight-saving change shows within minutes, long enough that a turn
  never waits on a process for it."
  (* 10 60 1000))

(defonce ^:private offset-cache (atom nil))

(defn- parse-offset
  "`+0900` → 32400 seconds; nil for anything else."
  [s]
  (when-let [[_ sign hh mm] (re-matches #"([+-])(\d{2})(\d{2})" (str/trim (str s)))]
    (* (if (= "-" sign) -1 1) (+ (* 3600 (parse-long hh)) (* 60 (parse-long mm))))))

(defn offset-seconds
  "This machine's UTC offset in seconds, asked of the OS at most every
  `offset-ttl-ms`; nil when it cannot be asked."
  []
  (let [now (System/currentTimeMillis)
        {:keys [at offset]} @offset-cache]
    (if (and at (< (- now at) offset-ttl-ms))
      offset
      (let [offset (try (parse-offset (:out (fx/sh {:out :string :err :string} "date" "+%z")))
                        (catch Throwable _ nil))]
        (reset! offset-cache {:at now :offset offset})
        offset))))

(def ^:private weekdays ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(defn civil
  "The calendar fields of `epoch-ms` at `offset` seconds east of UTC:
  `{:year :month :day :hour :minute :second :weekday}`. Howard Hinnant's
  days-to-civil, proleptic Gregorian."
  [epoch-ms offset]
  (let [secs (+ (Math/floorDiv (long epoch-ms) 1000) offset)
        days (Math/floorDiv secs 86400)
        sod (Math/floorMod secs 86400)
        z (+ days 719468)
        era (Math/floorDiv z 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (if (< mp 10) (+ mp 3) (- mp 9))
        y (+ yoe (* era 400) (if (<= m 2) 1 0))]
    {:year y :month m :day d
     :hour (quot sod 3600) :minute (quot (mod sod 3600) 60) :second (mod sod 60)
     ;; 1970-01-01 was a Thursday
     :weekday (nth weekdays (Math/floorMod (+ days 3) 7))}))

(defn- two [n] (if (< n 10) (str "0" n) (str n)))

(defn stamp
  "`2026-09-24T18:23:05+0900` for `epoch-ms` at `offset` seconds, as
  `date +%Y-%m-%dT%H:%M:%S%z` prints it; with `weekday?`, ` (Thursday)` after."
  [epoch-ms offset weekday?]
  (let [{:keys [year month day hour minute second weekday]} (civil epoch-ms offset)
        a (Math/abs (long offset))]
    (str year "-" (two month) "-" (two day) "T" (two hour) ":" (two minute) ":" (two second)
         (if (neg? offset) "-" "+") (two (quot a 3600)) (two (quot (mod a 3600) 60))
         (when weekday? (str " (" weekday ")")))))

(defn local-now
  "Now, as `date +%Y-%m-%dT%H:%M:%S%z` prints it, ` (Weekday)` after it when
  `weekday?`; nil when the OS cannot say its offset: a turn is not worth
  failing over a clock."
  [weekday?]
  (when-let [off (offset-seconds)]
    (stamp (System/currentTimeMillis) off weekday?)))
