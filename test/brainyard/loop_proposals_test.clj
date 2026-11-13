;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-proposals-test
  "`loop/` proposals (RFC-071, byj-ng7.2): a candidate loop staged with its
  evidence, accepted by a person, in force from the next session, and taken
  back by a revert that is a proposal too."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.loop-proposals :as lp]
            [brainyard.main :as main]
            [brainyard.project-loops :as project-loops]
            [brainyard.proposals :as proposals]
            [brainyard.session :as session]))

(defn- fresh-dir [name]
  (let [d (io/file "target/loop-proposals-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getAbsolutePath d)))

(defn- config [dir] {:project-dir dir :working-dir dir})

(defn- candidate
  "The bundled react with `tag` in its root opts: a loop that checks, and
  differs from the bundled one where a diff can show it."
  [tag]
  (let [[k o & cs] (project-loops/bundled-tree "react")]
    (into [k (assoc o :doc (str "candidate " tag))] cs)))

(defn- loop-file [dir] (project-loops/project-loop-file dir "react"))

(deftest staging-refuses-what-may-not-be-a-loop
  (let [dir (fresh-dir "refused")
        c (config dir)]
    (is (str/includes? (:error (lp/stage! c {:loop "react" :tree {:not "a tree"}})) "a loop is a tree"))
    (let [r (lp/stage! c {:loop "react" :tree [:sequence {:id :x/turn} [:action {:id :x/a :fn :no/such-fn}]]})]
      (is (str/starts-with? (:error r) "not staged: "))
      (is (some #(str/includes? (:problem %) "unregistered fn :no/such-fn") (:problems r)) "what flow check refuses"))
    (let [[k o & cs] (candidate "perm")
          r (lp/stage! c {:loop "react"
                          :tree (into [k o [:action {:id :x/p :fn :react/init :permission-mode :auto-approve}]] cs)})]
      (is (some #(str/includes? (:problem %) "it names :permission-mode") (:problems r))
          "a config key of the permissions family, anywhere in its opts"))
    (is (str/includes? (:error (lp/stage! c {:loop "no such/loop" :tree (candidate "x")})) "not a loop name"))
    (is (empty? (lp/list-proposals c)) "nothing was staged")
    (is (= [:permission-mode] (lp/permission-keys-in [:a {:b {:permission-mode 1 :max-n 2}}])))))

(deftest a-person-accepts-a-loop-and-it-is-in-force-from-the-next-session
  (let [dir (fresh-dir "accept")
        c (config dir)
        {:keys [id]} (lp/stage! c {:loop "brainyard/loops/react.edn" :tree (candidate "a")
                                   :why "It verifies before it answers." :report {:verdict :better :primary {:passed [7 5]}}})]
    (testing "listed and shown as /proposals shows every kind"
      (is (= [(str "loop/" id)] (map :id (proposals/list-all c "loop"))))
      (let [{:keys [body]} (proposals/read-one c (str "loop/" id))]
        (is (str/includes? body "It verifies before it answers."))
        (is (str/includes? body ":better") "the evidence")
        (is (str/includes? body "+ ") "the expanded tree's diff against the loop in force")
        (is (str/includes? body "candidate a"))
        (is (str/includes? body "from the next session"))))
    (testing "nothing changes until it is accepted"
      (is (nil? (loop-file dir)))
      (is (empty? (project-loops/history dir))))
    (let [r (proposals/accept! c (str "loop/" id) {})]
      (is (= :next-session (:in-force r)))
      (is (= [:accept (str "loop/" id) nil] ((juxt :action :proposal :replaced) r)))
      (is (= "candidate a" (:doc (second (edn/read-string (slurp (loop-file dir)))))))
      (is (= (project-loops/bundled-hash "react") (:hash (project-loops/tree-based-on (edn/read-string (slurp (loop-file dir))))))
          "written with the loop it was made from")
      (is (= [:accept] (map :action (project-loops/history dir))))
      (is (= "candidate a" (:doc (second (agent/load-tree "react" dir)))) "the loop a session started now expands"))
    (is (empty? (lp/list-proposals c)) "accepted, it is no longer pending")
    (is (str/includes? (:error (lp/accept! c id)) "accepted, not pending"))))

(deftest a-proposal-made-against-another-loop-is-not-accepted
  (let [dir (fresh-dir "stale")
        c (config dir)
        a (:id (lp/stage! c {:loop "react" :tree (candidate "a")}))
        b (:id (lp/stage! c {:loop "react" :tree (candidate "b")}))]
    (is (nil? (:error (lp/accept! c a))))
    (is (str/includes? (:error (lp/accept! c b)) "has changed since")
        "its evidence was about the bundled loop, which is no longer in force")
    (is (= "candidate a" (:doc (second (edn/read-string (slurp (loop-file dir)))))))
    (is (= {:rejected (str "loop/" b)} (lp/reject! c b)))
    (is (empty? (lp/list-proposals c)))))

(deftest a-revert-is-a-proposal-that-puts-back-the-loop-before
  (let [dir (fresh-dir "revert")
        c (config dir)]
    (is (str/includes? (:error (lp/stage-revert! c "react")) "nothing to revert"))
    (lp/accept! c (:id (lp/stage! c {:loop "react" :tree (candidate "a")})))
    (let [written-a (slurp (loop-file dir))]
      (lp/accept! c (:id (lp/stage! c {:loop "react" :tree (candidate "b")})))
      (testing "back to the loop the last accept replaced"
        (let [{:keys [id]} (lp/stage-revert! c "react")]
          (is (str/includes? (:summary (first (lp/list-proposals c))) "revert react to the loop before"))
          (is (= "candidate b" (:doc (second (edn/read-string (slurp (loop-file dir)))))) "staged, nothing changed")
          (is (= :revert (:action (lp/accept! c id))))
          (is (= written-a (slurp (loop-file dir))))))
      (testing "a revert is a change like any other: reverting it puts b back"
        (lp/accept! c (:id (lp/stage-revert! c "react")))
        (is (= "candidate b" (:doc (second (edn/read-string (slurp (loop-file dir))))))))
      (is (= [:accept :accept :revert :revert] (map :action (project-loops/history dir))))))
  (let [dir (fresh-dir "revert-bundled")
        c (config dir)]
    (lp/accept! c (:id (lp/stage! c {:loop "react" :tree (candidate "a")})))
    (let [{:keys [id]} (lp/stage-revert! c nil)]
      (is (str/includes? (:body (lp/read-proposal c id)) "to the bundled loop"))
      (is (nil? (:hash (lp/accept! c id))))
      (is (nil? (loop-file dir)) "the project's file is gone; the bundled loop is found again"))))

(deftest no-tool-reaches-a-loops-accept
  (let [dir (fresh-dir "tools")
        c (config dir)
        {:keys [id]} (lp/stage! c {:loop "react" :tree (candidate "a")})
        ctx (agent/make-ctx {:config c})
        reg @(:tools ctx)]
    (is (not-any? #(or (str/includes? % "accept") (str/includes? % "reject") (str/starts-with? % "loop")) (keys reg)))
    (is (str/includes? (str (:body ((:fn (get reg "proposal$read")) {:id (str "loop/" id)} ctx))) "candidate a")
        "the model may read it")
    (is (str/includes? (str (:error ((requiring-resolve 'brainyard.tools/call-tool)
                                     (get reg "write-file") {:path (str dir "/.brainyard/loops/react.edn") :content "[]"}
                                     (assoc-in ctx [:config :permission-mode] :auto-approve))))
                       "Tool call refused (loop)")
        "nor write the file an accept writes (byj-ng7.12)")
    (is (= [(str "loop/" id)] (map :id (proposals/list-all c "loop"))) "still pending")))

(deftest the-session-says-an-accepted-loop-waits-for-the-next-session
  (let [dir (fresh-dir "session")
        c (config dir)
        {:keys [id]} (lp/stage! c {:loop "react" :tree (candidate "a")})
        out (atom [])
        queue (atom ["/proposals loop" (str "/proposals accept loop/" id)])]
    (session/run {:lm {:provider :mock :format :mock :responses []}
                  :config (merge c {:persist-session? false :recap? false})
                  :read-input (fn [_] (let [[l] @queue] (swap! queue #(vec (rest %))) l))
                  :emit (fn [kind text] (swap! out conj [kind text]))})
    (let [infos (map second (filter #(= :info (first %)) @out))]
      (is (some #(str/includes? % (str "loop/" id)) infos))
      (is (some #(and (str/includes? % "the project's react loop is now .brainyard/loops/react.edn")
                      (str/includes? % "in force from the next session"))
                infos)))))

(defn- run-main
  "`byj args…` in this process: `{:exit :out :err}`."
  [& args]
  (let [err (java.io.StringWriter.)
        code (atom nil)
        out (with-out-str
              (binding [*err* err
                        main/*exit* (fn [c] (throw (ex-info "exit" {::exit c})))]
                (try (apply #'main/main* args)
                     (catch clojure.lang.ExceptionInfo e
                       (if-let [c (::exit (ex-data e))] (reset! code c) (throw e))))))]
    {:exit @code :out out :err (str err)}))

(deftest byj-loops-revert-stages-a-proposal
  (let [dir (fresh-dir "cli")
        ;; a repository of its own, so the project the CLI finds is this one
        _ (.waitFor (.start (doto (ProcessBuilder. ["git" "init" "-q" dir]) (.redirectErrorStream true))))
        c (config dir)]
    (is (str/includes? (:err (run-main "-C" dir "loops" "revert")) "nothing to revert"))
    (lp/accept! c (:id (lp/stage! c {:loop "react" :tree (candidate "a")})))
    (let [{:keys [exit out]} (run-main "-C" dir "loops" "revert")]
      (is (= 0 exit))
      (is (re-find #"staged loop/react-\d+ — /proposals show" out))
      (is (some? (loop-file dir)) "staged only: the project's loop is still there"))
    (let [{:keys [exit out]} (run-main "-C" dir "loops" "revert" "react" "--json")]
      (is (= 0 exit))
      (is (str/includes? out "\"id\":\"react-")))))

;; --- attachments to a slot (RFC-072, byj-9sp.3) --------------------------------------

(def ^:private lint [:action {:id :lint/note :fn :mem/set :set {:ext/notices "LINT: fine"}}])

(defn- note
  "Another attachment: its own node id, since two trees attached with one id
  would make a loop whose trace cannot name either."
  [n]
  [:action {:id (keyword "note" n) :fn :mem/set :set {:ext/notices n}}])

(deftest an-attachment-is-staged-read-and-accepted-as-a-loop-is
  (let [dir (fresh-dir "attach")
        c (config dir)]
    (testing "refused: no such slot, a name that is no file's, what flow check refuses, a permissions key"
      (is (str/includes? (:error (lp/stage-attachment! c {:slot :react/nowhere :name "lint" :tree lint}))
                         "has no slot :react/nowhere — it has :react/ext-after-tools"))
      (is (str/includes? (:error (lp/stage-attachment! c {:slot :react/ext-after-tools :name "../x" :tree lint}))
                         "an attachment's name is a word"))
      (is (str/includes? (:error (lp/stage-attachment! c {:slot :react/ext-after-tools :name "bad"
                                                          :tree [:action {:id :x/n :fn :no/such-fn}]}))
                         "unregistered fn :no/such-fn"))
      (is (str/includes? (:error (lp/stage-attachment! c {:slot :react/ext-after-tools :name "perm"
                                                          :tree [:action {:id :x/p :fn :mem/set :set {} :permission-mode :auto}]}))
                         "it names :permission-mode"))
      (is (str/includes? (:error (lp/stage-attachment! c {:slot :react/ext-after-tools :name "lint" :tree nil}))
                         "lint is not attached to :react/ext-after-tools"))
      (is (empty? (lp/list-proposals c))))
    (let [{:keys [id]} (lp/stage-attachment! c {:slot :react/ext-after-tools :name "lint" :tree lint :why "Lint after a write."})]
      (testing "listed and shown, with the diff of the loop every turn would run"
        (is (str/starts-with? id "ext-lint-"))
        (is (= [(str "attach lint to :react/ext-after-tools in the react loop (in force: attached "
                     (:hash (lp/attached-in-force c)) ")")]
               (map :summary (lp/list-proposals c))))
        (let [{:keys [body]} (proposals/read-one c (str "loop/" id))]
          (is (str/includes? body "Lint after a write."))
          (is (str/includes? body "+ ") "a diff")
          (is (str/includes? body ":lint/note"))))
      (testing "nothing is attached until a person accepts"
        (is (= {} (project-loops/attached dir))))
      (let [r (proposals/accept! c (str "loop/" id) {})]
        (is (= [:attach :react/ext-after-tools "lint" (str "loop/" id)] ((juxt :action :slot :name :proposal) r)))
        (is (= {:react/ext-after-tools ["lint"]} (project-loops/attached dir)))
        (is (= (get (project-loops/slots (agent/load-tree "react")) :react/ext-after-tools)
               (:contract (project-loops/tree-based-on (edn/read-string (slurp (project-loops/attachment-file dir "lint"))))))
            "written with the slot's contract it was made against")
        (is (some #(= :lint/note (:id (second %))) (tree-seq vector? #(filter vector? (drop 2 %)) (agent/load-tree "react" dir))))))
    (testing "an attachment staged before another change is not accepted"
      (is (str/includes? (:error (lp/stage-attachment! c {:slot :react/ext-after-tools :name "again" :tree lint}))
                         "duplicate node id :lint/note")
          "one tree attached twice is refused")
      (let [a (lp/stage-attachment! c {:slot :react/ext-after-tools :name "two" :tree (note "two")})
            b (lp/stage-attachment! c {:slot :react/ext-after-tools :name "three" :tree (note "three")})]
        (is (nil? (:error (lp/accept! c (:id a)))))
        (is (str/includes? (:error (lp/accept! c (:id b))) "has changed since"))))
    (testing "a revert undoes the last change: two, attached last, is detached"
      (let [{:keys [id]} (lp/stage-revert! c "react")]
        (is (str/starts-with? (:body (lp/read-proposal c id)) "detach two from :react/ext-after-tools"))
        (is (nil? (:error (lp/accept! c id))))
        (is (= {:react/ext-after-tools ["lint"]} (project-loops/attached dir)))
        (testing "and a revert of that detach attaches it again, as it was"
          (let [{:keys [id]} (lp/stage-revert! c "react")]
            (is (nil? (:error (lp/accept! c id))))
            (is (= {:react/ext-after-tools ["lint" "two"]} (project-loops/attached dir)))))))
    (testing "a detach is staged by name and accepted"
      (let [{:keys [id]} (lp/stage-attachment! c {:slot :react/ext-after-tools :name "lint"})]
        (is (nil? (:error (lp/accept! c id))))
        (is (= {:react/ext-after-tools ["two"]} (project-loops/attached dir)))
        (is (= [:attach :attach :detach :attach :detach] (map :action (project-loops/history dir))))))))
