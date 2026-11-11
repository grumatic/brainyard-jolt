(ns brainyard.events
  "The event vocabulary (RFC-026): every event the kernel emits through
  `:on-event`, and what KIND of thing each one is.

  This is a schema, not a viewer. It says only whether an event reports a
  problem someone has to see, narrates work someone may want to watch, or is
  bookkeeping nobody asked for — `brainyard.main/tracer` decides how any of that
  is shown, exactly as it decides what to do with a streamed reply field
  (RFC-006). The two questions are separate because the answers change
  independently: a new viewer re-decides the second, never the first.

  It exists because 27 of 41 events had no consumer at all: `main/tracer`'s
  `case` had no branch for them, so they fell through to `nil` and vanished with
  or without `-v`. A malformed user tool was skipped, reported, and never
  mentioned — the agent then told its author the tool did not exist
  (`byj-gly.80`). Nothing distinguished a drop someone chose from a drop nobody
  wrote, which is what `:quiet` is for: it is a decision, recorded, with the
  reason beside it.

  `events-test/every-emitted-event-is-declared` reads `src/` and fails on an
  event that is emitted and not listed here, so the next one cannot join that
  list silently."
  (:require [clojure.string :as str]))

(defn emit!
  "Put `event` on the `:on-event` bus, if anyone is listening.

  `target` is a ctx — anything carrying `:on-event` — or the handler itself,
  because both shapes exist at the call sites and one door is the point: the
  catalogue below is only trustworthy if every emission goes through a name the
  source can be read for. `events-test/nothing-reaches-the-bus-except-through-here`
  is what keeps that true."
  [target event data]
  (when-let [f (if (fn? target) target (:on-event target))]
    (f event data)))

