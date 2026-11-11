- Also, in a body: `(pmap f xs)` runs independent calls a few at a time, in
  order — reading many files; `(now)` is the time as an ISO-8601 UTC string and
  `(now-ms)` milliseconds; and state that must outlive the call — a counter, a
  last-seen value — goes in `(store$put {:key k :value v})` and comes back from
  `(store$get {:key k})` as `{:found :value}`, not in a file of your own.
