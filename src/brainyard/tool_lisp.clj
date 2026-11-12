;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tool-lisp
  "Tool Lisp — the language of composite tool bodies (RFC-010): an interpreted,
  allowlisted subset of Clojure that may call other tools. It replaces
  brainyard's user-tool bodies, which are Clojure source re-evaluated in SCI
  (`agent/common/user_tools.clj`); jolt has no SCI, and agent-written code must
  not reach host `eval`.

  A body is ONE form, `(fn [args] …)`. It is read with the Clojure reader and
  `*read-eval*` false — so `#(…)` and `#\"…\"` read, and `#=` is refused — then
  validated: every symbol must be a special form, an allowed function, a local,
  or a tool the caller knows. It is then INTERPRETED: nothing here calls `eval`.

  Evaluation is bounded (steps and tool calls), stops when its thread is
  interrupted (a cancelled turn), and string functions check their argument
  types — jolt's `clojure.string` accepts a map where the JVM would throw, which
  let a wrong body pass its own test (RFC-010 'Authoring evidence').

  This namespace knows nothing about how tools are found or dispatched: callers
  pass `tool?` (is this symbol a tool) to `validate` and `call-tool`
  (name args → result) to `run`."
  (:require [clojure.data]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.schema :as schema]))

;; ============================================================================
;; Reading
;; ============================================================================

