;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.deps-test
  "A project's dependencies as data, checked, and written safely (byj-9dv,
  RFC-075)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [brainyard.persist]
            [brainyard.changes :as changes]
            [brainyard.deps :as deps]
            [brainyard.effect :as fx]))

(defn- tmp [name]
  (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-deps-" name "-" (System/nanoTime))) .mkdirs)))

(defn- git! [dir & args]
  (let [r (apply fx/sh {:dir (str dir) :out :string :err :string}
                 "git" "-c" "user.name=t" "-c" "user.email=t@example.invalid" "-c" "commit.gpgsign=false" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) {})))
    (str/trim (str (:out r)))))

(defn- repo! [name]
  (let [d (tmp name)]
    (git! d "init" "-q" "-b" "main")
    d))

(defn- commit! [dir path text msg]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f text)
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" msg)
    (git! dir "rev-parse" "--short" "HEAD")))

(defn- session! [dir id {:keys [turns title]}]
  (let [f (io/file (brainyard.persist/state-dir {:project-dir dir} "sessions") id "session.edn")]
    (io/make-parents f)
    (spit f (pr-str {:schema-version 1 :id id :turn-count turns
                     :previous-turns (vec (for [i (range turns)] {:question (str "q" i) :answer "a" :turn (inc i)}))
                     :conversation-title title :created-at 1000 :updated-at 1763427723000}))))

(defn- bin! [dir name said]
  (let [f (io/file dir name)]
    (io/make-parents f)
    (spit f (str "#!/bin/sh\necho '" said "'\n"))
    (.setExecutable f true)))

(defn- kinds [r] (set (map (juxt :level :kind) (:checks r))))

