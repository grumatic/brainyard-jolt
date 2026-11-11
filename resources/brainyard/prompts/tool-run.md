Run a Tool Lisp body ONCE, without saving a tool: a small program over the tools, run in one step. Everything the body reads stays inside it and only its return value comes back to you, so ten file reads that end in one number cost one result, not ten.

Use it when:
- the next step depends on what the last one returned (list a directory, then read what it lists), or
- many results matter only as a summary: count, filter, sum, pick the largest, collect one field.
Not for a single tool call, and not for what one shell command does when `bash` is listed.

Args:
- body: a string holding ONE form, (fn [args] ...)
- args: an optional map, passed to the body as `args` (default {})

Example: the line count of every file under docs/
  (fn [_]
    (->> (:entries (list-dir {:path "docs"}))
         (remove #(clojure.string/ends-with? % "/"))
         (map (fn [f] (let [r (read-file {:path (str "docs/" f)})]
                        [f (:total-lines r)])))
         (into {})))
It lists the directory inside the body — no call before it to see what is there.

An invalid body comes back as {:error "Invalid Tool Lisp: ..."} listing every problem: fix them all and call again. A body worth running twice is worth saving with tool$create.
