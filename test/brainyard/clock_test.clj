(ns brainyard.clock-test
  "The local clock (byj-gly.382): the date worked out from the epoch, the
  offset the only thing asked of the OS."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.clock :as clock]
            [brainyard.effect :as fx]))

(deftest the-date-is-worked-out-as-date-prints-it
  (testing "fixed instants, read off `date -r` at those offsets"
    (is (= "1970-01-01T00:00:00+0000 (Thursday)" (clock/stamp 0 0 true)))
    (is (= "2024-02-29T12:00:00+0000 (Thursday)" (clock/stamp 1709208000000 0 true)) "a leap day")
    (is (= "1969-12-31T18:59:59-0500 (Wednesday)" (clock/stamp -1000 -18000 true)) "before the epoch, west of UTC")
    (is (= "2026-09-24T18:23:05+0900" (clock/stamp 1790241785000 32400 false)) "no weekday when not asked")
    (is (= "2000-03-01T00:00:00+0530 (Wednesday)" (clock/stamp 951849000000 19800 true)) "a half-hour offset"))
  (testing "a day later is the next day and the next weekday, across ten thousand days"
    (doseq [d (range 0 10000 37)
            :let [a (clock/civil (* d 86400000) 0)
                  b (clock/civil (* (inc d) 86400000) 0)]]
      (is (not= (:weekday a) (:weekday b)))
      (is (or (= (inc (:day a)) (:day b)) (= 1 (:day b))) (str d))))
  (testing "now, as the OS says it, to the minute"
    (let [os (str/trim (:out (fx/sh {:out :string :err :string} "date" "+%Y-%m-%dT%H:%M%z (%A)")))
          ours (clock/local-now true)
          strip-seconds #(str/replace % #":\d{2}([+-]\d{4})" "$1")]
      (is (= os (strip-seconds ours))))))
