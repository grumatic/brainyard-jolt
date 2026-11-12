;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.contract-test
  "byj's public contract with the programs that reach it as a process, a
  socket or a file (RFC-070 phase 2, byj-ahf.6): golden files in `contract/`
  of what byj gives, held here to what it gives now. Share vendors them and
  its tests read its copy, so neither side's tests read the other's tree.

  - `session/status.edn`, `session/frames.edn`: the session socket's
    `:status` reply and the frames a turn that reads a file streams to a
    `:subscribe` (RFC-031), a frame a line;
  - `trajectory/trajectory.edn`, `trajectory/meta.edn`: what that turn leaves
    on disk (RFC-025, RFC-016);
  - `agent/agents.json`: `byj agent --json` in a project of byj's own agents;
  - `prices.edn`: the price table byj costs a run by.

  What varies between two runs — times, ids, the scratch directory, the
  socket's name — is written `<varies>`, `<sid>`, `<dir>` and `<socket>`, and
  the words of a prompt sent to the model `<prompt>`: their shape is the
  contract, their wording byj's to change. `BYJ_CONTRACT_RECORD=1` writes the
  files again; review the diff, which is the contract changing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [brainyard.channel :as channel]
            [brainyard.config :as config]
            [brainyard.json :as json]
            [brainyard.main :as main]
            [brainyard.session :as session]))

(def root "contract")

(def record? (= "1" (System/getenv "BYJ_CONTRACT_RECORD")))

