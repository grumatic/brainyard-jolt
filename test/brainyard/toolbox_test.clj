(ns brainyard.toolbox-test
  "Declarative tools (RFC-010): the Tool Lisp language, user tools and their
  commands, brainyard's own tool files, and user tools inside the loop."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [brainyard.agent :as agent]
            [brainyard.schema :as schema]
            [brainyard.effect :as fx]
            [brainyard.hooks :as hooks]
            [brainyard.json :as json]
            [brainyard.store :as store]
            [brainyard.tool-distill :as tool-distill]
            [brainyard.tool-lisp :as tl]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]
            [jolt.process :as p]))

(defn- fresh-dir [name]
  (let [d (io/file "target/toolbox-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- spit-in [dir path content]
  (let [f (io/file dir path)] (.mkdirs (.getParentFile f)) (spit f content)))

(defn- setup
  "A registry with the builtins and the commands, persisting under a fresh dir."
  [name]
  (let [dir (fresh-dir name)
        reg (toolbox/registry tools/builtin-tools)
        cmds (toolbox/authoring-tools reg (toolbox/tools-dir dir))]
    (swap! reg into (map (juxt :name identity)) cmds)
    {:dir dir :reg reg :ctx {:config {:working-dir dir :project-dir dir}}
     :cmd (fn [n args] (tools/call-tool (get @reg n) args {:config {:working-dir dir :project-dir dir}}))}))

;; --- the language -------------------------------------------------------------------

(defn- known [sym] (get {'read-file {:input-schema [:map [:path :any]
                                                         [:offset {:optional true} :any]]}} sym))

(deftest reading
  (testing "#(...) and #\"...\" read; #= is refused"
    (is (seq? (tl/read-body "(fn [a] (map #(inc %) (re-seq #\"\\d+\" a)))")))
    (is (thrown-with-msg? Exception #"unreadable" (tl/read-body "(fn [a] #=(+ 1 2))"))))
  (testing "unbalanced delimiters are reported with a position"
    (is (= ["body delimiters do not balance: unclosed `(` opened at line 3, column 3"]
           (:problems (ex-data (try (tl/read-body "(fn [a]\n  (let [x 1] x)\n  (inc") (catch Throwable e e)))))))
  (testing "one form only"
    (is (thrown-with-msg? Exception #"more than one" (tl/read-body "(fn [a] a) (fn [b] b)")))))

(declare with-ext validation-in-the-base-language)

(deftest validation
  ;; the BASE language's refusals: a run's config may have put the extensions
  ;; in force (byj-gly.258), so this says which language it means
  (with-ext #{} #(validation-in-the-base-language)))

(defn- validation-in-the-base-language []
  (is (nil? (tl/validate (tl/read-body "(fn [{:keys [path]}] (let [c (:content (read-file {:path path}))] (count (clojure.string/split-lines c))))") known)))
  (is (= ["`def` is not allowed in Tool Lisp"
          "unknown symbol `x` — not a local, an allowed function, or a tool"
          "`Math/abs`: Java interop is not allowed"
          "`.length`: Java interop is not allowed"
          "`slurp` is not allowed in Tool Lisp"
          "tool read-file has no param `pth` (params: offset, path)"
          "tool read-file is called without its required param `path`"
          "`loop` is not allowed in Tool Lisp"
          "`recur` is not allowed in Tool Lisp"]
         (tl/validate (tl/read-body "(fn [a] (def x 1) (Math/abs -1) (.length \"s\") (slurp \"f\") (read-file {:pth 1}) (loop [] (recur)))") known)))
  (is (= ["named fns are not allowed (no recursion)"] (tl/validate (tl/read-body "(fn [a] (fn self [x] x))") known)))
  (is (= ["body must be a single (fn [args] ...) form"] (tl/validate (tl/read-body "(+ 1 2)") known)))
  (testing "a local shadowing a tool name is a local, not a call"
    (is (= #{} (tl/tool-refs (tl/read-body "(fn [read-file] (read-file 1))") known)))))

(deftest evaluation
  (let [calls (atom [])
        call (fn [n args] (swap! calls conj [n args]) {:content "b a\nc a" :size 7})
        run #(tl/run (tl/read-body %1) %2 call)]
    (is (= {:lines 2 :words ["a" "a" "b" "c"] :inc [2 3] :first "B"}
           (run "(fn [{:keys [path]}]
                   (let [c (:content (read-file {:path path}))]
                     {:lines (count (clojure.string/split-lines c))
                      :words (sort (remove clojure.string/blank? (clojure.string/split c #\"\\s+\")))
                      :inc (map #(inc %) [1 2])
                      :first (-> c clojure.string/upper-case (subs 0 1))}))" {:path "x"})))
    (is (= [["read-file" {:path "x"}]] @calls))
    (is (= [:pos 1 3 1 nil [1 [2 3]]]
           (run "(fn [{:keys [n]}] [(cond (< n 0) :neg (= n 0) :zero :else :pos)
                                    (if-let [x (get {:a 1} :a)] x :none)
                                    (some-> {:a {:b 2}} :a :b inc)
                                    (when-not false 1)
                                    (some-> {} :a :b)
                                    (let [[x & more] [1 2 3]] [x more])])" {:n 5})))))

(deftest runtime-guards
  (let [call (fn [_ _] {:content "text" :size 4})
        run #(tl/run (tl/read-body %1) {} call %2)]
    (testing "string functions reject non-strings, naming what they got"
      (is (thrown-with-msg? Exception #"clojure.string/lower-case expects a string, got a map with keys \[:content :size\]"
                            (run "(fn [a] (clojure.string/lower-case (read-file {:path \"p\"})))" nil))))
    (testing "budgets"
      (is (thrown-with-msg? Exception #"step budget" (run "(fn [a] (map (fn [x] (inc x)) (range 10000)))" {:steps 1000})))
      (is (thrown-with-msg? Exception #"range may produce at most" (run "(fn [a] (reduce + (range 100000000)))" nil)))
      (is (thrown-with-msg? Exception #"more than 3 tool calls" (run "(fn [a] (map (fn [x] (read-file {:path x})) [\"a\" \"b\" \"c\" \"d\"]))" {:tool-calls 3}))))
    (testing "an interrupted thread stops the body"
      (let [out (promise)
            t (Thread. (fn [] (deliver out (try (run "(fn [a] (map (fn [x] (* x x)) (range 1000000)))" {:steps 100000000})
                                                (catch InterruptedException _ :interrupted)
                                                (catch Throwable e (ex-message e))))))]
        (.start t)
        (Thread/sleep 50)
        (.interrupt t)
        (is (= :interrupted (deref out 5000 :timeout)))))))

;; --- extensions (byj-gly.258) ------------------------------------------------------------

(defn- with-ext [xs f]
  (let [before @tl/extensions]
    (try (tl/set-extensions! xs) (f)
         (finally (reset! tl/extensions before)))))

(deftest the-base-language-refuses-what-the-tiers-add
  (let [probs #(tl/validate (tl/read-body %) (constantly nil))]
    (testing "switched off (`:tool-lisp-extensions []`), the base language refuses what the tiers add"
     (with-ext #{}
      #(do
      (is (some #(clojure.string/includes? % "`loop` is not allowed") (probs "(fn [_] (loop [i 0] (if (< i 2) (recur (inc i)) i)))")))
      (is (some #(clojure.string/includes? % "unknown symbol `for`") (probs "(fn [_] (for [x [1]] x))")))
      (is (some #(clojure.string/includes? % "unknown symbol `fnil`") (probs "(fn [_] (fnil inc 0))")))
      (is (not (some #{"fnil"} (tl/allowed-function-names)))))))
    (testing "each tier alone adds only its own"
      (with-ext #{:vocabulary}
        #(do (is (nil? (probs "(fn [_] ((fnil inc 0) nil))")))
             (is (some? (probs "(fn [_] (for [x [1]] x))")))
             (is (some #{"fnil"} (tl/allowed-function-names)))))
      (with-ext #{:forms}
        #(do (is (nil? (probs "(fn [_] (for [x [1] :let [y (inc x)]] y))")))
             (is (some? (probs "(fn [_] (loop [i 0] i))"))))))))

(deftest extended-forms-evaluate-as-clojure
  (with-ext #{:vocabulary :forms :control}
    (fn []
      (let [call (fn [n a] {:content (str (:path a))})
            run #(tl/run (tl/read-body %) {} call)]
        (testing "vocabulary"
          (is (= ["a" 2] (run "(fn [_] (->> [\"a\" \"b\" \"a\"] (reduce (fn [m w] (update m w (fnil inc 0))) {}) (sort-by val >) first))")))
          (is (= [#{1 3} {:a {:b 1}} {:x [1 2]}]
                 (run "(fn [_] [(clojure.set/difference #{1 2 3} #{2}) (clojure.walk/keywordize-keys {\"a\" {\"b\" 1}}) (clojure.edn/read-string \"{:x [1 2]}\")])"))))
        (testing "forms: an eager for, doseq for effects, case with a list of constants, cond->"
          (is (= [11 21 13 23] (run "(fn [_] (for [x [1 2 3] y [10 20] :when (odd? x)] (+ x y)))")))
          (is (= :done (run "(fn [_] (doseq [p [\"a\" \"b\"]] (read-file {:path p})) :done)")))
          (is (= 2 (run "(fn [_] (case \"md\" \"clj\" 1 (\"md\" \"txt\") 2 3))")))
          (is (= {:a 1 :none true} (run "(fn [_] (cond-> {:a 1} false (assoc :n 1) true (assoc :none true)))"))))
        (testing "control: loop/recur, recur in a fn, letfn, a named fn"
          (is (= [0 1 2 3] (run "(fn [_] (loop [i 0 acc []] (if (< i 4) (recur (inc i) (conj acc i)) acc)))")))
          (is (= 120 (run "(fn [_] ((fn [n acc] (if (zero? n) acc (recur (dec n) (* acc n)))) 5 1))")))
          (is (= 3 (run "(fn [_] (letfn [(depth [t] (if (map? t) (inc (apply max 0 (map depth (vals t)))) 0))] (depth {:a {:b {:c 1}}})))")))
          (is (= 720 (run "(fn [_] ((fn fact [n] (if (< n 2) 1 (* n (fact (dec n))))) 6))"))))
        (testing "try/catch keeps going past a failure; throw ex-info stops with a reason"
          (is (= [5 :bad 2] (run "(fn [_] (mapv (fn [s] (try (/ 10 (parse-long s)) (catch Exception e :bad))) [\"2\" \"0\" \"5\"]))")))
          (is (= ["nope" {:k 1}] (run "(fn [_] (try (throw (ex-info \"nope\" {:k 1})) (catch ExceptionInfo e [(ex-message e) (ex-data e)])))")))
          (is (thrown-with-msg? Exception #"stop here" (run "(fn [_] (throw (ex-info \"stop here\" {})))"))))
        (testing "a catch never catches the budget, and names only what a body raises"
          (is (thrown-with-msg? Exception #"step budget" (run "(fn [_] (try (loop [] (recur)) (catch Exception e :caught)))")))
          (is (= ["catch java.io.IOException: catch Exception (anything a body can raise) or ExceptionInfo"]
                 (tl/validate (tl/read-body "(fn [_] (try 1 (catch java.io.IOException e 2)))") (constantly nil)))))))))

;; --- tier D, reach (byj-gly.261) --------------------------------------------------------

(deftest reach-is-its-own-tier
  (let [probs #(tl/validate (tl/read-body %) (constantly nil))]
    (with-ext #{:vocabulary :forms :control}
      #(do (is (= ["`pmap` is not allowed in Tool Lisp"] (probs "(fn [_] (pmap inc [1 2]))")))
           (is (some (fn [p] (str/includes? p "unknown symbol `now-ms`")) (probs "(fn [_] (now-ms))")))))
    (with-ext #{:reach}
      #(do (is (nil? (probs "(fn [_] [(pmap inc [1 2]) (now) (now-ms)])")))
           (is (some #{"pmap"} (tl/allowed-function-names)))))))

(deftest pmap-runs-a-bodys-calls-a-few-at-a-time
  (with-ext #{:vocabulary :forms :control :reach}
    (fn []
      (let [running (atom 0) most (atom 0) calls (atom [])
            call (fn [_ {:keys [path]}]
                   (swap! most max (swap! running inc))
                   (Thread/sleep 40)
                   (swap! running dec)
                   (swap! calls conj path)
                   {:content (str "<" path ">")})
            run #(tl/run (tl/read-body %1) {} call %2)]
        (testing "in input order, at most :parallel at once, more than one"
          (is (= ["<a>" "<b>" "<c>" "<d>" "<e>" "<f>"]
                 (run "(fn [_] (pmap (fn [p] (:content (read-file {:path p}))) [\"a\" \"b\" \"c\" \"d\" \"e\" \"f\"]))" {:parallel 2})))
          (is (= 2 @most)))
        (testing "several collections, as clojure's pmap"
          (is (= [11 22] (run "(fn [_] (pmap + [1 2] [10 20]))" nil))))
        (testing "the calls it makes are the body's: one tool-call budget"
          (is (thrown-with-msg? Exception #"more than 3 tool calls"
                                (run "(fn [_] (pmap (fn [p] (read-file {:path p})) [\"a\" \"b\" \"c\" \"d\" \"e\"]))" {:tool-calls 3}))))
        (testing "a failure is thrown, and a catch takes it; the budget it never does"
          (is (= :caught (run "(fn [_] (try (pmap (fn [x] (if (= x 2) (throw (ex-info \"two\" {})) x)) [1 2 3]) (catch Exception e :caught)))" nil)))
          (is (thrown-with-msg? Exception #"step budget"
                                (run "(fn [_] (try (pmap (fn [x] (loop [] (recur))) [1 2]) (catch Exception e :caught)))" {:steps 5000}))))
        (testing "at most max-parallel-items"
          (is (thrown-with-msg? Exception #"pmap takes at most 1000 items" (run "(fn [_] (pmap inc (range 1001)))" nil))))
        (testing "an interrupted body cancels the calls still running"
          (reset! calls [])
          (let [slow (fn [_ _] (Thread/sleep 3000) (swap! calls conj :done) {})
                out (promise)
                t (Thread. (fn [] (deliver out (try (tl/run (tl/read-body "(fn [_] (pmap (fn [p] (read-file {:path p})) [\"a\" \"b\"]))") {} slow nil)
                                                    (catch InterruptedException _ :interrupted)
                                                    (catch Throwable e (ex-message e))))))]
            (.start t)
            (Thread/sleep 100)
            (.interrupt t)
            (is (= :interrupted (deref out 2000 :timeout)))
            (Thread/sleep 100)
            (is (empty? @calls) "neither call finished its sleep")))))))

(deftest a-body-that-reads-the-clock-says-so
  (with-ext #{:vocabulary :forms :control :reach}
    (fn []
      (let [{:keys [reg cmd ctx]} (setup "clock")]
        (is (re-matches #"\d{4}-\d\d-\d\dT.*Z" (tl/run (tl/read-body "(fn [_] (now))") {} (fn [_ _] nil))))
        (is (= "user$tool$stamp" (:id (cmd "tool$create" {:name "stamp" :body "(fn [_] {:at (now-ms)})"}))))
        (is (= "user$tool$wrap" (:id (cmd "tool$create" {:name "wrap" :body "(fn [_] (user$tool$stamp {}))"}))))
        (is (number? (:at (cmd "user$tool$stamp" {}))))
        (testing "its effect is its own, and a caller's through it"
          (is (= #{:clock/read} (toolbox/effects reg "user$tool$stamp")))
          (is (= #{:clock/read} (toolbox/effects reg "user$tool$wrap"))))
        (testing "so a draft that reads it is not proved by replaying it"
          (is (= {:status :unproved :effects #{:clock/read}}
                 (select-keys (tool-distill/prove reg ctx {:name "stamp2" :body "(fn [_] (now-ms))"} [{:run 1 :args {}}] [{:result "1"}])
                              [:status :effects]))))))))

(deftest a-store-keeps-state-between-calls
  (let [dir (fresh-dir "store")
        ctx {:config {:working-dir dir :project-dir dir}}
        reg (toolbox/registry (concat tools/builtin-tools (store/commands (:config ctx))))
        call #(tools/call-tool (get @reg %1) %2 ctx)]
    (testing "put, get, keys, forget"
      (is (= {:key "k" :found false} (call "store$get" {:key "k"})))
      (is (= {:key "k" :stored true} (call "store$put" {:key "k" :value {:n 1 :xs [1 2]}})))
      (is (= {:key "k" :found true :value {:n 1 :xs [1 2]}} (call "store$get" {:key "k"})))
      (call "store$put" {:key "scan/a" :value "x"})
      (is (= {:keys ["k" "scan/a"]} (call "store$keys" {})))
      (is (= {:keys ["scan/a"]} (call "store$keys" {:prefix "scan/"})))
      (is (= {:key "k" :removed true} (call "store$put" {:key "k"})))
      (is (= {:key "k" :found false} (call "store$get" {:key "k"}))))
    (testing "on disk, as EDN, for a later session"
      (is (= {"scan/a" "x"} (edn/read-string (slurp (io/file dir ".brainyard" "store.edn"))))))
    (testing "what is not EDN data is refused"
      (is (str/includes? (:error (store/put! (:config ctx) "f" inc)) "EDN data")))
    (testing "declared effects, for the gates"
      (is (= #{:fs/write :store/write} (toolbox/effects reg "store$put")))
      (is (= #{:fs/read :store/read} (toolbox/effects reg "store$get"))))
    (testing "puts from a body's pmap all land"
      (with-ext #{:vocabulary :forms :control :reach}
        #(tl/run (tl/read-body "(fn [_] (pmap (fn [i] (store$put {:key (str \"n\" i) :value i})) (range 20)))")
                 {} (fn [n a] (call n a)) {:parallel 8})))
      (is (= 20 (count (:keys (call "store$keys" {:prefix "n"})))))))

(deftest the-store-is-bound-with-its-tier
  (let [dir (fresh-dir "store-bound")
        names #(set (keys @(:tools (agent/make-ctx {:config {:working-dir dir :project-dir dir :tool-lisp-extensions %}
                                                   :lm {:provider :mock :format :mock}}))))
        before @tl/extensions]
    (try
      (is (not (contains? (names [:vocabulary :forms :control]) "store$put")))
      (is (contains? (names [:vocabulary :forms :control :reach]) "store$put"))
      (is (str/includes? (toolbox/language) "(pmap (fn [p]") "the model reads tier D while it is on")
      (testing "where a body is about to be written, not only in tool$create's spec (byj-gly.278)"
        (let [prompt #(let [c (agent/make-ctx {:config {:working-dir dir :project-dir dir :tool-lisp-extensions %}
                                                :lm {:provider :mock :format :mock}})]
                        (agent/session-context (:lm c) (:config c) (vals @(:tools c))))]
          (is (str/includes? (prompt [:vocabulary :forms :control :reach]) "`(pmap f xs)`"))
          (is (not (str/includes? (prompt [:vocabulary :forms :control]) "pmap")))))
      (finally (reset! tl/extensions before)))))

;; --- user tools ----------------------------------------------------------------------

(deftest brainyards-own-tool-files-load-and-run
  (let [{:keys [dir reg ctx]} (setup "brainyard-files")
        tdir (toolbox/tools-dir dir)]
    (.mkdirs tdir)
    (doseq [f (.listFiles (io/file "test/fixtures/brainyard-tools"))]
      (io/copy f (io/file tdir (.getName f))))
    (spit-in dir "notes.txt" "one two\nthree")
    (let [{:keys [loaded skipped]} (toolbox/load-user-tools! reg tdir)
          call #(tools/call-tool (get @reg %1) %2 ctx)]
      (is (= #{"user$tool$count-lines" "user$tool$count-words" "user$tool$shout-test"} (set loaded)))
      (is (empty? skipped))
      (testing "an inline :body, a sidecar .clj, and brainyard's malli :input-schema"
        (is (= [{:name :path :type "string" :optional? false :desc "File path to count lines for"}]
               (schema/inputs (get @reg "user$tool$count-lines"))))
        (is (= {:path "notes.txt" :lines 2} (call "user$tool$count-lines" {:path "notes.txt"})))
        (is (= {:path "notes.txt" :word-count 3} (call "user$tool$count-words" {:path "notes.txt"})))
        (is (= {:loud "HI"} (call "user$tool$shout-test" {:text "hi"})))
        (is (= {:path "missing.txt" :error "Could not read file: missing.txt"}
               (call "user$tool$count-lines" {:path "missing.txt"})))))))

(deftest a-brainyard-input-schema-is-checked-as-it-is
  ;; a nested shape is checked as it is written (RFC-030)
  (let [{:keys [dir reg cmd]} (setup "input-schema")
        r (cmd "tool$create" {:name "sum-items" :description "Sum."
                              :input-schema "[:map [:items [:vector [:map [:n :int]]]] [:opts {:optional true} :brainyard.schema/map-object-arg]]"
                              :body "(fn [{:keys [items]}] {:count (count items)})"})]
    (is (= "user$tool$sum-items" (:id r)) (pr-str r))
    (testing "a good call runs, its nested ints coerced from strings"
      (is (= {:count 2} (cmd "user$tool$sum-items" {:items [{:n 1} {:n "2"}]}))))
    (testing "a nested problem is named by its path"
      (is (str/includes? (str (:error-message (cmd "user$tool$sum-items" {:items [{:n "x"}]})))
                         "`items.0.n` should be an integer")))
    (testing "a shared schema from brainyard's registry is resolved"
      (is (str/includes? (str (:error-message (cmd "user$tool$sum-items" {:items [] :opts 5})))
                         "`opts`")))
    (testing "the schema is persisted, and a reload checks against it still"
      (is (str/includes? (slurp (io/file (toolbox/tools-dir dir) "sum-items.edn")) ":input-schema"))
      (let [reg2 (toolbox/registry tools/builtin-tools)]
        (toolbox/load-user-tools! reg2 (toolbox/tools-dir dir))
        (is (= (:input-schema (get @reg "user$tool$sum-items")) (:input-schema (get @reg2 "user$tool$sum-items"))))))
    (testing "and one that is not a malli schema is refused"
      (is (str/includes? (str (cmd "tool$create" {:name "bad-schema" :input-schema "[:map [:a :no-such-type]]" :body "(fn [_] 1)"}))
                         "input-schema is not a malli schema")))))

(deftest a-schema-that-is-not-a-map-is-refused-with-what-to-write
  ;; the shapes haiku sent with tier D on (byj-gly.279), each refused with the
  ;; form to write instead, and none rewritten into a schema
  (let [{:keys [dir cmd]} (setup "not-a-map")
        refusal (fn [schema] (str (:error (cmd "tool$create" {:name "bad" :input-schema schema :body "(fn [_] 1)"}))))
        example "e.g. \"[:map [:path [:string"]
    (testing "a list of parameter maps"
      (let [r (refusal [{:name "path" :type "string" :description "Path to the file"}])]
        (is (str/includes? r "input-schema must be a malli [:map ...] schema"))
        (is (str/includes? r "not a list of parameter maps"))
        (is (str/includes? r example) r)))
    (testing "malli with its keywords as strings, as data or as EDN text"
      (doseq [schema [["map" ["path" "string?"]] [":map" [":path" ":string"]] "[\"map\" [\"path\" \"string\"]]"]]
        (let [r (refusal schema)]
          (is (str/includes? r "malli's names are keywords") (pr-str schema))
          (is (str/includes? r example) r))))
    (testing "any other shape still says what to write"
      (let [r (refusal ":string")]
        (is (str/includes? r "input-schema must be a malli [:map ...] schema"))
        (is (not (str/includes? r " — ")) r)
        (is (str/includes? r example) r)))
    (testing "nothing was saved"
      (is (not (.exists (io/file (toolbox/tools-dir dir) "bad.edn")))))))

(deftest an-unbalanced-body-is-repaired-from-its-indentation
  (testing "closers at the end of a line are rebuilt from how the next line is indented"
    ;; haiku's, from byj-gly.258's eval: a `]` for the `)` that closes the ->>
    (let [body (str "(fn [{:keys [path]}]\n"
                    "  (let [data (json/read (:content (read-file {:path path})))\n"
                    "        totals (->> data\n"
                    "                    (map (fn [[s xs]] [s (count xs)]))\n"
                    "                    (into {})]\n"
                    "    {:totals totals}))")
          r (tl/repair-body body)]
      (is (= [{:line 5 :was "                    (into {})]" :now "                    (into {}))]"}] (:lines r)))
      (is (seq? (tl/read-body (:body r))))))
  (testing "a body left open at its end is closed, and one closed too far is trimmed"
    (is (= "(fn [x]\n  (inc x))" (:body (tl/repair-body "(fn [x]\n  (inc x)"))))
    (is (= "(fn [x] (inc x))" (:body (tl/repair-body "(fn [x] (inc x)))")))))
  (testing "a line that already closes right is left as it was, trailing space and comment too"
    (is (= ["(fn [x]  ; the arg" "  (let [a 1]" "    a))"]
           (str/split-lines (:body (tl/repair-body "(fn [x]  ; the arg\n  (let [a 1]\n    a)"))))))
  (testing "what indentation cannot decide stays refused"
    (is (nil? (tl/repair-body "(fn [x] (inc x))")) "it balances: nothing to repair")
    (is (nil? (tl/repair-body "(fn [x] (str \"a)")) "an open string")
    (is (nil? (tl/repair-body "(fn [x] [(inc x)) 1])")) "a wrong closer in the middle of a line")
    (is (nil? (tl/repair-body "(fn [x]\n  (let [a 1]\n  ) a)")) "a line that starts by closing and goes on")
    (is (nil? (tl/repair-body "(fn [x] (str \\\".md\\\")")) "escaped twice: rebuilt, it still does not read"))
  (testing "tool$create saves the repaired body and says which lines changed; off, it refuses"
    (let [{:keys [dir reg cmd]} (setup "repair")
          args {:name "twice" :input-schema "[:map [:n :int]]"
                :body "(fn [{:keys [n]}]\n  (let [m (* 2 n)]]\n    {:twice m}))"}
          r (cmd "tool$create" args)]
      (is (= ["line 2: (let [m (* 2 n)]"] (get-in r [:repaired :lines])))
      (is (str/includes? (get-in r [:repaired :note]) "rebuilt from its indentation"))
      (is (= {:twice 6} (cmd "user$tool$twice" {:n 3})))
      (is (= "(fn [{:keys [n]}] (let [m (* 2 n)] {:twice m}))"
             (slurp (io/file (toolbox/tools-dir dir) "twice.clj")))
          "the saved body is the repaired one, laid out (byj-gly.264)")
      (is (get-in (cmd "tool$validate" args) [:repaired :lines]))
      (let [create (:fn (first (toolbox/authoring-tools reg (toolbox/tools-dir dir))))]
        (is (str/includes? (:error (create (assoc args :name "off") {:config {:working-dir dir :tool-lisp-repair? false}}))
                           "do not balance"))))))

(deftest a-schema-malli-does-not-know-is-kept-as-text
  ;; the judge wrote `:output-schema :number`; kept as a form, every listing
  ;; that showed the tool threw (provenance P-21)
  ;; tool$create refuses one (byj-gly.263); a tool on disk or a proposal
  ;; accepted is compiled as it is
  (let [{:keys [reg]} (setup "bad-schema")
        t (:tool (toolbox/compile-tool reg {:name "total" :input-schema "[:map [:n :int]]" :output-schema :number
                                            :body "(fn [{:keys [n]}] n)"}))]
    (is (= "user$tool$total" (:name t)))
    (is (= ":number" (:output-schema t)) "kept as its text")
    (is (str/includes? (tools/format-tools-compact [t]) "Outputs: :number"))
    (is (nil? (schema/output-problems t 3)) "and nothing checks a result against it")
    (is (= ":number" (schema/output-text :number)) "a form that reached a listing some other way is shown as written")))

(deftest a-body-is-kept-laid-out
  ;; byj-gly.264: a model writes a body as one line; the file is read by a person
  (let [one-line (str "(fn [{:keys [project limit]}] (let [n (or limit 25) pres (mcp$linear$list_projects {:limit 50})] "
                      "(if (:error-message pres) pres {:projects (mapv (fn [p] {:project (:name p) "
                      ":issues (mcp$linear$list_issues {:project (:name p) :limit n :fields [\"title\" \"status\" \"assignee\" \"url\"]})}) "
                      "(:projects (json/read (:result pres))))})))")
        laid (tl/format-body one-line)]
    (is (= (str "(fn [{:keys [project limit]}]\n"
                "  (let [n (or limit 25)\n"
                "        pres (mcp$linear$list_projects {:limit 50})]\n"
                "    (if (:error-message pres)\n"
                "      pres\n"
                "      {:projects (mapv (fn [p]\n"
                "                         {:project (:name p)\n"
                "                          :issues (mcp$linear$list_issues\n"
                "                                    {:project (:name p)\n"
                "                                     :limit n\n"
                "                                     :fields [\"title\" \"status\" \"assignee\"\n"
                "                                              \"url\"]})})\n"
                "                       (:projects (json/read (:result pres))))})))")
           laid))
    (is (= (tl/read-body one-line) (tl/read-body laid)) "the same form")
    (is (= laid (tl/format-body laid)) "and laying it out again changes nothing")
    (is (every? #(<= (count %) 80) (str/split-lines laid))))
  (testing "a form that fits stays on one line"
    (is (= "(fn [a] (inc a))" (tl/format-body "(fn [a]\n   (inc a))"))))
  (testing "cond in pairs, #(…), 'x and @x written as they were"
    (is (= (str "(fn [a]\n"
                "  (cond\n"
                "    (nil? a) {:error-message \"a is required, and this message is long\"}\n"
                "    (re-find #\"x;y\" (:s a)) (map #(inc %) @(:v a))\n"
                "    :else '(1 2)))")
           (tl/format-body (str "(fn [a] (cond (nil? a) {:error-message \"a is required, and this message is long\"} "
                                "(re-find #\"x;y\" (:s a)) (map #(inc %) @(:v a)) :else '(1 2)))")))))
  (testing "text that reading would lose, or that does not read, is kept as it is"
    (doseq [s ["(fn [a] ;; the count\n (inc a))" "(fn [a] #_(dec a) (inc a))" "(fn [^String a] a)" "(fn [a] (inc a)"]]
      (is (= s (tl/format-body s)) s))))

(deftest create-persist-reload
  (let [{:keys [dir reg ctx cmd]} (setup "create")]
    (spit-in dir "a.txt" "x y\nz")
    (let [r (cmd "tool$create" {:name "count-lines" :description "Lines."
                                      :input-schema "[:map [:path [:string {:desc \"File\"}]]]"
                                      :output-schema "[:map [:lines :int]]"
                                      :body "(fn [{:keys [path]}] {:lines (count (clojure.string/split-lines (:content (read-file {:path path}))))})"})]
      (is (= {:id "user$tool$count-lines" :name "count-lines" :effects [:fs/read]}
             (select-keys r [:id :name :effects])))
      (is (.isFile (io/file (:persisted r))))
      (is (= (str "(fn [{:keys [path]}]\n"
                  "  {:lines (count (clojure.string/split-lines\n"
                  "                   (:content (read-file {:path path}))))})")
             (slurp (io/file (toolbox/tools-dir dir) "count-lines.clj")))
          "the body is kept in a sidecar, laid out (byj-gly.264)")
      (is (= [:map [:lines :int]] (:output-schema (edn/read-string (slurp (:persisted r)))))
          "the schema is persisted as the form, not the text it came as"))
    (testing "callable at once, and listed with its :output-schema"
      (is (= {:lines 2} (cmd "user$tool$count-lines" {:path "a.txt"})))
      (is (str/includes? (tools/format-tools [(get @reg "user$tool$count-lines")]) "Outputs: {:lines int}")))
    (testing "a fresh registry reloads it from disk"
      (let [reg2 (toolbox/registry tools/builtin-tools)]
        (is (= ["user$tool$count-lines"] (:loaded (toolbox/load-user-tools! reg2 (toolbox/tools-dir dir)))))
        (is (= {:lines 2} (tools/call-tool (get @reg2 "user$tool$count-lines") {:path "a.txt"} ctx)))))
    (testing "an invalid definition returns every problem and saves nothing"
      ;; an open string: no indentation can repair that (byj-gly.260)
      (let [r (cmd "tool$create" {:name "Bad Name" :body "(fn [a] (slurp \"a)"})]
        (is (str/starts-with? (:error r) "Invalid tool definition:"))
        (is (str/includes? (:error r) "lowercase-kebab"))
        (is (str/includes? (:error r) "do not balance")))
      (is (= {:error "Invalid tool definition:\n- `slurp` is not allowed in Tool Lisp"}
             (cmd "tool$create" {:name "bad" :body "(fn [a] (slurp a))"})))
      (is (not (.exists (io/file (toolbox/tools-dir dir) "bad.edn")))))))

(deftest composition-effects-and-cycles
  (let [{:keys [dir reg cmd]} (setup "compose")]
    (spit-in dir "a.txt" "one two")
    (cmd "tool$create" {:name "words" :input-schema "[:map [:path :string]]"
                              :body "(fn [{:keys [path]}] (count (clojure.string/split (:content (read-file {:path path})) #\" \")))"})
    (cmd "tool$create" {:name "stamp" :input-schema "[:map [:path :string]]"
                              :body "(fn [{:keys [path]}] (write-file {:path (str path \".count\") :content (str (user$tool$words {:path path}))}))"})
    (testing "a composite over a composite runs, and its effects are the union"
      (is (= {:path "a.txt.count" :chars 1} (cmd "user$tool$stamp" {:path "a.txt"})))
      (is (= "2" (slurp (io/file dir "a.txt.count"))))
      (is (= {:valid true :calls ["user$tool$words" "write-file"] :effects [:fs/read :fs/write]}
             (cmd "tool$validate" {:name "stamp" :input-schema "[:map [:path :string]]"
                                         :body (:body (get @reg "user$tool$stamp"))}))))
    (testing "argument checks against the called tool's params"
      (is (str/includes? (:error (cmd "tool$create" {:name "x" :body "(fn [a] (user$tool$words {:file 1}))"}))
                         "tool user$tool$words has no param `file`")))
    (testing "no tool may reach itself — directly or through another"
      (is (= {:error "Invalid tool definition:\n- a tool may not call itself: user$tool$loopy → user$tool$loopy"}
             (cmd "tool$create" {:name "loopy" :body "(fn [a] (user$tool$loopy a))"})))
      (is (= {:error "Invalid tool definition:\n- a tool may not call itself: user$tool$words → user$tool$stamp → user$tool$words"}
             (cmd "tool$create" {:name "words" :input-schema "[:map [:path :string]]"
                                       :body "(fn [{:keys [path]}] (user$tool$stamp {:path path}))"}))))
    (testing "read, list, test, delete"
      (is (= "(fn [{:keys [path]}]\n  (count (clojure.string/split (:content (read-file {:path path})) #\" \")))"
             (:body (cmd "tool$read" {:name "words"})))
          "the body as the file holds it, laid out")
      (is (= ["user$tool$stamp" "user$tool$words"] (map :id (:tools (cmd "tool$list" {})))))
      (is (= 2 (cmd "tool$test" {:name "words" :args {:path "a.txt"}})))
      (is (= {:deleted "words" :dependents ["user$tool$stamp"]} (cmd "tool$delete" {:name "words"})))
      (is (= {:error-message "Tool Lisp: no tool named user$tool$words"}
             (select-keys (cmd "user$tool$stamp" {:path "a.txt"}) [:error-message]))
          "a dependent of a deleted tool fails as a value, not a crash"))))

(deftest loading-resolves-dependency-order-and-skips-bad-files
  (let [{:keys [dir reg]} (setup "load-order")
        tdir (toolbox/tools-dir dir)]
    ;; alphabetically `aa` loads before `zz`, but calls it
    (spit-in (.getPath tdir) "aa.edn" "{:name \"aa\" :input-schema [:map] :body \"(fn [a] (user$tool$zz {}))\"}")
    (spit-in (.getPath tdir) "zz.edn" "{:name \"zz\" :input-schema [:map] :body \"(fn [a] 42)\"}")
    (spit-in (.getPath tdir) "broken.edn" "{:name \"broken\" :body \"(fn [a] (eval a))\"}")
    (spit-in (.getPath tdir) "garbage.edn" "{:name ")
    (let [{:keys [loaded skipped]} (toolbox/load-user-tools! reg tdir)]
      (is (= #{"user$tool$aa" "user$tool$zz"} (set loaded)))
      (is (= #{"broken.edn" "garbage.edn"} (set (map #(.getName (io/file (:file %))) skipped))))
      (is (= 42 (tools/call-tool (get @reg "user$tool$aa") {} {:config {:working-dir dir :project-dir dir}}))))))

;; --- in the loop -----------------------------------------------------------------------

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(deftest a-turn-defines-a-tool-then-uses-it
  (let [dir (fresh-dir "loop")
        _ (spit-in dir "log.txt" "a\nb\nc\n")
        prompts (atom [])
        replies [(reply {:tool-calls [{:tool-name "tool$create"
                                       :tool-args {:name "tail" :input-schema "[:map [:path :string] [:n :int]]"
                                                   :output-schema "[:map [:lines [:vector :string]]]"
                                                   :body "(fn [{:keys [path n]}] (let [ls (clojure.string/split-lines (:content (read-file {:path path})))] {:lines (vec (take-last n ls))}))"}}]})
                 (reply {:tool-calls [{:tool-name "user$tool$tail" :tool-args {:path "log.txt" :n 2}}]})
                 (reply {:answer "done"})]
        r (agent/ask "make a tail tool and use it"
                     {:lm {:provider :mock :format :mock
                           :responses (fn [msgs] (let [i (count @prompts)] (swap! prompts conj msgs) (nth replies i)))}
                      :config {:working-dir dir :project-dir dir}})]
    (is (= "done" (:answer r)))
    (testing "the tool created in iteration 1 was dispatched in iteration 2"
      (is (str/includes? (:content (second (nth @prompts 2))) ":lines [\\\"b\\\" \\\"c\\\"]")))
    (testing "the listing a turn starts with documents the commands and their reference"
      (let [system (:content (first (first @prompts)))]
        (is (str/includes? system "`tool$create`"))
        (is (str/includes? system "Tool Lisp"))
        (is (str/includes? system "Outputs: {:path string, :size int, :content string, :total-lines int"))))
    (testing "and the next turn starts with it listed"
      (let [seen (atom nil)]
        (agent/ask "q" {:lm {:provider :mock :format :mock
                             :responses (fn [msgs] (reset! seen (:content (first msgs))) (reply {:answer "ok"}))}
                        :config {:working-dir dir :project-dir dir}})
        (is (str/includes? @seen "`user$tool$tail`"))))))

(deftest tool-run-composes-once
  (let [{:keys [dir reg cmd]} (setup "run")]
    (spit-in dir "a.txt" "x\nTODO one\n")
    (spit-in dir "b.txt" "TODO two\nTODO three\n")
    (testing "a body runs over the registry with its args and returns its value; nothing is saved"
      (is (= {:todos 3}
             (cmd "tool$run" {:body "(fn [{:keys [files]}] {:todos (reduce + (map (fn [f] (count (re-seq #\"TODO\" (:content (read-file {:path f}))))) files))})"
                              :args {:files ["a.txt" "b.txt"]}})))
      (is (not (.exists (io/file dir ".brainyard/tools"))))
      (is (= 2 (cmd "tool$run" {:body "(fn [_] (count (:entries (list-dir {}))))"}))))
    (testing "every problem is reported at once"
      (let [{:keys [error]} (cmd "tool$run" {:body "(fn [_] (slurp \"x\") (read-file {:pth \"a\"}))"})]
        (is (str/starts-with? error "Invalid Tool Lisp:"))
        (is (str/includes? error "`slurp` is not allowed in Tool Lisp"))
        (is (str/includes? error "tool read-file has no param `pth`")))
      (is (str/includes? (:error (cmd "tool$run" {:body "(fn [_] (+ 1"})) "body delimiters do not balance")))
    (testing "a runtime failure is a value"
      (is (= {:error-message "Tool Lisp: more than 128 tool calls"}
             (cmd "tool$run" {:body "(fn [_] (mapv (fn [i] (list-dir {})) (range 200)))"}))))
    (testing "nested calls fire the tool hooks, so gates apply inside a body"
      (let [ctx {:config {:working-dir dir :project-dir dir} :tools reg
                 :hooks (hooks/registry [{:id "no-b" :event :agent.tool-use/pre
                                          :handler (fn [{:keys [args]} _]
                                                     (when (= "b.txt" (:path args))
                                                       {:result :replace :replacement {:error "b.txt is off limits"}}))}])}]
        (is (= [2 "b.txt is off limits"]
               (tools/call-tool (get @reg "tool$run")
                                {:body "(fn [_] [(count (clojure.string/split-lines (:content (read-file {:path \"a.txt\"})))) (:error (read-file {:path \"b.txt\"}))])"}
                                ctx)))))
    (testing ":tool-run? false leaves it out of the registry and the prompt"
      (let [{:keys [tools zones]} (agent/make-ctx {:config {:working-dir dir :project-dir dir :tool-run? false}})]
        (is (not (contains? @tools "tool$run")))
        (is (not (str/includes? (:session-context zones) "`tool$run`")))
        (is (contains? @(:tools (agent/make-ctx {:config {:working-dir dir :project-dir dir}})) "tool$run"))))))

(deftest a-cancelled-turn-stops-a-composite-and-its-children
  (let [dir (fresh-dir "cancel")
        marker (str "sleep 8." (rand-int 1000000))
        _ (spit-in (.getPath (toolbox/tools-dir dir)) "slow.edn"
                   (pr-str {:name "slow" :input-schema [:map] :body (str "(fn [a] (bash {:command \"" marker "\"}))")}))
        out (promise)
        cancel (fx/run (agent/ask-task "q" {:lm {:provider :mock :format :mock
                                                 :responses (fn [_] (reply {:tool-calls [{:tool-name "user$tool$slow" :tool-args {}}]}))}
                                            :config {:working-dir dir :project-dir dir :max-iterations 2}})
                       #(deliver out {:ok %}) #(deliver out {:err %}))
        alive? #(not (str/blank? (:out (p/sh "pgrep" "-f" marker))))
        wait (fn [pred] (loop [n 0] (cond (pred) true (> n 250) false :else (do (Thread/sleep 20) (recur (inc n))))))]
    (is (wait alive?) "non-vacuous: the composite's bash child was running")
    (cancel)
    (is (fx/cancelled? (:err (deref out 3000 {:timeout true}))))
    (is (wait #(not (alive?))) "the child died with the turn")))

(defn- code-symbols
  "Every symbol `form` could call or pass: a quoted form is data — Tool Lisp's
  own list of what it refuses names `eval` — so it is left out. jolt 0.8.10's
  postwalk never went into a set, which is how that list went unseen until
  0.8.15 walked it."
  [form]
  (let [syms (atom #{})]
    (clojure.walk/prewalk #(cond (and (seq? %) (= 'quote (first %))) nil
                                 (symbol? %) (do (swap! syms conj %) %)
                                 :else %)
                          form)
    @syms))

(deftest bodies-never-reach-host-eval
  (is (contains? (code-symbols '(fn [x] (eval x))) 'eval) "non-vacuous: a call is seen")
  (is (not (contains? (code-symbols '(def refused (quote #{eval}))) 'eval)) "a quoted name is data")
  (doseq [path ["src/brainyard/tool_lisp.clj" "src/brainyard/toolbox.clj"]]
    (let [syms (code-symbols (binding [*read-eval* false] (read-string (str "[" (slurp path) "\n]"))))]
      (testing path
        (is (empty? (filter '#{eval load-string load-file clojure.core/eval} syms)))))))

(deftest tool-run-carries-its-own-language-and-an-example-that-runs
  ;; it sent the model to tool$create's description for Tool Lisp, and a live
  ;; model looked that up (4,400 characters) before writing a line (byj-gly.242)
  (let [{:keys [dir reg cmd]} (setup "tool-run-guide")
        run-desc (:description (get @reg "tool$run"))
        create-desc (:description (get @reg "tool$create"))]
    (testing "tool$run says when to reach for it, and has the whole language"
      (is (str/includes? run-desc "Use it when"))
      (is (str/includes? run-desc "Tool Lisp:"))
      (is (str/includes? run-desc "Allowed functions: "))
      (is (not (str/includes? run-desc "the language tool$create describes"))))
    (testing "tool$create has the same language, between its args and how to check a body"
      (is (str/includes? create-desc "Tool Lisp:"))
      (is (< (str/index-of create-desc "Args:") (str/index-of create-desc "Tool Lisp:")
             (str/index-of create-desc "The body is validated"))))
    (testing "the example in the description is a body that runs"
      (spit-in dir "docs/a.md" "one\ntwo")
      (spit-in dir "docs/b.md" "one")
      (spit-in dir "docs/sub/c.md" "x")
      (let [example (->> (str/split-lines run-desc)
                         (drop-while #(not (str/starts-with? % "Example")))
                         rest
                         (take-while #(str/starts-with? % "  "))
                         (str/join "\n"))]
        (is (str/starts-with? (str/trim example) "(fn [_]"))
        (is (= {"a.md" 2 "b.md" 1} (cmd "tool$run" {:body example})))))))

(deftest a-body-reads-a-file-whole
  ;; the prompt's cap paged read-file inside a body too: numbered lines, the
  ;; first page only, and a body cannot loop to read on — haiku answered
  ;; "the longest line" from line 251 of 1,201 (byj-gly.243)
  (let [{:keys [dir cmd]} (setup "body-reads-whole")
        text (str (str/join "\n" (for [i (range 3000)] (str "row " i " of a long file"))) "\nTHE LAST LINE")]
    (spit-in dir "big.txt" text)
    (testing "a call from the model is paged, for its context"
      (let [r (cmd "read-file" {:path "big.txt"})]
        (is (:next-offset r))
        (is (not (str/includes? (:content r) "THE LAST LINE")))))
    (testing "and says how many lines, whole or paged, so a body need not guess which it got"
      (spit-in dir "small.txt" "a\nb\nc")
      (is (= 3 (:total-lines (cmd "read-file" {:path "small.txt"}))))
      (is (= 3001 (:total-lines (cmd "read-file" {:path "big.txt"})))))
    (testing "a call from a body gets the whole file, as text"
      (is (= "THE LAST LINE"
             (cmd "tool$run" {:body "(fn [_] (last (clojure.string/split-lines (:content (read-file {:path \"big.txt\"})))))"}))))
    (testing "and :tool-lisp-output-chars bounds it"
      (let [r (tools/call-tool (get @(:reg (setup "body-reads-whole-2")) "tool$run")
                               {:body "(fn [_] (:next-offset (read-file {:path \"big.txt\"})))"}
                               {:config {:working-dir dir :project-dir dir :tool-lisp-output-chars 20000}})]
        (is (pos-int? r))))))

(deftest the-composing-sections-example-runs
  ;; the prompt's Composing Tools section carries a body to copy, so the model
  ;; need not look tool$run up before its first call (byj-gly.244)
  (let [{:keys [dir cmd]} (setup "composing-example")
        section (slurp (io/resource "brainyard/prompts/tool-composition.md"))
        example (second (re-find #"(?s)```\n(.*?)```" section))]
    (spit-in dir "docs/a.md" "one\ntwo")
    (spit-in dir "docs/b.md" "one")
    (is (str/includes? (str example) "(fn [_]"))
    (is (= {"a.md" 2 "b.md" 1} (cmd "tool$run" {:body example})))))
