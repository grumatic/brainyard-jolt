Define (or replace) a persistent user tool whose body is written in Tool Lisp — a small Clojure subset that is INTERPRETED, not compiled, and may call other tools. The new tool is callable at once as `user$tool$<name>`, by you and by other user tools.

Args:
- name: lowercase-kebab name, e.g. "count-lines" (the tool id becomes user$tool$count-lines)
- description: one line
- input-schema: the arguments, a malli map schema as EDN text, e.g. "[:map [:path [:string {:desc \"File to count\"}]] [:min {:optional true} :int]]" — types :string :int :boolean [:vector …] :map [:enum …]; omit it for a tool that takes none
- output-schema: the result as a malli schema in EDN text, e.g. "[:map [:lines :int]]"; a body that can also return an error is "[:or [:map [:lines :int]] [:map [:error-message :string]]]". Results are checked against it; prose is refused
- body: a string holding ONE form: (fn [args] ...) where args is the map of arguments with keyword keys. Destructuring works: (fn [{:keys [path n]}] ...)

The body is validated when you call tool$create; an invalid definition returns every problem at once. Use tool$test to run it on sample args. tool$validate checks without saving.
