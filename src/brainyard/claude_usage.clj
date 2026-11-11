(ns brainyard.claude-usage
  "Claude Code's login and its subscription's usage windows — what `/doctor`
  and `/usage` say of the account agent$claude, the claude-code provider and
  the simulation spend (byj-gly.324). Ported from brainyard-playground-apps'
  `apps/desktop/src/main/claude-usage.ts`.

  THE ONE CALL TO A NON-PUBLIC ENDPOINT. This reads Claude Code's own OAuth
  access token — read-only: the credentials file, else the keychain — and
  sends it to `api.anthropic.com/api/oauth/usage`, the endpoint the CLI's own
  `/usage` reads, and nowhere else. It is never written down, logged or
  refreshed: the endpoint answers for a locally expired token, and writing a
  rotated refresh token back would corrupt Claude Code's credentials, of which
  this is only a reader. Not a public interface, so it is read defensively:
  any failure is `unknown` with its reason, never a number (decided with the
  person, 2026-10-09).

  The endpoint 429s when polled hard, so a snapshot is kept five minutes, and
  a Retry-After is honoured even by a forced refresh. A failed fetch keeps the
  last good snapshot."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [jolt.http-client :as http]))

(def usage-url "https://api.anthropic.com/api/oauth/usage")
(def beta-header "oauth-2025-04-20")
;; the endpoint keys off the CLI's user agent; a generic one is refused
(def user-agent "claude-code/2.1.0")
(def fetch-timeout-ms 10000)
(def keychain-timeout-ms 3000)
(def ttl-ms (* 5 60 1000))

;; --- the credentials, read the way Claude Code looks them up --------------------------------

(defn config-dir
  "Claude Code's configuration directory: `CLAUDE_CONFIG_DIR`, else `~/.claude`."
  [env]
  (or (not-empty (env "CLAUDE_CONFIG_DIR")) (str (System/getProperty "user.home") "/.claude")))

(defn- sha256-hex [s]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8")))))

(defn keychain-services
  "The keychain items Claude Code keeps its credentials in, newest first: one
  scoped by a hash of the configuration directory, then the older unsuffixed one."
  [dir]
  [(str "Claude Code-credentials-" (subs (sha256-hex dir) 0 8)) "Claude Code-credentials"])

(defn read-keychain
  "macOS: a generic password's secret, or nil — not found, not macOS, or no
  answer in `keychain-timeout-ms` (a keychain prompt nobody answers)."
  [service]
  (when (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
    (let [r (fx/run!! (fx/process-task {:out :string :err :string :in ""} "/usr/bin/security"
                                       "find-generic-password" "-s" service "-a" (System/getProperty "user.name") "-w")
                      keychain-timeout-ms)]
      (when (zero? (get-in r [:ok :exit] 1)) (not-empty (str/trim (str (get-in r [:ok :out]))))))))

(defn parse-blob
  "Claude Code's credentials JSON as `{:access-token :expires-at
  :refresh-expires-at :plan}`, or nil when it holds no access token."
  [raw]
  (let [o (:claudeAiOauth (try (json/read-str (str raw)) (catch Throwable _ nil)))
        num #(when (and (number? %) (pos? %)) (long %))]
    (when (and (map? o) (string? (:accessToken o)) (seq (:accessToken o)))
      {:access-token (:accessToken o)
       :expires-at (num (:expiresAt o))
       :refresh-expires-at (num (:refreshTokenExpiresAt o))
       :plan (when (string? (:subscriptionType o)) (:subscriptionType o))})))

(defn read-oauth
  "The credentials, in Claude Code's own order: the credentials file (no
  keychain prompt), then the scoped keychain item, then the legacy one. nil:
  not signed in on this machine. `read-secret` is the keychain seam."
  [{:keys [env read-secret] :or {env #(System/getenv %) read-secret read-keychain}}]
  (let [dir (config-dir env)
        f (io/file dir ".credentials.json")]
    (or (when (.isFile f) (parse-blob (try (slurp f) (catch Throwable _ nil))))
        (some #(some-> (read-secret %) parse-blob) (keychain-services dir)))))

(defn profile
  "The account's display fields, from `~/.claude.json`: `{:email :organization}`."
  [& [home]]
  (let [a (:oauthAccount (try (json/read-str (slurp (io/file (or home (System/getProperty "user.home")) ".claude.json")))
                              (catch Throwable _ nil)))]
    {:email (when (string? (:emailAddress a)) (:emailAddress a))
     :organization (when (string? (:organizationName a)) (:organizationName a))}))

;; --- the endpoint's windows -------------------------------------------------------------------

(defn- percent-of
  "A window's use, 0–100: ALREADY a percentage — `utilization 1.0` is one
  percent. The endpoint spells it three ways."
  [w]
  (let [raw (some #(get w %) [:used_percentage :utilization :percent])]
    (when (and (number? raw) (not (Double/isNaN (double raw))))
      (max 0 (min 100 (Math/round (double raw)))))))

(defn- reset-of [w]
  (let [v (:resets_at w)]
    (cond (and (string? v) (not (str/blank? v))) v
          ;; epoch seconds unless too big to be: 1e10 seconds is the year 2286
          (and (number? v) (pos? v)) (str (java.time.Instant/ofEpochMilli (long (if (> v 1e10) v (* v 1000)))))
          :else nil)))

(defn- label-of-key [k]
  (let [base (-> (name k) (str/replace #"^(seven_day|five_hour)_" "") (str/replace "_" " "))]
    (str (str/upper-case (subs base 0 (min 1 (count base)))) (subs base (min 1 (count base))))))

(defn limits
  "The endpoint's answer as rows `{:label :percent :resets-at}`: the session
  (five-hour) and weekly windows by the names Claude Code gives them, then
  every other window taken BY SHAPE — an object with a percentage — since
  which model buckets exist depends on the account and the season. A null
  window is absent, not zero, and is left out: `0% used` for a bucket the
  account does not have is a lie that reads as good news."
  [body]
  (if-not (map? body)
    []
    (let [row (fn [w label] (when (map? w)
                              (when-let [p (percent-of w)] {:label label :percent p :resets-at (reset-of w)})))]
      (vec (concat
            (keep identity [(row (:five_hour body) "Session") (row (:seven_day body) "Weekly")])
            (for [[k v] body
                  :when (and (map? v) (not (#{:five_hour :seven_day :extra_usage :spend :limits} k)))
                  :let [r (row v (or (let [n (get-in v [:scope :model :display_name])] (when (string? n) n))
                                     (label-of-key k)))]
                  :when r]
              r))))))

;; --- the snapshot -----------------------------------------------------------------------------

(defonce ^:private state (atom {:cached nil :retry-after 0}))

(defn cached
  "The last snapshot, without a call: what a status line reads, which must
  never wait on the network. nil before the first."
  []
  (:cached @state))

(defn- retry-after-ms [h]
  (let [s (some-> h str str/trim)
        n (when s (try (Long/parseLong s) (catch Throwable _ nil)))]
    (cond n (min (* 1000 n) (* 24 3600 1000))
          :else 60000)))

(defn- get-usage
  "The endpoint's answer: `{:status :headers :body}`."
  [token]
  (http/get usage-url {:headers {"Authorization" (str "Bearer " token)
                                 "anthropic-beta" beta-header
                                 "User-Agent" user-agent}
                       :socket-timeout fetch-timeout-ms :conn-timeout fetch-timeout-ms
                       :throw-exceptions false}))

(defn usage
  "The account and its windows: `{:logged-in? :account {:email :organization
  :plan :expires-at :refresh-expires-at} :limits [..] :fetched-at :error}`.
  Blocks on the network (up to `fetch-timeout-ms`) when the snapshot is
  older than `ttl-ms`, or with `:force?`; never inside a Retry-After. A
  caller on a fiber runs it with `fx/task-of`. Seams: `:read-oauth`, `:fetch`
  (the HTTP call), `:now`, `:home`."
  [& [{:keys [force? now fetch] :as opts
       :or {now #(System/currentTimeMillis) fetch get-usage}}]]
  (let [blob ((or (:read-oauth opts) read-oauth) opts)
        p (profile (:home opts))
        account (when blob (merge p (select-keys blob [:plan :expires-at :refresh-expires-at])))
        {:keys [cached retry-after]} @state
        t (now)]
    (cond
      (nil? blob)
      (:cached (swap! state assoc :cached {:logged-in? false :account nil :limits [] :fetched-at nil
                                           :error "not signed in to Claude Code"}))

      (and cached (seq (:limits cached)) (:fetched-at cached) (< (- t (:fetched-at cached)) ttl-ms) (not force?))
      (assoc cached :account account)

      (< t retry-after)
      (assoc (or cached {:limits [] :fetched-at nil}) :logged-in? true :account account
             :error (when-not (seq (:limits cached)) "rate-limited by the usage endpoint — try again shortly"))

      :else
      (let [r (try (fetch (:access-token blob)) (catch Throwable e {:error (ex-message e)}))
            keep-last (fn [why] (assoc (or cached {:limits [] :fetched-at nil}) :logged-in? true :account account :error why))]
        (cond
          (:error r) (keep-last (str "usage endpoint unreachable: " (:error r)))
          (= 429 (:status r))
          (do (swap! state assoc :retry-after (+ t (retry-after-ms (get-in r [:headers "retry-after"]))))
              (keep-last "rate-limited by the usage endpoint — try again shortly"))
          ;; a 401 is a token really dead, not one merely past :expires-at —
          ;; the endpoint takes a locally expired one, which is why it is never refreshed here
          (= 401 (:status r)) (keep-last "token rejected — run `claude auth login` (/login claude)")
          (not (<= 200 (:status r 0) 299)) (keep-last (str "usage endpoint HTTP " (:status r)))
          :else
          (let [ls (limits (try (json/read-str (str (:body r))) (catch Throwable _ nil)))]
            (:cached (swap! state assoc :cached {:logged-in? true :account account :limits ls :fetched-at t
                                                 :error (when (empty? ls) "no limits reported")}))))))))

(defn reset!*
  "Forget the snapshot and any Retry-After — for a test."
  []
  (reset! state {:cached nil :retry-after 0}))

;; --- as text ----------------------------------------------------------------------------------

(defn bar
  "`percent` as a 20-cell bar."
  [percent]
  (let [n (int (Math/round (/ (double percent) 5.0)))]
    (str (apply str (repeat n "█")) (apply str (repeat (- 20 n) "░")))))

(defn- when-text [iso]
  (when iso
    (try (let [i (java.time.Instant/parse iso)
               mins (quot (- (.toEpochMilli i) (System/currentTimeMillis)) 60000)]
           (cond (neg? mins) "resetting"
                 (< mins 60) (str "resets in " mins " min")
                 (< mins (* 48 60)) (str "resets in " (quot mins 60) " h " (mod mins 60) " min")
                 :else (str "resets in " (quot mins 1440) " d")))
         (catch Throwable _ nil))))

(defn limit-lines
  "`snapshot`'s windows, a line each, `label  bar  n%  resets in …`; or what
  is unknown, and why."
  [{:keys [logged-in? limits error fetched-at]}]
  (cond
    (not logged-in?) [(str "  " (or error "not signed in to Claude Code"))]
    (empty? limits) [(str "  unknown — " (or error "no limits reported"))]
    :else
    (let [w (apply max (map (comp count :label) limits))]
      (concat
       (for [{:keys [label percent resets-at]} limits]
         (str "  " label (apply str (repeat (- w (count label)) " ")) "  " (bar percent) " " (format "%3d%%" percent)
              (some->> (when-text resets-at) (str "  "))))
       (when error [(str "  (" error "; showing the last reading"
                         (when fetched-at (str ", " (quot (- (System/currentTimeMillis) fetched-at) 60000) " min old")) ")")])))))

(defn warning
  "A status-line word when a window of the last snapshot is at 90% or more —
  `Weekly 93%` — else nil. Reads the cache only."
  []
  (when-let [hot (seq (filter #(>= (:percent %) 90) (:limits (cached))))]
    (let [{:keys [label percent]} (apply max-key :percent hot)]
      (str label " " percent "%"))))
