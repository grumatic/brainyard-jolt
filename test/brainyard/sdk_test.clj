;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.sdk-test
  "brainyard.sdk, the SDK's one implementation (RFC-053, byj-g5n.3): a
  session an application opens is an ordinary session, on the mock provider."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.json :as json]
            [brainyard.main]
            [brainyard.persist :as persist]
            [brainyard.sdk :as sdk]))

(defn- fresh-dir [name]
  (let [d (io/file "target/sdk-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(defn- scripted
  "A mock LM answering with `replies` in turn, the last one again once they
  run out; every call's messages kept in `seen`."
  [replies & [seen]]
  (let [left (atom (vec replies))]
    {:provider :mock :format :mock :model "mock"
     :responses (fn [msgs]
                  (some-> seen (swap! conj msgs))
                  (let [[r & more] @left]
                    (when (seq more) (reset! left (vec more)))
                    r))}))

(defn- open [dir lm & {:as opts}]
  (sdk/open-session (merge {:project-dir dir :lm lm
                            ;; nothing of this machine's own (~/.brainyard)
                            :config {:builtin-agents? false :mcp-servers {}}}
                           opts)))

(defn- answer [session text] (deref (sdk/submit! session text) 20000 ::timeout))

(deftest an-sdk-session-is-an-ordinary-session
  (let [dir (fresh-dir "ordinary")
        events (atom [])
        said (atom [])
        s (open dir (scripted [(reply {:answer "forty-two" :goal-achieved true})])
                :on-event (fn [e d] (swap! events conj [e d]))
                :on-said (fn [k t] (swap! said conj [k t])))]
    (try
      (testing "a prompt is a turn, answered with its result once the session is waiting again"
        (let [{:keys [result said]} (answer s "what is six times seven?")]
          (is (= "forty-two" (:answer result)))
          (is (= :answer-channel (:terminated-by result)))
          (is (number? (:elapsed-ms result)))
          (is (some #{[:answer "forty-two"]} said) "with what the session said for it")))
      (testing "its events reached the viewer, the turn's start and end among them"
        (is (some #(= :session.turn/start (first %)) @events))
        (is (some #(= :session.turn/end (first %)) @events)))
      (testing "it is locked while open, and leaves its trajectory and snapshot as any session"
        (is (.isFile (persist/lock-file (:config s) (:id s))))
        (is (.isDirectory (io/file dir ".brainyard" "sessions" (:id s)))))
      (testing "a second prompt is a second turn of the same session"
        (is (= "forty-two" (get-in (answer s "again") [:result :answer]))))
      (finally (is (sdk/close! s))))
    (testing "closing released the lock"
      (is (not (.isFile (persist/lock-file (:config s) (:id s))))))))

(deftest a-prompt-that-is-not-a-question-is-refused
  (let [dir (fresh-dir "refused")
        s (open dir (scripted [(reply {:answer "ok"})]))]
    (try
      (testing "a slash command or a direct call, unless the session allows commands; an empty line"
        (is (str/starts-with? (:refused (answer s "/mcp add x -- rm -rf /")) "not run: /mcp add"))
        (is (str/starts-with? (:refused (answer s ":bash ls")) "not run: :bash ls"))
        (is (= "empty prompt" (:refused (answer s "  ")))))
      (finally (sdk/close! s))))
  (testing "one opened with commands runs a slash command, which is no turn"
    (let [dir (fresh-dir "commands")
          s (open dir (scripted [(reply {:answer "ok"})]) :commands? true)]
      (try
        (let [r (answer s "/history")]
          (is (:no-turn r))
          (is (some #(str/includes? (second %) "no turns yet") (:said r))))
        (finally (sdk/close! s))))))

(deftest a-client-tool-is-an-ordinary-tool
  (let [dir (fresh-dir "tool")
        calls (atom [])
        asked (atom [])
        lookup {:name "order_lookup"
                :description "An order by its id"
                :input-schema [:map [:id :string]]
                :effects #{:sdk/call}
                :fn (fn [{:keys [id]} _] (swap! calls conj id) {:status "refunded" :id id})}
        seen (atom [])
        s (open dir (scripted [(reply {:tool-calls [{:tool-name "order_lookup" :tool-args {:id "1042"}}]})
                               (reply {:answer "refunded" :goal-achieved true})]
                              seen)
                :tools [lookup]
                ;; the gate is inert by default (RFC-012): a project asks for
                ;; its tools' effects to be approved
                :config {:builtin-agents? false :mcp-servers {} :tool-ask-effects #{:sdk/call}}
                :permission-mode :ask-each-time
                :permission-fn (fn [req] (swap! asked conj (:tool req)) {:allowed true}))]
    (try
      (let [{:keys [result]} (answer s "why was 1042 refunded?")]
        (testing "the model is shown it, calls it, and the call is the application's fn"
          (is (str/includes? (:content (first (first @seen))) "order_lookup"))
          (is (= ["1042"] @calls))
          (is (= "refunded" (:answer result))))
        (testing "through the permission gate, as any tool"
          (is (= ["order_lookup"] @asked))))
      (finally (sdk/close! s)))))

(deftest a-client-hook-survives-the-session
  (let [dir (fresh-dir "hook")
        fired (atom [])
        s (open dir (scripted [(reply {:answer "ok"})])
                :hooks [{:id :sdk/watch :event :agent.ask/pre
                         :handler (fn [p _] (swap! fired conj (:question p)) nil)}])]
    (try
      (answer s "one")
      (answer s "two")
      (is (= 2 (count @fired)) "fired for every turn")
      (finally (sdk/close! s)))))

(deftest cancel-close-and-a-session-held-elsewhere
  (let [dir (fresh-dir "cancel")
        started (promise)
        s (open dir {:provider :mock :format :mock :model "mock"
                     :responses (fn [_] (deliver started true) (Thread/sleep 30000) (reply {:answer "late"}))})]
    (try
      (let [p (sdk/submit! s "slow")]
        (deref started 10000 nil)
        (testing "a running turn is cancelled, and the prompt answered with how it ended"
          (is (sdk/running? s))
          (is (sdk/cancel! s))
          (let [r (deref p 10000 ::timeout)]
            (is (not= ::timeout r))
            (is (= :cancelled (get-in r [:result :terminated-by]))))))
      (testing "a session already open is refused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already open in this process"
                              (open dir (scripted [(reply {:answer "x"})]) :resume (:id s)))))
      (finally (is (sdk/close! s))))
    (testing "a prompt after close is refused"
      (is (= "the session is closed" (:refused (answer s "anything")))))))

(deftest an-agent-names-the-sessions-persona
  (let [dir (fresh-dir "persona")
        d (io/file dir ".brainyard" "agents" "support")
        _ (.mkdirs d)
        _ (spit (io/file d "agent.edn") (pr-str {:name "support" :description "answers customers"
                                                :instruction "You are SUPPORT, a careful agent."}))
        seen (atom [])
        s (open dir (scripted [(reply {:answer "ok"})] seen) :agent "support"
                :config {:mcp-servers {} :agent-roots [(str dir "/.brainyard/agents")] :builtin-agents? false})]
    (try
      (answer s "hello")
      (is (str/includes? (:content (first (first @seen))) "You are SUPPORT"))
      (finally (sdk/close! s))))
  (testing "an agent nobody defines is refused before anything opens"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no agent named"
                          (open (fresh-dir "no-agent") (scripted []) :agent "nobody")))))

(deftest predict-is-structured-output-with-no-turn
  (let [dir (fresh-dir "predict")
        r (sdk/predict {:project-dir dir
                        :lm (scripted [(json/write-str {:reasoning "it mentions a refund" :category "billing"})])
                        :signature {:instructions "Classify the ticket."
                                    :inputs [[:ticket {:desc "the ticket"} :string]]
                                    :outputs [[:category {:desc "billing, bug or other"} :string]]}
                        :inputs {:ticket "I was charged twice"}})]
    (is (= "billing" (get-in r [:outputs :category])))
    (is (= "user/inline" (:predictor-id r)))))

(deftest a-session-is-forked-and-the-original-left-as-it-was
  (let [dir (fresh-dir "fork")
        seen (atom [])
        lm {:provider :mock :format :mock :model "mock"
            :responses (fn [[system user :as msgs]]
                         (swap! seen conj (:content system))
                         (json/write-str {:reasoning "r" :tool-calls [] :answer (str "answer " (count @seen))}))}
        one (open dir lm)
        _ (answer one "the first question")
        _ (sdk/close! one)
        before (slurp (persist/session-file (:config one) (:id one)))
        two (open dir lm :fork-from (:id one))]
    (try
      (testing "a new id, its snapshot naming where it came from"
        (is (not= (:id one) (:id two)))
        (is (= (:id one) (:forked-from (persist/read-session (:config two) (:id two))))))
      (testing "its turns go on from the original's: the first question is in its history"
        (answer two "and then?")
        (is (str/includes? (last @seen) "the first question")))
      (finally (sdk/close! two)))
    (testing "the original is as it was"
      (is (= before (slurp (persist/session-file (:config one) (:id one))))))
    (testing "a session that is not there is no fork"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot fork nope"
                            (open dir lm :fork-from "nope"))))))

(deftest an-sdk-session-is-listed-by-session-list-as-saved
  ;; divergence session-list-shows-saved-sessions: an SDK's session — byj
  ;; acp's, a machine's workbench's — never listens on the session channel,
  ;; and `byj session list` showed nothing for it
  (let [dir (fresh-dir "listed")
        s (open dir (scripted [(reply {:answer "ok" :goal-achieved true})]))
        id (:id s)]
    (try (answer s "list me") (finally (sdk/close! s)))
    (let [cfg {:project-dir dir :working-dir dir}
          out (with-out-str (#'brainyard.main/session-command {:questions ["list"]} cfg))]
      (is (str/includes? out id))
      (is (re-find (re-pattern (str id "\\s+saved")) out) "marked saved, not live")
      (is (str/includes? out "list me") "by what it last asked")
      (is (not (str/includes? (with-out-str (#'brainyard.main/session-command {:questions ["list"] :live true} cfg)) id))
          "and not among the live ones")
      (is (= [{:session-id id :saved? true :live? false}]
             (map #(select-keys % [:session-id :saved? :live?])
                  (json/read-str (with-out-str (#'brainyard.main/session-command {:questions ["list"] :json true} cfg)))))
          "in the JSON too"))))
