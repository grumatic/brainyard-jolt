;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.malli-spike
  "The measurements behind the malli decision (byj-gly.196, RFC-030).

      jolt -A:dev:malli -m brainyard.dev.malli-spike [events.log]

  Tools declare a malli :input-schema since byj-gly.237 and carry no :params,
  so FIDELITY below finds nothing to convert: check out 0e183b9 to reproduce
  the numbers RFC-030 records.

  Tool args were checked then by `tools/check-args`: presence, and a coercion
  per `:params` type (then divergence `tool-params-not-malli`). This asks what
  malli would change, on evidence rather than on its README:

  1. LOAD — what requiring malli costs a process that has loaded the port.
  2. FIDELITY — every registered tool's `:params` converted to a malli
     `[:map …]` mechanically, and the tool calls models actually made (the raw
     `agent.llm/response` replies in the event log, before any check) judged
     by both. Agreement is the same verdict and, when both accept, the same
     coerced args. Every disagreement is printed.
  3. COST — per-call time of each, over that corpus.

  malli is loaded at run time, not in the ns form, so (1) can be timed in a
  process that already holds the port.

  Since byj-gly.197 `tools/check-args` IS malli (`brainyard.schema`), so (2)
  and (3) compare malli with itself here; `git checkout 0e183b9` for the plain
  check the decision measured against."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.json :as json]
            [brainyard.agent :as agent]
            [brainyard.tools :as tools]))

(defn- mf [sym] @(requiring-resolve sym))

(defn- ms [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 10000.0)) 100.0))

;; --- 1. load -------------------------------------------------------------------

