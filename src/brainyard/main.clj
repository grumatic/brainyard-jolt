;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.main
  "The `byj` CLI: `run`, the default, is brainyard's multi-turn session, an
  outer loop nesting the ReAct loop; `ask` (or `--prompt`) runs the ReAct
  loop on one or more questions and exits.

    jolt run:byj [options] [\"question\"]   jolt ask:byj [options] \"question\"
    target/byj [run] [options] [question…]   target/byj --prompt \"question\""
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.effect :as fx]
            [jolt.scheme :as scheme]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.mcp :as mcp]
            [brainyard.tools :as tools]
            [brainyard.config :as config]
            [brainyard.events :as events]
            [brainyard.launch :as launch]
            [brainyard.acp :as acp]
            [brainyard.context :as context]
            [brainyard.permission :as permission]
            [brainyard.persist :as persist]
            [brainyard.schedule :as schedule]
            [brainyard.web-share :as web-share]
            [brainyard.share :as share]
            [brainyard.worktree :as worktree]
            [brainyard.children :as children]
            [brainyard.trajectory :as trajectory]
            [brainyard.claude-agent :as claude-agent]
            [brainyard.init :as init]
            [brainyard.line-input :as line-input]
            [brainyard.claude-host :as claude-host]
            [brainyard.claude-sessions :as claude-sessions]
            [brainyard.codex-host :as codex-host]
            [brainyard.codex-sessions :as codex-sessions]
            [brainyard.sandbox :as sandbox]
            [brainyard.bt :as bt]
            [brainyard.agents :as agents]
            [brainyard.agent-reach :as agent-reach]
            [brainyard.project-loops :as project-loops]
            [brainyard.project-views :as project-views]
            [brainyard.rail-state :as rail-state]
            [brainyard.project-overview :as project-overview]
            [brainyard.issues :as issues]
            [brainyard.memory-rail :as memory-rail]
            [brainyard.self-improve-rail :as self-improve-rail]
            [brainyard.activity :as activity]
            [brainyard.emacs :as emacs]
            [brainyard.json :as json]
            [brainyard.hook-serve :as hook-serve]
            [brainyard.unix-socket :as us]
            [brainyard.tool-hook :as tool-hook]
            [brainyard.changes :as changes]
            [brainyard.terminal :as terminal]
            [brainyard.links :as links]
            [brainyard.tui-frame :as frame]
            [brainyard.channel :as channel]
            [brainyard.cli-help :as cli-help]
            [jolt.ffi :as ffi]
            [brainyard.flow :as flow]
            [brainyard.predictor :as predictor]
            [brainyard.programs :as programs]
            [brainyard.loop-tasks :as loop-tasks]
            [brainyard.loop-eval :as loop-eval]
            [brainyard.loop-proposals :as loop-proposals]
            [brainyard.session :as session]
            [brainyard.tasks :as tasks]
            [brainyard.tui :as tui]
            [brainyard.version :as version]
            [brainyard.update :as update]
            [jolt.http-client :as http]))

