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
  - `config/config.json`: `byj config --json` in a project whose config.edn
    sets a key and holds one byj does not know, with a home of its own
    (byj-d9q);
  - `deps/deps.json`: `byj deps --json` in a project that depends on one
    found by its path and one found nowhere, with two roles, a session bound
    to one and one gone, and a handoff whose gives are ready but its branch,
    bd's answer given (byj-9dv);
  - `doctor/doctor.json`: `byj doctor --json` in a project with AGENTS.md
    alone, two copies of byj on PATH and a signed-in account, the machine's
    tools all said missing so that the file is not the machine's (byj-mmd);
  - `prices.edn`: the price table byj costs a run by;
  - `loops/slots.edn`: every slot the bundled loops offer a project, with its
    keys and its place (RFC-072).

  What varies between two runs — times, ids, the scratch directory, the
  socket's name — is written `<varies>`, `<sid>`, `<dir>` and `<socket>`, and
  the words of a prompt sent to the model `<prompt>`: their shape is the
  contract, their wording byj's to change. `BYJ_CONTRACT_RECORD=1` writes the
  files again; review the diff, which is the contract changing."
  (:refer-clojure :exclude [record?])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [brainyard.agent :as agent]
            [brainyard.bt :as bt]
            [brainyard.channel :as channel]
            [brainyard.config :as config]
            [brainyard.configbox :as configbox]
            [brainyard.deps :as deps]
            [brainyard.init :as init]
            [brainyard.json :as json]
            [brainyard.main :as main]
            [brainyard.session :as session]
            [brainyard.update :as update]))

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
    :total-tokens :after-tokens
    ;; a turn's loop, by the hash of its tree: it moves with every change to
    ;; a bundled loop; that a record carries one is the contract (byj-ng7.19)
    :hash})

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