(defn- line-col [s idx]
  (let [prefix (subs s 0 (min (count s) (max 0 idx)))]
    {:line (inc (count (filter #{\newline} prefix)))
     :col (inc (- (count prefix) (inc (or (str/last-index-of prefix "\n") -1))))}))

(defn- delimiter-problem
  "Where a body's delimiters stop balancing, as a readable hint, or nil. String
  and comment aware; enough to point a model at the spot (samizdat/lisp.clj
  does the full repair)."
  [s]
  (let [closer {\( \) \[ \] \{ \}}]
    (loop [i 0, stack [], in-str? false, esc? false]
      (if (>= i (count s))
        (when-let [[open at] (peek stack)]
          (let [{:keys [line col]} (line-col s at)]
            (str "unclosed `" open "` opened at line " line ", column " col)))
        (let [c (nth s i)]
          (cond
            esc? (recur (inc i) stack in-str? false)
            in-str? (recur (inc i) stack (not= c \") (= c \\))
            (= c \") (recur (inc i) stack true false)
            (= c \;) (recur (or (some-> (str/index-of s "\n" i) inc) (count s)) stack false false)
            (contains? closer c) (recur (inc i) (conj stack [c i]) false false)
            (contains? #{\) \] \}} c)
            (let [[open at] (peek stack)]
              (if (= c (get closer open))
                (recur (inc i) (pop stack) false false)
                (let [{:keys [line col]} (line-col s i)]
                  (str "unexpected `" c "` at line " line ", column " col
                       (when open (str " (expected `" (get closer open) "`)"))))))
            :else (recur (inc i) stack false false)))))))

;; --- repair (byj-gly.260) ---------------------------------------------------

(defn- lex-line
  "One line's delimiters and code, lexed from `in-str?` (whether the line starts
  inside a string). Returns {:tokens [[kind idx char]…] :in-str? at line end},
  kind :open, :close or :code; whitespace and a line comment are not tokens,
  and a delimiter inside a string or a char literal (`\\(`) is :code."
  [line in-str?]
  (let [n (count line)]
    (loop [i 0, in-str? in-str?, esc? false, toks []]
      (if (>= i n)
        {:tokens toks :in-str? in-str?}
        (let [c (nth line i)]
          (cond
            in-str? (recur (inc i) (or esc? (not= c \")) (and (not esc?) (= c \\))
                           (conj toks [:code i c]))
            (= c \\) (recur (+ i 2) false false (conj toks [:code i c]))
            (= c \") (recur (inc i) true false (conj toks [:code i c]))
            (= c \;) {:tokens toks :in-str? false}
            (#{\( \[ \{} c) (recur (inc i) false false (conj toks [:open i c]))
            (#{\) \] \}} c) (recur (inc i) false false (conj toks [:close i c]))
            (#{\space \tab \return \,} c) (recur (inc i) false false toks)
            :else (recur (inc i) false false (conj toks [:code i c]))))))))

(defn indent-repair
  "Rebuild a body's closing delimiters from its indentation, or nil.

  A model writes a body indented as it means it and miscounts the closers at the
  end of a line: of 25 unbalanced bodies haiku wrote in byj-gly.258's eval, the
  code was indented right in nearly every one and a line's run of closers was
  one short, one long, or in the wrong order. This is parinfer's indent mode on
  that one point, the approach samizdat's `samizdat.lisp/indent-repair` takes:
  each line's TRAILING closers are dropped, and a line indented at column x
  closes every form opened at column x or right of it, at the end of the last
  line of code before it. The last line closes what is still open.

  Only line-end closers change. Anything else — a closer in the middle of a
  line that closes the wrong thing, a line that starts with a closer and goes
  on, a string left open — is not a matter of indentation, and returns nil
  rather than a guess. Returns {:body s' :lines [{:line :was :now}…]}; whether
  s' reads is `repair-body`'s question."
  [s]
  (let [closer {\( \) \[ \] \{ \}}
        lines (str/split s #"\n" -1)]
    (loop [li 0, in-str? false, stack [], out (vec lines), trail-at {}, last-code nil]
      (if (>= li (count lines))
        (when (and (not in-str?) last-code)
          (let [tail (apply str (map (comp closer first) (rseq stack)))
                at (trail-at last-code)
                out (update out last-code #(str (subs % 0 at) tail (subs % at)))
                body (str/join "\n" out)]
            (when (not= body s)
              {:body body
               :lines (vec (for [i (range (count lines))
                                 :when (not= (nth lines i) (nth out i))]
                             {:line (inc i) :was (nth lines i) :now (nth out i)}))})))
        (let [line (nth lines li)
              {:keys [tokens] end-str? :in-str?} (lex-line line in-str?)
              trail (count (take-while #(= :close (first %)) (rseq tokens)))
              trail (if end-str? 0 trail)
              body-toks (subvec tokens 0 (- (count tokens) trail))
              code? (seq body-toks)
              indent (when (and code? (not in-str?)) (second (first tokens)))]
          (cond
            ;; a line that starts by closing and then goes on: no indentation says where
            (and code? (not in-str?) (= :close (ffirst tokens))) nil
            :else
            (let [;; dedent: what this line's indentation closes, innermost first
                  [stack out trail-at]
                  (if (and indent last-code)
                    (let [closing (vec (take-while #(>= (second %) indent) (rseq stack)))
                          tail (apply str (map (comp closer first) closing))
                          at (trail-at last-code)]
                      [(subvec stack 0 (- (count stack) (count closing)))
                       (update out last-code #(str (subs % 0 at) tail (subs % at)))
                       (update trail-at last-code + (count tail))])
                    [stack out trail-at])
                  ;; this line's own delimiters, up to its trail
                  stack (reduce (fn [st [kind i c]]
                                  (case kind
                                    :open (conj st [c i])
                                    :close (if (= c (closer (first (peek st)))) (pop st) (reduced nil))
                                    st))
                                stack body-toks)]
              (when stack
                (let [drop-idx (set (map second (take-last trail tokens)))
                      text (apply str (keep-indexed (fn [i c] (when-not (drop-idx i) c)) line))
                      ;; where closers go: where this line's own trail began,
                      ;; so a line whose trail was right comes back unchanged
                      at (when code? (if (pos? trail)
                                       (second (nth tokens (- (count tokens) trail)))
                                       (inc (second (peek body-toks)))))]
                  (recur (inc li) end-str? stack
                         (assoc out li text)
                         (cond-> trail-at at (assoc li at))
                         (if code? li last-code)))))))))))

(defn read-body
  "Read a body string into a form. Throws ex-info {:problems [...]} naming what
  is wrong and, for delimiter trouble, where."
  [s]
  (when-not (and (string? s) (not (str/blank? s)))
    (throw (ex-info "body must be a non-empty string" {:problems ["body must be a non-empty string holding (fn [args] ...)"]})))
  (when-let [d (delimiter-problem s)]
    (throw (ex-info "unbalanced body" {:problems [(str "body delimiters do not balance: " d)]})))
  (let [forms (try (binding [*read-eval* false]
                     (read-string (str "[" s "\n]")))
                   (catch Throwable e
                     (throw (ex-info "unreadable body"
                                     {:problems [(str "body does not read: " (ex-message e))]}))))]
    (case (count forms)
      1 (first forms)
      0 (throw (ex-info "empty body" {:problems ["body is empty"]}))
      (throw (ex-info "more than one form" {:problems [(str "body must be ONE form; found " (count forms))]})))))

;; brainyard's sandbox only appends the closers a body left open at its end,
;; silently (divergence `tool-lisp-indent-repair`).
(defn repair-body
  "`s` with its closers rebuilt from its indentation (`indent-repair`) when its
  delimiters do not balance and the rebuilt body reads as one form; else nil.
  The gate is samizdat's: a repair is kept only when it reads back — a body
  whose strings are escaped twice (`\\\".md\\\"`) rebuilds into one that still
  does not read, and stays refused (byj-gly.260)."
  [s]
  (when (and (string? s) (delimiter-problem s))
    (when-let [r (indent-repair s)]
      (when (try (read-body (:body r)) true (catch Exception _ false))
        r))))

;; ============================================================================
;; Layout — a body as it is kept on disk
;; ============================================================================
;; A model writes a body as one line; a person reads the file. jolt's
;; `pprint/code-dispatch` had no code table (byj-gly.264; jolt-lang/jolt#1177),
;; so this is one, cut to what Tool Lisp has. Since 0.8.15 it has one; tried
;; in its place, it lost `#(…)` bodies on read-back and laid `let` and calls
;; out less plainly, so this stays (byj-gly.265, RFC-010).

(def ^:private layout-width 80)

(def ^:private held
  "How many arguments a form keeps on its first line before the rest are
  indented two."
  '{fn 1 fn* 1 let 1 when-let 1 if-let 1 when 1 when-not 1 if 1 do 0})

(def ^:private binding-heads '#{let when-let if-let})

(defn- anon-params
  "A `#(…)` form's parameters to their `%` names by position, or nil when
  `form` is not one: the reader names them afresh on each read (`p__1#` here,
  `p1__123#` on the JVM)."
  [form]
  (when (and (seq? form) (= 'fn* (first form)) (vector? (second form)) (= 3 (count form)))
    (let [ps (second form)
          fixed (vec (take-while #(not= '& %) ps))
          rest-p (second (drop-while #(not= '& %) ps))]
      (when (every? #(and (symbol? %) (re-matches #"(p\d*|rest)__\d+#" (name %)))
                    (cond-> fixed rest-p (conj rest-p)))
        (cond-> (if (= 1 (count fixed))
                  {(first fixed) '%}
                  (zipmap fixed (map #(symbol (str "%" (inc %))) (range))))
          rest-p (assoc rest-p '%&))))))

(defn- anon-body [form] (walk/postwalk-replace (anon-params form) (nth form 2)))

(defn- prefixed
  "The reader-macro prefix `form` is written with — `'x`, `@x` — or nil."
  [form]
  (when (and (seq? form) (= 2 (count form)))
    ({'quote "'" 'clojure.core/deref "@" 'deref "@"} (first form))))

(declare flat)

(defn- flat-all [xs] (str/join " " (map flat xs)))

(defn- flat
  "`x` on one line."
  [x]
  (cond (anon-params x) (str "#" (flat (anon-body x)))
        (prefixed x) (str (prefixed x) (flat (second x)))
        (seq? x) (str "(" (flat-all x) ")")
        (vector? x) (str "[" (flat-all x) "]")
        (map? x) (str "{" (flat-all (apply concat x)) "}")
        (set? x) (str "#{" (flat-all x) "}")
        :else (pr-str x)))

(defn- nl [col] (str "\n" (apply str (repeat col " "))))

(defn- last-line-length [s] (count (peek (str/split s #"\n" -1))))

(declare lay)

;; `right` is how many closing delimiters follow a form on its last line — the
;; `]})` its enclosing forms end with — so that what "fits" counts them.

(defn- lay-each
  "`xs` each from column `c`, the last with `right` after it."
  [xs c right]
  (let [n (count xs)]
    (map-indexed (fn [i x] (lay x c (if (= i (dec n)) right 0))) xs)))

(defn- pair-lines
  "Key-value pairs one to a line from column `c`, each value after its key."
  [pairs c right]
  (let [n (count pairs)]
    (str/join (nl c) (map-indexed
                      (fn [i [k v :as pair]]
                        (let [r (if (= i (dec n)) right 0)]
                          (if (= 1 (count pair))
                            (lay k c r)
                            (let [fk (lay k c 0)]
                              (if (str/includes? fk "\n")
                                (str fk (nl c) (lay v c r))
                                (str fk " " (lay v (+ c (count fk) 1) r)))))))
                      pairs))))

(defn- items
  "One item to a line from one past `open` — or, when none of them is a
  collection, as many to a line as fit."
  [open close xs col right]
  (let [c (+ col (count open))
        right (+ right (count close))]
    (if (not-any? coll? xs)
      (let [fs (vec (map flat xs))
            last-i (dec (count fs))]
        ;; `at`: the column the text so far ends at
        (str open
             (first (reduce (fn [[s at] i]
                              (let [f (fs i)]
                                (if (<= (+ at 1 (count f) (if (= i last-i) right 0)) layout-width)
                                  [(str s " " f) (+ at 1 (count f))]
                                  [(str s (nl c) f) (+ c (count f))])))
                            [(fs 0) (+ c (count (fs 0)))]
                            (range 1 (count fs))))
             close))
      (str open (str/join (nl c) (lay-each xs c right)) close))))

(defn- lay-bindings
  "A binding vector: on one line when it holds one binding and fits, else a
  binding to a line."
  [v col right]
  (let [f (flat v)]
    (if (and (<= (count v) 2) (<= (+ col (count f) right) layout-width))
      f
      (str "[" (pair-lines (partition-all 2 v) (inc col) (inc right)) "]"))))

(defn- lay-call [[head & args :as x] col right]
  (let [fh (flat head)
        right (inc right)
        n (cond (and (= 'fn head) (symbol? (first args))) 2
                :else (get held head))
        indented (fn [xs] (apply str (map #(str (nl (+ col 2)) %) (lay-each xs (+ col 2) right))))]
    (cond
      (empty? args) (str "(" fh ")")
      n (let [[hold more] (split-at n args)
              line (reduce (fn [s [i a]]
                             (let [c (+ (if (str/includes? s "\n") 0 col) (last-line-length s) 1)
                                   r (if (and (empty? more) (= i (dec (count hold)))) right 0)]
                               (str s " " (if (and (binding-heads head) (vector? a)) (lay-bindings a c r) (lay a c r)))))
                           (str "(" fh) (map-indexed vector hold))]
          (str line (indented more) ")"))
      (= 'cond head) (str "(cond" (nl (+ col 2)) (pair-lines (partition-all 2 args) (+ col 2) right) ")")
      ;; the arguments under the first, unless that starts too far right
      (and (symbol? head) (<= (+ col 2 (count fh)) (quot layout-width 2)))
      (let [c (+ col 2 (count fh))]
        (str "(" fh " " (str/join (nl c) (lay-each args c right)) ")"))
      (symbol? head) (str "(" fh (indented args) ")")
      :else (items "(" ")" x col (dec right)))))

(defn- lay
  "`x` from column `col`, with `right` closers after it: on one line when it
  fits, else broken as Clojure is."
  [x col right]
  (let [f (flat x)]
    (if (<= (+ col (count f) right) layout-width)
      f
      (cond (anon-params x) (str "#" (lay (anon-body x) (inc col) right))
            (prefixed x) (str (prefixed x) (lay (second x) (+ col (count (prefixed x))) right))
            (seq? x) (lay-call x col right)
            (vector? x) (items "[" "]" x col right)
            (set? x) (items "#{" "}" x col right)
            (map? x) (str "{" (pair-lines (partition 2 (apply concat x)) (inc col) (inc right)) "}")
            :else f))))

(defn- lossy?
  "Does `s` hold what reading it drops — a comment, a `#_`, metadata?"
  [s]
  (loop [cs (seq s), in-str? false]
    (if-let [[c & more] cs]
      (cond in-str? (case c \\ (recur (next more) true) \" (recur more false) (recur more true))
            (= c \") (recur more true)
            (= c \\) (recur (next more) false)          ; a character literal: \; is no comment
            (#{\; \^} c) true
            (and (= c \#) (= \_ (first more))) true
            :else (recur more false))
      false)))

(defn- comparable
  "`form` with what differs between two reads of the same text made equal: a
  `#(…)`'s parameter names, and regexes, which are never `=`."
  [form]
  (walk/prewalk (fn [x] (cond (anon-params x) (list 'fn* (mapv #((anon-params x) % %) (second x)) (anon-body x))
                              (instance? java.util.regex.Pattern x) (list ::re (str x))
                              :else x))
                form))

(defn format-body
  "A body as it is kept on disk: laid out as Clojure is, to 80 columns — `fn`
  with its arguments, `let` a binding to a line, the arguments of a call under
  its first. `s` as it is when laying it out would lose what the text holds (a
  comment, a `#_`, metadata), when it does not read, or when what is laid out
  does not read back as the same form."
  [s]
  (try
    (if (lossy? s)
      s
      (let [form (read-body s)
            out (lay form 0 0)]
        (if (= (comparable form) (comparable (read-body out))) out s)))
    (catch Throwable _ s)))

;; ============================================================================
;; The language
;; ============================================================================

(def special-forms '#{fn fn* let if when when-not if-let when-let cond do and or -> ->> some->})

(def forbidden
  '#{def defn defn- defmacro loop recur eval load-string read-string load-file atom swap! reset!
     slurp spit future pmap binding letfn try catch throw new set! var quote import require ns
     alter-var-root intern resolve ns-resolve deref})

(defn- type-name [x]
  (cond (nil? x) "nil" (map? x) "a map" (vector? x) "a vector" (sequential? x) "a list"
        (set? x) "a set" (keyword? x) "a keyword" (number? x) "a number" (boolean? x) "a boolean"
        :else (str (type x))))

(defn- strict
  "Wrap a string function so a non-string first argument fails loudly."
  [op-name f]
  (fn [s & more]
    (when-not (string? s)
      (throw (ex-info (str op-name " expects a string, got " (type-name s)
                           (when (map? s) (str " with keys " (vec (keys s)))))
                      {:kind :type})))
    (apply f s more)))

(defn- strict-second
  "Wrap a function whose second argument must be a string (re-find, re-seq …)."
  [op-name f]
  (fn [a s & more]
    (when-not (string? s)
      (throw (ex-info (str op-name " expects a string as its second argument, got " (type-name s)) {:kind :type})))
    (apply f a s more)))

(def max-generated
  "The most elements `range` or `repeat` may produce. The step budget meters
  interpretation, not work inside an allowed function, so a generator is the one
  place a short body could ask for unbounded work."
  1000000)

(defn- bounded-generator [op-name f count-of]
  (fn [& args]
    (let [n (count-of args)]
      (when (or (nil? n) (> n max-generated))
        (throw (ex-info (str op-name " may produce at most " max-generated " elements") {:kind :budget})))
      (vec (apply f args)))))

(def ops
  "The allowlist: name → function. Sequence functions are EAGER (they return
  vectors), so nothing lazy escapes a body and no tool call runs during a later
  realization."
  (let [v (fn [f] (comp vec f))]
    {'get get 'get-in get-in 'assoc assoc 'assoc-in assoc-in 'dissoc dissoc 'update update
     'update-in update-in 'merge merge 'merge-with merge-with 'select-keys select-keys
     'keys (v keys) 'vals (v vals) 'count count 'first first 'second second 'last last
     'rest (v rest) 'next (v next) 'butlast (v butlast) 'nth nth 'take (v take) 'drop (v drop)
     'take-last (v take-last) 'drop-last (v drop-last) 'take-while (v take-while) 'drop-while (v drop-while)
     'conj conj 'cons (v cons) 'into into 'vec vec 'set set 'list (v list) 'hash-map hash-map
     'concat (v concat) 'distinct (v distinct) 'sort (v sort) 'sort-by (v sort-by)
     'reverse (v reverse) 'frequencies frequencies 'group-by group-by 'partition (v partition)
     'partition-all (v partition-all) 'interleave (v interleave) 'interpose (v interpose)
     'zipmap zipmap 'flatten (v flatten) 'empty? empty? 'not-empty not-empty 'seq seq
     'contains? contains?
     'range (bounded-generator "range" range
                               (fn [[a b c]] (case (count (remove nil? [a b c]))
                                               1 a
                                               2 (- b a)
                                               (when (and c (not (zero? c))) (quot (- b a) c)))))
     'repeat (bounded-generator "repeat" repeat (fn [args] (when (= 2 (count args)) (first args))))
     'map (v map) 'mapv mapv 'map-indexed (v map-indexed) 'filter (v filter) 'filterv filterv
     'remove (v remove) 'keep (v keep) 'mapcat (v mapcat) 'reduce reduce 'reduce-kv reduce-kv
     'some some 'every? every? 'not-any? not-any? 'apply apply 'comp comp 'partial partial
     'juxt juxt 'complement complement 'constantly constantly 'identity identity
     'max-key max-key 'min-key min-key
     'str str 'subs (strict "subs" subs) 'format format 'pr-str pr-str 'name name 'keyword keyword
     'symbol symbol 'parse-long (strict "parse-long" parse-long) 'parse-double (strict "parse-double" parse-double)
     'clojure.string/split (strict "clojure.string/split" str/split)
     'clojure.string/split-lines (strict "clojure.string/split-lines" str/split-lines)
     'clojure.string/join str/join
     'clojure.string/upper-case (strict "clojure.string/upper-case" str/upper-case)
     'clojure.string/lower-case (strict "clojure.string/lower-case" str/lower-case)
     'clojure.string/capitalize (strict "clojure.string/capitalize" str/capitalize)
     'clojure.string/trim (strict "clojure.string/trim" str/trim)
     'clojure.string/triml (strict "clojure.string/triml" str/triml)
     'clojure.string/trimr (strict "clojure.string/trimr" str/trimr)
     'clojure.string/blank? (fn [s] (when-not (or (nil? s) (string? s))
                                      (throw (ex-info (str "clojure.string/blank? expects a string, got " (type-name s)) {:kind :type})))
                              (str/blank? s))
     'clojure.string/includes? (strict "clojure.string/includes?" str/includes?)
     'clojure.string/starts-with? (strict "clojure.string/starts-with?" str/starts-with?)
     'clojure.string/ends-with? (strict "clojure.string/ends-with?" str/ends-with?)
     'clojure.string/index-of (strict "clojure.string/index-of" str/index-of)
     'clojure.string/replace (strict "clojure.string/replace" str/replace)
     'clojure.string/replace-first (strict "clojure.string/replace-first" str/replace-first)
     'clojure.string/reverse (strict "clojure.string/reverse" str/reverse)
     're-pattern re-pattern 're-find (strict-second "re-find" re-find)
     're-seq (comp vec (strict-second "re-seq" re-seq)) 're-matches (strict-second "re-matches" re-matches)
     '+ + '- - '* * '/ / 'inc inc 'dec dec 'min min 'max max 'quot quot 'rem rem 'mod mod 'abs abs
     '= = 'not= not= '< < '> > '<= <= '>= >= 'not not 'zero? zero? 'pos? pos? 'neg? neg?
     'even? even? 'odd? odd? 'nil? nil? 'some? some? 'true? true? 'false? false?
     'string? string? 'number? number? 'int? int? 'keyword? keyword? 'map? map? 'vector? vector?
     'coll? coll? 'sequential? sequential? 'boolean? boolean?
     'json/read (strict "json/read" json/read-str) 'json/write json/write-str}))

;; ============================================================================
;; Extensions (byj-gly.258) — what an agent's own tools reach for, by tier
;; ============================================================================

(defonce ^{:doc "The extensions in force for this run: a set of :vocabulary
  (pure functions and namespaces SCI ships by default), :forms (macros the
  interpreter evaluates directly: for, doseq, case, cond-> …), :control
  (loop/recur, try/catch/throw, letfn and named fns) and :reach (bounded
  `pmap`, a clock — byj-gly.261). Set once per run from config
  (`:tool-lisp-extensions`), since config is run-wide."}
  extensions (atom #{}))

(defn set-extensions!
  "Put `xs` — keywords, or their names — in force."
  [xs]
  (reset! extensions (set (map keyword xs))))

(defn- ext? [k] (contains? @extensions k))

(def vocabulary-ops
  "Tier A: pure functions. Sequence results are EAGER, as the base list's are."
  (let [v (fn [f] (comp vec f))]
    {'fnil fnil 'compare compare 'keep-indexed (v keep-indexed) 'some-fn some-fn 'every-pred every-pred
     'disj disj 'peek peek 'pop pop 'subvec subvec 'vector vector
     'split-at (comp (partial mapv vec) split-at) 'split-with (comp (partial mapv vec) split-with)
     'partition-by (comp (partial mapv vec) partition-by) 'dedupe (v dedupe)
     'update-vals update-vals 'update-keys update-keys 'find find 'key key 'val val
     'not-every? not-every? 'boolean boolean 'int int 'long long 'double double
     'set? set? 'integer? integer? 'tree-seq (v tree-seq) 'run! run!
     'clojure.string/last-index-of (strict "clojure.string/last-index-of" str/last-index-of)
     'clojure.set/union set/union 'clojure.set/intersection set/intersection
     'clojure.set/difference set/difference 'clojure.set/subset? set/subset?
     'clojure.set/superset? set/superset? 'clojure.set/rename-keys set/rename-keys
     'clojure.set/map-invert set/map-invert 'clojure.set/select set/select
     'clojure.set/project set/project 'clojure.set/index set/index
     'clojure.walk/postwalk walk/postwalk 'clojure.walk/prewalk walk/prewalk
     'clojure.walk/keywordize-keys walk/keywordize-keys 'clojure.walk/stringify-keys walk/stringify-keys
     'clojure.edn/read-string (strict "clojure.edn/read-string" #(edn/read-string {:readers {} :default (fn [_ x] x)} %))
     'clojure.data/diff (comp vec clojure.data/diff)
     'ex-message ex-message 'ex-data ex-data}))

(def control-ops
  "Tier C's one function: the value `throw` throws."
  {'ex-info ex-info})

(def ^:dynamic *parallel*
  "How many of a `pmap`'s calls run at once — the body budget's `:parallel`."
  4)

(def max-parallel-items
  "The most items one `pmap` takes. Each is a task, not a step, so the step
  budget does not bound how many are made."
  1000)

(defn- bounded-pmap
  "Tier D's `pmap`: `f` over the items of `colls`, at most `*parallel*` at a
  time, in input order, eagerly — `fx/bounded`, as the tool dispatch runs a
  batch. Each call runs on its own thread with the body's bindings, so the
  steps, the tool calls and the nesting depth it spends are the body's. A
  failure cancels its siblings and is thrown here; a cancelled turn (this
  thread interrupted) cancels them all (RFC-009)."
  [f & colls]
  (let [xs (vec (apply map vector colls))]
    (when (> (count xs) max-parallel-items)
      (throw (ex-info (str "pmap takes at most " max-parallel-items " items") {:kind :budget})))
    (fx/run-value!! (fx/bounded *parallel* (mapv (fn [args] (fx/task-of #(apply f args))) xs)))))

(def reach-ops
  "Tier D (byj-gly.261): parallel calls and a clock. State across calls is not
  here: it is the `store$*` tools, whose effects are declared."
  {'pmap bounded-pmap
   'now (fn [] (str (java.time.Instant/now)))
   'now-ms (fn [] (System/currentTimeMillis))})

(def clock-symbols
  "What reads the clock: a body that names one cannot be proved by replaying
  it (`tool-distill/prove`), so it carries `:clock/read`."
  '#{now now-ms})

(defn body-effects
  "The effects a body has of its own, beside those of the tools it calls:
  `#{:clock/read}` when it names the clock. A local of that name counts too —
  the answer is conservative, never short."
  [form]
  (if (some clock-symbols (filter symbol? (tree-seq coll? seq form)))
    #{:clock/read}
    #{}))

(def form-heads
  "Tier B: special forms the interpreter evaluates directly."
  '#{for doseq dotimes case condp cond-> cond->> as-> some->> if-not if-some when-some when-first})

(def control-heads
  "Tier C: bounded control. Loops and recursion spend the same step budget
  as anything else; a catch never catches a cancelled turn or an exhausted
  budget."
  '#{loop recur try catch finally throw letfn})

(def catchable-classes
  "What a catch may name: every error a body can raise, or only an ex-info."
  '#{Exception Throwable RuntimeException :default ExceptionInfo clojure.lang.ExceptionInfo})

(defn active-ops []
  (cond-> ops
    (ext? :vocabulary) (merge vocabulary-ops)
    (ext? :control) (merge control-ops)
    (ext? :reach) (merge reach-ops)))

(defn- active-special-forms []
  (cond-> special-forms
    (ext? :forms) (into form-heads)
    (ext? :control) (into control-heads)))

(defn- active-forbidden []
  (cond->> forbidden
    (ext? :control) (remove control-heads)
    (ext? :reach) (remove '#{pmap})
    true set))

(defn allowed-function-names
  "The allowlist as sorted names, for the reference the model reads."
  []
  (sort (map str (keys (active-ops)))))

;; ============================================================================
;; Validation
;; ============================================================================

(defn- pattern-locals [pat]
  (cond
    (symbol? pat) (if (= '& pat) #{} #{pat})
    (vector? pat) (reduce into #{} (map pattern-locals pat))
    (map? pat) (into (set (concat (map #(symbol (name %)) (:keys pat)) (when (:as pat) [(:as pat)])))
                     (mapcat pattern-locals (remove keyword? (keys pat))))
    :else #{}))

(defn validate
  "Problems with `form` as a body, or nil. `tool` is (fn [symbol]) → the tool's
  spec {:input-schema …} when the symbol names a tool, else nil."
  [form tool]
  (let [problems (atom [])
        problem! #(swap! problems conj %)]
    (letfn [(symbol-ok? [s locals]
              (or (contains? locals s) (contains? (active-ops) s) (contains? (active-special-forms) s) (tool s)))
            (bad-symbol [s]
              (cond
                (contains? (active-forbidden) s) (str "`" s "` is not allowed in Tool Lisp")
                (str/starts-with? (name s) ".") (str "`" s "`: Java interop is not allowed")
                (and (namespace s) (re-find #"^[A-Z]" (namespace s))) (str "`" s "`: Java interop is not allowed")
                :else (str "unknown symbol `" s "` — not a local, an allowed function, or a tool")))
            (check-tool-call [s spec [arg]]
              (when (map? arg)
                (let [inputs (schema/inputs spec)
                      known (set (map (comp name :name) inputs))
                      given (set (map name (filter keyword? (keys arg))))]
                  (doseq [k (sort (remove known given))]
                    (problem! (str "tool " s " has no param `" k "`"
                                   (when (seq known) (str " (params: " (str/join ", " (sort known)) ")")))))
                  (doseq [{k :name :keys [optional?]} inputs
                          :when (and (not optional?) (not (contains? given (name k))))]
                    (problem! (str "tool " s " is called without its required param `" (name k) "`"))))))
            (walk-body [body locals] (doseq [b body] (walk b locals)))
            (walk [f locals]
              (cond
                (symbol? f) (when-not (symbol-ok? f locals) (problem! (bad-symbol f)))
                (seq? f)
                (let [[head & args] f]
                  (cond
                    (contains? '#{fn fn*} head)
                    (let [named (when (symbol? (first args)) (first args))
                          args (if named
                                 (do (when-not (ext? :control)
                                       (problem! "named fns are not allowed (no recursion)"))
                                     (rest args))
                                 args)
                          [params & body] args]
                      (if (vector? params)
                        (walk-body body (cond-> (into locals (pattern-locals params)) named (conj named)))
                        (problem! "fn needs a parameter vector (multi-arity fns are not allowed)")))

                    ;; tier B and C binding forms (byj-gly.258)
                    (and (contains? '#{for doseq} head) (ext? :forms))
                    (let [[bindings & body] args]
                      (if-not (and (vector? bindings) (even? (count bindings)))
                        (problem! (str head " needs a vector of binding pairs"))
                        (walk-body body (reduce (fn [ls [k v]]
                                                  (cond
                                                    (= :let k) (reduce (fn [ls2 [p x]] (walk x ls2) (into ls2 (pattern-locals p)))
                                                                       ls (partition 2 v))
                                                    (keyword? k) (do (walk v ls) ls)
                                                    :else (do (walk v ls) (into ls (pattern-locals k)))))
                                                locals (partition 2 bindings)))))

                    (and (contains? '#{if-some when-some when-first dotimes} head) (ext? :forms))
                    (let [[[p v] & _] (partition 2 (first args))]
                      (walk v locals)
                      (walk-body (rest args) (into locals (pattern-locals p))))

                    (and (= 'case head) (ext? :forms))
                    (let [[e & clauses] args]
                      (walk e locals)
                      (doseq [r (concat (take-nth 2 (rest clauses)) (when (odd? (count clauses)) [(last clauses)]))]
                        (walk r locals)))

                    (and (= 'as-> head) (ext? :forms))
                    (let [[e nm & forms] args]
                      (walk e locals)
                      (walk-body forms (conj locals nm)))

                    (and (= 'loop head) (ext? :control))
                    (let [[bindings & body] args]
                      (if-not (and (vector? bindings) (even? (count bindings)))
                        (problem! "loop needs a vector of binding pairs")
                        (walk-body body (reduce (fn [ls [pat v]] (walk v ls) (into ls (pattern-locals pat)))
                                                locals (partition 2 bindings)))))

                    (and (= 'letfn head) (ext? :control))
                    (let [[specs & body] args
                          ls (into locals (map first specs))]
                      (doseq [[_ params & fbody] specs]
                        (if (vector? params)
                          (walk-body fbody (into ls (pattern-locals params)))
                          (problem! "letfn needs (name [params] body) specs")))
                      (walk-body body ls))

                    (and (= 'try head) (ext? :control))
                    (doseq [x args]
                      (if (and (seq? x) (= 'catch (first x)))
                        (let [[_ cls e & cbody] x]
                          (when-not (contains? catchable-classes cls)
                            (problem! (str "catch " cls ": catch Exception (anything a body can raise) or ExceptionInfo")))
                          (walk-body cbody (conj locals e)))
                        (walk x locals)))

                    (contains? '#{let if-let when-let} head)
                    (let [[bindings & body] args]
                      (if-not (and (vector? bindings) (even? (count bindings)))
                        (problem! (str head " needs a vector of binding pairs"))
                        (walk-body body (reduce (fn [ls [pat v]] (walk v ls) (into ls (pattern-locals pat)))
                                                locals (partition 2 bindings)))))

                    (and (symbol? head) (not (contains? locals head)) (tool head))
                    (do (check-tool-call head (tool head) args)
                        (walk-body args locals))

                    :else
                    (do (cond
                          (symbol? head) (walk head locals)
                          (or (keyword? head) (seq? head) (map? head) (set? head)) (walk head locals)
                          :else (problem! (str "cannot call " (pr-str head))))
                        (walk-body args locals))))
                (map? f) (doseq [[k v] f] (walk k locals) (walk v locals))
                (coll? f) (doseq [x f] (walk x locals))
                :else nil))]
      (if (and (seq? form) (contains? '#{fn fn*} (first form)) (vector? (second form)))
        (walk form #{})
        (problem! "body must be a single (fn [args] ...) form")))
    (not-empty (vec (distinct @problems)))))

(defn tool-refs
  "Every symbol in call position (or passed as a value) that `tool` recognizes —
  the static call graph edge set. Locals shadowing a tool name are not refs."
  [form tool]
  (let [refs (atom #{})]
    (letfn [(walk [f locals]
              (cond
                (symbol? f) (when (and (not (contains? locals f)) (tool f)) (swap! refs conj f))
                (seq? f)
                (let [[head & args] f]
                  (cond
                    (contains? '#{fn fn*} head)
                    (let [[params & body] (if (symbol? (first args)) (rest args) args)]
                      (doseq [b body] (walk b (into locals (pattern-locals params)))))
                    (contains? '#{let if-let when-let} head)
                    (let [[bindings & body] args
                          ls (reduce (fn [ls [pat v]] (walk v ls) (into ls (pattern-locals pat)))
                                     locals (partition 2 bindings))]
                      (doseq [b body] (walk b ls)))
                    :else (doseq [x f] (walk x locals))))
                (map? f) (doseq [[k v] f] (walk k locals) (walk v locals))
                (coll? f) (doseq [x f] (walk x locals))
                :else nil))]
      (walk form #{}))
    @refs))

;; ============================================================================
;; Evaluation
;; ============================================================================

(def ^:dynamic *budget* nil)

(defn- burn! []
  (when (Thread/interrupted)
    (throw (InterruptedException. "Tool Lisp evaluation interrupted")))
  (when (neg? (:steps (swap! *budget* update :steps dec)))
    (throw (ex-info "Tool Lisp: evaluation step budget exhausted" {:kind :budget}))))

(defn- bind [pat v]
  (cond
    (symbol? pat) {pat v}
    (vector? pat) (let [[fixed [_ rest-sym]] (split-with #(not= '& %) pat)
                        xs (vec v)]
                    (merge (apply merge {} (map-indexed (fn [i p] (bind p (get xs i))) fixed))
                           (when rest-sym (bind rest-sym (vec (drop (count fixed) xs))))))
    (map? pat) (merge (into {} (for [k (:keys pat)
                                     :let [sym (symbol (name k))]]
                                 [sym (get v (keyword (name k)) (get (:or pat) sym))]))
                      (when (:as pat) {(:as pat) v})
                      (apply merge {} (for [[p k] pat :when (not (keyword? p))] (bind p (get v k)))))
    :else {}))

(declare ev)

(defn- recur? [x] (and (map? x) (contains? x ::recur)))

(defn- uncatchable?
  "What a body's catch never catches: a cancelled turn, an interrupted thread,
  an exhausted budget. Catching them would let a body outlive its turn or its
  bounds."
  [t]
  (or (instance? InterruptedException t)
      (fx/cancelled? t)
      (= :budget (:kind (ex-data t)))))

(defn- catches? [cls t]
  (if (contains? '#{ExceptionInfo clojure.lang.ExceptionInfo} cls)
    (some? (ex-data t))
    true))

(defn- ev-comprehension
  "`for`/`doseq`'s bindings, eagerly: each seq binding, `:when`, `:while` (as
  `:when`) and `:let`. `emit` turns one innermost env into a seq of results."
  [bindings env call emit]
  (letfn [(step [pairs e]
            (if (empty? pairs)
              (emit e)
              (let [[k v] (first pairs) more (rest pairs)]
                (cond
                  (#{:when :while} k) (if (ev v e call) (step more e) [])
                  (= :let k) (step more (reduce (fn [e2 [p x]] (merge e2 (bind p (ev x e2 call)))) e (partition 2 v)))
                  :else (vec (mapcat (fn [x] (step more (merge e (bind k x)))) (ev v e call)))))))]
    (step (partition 2 bindings) env)))

(defn- thread-form [x steps last?]
  (reduce (fn [acc step]
            (cond
              (seq? step) (if last? (concat step [acc]) (list* (first step) acc (rest step)))
              :else (list step acc)))
          x steps))

(defn- ev-body [body env call]
  (reduce (fn [_ b] (ev b env call)) nil body))

(defn- ev-let [bindings body env call]
  (ev-body body (reduce (fn [e [p v]] (merge e (bind p (ev v e call)))) env (partition 2 bindings)) call))

(defn ev
  "Evaluate `form` in `env` (symbol → value). `call` is (fn [tool-symbol args])."
  [form env call]
  (burn!)
  (cond
    (symbol? form) (cond
                     (contains? env form) (get env form)
                     (contains? (active-ops) form) (get (active-ops) form)
                     :else (fn [& args] (call form (if (map? (first args)) (first args) (apply hash-map args)))))
    (vector? form) (mapv #(ev % env call) form)
    (map? form) (into {} (map (fn [[k v]] [(ev k env call) (ev v env call)])) form)
    (set? form) (set (map #(ev % env call) form))
    (seq? form)
    (let [[head & args] form]
      (if (and (symbol? head) (contains? env head))
        (apply (get env head) (mapv #(ev % env call) args))
        (case head
          (fn fn*) (let [named (when (symbol? (first args)) (first args))
                         [params & body] (if named (rest args) args)
                         self (volatile! nil)
                         f (fn [& xs]
                             (loop [xs xs]
                               (let [r (ev-body body (cond-> (merge env (bind params (vec xs)))
                                                       named (assoc named @self))
                                                call)]
                                 (if (recur? r) (recur (::recur r)) r))))]
                     (vreset! self f)
                     f)
          let (ev-let (first args) (rest args) env call)
          if (if (ev (first args) env call) (ev (second args) env call) (ev (nth args 2 nil) env call))
          when (when (ev (first args) env call) (ev-body (rest args) env call))
          when-not (when-not (ev (first args) env call) (ev-body (rest args) env call))
          if-let (let [[[p v] & _] (partition 2 (first args))
                       x (ev v env call)]
                   (if x (ev (second args) (merge env (bind p x)) call) (ev (nth args 2 nil) env call)))
          when-let (let [[[p v] & _] (partition 2 (first args))
                         x (ev v env call)]
                     (when x (ev-body (rest args) (merge env (bind p x)) call)))
          cond (loop [[t e & more] args]
                 (cond (and (nil? t) (nil? e)) nil
                       (or (= :else t) (ev t env call)) (ev e env call)
                       :else (recur more)))
          do (ev-body args env call)
          and (reduce (fn [_ a] (let [x (ev a env call)] (if x x (reduced x)))) true args)
          or (reduce (fn [_ a] (let [x (ev a env call)] (if x (reduced x) x))) nil args)
          -> (ev (thread-form (first args) (rest args) false) env call)
          ->> (ev (thread-form (first args) (rest args) true) env call)
          some-> (reduce (fn [acc step] (if (nil? acc) (reduced nil)
                                            (ev (thread-form (list 'quote* acc) [step] false) env call)))
                         (ev (first args) env call) (rest args))
          quote* (first args)
          ;; tier B (byj-gly.258)
          if-not (if (ev (first args) env call) (ev (nth args 2 nil) env call) (ev (second args) env call))
          (if-some when-some) (let [[[p v] & _] (partition 2 (first args))
                                    x (ev v env call)]
                                (if (some? x)
                                  (ev-body (if (= 'if-some head) [(second args)] (rest args)) (merge env (bind p x)) call)
                                  (when (= 'if-some head) (ev (nth args 2 nil) env call))))
          when-first (let [[[p v] & _] (partition 2 (first args))
                           xs (seq (ev v env call))]
                       (when xs (ev-body (rest args) (merge env (bind p (first xs))) call)))
          for (vec (ev-comprehension (first args) env call (fn [e] [(ev-body (rest args) e call)])))
          doseq (do (dorun (ev-comprehension (first args) env call (fn [e] (ev-body (rest args) e call) nil))) nil)
          dotimes (let [[[p n] & _] (partition 2 (first args))
                        n (ev n env call)]
                    (when (> n max-generated)
                      (throw (ex-info (str "dotimes may run at most " max-generated " times") {:kind :budget})))
                    (dotimes [i n] (ev-body (rest args) (merge env (bind p i)) call)))
          case (let [[e & clauses] args
                     v (ev e env call)
                     pairs (partition 2 clauses)
                     hit (some (fn [[t r]] (when (if (seq? t) (some #(= v %) t) (= v t)) [r])) pairs)]
                 (cond hit (ev (first hit) env call)
                       (odd? (count clauses)) (ev (last clauses) env call)
                       :else (throw (ex-info (str "case: no clause matches " (pr-str v)) {:kind :type}))))
          condp (let [[pred e & clauses] args
                      pf (ev pred env call)
                      v (ev e env call)
                      hit (some (fn [[t r]] (when (pf (ev t env call) v) [r])) (partition 2 clauses))]
                  (cond hit (ev (first hit) env call)
                        (odd? (count clauses)) (ev (last clauses) env call)
                        :else (throw (ex-info (str "condp: no clause matches " (pr-str v)) {:kind :type}))))
          (cond-> cond->>) (reduce (fn [acc [t step]]
                                     (if (ev t env call)
                                       (ev (thread-form (list 'quote* acc) [step] (= 'cond->> head)) env call)
                                       acc))
                                   (ev (first args) env call) (partition 2 (rest args)))
          as-> (let [[e nm & forms] args]
                 (reduce (fn [acc f] (ev f (assoc env nm acc) call)) (ev e env call) forms))
          some->> (reduce (fn [acc step] (if (nil? acc) (reduced nil)
                                             (ev (thread-form (list 'quote* acc) [step] true) env call)))
                          (ev (first args) env call) (rest args))
          ;; tier C (byj-gly.258)
          loop (let [pairs (partition 2 (first args))
                     init (reduce (fn [e [p v]] (merge e (bind p (ev v e call)))) env pairs)]
                 (loop [e init]
                   (let [r (ev-body (rest args) e call)]
                     (if (recur? r)
                       (recur (reduce (fn [e2 [[p _] x]] (merge e2 (bind p x))) e (map vector pairs (::recur r))))
                       r))))
          recur {::recur (mapv #(ev % env call) args)}
          letfn (let [[specs & body] args
                      box (volatile! env)
                      fns (into {} (for [[nm params & fbody] specs]
                                     [nm (fn [& xs]
                                           (loop [xs xs]
                                             (let [r (ev-body fbody (merge @box (bind params (vec xs))) call)]
                                               (if (recur? r) (recur (::recur r)) r))))]))]
                  (vreset! box (merge env fns))
                  (ev-body body @box call))
          throw (let [x (ev (first args) env call)]
                  (throw (if (instance? Throwable x) x (ex-info (str x) {}))))
          try (let [clause? (fn [x k] (and (seq? x) (= k (first x))))
                    catches (filter #(clause? % 'catch) args)
                    fin (first (filter #(clause? % 'finally) args))
                    body (remove #(or (clause? % 'catch) (clause? % 'finally)) args)]
                (try (ev-body body env call)
                     (catch Throwable t
                       (if (uncatchable? t)
                         (throw t)
                         (if-let [[_ _ e & cbody] (first (filter (fn [[_ cls]] (catches? cls t)) catches))]
                           (ev-body cbody (assoc env e t) call)
                           (throw t))))
                     (finally (when fin (ev-body (rest fin) env call)))))
          (let [f (ev head env call)]
            (apply f (mapv #(ev % env call) args))))))
    :else form))

(def default-budget {:steps 200000 :tool-calls 128 :parallel 4})

(defn run
  "Run a validated body `form` on `args`. `call-tool` is (fn [name-string args])
  → result. Returns the body's value; throws ex-info {:kind :budget|:type …} on
  a runtime error, and InterruptedException when the thread is interrupted."
  ([form args call-tool] (run form args call-tool nil))
  ([form args call-tool budget]
   (let [{:keys [steps tool-calls parallel]} (merge default-budget budget)
         calls (atom 0)]
     (binding [*budget* (atom {:steps steps})
               *parallel* (max 1 (or parallel 1))]
       (let [call (fn [sym args]
                    (when (> (swap! calls inc) tool-calls)
                      (throw (ex-info (str "Tool Lisp: more than " tool-calls " tool calls") {:kind :budget})))
                    (call-tool (str sym) args))
             f (ev form {} call)]
         (f args))))))