(defn- check [r kind] (filter #(= kind (:kind %)) (:checks r)))

(deftest a-projects-dependencies-are-read-resolved-and-checked
  (let [up (repo! "up")
        seen (commit! up "README.md" "up\n" "first")
        _ (commit! up "contract/config/config.json" "{}\n" "the contract")
        _ (commit! up "src/other.clj" "x\n" "elsewhere")
        _ (git! up "remote" "add" "origin" "https://github.com/o/elsewhere.git")
        reg (repo! "reg")
        _ (git! reg "remote" "add" "origin" "git@github.com:o/reg.git")
        dir (repo! "here")
        _ (commit! dir "docs/decisions/a.md" "# a\n" "a decision")
        wt (str (tmp "wt") "/wt")
        _ (git! dir "worktree" "add" "-q" "-b" "byj/design" wt)
        _ (commit! wt "notes.md" "n\n" "on the branch")
        bin (tmp "bin")
        _ (bin! bin "tool" "tool 1.2.3")
        rel (fn [d] (str (.relativize (.toPath (io/file dir)) (.toPath (io/file d)))))
        spec {:schema-version 1
              :projects {:up {:name "up" :git "git@github.com:o/up.git" :path (rel up) :kind :upstream
                              :provides [{:id :config-api :kind :files :paths ["contract/config/config.json"]}
                                         {:id :gone :kind :files :paths ["gone.txt"]}
                                         {:id :tool :kind :binary :command "tool" :version ">= 1.0, < 2"}
                                         {:id :new-tool :kind :binary :command "tool" :version ">= 2"}
                                         {:id :absent :kind :binary :command "no-such-tool-anywhere"}]
                              :seen seen}
                         :reg {:git "https://github.com/o/reg" :kind :reference}
                         :nowhere {:path "../no-such-dir-at-all" :kind :reference}}
              :roles {:design {:title "설계"} :implement {:worktree true} :review {}}
              :handoffs [{:from :design :to :implement
                          :gives [{:kind :file :paths ["docs/decisions/*.md"]}
                                  {:kind :bead :id "x-1"}
                                  {:kind :branch}
                                  {:kind :note}]}
                         {:from :implement :to :review :gives [{:kind :note} {:kind :bead :parent "x-2"}]}]}]
    (io/make-parents (deps/spec-file dir))
    (spit (deps/spec-file dir) (pr-str spec))
    (spit (io/file dir ".brainyard" "deps-local.edn")
          (pr-str {:bindings {:design ["s-design" "s-wt"] :implement ["s-impl" "gone-1"]}}))
    (session! dir "s-design" {:turns 1 :title "decide"})
    (session! wt "s-wt" {:turns 0})
    (session! dir "s-impl" {:turns 2 :title "build"})
    (let [bd (fn [_ & args]
               (case (first args)
                 "show" {:exit 0 :out "[{\"id\":\"x-1\",\"status\":\"closed\"}]"}
                 "list" {:exit 0 :out "[{\"id\":\"x-2.1\",\"status\":\"closed\"},{\"id\":\"x-2.2\",\"status\":\"open\"}]"}))
          report #(with-redefs [changes/rows (fn [_ id] (get {"s-design" [{:turn 1 :at 1 :files ["docs/decisions/a.md" "x.clj"]}]} id []))]
                    (deps/report dir {:registry [{:path reg :git-remote "git@github.com:o/reg.git"}]
                                      :path-env bin :bd-fn bd}))
          r (report)
          proj (fn [id] (some #(when (= id (:id %)) %) (:projects r)))]
      (testing "both files, as JSON names them"
        (is (= {:path (str dir "/.brainyard/deps.edn") :exists true} (get-in r [:files :spec])))
        (is (= "upstream" (get-in r [:spec :projects :up :kind])))
        (is (= "config-api" (get-in r [:spec :projects :up :provides 0 :id])))
        (is (= ["s-design" "s-wt"] (get-in r [:local :bindings :design])))
        (is (= [] (:problems r))))
      (testing "a project found by its path, by the registry, or not at all"
        (is (= [up "path" "https://github.com/o/elsewhere.git"] ((juxt :dir :resolvedBy :gitRemote) (proj "up"))))
        (is (= [reg "registry"] ((juxt :dir :resolvedBy) (proj "reg"))))
        (is (= [nil nil] ((juxt :dir :resolvedBy) (proj "nowhere"))))
        (is (= ["error" {:project "nowhere"}] ((juxt :level :target) (first (check r "project-missing"))))))
      (testing "what a project provides, as it stands"
        (is (= [["config-api" true] ["gone" false] ["tool" true] ["new-tool" false] ["absent" false]]
               (mapv (juxt :id :ok) (:provides (proj "up")))))
        (is (= "tool 1.2.3" (:detail (nth (:provides (proj "up")) 2))))
        (is (= #{["error" "provide-missing"] ["error" "version"]}
               (set (map (juxt :level :kind) (filter #(#{"provide-missing" "version"} (:kind %)) (:checks r))))))
        (is (= 2 (count (check r "provide-missing")))))
      (testing "its commits since :seen that touch what it provides"
        (is (= [["the contract" ["contract/config/config.json"]]] (mapv (juxt :subject :paths) (:changes (proj "up")))))
        (is (re-matches #"\d{4}-\d\d-\d\dT.*" (:at (first (:changes (proj "up"))))))
        (is (= [] (:changes (proj "reg"))) "none without :seen")
        (is (contains? (kinds r) ["warn" "upstream-changed"]))
        (is (= #{{:project "reg"} {:project "nowhere"}} (set (map :target (check r "never-reviewed")))))
        (is (= [{:project "up"}] (map :target (check r "remote-mismatch")))))
      (testing "the sessions bound to each role, a worktree's among them"
        (let [role (fn [id] (some #(when (= id (:id %)) %) (:roles r)))]
          (is (= [["s-design" true "decide" 1] ["s-wt" true nil 0]]
                 (mapv (juxt :id :exists :title :turnCount) (:sessions (role "design")))))
          (is (= [["s-impl" true] ["gone-1" false]] (mapv (juxt :id :exists) (:sessions (role "implement")))))
          (is (= "2025-11-18T01:02:03Z" (:updatedAt (first (:sessions (role "design"))))))
          (is (= [{:role "review"}] (map :target (check r "unbound-role"))))
          (is (= [{:role "implement"}] (map :target (check r "session-gone"))))))
      (testing "each give, ready or not"
        (let [[h1 h2] (:handoffs r)]
          (is (= [["file" true ["docs/decisions/a.md"]] ["bead" true ["x-1"]] ["branch" true ["byj/design"]] ["note" true ["s-design"]]]
                 (mapv (juxt :kind :ready :items) (:gives h1))))
          (is (true? (:ready h1)))
          (is (nil? (:handed h1)))
          (is (= [["note" true] ["bead" false]] (mapv (juxt :kind :ready) (:gives h2))))
          (is (= "1 of 2 under x-2 not closed" (:detail (second (:gives h2)))))
          (is (= [["wait" {:handoff ["implement" "review"]}]] (map (juxt :level :target) (check r "not-ready"))))
          (is (= [{:handoff ["design" "implement"]}] (map :target (check r "handoff-ready"))))
          (is (= [{:handoff ["design" "implement"]}] (map :target (check r "started-early")))
              "s-impl took turns while design → implement was not handed")))
      (testing "handed: no longer ready to hand, and early only for the turns before it"
        (spit (io/file dir ".brainyard" "deps-local.edn")
              (pr-str {:bindings {:design ["s-design" "s-wt"] :implement ["s-impl"]}
                       :handed [{:from :design :to :implement :session "s-impl" :turn 1 :at #inst "2026-11-18T00:00:00Z"}]}))
        (let [r (report)]
          (is (= {:session "s-impl" :turn 1 :at "2026-11-18T00:00:00Z"} (:handed (first (:handoffs r)))))
          (is (empty? (check r "handoff-ready")))
          (is (empty? (check r "started-early")))))
      (testing "a session going on from the handing role is not early (byj-ba5)"
        (spit (io/file dir ".brainyard" "deps-local.edn")
              (pr-str {:bindings {:design ["s-design" "s-wt" "s-impl"] :implement ["s-impl"]}}))
        (is (empty? (check (report) "started-early"))))
      (testing "its health, for /init's check and byj doctor: the projects' trouble, no session and no give (byj-g6o)"
        (let [ran (atom [])
              h (deps/health dir {:registry [{:path reg :git-remote "git@github.com:o/reg.git"}]
                                  :path-env bin :bd-fn (fn [& a] (swap! ran conj a) {:exit 1}) :gh-fn (fn [& a] (swap! ran conj a) {:exit 1})})]
          (is (= 3 (:projects h)))
          (is (= [] (:problems h)))
          (is (some #(str/starts-with? % "nowhere is not on this machine") (:errors h)))
          (is (= 3 (count (filter #(str/starts-with? % "up provides ") (:errors h)))) "gone, new-tool and absent")
          (is (some #(str/starts-with? % "up's origin is https://github.com/o/elsewhere.git") (:warnings h)))
          (is (some #(str/starts-with? % "up has 1 commit(s) since") (:warnings h)))
          (is (not-any? #(re-find #"session|role|hand" %) (concat (:errors h) (:warnings h))) "roles and handoffs are byj deps's")
          (is (empty? @ran) "neither bd nor gh is run"))
        (is (nil? (deps/health (tmp "no-spec"))) "no deps.edn, nothing to say"))
      (testing "without bd, a bead give is not ready, and says why"
        (let [r (with-redefs [changes/rows (constantly [])] (deps/report dir {:registry [] :path-env bin}))]
          (is (= "bd is not on PATH" (:detail (second (:gives (first (:handoffs r))))))))))))

(deftest an-ssh-host-alias-names-the-forge-it-stands-for
  ;; byj-sih: `Host github-work` / `HostName github.com` in ~/.ssh/config, a
  ;; second GitHub account's remote
  (binding [deps/*ssh-hostname* {"github-work" "github.com" "github.com" "github.com"}]
    (testing "an alias is the host ssh resolves it to; https is as written"
      (is (= "github.com/o/r" (deps/git-key "git@github-work:o/r.git")))
      (is (= "github.com/o/r" (deps/git-key "ssh://git@github-work:22/o/r.git")))
      (is (= "github.com/o/r" (deps/git-key "https://github.com/O/r/")))
      (is (deps/same-repo? "git@github-work:o/r.git" "https://github.com/o/r")))
    (testing "the registry finds a project by its alias's origin, and no remote-mismatch"
      (let [dir (tmp "alias-here")
            reg (tmp "alias-reg")]
        (fx/sh {:dir reg :out :string :err :string} "git" "init" "-q")
        (fx/sh {:dir reg :out :string :err :string} "git" "remote" "add" "origin" "git@github-work:o/r.git")
        (io/make-parents (deps/spec-file dir))
        (spit (deps/spec-file dir) (pr-str {:projects {:r {:git "git@github.com:o/r.git" :seen "x"}}}))
        (let [r (deps/report dir {:registry [{:path reg :git-remote "git@github-work:o/r.git"}]})]
          (is (= [reg "registry"] ((juxt :dir :resolvedBy) (first (:projects r)))))
          (is (empty? (check r "remote-mismatch")))))))
  (testing "where ssh cannot say, the owner and name, one host no known forge"
    (binding [deps/*ssh-hostname* (constantly nil)]
      (is (= "github-work/o/r" (deps/git-key "git@github-work:o/r.git")))
      (is (deps/same-repo? "git@github-work:o/r.git" "git@github.com:o/r.git"))
      (is (not (deps/same-repo? "git@gitlab.com:o/r.git" "git@github.com:o/r.git")) "two forges are two repositories")
      (is (not (deps/same-repo? "git@github-work:o/other.git" "git@github.com:o/r.git")))
      (is (not (deps/same-repo? "git@github.com:o/r.git" nil))))))

(deftest a-registered-project-is-found-by-its-origin-as-it-is-now
  ;; byj-339: a record registered before its origin was set holds
  ;; :git-remote nil, and one whose origin moved holds the old one
  (binding [deps/*ssh-hostname* (constantly nil)]
    (let [dir (tmp "live-here")
          reg (repo! "live-reg")
          _ (git! reg "remote" "add" "origin" "git@github.com:o/reg.git")
          found (fn [rec] (deps/resolve-project dir {:git "https://github.com/o/reg"} [(merge {:path reg} rec)]))]
      (is (= {:dir reg :resolved-by :registry} (found {:git-remote nil})) "recorded none")
      (is (= {:dir reg :resolved-by :registry} (found {:git-remote "git@github.com:o/old.git"})) "recorded another")
      (is (= {} (deps/resolve-project dir {:git "https://github.com/o/old"} [{:path reg :git-remote "git@github.com:o/old.git"}]))
          "what the folder's origin is now wins over what was recorded")
      (is (= {} (found {:git-remote nil :missing? true})) "a missing project is not found")
      (let [bare (tmp "live-nogit")]
        (is (= {:dir bare :resolved-by :registry}
               (deps/resolve-project dir {:git "https://github.com/o/reg"} [{:path bare :git-remote "git@github.com:o/reg.git"}]))
            "where there is no git config to read, the record's")))))

;; byj-ykq, #3: a give of an issue in whichever of the profile's trackers
;; holds it, not only a bead in bd
(deftest an-issue-give-is-checked-in-the-tracker-that-holds-it
  (testing "the tracker: its own, a GitHub number, the profile's only one, a key Linear or Jira holds, else beads"
    (let [t #(deps/issue-tracker %1 %2)]
      (is (= {:tracker :linear} (t {:id "#4" :tracker :linear} [])))
      (is (= {:tracker :github} (t {:id "#42"} [{:tracker :beads}])))
      (is (= {:tracker :github} (t {:id "o/r#42"} [])))
      (is (= {:tracker :github} (t {:id "42"} [{:tracker :beads} {:tracker :github}])))
      (is (= {:tracker :beads} (t {:id "x-1"} [{:tracker :beads} {:tracker :github}])))
      (is (= {:tracker :jira} (t {:id "x-1"} [{:tracker :jira}])) "the only one named")
      (is (= {:tracker :linear} (t {:id "ENG-12"} [{:tracker :beads} {:tracker :linear}])))
      (is (:error (t {:id "ENG-12"} [{:tracker :linear} {:tracker :jira}])) "both could hold it: said, not guessed")
      (is (= {:tracker :beads} (t {:epic "x"} [])) "nothing named: beads, as a bead give")))
  (let [dir (repo! "issues")
        _ (spit (doto (io/file dir ".brainyard" "profile.edn") io/make-parents)
                (pr-str {:profile/version 1 :issues [{:tracker :beads} {:tracker :github :github-repo "o/here"}]}))
        _ (spit (deps/spec-file dir)
                (pr-str {:roles {:a {} :b {}}
                         :handoffs [{:from :a :to :b
                                     :gives [{:kind :issue :id "#7"}
                                             {:kind :issue :id "other/repo#8"}
                                             {:kind :issue :id "x-1"}
                                             {:kind :issue :parent "x-2"}
                                             {:kind :issue :id "ENG-3" :tracker :linear}
                                             {:kind :bead :id "x-1"}]}]}))
        asked (atom [])
        gh (fn [_ & args]
             (swap! asked conj (vec args))
             (case (nth args 2) "7" {:exit 0 :out "{\"state\":\"CLOSED\"}"} "8" {:exit 0 :out "{\"state\":\"OPEN\"}"}))
        bd (fn [_ & args]
             (case (first args)
               "show" {:exit 0 :out "[{\"id\":\"x-1\",\"status\":\"closed\"}]"}
               "list" {:exit 0 :out "[{\"id\":\"x-2.1\",\"status\":\"closed\"}]"}))
        gives #(:gives (first (:handoffs (with-redefs [changes/rows (constantly [])]
                                            (deps/report dir (merge {:registry [] :gh-fn gh :bd-fn bd} %))))))]
    (testing "each in its tracker, the profile's :issues read for them"
      (is (= [["issue" "github" true ["#7"] "#7 is closed"]
              ["issue" "github" false ["other/repo#8"] "other/repo#8 is open"]
              ["issue" "beads" true ["x-1"] "x-1 is closed"]
              ["issue" "beads" true ["x-2.1"] "all 1 under x-2 closed"]
              ["issue" "linear" false ["ENG-3"] "byj does not read linear issues yet; ENG-3 is not checked"]
              ["bead" nil true ["x-1"] "x-1 is closed"]]
             (mapv (juxt :kind :tracker :ready :items :detail) (gives {}))))
      (is (= [["issue" "view" "7" "--json" "state" "--repo" "o/here"]
              ["issue" "view" "8" "--json" "state" "--repo" "other/repo"]]
             @asked)
          "the profile's repository, unless the id names its own"))
    (testing "a tracker byj cannot reach: not ready, and said which"
      (let [bin (tmp "nobin")
            [g7 _ x1] (gives {:gh-fn nil :bd-fn nil :path-env bin})]
        (is (= [false "gh is not on PATH"] ((juxt :ready :detail) g7)))
        (is (= [false "bd is not on PATH"] ((juxt :ready :detail) x1)))
        (is (= [false "gh issue view: not logged in"]
               ((juxt :ready :detail) (first (gives {:gh-fn (fn [& _] {:exit 1 :err "not logged in"})})))))))
    (testing "written from JSON, its tracker a keyword"
      (let [w (deps/write! dir :spec {"roles" {"a" {} "b" {}}
                                      "handoffs" [{"from" "a" "to" "b" "gives" [{"kind" "issue" "id" "ENG-3" "tracker" "jira"}]}]} {})]
        (is (:ok w) (pr-str w))
        (is (= [{:kind :issue :id "ENG-3" :tracker :jira}]
               (:gives (first (:handoffs (edn/read-string (slurp (deps/spec-file dir)))))))))
      (is (= :schema
             (:stage (deps/write! dir :spec {"roles" {"a" {} "b" {}}
                                             "handoffs" [{"from" "a" "to" "b" "gives" [{"kind" "issue" "id" "#1" "tracker" "gitea"}]}]} {})))
          "a tracker the profile cannot name is refused"))))

(deftest a-bead-give-reads-as-it-did-and-is-said-deprecated
  ;; a bead is an issue: {:kind :bead} is deprecated for the :issue give in
  ;; beads, which says the same whatever trackers the profile names (byj-5q4)
  (testing "the issue give that says the same, kept in beads"
    (is (= {:kind :issue :id "x-1" :tracker :beads} (deps/bead-as-issue {:kind :bead :id "x-1"})))
    (is (= {:kind :issue :parent "x" :tracker :beads} (deps/bead-as-issue {:kind :bead :parent "x"})))
    (is (= {:tracker :beads} (deps/issue-tracker (deps/bead-as-issue {:kind :bead :id "x-1"}) [{:tracker :github}]))
        "not GitHub's though it is the profile's only tracker")
    (is (= {:kind :note} (deps/bead-as-issue {:kind :note}))))
  (let [dir (tmp "bead-give")
        spec {"roles" {"a" {} "b" {}}
              "handoffs" [{"from" "a" "to" "b" "gives" [{"kind" "bead" "id" "x-1"} {"kind" "issue" "id" "#2"}]}]
              "workflows" {"fix" {"stages" {"a" {} "b" {}}
                                  "links" [{"from" "a" "to" "b" "gives" [{"kind" "bead" "parent" "x"}]}]}}}
        w (deps/write! dir :spec spec {:registry []})]
    (testing "written as asked, and said"
      (is (:ok w) (pr-str w))
      (is (= [{:kind :bead :id "x-1"} {:kind :issue :id "#2"}]
             (:gives (first (:handoffs (edn/read-string (slurp (deps/spec-file dir))))))))
      (is (= ["a → b: a :bead give is deprecated, a bead being an issue; write {:kind :issue :tracker :beads :id \"x-1\"} instead"
              "workflow fix, a → b: a :bead give is deprecated, a bead being an issue; write {:kind :issue :tracker :beads :parent \"x\"} instead"]
             (:warnings w))))
    (testing "the report checks it as it did, and warns"
      (let [r (deps/report dir {:registry [] :issues [] :path-env ""})]
        (is (= [{:handoff ["a" "b"]} {:workflow "fix" :link ["a" "b"]}]
               (mapv :target (check r "deprecated-give"))))
        (is (every? #(= "warn" (:level %)) (check r "deprecated-give")))
        (is (= "bead" (:kind (first (:gives (first (:handoffs r))))))
            "its row is a bead give's, as before")))
    (testing "and byj doctor and /init's check say it"
      (is (= 2 (count (filter #(str/includes? % ":bead give is deprecated") (:warnings (deps/health dir {:registry []}))))))))
  (testing "a spec without one is written with no warnings"
    (is (not (contains? (deps/write! (tmp "no-bead") :spec {"roles" {"a" {}}} {:registry []}) :warnings)))))

(deftest what-does-not-fit-is-dropped-and-said
  (let [dir (tmp "lenient")
        f (io/file dir ".brainyard" "deps.edn")]
    (io/make-parents f)
    (spit f (pr-str {:schema-version 1 :projects {:self {} :a {:kind :sideways :bogus 1 :provides [{:id :x :kind :files}]}}
                     :handoffs [{:from :a}]}))
    (let [r (deps/report dir {:registry []})]
      (is (= {:a {:provides []}} (:projects (:value (deps/read-spec dir)))))
      (is (= #{["projects.self" "self names this project; left out"]
               ["projects.a.kind" "should be one of reference, upstream; got :sideways"]
               ["projects.a.bogus" "unknown key"]
               ["projects.a.provides.0" "files need their :paths; left out"]
               ["handoffs.0" "needs to; left out"]}
             (set (map (juxt :key :message) (:problems r)))))
      (is (every? #(= (.getPath f) (:file %)) (:problems r)))
      (is (some #{"deps.edn projects.a.bogus: unknown key"} (:problems (deps/health dir {:registry []})))
          "and /init's check says it, by the file's name"))
    (spit f "{:projects")
    (is (str/starts-with? (:message (first (:problems (deps/report dir {:registry []})))) "unreadable EDN"))))

(deftest workflows-are-checked-written-and-reported
  ;; byj-dls: several workflows, each a graph of stages playing roles, linked by their own gives
  (let [dir (tmp "workflows")
        js #(clojure.walk/keywordize-keys %)
        spec {"roles" {"lead" {} "design" {} "implement" {} "review" {}}
              "workflows" {"feature" {"title" "Feature" "master" "lead"
                                      "stages" {"design" {} "fix" {"role" "implement" "title" "Fix"} "review" {}}
                                      "links" [{"from" "design" "to" "fix" "gives" [{"kind" "note"}]}
                                               {"from" "fix" "to" "review"}]}
                           "docs" {"stages" {"design" {}}}}}
        refused (fn [m] (deps/write! dir :spec (js m) {:registry []}))]
    (testing "refused: a stage playing no role, a link to no stage, a master that is no role, a cycle"
      (is (str/includes? (:error (refused (assoc-in spec ["workflows" "docs" "stages" "plan"] {})))
                         "workflow docs's stage plan plays plan, which is not a role"))
      (is (str/includes? (:error (refused (update-in spec ["workflows" "feature" "links"] conj {"from" "review" "to" "ship"})))
                         "names ship, which is not a stage"))
      (is (str/includes? (:error (refused (assoc-in spec ["workflows" "docs" "master"] "boss"))) "master boss is not a role"))
      (is (str/includes? (:error (refused (update-in spec ["workflows" "feature" "links"] conj {"from" "review" "to" "design"})))
                         "feature's links go round"))
      (is (= :schema (:stage (refused (assoc-in spec ["workflows" "docs" "stages" "design" "bogus"] 1)))))
      (is (not (.exists (deps/spec-file dir)))))
    (testing "written in a stable order, read back, each link's gives reported"
      (is (:ok (refused spec)))
      (let [text (slurp (deps/spec-file dir))
            w (get-in (edn/read-string text) [:workflows :feature])]
        (is (< (str/index-of text ":roles") (str/index-of text ":workflows")) "after the roles")
        (is (= {:title "Feature" :master :lead :stages {:design {} :fix {:role :implement :title "Fix"} :review {}}
                :links [{:from :design :to :fix :gives [{:kind :note}]} {:from :fix :to :review}]}
               w))
        (let [r (deps/report dir {:registry []})]
          (is (= [["docs" []] ["feature" [["design" "fix" false] ["fix" "review" true]]]]
                 (mapv (fn [x] [(:id x) (mapv (juxt :from :to :ready) (:links x))]) (:workflows r))))
          (is (empty? (filter #(= "error" (:level %)) (:checks r)))))))))

(deftest a-write-is-held-to-the-schema-refuses-a-cycle-and-keeps-the-old-file
  (let [dir (tmp "write")
        other (tmp "other")
        f (io/file dir ".brainyard" "deps.edn")
        spec {"projects" {"other" {"path" other "kind" "upstream"
                                   "provides" [{"id" "api" "kind" "files" "paths" ["a.json"]}]}}
              "roles" {"design" {"title" "설계"} "implement" {"worktree" true "session" "own"}}
              "handoffs" [{"from" "design" "to" "implement" "gives" [{"kind" "bead" "parent" "x"}]}]}
        ;; as `byj deps write` has it from JSON
        js #(clojure.walk/keywordize-keys %)]
    (testing "refused, the file untouched"
      (is (= :schema (:stage (deps/write! dir :spec (js (assoc-in spec ["projects" "other" "kind"] "sideways")) {:registry []}))))
      (is (= :schema (:stage (deps/write! dir :spec (js (assoc-in spec ["roles" "implement" "session"] "elsewhere")) {:registry []})))
          "a role's :session is :upstream or :own (byj-ba5)")
      (is (= :check (:stage (deps/write! dir :spec (js (assoc spec "handoffs" [{"from" "design" "to" "nobody"}])) {:registry []}))))
      (let [r (deps/write! dir :spec (js (assoc spec "handoffs" [{"from" "design" "to" "implement"} {"from" "implement" "to" "design"}])) {:registry []})]
        (is (= :check (:stage r)))
        (is (str/includes? (:error r) "design → implement → design")))
      (is (not (.exists f))))
    (testing "written in a stable order, ids and kinds as keywords, and read back"
      (let [r (deps/write! dir :spec (js spec) {:registry []})
            text (slurp f)]
        (is (= [true (.getPath f) nil] ((juxt :ok :file :snapshot) r)))
        (is (< (str/index-of text ":schema-version") (str/index-of text ":projects")
               (str/index-of text ":roles") (str/index-of text ":handoffs")))
        (is (= {:path other :kind :upstream :provides [{:id :api :kind :files :paths ["a.json"]}]}
               (get-in (edn/read-string text) [:projects :other])))
        (is (= [{:from :design :to :implement :gives [{:kind :bead :parent "x"}]}] (:handoffs (edn/read-string text))))
        (is (= {:design {:title "설계"} :implement {:worktree true :session :own}} (:roles (edn/read-string text)))
            "a role's :session kept, as a keyword (byj-ba5)")))
    (testing "a second write keeps the first as a snapshot"
      (let [before (slurp f)
            r (deps/write! dir :spec (js (assoc-in spec ["roles" "review"] {})) {:registry []})]
        (is (:ok r))
        (is (= before (slurp (:snapshot r))))
        (is (str/includes? (:snapshot r) "/.brainyard/deps-snapshots/"))
        (is (contains? (:roles (edn/read-string (slurp f))) :review))))
    (testing "a cycle through another project's spec is refused"
      (io/make-parents (deps/spec-file other))
      (spit (deps/spec-file other) (pr-str {:projects {:back {:path dir}}}))
      (let [r (deps/write! dir :spec (js spec) {:registry []})]
        (is (= :check (:stage r)))
        (is (str/includes? (:error r) "self → other → self"))))
    (testing "the local file: :at from ISO-8601 to an instant"
      (let [r (deps/write! dir :local (js {"bindings" {"design" ["s-1"]}
                                            "handed" [{"from" "design" "to" "implement" "session" "s-2" "turn" 3
                                                       "at" "2026-11-18T01:02:03Z"}]})
                           {:registry []})
            m (edn/read-string (slurp (deps/local-file dir)))]
        (is (:ok r))
        (is (= {:design ["s-1"]} (:bindings m)))
        (is (= #inst "2026-11-18T01:02:03Z" (:at (first (:handed m)))))
        (is (= :schema (:stage (deps/write! dir :local (js {"handed" [{"from" "a" "to" "b" "at" "yesterday"}]}) {:registry []}))))))))
