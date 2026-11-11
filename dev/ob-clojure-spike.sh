#!/bin/bash
# Spike (RFC-067 phase 2, byj-gly.460): does an org src block of Clojure run
# on jolt — org-babel's ob-clojure through CIDER, against this repository's
# `jolt -A:dev nrepl-server`? Measured, not assumed: it starts an nREPL server
# of its own on PORT, a batch Emacs with the CIDER the person's package
# directory has (read, not changed), connects, runs three blocks and prints
# what each gave and how long the connect and each run took. No model.
#
# Measured 2026-11-05 (jolt 0.8.x, CIDER 20250902, Org 9.7, Emacs 30.2):
# connected in 88 ms; `(+ 1 2)` → 3 in 24 ms; a :results output block's
# println came back; a block that requires a namespace and calls into it
# fails ("No such namespace") — ob-clojure wraps a :results value block in
# one form, compiled whole before the require runs, as JVM Clojure would —
# and the same call after a block of its own that requires it works. An org
# buffer outside the connected project has no CIDER session, and sesman then
# prompts for one, which in batch reads stdin forever: the person's Emacs
# links the buffer (or keeps the org file in the project).
#
#   dev/ob-clojure-spike.sh [PORT] [ELPA]    # 7911, ~/.emacs.d/elpa
set -u
PORT=${1:-7911}
ELPA=${2:-$HOME/.emacs.d/elpa}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/byj-ob-clojure.XXXX")
trap 'kill $NREPL 2>/dev/null; rm -rf "$W"' EXIT

cd "$ROOT"
jolt -A:dev nrepl-server "$PORT" > "$W/nrepl.log" 2>&1 &
NREPL=$!
for _ in $(seq 1 120); do
  nc -z localhost "$PORT" 2>/dev/null && break
  sleep 0.5
done
nc -z localhost "$PORT" || { echo "nREPL did not come up"; cat "$W/nrepl.log"; exit 1; }

cat > "$W/spike.org" <<'ORG'
#+name: arith
#+begin_src clojure :results value
(+ 1 2)
#+end_src

#+name: same-block
#+begin_src clojure :results value
(require 'brainyard.json)
(brainyard.json/write-str {:a [1 2]})
#+end_src

#+name: req
#+begin_src clojure :results silent
(require 'brainyard.json)
#+end_src

#+name: ns
#+begin_src clojure :results value
(brainyard.json/write-str {:a [1 2]})
#+end_src

#+name: out
#+begin_src clojure :results output
(println "printed" (count (all-ns)))
#+end_src
ORG

cat > "$W/spike.el" <<EL
(setq package-user-dir "$ELPA")
(package-initialize)
(require 'cider)
(require 'ob-clojure)
(setq org-babel-clojure-backend 'cider
      org-confirm-babel-evaluate nil
      cider-repl-pop-to-buffer-on-connect nil
      cider-skip-clojure-version-check t)
(defun spike-ms (t0) (round (* 1000 (float-time (time-subtract (current-time) t0)))))
(let ((t0 (current-time)))
  (cider-connect-clj (list :host "localhost" :port $PORT :project-dir "$ROOT"))
  (while (and (not (cider-connected-p)) (< (spike-ms t0) 30000))
    (accept-process-output nil 0.2))
  (message "SPIKE connect: %s in %d ms" (if (cider-connected-p) "connected" "NOT connected") (spike-ms t0)))
(with-current-buffer (find-file-noselect "$W/spike.org")
  ;; ob-clojure asks sesman for the buffer's CIDER session; a file outside
  ;; the connected project has none, and sesman would prompt for one
  (setq-local sesman-system 'CIDER)
  (sesman-link-with-buffer (current-buffer) (car (sesman-sessions 'CIDER)))
  (dolist (name '("arith" "same-block" "req" "ns" "out"))
    (goto-char (org-babel-find-named-block name))
    (let* ((t0 (current-time))
           (r (condition-case e (org-babel-execute-src-block) (error (list 'error (error-message-string e))))))
      (message "SPIKE %s: %S in %d ms" name r (spike-ms t0)))))
EL

# stdin from /dev/null: a prompt fails at once rather than waiting forever
timeout 120 emacs -Q --batch -l "$W/spike.el" </dev/null 2>&1 | grep -E "^SPIKE|rror" | head -40
