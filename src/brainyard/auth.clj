(ns brainyard.auth
  "What `/login` and `/logout` sign in to (RFC-006, byj-gly.311) — brainyard's
  `common/auth.clj` and its login targets (`docs/design/login-auth-methods.md`).
  Mechanism only: which CLIs log in, and with what arguments, is data
  (`resources/brainyard/auth/targets.edn`); the API-key providers are those in
  `llm/providers` that name an `:api-key-env`.

  A target has one method, as in brainyard:

  - `:cli` — another CLI's own login (brainyard's `:cli-delegate`): claude,
    codex, gh, aws. Its status is the CLI's own word, asked without a
    terminal; logging in runs the CLI's login with the terminal handed to it
    (`:hand-terminal`, the front end's seam), since each asks in a browser or
    on the terminal. brainyard only says which command to run — its own
    follow-up 4 was this — so the port goes further (divergence
    `login-runs-the-cli`). With no terminal to hand over, the command is said.
  - `:api-key` — a variable in the environment or a `.env`: its status is
    whether it is set, never its value; logging in is saying where to put it.
    Nothing here writes a key anywhere."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.init :as init]
            [brainyard.json :as json]
            [brainyard.llm :as llm]))

(def cli-targets
  "The CLI targets: `resources/brainyard/auth/targets.edn`."
  (delay (mapv #(assoc % :method :cli)
               (edn/read-string (slurp (io/resource "brainyard/auth/targets.edn"))))))

(defn key-targets
  "One `:api-key` target a provider that reads an API key from the
  environment: `{:id :method :env :label}`."
  []
  (vec (for [[p {:keys [api-key-env]}] (sort-by key llm/providers)
             :when api-key-env]
         {:id (name p) :method :api-key :env api-key-env
          :label (str "the " (name p) " provider — " api-key-env)})))

(defn targets [] (into @cli-targets (key-targets)))

(defn find-target
  "The target named `s` — its id or an alias, any case — or nil."
  [s]
  (let [k (str/lower-case (str/trim (str s)))]
    (some #(when (or (= k (:id %)) (some #{k} (:aliases %))) %) (targets))))

(def status-timeout-ms
  "How long a CLI is given to say whether it is signed in."
  5000)

(defn- profile-args
  "`--profile <p>` when the target reads a profile from the environment and
  one is set (aws: `AWS_PROFILE`)."
  [{:keys [profile-env]} env]
  (when-let [p (some-> profile-env env str str/trim not-empty)] ["--profile" p]))

(defn argv
  "The command line that does `action` (`:login`, `:logout`) for CLI target `t`."
  [t action env]
  (vec (concat [(:command t)] (get t action) (profile-args t env))))

(defn status
  "Where target `t` stands: `:signed-in`, `:not-signed-in`, or for a CLI
  target `:cli-missing`. Never reads out a credential. `opts`: `:env` (a fn
  of a variable's name: the environment and the `.env` files), `:path` (the
  PATH a CLI is looked for on)."
  [t & [{:keys [env path]}]]
  (let [env (or env #(System/getenv %))]
    (case (:method t)
      :api-key (if (str/blank? (str (env (:env t)))) :not-signed-in :signed-in)
      :cli
      (if-let [exe (init/on-path (:command t) (or path (env "PATH") (System/getenv "PATH")))]
        (let [{:keys [argv json-key]} (:status t)
              r (fx/run!! (apply fx/process-task {:out :string :err :string :in ""} exe
                                 (concat argv (profile-args t env)))
                          status-timeout-ms)
              ok (:ok r)]
          (if (and ok (zero? (:exit ok))
                   (or (nil? json-key)
                       (true? (get (try (json/read-str (:out ok)) (catch Throwable _ nil)) json-key))))
            :signed-in
            :not-signed-in))
        :cli-missing))))

(defn- status-word [s]
  (case s :signed-in "signed in" :not-signed-in "not signed in" :cli-missing "not installed" "unknown"))

(defn- install-hint
  "How to install a target's CLI, from /init's requirements, when it is one."
  [t]
  (some (fn [r] (when (= (:command t) (:command r))
                  (first (get (:install r) (init/platform)))))
        @init/requirements))

(defn overview
  "Every target and where it stands, a line each: `/login` with no target."
  [& [opts]]
  (let [ts (targets)
        w (apply max (map (comp count :id) ts))]
    (str "Sign-in targets:\n"
         (str/join "\n" (for [t ts]
                          (str "  " (:id t) (apply str (repeat (- (inc w) (count (:id t))) " "))
                               (format "%-14s" (status-word (status t opts))) (:label t))))
         "\n\n/login <target> signs in   ·   /logout <target> signs out")))

(defn instructions
  "How to do `action` for `t` by hand: what /login says when it cannot do it
  itself."
  [t action env]
  (case (:method t)
    :api-key (if (= :login action)
               (str "Set " (:env t) " — in your environment, or as a line `" (:env t) "=…` in ~/.brainyard/.env "
                    "or the project's .env, which byj reads at its next start. Nothing here writes a key.")
               (str "Remove " (:env t) " from your environment and from any .env that sets it."))
    :cli (str "Run: " (str/join " " (argv t action env)))))

(defn- run-cli!
  "Hand the terminal to `t`'s `action` command and say where it stands after."
  [t action {:keys [hand-terminal env] :as opts}]
  (let [cmd (argv t action env)]
    (hand-terminal cmd nil)
    (let [s (status t opts)]
      (str (:id t) ": " (str/join " " cmd) " ran — now " (status-word s) "."))))

(defn login!
  "`/login <target>`: an API key is said where to put; a CLI target already
  signed in is said so; one that is not has its login run with the terminal
  handed to it — `:hand-terminal` (fn [argv env]) in `opts`, the front end's
  — or, with none, the command is said. A CLI that is not installed is said
  how to install. Returns the words to show."
  [t {:keys [hand-terminal env] :as opts}]
  (let [env (or env #(System/getenv %))
        opts (assoc opts :env env)
        s (status t opts)]
    (cond
      (= :signed-in s) (str "Already signed in to " (:id t) ". /logout " (:id t) " first to sign in as someone else.")
      (= :api-key (:method t)) (instructions t :login env)
      (= :cli-missing s) (str (:command t) " is not installed"
                              (when-let [h (install-hint t)] (str " — " h)) ".")
      hand-terminal (run-cli! t :login opts)
      :else (instructions t :login env))))

(defn logout!
  "`/logout <target>`: a CLI target's logout run with the terminal handed to
  it (some ask which account), or said; an API key is said how to remove."
  [t {:keys [hand-terminal env] :as opts}]
  (let [env (or env #(System/getenv %))
        opts (assoc opts :env env)]
    (cond
      (= :api-key (:method t)) (instructions t :logout env)
      (= :cli-missing (status t opts)) (str (:command t) " is not installed: nothing to sign out of.")
      hand-terminal (run-cli! t :logout opts)
      :else (instructions t :logout env))))
