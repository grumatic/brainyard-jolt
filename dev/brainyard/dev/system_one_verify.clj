(ns brainyard.dev.system-one-verify
  "Pre-production verification for a System One decision (byj-gly.113).

  lev's own discipline is that a decision is PREPARED and VERIFIED before it
  serves traffic: `workflows/*.clj` defines the questions and the state fn,
  `golden/*.edn` pins the exact tokenized request, and the suite checks one
  against the other. Nothing in this repo had that, so the investigation that
  produced these gates was a pile of one-off spikes whose wording nobody could
  reproduce. This is the harness that would have caught each failure it now
  enforces.

  Mechanism only (RFC-001). It knows nothing about permissions, commands or
  tiers; every decision is data in `system_one_decisions.edn`, and the labelled
  cases are data too. Adding a decision needs no change here.

  WHAT IT CHECKS, and why each one exists rather than being a round number:

    fit          no case may come back with `truncated`. A cut state answers a
                 question the model could not fully read, and lev reports it for
                 exactly this reason. The first spike never looked.
    routing      the request must NAME its checkpoint. With none, lev routes by
                 content, and a shell command is not English prose — one run died
                 on a 503 for `multilingual`, a checkpoint nobody had prepared.
    accuracy     against the decision's own floor, not a shared one.
    separation   mean confidence when RIGHT minus when WRONG, which must be
                 POSITIVE. This is the gate that matters most and the one
                 accuracy cannot see: `needs_approval` scored 51.6% with
                 confidence 0.834 when right and 0.843 when WRONG. Inverted
                 confidence behind a `:fallback` auto-answers its own errors,
                 which is worse than having no fast path.
    coverage     the share at or above the operating point. A gate nothing
                 clears saves nothing — after recalibration one decision cleared
                 0 of 88 at 0.8.
    gate-acc     accuracy on the slice that would be auto-answered. The only
                 accuracy number that describes production.
    never-miss   for an asymmetric decision, a gold value that must never be
                 wrong above the gate. A destructive command called safe is a
                 deleted file; a safe one called destructive is a prompt.
    drift        the request's fingerprint against the recorded one. Reword an
                 instruction and every number above silently stops describing
                 what ships.

  Run:  cd ../lev && jolt -M:serve --data data --calibration calibration.edn
        jolt -A:dev -e \"(require 'brainyard.dev.system-one-verify) (brainyard.dev.system-one-verify/main)\""
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [brainyard.json :as json]
            [jolt.http-client :as http]))

(def base-url (or (System/getenv "LEV_BASE_URL") "http://127.0.0.1:8080"))
(def decisions-file "dev/brainyard/dev/system_one_decisions.edn")
(def cases-file "dev/brainyard/dev/lev_permission_set.edn")
(def golden-file "dev/brainyard/dev/system_one_golden.edn")

(defn- read-edn [f] (edn/read-string (slurp f)))

;; --- the request, built from data ------------------------------------------

(defn state-for
  "Exactly the keys the decision declares, in declared order, and nothing else —
  the measured reason this is per-decision and not per-spec is in the bead: a
  rubric added to a perceptual question cost it 27 points."
  [{:keys [state-keys policy]} case-row]
  (let [ctx (:ctx case-row)
        v (fn [k] (case k
                    :policy policy
                    :command (:command case-row)
                    (get ctx k)))]
    (reduce (fn [m k]
              (let [x (v k)]
                (if (some? x) (assoc m (name k) x) m)))
            (array-map) state-keys)))

(defn request-for [{:keys [model question] :as d} case-row]
  (let [{:keys [name type instructions criteria]} question]
    {:state (state-for d case-row)
     :questions {name (cond-> (array-map "type" type "instructions" instructions)
                        criteria (assoc "criteria" criteria))}
     :model model}))

(defn fingerprint
  "A stable digest of what this decision SENDS, minus the per-case state values.
  Changing an instruction, an option, the key list or the model moves it; running
  on different cases does not."
  [{:keys [id model question state-keys policy]}]
  (str (hash [id model question (vec state-keys) policy])))

;; --- running ----------------------------------------------------------------

(defn- post [body]
  (let [resp (http/post (str base-url "/v1/systemone")
                        {:headers {"Content-Type" "application/json"}
                         :body (json/write-str body)
                         :socket-timeout 120000 :conn-timeout 15000
                         :throw-exceptions false})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "lev " (:status resp) ": " (:body resp)) {:status (:status resp)})))
    (json/read-str (:body resp))))

(defn run-decision [{:keys [question label] :as d} cases]
  (let [qk (keyword (:name question))]
    (vec (for [c cases]
           (let [resp (post (request-for d c))
                 a (get-in resp [:answers qk])
                 pred (if (= "noul" (:type question))
                        (>= (double (get a :noul 0.0)) 0.5)
                        (get a :choice))]
             {:command (:command c) :borderline (boolean (:borderline c))
              :truncated (some? (:truncated resp))
              :gold (get c label) :pred pred
              :conf (double (get a :confidence 0.0))})))))

;; --- the gates --------------------------------------------------------------

