Tool Lisp:
- Special forms: fn (anonymous; #(...) works too) let if when when-not if-let when-let cond do and or -> ->> some->. Destructuring with {:keys [...] :as x} and [a b & more] in fn params and let.
- Call a tool with a map of its arguments: (read-file {:path p}) or (user$tool$count-lines {:path p}). A tool returns the map a tool call would — its `Outputs:` line, or the output-schema get-tool-info gives — but whole: read-file does not page a large file inside a body; a failed tool returns {:error-message "..."} or {:error "..."}.
- Keywords, maps and sets work as functions: (:content res), (map :name xs).
- Literals: strings, numbers, keywords, vectors, maps, sets, nil, true, false, regex #"\s+".
- json/read parses a JSON string into a map with KEYWORD keys; json/write does the reverse.
- String functions require strings: pass (:content (read-file ...)), not the result map.
- No loop/recur: walk a sequence with map, filter, reduce, keep, sort-by, group-by.
- NOT allowed: def, defn, loop, recur, named or recursive fns, eval, atoms, try/catch, Java interop, and any function not in the list below. A tool may not call itself, directly or through other tools.
