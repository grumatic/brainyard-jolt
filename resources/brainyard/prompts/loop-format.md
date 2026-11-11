The behavior-tree format. A loop is ONE EDN vector in a file, `[kind opts & children]`, where `opts` is always a map. Read this before writing or editing a loop; `flow$check` tells you what is wrong with one, and it cannot tell you what a node is.

## The seven node kinds

- `[:sequence {} a b c]` — run children in order, stop at the first FAILURE. Succeeds when all did.
- `[:fallback {} a b c]` — run children in order, stop at the first SUCCESS. Fails when all did. This is how a tree branches: put a `:condition` first, or a recovery node last.
- `[:repeat {:max-n 20 :until :some/pred?} child]` — run the child until `:max-n` or `:until` (a registry fn returning truthy). **One of the two is required**, or the loop cannot terminate.
- `[:scope {:in {inner outer} :out {outer inner}} child]` — run the child on its OWN blackboard, seeded only from `:in`; `:out` copies back afterwards (`:*` means the whole inner map). A barrier: nothing else crosses.
- `[:condition {:fn :x/pred?}]` — a leaf whose truthy value is success.
- `[:action {:fn :x/do}]` — a leaf; it fails only by returning `:failure` or throwing.
- `[:include {:tree "other.edn"}]` — spliced in when the tree loads.

Anything else is not a node kind. There is no `[:tool …]` or `[:predictor …]` node — those are `:action`s, below.

## Every leaf names `:fn`

A leaf runs a fn from the registry, and `:fn` is mandatory. Three of those fns are parameterized by the node, which is how a leaf does something new without new code:

```clojure
;; a model call: :in maps PREDICTOR INPUT ← blackboard key, :out maps blackboard key ← OUTPUT field
[:action {:id :my/think :fn :predictor/run :predictor "user/classify"
          :in {:text :question} :out {:label :severity}
          :zones [:agent-core :session-context]}]

;; a tool call: :in maps TOOL ARG ← blackboard key, :out maps blackboard key ← RESULT key
;; the tool is named by STRING, exactly as the registry lists it
[:action {:id :my/classify :fn :tool/run :tool "user$tool$classify"
          :in {:text :question} :out {:severity :severity}
          :on-error :failure}]          ; :failure (default) or :success

;; an event: :payload values are blackboard keys, or :config/… keys
[:action {:id :my/pre :fn :hooks/fire :event :agent.ask/pre :payload {:input :question}}]
```

Everything else is a compiled fn — `list-tools` will not show them, but `flow$check` reports any `:fn` that is not registered. The ReAct loop's are `:react/*` (`init`, `inc-iter`, `think-act`, `repair`, `tool-dispatch`, `accumulate`, `terminated?`, `stamp-answer`, `ensure-answer`, …) plus `:context/enforce`.

## The blackboard

Leaves share one mutable map. A node reads what earlier nodes wrote, so **order is the contract**: a key must be written by something that always runs first. `flow$check` verifies this and distinguishes a key nothing writes (a typo) from one written too late (an ordering mistake).

`:sequence` accumulates writes left to right. A `:fallback` guarantees only what EVERY branch writes, since only one branch ran. A `:repeat` body may run zero times, so nothing it writes is guaranteed after it.

A turn starts with `:question` on the blackboard and its caller reads back `:answer`, `:terminated-by`, `:usage` and the rest.

## Values from config

Any opt value may be `:config/<key>`, resolved from the run's config when the node ticks — `{:max-n :config/max-iterations}`. The key must exist; a misspelling reads as nil and the loop quietly misbehaves, which is why `flow$check` reports unknown ones.

## Worked example

```clojure
[:sequence {:id :my/turn}
 [:action {:id :my/init :fn :react/init}]
 [:repeat {:id :my/iterate :max-n :config/max-iterations :until :react/terminated?}
  [:sequence {:id :my/iteration}
   [:action {:id :my/inc :fn :react/inc-iter}]
   [:fallback {:id :my/llm-guard}
    [:action {:id :my/think :fn :react/think-act :predictor "react/think-act"
              :zones [:agent-core :session-context :history-context :user-context]}]
    [:action {:id :my/repair :fn :react/repair}]]
   [:fallback {:id :my/router}
    [:sequence {}
     [:condition {:id :my/has-tools :fn :react/has-tool-calls?}]
     [:action {:id :my/dispatch :fn :react/tool-dispatch}]]
    [:action {:id :my/stamp :fn :react/stamp-answer}]]
   [:action {:id :my/accumulate :fn :react/accumulate}]]]
 [:action {:id :my/ensure :fn :react/ensure-answer}]]
```

Give every node an `:id`: it names the node in a trace, in `flow$check`'s problems and in `flow$brief`, and duplicates are reported.

## Before you finish

Run `flow$check` on the file. It reports every problem at once — unregistered fns, predictors and tools that do not exist or whose `:in`/`:out` name fields they do not have, unknown config keys, zones and events, duplicate ids, an unbounded `:repeat`, and a blackboard key nothing feeds. Most of those are SILENT at run time, so a loop that has not been checked is a loop that has not been tested.
