;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.which
  "Where an executable is on a PATH, running nothing. Its own namespace so
  that what needs it — `brainyard.init`'s tool check, `brainyard.deps`'s
  provides — need not require one another (byj-g6o)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn on-path
  "The absolute path of executable `cmd` on `path` (default this process's
  PATH), or nil: a scan of the directories, running nothing."
  ([cmd] (on-path cmd (System/getenv "PATH")))
  ([cmd path]
   (some (fn [d]
           (let [f (io/file d cmd)]
             (when (and (not (str/blank? d)) (.isFile f) (.canExecute f)) (.getAbsolutePath f))))
         (str/split (str path) #":"))))