(defn- fresh-dir [name]
  (let [d (io/file "target/contract-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(def varying
  "Keys whose values differ between two runs of the same turn."
  #{:at :ts :ms :started-at :ended-at :created-at :updated-at :last-active :pid :duration-ms :elapsed-ms
    :latency-ms :wall-ms :timestamp :time :ttft-ms :tokens-per-s :saved-at
    ;; :auto resolves by where byj runs: :auto-approve in a container
    :resolved-mode
    ;; the commit byj was built from
    :brainyard-version
    ;; a prompt's size, which the scratch directory's path is part of: a
    ;; checkout elsewhere (byj's export, byj-ahf.2) counts it otherwise
    :total-tokens :after-tokens})

(defn- prompt-elided
  "A frame of the prompt a model is sent, its messages' words written
  <prompt>: their shape is the contract, their wording byj's to change."
  [frame]
  (if (= :agent.llm/request (:event frame))
    (update-in frame [:payload :messages]
               (fn [ms] (mapv #(cond-> % (contains? % :content) (assoc :content "<prompt>")
                                     (contains? % :parts) (assoc :parts "<prompt>"))
                              ms)))
    frame))

(defn- normal
  "`x` with the scratch dir written <dir>, session ids <sid>, the session's
  socket <socket>, and the values of `varying` keys <varies>."
  [x dir]
  (walk/postwalk
   (fn [v]
     (cond
       (map-entry? v) (if (and (keyword? (key v)) (varying (key v)) (some? (val v))) [(key v) "<varies>"] v)
       (string? v) (-> v
                       (str/replace dir "<dir>")
                       (str/replace #"[^\s\"]*/by-[0-9a-f]{16}\.sock" "<socket>")
                       (str/replace #"\b\d{13}-[0-9a-f]{8}\b" "<sid>")
                       (str/replace #"\bs-\d{13}\b" "<sid>")
                       ;; where the socket lives is byj's to choose: in the
                       ;; session's directory where its path fits a socket's
                       ;; (Linux), else a short one (macOS) (byj-ahf.29)
                       (str/replace "<dir>/.brainyard/sessions/<sid>/ask.sock" "<socket>"))
       :else v))
   x))

(defn- lines-text [xs] (str/join (map #(str (pr-str %) "\n") xs)))

(defn- held
  "`text` is contract file `path`'s, or, recording, becomes it."
  [path text]
  (let [f (io/file root path)]
    (if record?
      (do (io/make-parents f) (spit f text) (is true))
      (is (= (when (.isFile f) (slurp f)) text)
          (str "contract/" path " is not what byj gives now: BYJ_CONTRACT_RECORD=1 jolt -M:test brainyard.contract-test, and review the diff")))))

(defn- a-turn
  "A session in `dir`, its channel open, asked one question over the socket
  that reads note.txt and answers: `{:status :frames :reply :sid}`."
  [dir]
  (spit (io/file dir "note.txt") "hello from the note\n")
  (let [sid (str "s-" (System/currentTimeMillis))
        ch (channel/open! {:project-dir dir :working-dir dir :ask-timeout-ms 20000} sid {:cancel! (constantly true)})
        frames (atom [])
        {:keys [stop!]} (channel/subscribe! (:path ch) nil #(swap! frames conj %))
        done? (atom false)
        replies (atom [{:reasoning "the note says what it says" :tool-calls [{:tool-name "read-file" :tool-args {:path "note.txt"}}]}
                       {:reasoning "it read" :tool-calls [] :answer "the note says hello"}])
        read-input (fn [_] (loop [] (if @done? nil
                                        (if-let [{:keys [text]} (channel/next-turn! ch)] text
                                                (do (Thread/sleep 10) (recur))))))
        out (promise)]
    (.start (Thread. #(deliver out (let [s (channel/request! (:path ch) {:op :status} 5000)
                                         r (channel/request! (:path ch) {:op :ask :question "what does note.txt say?"} 20000)]
                                     (Thread/sleep 300)
                                     (reset! done? true)
                                     {:status s :reply r}))))
    (session/run {:lm {:provider :mock :format :mock
                       :responses (fn [_] (json/write-str (let [r (first @replies)] (swap! replies #(vec (rest %))) (or r {:answer "done"}))))}
                  :config {:working-dir dir :project-dir dir :max-iterations 4 :recap? false :session-id sid}
                  :read-input read-input :emit (fn [_ _]) :channel ch})
    (let [{:keys [status reply]} (deref out 30000 nil)]
      (stop!)
      (channel/close! ch)
      {:status status :reply reply :frames @frames :sid sid})))

(deftest the-session-socket-and-the-trajectory-are-byjs-contract
  (let [dir (fresh-dir "turn")
        {:keys [status reply frames sid]} (a-turn dir)
        sdir (io/file dir ".brainyard" "sessions" sid)]
    (testing "the socket: the :status reply, the frames a turn streams, its answer"
      (is (= :ok (:status reply)) (pr-str reply))
      (held "session/status.edn" (lines-text [(normal status dir)]))
      (held "session/frames.edn" (lines-text (normal (mapv prompt-elided frames) dir)))
      (held "session/ask.edn" (lines-text [(normal reply dir)])))
    (testing "what the turn leaves on disk"
      (is (.isFile (io/file sdir "trajectory.edn")) "the turn kept its trajectory")
      (held "trajectory/trajectory.edn"
            (lines-text (normal (vec (for [l (str/split-lines (slurp (io/file sdir "trajectory.edn"))) :when (not (str/blank? l))]
                                       (edn/read-string l)))
                                dir)))
      (held "trajectory/meta.edn"
            (with-out-str (pprint/pprint (normal (edn/read-string (slurp (io/file sdir "meta.edn"))) dir)))))))

(deftest the-agent-listing-is-byjs-contract
  (let [dir (fresh-dir "agents")
        ;; no tool that registers only where a program is installed (emacsclient,
        ;; tmux): the listing is byj's, not the machine's (byj-ahf.29)
        config (assoc (:config (config/load-config {:working-dir dir :project-dir dir :user-dir (fresh-dir "agents-home")}))
                      :emacs-tools? false :terminal-tools? false)
        out (with-out-str (#'main/agent-command {:json true} config))]
    (held "agent/agents.json" (str (json/write-str (normal (json/read-str (str/trim out)) dir)) "\n"))))

(deftest the-price-table-is-byjs-contract
  (held "prices.edn" (slurp (io/resource "brainyard/prices.edn"))))