(deftest the-settings-are-byjs-contract
  ;; byj-d9q: what the workbench's settings page reads. A home of its own, so
  ;; the person's config.edn is not in it
  (let [dir (fresh-dir "config")
        home (fresh-dir "config-home")
        f (io/file dir ".brainyard" "config.edn")]
    (io/make-parents f)
    (spit f "{:agent {:config {:max-iterations 30 :bogus 1}}}")
    (let [loaded (config/load-config {:working-dir dir :project-dir dir :user-dir home})
          ;; as `byj config --json` prints it, from the same parts
          j (-> (merge (configbox/report (assoc-in loaded [:config :user-dir] home)) {:choices (main/config-choices)})
                ;; the directories a run may write by default name the
                ;; machine's temporary directory and home
                (update :items (fn [is] (mapv #(cond-> % (= "allowed-dirs" (:key %)) (assoc :value "<varies>")) is))))]
      (held "config/config.json"
            (str (json/write-str (normal (json/read-str (json/write-str j)) (str/replace dir (str "/" (.getName (io/file dir))) ""))) "\n")))))

(deftest the-dependencies-are-byjs-contract
  ;; byj-9dv: what the workbench's dependencies tab reads. Outside this
  ;; repository and git, so no commit's id is in it; bd's answer given
  (let [top (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-contract-deps-" (System/nanoTime))) .mkdirs))
        dir (str top "/here")
        sid "s-1700000000000"
        put! (fn [f text] (io/make-parents f) (spit f text))]
    (put! (io/file top "up" "contract" "api.json") "{}\n")
    (put! (io/file dir "docs" "a.md") "# a\n")
    (put! (deps/spec-file dir)
          (pr-str {:schema-version 1
                   :projects {:up {:name "up" :git "git@github.com:o/up.git" :path "../up" :kind :upstream
                                   :provides [{:id :api :kind :files :paths ["contract/api.json"]}
                                              {:id :tool :kind :binary :command "no-such-tool" :version ">= 1.0"}]
                                   :affects ["src/a.clj"]}
                              :lost {:git "git@github.com:o/lost.git" :kind :reference}}
                   :roles {:design {:title "설계" :does "decisions and beads" :bogus 1} :implement {:worktree true :session :own}}
                   :handoffs [{:from :design :to :implement
                               :gives [{:kind :file :paths ["docs/*.md"]} {:kind :bead :id "x-1"} {:kind :branch} {:kind :note}]}]}))
    (put! (deps/local-file dir) (pr-str {:bindings {:design [sid "gone-1"]}}))
    (put! (io/file dir ".brainyard" "sessions" sid "session.edn")
          (pr-str {:schema-version 1 :id sid :turn-count 1 :previous-turns [{:question "decide" :answer "done" :turn 1}]
                   :conversation-title "decide" :created-at 1700000000000 :updated-at 1700000000000}))
    (let [d (deps/report dir {:registry [] :path-env ""
                              :bd-fn (fn [_ & _] {:exit 0 :out "[{\"id\":\"x-1\",\"status\":\"closed\"}]"})})]
      (held "deps/deps.json" (str (json/write-str (normal (json/read-str (json/write-str d)) top)) "\n")))))

(deftest the-doctor-report-is-byjs-contract
  ;; byj-mmd: what the workbench's Doctor panel reads. Outside this
  ;; repository, so the project is not a git repository's subdirectory
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-contract-doctor-" (System/nanoTime))) .mkdirs))
        exe! (fn [d] (let [f (io/file dir d "byj")] (io/make-parents f) (spit f "x") (.setExecutable f true) (.getParent f)))
        path-env (str (exe! "bin") ":" (exe! "other"))]
    (spit (io/file dir "AGENTS.md") "# a\n")
    (with-redefs [init/tool-status (fn [req & _] {:name (:name req) :command (:command req) :need (:need req)
                                                  :for (:for req) :available? false :install []})]
      (let [d (session/doctor-data
               {:config {:project-dir dir :working-dir dir}
                :checkout nil
                :install-lines-fn (fn [_] (update/install-lines {:running nil :path-env path-env :version-of (constantly "v0.0.0")}))
                :claude-usage-fn (constantly {:logged-in? true
                                              :limits [;; a reset already past: one to come is said in
                                                       ;; hours from now, which moves
                                                       {:label "Session" :percent 42 :resets-at "2026-01-01T00:00:00Z"}
                                                       {:label "Weekly" :percent 7 :resets-at nil}]
                                              :account {:email "person@example.invalid" :organization "Org" :plan "max"
                                                        :refresh-expires-at (+ (System/currentTimeMillis) (* 3 86400000) 60000)}})})]
        (held "doctor/doctor.json" (str (json/write-str (normal (json/read-str (json/write-str d)) dir)) "\n"))))))

(deftest the-price-table-is-byjs-contract
  (held "prices.edn" (slurp (io/resource "brainyard/prices.edn"))))

(defn slots
  "Every `:extension` slot in the bundled loop `name`'s own file, in tree
  order: its id and contract, and its place — the node it sits in and the
  node before it there (RFC-072)."
  [name]
  (letfn [(walk [[_ opts & cs]]
            (let [kids (filter vector? cs)]
              (concat
               (for [[prev [k o]] (map vector (cons nil kids) kids) :when (= :extension k)]
                 {:loop name :id (:id o) :reads (:reads o) :writes (:writes o) :on-failure (:on-failure o)
                  :in (:id opts) :after (some-> prev second :id)})
               (mapcat walk kids))))]
    (walk (agent/resolve-tree name))))

(deftest the-slots-the-bundled-loops-offer-are-byjs-contract
  ;; a project attaches to a slot by its id and is held to its keys, so a slot
  ;; that moved or changed its keys breaks every project attached to it
  (held "loops/slots.edn"
        (lines-text (mapcat slots (sort (map #(str/replace (.getName %) #"\.edn$" "")
                                             (.listFiles (io/file "resources/brainyard/loops"))))))))
