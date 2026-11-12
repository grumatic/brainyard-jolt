;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.malli-main
  "brainyard.main with malli loaded, for the binary measurement of the malli
  decision (byj-gly.196): `jolt -A:malli build -m brainyard.dev.malli-main`.
  Its own path, which only the :malli alias adds, so dev/cold-load.sh (which
  loads with -A:dev) never meets a namespace needing malli."
  (:require [jolt.time]
            [malli.core]
            [malli.error]
            [malli.transform]
            [brainyard.main :as main]))

(defn -main [& args] (apply main/-main args))
