(ns brainyard.test-run
  "How a suite is run, behind byj's guards: in this process (`run`), in
  shards across processes, or named namespaces alone (`main-for`), with a
  watchdog over each test. It requires no test namespace, so another suite
  over byj's could run by it too, from its own directory;
  `brainyard.test-runner` is byj's suite over it."
  (:require [clojure.edn]
            [clojure.java.io]
            [clojure.set]
            [clojure.string]
            [brainyard.schema :as schema]
            [clojure.test :as t]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.test-shards :as shards]))

(defn- own-sessions
  "Directories under THIS repository's .brainyard/sessions, and its turn and
  rewind refs (refs/byj/…, RFC-069): what a turn leaves in its project."
  []
  (let [d (clojure.java.io/file ".brainyard" "sessions")
        refs (try (->> (:out (fx/sh {:out :string :err :string} "git" "for-each-ref" "--format=%(refname)" "refs/byj/"))
                       str clojure.string/split-lines (remove clojure.string/blank?))
                  (catch Throwable _ nil))]
    (into (set (when (.isDirectory d) (map #(.getName %) (.listFiles d)))) refs)))

(defn nested-deftests
  "`file:line` for every `deftest` that is not at the start of a line.

  A `deftest` inside another one is not a test. It is a form that runs when the
  ENCLOSING test runs, so it registers late or not at all: it is invisible to
  anything that looks for it earlier, and if the outer test is never reached it
  never exists. Nothing about it looks wrong — the tests pass in isolation,
  because running the namespace runs the outer test, which defines them.

  Two of these were written into `analytics_test.clj` by an edit whose anchor
  closed an inner `let` rather than the `deftest` around it. The suite reported
  409 tests and a green run right after the file changed, then failed on every
  later run, and the intermittency read convincingly as a stale compile cache —
  a P1 was filed against jolt before the real cause was found (`byj-gly.95`).

  Column zero is the whole rule. It costs nothing and it is unambiguous."
  []
  (for [f (sort-by #(.getName %) (seq (.listFiles (clojure.java.io/file "test" "brainyard"))))
        :when (clojure.string/ends-with? (.getName f) ".clj")
        [n line] (map-indexed vector (clojure.string/split-lines (slurp f)))
        :when (re-find #"^\s+\(deftest\s" line)]
    (str "test/brainyard/" (.getName f) ":" (inc n))))

(def verified-file
  "The jolt the suite was last verified against. Re-record it after an upgrade
  you MEANT to make, never to silence the warning."
  "dev/jolt-verified.edn")

(defn- verified-jolt []
  (try (:jolt (clojure.edn/read-string (slurp verified-file)))
       (catch Throwable _ nil)))

(defn report-runtime
  "Say which jolt ran the suite, and WARN when it is not the one the suite was
  last verified against (`byj-gly.118`).

  A warning and never a failure. `deps.edn` declares `:jolt/min-version`, a
  floor and not a pin, and there is no CI to pin one: a hard failure here would
  block the deliberate upgrade it cannot tell apart from an accidental one, and
  would be silenced by the first person it inconvenienced. The point is to be
  TOLD, on the first run, rather than to find out afterwards — which is how
  `byj-gly.117` went, where a brew upgrade stopped `via` conveying dynamic
  bindings and only a test that happened to pin substrate behaviour caught it."
  []
  (let [now (config/jolt-version)
        want (verified-jolt)]
    (println (str "\njolt " (or now "(jolt.version property absent)")
                  (cond (nil? want) "  — nothing recorded in dev/jolt-verified.edn"
                        (= now want) "  — as verified"
                        :else (str "  — LAST VERIFIED ON " want))))
    (when (and now want (not= now want))
      (binding [*out* *err*]
        (println (str "\nTHE RUNTIME MOVED UNDER THE SUITE: verified on " want
                      ", running on " now ".\n"
                      "A green suite here says the tests pass on " now
                      ", not that " now " is a runtime anyone has checked this\n"
                      "port against. ebb declares no jolt floor and pins 0.8.2 in its own CI.\n"
                      "If this upgrade was deliberate and the suite is green, re-record:\n"
                      "  echo '{:jolt \"" now "\"}' > " verified-file))
        (flush)))
    now))

(defn- repo-root
  "The git root the suite runs in: the working directory for byj's, the
  checkout above it for a suite run from a directory of its own. A turn a test runs in a project under it
  is never kept in its refs (provenance P-55)."
  []
  (loop [d (.getCanonicalFile (clojure.java.io/file "."))]
    (cond (nil? d) (.getCanonicalPath (clojure.java.io/file "."))
          (.exists (clojure.java.io/file d ".git")) (.getPath d)
          :else (recur (.getParentFile d)))))

(defn run
  "Run `nses` in this process and return the summary — no exit, so a live
  nREPL image can call it: (brainyard.test-runner/run).

  Fails the run if the suite wrote into the DEVELOPER'S OWN project. A test
  that gives no `:project-dir` gets the git root, which is this repository, and
  since RFC-025 every turn there leaves a trajectory: one afternoon left 615
  session directories, and `session$analytics` then reported on mock fixtures
  as though they were this project's work (`byj-gly.88`). A test that needs a
  real project dir makes a temporary one."
  [{:keys [nses report?] :or {report? true}}]
  (let [_ (when report? (report-runtime))
        _ (schema/record-outputs!)
        ;; nor the person's memory store: a test that names no store gets none
        ;; (memory/defaults-off?, byj-gly.382)
        _ (System/setProperty "brainyard.memory-store" "none")
        ;; nor the person's Codex sessions and plan limits (RFC-049): an empty
        ;; home of the run's own, unless a test gives its own
        _ (System/setProperty "brainyard.codex-home" (.getCanonicalPath (doto (clojure.java.io/file "target/test-codex-home") .mkdirs)))
        ;; nor the person's byj-share login, which names the relay a session
        ;; shares through (share/relay-of, byj-1wv.134): one of the run's own, empty
        _ (System/setProperty "brainyard.share-home" (.getCanonicalPath (doto (clojure.java.io/file "target/test-share-home") .mkdirs)))
        ;; nor the person's own skills (~/.claude/skills, ~/.agents/skills), which
        ;; every turn's prompt lists and a test sized to a context window then
        ;; overflowed differently on each machine (byj-gly.467): an empty home
        _ (System/setProperty "brainyard.skills-home" (.getCanonicalPath (doto (clojure.java.io/file "target/test-skills-home") .mkdirs)))
        ;; nor this repository's git refs: since every turn is kept (RFC-069),
        ;; a test that runs one without a project of its own kept it here, 200
        ;; at a time (provenance P-55)
        _ (System/setProperty "brainyard.changes-skip-root" (repo-root))
        before (own-sessions)
        nested (nested-deftests)
        times (atom {})
        {:keys [fail] :as summary} (reduce (fn [acc n]
                                             (let [t0 (System/currentTimeMillis)
                                                   r (t/run-tests n)]
                                               (swap! times assoc n (- (System/currentTimeMillis) t0))
                                               (merge-with #(if (number? %1) (+ %1 %2) %2) acc (dissoc r :type))))
                                           {:test 0 :pass 0 :fail 0 :error 0}
                                           nses)
        summary (assoc summary :times @times)
        leaked (sort (clojure.set/difference (own-sessions) before))
        ;; every result any test produced, from a tool with an :output-schema,
        ;; held to it: the suite is the conformance check (RFC-030, byj-gly.199)
        mismatched (->> (schema/recorded-outputs) (filter :problems) (group-by :tool-name))]
    (when (seq nested)
      (binding [*out* *err*]
        (println (str "\n" (count nested) " deftest(s) are nested inside another test"
                      " and do not run as tests (byj-gly.95): "
                      (clojure.string/join ", " nested)))
        (flush)))
    (when (seq mismatched)
      (binding [*out* *err*]
        (println (str "\n" (count mismatched) " tool(s) returned what their :output-schema does not"
                      " describe (RFC-030) — fix the schema or the tool:"))
        (doseq [[tool rs] (sort-by key mismatched)]
          (println (str "  " tool ": " (clojure.string/join "; " (distinct (mapcat :problems rs)))
                        " (" (count rs) " call(s))")))
        (flush)))
    (if (and (empty? leaked) (empty? nested) (empty? mismatched))
      summary
      (do (when (seq leaked)
            (binding [*out* *err*]
            (println (str "\n" (count leaked) " turn(s) appeared in this repository's"
                          " .brainyard/sessions or refs/byj/ during the suite — give the test a temporary"
                          " :project-dir (byj-gly.88). First few: "
                          (clojure.string/join ", " (take 3 leaked))
                          "\n(this is a wall-clock diff, so a `by` run of your own in this"
                          " project WHILE the suite ran will also land here)"))
            (flush)))
          (update summary :fail (fnil inc 0))))))

;; --- the suite in parallel: shards across processes (byj-gly.333) ---------------------------

(def times-baseline "dev/test-times.edn")
(def times-measured "target/test-times.edn")

(defn- read-times []
  (or (try (clojure.edn/read-string (slurp times-measured)) (catch Throwable _ nil))
      (try (clojure.edn/read-string (slurp times-baseline)) (catch Throwable _ nil))
      {}))

(def assign
  "`brainyard.test-shards/assign`: kept apart so a test can require it — the
  runner requires every test namespace."
  shards/assign)

(defn default-shards
  "How many processes: `BY_TEST_SHARDS`, else half the cores, between 1 and 6.
  1 is the suite in one image — the only run that sees a test leave global
  state that breaks another in another namespace (provenance P-18)."
  []
  (or (some-> (System/getenv "BY_TEST_SHARDS") parse-long)
      (max 1 (min 6 (quot (.availableProcessors (Runtime/getRuntime)) 2)))))

(def ^:private summary-mark "@@shard-summary ")

(defn- run-shards
  "Run `shards` as child processes of this suite (`jolt -M:test`, each given
  its namespaces), wait for all, and return the merged summary. Each child's
  output is kept in target/test-shard-<i>.log; a failing one's is printed."
  [shards]
  (.mkdirs (clojure.java.io/file "target"))
  (let [t0 (System/currentTimeMillis)
        env (into {} (System/getenv))
        procs (vec (map-indexed
                    (fn [i {:keys [nses]}]
                      (let [log (str "target/test-shard-" i ".log")]
                        {:i i :log log :nses nses
                         :proc (fx/start {:env (assoc env "BY_TEST_SHARD" (str i)
                                                      "BY_TEST_NSES" (clojure.string/join "," nses)
                                                      ;; and every process a shard's test starts
                                                      ;; inherits it (provenance P-55)
                                                      "BY_CHANGES_SKIP_ROOT" (repo-root))
                                          :out :string :err :string}
                                         "sh" "-c" (str "exec jolt -M:test > " log " 2>&1"))}))
                    shards))
        results (vec (for [{:keys [proc log] :as p} procs
                           :let [exit (:exit @proc)
                                 out (try (slurp log) (catch Throwable _ ""))
                                 line (some #(when (clojure.string/starts-with? % summary-mark) %)
                                            (reverse (clojure.string/split-lines out)))
                                 sm (some-> line (subs (count summary-mark)) clojure.edn/read-string)]]
                       (assoc p :exit exit :out out :summary sm)))
        merged (reduce (fn [acc {:keys [summary]}]
                         (merge-with + acc (select-keys summary [:test :pass :fail :error])))
                       {:test 0 :pass 0 :fail 0 :error 0} results)
        bad (filter #(or (not (zero? (:exit %))) (nil? (:summary %))) results)]
    (spit times-measured (pr-str (into (read-times) (mapcat (comp :times :summary) results))))
    (doseq [{:keys [i log out exit summary]} bad]
      (println (str "\n===== shard " i " (" log ") — exit " exit (when-not summary ", no summary: it did not finish") " ====="))
      (println out))
    (println (str "\n" (count shards) " shard(s), " (quot (- (System/currentTimeMillis) t0) 1000) " s: "
                  (clojure.string/join " · " (for [{:keys [i summary]} results]
                                               (str "#" i " " (:test summary "?") " tests")))))
    (println (str "\nRan " (:test merged) " tests. " (:pass merged) " assertions passed, "
                  (:fail merged) " failures, " (:error merged) " errors."))
    (cond-> merged
      (seq bad) (update :fail + (count (remove :summary bad))))))

(def default-timeout-ms
  "How long a run may go with no test starting or ending: past it, it is hung,
  not slow. The slowest test takes ~17 s (a live tmux drive). Until
  byj-gly.444 this bounded the whole run at 300 s, which the serial suite
  outgrew by work rather than by hanging: the 1333 tests of 2026-10-30 took
  328 s. Measured per test, a hang is named as soon as it is one, at any size
  of suite."
  120000)

(def serial-budget-ms
  "What the serial suite is expected to take. Past it the run says so — a
  finding, to cut what grew (byj-gly.382, byj-gly.442) — but does not fail:
  time spent working is not a hang."
  360000)

(defn- track-current-test!
  "Wrap every registered test so `current` names the one running, and so an
  interrupt flag cannot outlive the test that set it. jolt's clojure.test runs
  tests from a registry of {:ns :name :fn}; JVM clojure.test has no such
  registry, which is why this lives in the runner, not a fixture.

  **The flag is the part that matters.** `fx/run!!` restores the interrupt on
  its calling thread when its wait is interrupted — correct for an edge whose
  own loop consumes it, and in `jolt test:byj` that thread is the ONE clojure.test
  runs every test on. A flag left set there makes the very next blocking call
  anywhere in the suite throw: a bare `(.interrupt (Thread/currentThread))`
  followed by an ordinary subprocess wait gives exactly
  `InterruptedException: Process.waitFor`, which is what
  `memory-test/a-store-is-a-command` failed with under `dev/flake-hunt.sh`.

  That is the shape of `byj-gly.82` and of provenance P-11: the test that fails
  is whichever blocks NEXT, so the name differs every time and the namespace
  always passes alone. Clearing between tests bounds the damage to one test,
  and saying which test left it set names the culprit the next time it
  happens.

  `current` is `{:test id :since ms}` while a test runs, from its start, and
  `{:last id :since ms}` between tests, from the end of the last one — what
  the watchdog measures."
  [current left-set]
  (swap! t/registry
         (fn [entries]
           (mapv (fn [{:keys [ns name] :as e}]
                   (update e :fn
                           (fn [f]
                             (fn []
                               (let [id (symbol (str ns) (str name))]
                                 (reset! current {:test id :since (System/currentTimeMillis)})
                                 ;; clears AND returns the flag
                                 (Thread/interrupted)
                                 (try (f)
                                      (finally
                                        (reset! current {:last id :since (System/currentTimeMillis)})
                                        (when (Thread/interrupted)
                                          (swap! left-set conj id)))))))))
                 entries))))

(defn- watch!
  "Exit 2 once `current` has not moved for `timeout-ms`, naming the test it
  stopped in, or the test it stopped after (a namespace loading, a fixture)."
  [current timeout-ms]
  (future
    (loop []
      (Thread/sleep 1000)
      (let [{:keys [test since] after :last} @current]
        (if (< (- (System/currentTimeMillis) since) timeout-ms)
          (recur)
          (binding [*out* *err*]
            (println (str "\nTEST SUITE TIMED OUT: no test started or ended for " timeout-ms " ms — "
                          (cond test (str "still running: " test)
                                after (str "none running since " after " ended (a namespace loading, a fixture)")
                                :else "none has started (the suite loading)")))
            (flush)
            (System/exit 2)))))))

(defn main-for
  "`-main` over `suite`, the namespaces a run without names runs: byj's
  `namespaces`, or another suite's that keeps byj's guards, whose deps.edn
  puts this directory on its test path."
  [suite args]
  ;; A watchdog, because a hung suite used to be silent: `jolt test:byj` prints its
  ;; summary only at the end, so a test blocked forever (the effect engine's
  ;; edges — ask, tick — wait without a timeout) looked like a slow run. One
  ;; unexplained 16-minute hang was seen on 2026-09-17 and not reproduced in 15
  ;; runs; if it recurs, this names the test it was in. It watches each test,
  ;; not the run (byj-gly.444): in a shard, the shard's; the parent of shards
  ;; runs none, and a shard that hangs ends on its own watchdog.
  (let [t0 (System/currentTimeMillis)
        current (atom {:since t0})
        left-set (atom [])
        timeout-ms (or (some-> (System/getenv "BY_TEST_TIMEOUT_MS") parse-long) default-timeout-ms)
        shard (System/getenv "BY_TEST_SHARD")
        named (seq (map symbol args))
        n (if (or shard named) 1 (default-shards))]
    (track-current-test! current left-set)
    (when (= 1 n) (watch! current timeout-ms))
    (let [{:keys [fail error] :as summary}
          (cond
            ;; a shard: its namespaces, its own eval store, a summary the parent reads
            shard (let [nses (mapv symbol (remove clojure.string/blank? (clojure.string/split (str (System/getenv "BY_TEST_NSES")) #",")))
                        r (run {:nses nses :report? false})]
                    (println (str summary-mark (pr-str (select-keys r [:test :pass :fail :error :times]))))
                    r)
            named (run {:nses (vec named)})
            (> n 1) (do (report-runtime)
                        (run-shards (assign suite n (read-times))))
            :else (let [r (run {:nses suite})
                        took (- (System/currentTimeMillis) t0)]
                    (spit times-measured (pr-str (into (read-times) (:times r))))
                    (when (> took serial-budget-ms)
                      (binding [*out* *err*]
                        (println (str "\nthe serial suite took " (quot took 1000) " s, past its "
                                      (quot serial-budget-ms 1000) " s budget: time each namespace and cut what grew"
                                      " (byj-gly.382, byj-gly.442, byj-gly.444)"))
                        (flush)))
                    r))]
      (when (seq @left-set)
        (binding [*out* *err*]
          (println (str "\nleft the thread interrupted: " (clojure.string/join ", " @left-set)
                        " — every blocking call after it would have thrown"
                        " (byj-gly.82). The flag was cleared between tests."))
          (flush)))
      (System/exit (if (zero? (+ fail error)) 0 1)))))
