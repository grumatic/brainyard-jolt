(ns brainyard.dev.memory-hint-spike
  "byj-gly.335: how well a lexical match finds the memory a question needs,
  with no model call — the offline half of the decision in RFC-042.

  Questions are written the way a person asks, not in the memory's words; each
  names the memory it needs (or none). The labels are a reader's judgement,
  made once, against this project's own store as `bd memories --json` has it.
  For each setting it reports:

    found      questions needing a memory whose memory was hinted
    precise    hinted memories that were the one needed
    noise      memories hinted for questions that need none
    chars      the hint's size, averaged over the turns that got one

  Run:  jolt -A:dev -e \"(require 'brainyard.dev.memory-hint-spike) (brainyard.dev.memory-hint-spike/report)\""
  (:require [brainyard.effect]
            [brainyard.json]
            [brainyard.llm]
            [brainyard.memory :as memory]
            [brainyard.session]))

(def questions
  [["send a prompt to the claude code session running in tmux" "claude-code-input"]
   ["fix the paren mismatch in tui.clj after my last edit" "clj-edit-balanced"]
   ["opus refuses the task here but brainyard's request worked, why?" "compare-requests-with-brainyard"]
   ["add a timeout to this blocking call with missionary" "effect-substrate-ebb"]
   ["stream the provider's response over SSE" "http-streaming-routes"]
   ["my map literal with :pass@1 has an odd number of entries" "jolt-keyword-at"]
   ["Cannot run program error when spawning claude from the code" "jolt-process-spread-args"]
   ["why doesn't jolt run start my deps.edn task" "jolt-task-shadowing"]
   ["try this question on gpt through openai" "live-provider-status"]
   ["verify the picker on the full-screen renderer in a real terminal" "live-tui-checks-use-tmux"]
   ["should the agent emit clojure code blocks for the sandbox to execute?" "no-code-channel"]
   ["reload the namespace in the repl after editing it" "nrepl-dev-loop"]
   ["port the ensemble and knn optimizers from brainyard" "programs-measurement-first"]
   ["is the roadmap in RFC-008 up to date?" "roadmap-drift"]
   ["run byj against its own repository to find bugs" "self-hosting-finds-what-reading-misses"]
   ["which tests should I run after changing session.clj" "test-cadence"]
   ["does piping input into by run work end to end?" "session-loop-is-in-good-shape"]
   ;; none of the store's memories is about these
   ["commit this" nil]
   ["what's the weather in Seoul" nil]
   ["rename the variable foo to bar in config.clj" nil]
   ["write a haiku about autumn" nil]
   ["list the files in src" nil]
   ["summarize your last answer" nil]
   ["add a /memory command to the session" nil]
   ["close the bead and push the branch" nil]])

(def settings
  [{:min-terms 1}
   {:min-terms 1 :min-share 0.5}
   {:min-terms 2}
   {:min-terms 1 :max 1}
   {:min-terms 1 :min-score 3}
   {:min-terms 1 :min-score 3 :min-share 0.5}
   {:min-terms 1 :min-score 3 :min-share 0.5 :prior 0}
   {:min-terms 1 :min-score 4 :min-share 0.5}
   ;; the session tree's (loops/session.edn :session/memory-hint)
   {:min-terms 1 :min-score 4.5 :min-share 0.5}
   {:min-terms 1 :min-score 2.5 :min-share 0.5}])