(def levels
  "What an event is, which is not the same as how it is shown.

  - `:problem` — a misconfiguration or failure that is invisible otherwise.
    Shown whether or not anyone asked to watch, because the alternative is that
    someone's tool, hook or agent is silently not there.
  - `:notice` — something became POSSIBLE that was not before. Shown unasked,
    like a problem, because a capability nobody is told about is a capability
    nobody has; unlike a problem, nothing is wrong. Say it once, not every turn
    — an opportunity repeated is an interruption.
  - `:trace` — the turn narrating itself. Shown when asked (`-v`).
  - `:quiet` — deliberately not shown, because something else already says it.
    A `:quiet` entry's `:doc` says what."
  #{:problem :notice :trace :quiet})

(def catalogue
  "event → `{:level :doc}`, plus `:label` where the event's own name does not
  read as a phrase, and `:log? false` where the event is live-only — too
  voluminous or too full of the model's own text to write down (RFC-026)."
  {;; --- someone's own definition was rejected, and only this says so ------------
   :tools/user-tool-skipped
   {:level :problem :label "user tool skipped" :doc "A user tool in .brainyard/tools was malformed; {:file :problems}."}
   :tools/user-tool-waiting
   {:level :trace :label "user tool waiting for its MCP server" :doc "A user tool calls a tool of an MCP server still starting, so it is held rather than skipped, and a turn's start waits for the server (byj-gly.300); {:file :waits-for}."}
   :hooks/user-hook-skipped
   {:level :problem :label "user hook skipped" :doc "A user hook in .brainyard/hooks was malformed; {:file :problems}."}
   :agents/agent-skipped
   {:level :problem :label "agent definition skipped" :doc "A user agent definition was rejected; the problem, as text."}
   :agents/shadowed
   {:level :problem :label "agent name defined twice" :doc "Two agents share a name and the more local one won; {:name :dropped :kept}."}
   :tools/output-mismatch
   {:level :problem :label "tool result unlike its :output-schema" :doc "A tool returned what its :output-schema does not describe — the call still worked; {:tool-name :problems} (RFC-030)."}
   :predictor/params-skipped
   {:level :problem :label "params file skipped" :doc "A params file failed validation and was ignored; {:skipped [{:file :problems}]}."}

   ;; --- a user hook misbehaved -------------------------------------------------
   :hooks/handler-failed
   {:level :problem :label "hook handler failed" :doc "A hook handler threw; {:event :id :source :error}."}
   :hooks/malformed-decision
   {:level :problem :label "hook returned a malformed decision" :doc "A gated hook returned something that is not a decision; {:event :id :decision}."}

   ;; --- something named a thing that is not there -------------------------------
   :agent.tree/tool-missing
   {:level :problem :label "loop names a tool that does not exist" :doc "A :tool/run node names a tool no registry has; {:node :tool}."}
   :predictor/lm-unresolved
   {:level :problem :label "predictor names an unknown model" :doc "A predictor's :lm label resolves to no model, so the default is used; {:predictor-id :lm}."}
   :predictor/tier-unresolved
   {:level :problem :label "predictor names an unknown tier" :doc "A predictor's :tier is not in the tier table, so the default is used; {:predictor-id :tier}."}
   :llm.cli/params-ignored
   {:level :problem :label "model takes no such parameter"
    :doc "A sampling parameter the caller set explicitly that the provider cannot convey, so the model's own default is used; {:ignored :model :why}. claude -p takes neither --temperature nor --max-tokens, and while the LM map still carried them a measurement through it looked reproducible when it was not (byj-gly.126)."}

   :programs/dataset-ready
   {:level :notice :label "enough predictions to compile"
    :doc "The prediction pool for a predictor reached :prediction-notice-at, so program$build-dataset has something to build from; {:predictor-id :records :threshold}. Once per session."}

   ;; --- the wire: what actually left this process (RFC-006) ---------------------
   ;; Written whole, like everything else: the log is lossless, and these are
   ;; what makes a provider call reproducible (RFC-026).
   :llm.wire/request {:level :trace
                      :doc "The HTTP body actually sent, after the provider's own shaping; {:provider :model :url :body :headers}. Headers arrive credential-redacted."}
   :llm.wire/response {:level :trace
                       :doc "What came back, body verbatim — the usage is inside it; {:status :duration-ms :body}."}
   :llm.wire/failed {:level :trace
                     :doc "A call that did not return a reply; {:status :duration-ms :error :retryable?}."}
   :llm.cli/call {:level :trace
                  :doc "A claude -p invocation, argv and stdin together — the pair that makes a call replayable; {:provider :model :args :stdin}."}
   :llm.cli/result {:level :trace
                    :doc "What the CLI returned; {:duration-ms :usage :response}."}

   ;; --- the turn, narrating itself ----------------------------------------------
   :agent.iteration/start {:level :trace :doc "An iteration began; {:iteration}."}
   :agent.llm/request {:level :trace
                       :doc "The assembled messages, before the call; {:messages}. Written whole: the log is lossless, and a dataset is derived from it (RFC-021)."}
   :agent.llm/response {:level :trace
                        :doc "The raw reply text; {:content}. Written whole, for the reason the request is."}
   :agent.llm/chunk {:level :trace :log? false
                     :doc "A streamed fragment, labelled with its reply field (RFC-006); {:field :text}. The ONLY event not written down, and not because it is large: `:agent.llm/response` already holds the whole reply, so these are a second copy of it in pieces. Excluding a redundant representation loses no information."}
   :agent.think/reasoning {:level :trace :doc "The parsed reasoning for this iteration; {:reasoning}."}
   :agent.tools/calls {:level :trace :doc "The tool calls this iteration made; {:tool-calls}."}
   :agent.tool/output {:level :trace :log? false
                       :doc "A line a running tool printed, as it printed it — bash's — so the person sees a login's code or a server's port while the call runs, not when it returns (byj-gly.364); {:tool-name :line :call} — :call its place in the iteration's batch. Not written: :agent.tools/results already holds the output, whole, once the call returns."}
   :agent.tools/results {:level :trace :doc "What they returned; {:results}, each with the :elapsed-ms it took — here only, not in the results a model reads again."}
   :agent.tools/recovered {:level :trace :doc "Native tool calls the provider rejected, run anyway (provenance P-12); {:tool-calls :thought}, :thought replacing the iteration's reasoning, written under the refusal (byj-gly.339)."}
   :permission/classified {:level :trace
                           :doc "The auto-mode classifier decided a call a gate would have asked about (byj-gly.285); {:verdict :reason :tool :display :type :cached? :ms}. The renderer shows it under the call."}
   :agent.tools/blocked {:level :trace :doc "A hook blocked a call and ended the turn; {:reason :by}. The answer says so too."}
   :agent.tree/tool-failed {:level :trace :doc "A :tool/run node's tool failed; the node's :on-error decides what that costs; {:node :tool :error}."}
   :agent.tree/step-start {:level :trace :doc "A workflow step began — a :tool/run or :predictor/run node — what the TUI draws as a call (byj-gly.299); {:node :kind :name :args}."}
   :agent.tree/step-progress {:level :trace :doc "A running step said what it is doing — claude$await, Claude Code's newest tool call or text; {:line}."}
   :agent.tree/step-end {:level :trace :doc "A workflow step ended: what it kept, in short, or its error; {:node :kind :name :result :error}."}
   :agent.repair/llm-error {:level :trace :doc "A provider call failed and is being retried; {:attempt :message}."}
   :agent.repair/no-action {:level :trace :doc "A reply populated neither channel; {:streak}."}
   :agent.answer/evaluated {:level :trace :doc "The answer gate's verdict (RFC-003); {:verdict :detail}."}
   :agent.answer/refused {:level :trace :doc "The gate sent the answer back; {:reason :self-reported}."}
   :agent.context/budgeted {:level :trace :doc "What the prompt cost and what was elided (RFC-014)."}
   :agent.tasks/harvested {:level :trace :doc "Background work folded back into the turn (RFC-019)."}
   :predictor/call
   {:level :trace
    ;; the metadata, never the content. :inputs is the assembled question and
    ;; iteration history, which is verbatim what the PREDICTION log records —
    ;; and that log is off until a project turns it on (RFC-021), so logging
    ;; the same thing here by default would make its opt-in a weaker promise
    ;; than it reads (`byj-gly.85`, found by running the agent on this repo).
    :doc "One predictor call, with its params source — the prediction log's event (RFC-021)."}
   :bt.node/error {:level :trace :doc "A tree node failed; {:node :message}."}
   :bt.repeat/exhausted {:level :trace :doc "A :repeat hit :max-n without its :until; {:id}."}
   :bt.scope/cancelled {:level :trace :doc "A :scope was cancelled."}
   :session.turn/end {:level :trace :doc "A session turn finished; {:terminated-by :iterations :usage :elapsed-ms :ended-at :files-changed}, the last how many files differ between the git snapshots of its start and end, kept for a review (RFC-067)."}
   :session/rewind {:level :quiet :doc "The person put files back between turns (RFC-069): {:kind :rewind|:undo-turn|:undo-rewind|:fork :turn :end? :files :conflicts :saved}, :saved the ref that keeps what was replaced; a :fork's :session is the new session, whose conversation was cut before :turn. The command's own line says it."}
   :session/suggestion {:level :trace :doc "What the person might type next, from a call they made themselves (a typed :agent$…), outside a turn; {:next-user-prompt}. /init's comes with its turn's end instead (byj-gly.350)."}
   :session/resumed {:level :trace :doc "A session was resumed from its snapshot; {:id :transcript [[kind text] …] :turn-count :usage}, what was said before and what it cost, for a front end to show again."}
   :config/applied {:level :trace :doc "A turn's start found the config files changed and put the new values in force; {:keys}."}
   :session.turn/recap {:level :trace :doc "A long turn's recap (session/recap); {:turn :recap :next-step :conversation-title :cost}."}
   :session.tool/judged {:level :trace :doc "A tool-distillation judge ran (RFC-010, byj-gly.249); {:outcome :staged|:not-reusable|:below-threshold|:invalid-name|:empty-body|:prose-output-schema|:unreadable-draft|:no-score|:invalid|:mismatch|:no-replay|:error :proof :verified|:unproved|… :name :score :trigger :repeated-body|:multi-tool-body :turns :runs :cost}."}
   :permission/asked {:level :trace :label "permission asked"
                      :doc "A gate handed a request to the person's prompt (RFC-043, read only on :subscribe); {:id :tool :summary}."}
   :permission/decided {:level :trace :label "permission decided"
                        :doc "The person's prompt answered it; {:id :tool :decision :allow|:deny}."}
   :session.share/note {:level :notice :label "note"
                        :doc "A note for people only, sent over the session channel (`:inject :as :note`, RFC-043): shown and recorded, never a turn, never in the model's context; {:text :from}."}
   :session.share/control {:level :quiet
                           :doc "The owner's /share pause, resume, kick, stop or record on|off (:record-on, :record-off; byj-gly.362.7), for the sidecar watching the bus (RFC-043); {:action :user}. /share says what it did."}
   :session.share/started {:level :trace :doc "The share sidecar started as a background task of this session (RFC-043); {:task-id :target :argv}."}
   :session.share/login-ended {:level :trace :doc "byj-share's login, started by share$login, ended (RFC-043, byj-gly.362.5); {:task-id :ok :why} — :why its last lines. The session says it to the person."}
   :session.share/ended {:level :trace :doc "The share sidecar ended on its own, not stopped (RFC-043, byj-gly.362.4); {:task-id :exit :why} — :why its last lines, what it said on the way out. The session says it to the person."}
   :session.member/spent {:level :trace :doc "Another session answered this one and its spend is now this session's (RFC-031); {:session-id :usage :cost}."}
   :session.skill/judged {:level :trace :doc "A self-improvement judge ran (RFC-017); {:kind :distillation|:refinement :outcome :staged|:not-reusable|:below-threshold|:invalid-name|:empty-skill-md|:no-score|:no-revision|:empty-revised-md|:error :name :score :turns :trigger :cost}; a distillation's :trigger is :cadence, :repeated-procedure, :long-procedure or :session-end."}

   ;; --- subagents and tasks (RFC-018, RFC-019) ----------------------------------
   :agent.subagent/dispatch {:level :trace :doc "A child turn is starting; {:agent :id :depth :chain :question}. Every event of the child turn itself carries :subagent-id and :subagent."}
   :agent.subagent/ask {:level :trace :doc "A follow-up to a live instance; {:id :agent :question}."}
   :agent.subagent/waiting {:level :trace :doc "A person's ask of an instance still running — a child parked as a task — waits for its turn to end (byj-gly.345); {:id :agent}."}
   :agent.subagent/result {:level :trace :doc "What the child turn answered and cost; {:id :agent :answer :iterations :terminated-by :usage}."}
   :agent.subagent/tier {:level :trace :doc "A child's requested tier was remapped; {:agent :requested-tier :tier}."}
   :agent.subagent/refused {:level :trace :doc "A dispatch was refused — depth or budget; {:agent :reason}."}
   :task/created {:level :trace :doc "Background work started; {:id :name :job-type}."}
   :task/completed {:level :trace :doc "It finished; {:id :status :elapsed-ms}."}
   :task/detached {:level :trace :doc "A tool call became a task: it ran past :fast-eval-timeout-ms, or the person moved it to the background with ctrl+g (:by :person, byj-gly.391); {:id :tool :by}."}
   :task/parked {:level :trace :doc "A turn is waiting for a task to end; {:task-id :note}."}
   :task/wakeup-disarmed {:level :trace :doc "A wakeup taken back because a turn already has its task's result (task$wait or task$detail on it, ended): it would have started a turn with nothing to do (byj-gly.388); {:task-id}."}
   :claude/stopped {:level :trace :label "Claude Code session stopped" :doc "A delegation's Claude Code session was stopped, saved, since nothing was left for it to work for — its worktree removed (byj-gly.318); {:session-id :why}."}
   :task/auto-arm-skipped {:level :trace :doc "A turn ended leaving tasks running but had set a schedule$watch, its own wake signal, so no wakeup was armed for them (byj-gly.332); {:task-ids :watches}."}
   :task/auto-armed {:level :trace :doc "A turn ended leaving tasks running, so one wakeup was armed for them; {:task-ids}."}
   :session.turn/start {:level :trace :doc "A session turn began; {:turn :question}, and :injected-by when it came over the channel from someone (RFC-043)."}
   :session.memory/hinted {:level :trace :doc "A turn was told which memories its question is about (byj-gly.335); {:keys :matched}."}
   :session.editor/context {:level :trace :doc "A question mentioned @here and the turn was told what the person is looking at in their editor, or that it could not be read (byj-gly.453); {:program :file :region-chars} or {:error}."}
   :agents/defined {:level :trace :doc "An agent was written to .brainyard/agents; {:name :dir}."}
   :agents/deleted {:level :trace :doc "One was removed; {:name :dir}."}

   ;; --- said better elsewhere ------------------------------------------------------
   :agent.answer/final
   {:level :quiet :doc "The CLI prints the answer itself; this would print it twice."}
   :agent.trajectory/stored
   {:level :quiet :doc "One per turn, always, saying only that the log worked (RFC-025)."}
   :agent.instance/created
   {:level :quiet :doc "The :agent.subagent/dispatch beside it names the work; this names only the registry."}
   :agent.instance/closed
   {:level :quiet :doc "Scope bookkeeping at the end of a turn or session (RFC-018)."}
   :session/closed
   {:level :quiet :doc "The session's own closing summary line already reports the turns and the usage."}})

(defn level
  "What kind of thing `event` is, or nil when it is not declared.

  nil is not a default to paper over: an undeclared event fails
  `events-test/every-emitted-event-is-declared`. A viewer that meets one at
  runtime — from a caller outside `src/` — should treat it as a trace, which is
  the harmless end of being wrong."
  [event]
  (get-in catalogue [event :level]))

(defn problem?
  "Does `event` report something someone has to see whether or not they asked?"
  [event]
  (= :problem (level event)))

(defn label
  "What to call `event` in front of a person. The entry's `:label` when it has
  one, else the event's own name with its dashes opened out — which reads
  correctly for most of them (`:hooks/handler-failed` → \"handler failed\")
  and not at all for the few that carry their meaning in the namespace, which
  is what `:label` is for."
  [event]
  (or (get-in catalogue [event :label])
      (str/replace (name event) "-" " ")))

(defn detail
  "What a payload says about itself, in its own words. A problem is usually
  already a sentence someone wrote — the file that was rejected, the reason it
  was, the message a handler threw — and restating it would only lose it."
  [data]
  (cond
    (string? data) data
    (map? data) (->> [(some-> (:file data) str)
                      (some-> (:name data) str)
                      (or (some->> (:problems data) seq (str/join "; "))
                          (:error data)
                          (:reason data)
                          (some-> (:tool data) str)
                          (some-> (:lm data) str)
                          (some-> (:tier data) str))]
                     (remove str/blank?)
                     (str/join " — "))
    :else nil))
