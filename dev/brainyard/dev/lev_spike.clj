(ns brainyard.dev.lev-spike
  "byj-gly.113 — the one measurement the System One path rests on.

  The question is NOT whether lev is accurate. It is whether lev's calibrated
  confidence separates its right answers from its wrong ones at the threshold a
  gate would use. RFC-005's proposed `:strategy :system-one` is only worth
  building if a cheap encoder answer can be TRUSTED when it is confident and
  escalated when it is not; if confidence is uninformative, the `:fallback` in
  byj-gly.116 either escalates everything (no saving) or auto-answers its own
  errors (worse than no fast path), and the whole path closes `no`.

  The set is `lev_routing_set.edn`: paragraphs from this repo's own RFCs,
  labelled by the subsystem of the RFC they came from. The ground truth is
  objective — nobody hand-labelled them, so nobody biased them — and the task is
  topic classification, which is the shape lev's bench says the encoder is good
  at (AG News 97.5%) rather than the shape it is bad at (SST-5 27.5%).

  What this does NOT measure, and must not be read as measuring: work-tier
  selection. There is no tier ground truth in this repo (`grep work-tier` over
  every trajectory returns nothing), so tier accuracy remains unmeasured and
  byj-gly.113 cannot close on this evidence alone.

  Needs a lev server:  cd ../lev && jolt -M:serve
  Then:                jolt -A:dev -e \"(require 'brainyard.dev.lev-spike) (brainyard.dev.lev-spike/main)\""
  (:require [clojure.edn :as edn]
            [brainyard.json :as json]
            [jolt.http-client :as http]))

(def base-url (or (System/getenv "LEV_BASE_URL") "http://127.0.0.1:8080"))

(def labels
  "The criteria, in a fixed order: option order is model input, so it stays an
  array-map and never becomes a hash-map."
  (array-map
   "loop"        "the agent's control flow: behavior trees, the ReAct turn, the session loop"
   "prompt"      "what the model is asked: signatures, predictors, prompt caching"
   "provider"    "talking to a model endpoint and surviving its failures"
   "tools"       "tools the agent calls, their discovery, schemas and skills"
   "effects"     "concurrency, cancellation and the effect substrate"
   "policy"      "permission, sandboxing, hooks and layered configuration"
   "context"     "context budget and compaction"
   "persistence" "what outlives a turn: sessions, memory, trajectories, the event log"
   "agents"      "subagents, dispatch, background tasks and work that outlives a turn"
   "ui"          "the terminal interface and the boundary between it and the kernel"))

(defn- read-set []
  (edn/read-string (slurp "dev/brainyard/dev/lev_routing_set.edn")))

(defn ask
  "One paragraph through POST /v1/systemone. Returns the answer map for the
  single question, or throws with lev's own message so a 422 names the field it
  rejected rather than arriving as a parse failure."
  [text]
  (let [body {:state {:text text}
              :questions {:subsystem (array-map
                                      "type" "choice"
                                      "instructions" "Which subsystem of an agent runtime does this text describe?"
                                      "criteria" labels)}}
        resp (http/post (str base-url "/v1/systemone")
                        {:headers {"Content-Type" "application/json"}
                         :body (json/write-str body)
                         :socket-timeout 120000
                         :conn-timeout 15000
                         :throw-exceptions false})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "lev " (:status resp) ": " (:body resp))
                      {:status (:status resp)})))
    (get-in (json/read-str (:body resp)) [:answers :subsystem])))

(defn run
  "Every row, one call each. Returns [{:label :predicted :confidence :correct?}]."
  ([] (run (read-set)))
  ([rows]
   (vec (for [[i r] (map-indexed vector rows)]
          (let [a (ask (:text r))
                predicted (get a :choice)
                conf (double (get a :confidence 0.0))]
            (when (zero? (mod (inc i) 20)) (println "  ..." (inc i) "of" (count rows)))
            {:label (:label r) :rfc (:rfc r)
             :predicted predicted :confidence conf
             :correct? (= predicted (:label r))})))))

(defn- pct [x] (format "%.1f%%" (* 100.0 (double x))))
(defn- mean [xs] (if (seq xs) (/ (reduce + xs) (count xs)) 0.0))

(defn report
  "Accuracy, then the thing that actually decides the bead: whether confidence
  separates right from wrong, and what a gate at each threshold would buy."
  [results]
  (let [n (count results)
        correct (filter :correct? results)
        wrong (remove :correct? results)
        acc (/ (count correct) (double n))]
    (println)
    (println "n =" n "  accuracy =" (pct acc) "  (random =" (pct (/ 1.0 (count labels))) ")")
    (println)
    (println "SEPARATION — the question the bead turns on")
    (println "  mean confidence when RIGHT :" (format "%.3f" (mean (map :confidence correct))))
    (println "  mean confidence when WRONG :" (format "%.3f" (mean (map :confidence wrong))))
    (println "  gap                        :" (format "%.3f" (- (mean (map :confidence correct))
                                                                (mean (map :confidence wrong)))))
    (println)
    (println "GATE — what a :fallback at each threshold would do")
    (println (format "  %-8s %-10s %-12s %-12s %s" "thresh" "auto" "auto acc" "escal acc" "errors caught"))
    (doseq [t [0.5 0.6 0.7 0.8 0.9 0.95]]
      (let [auto (filter #(>= (:confidence %) t) results)
            esc (remove #(>= (:confidence %) t) results)
            auto-acc (if (seq auto) (/ (count (filter :correct? auto)) (double (count auto))) 0.0)
            esc-acc (if (seq esc) (/ (count (filter :correct? esc)) (double (count esc))) 0.0)
            caught (if (seq wrong)
                     (/ (count (remove :correct? esc)) (double (count wrong)))
                     0.0)]
        (println (format "  %-8s %-10s %-12s %-12s %s"
                         t
                         (str (count auto) " (" (pct (/ (count auto) (double n))) ")")
                         (pct auto-acc) (pct esc-acc) (pct caught)))))
    (println)
    (println "Read it like this: the path is worth building only if some threshold")
    (println "auto-answers a WORTHWHILE share at accuracy clearly above the base rate,")
    (println "while catching most errors in the escalated slice. If 'auto acc' tracks")
    (println "overall accuracy no matter the threshold, confidence is uninformative")
    (println "and byj-gly.113 closes no.")
    {:n n :accuracy acc
     :mean-conf-right (mean (map :confidence correct))
     :mean-conf-wrong (mean (map :confidence wrong))}))

(defn main []
  (println "lev at" base-url)
  (let [rows (read-set)]
    (println "asking" (count rows) "paragraphs ...")
    (report (run rows))))