(defn- mean [xs] (if (seq xs) (/ (reduce + xs) (double (count xs))) 0.0))
(defn- pct [x] (format "%5.1f%%" (* 100.0 (double x))))

(defn evaluate [{:keys [gate requires model] :as d} results golden]
  (let [hit? #(= (:gold %) (:pred %))
        n (count results)
        auto (filter #(>= (:conf %) gate) results)
        acc (/ (count (filter hit? results)) (double n))
        sep (- (mean (map :conf (filter hit? results)))
               (mean (map :conf (remove hit? results))))
        cov (/ (count auto) (double n))
        gacc (if (seq auto) (/ (count (filter hit? auto)) (double (count auto))) 0.0)
        nm (:never-miss requires)
        missed (when (some? nm)
                 (filter #(and (= nm (:gold %)) (not= nm (:pred %))) auto))
        fp (fingerprint d)
        recorded (get golden (:id d))]
    {:metrics {:n n :accuracy acc :separation sep :coverage cov :gate-accuracy gacc}
     :fingerprint fp
     :checks
     (cond-> [[:fit (not-any? :truncated results) "no state was truncated"]
              [:routing (some? model) "the request names its checkpoint"]
              [:accuracy (>= acc (:accuracy requires))
               (format "%s >= %s" (pct acc) (pct (:accuracy requires)))]
              [:separation (>= sep (:separation requires))
               (format "%+.3f >= %+.3f  (confidence must be higher when RIGHT)"
                       sep (double (:separation requires)))]
              [:coverage (>= cov (:coverage requires))
               (format "%s >= %s at gate %.2f" (pct cov) (pct (:coverage requires)) (double gate))]
              [:gate-accuracy (>= gacc (:gate-accuracy requires))
               (format "%s >= %s on the %d auto-answered" (pct gacc) (pct (:gate-accuracy requires)) (count auto))]]
       (some? nm) (conj [:never-miss (empty? missed)
                         (if (empty? missed)
                           (format "no %s called %s above the gate" (pr-str nm) (pr-str (not nm)))
                           (format "%d MISSED: %s" (count missed)
                                   (str/join "; " (map :command (take 3 missed)))))])
       true (conj [:drift (or (nil? recorded) (= recorded fp))
                   (if (nil? recorded)
                     (str "no golden recorded yet (" fp ") — record it to pin this wording")
                     (if (= recorded fp) (str "matches golden " fp)
                         (str "CHANGED: golden " recorded " but now " fp)))]))}))

;; --- reporting --------------------------------------------------------------

(defn verify
  ([] (verify (read-edn decisions-file) (read-edn cases-file)))
  ([decisions cases]
   (let [golden (try (read-edn golden-file) (catch Throwable _ {}))]
     (println "System One pre-production verification —" (count decisions) "decision(s),"
              (count cases) "labelled cases, lev at" base-url)
     (doall
      (for [d decisions]
        (let [results (run-decision d cases)
              {:keys [metrics checks fingerprint]} (evaluate d results golden)
              failed (remove second checks)
              pass? (empty? failed)
              expected-fail? (:expect-fail d)]
          (println)
          (println "──" (:id d) (if expected-fail? "  [NEGATIVE CONTROL — expected to fail]" ""))
          (println "  " (:description d))
          (println (format "   n=%d  acc=%s  sep=%+.3f  cov=%s  gate-acc=%s"
                           (:n metrics) (pct (:accuracy metrics)) (:separation metrics)
                           (pct (:coverage metrics)) (pct (:gate-accuracy metrics))))
          (doseq [[k ok why] checks]
            (println (format "   %-14s %s  %s" (name k) (if ok "PASS" "FAIL") why)))
          (println (format "   => %s%s"
                           (if pass? "READY" "NOT READY")
                           (cond
                             (and expected-fail? pass?) "  <-- the control PASSED; the harness is not catching what it should"
                             (and expected-fail? (not pass?)) "  (as expected — the harness works)"
                             :else "")))
          {:id (:id d) :pass pass? :expect-fail expected-fail?
           :fingerprint fingerprint :metrics metrics}))))))

(defn record-golden
  "Pin the current wording of every decision. Run deliberately, after a change
  you MEANT to make — never to silence a drift failure."
  []
  (let [ds (read-edn decisions-file)
        m (into (sorted-map) (map (juxt :id fingerprint) ds))]
    (spit golden-file (str ";; Fingerprints of the decisions as verified. A drift FAIL means the\n"
                           ";; wording changed and the numbers no longer describe what ships:\n"
                           ";; re-verify, then re-record. Do not record to make a failure go away.\n"
                           (pr-str m) "\n"))
    (println "recorded" (count m) "fingerprints to" golden-file)
    m))

(defn main []
  (let [rs (verify)
        real (remove :expect-fail rs)
        controls (filter :expect-fail rs)
        bad-controls (filter :pass controls)]
    (println)
    (println "=====")
    (println (format "  %d of %d decisions READY" (count (filter :pass real)) (count real)))
    (when (seq controls)
      (println (format "  %d negative control(s): %s"
                       (count controls)
                       (if (empty? bad-controls) "all correctly rejected"
                           (str "PROBLEM — these passed: " (str/join ", " (map :id bad-controls)))))))
    rs))