(defn measure
  "The numbers for one `setting` (memory/rank's opts), over `memories`."
  [memories setting]
  (let [opts (merge {:max 3} setting)
        runs (for [[q want] questions
                   :let [hits (memory/rank memories q opts)]]
               {:want want :hits (mapv :key hits)
                :chars (reduce + (for [h hits]
                                   (+ 6 (count (:key h)) (min 120 (count (first (clojure.string/split-lines (:content h))))))))})
        needing (filter :want runs)
        none (remove :want runs)
        hinted (mapcat :hits needing)
        with-hint (filter (comp seq :hits) runs)]
    {:setting setting
     :found [(count (filter #(some #{(:want %)} (:hits %)) needing)) (count needing)]
     :first [(count (filter #(= (:want %) (first (:hits %))) needing)) (count needing)]
     ;; a hint is precise when it is the memory THIS question needs
     :precise [(count (for [r needing h (:hits r) :when (= h (:want r))] h)) (count hinted)]
     :noise [(count (mapcat :hits none)) (count none)]
     :chars (if (seq with-hint) (quot (reduce + (map :chars with-hint)) (count with-hint)) 0)
     :misses (vec (for [r needing :when (not (some #{(:want r)} (:hits r)))] [(:want r) (:hits r)]))}))

(defn report
  "Every setting's numbers, against this project's own store."
  []
  (let [memories (:memories (memory/list-memories {:project-dir "."}))
        f (fn [[a b]] (str a "/" b))]
    (println (count memories) "memories in this project's store," (count questions) "questions")
    (doseq [s settings]
      (let [{:keys [found first precise noise chars misses]} (measure memories s)]
        (println (format "%-62s found %s · first %s · precise %s · noise %s · ~%d chars"
                         (pr-str s) (f found) (f first) (f precise) (f noise) chars))
        (doseq [x misses] (println "     missed" x))))))

;; --- live: does a hint change what a model does? ------------------------------------
;;
;; Three facts a model cannot derive, each with a question that needs it, in a
;; store of eight; each question asked in a fresh session, pull only and hinted.
;; It spends: a claude -p call per iteration on the person's subscription. Run
;; it a few times, not in a loop (AGENTS.md).
;;
;;   jolt -A:dev -e "(require 'brainyard.dev.memory-hint-spike) (brainyard.dev.memory-hint-spike/live \"haiku\")"

;; The live set is ROT13 (digits rotated by five) in this file and decoded when it runs: in plain text,
;; a model asked one of these questions found this repository (claude -p runs
;; in it), grepped it, and read the answer here — pull-only looked as good as
;; a hint for a reason that had nothing to do with memory.

(defn- rot13 [s]
  (apply str (map (fn [c] (let [i (int c)]
                            (cond (<= 97 i 122) (char (+ 97 (mod (+ (- i 97) 13) 26)))
                                  (<= 65 i 90) (char (+ 65 (mod (+ (- i 65) 13) 26)))
                                  (<= 48 i 57) (char (+ 48 (mod (+ (- i 48) 5) 10)))
                                  :else c)))
                  s)))

(def live-store
  (into {} (for [[k v] {"qrcybl-serrmr" "Ab qrcyblf ba Sevqnlf: gur eryrnfr genva yrnirf Ghrfqnl 65:55 XFG, naq n ubgsvk tbrf guebhtu gur ba-pnyy yrnq svefg."
                        "fgntvat-qo-cbeg" "Gur fgntvat qngnonfr yvfgraf ba cbeg 1098, abg 0987: ctobhapre sebagf vg."
                        "vaibvpr-ebhaqvat" "Vaibvpr gbgnyf ebhaq unys-rira (onaxre'f ebhaqvat), nf gur svanapr grnz erdhverf — arire unys-hc."
                        "pv-pnpur-xrl" "PV pnpurf ~/.z7 xrlrq ba qrcf.rqa'f unfu; n fgnyr pnpur vf pyrnerq ol ohzcvat PNPUR_IREFVBA."
                        "ybtb-pbybhef" "Gur ybtb vf #6S1SRO ba juvgr; gur qnex-zbqr inevnag vairegf gb juvgr ba #5Q6662."
                        "bapnyy-ebgn" "Gur ba-pnyy ebgn vf va CntreQhgl, grnz 'cyngsbez'; fjncf ner naabhaprq va #cyngsbez."
                        "ncv-irefvbavat" "Choyvp NCV irefvbaf ner qngrq (7571-52-56); n oernxvat punatr trgf n arj qngr, arire n i7."
                        "grfg-qngn" "Grfg svkgherf yvir haqre grfg/erfbheprf/svkgherf naq ner ertrarengrq ol ova/svkgherf."}]
             [(rot13 k) (rot13 v)])))

(def live-tasks
  (vec (for [[question want] [["Vg'f Sevqnl nsgreabba. Pna V qrcybl gur cnlzragf ubgsvk evtug abj?" "(?v)ghrfqnl|ba-pnyy"]
                              ["Juvpu cbeg fubhyq V pbaarpg gb sbe gur fgntvat qngnonfr?" "1098"]
                              ["Ubj fubhyq gur arj vaibvpr rkcbeg ebhaq vgf gbgnyf?" "(?v)unys.rira|onaxre"]]]
         [(rot13 question) (re-pattern (rot13 want))])))

(defn- live-project!
  "A scratch project whose memory store is `live-store`, behind the same
  four commands bd fills."
  []
  ;; the store's files OUTSIDE the project: kept in it, a model asked about
  ;; one of these questions grepped the project and read store.json, and pull-only
  ;; looked as good as a hint for a reason that had nothing to do with memory
  (let [stamp (System/nanoTime)
        tmp (System/getProperty "java.io.tmpdir")
        dir (.getCanonicalPath (doto (java.io.File. tmp (str "memlive-" stamp)) .mkdirs))
        store (.getCanonicalPath (doto (java.io.File. tmp (str "memstore-" stamp)) .mkdirs))
        json (str store "/store.json")
        py (fn [n code] (let [f (str store "/" n)]
                          (spit f (str "#!/bin/sh\nexec python3 -c '" code "' \"$@\"\n"))
                          (brainyard.effect/sh {:out :string :err :string} "chmod" "+x" f)
                          f))]
    (spit json (brainyard.json/write-str live-store))
    {:dir dir
     :config {:memory-list-cmd [(py "list.sh" (str "import json; print(open(\"" json "\").read())"))]
              :memory-recall-cmd [(py "recall.sh" (str "import json,sys; d=json.load(open(\"" json "\")); q=sys.argv[1].lower(); "
                                                      "hits=[k+\": \"+v for k,v in d.items() if any(w in (k+\" \"+v).lower() for w in q.split())]; "
                                                      "print(\"\\n\".join(hits) if hits else \"No memories matching \"+repr(q))"))]
              :memory-read-cmd [(py "read.sh" (str "import json,sys; d=json.load(open(\"" json "\")); print(d.get(sys.argv[1], \"no memory \"+sys.argv[1]))"))]
              :memory-store-cmd [(py "store.sh" "print(\"stored\")")]}}))

(defn- live-run
  "One question in a fresh session on `model`: what the model did with memory."
  [model hint? [q want]]
  (let [{:keys [dir config]} (live-project!)
        calls (atom [])
        queue (atom [q])
        lm (brainyard.llm/create-lm :claude-code #(System/getenv %) {:model model})
        final (brainyard.session/run
               {:lm lm
                :config (merge config {:working-dir dir :project-dir dir :persist-session? false
                                       :max-iterations 6 :memory-hint? hint? :recap? false})
                :read-input (fn [_] (let [[l] @queue] (swap! queue #(vec (rest %))) l))
                :emit (fn [_ _])
                :on-event (fn [e d] (when (= :agent.tools/calls e)
                                      (swap! calls into (map :tool-name (:tool-calls d)))
                                      (when (System/getenv "SPIKE_TRACE")
                                        (doseq [c (:tool-calls d)] (println "   call" (:tool-name c) (pr-str (:tool-args c)))))))})
        turn (last (:previous-turns final))
        answer (str (:answer turn))]
    {:hint? hint? :question q
     :right? (boolean (re-find want answer))
     :memory-calls (vec (filter #(clojure.string/starts-with? % "memory$") @calls))
     :calls (count @calls)
     :llm-calls (get-in final [:usage :llm-calls])
     :tokens [(get-in final [:usage :input-tokens]) (get-in final [:usage :output-tokens])]
     :answer (subs answer 0 (min 160 (count answer)))}))

(defn live
  "Every live task, pull only then hinted, on `model`."
  [model]
  (doseq [t live-tasks hint? [false true]]
    (let [r (live-run model hint? t)]
      (println (pr-str (dissoc r :question)))
      (flush))))
