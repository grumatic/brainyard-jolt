`tool$run` runs a small program — a Tool Lisp body, `(fn [args] ...)` — once.
Inside it every tool is a function: `(read-file {:path p})` returns the map a
call to `read-file` would, but whole — a large file is not paged, since no
prompt has to hold it. What the body reads stays inside it; only its
return value comes back to you. So "list a directory, read every file in it,
report the longest" is ONE call and ONE small result, where a call per step
brings every file back into the conversation and takes an iteration each.

- Steps that depend on each other's results, or many results that matter only
  as a summary (count, filter, sum, find the largest, collect a field)
  → ONE `tool$run` call, not a call per step.
- Not for a single call, and not for what one shell command does when `bash`
  is bound.

Enough to write a body without looking anything up:
- A tool is a function of its argument map: `(read-file {:path p})`,
  `(list-dir {:path "docs"})`, `(grep {:pattern "TODO" :path "src"})`.
- Clojure, but only its listed functions: `clojure.string/split-lines`,
  `clojure.string/ends-with?`, `clojure.string/includes?`, `map`, `filter`,
  `reduce`, `sort-by`, `frequencies`, `into` … The largest by a key is
  `(apply max-key second pairs)` — `max-key` takes the items as arguments.
- JSON text is `(json/read s)`, a map with keyword keys; a number in text is
  `(parse-long s)` or `(parse-double s)`, which return nil for text that is
  not a number rather than throwing.
- Refused, each costing you a retry: Java interop (`.split`, `.endsWith`,
  `Integer/parseInt`), `read-string`, `case` (use `cond`), `def`, and
  `loop`/`recur` — walk a sequence instead.
- Example, the line count of every file under docs/:
  ```
  (fn [_]
    (->> (:entries (list-dir {:path "docs"}))
         (remove #(clojure.string/ends-with? % "/"))
         (map (fn [f] [f (:total-lines (read-file {:path (str "docs/" f)}))]))
         (into {})))
  ```
`get-tool-info` on `tool$run` lists every allowed function, for one not named
here.