(defn- globs [s] (vec (remove str/blank? (map str/trim (str/split (str s) #",")))))
(defn- effects [s] (set (map keyword (globs s))))

(def ^:private command-names
  #{"ask" "run" "programs" "flow" "session" "update" "init" "doctor" "agent" "acp" "emacs" "turns" "hook" "loops"})

(def ^:private command-aliases
  "Old names a command still answers to: `sessions` (brainyard's `by
  sessions`) and `agents`, singular since byj-gly.435 — `session list`,
  `agent NAME` — and kept for scripts and a byj-share that asks by the old one."
  {"sessions" "session" "agents" "agent"})

(defn- settle-command
  "The command the parsed words come to. None named is `run`, a session, its
  bare words the first turn's, as `claude \"q\"` (byj-4dx); `--prompt` makes
  `run` (named or not) `ask`, one question answered and done, as `claude -p`
  — whose `-p` is byj's, and brainyard's, `--provider`. `--attach` with no
  command named is `ask` too, the one command it means anything to, as when
  `ask` was the default."
  [{:keys [command command-named? prompt attach] :as opts}]
  (cond
    (and prompt (contains? #{nil :run :ask} command)) (assoc opts :command :ask)
    prompt (update opts :errors (fnil conj [])
                   (str "--prompt asks one question, not `" (name command) "`: byj --prompt TEXT"))
    (and attach (not command-named?)) (assoc opts :command :ask)
    :else (update opts :command #(or % :run))))

(defn parse-args
  "The command line as opts. The command is the first word that is neither an
  option nor an option's value, when it names one; any other first word
  starts the question, and the command is `run` (`settle-command`). So
  options stand before the command as well as after it — `byj -C DIR eval
  exam`, as `git -C DIR status` — where once only the first argument was
  read for it, and `byj -C DIR eval exam` asked the default model \"eval
  exam\" (byj-gly.420)."
  [args]
  (settle-command
    (loop [[a & more :as args] args, opts {:command nil :questions []}]
      (cond
        (empty? args) opts
        :else
        (case a
          ("-p" "--provider") (recur (rest more) (assoc opts :provider (keyword (first more))))
          ("-m" "--model") (recur (rest more) (assoc opts :model (first more)))
          ("-C" "--working-dir") (recur (rest more) (assoc opts :working-dir (first more)))
          ("-n" "--max-iterations") (recur (rest more) (assoc opts :max-iterations (parse-long (first more))))
          ("-l" "--loop") (recur (rest more) (assoc opts :loop (first more)))
          ("-q" "--question") (recur (rest more) (update opts :questions conj (first more)))
          ;; one question, answered and done: run becomes ask (byj-4dx)
          "--prompt" (if (and (seq more) (not (str/blank? (first more))))
                       (recur (rest more) (-> opts (assoc :prompt true) (update :questions conj (first more))))
                       (recur (rest more) (update opts :errors (fnil conj []) "--prompt needs the question: --prompt TEXT")))
          "--print-tree" (recur more (assoc opts :print-tree true))
          "--print-config" (recur more (assoc opts :print-config true))
          ("-r" "--resume") (recur (rest more) (assoc opts :resume (first more)))
          "--resume-latest" (recur more (assoc opts :resume-latest true))
          "--resume-run" (recur more (assoc opts :resume-run true))
          "--sessions" (recur more (assoc opts :sessions true))
          "--no-persist" (recur more (assoc opts :persist-session? false))
          "--params" (recur (rest more) (assoc opts :params-file (first more)))
          "--print-predictor" (recur (rest more) (assoc opts :print-predictor (first more)))
          ("-v" "--verbose") (recur more (assoc opts :verbose true))
          "--tui" (recur more (assoc opts :tui true))
          ("-i" "--inline") (recur more (assoc opts :inline true))
          ;; sharing the session in the browser through ttyd (byj-gly.22): brainyard's flags
          ("--web" "--web-tmux" "--web-readonly" "--web-once")
          (recur more (assoc opts (first (web-share/flags a)) true))
          ("--web-port" "--web-bind" "--web-user" "--web-pass" "--web-max-clients")
          (recur (rest more) (assoc opts (first (web-share/flags a)) (first more)))
          ;; the session shared through the byj-share sidecar (RFC-043): link, class:CODE,
          ;; or an ended share's id to reopen with -r (shr_…)
          "--share" (recur (rest more) (assoc opts :share (or (first more) "")))
          "--trace-prompts" (recur more (assoc opts :verbose true :trace-prompts true))
          "--json" (recur more (assoc opts :json true))
          "--abilities" (recur (rest more) (assoc opts :abilities (vec (remove str/blank? (str/split (str (first more)) #",")))))
          "--procedures" (recur (rest more) (assoc opts :procedures (vec (remove str/blank? (str/split (str (first more)) #",")))))
          "--example" (recur (rest more) (assoc opts :example (first more)))
          "--test" (recur more (assoc opts :test true))
          ("-A" "--attach") (recur (rest more) (assoc opts :attach (first more)))
          ("-t" "--timeout") (recur (rest more) (assoc opts :timeout-secs (parse-long (first more))))
          "--live" (recur more (assoc opts :live true))
          "--all" (recur more (assoc opts :all true))
          "--force" (recur more (assoc opts :force true))
          "--by" (recur (rest more) (assoc opts :by (mapv keyword (globs (first more)))))
          "--judge" (recur (rest more) (assoc opts :judge (first more)))
          "--turn" (recur (rest more) (assoc opts :turn (parse-long (first more))))
          "--sample" (recur (rest more) (assoc opts :sample (parse-long (first more))))
          "--top" (recur (rest more) (assoc opts :top (parse-long (first more))))
          "--k" (recur (rest more) (assoc opts :k (parse-long (first more))))
          "--tasks" (recur (rest more) (assoc opts :tasks (first more)))
          ;; `loops eval` (RFC-071): the evaluation's spend cap, in USD, and
          ;; what the candidate is meant to improve
          "--max-cost" (recur (rest more) (assoc opts :max-cost (parse-double (str (first more)))))
          "--aim" (recur (rest more) (assoc opts :aim (keyword (first more))))
          "--keep" (recur more (assoc opts :keep true))
          ;; `loops propose`: why, in words; the bundled loop as the candidate
          "--why" (recur (rest more) (assoc opts :why (first more)))
          "--bundled" (recur more (assoc opts :bundled true))
          ;; `loops eval|propose`: each turn in this byj, not a sandboxed child
          "--in-process" (recur more (assoc opts :in-process true))
          ;; `loops tasks build`: a dogfood plan's stage checks as the set's
          "--plan" (recur (rest more) (assoc opts :plan (first more)))
          "--stage" (recur (rest more) (assoc opts :stage (first more)))
          "--rubric" (recur (rest more) (assoc opts :rubric (first more)))
          "--id" (recur (rest more) (assoc opts :experiment-id (first more)))
          "--port" (recur (rest more) (assoc opts :port (parse-long (first more))))
          "--settings" (recur more (assoc opts :settings true))
          "--events" (recur (rest more) (assoc opts :events (globs (first more))))
          "--serve" (recur more (assoc opts :serve true))
          "--agent" (recur (rest more) (assoc opts :agent (first more)))
          "--label" (recur (rest more) (assoc opts :label (first more)))
          "--leader" (recur (rest more) (assoc opts :leader (first more)))
          "--permission-mode" (recur (rest more) (assoc opts :permission-mode (keyword (first more))))
          "--sandbox" (recur more (assoc opts :sandbox true))
          "--container" (recur more (assoc opts :container true))
          ;; eval bench's skill axis (byj-gly.288.34): skills roots, or none at all
          "--skills" (recur (rest more) (assoc opts :skills (vec (remove str/blank? (str/split (str (first more)) #",")))))
          "--no-skills" (recur more (assoc opts :skills :none))
          "--sandbox-profile" (recur (rest more) (assoc opts :sandbox-profile (first more)))
          "--sandbox-allow-write" (recur (rest more) (update opts :sandbox-allow-write (fnil conj []) (first more)))
          "--sandbox-no-network" (recur more (assoc opts :sandbox-no-network true))
          "--ask" (recur (rest more) (assoc opts :tool-approval-patterns (globs (first more))))
          "--allow" (recur (rest more) (assoc opts :tool-allow-tools (globs (first more))))
          "--deny" (recur (rest more) (assoc opts :tool-deny-tools (globs (first more))))
          "--ask-effects" (recur (rest more) (assoc opts :tool-ask-effects (effects (first more))))
          "--deny-effects" (recur (rest more) (assoc opts :tool-deny-effects (effects (first more))))
          "--allow-path" (recur (rest more) (update opts :allowed-dirs (fnil into []) (globs (first more))))
          "--no-path-permission" (recur more (assoc opts :path-permission? false))
          ("-h" "--help") (recur more (assoc opts :help true))
          ("-V" "--version") (recur more (assoc opts :version true))
          "--metric" (recur (rest more) (assoc opts :metric (first more)))
          ;; an analytics report on a session (byj-gly.288.39): its instruction, "" the default
          "--report" (recur (rest more) (assoc opts :report (or (first more) "")))
          "--reports" (recur more (assoc opts :reports true))
          "--pdf" (recur more (assoc opts :pdf true))
          "--split" (recur (rest more) (assoc opts :split (first more)))
          "--repeats" (recur (rest more) (assoc opts :repeats (parse-long (first more))))
          "--parallel" (recur (rest more) (assoc opts :parallel (parse-long (first more))))
          "--max-calls" (recur (rest more) (assoc opts :max-calls (parse-long (first more))))
          "--max-tokens" (recur (rest more) (assoc opts :max-tokens (parse-long (first more))))
          "--optimizer" (recur (rest more) (assoc opts :optimizer (first more)))
          ;; programs eval|compile's model, provider/model (brainyard's --lm);
          ;; programs/resolve-eval-lm reads the label (byj-gly.438)
          "--lm" (recur (rest more) (assoc opts :lm (first more)))
          "--teacher-lm" (recur (rest more) (assoc opts :teacher-lm (first more)))
          ;; what a command names: byj agent's one agent (byj-gly.441)
          "--name" (recur (rest more) (assoc opts :name (first more)))
          "--teacher-tier" (recur (rest more) (assoc opts :teacher-tier (first more)))
          "--max-bootstrapped" (recur (rest more) (assoc opts :max-bootstrapped (parse-long (first more))))
          "--tier" (recur (rest more) (assoc opts :tier (first more)))
          "--yes" (recur more (assoc opts :yes true))
          "--sure" (recur more (assoc opts :sure true))
          "--candidate" (recur (rest more) (assoc opts :candidate (first more)))
          ;; `loops tasks build --check CMD` (RFC-071) takes a command;
          ;; everywhere else --check is a flag (update, init)
          "--check" (if (= :loops (:command opts))
                      (recur (rest more) (assoc opts :check-cmd (first more)))
                      (recur more (assoc opts :check true)))
          "--from" (recur (rest more) (assoc opts :from (globs (first more))))
          "--rollback" (recur more (assoc opts :rollback true))
          "--install" (recur more (assoc opts :install true))
          "--no-guide" (recur more (assoc opts :no-guide true))
          "--no-open" (recur more (assoc opts :no-open true))
          "--no-process" (recur more (assoc opts :no-process true))
          "--no-prompts" (recur more (assoc opts :no-prompts true))
          "--reason" (recur (rest more) (assoc opts :reason (first more)))
          "--answer" (recur (rest more) (assoc opts :answer (first more)))
          "--seed" (recur (rest more) (assoc opts :seed (first more)))
          "--accept" (recur more (assoc opts :accept true))
          "--paper" (recur (rest more) (assoc opts :paper (first more)))
          "--bank" (recur (rest more) (assoc opts :bank (first more)))
          "--exposure" (recur (rest more) (assoc opts :exposure (first more)))
          "--statuses" (recur (rest more) (assoc opts :statuses (first more)))
          "--out" (recur (rest more) (assoc opts :out (first more)))
          "--watch" (recur more (assoc opts :watch true))
          "--relay" (recur more (assoc opts :relay true))
          "--no-record" (recur more (assoc opts :no-record true))
          "--finish" (recur more (assoc opts :finish true))
          "--no-share" (recur more (assoc opts :no-share true))
          "--publish" (recur more (assoc opts :publish true))
          "--class" (recur (rest more) (assoc opts :class (first more)))
          "--title" (recur (rest more) (assoc opts :title (first more)))
          "--note" (recur (rest more) (assoc opts :note (first more)))
          "--workspace" (recur (rest more) (assoc opts :workspace (first more)))
          "--grade-out" (recur (rest more) (assoc opts :grade-out (first more)))
          "--grades" (recur (rest more) (assoc opts :grades (first more)))
          "--prefix" (recur (rest more) (assoc opts :prefix (first more)))
          "--log" (recur (rest more) (assoc opts :log (first more)))
          "--socket" (recur (rest more) (assoc opts :socket (first more)))
          "--idle" (let [v (first more) n (when v (parse-long v))]
                     (if (and n (pos? n))
                       (recur (rest more) (assoc opts :idle n))
                       (recur (rest more) (update opts :errors (fnil conj []) (str "--idle needs a number of seconds, not " (pr-str v))))))
          "--todo" (recur (rest more) (assoc opts :todo-slug (first more)))
          "--expect-hash" (recur (rest more) (assoc opts :expect-hash (first more)))
          (cond
            (str/starts-with? a "-")
            (recur more (update opts :errors (fnil conj []) (str "unknown option " a)))
            ;; the first bare word: the command, when it names one
            (and (nil? (:command opts)) (command-names (command-aliases a a)))
            (recur more (assoc opts :command (keyword (command-aliases a a)) :command-named? true))
            :else
            (recur more (-> opts (update :command #(or % :run)) (update :questions conj a)))))))))

(defn- help-words
  "The words the help is asked by: the command, when one was named, and the
  words after it — read off the parsed opts, so an option before the command
  and its value are passed over as `parse-args` passes them over. From the
  raw args, `byj -p mock session -h` read no command past `-p` and printed
  the top page (byj-gly.436)."
  [{:keys [command command-named? questions]}]
  (if command-named? (cons (name command) questions) []))

;; --- .env ----------------------------------------------------------------------

(def parse-dotenv launch/parse-dotenv)
(def env-fn launch/env-fn)

(declare err-println)

(def fallback-lm launch/fallback-lm)

(defn run-lm-choice
  "Which provider and model a run asks (`launch/lm-choice`), a bad
  `:default-lm` said on stderr."
  [provider model config]
  (launch/lm-choice provider model config err-println))

;; --- tracing -------------------------------------------------------------------

(def ^:dynamic *exit*
  "How the CLI ends with a code where a test would rather see it: the
  unknown-option and `programs` paths, which tests bind to throw the code
  instead (byj-gly.442) — they ran byj as a process for it, seven seconds of
  the serial suite. Nothing in `src` binds it."
  (fn [code] (System/exit code)))

(defn- err-println [& xs]
  (binding [*out* *err*] (apply println xs) (flush)))

(defn- clip [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn tracer
  "The CLI's viewer over the event stream (RFC-026).

  Installed ALWAYS, not only under `-v`: `brainyard.events` says which events
  report a problem someone has to see, and those are shown whether or not
  anyone asked to watch. `-v` adds the rest — the turn narrating itself."
  [{:keys [trace-prompts display-box] :as opts}]
  ;; which reply fields the LLM call in flight has already streamed. The events
  ;; only say what a fragment IS; deciding what to put in front of a person is
  ;; this viewer's job, and this is the state that decision needs (byj-gly.56).
  (let [shown (atom {})
        stream (fn [field text]
                 (binding [*out* *err*]
                   (when-not (= field (:at @shown))
                     (when (:at @shown) (println))
                     (print (if (= :reasoning field) "💭 " "")))
                   (print text)
                   (flush))
                 (swap! shown assoc :at field field true))]
    (fn [event data]
      ;; how much to show is read per event, so an :output-style edit takes
      ;; effect at the next one rather than at the next run (byj-gly.112)
      (let [verbose (if display-box
                      (contains? #{:verbose :debug} @display-box)
                      (:verbose opts))]
      (case event
        :agent.iteration/start (when verbose
                                 (reset! shown {})
                                 (err-println (str "\n── iteration " (:iteration data) " ──")))
        :agent.llm/request (when trace-prompts
                             (doseq [{:keys [role content]} (:messages data)]
                               (err-println (str "[" role "]\n" content))))
        :agent.llm/response (when trace-prompts (err-println "[response]\n" (:content data)))
        ;; a fragment as the provider produces it (RFC-006): written without a
        ;; newline, so a reply appears the way it is generated. Reasoning prose
        ;; is worth watching precisely while the answer is still empty, so it
        ;; is shown as it arrives and the answer takes over when it begins.
        :agent.llm/chunk (when verbose (stream (:field data) (:text data)))
        :agent.think/reasoning (when verbose
                                 ;; close the streamed line before the parsed reply
                                 (when (:at @shown) (binding [*out* *err*] (println) (flush)))
                                 ;; …and do not say it twice: if the reasoning
                                 ;; was streamed, the person has already read it
                                 (when (and (not (:reasoning @shown))
                                            (not (str/blank? (:reasoning data))))
                                   (err-println "💭" (:reasoning data)))
                                 (reset! shown {}))
        ;; the reasoning just printed was written under the CLI's refusal of
        ;; calls that now run (byj-gly.339)
        :agent.tools/recovered (when verbose (err-println "💭" (:thought data)))
        :agent.tools/calls (when verbose
                             (doseq [{:keys [tool-name tool-args]} (:tool-calls data)]
                               (err-println "🔧" tool-name (clip (pr-str tool-args) 300))))
        ;; what a running tool prints, as it prints it (byj-gly.364)
        :agent.tool/output (when verbose (err-println "  │" (clip (str (:line data)) 300)))
        :agent.tools/results (when verbose
                               (doseq [{:keys [tool-name tool-result]} (:results data)]
                                 (err-println "  ↳" tool-name (clip tool-result 400))))
        :agent.repair/llm-error (when verbose
                                  (err-println "⚠️  LLM error (attempt" (str (:attempt data) "):")
                                               (:message data)))
        ;; an LLM failure is reported once, by the repair line above
        :predictor/call (when-let [src (and verbose (:params-source data))]
                          (err-println "📐" (:predictor-id data) "params:"
                                       (if (keyword? src) (name src) src)
                                       (if-let [v (:params-version data)] (str "(version " v ")") "")))
        :predictor/params-skipped (doseq [{:keys [file problems]} (:skipped data)]
                                    ;; a problem: always, even without -v
                                    (err-println "⚠️  invalid params file skipped:" file "—"
                                                 (str/join "; " problems)))
        :bt.node/error (when-not (or (not verbose) (= :react/think-act (:node data)))
                         (err-println "✖ node" (:node data) "failed:" (:message data)))
        :agent.repair/no-action (when verbose
                                  (err-println "⚠️  no action (streak" (str (:streak data) ")")))
        :bt.scope/cancelled (when verbose (err-println "⏹  turn cancelled"))
        ;; a :notice, so it shows without -v — and it has to say what to DO,
        ;; because "enough predictions to compile" on its own tells someone a
        ;; fact and leaves them to go and find the command
        :programs/dataset-ready
        (err-println "💡" (:records data) "prediction records for" (:predictor-id data)
                     (str "— program$build-dataset can build a dataset from them"
                          " (:prediction-notice-at " (:threshold data) ")"))
        ;; a note for people (RFC-043), said whoever is watching: a person at
        ;; this terminal is who it was written for
        :session.share/note (err-println (str "✎ note from " (or (channel/sender-label (:from data)) "someone") ":")
                                         (clip (str (:text data)) 2000))
        :session.turn/end (when verbose
                            (err-println (str "[turn · " (name (or (:terminated-by data) :unknown))
                                              " · " (:iterations data) " iteration(s) · "
                                              (get-in data [:usage :llm-calls]) " LLM call(s)]")))

        ;; everything the branches above do not render: the catalogue decides.
        ;; A problem is shown whether or not anyone asked to watch; a trace only
        ;; under -v; a :quiet event is a drop someone chose, and stays dropped.
        (case (events/level event)
          :problem (err-println "⚠️ " (str (events/label event) ":")
                                (clip (events/detail data) 300))
          ;; shown unasked like a problem, marked differently because nothing
          ;; is wrong — see `events/levels`
          :notice (err-println "💡" (str (events/label event) ":")
                               (clip (events/detail data) 300))
          :trace (when verbose
                   ;; not-empty, not or: detail returns "" for a payload with no
                   ;; sentence of its own, and "" is truthy — which printed a
                   ;; label with nothing after it
                   (err-println "·" (events/label event)
                                (clip (or (not-empty (events/detail data)) (pr-str data)) 200)))
          nil))))))

;; --- main ------------------------------------------------------------------------

(def mock-lm launch/mock-lm)

(defn- stdin-reader [prompt]
  (print prompt)
  (flush)
  (read-line))

(ffi/defcfn ^:private c-isatty "isatty" [:int] :int)

(defn- stdin-tty?
  "Is standard input a terminal? A session reading a pipe must see the pipe
  end, which a reader that polls `.ready` cannot tell from a pause."
  []
  (try (= 1 (c-isatty 0)) (catch Throwable _ false)))

(defn- channel-turn!
  "The next request waiting on the session channel as a turn, said as it is
  taken — the injected turn names where it came from (RFC-031)."
  [ch say]
  (when ch
    (when-let [{:keys [text from approval]} (channel/next-turn! ch)]
      ;; a person's turn, put in by the share sidecar (RFC-043), says who
      ;; and under what; another session's, as it always has
      (say (if (map? from)
             (str "⇠ injected by " (channel/sender-label from)
                  (case approval :owner " · you allowed it" :consent " · under the class's consent" nil))
             (str "⇠ asked over the session channel" (when from (str " by " from)))))
      text)))

(defn tmux-pane
  "The tmux pane this process draws in, as `[SOCKET,]PANE` — from `$TMUX`
  and `$TMUX_PANE` — or nil outside tmux."
  [getenv]
  (let [t (not-empty (str (getenv "TMUX"))) p (not-empty (str (getenv "TMUX_PANE")))]
    (when (and t p)
      (str (first (str/split t #",")) "," p))))

(defn- open-channel
  "The session channel of a live session (RFC-031), or nil when
  `:ask-channel-enabled?` is off. One that could not listen says why through
  `say` and is still returned, taking no requests. `:terminal?` records the
  tmux pane the session draws in, for a share of it (RFC-043)."
  [base cancellers lm-box say & [{:keys [close! label permission-fn terminal?]}]]
  (let [config (:config base)]
    (when (:ask-channel-enabled? config true)
      (let [ch (channel/open! config (:session-id config)
                              {:close! close! :label label :permission-fn permission-fn
                               :tmux-pane (when terminal? (tmux-pane #(System/getenv %)))
                               :cancel! #(session/cancel-turn! cancellers)
                               :describe (fn []
                                           (let [lm (or (some-> lm-box deref) (:lm base))]
                                             {:provider (some-> (:provider lm) name) :model (:model lm)}))})]
        (if-let [p (:problem ch)]
          (say p)
          ;; the person's Emacs told which session to ask (RFC-067 phase 4)
          (emacs/register! config))
        ch))))

(defn- saved-rows
  "The project's saved sessions that never listened on the session channel —
  an SDK's, `byj acp`'s (a machine's workbench), a headless run's — as `session
  list` rows marked `:saved?`, newest first: divergence
  `session-list-shows-saved-sessions`. One that never ran a turn has nothing
  to show, and is left out, as `session/list` leaves it (RFC-053)."
  [config listened]
  (let [seen (set listened)]
    (for [{:keys [id problem turn-count conversation-title last-question]} (persist/summaries config)
          :when (and (not problem) (pos? (or turn-count 0)) (not (seen id)))]
      {:session-id id :live? false :saved? true :label conversation-title :last-question last-question})))

(defn- session-command
  "`byj session list [--live] [--json]` — brainyard's `cmd-sessions-list`,
  its `by sessions` singular here (divergence `session-and-agent-are-singular`),
  with the saved sessions that never listened after those that did (divergence
  `session-list-shows-saved-sessions`)."
  [{:keys [questions live json] :as opts} config]
  (let [sub (or (first questions) "list")]
    (case sub
      "list" nil
      "watch" (let [target (second questions)
                    s (channel/find-session (dissoc config :session-id) target
                                            " — `session list --live` names the live ones")]
                (if (:error s)
                  (do (err-println (str "error: " (:error s))) (System/exit 1))
                  (let [{:keys [ack done]} (channel/subscribe! (:ask-socket-path s) (:events opts)
                                                               (fn [f] (println (pr-str f)) (flush)))]
                    (err-println (str "watching " (:session-id s) " — " (pr-str ack) " (ctrl-c stops)"))
                    @done
                    (System/exit 0))))
      (do (err-println (str "session: unknown subcommand " (pr-str sub) " — list | watch SESSION")) (System/exit 2)))
    (if (not= "list" sub)
      nil
      (let [listened (channel/sessions config)
            rows (if live
                   (filter :live? listened)
                   (concat listened (saved-rows config (map :session-id listened))))]
        (if json
          (println (json/write-str (mapv #(update % :ops (fn [ops] (mapv name ops))) rows)))
          (if (empty? rows)
            (println (if live "no live sessions" "no sessions here"))
            (doseq [{:keys [session-id live? saved? owner-pid label last-question]} rows]
              (println (format "%-24s %-5s %-7s %s" session-id (cond live? "live" saved? "saved" :else "-") (or (when live? owner-pid) "")
                               (or label (some-> last-question (subs 0 (min 60 (count last-question)))) ""))))))))))

(defn- turns-diff
  "What turn TURN of a session changed (`more` is `[SESSION TURN?]`), git's
  diff between its snapshots, the last turn that changed files unless TURN
  is given; `json` for `{:turn :ref :before :after :root :files :diff}`
  (RFC-069): the exit code."
  [what more json config]
  (let [[id turn] more
        turn (some-> turn parse-long)]
    (if (str/blank? (str id))
      (do (err-println (str what ": give the session's id (byj session list), and a turn if not its last that changed files")) 2)
      (let [r (changes/diff config id turn)]
        (cond
          json (do (println (json/write-str r)) (if (:error r) 1 0))
          (:error r) (do (err-println (:error r)) 1)
          :else (do (print (:diff r)) (flush) 0))))))

(defn- turns-list
  "A session's kept turns, a line each — its number, how many files it
  changed, when it ended and its question — or `--json` rows (RFC-069): the
  exit code."
  [more json config]
  (let [[id] more]
    (cond
      (str/blank? (str id))
      (do (err-println "turns list: give the session's id (byj session list)") 2)
      (nil? (changes/root config))
      (do (err-println (:error (changes/diff config id))) 1)
      :else
      (let [rows (changes/rows config id)]
        (if json
          (println (json/write-str (mapv #(select-keys % [:turn :ref :before :after :files :subject :at]) rows)))
          (if (empty? rows)
            (println (str "no turns kept for " id))
            (doseq [row rows] (println (changes/row-line row)))))
        0))))

(defn- turns-back
  "`byj turns rewind SESSION TURN [end]`, `undo-turn SESSION TURN` and
  `undo SESSION [N]`: the files put back, as /rewind and /undo-turn put them
  (RFC-069), and the session told at its next turn — live in another
  process or resumed later — by a note left with it (byj-gly.488). The exit
  code."
  [sub more json config]
  (let [[id a b] more
        n (some-> a parse-long)
        r (cond
            (str/blank? (str id)) {:usage true}
            (= sub "undo") (if (or (nil? a) n) (changes/undo-rewind! config id n) {:usage true})
            (nil? n) {:usage true}
            (= sub "undo-turn") (changes/undo-turn! config id n)
            (or (nil? b) (= b "end")) (changes/rewind! config id n (= b "end"))
            :else {:usage true})]
    (when (seq (:files r))
      (persist/note! config id (session/outside-line (session/rewind-line ({"rewind" :rewind "undo-turn" :undo-turn "undo" :undo-rewind} sub) r) sub)))
    (cond
      (:usage r) (do (err-println (str "usage: byj turns " (case sub "undo" "undo SESSION [N]" "undo-turn" "undo-turn SESSION TURN" "rewind SESSION TURN [end]")
                                       " — byj turns list SESSION names the turns")) 2)
      json (do (println (json/write-str r)) (if (:error r) 1 0))
      (:error r) (do (err-println (:error r)) 1)
      (empty? (:files r)) (do (println "the files are already as they were then: nothing changed") 0)
      :else (do (println (str (count (:files r)) " file" (when (not= 1 (count (:files r))) "s") " put back: " (str/join ", " (:files r))))
                (when (seq (:conflicts r)) (println (str "conflict markers left in " (str/join ", " (:conflicts r)))))
                (println (str "what was there is kept as " (:saved r) " — byj turns undo " id " puts it back"))
                0))))

(defn- turns-fork
  "`byj turns fork SESSION [TURN [keep-files]]`: a new saved session, the
  conversation cut before TURN, the files put back to its start unless
  keep-files — what /fork does (RFC-069). The exit code."
  [more json config]
  (let [[id a b] more
        k (some-> a parse-long)
        keep-files? (= "keep-files" (if k b a))]
    (if (or (str/blank? (str id)) (and a (not k) (not keep-files?)) (and b (not= b "keep-files")))
      (do (err-println "usage: byj turns fork SESSION [TURN [keep-files]]") 2)
      (let [nid (context/session-id)
            r (persist/fork! config id nid (when k {:before-turn k}))
            files (when (and k (not keep-files?) (not (:problem r))) (changes/rewind! config id k))
            ;; told at their next turns (byj-gly.488): the session whose
            ;; files went back under it, and a fork whose files hold turns
            ;; its conversation does not
            _ (when (seq (:files files))
                (persist/note! config id (session/outside-line (session/rewind-line :forked-away files {:session nid}) "fork")))
            _ (when (and k keep-files? (not (:problem r)))
                (persist/note! config nid (session/rewind-line :fork-kept {:turn k} {:from id})))
            out (cond-> {:session-id nid :forked-from id}
                  k (assoc :before-turn k)
                  files (assoc :files (:files files) :saved (:saved files) :error (:error files)))]
        (cond
          (:problem r) (do (if json (println (json/write-str {:error (:problem r)})) (err-println (str "could not fork: " (:problem r)))) 1)
          json (do (println (json/write-str out)) 0)
          :else (do (println (str "forked as " nid (when k (str ", its conversation before turn " k))))
                    (cond (:error files) (println (str "the files were not put back: " (:error files)))
                          (seq (:files files)) (println (str "the files put back: " (str/join ", " (:files files))
                                                             " — what was there is kept as " (:saved files))))
                    (println (str "byj run --resume " nid " continues it"))
                    0))))))

(defn- turns-command
  "`byj turns list|diff|rewind|undo-turn|undo …` — a session's turns, kept
  as git snapshots of the work tree as each began and ended, and the files
  put back to one (RFC-069): the exit code."
  [{:keys [questions json]} config]
  (let [[sub & more] questions]
    (case sub
      "list" (turns-list more json config)
      "diff" (turns-diff "turns diff" more json config)
      ("rewind" "undo-turn" "undo") (turns-back sub more json config)
      "fork" (turns-fork more json config)
      (do (err-println (str "turns: unknown subcommand " (pr-str sub) " — list, diff, rewind, undo-turn, undo, fork")) 2))))

(defn- loops-command
  "`byj loops tasks build [--name SET] [--from SESSIONS] [--check CMD]`: a
  task set rebuilt from the project's past turns, written to
  .brainyard/loops/tasks/SET.edn (RFC-071, byj-ng7.3); `byj loops revert
  [LOOP]`: the loop before its last change, staged as a proposal
  (byj-ng7.2); `byj loops eval CANDIDATE`: the candidate against the loop in
  force on a task set, each run in a clone (byj-ng7.4); `byj loops propose
  CANDIDATE | --bundled [LOOP]`: that evaluation, staged as a proposal when
  it wins (byj-ng7.5); `json` for what each returns. The exit code."
  [{:keys [questions json name from check-cmd plan stage tasks repeats max-cost aim keep why bundled in-process]} lm config]
  (let [[sub & more] questions
        ;; an evaluation's turns: each a byj of its own under the seatbelt
        ;; where it can be, else in this one (RFC-071, byj-ng7.14)
        contained (fn [] (let [self (when-not in-process
                                      (or (some-> (System/getProperty "jolt.launcher") vector)
                                          (sandbox/self-argv #(System/getenv %))))
                               {:keys [run-turn sandbox]} (loop-eval/containment self)]
                           {:run-turn run-turn :containment sandbox}))]
    (case sub
      "tasks" (if (= "retire" (first more))
                (let [r (loop-tasks/retire! config name (rest more))]
                  (cond
                    json (do (println (json/write-str r)) (if (:error r) 1 0))
                    (:error r) (do (err-println (:error r)) 1)
                    :else (do (println (str "retired " (count (:retired r)) " task(s) from " (:file r) ", "
                                            (:tasks r) " left; their pins released"
                                            (when (seq (:unknown r)) (str " — not in the set: " (str/join ", " (:unknown r))))))
                              0)))
              (if (not= ["build"] (vec more))
                (do (err-println "usage: byj loops tasks build [--name SET] [--from SESSIONS] [--check CMD | --plan FILE [--stage ID]] [--json]") 2)
                (let [pc (when plan (loop-tasks/plan-checks plan stage))
                      r (if (:error pc) pc (loop-tasks/build! config {:name name :sessions from :check check-cmd :plan pc}))]
                  (cond
                    json (do (println (json/write-str r)) (if (:error r) 1 0))
                    (:error r) (do (err-println (:error r)) 1)
                    :else (do (doseq [l (loop-tasks/summary-lines r)] (println l)) 0)))))
      "eval" (if (str/blank? (str (first more)))
               (do (err-println "usage: byj loops eval CANDIDATE [--tasks SET] [--repeats N] [--max-cost USD] [--aim cost] [--in-process] [--keep] [--json]") 2)
               (let [r (loop-eval/evaluate! config (merge {:lm lm :candidate (first more)
                                                            :loop (loop-eval/loop-of (first more))
                                                            :tasks tasks :repeats repeats :max-cost max-cost
                                                            :aim aim :keep? keep}
                                                           (contained)))]
                 (cond
                   json (do (println (json/write-str (if (:error r) r {:file (:file r) :report (dissoc (:report r) :runs)})))
                            (if (:error r) 1 0))
                   (:error r) (do (err-println (:error r))
                                  (doseq [{:keys [node problem]} (rest (:problems r))]
                                    (err-println (str "  " node " " problem)))
                                  1)
                   :else (do (doseq [l (loop-eval/summary-lines r)] (println l)) 0))))
      "propose" (if (and (not bundled) (str/blank? (str (first more))))
                  (do (err-println "usage: byj loops propose CANDIDATE | --bundled [LOOP]  [--tasks SET] [--repeats N] [--max-cost USD] [--aim cost] [--why TEXT] [--json]") 2)
                  (let [r (loop-eval/propose! config (merge {:lm lm :candidate (when-not bundled (first more))
                                                             :loop (if bundled (or (first more) "react") (loop-eval/loop-of (first more)))
                                                             :remove? (boolean bundled) :why why
                                                             :tasks tasks :repeats repeats :max-cost max-cost :aim aim :keep? keep}
                                                            (contained)))]
                    (cond
                      json (do (println (json/write-str (if (:error r) r (-> r (update :report dissoc :runs)))))
                               (if (:error r) 1 0))
                      (:error r) (do (err-println (:error r)) 1)
                      :else (do (doseq [l (loop-eval/summary-lines r)] (println l))
                                (println (if-let [{:keys [id]} (:staged r)]
                                           (str "staged loop/" id " — /proposals show loop/" id
                                                " to read it; a person's accept makes it the project's loop from the next session")
                                           (str "not staged: " (:not-staged r))))
                                0))))
      "revert" (let [r (loop-proposals/stage-revert! config (first more))]
                 (cond
                   json (do (println (json/write-str r)) (if (:error r) 1 0))
                   (:error r) (do (err-println (:error r)) 1)
                   :else (do (println (str "staged loop/" (:id r) " — /proposals show loop/" (:id r)
                                           " to read it, accept to make it the project's loop from the next session"))
                             0)))
      (do (err-println (str "loops: unknown subcommand " (pr-str sub) " — tasks build, eval, propose, revert")) 2))))

(defn- hook-answer
  "What `byj hook pretooluse` prints for the payload `json`, its decision
  logged in `log` (`~/.brainyard/permissions` when nil): the gates under the
  config of the payload's `cwd`, nil for no opinion. The one answer, given by
  the hook as a process and by `byj hook serve` alike (RFC-012)."
  [json log overrides working-dir]
  (let [payload (try (json/read-str (str json)) (catch Throwable _ nil))]
    (when (map? payload)
      (let [wd (or (not-empty (str (:cwd payload))) working-dir (System/getProperty "user.dir") ".")
            config (try (:config (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)}))
                        (catch Throwable _ {:working-dir wd}))]
        (tool-hook/hook! config payload (tool-hook/log-dir log (System/getenv "HOME")))))))

(defn- hook-command
  "`byj hook pretooluse [--log DIR] [--socket SOCK]` — a Claude Code or Codex
  `PreToolUse` payload on stdin through byj's gates under its project's
  config, the decision logged and the agent's answer printed (nothing for no
  opinion), then, with `--socket`, the resident server started there for the
  next call; `byj hook serve [--socket SOCK] [--idle SECONDS]` — that server,
  answering the same way until it is idle or its byj is upgraded
  (`brainyard.hook-serve`, byj-ywp); `byj hook claude-settings` — the Claude
  Code settings that install it, named as `byj hook codex-flags` is, a codex
  command line that carries it (RFC-012, byj-ahf.24); each installs the
  command that asks the server first. `settings`, `claude-settings`'s name in
  v0.3.18 and v0.3.19, still answers: the exit code. A payload that cannot be
  read has no opinion: the hook must never stop the person's work."
  [{:keys [questions log socket idle working-dir]} overrides]
  (let [overrides (dissoc overrides :working-dir)
        self (or (System/getProperty "jolt.launcher") "byj")
        named-socket (not-empty socket)
        socket (or named-socket (hook-serve/default-socket (System/getenv "HOME")))
        command (if (us/supported?)
                  (hook-serve/client-command self log socket)
                  (tool-hook/hook-command self log))]
    (case (first questions)
      "pretooluse"
      (do (when-let [out (hook-answer (slurp *in*) log overrides working-dir)]
            (println out))
          (flush)
          ;; the next call is the server's: started only when the command
          ;; that ran this one names a socket, as the installed one does
          (when named-socket (hook-serve/start! self socket))
          0)
      "serve"
      (do (hook-serve/detach-session!)
          (try
            (let [{:keys [done]} (hook-serve/serve! {:socket socket
                                                     :answer #(hook-answer %1 (or (not-empty %2) log) overrides nil)
                                                     :idle-ms (some-> idle (* 1000))
                                                     :self self
                                                     :path-env (System/getenv "PATH")})]
              @done)
            ;; another server answers there already: it serves
            (catch clojure.lang.ExceptionInfo e
              (when-not (= :live-owner (:reason (ex-data e))) (throw e))))
          0)
      ("claude-settings" "settings") (do (println (json/write-str (tool-hook/claude-settings command))) 0)
      "codex-flags" (do (println (tool-hook/codex-line command)) 0)
      (do (err-println (str "hook: unknown subcommand " (pr-str (first questions)) " — pretooluse, serve, claude-settings, codex-flags")) 2))))

(defn- emacs-command
  "`byj emacs install-el` — byj.el where the person's init can require it,
  and the lines that do (RFC-067 phase 4); `byj emacs changes SESSION
  [TURN]` — what a turn of the session changed, git's diff between the
  turn's snapshots, the last turn that changed files unless TURN is given,
  `--json` for `{:turn :ref :before :after :root :files :diff}` (phase 8,
  what byj-review opens in magit or vc): the exit code."
  [{:keys [questions json]} config]
  (let [[sub & more] questions]
    (case sub
      "install-el" (do (println (emacs/install-el-text (emacs/install-el! config))) 0)
      ;; what byj turns diff says, under the name byj-review has asked by
      ;; since phase 8 (RFC-069)
      "changes" (turns-diff "emacs changes" more json config)
      (do (err-println (str "emacs: unknown subcommand " (pr-str sub) " — install-el, changes")) 2))))

(defn- attach-command
  "`by ask --attach SESSION [-t SECS] [--json] QUESTION…` — brainyard's
  `cmd-ask-attach`: each question to the live session in turn, stopping at the
  first that fails. Exit 0 when every one was answered."
  [{:keys [attach questions json timeout-secs provider model]} config]
  (when (or provider model)
    (err-println "warning: -p/-m are ignored with --attach: the session answers with its own model"))
  (let [s (channel/find-session (dissoc config :session-id) attach " — `session list --live` names the live ones")
        timeout-ms (* 1000 (or timeout-secs 120))]
    (if (:error s)
      (do (if json
            (println (json/write-str {:success false :session-id attach :error (:error s)}))
            (err-println (str "error: " (:error s))))
          (System/exit 1))
      (let [turns (reduce (fn [acc q]
                            (let [r (try (channel/request! (:ask-socket-path s) {:op :ask :question q :timeout-ms timeout-ms}
                                                           (+ timeout-ms 5000))
                                         (catch Throwable e {:status :error :error (ex-message e)}))
                                  t (cond-> {:question q :success (= :ok (:status r))}
                                      (:answer r) (assoc :answer (:answer r))
                                      (:error r) (assoc :error (:error r))
                                      (:usage r) (assoc :usage (:usage r))
                                      true (assoc ::reply r))]
                              (if (:success t) (conj acc t) (reduced (conj acc t)))))
                          [] questions)
            last-reply (::reply (peek turns))
            ok? (every? :success turns)]
        (if json
          (println (json/write-str (cond-> {:success ok? :session-id (:session-id s)
                                            :provider (:provider last-reply) :model (:model last-reply)
                                            :answer (:answer (peek turns))
                                            :turns (mapv #(dissoc % ::reply) turns)}
                                     (not ok?) (assoc :error (:error (peek turns))))))
          (doseq [{:keys [answer error]} turns]
            (if error (err-println (str "error: " error)) (println answer))))
        (when-not ok? (System/exit 1))))))

(defn- ready-turn
  "What runs next when nobody has typed: a parked turn whose task finished
  (RFC-019), else a prompt of this session set to a time that is due
  (byj-gly.325), else a turn handed over the session channel (RFC-031)."
  [wakeups task-registry ch say config]
  (or (tasks/ready-wakeup! wakeups task-registry)
      (when config
        (try (schedule/ready-turn! (or (:project-dir config) (:working-dir config) ".") (some-> (:session-id config) str))
             (catch Throwable _ nil)))
      (channel-turn! ch say)))

(defn- waiting-reader
  "Read a line, or a parked turn — whichever arrives first (RFC-019).

  A wakeup fires because something ELSE finished, so it cannot wait for the
  person to press return. `read-line` would block until they did, so stdin is
  POLLED instead: `.ready` is true once a whole line has been typed, and
  between looks the wakeup list is checked. 60 ms of sleep costs nothing at a
  prompt and is the difference between a parked turn resuming and a parked turn
  waiting for company."
  [wakeups task-registry & [ch config]]
  (let [in (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))
        ;; a terminal: the line is read and edited here, out of canonical
        ;; mode, where a line past MAX_CANON (1024 on macOS) was lost
        ;; (byj-gly.315.12); a pipe is read as it always was
        tty? (stdin-tty?)
        out (java.io.OutputStreamWriter. System/out "UTF-8")]
    (fn [prompt]
      (print prompt)
      (flush)
      (if tty?
        ;; fd 0 itself, so that whether a key is waiting can be known and a
        ;; parked turn run meanwhile (byj-gly.315.15: through a reader,
        ;; .ready said true before anything was typed, and the prompt never
        ;; polled — a timer wakeup waited for the person's next line)
        (let [r (line-input/read-terminal-line out
                                               {:poll #(ready-turn wakeups task-registry ch println config)})]
          (if (map? r)
            (do (println (:polled r)) (:polled r))
            r))
      (if (and (empty? @wakeups) (nil? ch))
        ;; nothing parked and no channel: block as always, so end of input is
        ;; still nil. A wakeup can only be parked BY a turn, and no turn runs
        ;; while we wait. A channel is opened only on a terminal (`stdin-tty?`),
        ;; where polling cannot miss the end of a pipe
        (.readLine in)
        (loop []
          (if (.ready in)
            (.readLine in)
            (if-let [turn (ready-turn wakeups task-registry ch println config)]
              (do (println turn) turn)
              (do (Thread/sleep 60) (recur))))))))))

(defn- emit-to-terminal [kind text]
  (case kind
    :answer (println (str "\n" text "\n"))
    :error (err-println text)
    (println text)))

(defn- with-sigint
  "Run `f` with `handler` as the SIGINT handler, restoring the previous one
  after. jolt exposes no signal API; SIGINT reaches Chez's
  `keyboard-interrupt-handler`, which runs `handler` on the main thread while
  it waits on an effect (measured, byj-gly.27). Host-specific by design — the
  CLI is the one edge that owns the terminal."
  [handler f]
  (let [prev (try (scheme/call "keyboard-interrupt-handler") (catch Throwable _ nil))]
    (if-not prev
      (f)
      (do (scheme/call "keyboard-interrupt-handler" handler)
          (try (f)
               (finally (scheme/call "keyboard-interrupt-handler" prev)))))))

(defn- ask-cancellably
  "One `ask` whose turn Ctrl-C cancels: the turn stops structurally, and its
  in-flight call and subprocesses die with it."
  [q base]
  (let [!cancel (atom nil)
        done (promise)]
    (with-sigint (fn []
                   (err-println "\n⏹  cancelling…")
                   (some-> @!cancel (apply [])))
      (fn []
        (reset! !cancel (fx/run (agent/ask-task q base)
                                #(deliver done {:ok %})
                                #(deliver done {:err %})))
        (let [{:keys [ok err]} @done]
          (cond
            ok ok
            (fx/cancelled? err) {:error "cancelled" :terminated-by :cancelled}
            :else (throw err)))))))

(defn- run-ask [{:keys [questions json verbose] :as opts} base]
  (let [failed? (atom false)]
    (doseq [q questions]
      (let [result (try (ask-cancellably q base)
                        (catch Throwable e {:error (or (ex-message e) (str e))}))]
        (when (or (:error result) (not= :answer-channel (:terminated-by result)))
          (reset! failed? true))
        (cond
          json (println (json/write-str (update result :terminated-by #(some-> % name))))
          (:error result) (err-println "error:" (:error result))
          :else (do (println (:answer result))
                    (when verbose
                      (err-println (str "\n[" (name (or (:terminated-by result) :unknown))
                                        " · " (:iterations result) " iteration(s) · "
                                        (get-in result [:usage :llm-calls]) " LLM call(s) · tokens in/out "
                                        (get-in result [:usage :input-tokens]) "/"
                                        (get-in result [:usage :output-tokens]) "]")))))))
    (when @failed? (System/exit 1))))

(defn- resume-id
  "The id of the session this run continues, or nil to start fresh.

  Resolved BEFORE the config is handed to anything (`byj-gly.76`): a session's
  id is its snapshot's name (RFC-016), so resuming reuses the id and the turn
  transcripts (RFC-014) land beside it — and every sink built from the config
  has to name the same run."
  [{:keys [resume resume-latest]} config]
  (let [id (or resume (when resume-latest (persist/latest-id config)))]
    ;; asked to continue and there was nothing to continue. The named
    ;; --resume path says so when its id is unknown; saying nothing here
    ;; leaves someone who meant to carry on wondering (`byj-gly.75`)
    (when (and (nil? id) resume-latest)
      (err-println "no saved session to resume — starting fresh"))
    id))

(defn run-config
  "The config a run actually uses, with its session id settled.

  The id has to be fixed HERE, before anything captures the config: the event
  sinks, the agent and the transcript directory all have to name the same run,
  and only this layer knows whether this run continues another one. It was
  generated further down instead, inside `agent/ask`, so every sink built in
  `-main` closed over a nil id and silently wrote nothing — and a `--resume`
  rebound the id after those sinks had already been built (`byj-gly.76`)."
  [opts config command]
  (let [rid (when (= :run command) (resume-id opts config))]
    (cond-> (assoc config :session-id (or rid
                                          (:session-id config)
                                          (context/session-id)))
      rid (assoc :resume rid))))

(defn- resumed-base
  "The base, once its session is locked to this process, or nil when another
  live process holds it. Every live session is locked, a fresh one as well as
  a resumed one: the lock is what says a session is live (RFC-031)."
  [base]
  (let [config (:config base)
        id (or (:resume config) (:session-id config))]
    (cond
      (nil? id) base
      (not (persist/lock! config id))
      (do (err-println (str "error: session " id " is open in another running process (pid "
                            (persist/holder config id) ")"))
          nil)
      :else base)))

(def ^:private max-path-matches 40)

(defn path-completions
  "Files a half-typed `@token` could name. `@` is how a question names a file —
  brainyard's `extract-at-token` / `list-path-matches` — and the renderer knows
  nothing about a filesystem, so the walk is here (RFC-001).

  Bounded on purpose: hidden directories and build output are skipped, and the
  list stops at `max-path-matches`, because a completion menu is a glance and a
  repository is not."
  [dir draft]
  (let [at (str/last-index-of (str draft) "@")]
    (when at
      (let [token (subs (str draft) (inc at))]
        (when-not (str/includes? token " ")
          (let [root (io/file (or dir "."))
                skip? (fn [^java.io.File f]
                        (let [n (.getName f)]
                          (or (str/starts-with? n ".") (= "target" n))))
                walk (fn walk [^java.io.File f depth]
                       (cond
                         ;; the root is not judged by its own name: a project
                         ;; opened as "." is not a hidden directory
                         (and (pos? depth) (skip? f)) nil
                         (.isDirectory f) (when (< depth 6)
                                            (mapcat #(walk % (inc depth)) (.listFiles f)))
                         :else [(str/replace (.getPath f) (str (.getPath root) "/") "")]))
                matches (->> (walk root 0)
                             (filter #(str/starts-with? % token))
                             sort
                             (take max-path-matches))
                ;; @here: what the person is looking at in their editor,
                ;; read as the turn begins (session/editor-context, byj-gly.453)
                here (when (str/starts-with? "here" token) [["@here" "what you are looking at in emacs"]])
                items (into (vec here) (for [m matches] [(str "@" m) ""]))]
            (when (seq items)
              {:items items :from at})))))))

(defn model-completions
  "Models `/model ` could be asking for: the curated ones first, as `/model`'s
  picker lists them (`llm/curated-models`), then every provider the registry
  knows with the model it defaults to. The labels are what
  `llm/parse-lm-label` reads back."
  [draft]
  (let [d (str draft)]
    (when (str/starts-with? d "/model ")
      (let [typed (str/trim (subs d (count "/model ")))
            curated (for [{:keys [provider model description]} @llm/curated-models]
                      [(str (name provider) "/" model) (or description (name provider))])
            defaults (for [[provider {:keys [model]}] (sort-by key llm/providers)]
                       [(str (name provider) "/" model) (name provider)])
            labels (->> (concat curated defaults)
                        (filter #(str/starts-with? (first %) typed))
                        (reduce (fn [acc [l :as item]] (if (some #(= l (first %)) acc) acc (conj acc item))) []))]
        ;; nothing once the line names one exactly, as for commands: Enter
        ;; then sends it, instead of taking the completion it already is
        (when (and (seq labels) (not-any? #(= typed (first %)) labels))
          {:items (vec labels) :from (count "/model ")})))))

(defn- tui-completions
  "What the input line offers, in the order a line is most likely to mean:
  a command, then a model for `/model`, then a path for an `@token`."
  [config commands]
  (fn [draft]
    (or (tui/command-completions commands draft)
        (model-completions draft)
        (path-completions (or (:project-dir config) (:working-dir config)) draft))))

(defn- session-summary
  "The closing line. Without a final memory there is nothing to count — a
  session quit out from under a running turn ends that way — and a line of
  empty numbers reads like a bug in the counting."
  [{:keys [turn-count usage] :as final}]
  (if (nil? final)
    "\nsession closed"
    (str "\nsession closed · " turn-count " turn(s) · "
         (:llm-calls usage) " LLM call(s) · tokens in/out "
         (:input-tokens usage) "/" (:output-tokens usage))))

(defn resume-hint
  "The parting lines naming what was saved and how to get it back —
  brainyard's `format-resume-hint` (agent/tui/format.clj): `Session saved.`,
  or `N sessions saved.`, and a `byj run --resume <id>` line for each session
  worth reopening — one written to disk that holds a turn — beside the first
  thing asked in it, which is what a person recognises. Every session is
  listed, not only the one in front: closing the run closes them all, and an
  id is not guessable. nil when there is none, so a run that said nothing
  exits as quietly as ever."
  [finals]
  (let [saved (for [{:keys [session-id persisted? turn-count previous-turns]} finals
                    :when (and session-id persisted? (pos? (or turn-count 0)))]
                {:id session-id
                 :label (let [q (str/replace (str (:question (first previous-turns))) #"\s+" " ")]
                          (if (> (count q) 40) (str (subs q 0 39) "…") q))})
        width (apply max 0 (map #(count (:label %)) saved))]
    (when (seq saved)
      (str (if (= 1 (count saved)) "Session saved." (str (count saved) " sessions saved.")) "\n"
           (str/join "\n" (for [{:keys [id label]} saved]
                            (str "  " (when (seq label) (str label (apply str (repeat (- width (count label)) " ")) "  "))
                                 "byj run --resume " id)))
           ;; several: they come back together too (byj-gly.178)
           (when (> (count saved) 1)
             (str "\n  all of them, as they were: byj run --resume-run"))))))

(defn- tui-session
  "One session for the full-screen renderer: its renderer, the thunk that runs
  it, and the poll that resumes a parked turn — what `tui/run-many!` spawns.

  The first session is the run's own: it reads `-q` questions and resumes what
  `--resume` named. Every later one, started from the session list with a
  `task`, is a new session of its own: a new id, so it persists beside the
  others rather than over them, and the task as its first question. What they
  share is the run's — the config, the model, the permission mode a person
  cycles — and nothing a session owns: its tasks, wakeups, cancellers and the
  model /model switches it to are its own.

  The gate's ask channel is the renderer's overlay rather than the reader, and
  everything it decides still belongs to `brainyard.permission`: this hands it
  somewhere to ask and somewhere to report what it wrote (RFC-012)."
  [{:keys [questions verbose share rails-store]} base mode-box first? {:keys [task on-agents resume rails start-session]}]
  (let [persist? (:persist-session? (:config base))
        ;; a saved session to reopen (byj-gly.178) — unless another process
        ;; has it open, when a new one starts in its place and says why
        held-by (when (and resume persist?) (persist/holder (:config base) resume))
        base (cond
               (and resume (not held-by))
               (update base :config #(assoc % :session-id resume :resume resume))
               (and first? (not resume)) base
               :else (update base :config #(-> % (assoc :session-id (context/session-id)) (dissoc :resume))))
        ;; every session but the run's own first one is locked here, as that
        ;; one was before the run began (RFC-031)
        _ (when (and persist? (or resume (not first?)))
            (persist/lock! (:config base) (:session-id (:config base))))
        queued (atom (vec (if first? questions (when task [task]))))
        wakeups (tasks/wakeups)
        task-registry (tasks/registry)
        cancellers (atom {})
        ;; the model /model switches to: the session writes it, the footer's
        ;; rule shows it
        lm-box (atom nil)
        ;; the right rail: the project's files, changes and commits, read
        ;; when it comes into sight, at a turn's end and after a tool batch
        ;; while it is open — never polled (byj-gly.380)
        ;; what a row does lands in this session's renderer, made just below
        ;; (byj-gly.381): a file opens as a clicked path does, a diff is shown
        ;; in the transcript and never said to the model
        made (promise)
        ;; the rails as a restart left them, and each view's folds kept in
        ;; the run's store (byj-gly.457); nil keeps nothing
        kept (some-> rails-store (rail-state/keeper tui/cell tui/on-change!))
        ;; a clicked target, and a file a rail opens: a file goes to the
        ;; Emacs :emacs-server names when it is up (RFC-067), else to a
        ;; Terminal program that runs and opens files without the terminal
        ;; (`terminal/open-file!`, byj-gly.451), else as tui opens it
        project-dir (or (:project-dir (:config base)) (:working-dir (:config base)) ".")
        open-link (fn [{:keys [kind path line col] :as target} base-dir]
                    (or (when (= :file kind)
                          (when-let [f (links/resolve-file path base-dir)]
                            (or (emacs/open-file! (:config base) (str f) line col)
                                (terminal/open-file! project-dir (str f) line col))))
                        (tui/open-link! target base-dir)))
        open-file (fn [path]
                    (let [c (:cells @made)]
                      (reset! (:flash c) (or (open-link {:kind :file :path path} (:working-dir (:config base)))
                                             (str "could not open " path)))))
        project (project-views/views (or (:project-dir (:config base)) (:working-dir (:config base)) ".")
                                     {:cell tui/cell :kept kept
                                      :open open-file
                                      :show (fn [title text] (tui/show-block! (:cells @made) title text))})
        ;; the left rail: the project's own agents, skills, tools and the
        ;; rest, then the window's sessions, as sections (byj-gly.384)
        overview (project-overview/views (:config base) {:cell tui/cell :kept kept :open open-file})
        ;; and last, after the sessions, the project's open issues (byj-gly.402);
        ;; with no tracker, that beads are not available and /init sets them
        ;; up (byj-gly.458)
        issue-views (issues/views (or (:project-dir (:config base)) (:working-dir (:config base)) ".")
                                  {:cell tui/cell :kept kept
                                   :show (fn [title text] (tui/show-block! (:cells @made) title text))})
        ;; last of all, what the project remembers, when it keeps memory
        ;; (byj-gly.421)
        memory-views (memory-rail/views (:config base)
                                        {:cell tui/cell :kept kept
                                         :show (fn [title text] (tui/show-block! (:cells @made) title text))})
        ;; and after it, self-improvement: distillation's toggles and the
        ;; proposals waiting for a person (byj-gly.425)
        improve-views (self-improve-rail/views (:config base)
                                               {:cell tui/cell :kept kept
                                                :send! (fn [line quiet?] ((:send! @made) line quiet?))
                                                :show (fn [title text] (tui/show-block! (:cells @made) title text))})
        ;; the right rail's Activity: what the session does, as its events
        ;; arrive (byj-gly.411)
        activity (activity/views {:cell tui/cell
                                  :show (fn [title text] (tui/show-block! (:cells @made) title text))})
        ;; and Terminal: programs beside the session, each in a tmux session
        ;; that outlives this one (byj-gly.412)
        terminal-views (terminal/views (or (:project-dir (:config base)) (:working-dir (:config base)) ".")
                                       {:cell tui/cell
                                        :hand! tui/hand-over!
                                        :say! (fn [s] (reset! (:flash (:cells @made)) s))})
        ;; the calls a turn waits on, for ctrl+g (byj-gly.391): the
        ;; renderer's and the session's, as cancellers are
        backgrounders (atom {})
        r (tui/make {:verbose verbose :cancellers cancellers :backgrounders backgrounders :display-box (:display-box base)
                     ;; a clicked relative path resolves here; marks only with the mouse on
                     :working-dir (:working-dir (:config base))
                     :open-link open-link
                     :mouse? (not (false? (:enable-mouse (:config base))))
                     ;; a permission ask rings the terminal (byj-gly.328)
                     :attention (:attention-signal (:config base))
                     :max-lines (:scrollback-max-entries (:config base) 2500)
                     :permission-mode-box mode-box
                     :lm (:lm base) :lm-box lm-box
                     :rails-saved (some-> rails-store rail-state/frame)
                     :on-rails (when rails-store #(rail-state/save-frame! rails-store %))
                     :on-agents on-agents
                     ;; the frame's rails: the window's sessions, from run-many!
                     ;; (byj-gly.379)
                     :rails {:left [(frame/stack (.getName (.getCanonicalFile (io/file (or (:project-dir (:config base)) "."))))
                                                 (cond-> (into [(:view overview)] (:left rails))
                                                   issue-views (conj (:view issue-views))
                                                   memory-views (conj (:view memory-views))
                                                   true (conj (:view improve-views)))
                                                 {:cell tui/cell :kept kept})]
                             :right (conj (:views project) (:view activity) (:view terminal-views))}
                     :suggestions? (not (false? (:enable-input-suggestions (:config base))))
                     ;; a project's own commands too (byj-gly.351), as they are at the start
                     :commands (session/commands-for (:config base))
                     :completions (tui-completions (:config base) (session/commands-for (:config base)))})
        _ (deliver made r)
        read-input (fn [prompt]
                     (if-let [q (first @queued)]
                       (do (swap! queued #(vec (rest %)))
                           ((:say! r) :you q)
                           q)
                       ((:read-input r) prompt)))
        base (assoc base :permission-fn
                    (permission/asking-permission-fn (:ask r) (:config base)
                                                     #((:emit r) :info %)))
        ;; the session channel: another session, or a script, may ask this one
        ;; something; it becomes a turn when this one is waiting (RFC-031)
        say #((:emit r) :info %)
        ;; the gate that asks this session's person also answers its team
        ;; members' prompts (RFC-032)
        ch (open-channel base cancellers lm-box say {:permission-fn (:permission-fn base) :terminal? true})]
    (when held-by
      (say (str "session " resume " is open in another running process (pid " held-by ") — this is a new one")))
    {:renderer r
     :session-id (:session-id (:config base))
     :ready-turn #(ready-turn wakeups task-registry ch say (:config base))
     :session-fn #(try
                    (session/run (assoc base
                                      :read-input read-input
                                      :emit (:emit r)
                                      ;; the renderer in the tracer's place,
                                      ;; in front of the same sinks
                                      :on-event (let [on-event (fn [e d]
                                                                 (events/emit! (:on-event r) e d)
                                                                 ((:record! activity) e d)
                                                                 ;; the files the tools wrote, reverted in a
                                                                 ;; running editor's buffers (byj-gly.453), and
                                                                 ;; in the Emacs :emacs-server names (RFC-067)
                                                                 (when (= :agent.tools/results e)
                                                                   (terminal/after-tools! project-dir)
                                                                   (emacs/after-tools! (:config base)))
                                                                 ;; the session's state in Emacs's mode line (RFC-067 phase 7)
                                                                 (emacs/turn! (:config base) e d)
                                                                 (when (#{:session.turn/end :agent.tools/results} e)
                                                                   (when (tui/rail-open? (:cells r) :right)
                                                                     ((:refresh! project))
                                                                     ;; a program the agent started or stopped
                                                                     ((:refresh! terminal-views)))
                                                                   (when (tui/rail-open? (:cells r) :left)
                                                                     ((:refresh! overview))
                                                                     (some-> (:refresh! issue-views) (apply []))
                                                                     ;; memory$remember may have written
                                                                     (when (= :session.turn/end e)
                                                                       (some-> (:refresh! memory-views) (apply []))
                                                                       ;; a turn may have staged a proposal
                                                                       ((:refresh! improve-views))))))]
                                                  ;; built from THIS session's config: the
                                                  ;; run's names the first session, and every
                                                  ;; session after it was logged as that one
                                                  ;; (byj-gly.387)
                                                  (if-let [f (:event-bus-for base)]
                                                    (f (:config base) on-event)
                                                    on-event))
                                      :wakeups wakeups
                                      :task-registry task-registry
                                      :permission-mode-box mode-box
                                      :lm-box lm-box
                                      ;; the editor and the picker
                                      :present (:present r)
                                      ;; a CLI's own login run with the
                                      ;; terminal, and the screen taken back
                                      ;; after (/login, RFC-027)
                                      :hand-terminal tui/hand-over!
                                      :channel ch
                                      ;; an injected turn's approval, asked in
                                      ;; the gate's overlay (RFC-043)
                                      :approve (:approve r)
                                      ;; --share is the run's own session's
                                      :share (when first? share)
                                      :cancellers cancellers
                                      :backgrounders backgrounders
                                      ;; another session in this window, for
                                      ;; session$start (byj-gly.386)
                                      :start-session start-session))
                    (finally (channel/close! ch)))}))

(defn- run-session-tui
  "The sessions under the full-screen renderer (RFC-028): the run's own, and
  any started from the session list (← on an empty line). The renderer supplies
  RFC-027's seams and the loop owns this thread, so each session runs on one of
  its own — `tui/run-many!` is the edge that arranges it."
  [opts base]
  (let [config (:config base)
        persist? (:persist-session? config)
        ;; --resume-run: the set the last run had open (byj-gly.178). The fresh
        ;; id this run was given, and locked, goes unused then
        last-run (when (and (:resume-run opts) persist?) (persist/read-last-run config))
        _ (when (:resume-run opts)
            (if last-run
              (persist/unlock! config (:session-id config))
              (err-println "no earlier full-screen run to resume — starting a new session")))
        ;; the permission mode, which a person cycles with shift+tab: the
        ;; renderer writes it, every gate reads it (`permission/mode`) — one
        ;; for the run, so a session started later is as trusted as the first
        mode-box (atom (:permission-mode config :auto))
        first? (atom true)
        ;; the rails as the last run left them, one store for every session
        ;; in the window (byj-gly.457). A run that saves no session keeps no
        ;; rails either, so a test leaves nothing in its project
        rails-store (when (and persist? (not (false? (:keep-rails? config))))
                      (rail-state/store (or (:project-dir config) (:working-dir config) ".")))
        ;; saved sessions the list offers to resume, read at most every few
        ;; seconds: the list is drawn many times a second, and reading them
        ;; parses every snapshot
        saved (let [cache (atom [0 nil])]
                (fn []
                  (let [[at rows] @cache now (System/currentTimeMillis)]
                    (if (and rows (< (- now at) 3000))
                      rows
                      (let [rows (vec (for [{:keys [id problem updated-at turn-count last-question conversation-title]}
                                            (persist/summaries config)
                                            :when (and (not problem) (pos? (or turn-count 0))
                                                       (nil? (persist/holder config id)))]
                                        {:session-id id :updated-at updated-at
                                         :title (or conversation-title
                                                    (let [q (str/replace (str last-question) #"\s+" " ")]
                                                      (if (> (count q) 60) (str (subs q 0 59) "…") (not-empty q))))
                                         :last (str turn-count " turn(s)")}))]
                        (reset! cache [now (vec (take 12 rows))])
                        (vec (take 12 rows)))))))
        ;; this project's Claude Code sessions, and which run in this run's
        ;; tmux sessions — read at most every 2 s: the list is drawn eight
        ;; times a second (byj-gly.287.2, .3)
        claude-memo (atom nil)
        claude-now (fn []
                     (let [now (System/currentTimeMillis)
                           [at v] @claude-memo]
                       (if (and at (< (- now at) 2000))
                         v
                         (let [running (claude-host/running)
                               v (assoc (claude-agent/with-delegations
                                         (claude-host/with-running (claude-sessions/sessions (:project-dir config))
                                                                   (claude-host/running-in (:project-dir config)))
                                         ;; which of them agent$claude drives, and how far it got
                                         (claude-agent/records config))
                                        :running running)]
                           (reset! claude-memo [now v])
                           v))))
        ;; the delegation records, for the agent$claude children (byj-gly.298.3),
        ;; read at most every 2 s as the Claude Code sessions are
        records-memo (atom nil)
        records-now (fn []
                      (let [now (System/currentTimeMillis) [at v] @records-memo]
                        (if (and at (< (- now at) 2000))
                          v
                          (let [v (claude-agent/records config)] (reset! records-memo [now v]) v))))
        claude-open (fn [row]
                      (let [p (claude-host/plan row {:tmux? (claude-host/tmux?)
                                                     :running (:running (claude-now))
                                                     :project-dir (:project-dir config)
                                                     :claude (:claude-command config "claude")
                                                     :new-id #(str (java.util.UUID/randomUUID))
                                                     ;; nested in tmux: its status line says how to detach it (byj-on2)
                                                     :nested-prefix (claude-host/outer-prefix)})
                            said (claude-host/run-plan! p tui/hand-over!)]
                        ;; what it left behind — a new session, one now running
                        (reset! claude-memo nil)
                        said))
        ;; this project's Codex sessions, as Claude Code's (byj-gly.430.4): a
        ;; session opened here under a pending name is first renamed for its
        ;; thread; read at most every 2 s
        codex-memo (atom nil)
        codex-installed? (delay (try (zero? (:exit (fx/sh {:out :string :err :string} "sh" "-c"
                                                          (str "command -v " (first (str/split (str (:codex-command config "codex")) #"\s+"))))))
                                     (catch Throwable _ false)))
        codex-now (fn []
                    (let [now (System/currentTimeMillis)
                          [at v] @codex-memo]
                      (if (and at (< (- now at) 2000))
                        v
                        (let [_ (codex-host/claim-pending!)
                              v (codex-host/with-running (codex-sessions/sessions (:project-dir config))
                                                         (codex-host/running-in (:project-dir config)))]
                          (reset! codex-memo [now v])
                          v))))
        codex-open (fn [row]
                     (let [p (codex-host/plan row {:tmux? (codex-host/tmux?)
                                                   :running (codex-host/running)
                                                   :project-dir (:project-dir config)
                                                   :codex (:codex-command config "codex")
                                                   :nested-prefix (claude-host/outer-prefix)})
                           said (codex-host/run-plan! p tui/hand-over!)]
                       (reset! codex-memo nil)
                       said))
        ;; ctrl+s on a row: its session shared through the relay — an open
        ;; byj session or a live Claude Code one; the sidecars stop when
        ;; this does (RFC-043)
        claude-share (share/process-sharer config)
        results (try
                 (tui/run-many! (fn [spawn-opts]
                                 (tui-session (assoc opts :rails-store rails-store) base mode-box
                                              (let [f @first?] (reset! first? false) f) spawn-opts))
                               :initial (if last-run (mapv #(hash-map :resume %) (:sessions last-run)) [{}])
                               :front (if last-run (max 0 (.indexOf ^java.util.List (:sessions last-run) (:front last-run))) 0)
                               ;; what the next --resume-run brings back
                               :on-change (when persist?
                                            (fn [open front]
                                              (when (seq open)
                                                (persist/write-last-run! config {:sessions open :front front}))))
                               :saved (when persist? saved)
                               ;; this project's Claude Code sessions, beside its
                               ;; own (byj-gly.287.2) — read at most every 2 s:
                               ;; the list is drawn eight times a second
                               :claude-sessions claude-now
                               :claude-open claude-open
                               :claude-attachable? #(contains? (:running (claude-now)) %)
                               ;; and its Codex sessions (byj-gly.430.4)
                               :codex-sessions codex-now
                               :codex-open codex-open
                               :codex-attachable? #(contains? (:running (codex-now)) %)
                               :codex-new? @codex-installed?
                               ;; each session's subagents, live or ended, under it
                               ;; (byj-gly.298.3): what its trajectory recorded, with
                               ;; what its renderer shows over it when it is open
                               ;; a subagent's turns, to rebuild its transcript when
                               ;; its session's renderer did not keep it (byj-gly.298.4)
                               :child-records (fn [sid cid]
                                                (filterv #(= cid (:agent %)) (trajectory/read-trajectories config sid)))
                               :children (fn [sid live]
                                           (-> (children/merge-live (children/saved config sid) live)
                                               children/tree
                                               (children/with-claude (records-now) (claude-now))))
;; ctrl+g on the agent page: what the marked session changed (byj-gly.326)
                               ;; — in its worktree since it branched, or uncommitted in the
                               ;; main checkout — any session, brainyard's or Claude Code's
                               :session-diff (fn [kind sid dir]
                                               (let [d (worktree/session-diff
                                                        (or dir
                                                            (when (= :codex kind)
                                                              (some #(when (= sid (:id %)) (:dir %))
                                                                    (concat (:live (codex-now)) (:saved (codex-now)))))
                                                            (when (= :brainyard kind)
                                                              (:working-dir (persist/read-session config sid)))
                                                            (:project-dir config)))]
                                                 {:lines (worktree/diff-lines d)
                                                  :pager (when (seq (:stat d)) (worktree/pager-argv d))}))
                               :mouse? (not (false? (:enable-mouse config)))
                               :claude-share claude-share
                               :header {:title "brainyard"
                                        :detail (str (llm/lm-label (:lm base)) " · "
                                                     (or (:project-dir (:config base)) (:working-dir (:config base))))})
                 (finally ((:stop-all! claude-share))))]
    (doseq [{:keys [result thrown]} results]
      (when thrown (throw thrown))
      ;; what the session said as it closed went to a screen that is gone
      ;; now; this terminal is the one a person reads (byj-gly.252)
      (doseq [n (:closing-notices result)] (println n))
      (println (session-summary result)))
    (some-> (resume-hint (map :result results)) println)))

(def event-bus launch/event-bus)

(defn replaying-resume
  "`on-event` that also prints a resumed session's history, when `print?`
  says the output is a terminal. brainyard prints its `:resume-tail` inline
  the same way, and on a tty only: a pipe or a CI log wants the new turns, not
  the old ones again (byj-gly.135). The full-screen renderer replays through
  its own handler (RFC-028)."
  [on-event print? out]
  (fn [event data]
    (when (and (= :session/resumed event) print? (seq (:transcript data)))
      (out (session/transcript-text (:transcript data))))
    (when on-event (on-event event data))))

(defn- with-persona
  "The base as `--agent` makes it (`session/with-persona`), or the base itself
  when no agent is named; an agent nobody defines ends the run, saying so."
  [base {:keys [agent] :as opts}]
  (if agent
    (or (session/with-persona base agent opts)
        (do (err-println (str "error: no agent named " (pr-str agent) " — agent$list names them"))
            (System/exit 2)))
    base))

(defn watch-parent
  "Close a headless session once the process that started it is gone
  (byj-1wv.42). An A2A gateway starts one `byj run --serve` per A2A context and
  names itself in `BYJ_PARENT_PID`; a gateway that is SIGKILLed or crashes cannot
  close its sessions, and nothing else would — nine sat idle under init for
  days (provenance P-39). A detached watch asks `alive?` every `ms`, calls
  `on-gone` once and stops, even when `on-gone` throws: a write to the starter's
  broken pipe once did, and the session stayed. nil, and no watch, when `pid`
  is not a pid. An environment
  variable rather than a flag, because an older byj rejects a flag it does not
  know and a newer starter must still start it."
  [pid on-gone & {:keys [alive? ms] :or {alive? persist/alive? ms 2000}}]
  (when-let [n (some-> pid str str/trim parse-long)]
    (when (pos? n)
      (fx/run-detached
       (fx/ticking ms #(or (alive? (str n))
                           (do (try (on-gone) (catch Throwable _ nil))
                               false)))
       ::parent-watch))))

(defn- run-session-serve
  "brainyard's `by run --serve`: a session with no terminal whose only input
  is its session channel (RFC-031). It waits for a request, runs it as a turn,
  and waits again, until SIGINT, a `:close-session` request or the end of the
  process named in `BYJ_PARENT_PID` (`watch-parent`) ends it; a parked turn
  still resumes. It has no one to ask for permission, so a gate
  that would ask refuses instead, as in any headless run (RFC-012)."
  [{:keys [agent label leader] :as opts} base]
  (let [base (with-persona base opts)
        ;; a member asks its leader's person; a session with no leader has no
        ;; one to ask, and a gate that would ask refuses (RFC-012)
        base (cond-> base
               leader (assoc :permission-fn (channel/forwarding-permission-fn (:config base) leader
                                                                              (or label agent))))
        wakeups (tasks/wakeups)
        task-registry (tasks/registry)
        cancellers (atom {})
        closing? (atom false)
        ch (open-channel (assoc-in base [:config :ask-channel-enabled?] true) cancellers nil err-println
                         {:close! #(reset! closing? true) :label (or label agent)})
        _ (when (or (nil? ch) (:problem ch))
            (err-println "error: a headless session needs its channel, and it could not listen")
            (System/exit 1))
        read-input (fn [prompt]
                     (loop []
                       (if @closing?
                         nil
                         (if-let [turn (ready-turn wakeups task-registry ch println (:config base))]
                           (do (println (str prompt turn)) turn)
                           (do (Thread/sleep 50) (recur))))))]
    (println (str "serving session " (:session-id (:config base))
                  (when (or label agent) (str " (" (or label agent) ")"))
                  " at " (:path ch) " — jolt ask:byj --attach " (:session-id (:config base)) " \"…\""))
    (let [stop-watch (watch-parent (System/getenv "BYJ_PARENT_PID")
                                   (fn []
                                     (session/cancel-turn! cancellers)
                                     (reset! closing? true)
                                     ;; last, and allowed to fail: the starter's
                                     ;; end usually broke the pipe this writes to
                                     (try (err-println "the process that started this session has gone — closing")
                                          (catch Throwable _ nil))))
          final (with-sigint (fn []
                               ;; the running turn is cancelled, and the session
                               ;; closes once it has settled
                               (session/cancel-turn! cancellers)
                               (reset! closing? true))
                  #(try (session/run (assoc base :read-input read-input :emit emit-to-terminal
                                            :wakeups wakeups :task-registry task-registry
                                            ;; headless: no one approves an
                                            ;; injected turn, so none is lent
                                            :share (:share opts)
                                            :channel ch :cancellers cancellers))
                        (finally (some-> stop-watch (apply []))
                                 (channel/close! ch))))]
      (println (session-summary final)))))

(defn- run-session [{:keys [questions share]} base]
  ;; a piped session has no channel (RFC-031), and a share is reached only
  ;; through one (RFC-043)
  (when (and share (not (stdin-tty?)))
    (err-println "--share needs the session channel, and a session reading a pipe has none (RFC-031) — run it on a terminal, or headless with --serve")
    (System/exit 2))
  (let [;; -q/positional questions are fed first, then stdin takes over
        queued (atom (vec questions))
        ;; the session owns both, so the reader can see a turn park itself
        wakeups (tasks/wakeups)
        task-registry (tasks/registry)
        cancellers (atom {})
        ;; the session channel, on a terminal only: a piped session reads to
        ;; the end of its pipe and must not poll past it (RFC-031)
        ch (when (stdin-tty?) (open-channel base cancellers nil err-println {:terminal? true}))
        wait (waiting-reader wakeups task-registry ch (:config base))
        ;; an injected turn's approval, asked here and answered at the prompt
        ;; with /share allow|refuse (RFC-043, byj-1wv.3.12)
        approver (share/inline-approver println)
        read-input (fn [prompt]
                     (if-let [q (first @queued)]
                       (do (swap! queued #(vec (rest %)))
                           (println (str prompt q))
                           q)
                       (wait prompt)))]
    (println "brainyard-jolt session — /help for commands, Ctrl-C cancels a turn, /quit or Ctrl-D to exit")
    (let [;; the gate's ask channel: the session's own reader, so a prompt
          ;; shares the terminal with the turn (RFC-012)
          ;; …and its `remember` answer writes the grant to config.edn, which is
          ;; the only route by which a permission key is ever persisted (RFC-012)
          base (assoc base
                      :permission-fn (permission/asking-permission-fn read-input (:config base) err-println)
                      :on-event (replaying-resume (:on-event base) (tui/usable-terminal?) println))
          final (with-sigint (fn []
                               (when-not (session/cancel-turn! cancellers)
                                 ;; no turn running: we are at the prompt
                                 (err-println "")
                                 (System/exit 130)))
                  #(try (session/run (assoc base :read-input read-input :emit emit-to-terminal
                                            ;; inline, a command a person runs has
                                            ;; the terminal as it is (/login, RFC-027)
                                            :hand-terminal (fn [argv env]
                                                             (apply fx/sh (cond-> {:in :inherit :out :inherit :err :inherit}
                                                                            env (assoc :env env))
                                                                    argv))
                                            :wakeups wakeups :task-registry task-registry
                                            :approve (:approve approver)
                                            :approve-answer (:answer! approver)
                                            :share share
                                            :channel ch :cancellers cancellers))
                        (finally (channel/close! ch))))]
      (println (session-summary final))
      (some-> (resume-hint [final]) println))))

(defn- read-params-file [path]
  (let [m (edn/read-string (slurp path))]
    (when-not (and (map? m) (every? string? (keys m)))
      (throw (ex-info (str path ": expected {\"predictor-id\" params}") {})))
    m))

(defn- show-predictor [id {:keys [working-dir params-file]}]
  (let [wd (or working-dir (System/getProperty "user.dir"))
        p (predictor/load-predictor id (predictor/default-roots wd "predictors"))
        r (predictor/resolve-params p {:params (some-> params-file read-params-file)
                                       :params-roots (predictor/default-roots wd "programs")})]
    (pp/pprint (-> p
                   (update-in [:signature :instructions] #(str (subs % 0 (min 160 (count %))) " …"))
                   (assoc :resolved-params r)))))

(defn- flow-command
  "`by flow check <tree>` (RFC-022) — every problem with a loop, before it runs.
  The tree is checked against the capabilities it would run with, plus the
  session loop's own defaults, which the session merges later (byj-gly.64)."
  [{:keys [questions json] :as opts-hash} lm config]
  (let [[sub tree node] questions
        out (fn [x] (if json (println (json/write-str x)) (pp/pprint x)))]
    (case (str sub)
      "check"
      (let [ctx (agent/make-ctx {:lm lm :config config})
            opts (-> (flow/ctx-opts ctx) (update :registry merge session/registry))
            {:keys [tree problems hash]} (flow/check-tree-name (or tree agent/react-loop) opts)
            out {:ok (empty? problems) :problems (vec problems)
                 :nodes (count (bt/nodes (or tree []))) :hash hash}]
        (if json
          (println (json/write-str out))
          (if (empty? problems)
            (println (str "ok — " (:nodes out) " nodes, no problems · " hash))
            (do (doseq [{:keys [node problem]} problems]
                  (err-println (format "%-34s %s" (str node) problem)))
                (err-println (str (count problems) " problem(s)"))
                (System/exit 1)))))
      "status"
      (let [ctx (agent/make-ctx {:lm lm :config config})
            opts (-> (flow/ctx-opts ctx) (update :registry merge session/registry))
            {:keys [tree problems]} (flow/check-tree-name (or tree agent/react-loop) opts)]
        (if-not tree
          (do (err-println (:problem (first problems))) (System/exit 1))
          (let [st (flow/status tree opts)]
            (if json
              (println (json/write-str st))
              (do (doseq [{:keys [node kind implements status problems]} (:detail st)]
                    (println (format "%-36s %-10s %-9s %s" (str node) (name kind) status
                                     (or (first problems) implements))))
                  (println (format "%d nodes · %d ready · %d missing · %d failing"
                                   (:nodes st) (:ready st) (:missing st) (:failing st))))))))

      ("brief" "probe")
      (let [ctx (agent/make-ctx {:lm lm :config config})
            opts (-> (flow/ctx-opts ctx) (update :registry merge session/registry))
            {:keys [tree problems]} (flow/check-tree-name (or tree agent/react-loop) opts)]
        (cond
          (not tree) (do (err-println (:problem (first problems))) (System/exit 1))
          (str/blank? (str node)) (do (err-println (str "flow " sub ": a node id is required"))
                                      (System/exit 1))
          :else (let [id (keyword (str/replace (str node) #"^:" ""))]
                  (out (if (= "brief" (str sub))
                         (flow/brief tree id opts)
                         (flow/probe tree id {} ctx))))))

      "patch"
      (let [ctx (agent/make-ctx {:lm lm :config config})
            opts (-> (flow/ctx-opts ctx) (update :registry merge session/registry))
            ops (try (edn/read-string (or node "[]")) (catch Throwable _ ::bad))]
        (cond
          (str/blank? (str tree)) (do (err-println "flow patch: a tree file path is required")
                                      (System/exit 1))
          (or (= ::bad ops) (not (sequential? ops)))
          (do (err-println "flow patch: OPS must be an EDN vector of ops") (System/exit 1))
          :else (let [r (flow/patch tree (:expect-hash opts-hash) ops opts)]
                  (out r)
                  (when (:error r) (System/exit 1)))))

      (do (err-println (str "flow: unknown subcommand " (pr-str sub)
                            " — check|status|brief|probe|patch"))
          (System/exit 1)))))

(defn- programs-command
  "`by programs …` (RFC-021). ACCEPT AND REJECT LIVE ONLY HERE: a params file
  rewrites the system message of every later turn, so installing one is a
  person's decision, and `program$*` deliberately has no tool for it."
  [{:keys [questions working-dir json yes] :as opts} lm config]
  (let [[sub a b] questions
        out (fn [x] (if json (println (json/write-str x)) (pp/pprint x)))]
    (case (str sub)
      "list" (out {:predictors (:predictors ((:fn (first (filter #(= "program$list" (:name %))
                                                                 (programs/commands config))))
                                             {} {:config config}))})
      "proposals" (out {:proposals (programs/list-proposals config a)})
      ;; a model, tier, dataset or split that cannot be had is said in a line,
      ;; as `exam`'s are, not as an unhandled exception and its trace (byj-gly.440)
      "eval" (try (out (programs/eval-predictor config a b (or (:metric opts) "exact-match")
                                                (-> (select-keys opts [:split :repeats :parallel :tier
                                                                       :max-calls :max-tokens])
                                                    (assoc :lm (or (:lm opts) lm)))))
                  (catch clojure.lang.ExceptionInfo e (err-println (str "programs eval: " (ex-message e))) (*exit* 1)))
      "compile" (try (out (programs/compile-predictor config a b
                                                      (-> (select-keys opts [:optimizer :repeats :parallel :tier
                                                                             :teacher-lm :teacher-tier
                                                                             :max-bootstrapped :max-calls :max-tokens])
                                                          (assoc :metric (or (:metric opts) "exact-match")
                                                                 :lm (or (:lm opts) lm)))))
                     (catch clojure.lang.ExceptionInfo e (err-println (str "programs compile: " (ex-message e))) (*exit* 1)))
      "accept"
      (let [dir (io/file (programs/proposals-dir config a) (str b))]
        (println (str "Proposal " b " for " a))
        (println (str "Review: " (.getPath (io/file dir "REVIEW.md"))))
        (println "Accepting REPLACES the params every later turn reads.")
        (if-not (or yes (do (print "Accept? [y/N] ") (flush)
                            (contains? #{"y" "yes"} (str/lower-case (str/trim (or (read-line) ""))))))
          (println "not accepted")
          (out (programs/accept-proposal! config a b))))

      "reject" (out (programs/reject-proposal! config a b))
      (do (err-println (str "programs: unknown subcommand " (pr-str sub)
                            " — list|eval|compile|proposals|accept|reject"))
          (System/exit 1)))))

(defn sandbox-opts
  "The sandbox options a run was given, flag then env (brainyard's precedence)."
  [opts env-fn working-dir]
  {:sandbox? (or (:sandbox opts) (= "1" (env-fn "BY_SANDBOX")))
   :child? (= "1" (env-fn "BY_SANDBOX_CHILD"))
   :profile-file (or (:sandbox-profile opts) (env-fn "BY_SANDBOX_PROFILE"))
   :network? (not (or (:sandbox-no-network opts) (= "1" (env-fn "BY_SANDBOX_NO_NETWORK"))))
   :extra-writes (sandbox/parse-allow-writes (or (:sandbox-allow-write opts) (env-fn "BY_SANDBOX_ALLOW_WRITE") [])
                                             working-dir)
   :home (System/getenv "HOME")
   :cwd working-dir
   :project-dir working-dir
   :tmpdir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))})

(defn- launch-sandbox!
  "Relaunch this command under sandbox-exec and exit with the child's status.
  Returns false when the run should carry on unsandboxed."
  [opts env-fn args]
  (let [{:keys [ok? reason]} (sandbox/available?)]
    (cond
      (not ok?) (do (err-println (str "⚠  " reason " Running unsandboxed.")) false)
      :else
      (if-let [self (sandbox/self-argv env-fn)]
        (let [child (vec (concat self (remove #{"--sandbox"} args)))]
          (println (sandbox/banner opts))
          (System/exit (sandbox/relaunch! opts child)))
        (do (err-println (str "⚠  could not resolve how to relaunch: set BY_SANDBOX_SELF to the command, "
                              "e.g. BY_SANDBOX_SELF=target/byj. Running unsandboxed."))
            false)))))

(declare update-command*)

(defn- install-notes
  "The other copies of byj on PATH, shadowed or shadowing this one
  (byj-gly.181.13): `update/install-lines`, with what each says its
  version is."
  []
  (let [checkout (version/source-root)]
    (update/install-lines
     {:running (when-not checkout (update/real-path (update/executable-path (persist/own-pid) update/shell)))
      :path-env (System/getenv "PATH")
      :version-of (fn [cmd p]
                    (some-> (init/version-of p (if (= "byj" cmd) ["--version"] ["version"]))
                            (cond->> (= "byj" cmd) (str "v"))))})))

(defn- update-command
  "`byj update` (RFC-029, byj-gly.181.3): `update-command*`, then, when it
  went well, the other copies of byj on PATH that it did not touch
  (byj-gly.181.13). Returns the exit code."
  [opts config]
  (let [code (update-command* opts config)]
    (when (zero? code)
      (doseq [{:keys [text]} (filter :note? (install-notes))]
        (println (str "note " (subs text 2)))))
    code))

(defn- update-command*
  "`byj update` (RFC-029, byj-gly.181.3): check for, install, or roll back a
  release of byj, by the installer that put it there: a Homebrew install
  through brew (byj-gly.181.12), a binary by its own swap, a checkout by
  git. A program built on byj — the share sidecar among them — updates
  itself (byj-ahf.7). Returns the exit code."
  [{:keys [check rollback yes]} config]
  (let [repo (:update-source config)
        current (version/version)
        checkout (version/source-root)
        sh update/shell
        exe (when-not checkout (update/executable-path (persist/own-pid) sh))
        origin (when-not checkout (update/install-origin exe))
        brew? (= :homebrew (:origin origin))
        confirm (fn [question]
                  (print (str question " [y/N] "))
                  (flush)
                  (or yes (boolean (#{"y" "yes"} (some-> (read-line) str/trim str/lower-case)))))]
    (cond
      (and (not checkout) (nil? exe))
      (do (err-println "update: cannot tell which file this byj runs from") 1)
      rollback
      (cond
        checkout (do (err-println "update --rollback swaps a binary with the one it replaced; a checkout goes back with git checkout") 2)
        ;; brew keeps no previous keg to switch back to (decided 2026-10-14)
        brew? (do (err-println "update --rollback: a Homebrew install has no previous version to go back to") 2)
        :else
        (let [{:keys [error]} (update/rollback! exe)]
          (if error
            (do (err-println (str "update: " error)) 1)
            (do (println (str "rolled back: " exe " is the binary it replaced; --version says which"))
                0))))
      :else
      (let [tok (update/token (into {} (System/getenv)) sh)
            rel (update/latest-release repo tok http/get)]
        (cond
          (:error rel) (do (err-println (str "update: " (:error rel))) 1)
          brew?
          (let [bp (update/brew-plan repo origin)
                stale? (update/newer? (:version rel) current)
                cmds (mapv #(str/join " " %) (update/brew-commands bp))]
            (cond
              (not stale?)
              (do (println (str "byj " current " is up to date (latest release: " (:version rel) "), installed by Homebrew")) 0)
              check
              (do (println (str "latest release: " (:version rel) " — " (:url rel) " (this byj is " current ")"))
                  (println (str "byj update installs it, through " (str/join " && " (rest cmds))))
                  0)
              :else
              (do (println (str "byj " current " was installed by Homebrew; to " (:version rel) " by:"))
                  (doseq [c cmds] (println (str "  " c)))
                  (if-not (confirm "go ahead?")
                    (do (println "not updated") 1)
                    (let [{:keys [error out]} (update/update-homebrew! bp sh)]
                      (doseq [o out :when (not (str/blank? o))] (print o) (flush))
                      (if error
                        (do (err-println (str "update: " error)) 1)
                        (do (println "updated by Homebrew; `byj --version` says what is installed") 0)))))))
          :else
          (let [rows (update/plan {:release rel :current current :checkout checkout :exe exe})
                lines (update/plan-lines rows)]
            (cond
              (update/nothing-to-do? rows)
              (do (println (str "byj " current " is up to date (latest release: " (:version rel) ")"))
                  0)
              check
              (do (println (str "latest release: " (:version rel) " — " (:url rel)))
                  (doseq [l lines] (println l))
                  (println "byj update does it")
                  0)
              :else
              (do (println (str "byj update to " (:version rel) ":"))
                  (doseq [l lines] (println l))
                  (if-not (confirm "go ahead?")
                    (do (println "not updated") 1)
                    (let [r (if checkout
                              (update/update-checkout! checkout (:tag rel) sh)
                              (let [p (update/platform (:out (sh "uname" "-s")) (:out (sh "uname" "-m")))
                                    assets (for [{:keys [component action]} rows :when (= :replace action)]
                                             (let [a (update/asset-name component (:version rel) p)]
                                               (str a (when-let [n (get-in rel [:sizes a])] (format " (%.1f MB)" (/ n 1e6))))))]
                                ;; tens of megabytes, and a minute or more: say so first
                                (println (str "downloading " (str/join " and " assets) " from " repo " …"))
                                (update/update-pair! {:repo repo :tok tok :get http/get :sh sh} rel p rows)))]
                      (cond
                        (:error r) (do (err-println (str "update: " (:error r))) 1)
                        checkout (do (println (str checkout " is at " (:checked-out r) ", detached at the tag (`git switch -` goes back); "
                                                   "run `jolt build:byj` to rebuild the binary"))
                                     0)
                        :else (do (doseq [{:keys [asset installed]} (:installed r)]
                                    (println (str "installed " asset " at " installed " (sha256 checked)")))
                                  ;; the release installed may predate `update --rollback` (v0.2.0
                                  ;; does), so the plain way back is said too (byj-gly.181.3)
                                  (println (str "to go back: byj update --rollback"
                                                (when-let [{:keys [installed previous]} (first (:installed r))]
                                                  (when previous (str ", or mv -f " previous " " installed)))))
                                  0))))))))))))

(defn- init-command
  "`byj init [DIR] [--check | --install] [--prefix P] [--no-guide]` (RFC-040,
  byj-gly.309.6): prepare DIR (made when absent; the project when not given)
  for brainyard without a model — the starting files, beads by bd, the
  starter issues when this run brought beads in (unless `--no-guide`), the
  check, and what to do next (byj-gly.337.4) — or, with `--check`, only
  report, and what to do next. `--install` is the person's yes, given in
  advance, to install every missing tool a package manager can; the AGENTS.md
  draft needs a model, so it is `/init` in a session. The exit code: 1 when a
  required tool is missing, else 0."
  [{:keys [check install prefix no-guide questions]} config]
  (let [dir (.getCanonicalPath (doto (io/file (or (first questions) (:project-dir config) ".")) .mkdirs))
        before (init/check config {:dir dir})]
    (if check
      (println (str (:text before) "\n\n" (:text (init/next-step config {:dir dir}))))
      (let [wrote (init/scaffold config {:dir dir})
            beads (init/beads config {:dir dir :prefix prefix})
            ;; as /init's plan decides it: on the run that brings beads in
            onboarded (when (and (not no-guide) (not (:beads? (:project before))))
                        (init/onboard config {:dir dir}))
            installed (when install
                        (init/install config {:permission-fn (constantly {:allowed true})}
                                      {:tools (map :name (remove :available? (:tools before)))}))
            after (init/check config {:dir dir})]
        (println (str/join "\n\n" (remove str/blank? [(:text wrote) (:text beads) (:text onboarded) (:text installed) (:text after)
                                                        (:text (init/next-step config {:dir dir}))
                                                        ;; only with code to draft from: an empty one waits (byj-gly.315.10)
                                                        (when (and (seq (get-in after [:project :build-files]))
                                                                   (= :placeholder (get-in after [:project :project-section])))
                                                          "To draft this project's section of AGENTS.md from the repository, run /init in a session here.")])))))
    (if (seq (:missing-required (init/check config {:dir dir}))) 1 0)))

(defn- web-command
  "`byj run --web` (and `--web-tmux`): this session shared in the browser
  through ttyd (brainyard's `--web`, byj-gly.22). The child is this program,
  run again with the same arguments but the `--web*` flags."
  [opts args]
  (let [env #(System/getenv %)
        path (env "PATH")
        o (web-share/options opts env)
        cred (web-share/resolve-credential o)
        exe (update/executable-path (persist/own-pid) (fn [& cmd] (apply fx/sh {:out :string :err :string} cmd)))
        ;; this program again: `run` and what it was given, but the --web flags
        child (vec (concat (:argv (web-share/self-argv env exe)) ["run"]
                           (web-share/strip-flags (if (= "run" (first args)) (rest args) args))))
        ttyd-opts {:bind (:bind o) :port (:port o) :credential (:credential cred) :writable? (:writable? o)
                   :max-clients (:max-clients o) :once? (:once? o)}]
    (cond
      (not (init/on-path "ttyd" path))
      (do (err-println (str "ttyd is not on PATH; install it: " (web-share/ttyd-hint))) 1)

      (and (:tmux? o) (not (init/on-path "tmux" path)))
      (do (err-println "tmux is not on PATH (needed for --web-tmux): brew install tmux · sudo apt-get install -y tmux") 1)

      (:tmux? o)
      (let [r (web-share/serve-tmux! {:child-argv child} ttyd-opts
                                     (fn [attach log] (str (web-share/banner (assoc o :tmux? true) cred {:attach attach})
                                                           "\n   ttyd's log: " log)))]
        (if (:error r) (do (err-println (:error r)) 1) 0))

      :else
      (do (println (web-share/banner o cred))
          (flush)
          (web-share/serve! (web-share/build-ttyd-argv (assoc ttyd-opts :child-argv child)))))))

(defn- agent-command
  "`byj agent [--name NAME] [--json]` (byj-1wv.47): every agent with its
  reach, least first, or one in full. The one is named by `--name`; a bare
  word, how it was named until byj-gly.441, is refused with the flag said.
  Share's builder reads an agent with `byj-share agent build` (RFC-070 D4)."
  [{:keys [questions json] :as opts} config]
  (let [nm (:name opts)
        all (when-not (or nm (seq questions))
              (->> (cons "default" (map :name (:agents (agents/load-agents config))))
                   (keep #(agent-reach/agent-reach config %))
                   (sort-by (juxt #(.indexOf ^java.util.List agents/reach-order (:level %)) :name))))
        one (when nm (agent-reach/agent-reach config nm))
        out (fn [x] (println (json/write-str x)))]
    (cond
      (seq questions)
      (do (err-println (str "byj agent takes the agent by --name: byj agent --name " (first questions))) 2)
      (and nm (nil? one)) (do (err-println (str "no agent named " (pr-str nm) " — byj agent lists them")) 1)
      json (do (out (or one all)) 0)
      one (do (println (str (:name one) " — " (:description one)))
              (println (str "  reach:   " (name (:level one))))
              (when (seq (:writes one)) (println (str "  writes:  " (str/join ", " (:writes one)))))
              (when-let [sh (:shell one)]
                (println (str "  shell:   " (if (:any sh) "any command" (str "only " (str/join ", " (:programs sh)))))))
              (when (seq (:network one)) (println (str "  network: " (str/join ", " (:network one)))))
              (when (seq (:hands-off one)) (println (str "  hands off: " (str/join ", " (:hands-off one)))))
              (when (seq (:services one)) (println (str "  services: " (str/join ", " (:services one)))))
              (when (seq (:service-writes one)) (println (str "  changes there: " (str/join ", " (:service-writes one)))))
              (println (str "  permission mode: " (name (:permission-mode one))))
              (println (str "  model:   " (:model one)))
              0)
      :else (do (doseq [r all]
                  (println (format "%-14s %-11s %s" (:name r) (name (:level r)) (:description r))))
                0))))

(declare main*)

(defn broken-pipe?
  "Did `e`, or anything that caused it, come of writing to a pipe whose
  reader has gone — `byj … | head` once head has its lines (byj-gly.368)?"
  [e]
  (boolean (some #(and (instance? java.io.IOException %)
                       (re-find #"(?i)broken pipe" (str (ex-message %))))
                 (take-while some? (iterate ex-cause e)))))

(defn -main
  "The CLI. A pipe closed under it — `byj … | head` — ends it quietly with
  128 + SIGPIPE, as `ls` or `git` would end there, rather than with jolt's
  report of an unhandled IOException and its stack (byj-gly.368).

  Whatever the command, it lets go of the agent and `future` threads once it
  is done: from jolt 0.8.21 an idle one keeps the process alive for its 60 s
  keep-alive, as on the JVM, and `byj --version` printed in 2 s and exited in
  62 (provenance P-41). It is the process's end, so nothing in-process calls
  it — a test calls `main*`, or every later `future` there is refused."
  [& args]
  (try
    (apply main* args)
    (catch Throwable e
      (if (broken-pipe? e)
        (System/exit 141)
        (throw e)))
    (finally (shutdown-agents))))

(defn- main* [& args]
  (let [{:keys [command help errors questions provider model working-dir max-iterations
                verbose print-tree print-config print-predictor params-file sessions]
         :as opts} (parse-args args)
        overrides (cond-> (select-keys opts [:permission-mode :tool-approval-patterns :tool-allow-tools
                                             :tool-deny-tools :tool-ask-effects :tool-deny-effects
                                             :allowed-dirs :path-permission? :persist-session?
                                             :todo-slug])
                    working-dir (assoc :working-dir working-dir)
                    max-iterations (assoc :max-iterations max-iterations))
        tree-name (or (:loop opts) (if (= :run command) session/session-loop agent/react-loop))]
    (cond
      ;; brainyard's layout: the commands, a command's, one subcommand's
      help (print (cli-help/text (help-words opts) (version/version-long)))

      ;; an option no command knows, before any command runs: once checked
      ;; only ahead of ask and run, so `byj --bogus agent` listed the agents
      ;; and said nothing of --bogus (byj-gly.437)
      (seq errors)
      (do (err-println (str/join "\n" errors))
          ;; the help of the command it was given to, not every command's
          (err-println (str "\n" (cli-help/text (help-words opts) nil)))
          (*exit* 2))

      ;; shared in the browser through ttyd (byj-gly.22): a session, run again
      ;; inside it — not here, which only launches
      (and (#{:run :ask} command) (empty? questions) (web-share/wanted? opts #(System/getenv %)))
      (do (System/exit (web-command opts args)))

      ;; --share shares a session, and only to a target it knows (RFC-043)
      (and (contains? opts :share) (not= :run command))
      (do (err-println "--share shares a session: byj run --share link (jolt run:byj --share link)") (System/exit 2))
      (and (contains? opts :share) (share/target-problem (:share opts)))
      (do (err-println (share/target-problem (:share opts))) (System/exit 2))

      (:version opts) (println (str "byj " (version/version-long)))

      ;; a newer release: checked, installed, rolled back (RFC-029)
      (= :update command)
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})]
        (System/exit (update-command opts config)))

      ;; byj.el for the person's init (RFC-067 phase 4)
      (= :emacs command)
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})]
        (*exit* (emacs-command opts config)))

      ;; another agent's tool calls through byj's gates (RFC-012, byj-ahf.24)
      (= :hook command)
      (*exit* (hook-command opts overrides))

      ;; a session's turns, as git snapshots of the work tree (RFC-069)
      (= :turns command)
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})]
        (*exit* (turns-command opts config)))

      ;; a loop measured on the project's own work (RFC-071)
      (= :loops command)
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            env (env-fn wd)
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn env})
            ;; only an evaluation runs turns, so only it is given a model
            lm (when (#{"eval" "propose"} (first (:questions opts)))
                 (let [[p m] (run-lm-choice provider model config)] (launch/make-lm p m config env)))]
        (*exit* (loops-command opts lm config)))

      ;; where this machine stands, as /doctor says it (byj-gly.324)
      (= :doctor command)
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})]
        (println (session/doctor-report {:config config}))
        (shutdown-agents)
        (System/exit 0))

      ;; what each agent can make happen, as a shared session would run it
      ;; (byj-1wv.47): byj-share asks before sharing one that writes
      ;; an ACP agent on stdio, for an application's SDK or an editor
      ;; (RFC-053): every option but the defaults below is the client's
      (= :acp command)
      (do (acp/serve! (select-keys opts [:provider :model :agent :permission-mode]))
          (shutdown-agents)
          (System/exit 0))

      (= :agent command)
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})]
        (System/exit (agent-command opts config)))

      ;; preparing a project, with no model (RFC-040)
      (= :init command)
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})]
        (System/exit (init-command opts config)))

      ;; the tree as it would run here, and where each of its loops was found:
      ;; the project's own before the bundled one (RFC-071)
      print-tree
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})
            pd (:project-dir config)]
        (doseq [src (agent/tree-sources tree-name pd)]
          (println (project-loops/source-line src)))
        (pp/pprint (agent/load-tree tree-name pd)))

      sessions
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})
            rows (persist/summaries config)]
        (if (empty? rows)
          (println "no saved sessions")
          (doseq [{:keys [id turn-count usage updated-at problem]} rows]
            (println (format "%-24s %s" id
                             (or problem
                                 (str (or turn-count 0) " turn(s) · "
                                      (:llm-calls usage 0) " LLM call(s) · "
                                      (when updated-at
                                        (str "updated " (quot (- (System/currentTimeMillis) updated-at) 60000) "m ago")))))))))

      print-config
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [problems files] :as loaded} (config/load-config {:working-dir wd :overrides overrides
                                                                     :env-fn (env-fn wd)})]
        (doseq [p problems] (err-println (str "config: " p)))
        (println (str "files: " (if (seq files) (str/join ", " files) "(none)")))
        (doseq [{:keys [key value source]} (config/describe loaded)]
          (println (format "%-30s %-8s %s" key (name source) (pr-str value)))))

      print-predictor (show-predictor print-predictor opts)

      (or (= :session command) (and (= :ask command) (:attach opts)))
      (let [wd (or working-dir (System/getProperty "user.dir") ".")
            {:keys [config]} (config/load-config {:working-dir wd :overrides overrides :env-fn (env-fn wd)})]
        (cond
          (= :session command) (session-command opts config)
          (empty? questions) (do (err-println "no question given for --attach") (System/exit 2))
          :else (attach-command opts config)))

      (and (= :ask command) (empty? questions))
      (do (err-println "no question given (byj alone starts an interactive session)")
          (err-println (str "\n" (cli-help/text (help-words opts) nil)))
          (System/exit 2))

      :else
      (let [working-dir (or working-dir (System/getProperty "user.dir") ".")
            env (env-fn working-dir)
            sandbox (sandbox-opts opts env working-dir)
            _ (when (and (:sandbox? sandbox) (not (:child? sandbox)))
                (launch-sandbox! sandbox env args))
            {:keys [config problems sources]} (config/load-config {:working-dir working-dir :overrides overrides
                                                                   :env-fn env})
            ;; which layer each key came from, carried on the config so a turn
            ;; can say WHY a setting is what it is — a key set in a file and
            ;; outranked by a flag is the whole of "I edited config.edn and
            ;; nothing happened" (RFC-013)
            config (assoc config :config-sources sources)
            ;; tracing the turn means watching it: -v turns streaming on unless
            ;; config already decided (RFC-006)
            config (cond-> config (and verbose (not (contains? overrides :stream?))) (assoc :stream? true))
            _ (doseq [p problems] (err-println (str "config: " p)))
            [provider model] (run-lm-choice provider model config)
            lm (launch/make-lm provider model config env)
            ;; before the sinks below capture it (`byj-gly.76`)
            config (run-config opts config command)
            ;; MCP servers start once per run, not once per turn (RFC-024). A
        ;; server that will not start is reported and the run goes on.
        ;; into the run's box, which /mcp starts and stops servers in while
        ;; the run goes on, and every session builds its tools from (byj-gly.182)
        mcp-box (mcp/box env)
        ;; a session is on the screen at once and gets a server's tools from
        ;; the first turn after it is up; a one-shot question waits for them,
        ;; since it may be the only chance to use them
        _ (if (= :run command)
            (mcp/start-all-in-background! mcp-box config)
            (doseq [p (mcp/start-all! mcp-box config)] (err-println (str "mcp: " p))))
        _ (when (and verbose (seq (mcp/box-tools mcp-box)))
            (err-println (str "mcp: " (count (mcp/box-tools mcp-box)) " tool(s) from "
                              (str/join ", " (sort (keys (mcp/box-clients mcp-box)))))))
        ;; how much of the turn is shown, for every viewer of it at once:
        ;; :output-style sets it, -v overrides it, and the tracer and the
        ;; renderer read it per event
        display-box (atom (config/display-level config verbose))
        opts (assoc opts :display-box display-box)
        base {:lm lm
              :display-box display-box
              ;; the environment this run resolved, .env included, so anything
              ;; that has to find a key later resolves it the same way (RFC-013)
              :env-fn env
              ;; -p or -m on the command line: a resumed session's /model
              ;; choice does not override it (byj-gly.232)
              :lm-flagged? (boolean (or (:provider opts) (:model opts)))
              :tools (vec tools/builtin-tools)
              :mcp-box mcp-box
                  :tree (agent/load-tree tree-name (:project-dir config))
                  :params (some-> params-file read-params-file)
                  :config config
                  :on-event (event-bus config (tracer opts))
                  ;; the same load, again: what a session re-reads at each
                  ;; turn's start, so an edit to config.edn applies from the
                  ;; next turn with the flags and the environment still on top
                  :reload-config (fn []
                                   (let [{:keys [config problems sources]}
                                         (config/load-config {:working-dir working-dir :overrides overrides :env-fn env})]
                                     {:config (cond-> (assoc config :config-sources sources)
                                                (and verbose (not (contains? overrides :stream?))) (assoc :stream? true))
                                      :problems problems}))
                  ;; ...and the load this run started from, which the next
                  ;; one is compared to
                  :config-loaded config
                  ;; the same sinks behind another viewer: the full-screen
                  ;; renderer takes the tracer's place, since the tracer writes
                  ;; to stderr and would draw over the screen (byj-gly.141)
                  :event-bus-for (fn [session-config viewer] (event-bus session-config viewer))}]
        (when verbose
          (err-println (str "provider " (name provider) " · model " (:model lm)
                            " · working dir " working-dir " · loop " tree-name)))
        ;; one directory listing at startup; never touches a live session
        (when (:prune-sessions? config)
          (let [{:keys [removed]} (persist/prune! config)]
            (when (and verbose (seq removed))
              (err-println (str "pruned " (count removed) " old session/transcript director"
                                (if (= 1 (count removed)) "y" "ies"))))))
        ;; a newer release, said as the run ends: from the last check, so
        ;; startup waits on nothing (RFC-029, byj-gly.181.7)
        (let [update-notice (when (:update-check? config)
                              (try (update/startup-check! config {:home (System/getenv "HOME")
                                                                  :now (System/currentTimeMillis)
                                                                  :current (version/version)
                                                                  :env (into {} (System/getenv))
                                                                  :sh update/shell :get http/get})
                                   (catch Throwable _ nil)))]
        ;; the run's MCP servers end with the run (byj-gly.225)
        (try
        (if (= :flow command)
          (flow-command opts lm config)
          (if (= :programs command)
          (programs-command opts lm config)
          (if (= :run command)
          (if-let [base (some-> (if (:persist-session? config false)
                                  (resumed-base base)
                                  base)
                                (with-persona opts))]
            ;; full-screen unless asked otherwise, as brainyard is — and
            ;; inline wherever a full-screen renderer cannot run at all: a pipe,
            ;; a CI log, an editor's terminal without a TERM (RFC-028 rule 4)
            (cond
              (:serve opts) (run-session-serve opts base)
              (and (not (:inline opts)) (tui/usable-terminal?))
              (run-session-tui opts base)
              :else
              (do (when (:tui opts)
                    (err-println "--tui needs a terminal (no tty, TERM or terminfo entry) — running inline"))
                  (when (:resume-run opts)
                    (err-println "--resume-run reopens a full-screen run's sessions; a session run inline is one — --resume ID continues it"))
                  (run-session opts base)))
            (System/exit 1))
          (run-ask opts (with-persona base opts)))))
        (finally (some-> update-notice err-println)
                 (mcp/stop-all! mcp-box))))))))
