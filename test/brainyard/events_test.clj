;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.events-test
  "RFC-026: every event the kernel emits is declared, and what is declared is
  emitted.

  The first half is the ratchet that matters. 27 of 41 events once had no
  consumer at all — `main/tracer`'s `case` had no branch, so they fell through
  to `nil` and vanished with or without `-v`, including a user's own tool being
  rejected as malformed. Nothing distinguished a drop someone chose from a drop
  nobody wrote. This reads `src/` and fails on an event that is emitted and not
  declared, so the next one cannot join that list quietly.

  Reading is syntactic — forms are read, not evaluated — the same way
  `effect-ratchet-test` reads for RFC-009's rules."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.events :as events]))

(def emitters
  "Call heads that put an event on the bus. All of them end in
  `events/emit!` — `emit!` is the private one-line wrapper in bt, hooks and
  agent, `emit` is the local alias in predictor and tasks — which is what
  `nothing-reaches-the-bus-except-through-here` keeps true, and what makes
  reading for these heads sound rather than hopeful."
  '#{emit! emit events/emit!})

(defn- read-forms [text]
  (binding [*read-eval* false] (read-string (str "[" text "\n]"))))

(defn emitted
  "Every namespaced keyword passed to an emitter in `forms`.

  Namespaced on purpose: `session/emit` takes a terminal KIND (`:answer`,
  `:error`), which is a different bus and is unqualified, so requiring a
  namespace excludes it without an exception for it."
  [forms]
  (let [found (atom #{})]
    (letfn [(walk [form]
              (when (coll? form)
                (when (and (seq? form)
                           (symbol? (first form))
                           (contains? emitters (symbol (name (first form)))))
                  (doseq [a (rest form)]
                    (when (and (keyword? a) (namespace a)) (swap! found conj a))))
                (doseq [x (if (map? form) (mapcat identity form) form)] (walk x))))]
      (doseq [f forms] (walk f)))
    @found))

(defn- src-files []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map #(.getPath %))
       sort))

(defn- emitted-in-src []
  (into (sorted-set) (mapcat #(emitted (read-forms (slurp %))) (src-files))))

;; --- the detector detects ---------------------------------------------------------

(deftest the-detector-is-not-vacuous
  (let [found (emitted (read-forms "
      (defn a [ctx] (emit! ctx :some/thing {:a 1}))
      (defn b [{:keys [on-event]}] (when on-event (events/emit! on-event :other/thing {})))
      (defn c [ctx] (emit ctx :answer \"text\"))
      (defn d [ctx] (doseq [x xs] (emit! ctx :nested/thing x)))
      (defn e [ctx] (log! ctx :not-an/event {}))"))]
    (is (= #{:some/thing :other/thing :nested/thing} found))
    (testing "an unqualified kind is a different bus, not an event"
      (is (not (contains? found :answer))))
    (testing "and a keyword passed to something that is not an emitter is not one"
      (is (not (contains? found :not-an/event))))))

;; --- the ratchet ------------------------------------------------------------------

(deftest every-emitted-event-is-declared
  (let [undeclared (remove events/catalogue (emitted-in-src))]
    (is (empty? undeclared)
        (str "emitted but not in brainyard.events/catalogue — decide whether each "
             "is a :problem, a :trace or a deliberate :quiet, and say which: "
             (str/join ", " undeclared)))))

(deftest every-declared-event-is-emitted
  ;; the other direction: a catalogue that outlives the code describes events
  ;; nobody sends, and the reader cannot tell which entries are real
  (let [in-src (emitted-in-src)
        stale (remove in-src (keys events/catalogue))]
    (is (empty? stale) (str "declared but never emitted: " (str/join ", " stale)))))

(defn raw-bus-access
  "Places in `forms` that could put an event on the bus without going through
  `events/emit!`, which is what would make the catalogue a guess.

  Three shapes, all of which existed before this test:
    ((:on-event ctx) :some/thing {})          — invoked in place
    (when-let [f (:on-event ctx)] (f :x {}))  — bound, then invoked
    (on-event :some/thing {})                 — the handler taken by :keys

  Reading `:on-event` to PASS IT ON — `:on-event (:on-event ctx)` in a child's
  option map — is not access and is not flagged."
  [forms]
  (let [found (atom [])
        binders '#{let when-let if-let when-some if-some loop binding}
        reads-bus? (fn [x] (and (seq? x) (= :on-event (first x))))]
    (letfn [(walk [form]
              (when (coll? form)
                (when (seq? form)
                  ;; ((:on-event ctx) …)
                  (when (reads-bus? (first form))
                    (swap! found conj form))
                  ;; (on-event :some/thing …)
                  (when (and (= 'on-event (first form))
                             (some #(and (keyword? %) (namespace %)) (rest form)))
                    (swap! found conj form))
                  ;; (when-let [f (:on-event ctx)] …)
                  (when (and (symbol? (first form))
                             (contains? binders (symbol (name (first form))))
                             (vector? (second form))
                             (some reads-bus? (second form)))
                    (swap! found conj form)))
                (doseq [x (if (map? form) (mapcat identity form) form)] (walk x))))]
      (doseq [f forms] (walk f)))
    @found))

(deftest the-bus-detector-is-not-vacuous
  (let [bad (raw-bus-access (read-forms "
        (defn a [ctx] ((:on-event ctx) :x/y {}))
        (defn b [ctx] (when-let [f (:on-event ctx)] (f :x/y {})))
        (defn c [{:keys [on-event]}] (on-event :x/y {}))"))
        ok (raw-bus-access (read-forms "
        (defn d [ctx] {:on-event (:on-event ctx) :config (:config ctx)})
        (defn e [ctx] (events/emit! ctx :x/y {}))
        (defn f [{:keys [on-event]}] (when on-event (events/emit! on-event :x/y {})))"))]
    (is (= 3 (count bad)))
    (is (empty? ok) "passing the handler on is not reaching the bus")))

(deftest nothing-reaches-the-bus-except-through-here
  ;; What makes every-emitted-event-is-declared worth anything. The scanner can
  ;; only see emissions whose head it recognises — session.clj's
  ;; (when-let [f (:on-event ctx)] (f :session.turn/end result)) was invisible to
  ;; it, and so were :task/detached, :task/parked, :agents/defined and
  ;; :agents/deleted. One door means the catalogue can be checked.
  (doseq [path (src-files)
          :when (not= path "src/brainyard/events.clj")]
    (testing path
      (is (empty? (raw-bus-access (read-forms (slurp path))))))))

(deftest every-entry-says-what-it-is-and-what-it-carries
  (doseq [[event {:keys [level doc]}] events/catalogue]
    (testing (str event)
      (is (contains? events/levels level) "a declared level")
      (is (not (str/blank? doc)) "and a doc saying what the payload carries"))))

(deftest a-quiet-event-says-what-says-it-instead
  ;; :quiet is a decision, not an oversight, so it has to carry its reason —
  ;; that is the whole difference between this and the branch nobody wrote
  (doseq [[event {:keys [level doc]}] events/catalogue
          :when (= :quiet level)]
    (testing (str event)
      (is (> (count doc) 30) "a reason, not a restatement of the name"))))

;; --- what a viewer gets to work with ------------------------------------------------

(deftest a-problem-is-named-in-words-and-keeps-the-payloads-own
  (testing "the label reads as a phrase, not a keyword"
    (is (= "user tool skipped" (events/label :tools/user-tool-skipped)))
    (is (= "hook handler failed" (events/label :hooks/handler-failed))))

  (testing "an undeclared event still gets a readable name rather than throwing"
    (is (= "something odd" (events/label :made-up/something-odd))))

  (testing "the detail is the payload's own words — a problem is usually already
            a sentence someone wrote, and restating it would lose it"
    (is (= "/p/x.edn — name must be lowercase-kebab; body must be a string"
           (events/detail {:file "/p/x.edn"
                           :problems ["name must be lowercase-kebab"
                                      "body must be a string"]})))
    (is (= "boom" (events/detail {:error "boom"})))
    (is (= "a plain string payload" (events/detail "a plain string payload"))))

  (testing "a payload with no sentence of its own yields nothing, not a space —
            \"\" is truthy, so a viewer that used `or` printed a bare label"
    (is (empty? (events/detail {:input-tokens 12 :elided 3})))
    (is (nil? (events/detail 42)))))

(deftest a-rejected-definition-is-a-problem-a-narration-is-not
  (testing "someone's tool, hook or agent silently not being there is a problem"
    (is (events/problem? :tools/user-tool-skipped))
    (is (events/problem? :hooks/user-hook-skipped))
    (is (events/problem? :agents/agent-skipped))
    (is (events/problem? :agents/shadowed))
    (is (events/problem? :predictor/params-skipped))
    (is (events/problem? :hooks/handler-failed))
    (is (events/problem? :agent.tree/tool-missing)))

  (testing "the turn narrating itself is not"
    (is (= :trace (events/level :agent.tools/calls)))
    (is (= :trace (events/level :agent.iteration/start))))

  (testing "and what something else already says is neither"
    (is (= :quiet (events/level :agent.answer/final)))))
