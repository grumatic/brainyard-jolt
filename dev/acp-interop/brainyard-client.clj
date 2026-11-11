;; brainyard's own ACP client (../brainyard components/acp-client) against
;; `byj acp` (RFC-053, byj-g5n.4). Run from ../brainyard, on the JVM:
;;
;;   clojure -M:dev -i <this file> BYJ_ARGV_EDN REPO
;;
;; brainyard's default permission callback rejects (callbacks.clj
;; `default-request-permission`), so the session asks nothing here: the
;; scripted turn reads a file under the default mode, and the check is the
;; handshake, the session, the streamed updates and the stop reason.
(require '[ai.brainyard.acp-client.interface :as acp]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(let [[argv-edn repo] *command-line-args*
      argv (edn/read-string argv-edn)
      dir (.toFile (java.nio.file.Files/createTempDirectory "byj-acp-brainyard-" (make-array java.nio.file.attribute.FileAttribute 0)))
      _ (spit (io/file dir "notes.md") "the notes\n")
      script (io/file dir "replies.edn")
      _ (spit script (pr-str [{:reasoning "r" :tool-calls [{:tool-name "read-file" :tool-args {:path "notes.md"}}] :answer ""}
                              {:reasoning "r" :tool-calls [] :answer "the notes say: the notes" :goal-achieved true}]))
      events (atom [])
      checks (atom 0)
      ok (fn [what] (println (str "ok " (swap! checks inc) " — " what)))
      c (acp/spawn! {:command (into argv ["acp" "-p" "mock" "-m" (str "script:" script)]) :working-dir repo}
                    {:on-event #(swap! events conj %)})]
  (try
    (let [info (acp/initialize! c)]
      (assert (= 1 (:protocolVersion info)) (pr-str info))
      (ok (str "initialize: " (get-in info [:agentInfo :name]) " " (get-in info [:agentInfo :version]))))
    (let [sess (acp/new-session! c {:cwd (.getPath dir)})
          _ (ok (str "session/new: " (:session-id sess)))
          r (acp/prompt-text! sess "what do the notes say?")
          kinds (set (keep #(get-in % [:params :update :sessionUpdate]) @events))]
      (assert (= "end_turn" (:stop-reason r)) (pr-str r))
      (ok "session/prompt: end_turn")
      (assert (every? kinds ["tool_call" "tool_call_update" "agent_message_chunk"]) (pr-str kinds))
      (ok (str "session/update: " (pr-str (sort kinds))))
      (let [text (apply str (keep #(when (= "agent_message_chunk" (get-in % [:params :update :sessionUpdate]))
                                     (get-in % [:params :update :content :text]))
                                  @events))]
        (assert (= "the notes say: the notes" text) text)
        (ok "the answer, as brainyard's client translates it")))
    (finally (acp/close! c)))
  (println (str @checks " checks passed"))
  (shutdown-agents)
  (System/exit 0))