(defn load-malli
  "Require malli's core, error and transform namespaces, then json-schema;
  the milliseconds each step took."
  []
  (let [t0 (System/nanoTime)
        _ (require 'malli.core 'malli.error 'malli.transform)
        core (ms t0)
        t1 (System/nanoTime)
        _ (require 'malli.json-schema)]
    {:core+error+transform-ms core :json-schema-ms (ms t1)}))

;; --- 2. conversion ---------------------------------------------------------------

(defn params->schema
  "A tool's `:params` as the malli `[:map …]` it would be written as: the same
  keys, optional where it was, each type the nearest malli type, its `:desc`
  as `:description`. Open, as `check-args` is: a key no param names passes."
  [params]
  (into [:map]
        (for [[k {:keys [type optional desc]}] params]
          (let [t (case type
                    "integer" :int
                    "boolean" :boolean
                    "string" :string
                    "array" [:vector :any]
                    "object" [:map-of :any :any]
                    "number" number?
                    :any)
                props (cond-> {} optional (assoc :optional true))
                t (if (and desc (keyword? t)) [t {:description desc}] t)]
            (if (seq props) [k props t] [k t])))))

(defn tool-transformer
  "malli's string-transformer, plus the three things `tools/coerce` does that
  it does not — each a rule the port learned from a model:
  - a non-string where a string is wanted is printed (`pr-str`);
  - a lone string where an array is wanted is wrapped;
  - an object that arrived as a JSON string is parsed;
  - an integer that arrived as a whole float is taken (a JSON 3.0)."
  []
  (let [transformer (mf 'malli.transform/transformer)
        string-transformer (mf 'malli.transform/string-transformer)]
    (transformer
     (string-transformer)
     {:name :tool
      :decoders {:string (fn [v] (if (or (nil? v) (string? v)) v (pr-str v)))
                 :vector (fn [v] (if (string? v) [v] v))
                 :map-of (fn [v] (if (string? v)
                                   (let [m (try (json/read-str v) (catch Throwable _ nil))] (if (map? m) m v))
                                   v))
                 :int (fn [v] (if (and (number? v) (not (integer? v))) (long v) v))}})))

(defn malli-check
  "`check-args`'s contract through malli: {:args decoded} or {:problems […]}.
  `validator` is compiled once per tool, as the port would keep it."
  [schema decoder validator args]
  (let [v (decoder args)]
    (if (validator v)
      {:args v}
      {:problems (for [[k msgs] ((mf 'malli.error/humanize) ((mf 'malli.core/explain) schema v))
                       msg (if (sequential? msgs) msgs [msgs])]
                   (str "`" (name k) "` " msg))})))

;; --- the corpus --------------------------------------------------------------------

(defn recorded-calls
  "Every tool call in the event log's raw model replies, as the model wrote
  it, deduplicated."
  [path]
  (with-open [r (io/reader path)]
    (->> (line-seq r)
         (filter #(str/includes? % ":event :agent.llm/response"))
         (keep #(try (edn/read-string %) (catch Throwable _ nil)))
         (keep #(get-in % [:data :content]))
         (keep #(try (json/parse-json-response %) (catch Throwable _ nil)))
         (mapcat #(when (sequential? (:tool-calls %)) (:tool-calls %)))
         (keep (fn [{:keys [tool-name tool-args]}]
                 (when (string? tool-name) [tool-name (or tool-args {})])))
         distinct
         vec)))

(defn trajectory-calls
  "Every tool call in the project's session records (RFC-025) — the
  trajectory and the predictor outputs, which hold the calls as the model
  wrote them — deduplicated."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(#{"trajectory.edn" "predictions.edn"} (.getName %)))
       (keep #(try (edn/read-string (str "[" (slurp %) "]")) (catch Throwable _ nil)))
       (mapcat #(tree-seq coll? seq %))
       (keep (fn [x] (when (and (map? x) (string? (:tool-name x)) (map? (:tool-args x)))
                       [(:tool-name x) (:tool-args x)])))
       distinct
       vec))

(def synthetic
  "What a model sends that the port has a rule for, in case the log has none."
  [["read-file" {:path "a" :offset "145" :limit 200}]
   ["read-file" {:path "a" :offset 145.0}]
   ["read-file" {:path "a" :offset "ten"}]
   ["read-file" {:offset 1}]
   ["read-file" {:path 42}]
   ["list-tools" {:grouped "false"}]
   ["list-tools" {:grouped "no"}]
   ["grep" {:pattern "x" :include-exts "clj"}]
   ["grep" {:pattern "x" :include-exts ["clj" "edn"]}]
   ["grep" {:pattern "x" :max-results "30"}]
   ["bash" {:command "ls" :extra "ignored"}]])

;; --- 2 and 3 ---------------------------------------------------------------------

(defn- verdict [{:keys [problems args]}] (if (seq problems) [:reject] [:accept args]))

(defn compare-calls
  [registry calls]
  (let [check @#'tools/check-args
        tt (tool-transformer)
        compiled (into {} (for [[n t] registry]
                            (let [s ((mf 'malli.core/schema) (params->schema (:params t)))]
                              [n {:schema s :decoder ((mf 'malli.core/decoder) s tt)
                                  :validator ((mf 'malli.core/validator) s)}])))
        rows (for [[n args] calls
                   :let [t (get registry n)]
                   :when t
                   :let [args (tools/normalize-args args)
                         {:keys [schema decoder validator]} (compiled n)
                         old (check t args)
                         new (malli-check schema decoder validator args)]]
               {:tool n :args args :old old :new new :same? (= (verdict old) (verdict new))})]
    {:rows (vec rows)
     :unknown (vec (distinct (for [[n] calls :when (not (get registry n))] n)))
     :timing (let [reps 50
                   pairs (vec (for [{:keys [tool args]} rows] [(get registry tool) (compiled tool) args]))
                   t0 (System/nanoTime)
                   _ (dotimes [_ reps] (doseq [[t _ a] pairs] (check t a)))
                   old-us (/ (- (System/nanoTime) t0) 1000.0 reps (max 1 (count pairs)))
                   t1 (System/nanoTime)
                   _ (dotimes [_ reps] (doseq [[_ {:keys [schema decoder validator]} a] pairs] (malli-check schema decoder validator a)))
                   new-us (/ (- (System/nanoTime) t1) 1000.0 reps (max 1 (count pairs)))]
               {:old-us-per-call old-us :malli-us-per-call new-us})}))

(defn -main [& [log]]
  (require 'jolt.time) ; malli.transform needs java.time, as data.json does
  (let [load (load-malli)
        ctx (agent/make-ctx {:config {:working-dir "." :project-dir (str (System/getProperty "java.io.tmpdir") "/malli-spike")
                                      :event-log? false}})
        registry @(:tools ctx)
        convertible (count (filter #(try ((mf 'malli.core/schema) (params->schema (:params %))) true
                                         (catch Throwable _ false))
                                   (vals registry)))
        recorded (vec (distinct (concat (recorded-calls (or log ".brainyard/logs/events.log"))
                                        (trajectory-calls ".brainyard/sessions"))))
        {:keys [rows unknown timing]} (compare-calls registry (concat recorded synthetic))
        differ (remove :same? rows)]
    (println "== load (ms, in a process that has loaded the port)")
    (println " " load)
    (println "== conversion")
    (println "  tools registered:" (count registry) "· :params converted to a malli schema:" convertible)
    (println "== fidelity")
    (println "  recorded calls:" (count recorded) "· synthetic:" (count synthetic)
             "· judged:" (count rows) "· for tools not registered here:" (count unknown) unknown)
    (println "  tools covered:" (count (distinct (map :tool rows)))
             "· rejected by check-args:" (count (filter #(seq (:problems (:old %))) rows)))
    (println "  same verdict and args:" (count (filter :same? rows)) "· different:" (count differ))
    (doseq [{:keys [tool args old new]} differ]
      (println "  -" tool (pr-str args))
      (println "      check-args:" (pr-str (or (seq (:problems old)) (:args old))))
      (println "      malli:     " (pr-str (or (seq (:problems new)) (:args new)))))
    (println "== messages for the same bad call")
    (let [bad (first (filter #(and (seq (:problems (:old %))) (seq (:problems (:new %)))) rows))]
      (when bad
        (println "  " (:tool bad) (pr-str (:args bad)))
        (println "   check-args:" (vec (:problems (:old bad))))
        (println "   malli:     " (vec (:problems (:new bad))))))
    (println "== cost (µs per call, over the judged corpus)")
    (println " " timing)
    (shutdown-agents)))
