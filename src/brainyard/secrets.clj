(ns brainyard.secrets
  "Secret-shaped text, and what to do about it — brainyard's two copies in one
  place (`byj-gly.77`).

  brainyard carries two different pattern lists: eight in
  `agent/core/config.clj`, used to REFUSE a config write, and five in
  `agent/common/trajectory_export.clj`, used to REDACT an export. They overlap
  but neither contains the other, and nothing says why.

  The reason is real and is kept here as the shape of the namespace: the two
  jobs have opposite costs when they are wrong.

  - **Refusing** on a false positive blocks work someone meant to do. So
    `token-patterns` holds only tokens with a *shape* — a vendor prefix and a
    length — where a match is almost certainly the real thing.
  - **Redacting** on a false positive costs one `[REDACTED]` in a file nobody
    has read yet. So `redaction-patterns` adds two heuristics that would be far
    too eager to refuse on: a `bearer …` header, and `api_key=`-style
    assignments, which catch the secrets that have no shape of their own.

  Neither list is exhaustive and neither is meant to be. They refuse the obvious
  paste-in mistake; they are not a guarantee that nothing sensitive is in a
  file."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def token-patterns
  "Tokens recognisable by their own shape. brainyard's config-write list, plus
  `gck_` from its export list — the union of the two shaped sets, since a
  token worth redacting in an export is worth refusing in a config file.

  Plus the two provider keys brainyard's `sk-[A-Za-z0-9]` misses, because a
  hyphen follows `sk-` in both: Anthropic's `sk-ant-api03-…` and OpenAI's
  project keys `sk-proj-…` (byj-gly.146, divergence
  `provider-key-shapes-are-recognised`). The port's own default provider is
  Anthropic's."
  [#"sk-[A-Za-z0-9]{16,}"
   #"sk-ant-[A-Za-z0-9_-]{20,}"
   #"sk-proj-[A-Za-z0-9_-]{20,}"
   #"gck_[A-Za-z0-9]{8,}"
   #"AKIA[0-9A-Z]{16}"
   #"ASIA[0-9A-Z]{16}"
   #"ghp_[A-Za-z0-9]{20,}"
   #"github_pat_[A-Za-z0-9_]{20,}"
   #"xox[bpoars]-[A-Za-z0-9-]{10,}"
   #"AIza[0-9A-Za-z_-]{20,}"
   #"-----BEGIN [A-Z ]*PRIVATE KEY-----"])

(def heuristic-patterns
  "Secrets with no shape of their own, found by what is next to them.

  Too eager to refuse a write on — `password: correcthorsebattery` in a note is
  a match, and so is a sentence about an API key — and exactly right to redact,
  where being wrong costs a `[REDACTED]` nobody minds. brainyard's export list."
  [#"(?i)bearer\s+[A-Za-z0-9._\-]{12,}"
   #"(?i)(api[-_]?key|secret|token|password)([\"':=\s]+)[A-Za-z0-9._\-/+]{12,}"])

(def redaction-patterns (into token-patterns heuristic-patterns))

(def marker "[REDACTED]")

(defn redact-str
  "`s` with every secret-shaped substring replaced by `[REDACTED]`. A non-string
  passes through, so this can be walked over a whole structure.

  The assignment pattern replaces its own left-hand side too — `api_key=sk-…`
  becomes `[REDACTED]`, not `api_key=[REDACTED]` — which is brainyard's
  behaviour and loses nothing that matters."
  [s]
  (if (string? s)
    (reduce (fn [acc re] (str/replace acc re marker)) s redaction-patterns)
    s))

(defn redact
  "Every string leaf of `x`, redacted. brainyard's `redact-example`.

  A postwalk over leaves rather than a check of key names: a secret is found by
  what it looks like, not by where someone put it."
  [x]
  (walk/postwalk redact-str x))

(defn redacted?
  "Does `x` carry the marker anywhere?"
  [x]
  (str/includes? (pr-str x) marker))

(defn tokens-in
  "Shaped tokens in `x`, each masked to its first 8 characters — for a caller
  that REFUSES and has to say why. Reporting the match in full would put the
  secret in a transcript and an event log, which is the thing being prevented.

  Heuristic matches are deliberately absent: this is the list a refusal is
  built on, and a refusal has to be right."
  [x]
  (let [s (pr-str x)]
    (vec (for [re token-patterns
               m (re-seq re s)
               :let [m (if (vector? m) (first m) m)]]
           (str (subs m 0 (min 8 (count m))) "…")))))
