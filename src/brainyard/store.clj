(ns brainyard.store
  "`store$*` — state that outlives one call, for a Tool Lisp body and the
  model alike (byj-gly.261, Tool Lisp tier D). A body has no atoms: what it
  keeps between calls it puts here, through tools whose effects are declared,
  so the gates see a write and `tool-distill/prove` knows a tool that reads
  it cannot be proved by replay.

  brainyard has no such tool; its bodies ran in SCI, where `atom` was there to
  reach for (divergence `tool-lisp-reach`).

  One EDN map per project, `.brainyard/store.edn`: a string key to any EDN
  value. Written whole and atomically, one writer at a time in a process — a
  body's `pmap` may put from several threads at once."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [brainyard.config :as config]))

(def max-chars
  "The most characters the store may take once printed. A put past it is
  refused: the store is for what a tool keeps between calls, not for data."
  1000000)

(def max-key-chars 200)

(defn store-file [config]
  (io/file (config/canonical-dir (or (:project-dir config) (:working-dir config) "."))
           ".brainyard" "store.edn"))

(def ^:private lock (Object.))

(defn read-store
  "The store as a map; empty when there is none yet."
  [config]
  (let [f (store-file config)]
    (if (.exists f) (or (edn/read-string (slurp f)) {}) {})))

(defn- write-store!
  "Atomically: a sibling temp file, then a rename, as `persist` writes a
  session."
  [config m]
  (let [f (store-file config)
        tmp (io/file (.getParentFile f) (str (.getName f) "." (System/currentTimeMillis) ".tmp"))]
    (.mkdirs (.getParentFile f))
    (try
      (spit tmp (with-out-str (clojure.pprint/pprint m)))
      (.renameTo tmp f)
      (finally (.delete tmp)))))

(defn- key-problem [k]
  (cond (not (string? k)) "key must be a string"
        (str/blank? k) "key is required"
        (> (count k) max-key-chars) (str "key is longer than " max-key-chars " characters")))

(defn- edn-value?
  "Does `v` print as EDN that reads back as itself? A fn, an exception or a
  host object does not, and would leave the store unreadable."
  [v]
  (try (= v (edn/read-string (pr-str v))) (catch Throwable _ false)))

(defn put!
  "Keep `v` under `k`, or forget `k` when `v` is nil. `{:key :stored true}`,
  `{:key :removed true}` or `{:error …}`."
  [config k v]
  (or (some->> (key-problem k) (hash-map :error))
      (when-not (edn-value? v)
        {:error "value must be EDN data: maps, vectors, sets, strings, numbers, keywords, booleans"})
      (locking lock
        (let [m (read-store config)
              m2 (if (nil? v) (dissoc m k) (assoc m k v))]
          (if (> (count (pr-str m2)) max-chars)
            {:error (str "the store would pass " max-chars " characters; remove what is no longer needed")}
            (do (write-store! config m2)
                (if (nil? v) {:key k :removed true} {:key k :stored true})))))))

(def ^:private error-schema [:map [:error :string]])

(defn commands
  "`store$get`, `store$put` and `store$keys`."
  [_config]
  [{:name "store$get" :kind :command
    :description (str "Read what was kept under a key with store$put — by this call, an earlier one, or an"
                      " earlier session of this project. A Tool Lisp body's state between calls.")
    :input-schema [:map [:key [:string {:desc "The key it was put under."}]]]
    :output-schema [:or [:map [:key :string] [:found :boolean] [:value {:optional true} :any]] error-schema]
    :effects #{:fs/read :store/read}
    :fn (fn [{:keys [key]} ctx]
          (or (some->> (key-problem key) (hash-map :error))
              (let [m (read-store (:config ctx))]
                (cond-> {:key key :found (contains? m key)}
                  (contains? m key) (assoc :value (get m key))))))}

   {:name "store$put" :kind :command
    :description (str "Keep a value under a key, for a later call or session of this project to read with"
                      " store$get; with no value, forget the key. EDN data only. A Tool Lisp body has no"
                      " atoms: this is where it keeps state.")
    :input-schema [:map [:key [:string {:desc "A name for it, e.g. last-scan."}]]
                   [:value {:optional true} [:any {:desc "Any data: a map, a vector, a string, a number. Omit to forget the key."}]]]
    :output-schema [:or [:map [:key :string] [:stored {:optional true} :boolean] [:removed {:optional true} :boolean]]
                    error-schema]
    :effects #{:fs/write :store/write}
    :fn (fn [{:keys [key value]} ctx] (put! (:config ctx) key value))}

   {:name "store$keys" :kind :command
    :description "The keys store$put has kept, sorted; with a prefix, only those starting so."
    :input-schema [:map [:prefix {:optional true} [:string {:desc "Only keys starting with this."}]]]
    :output-schema [:map [:keys [:vector :string]]]
    :effects #{:fs/read :store/read}
    :fn (fn [{:keys [prefix]} ctx]
          {:keys (vec (sort (filter #(str/starts-with? % (str prefix)) (keys (read-store (:config ctx))))))})}])
