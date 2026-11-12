;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

;; Where can "the latest version" come from, for a PRIVATE GitHub repo that
;; ships both a binary and a checkout? (byj-gly.181.2, RFC-029)
;;
;;     GH_TOKEN=$(gh auth token) jolt -A:dev -e '(load-file "dev/brainyard/dev/latest_version_spike.clj")'
;;
;; The token comes in through the environment and is never printed. Every
;; source is asked three times; the median time is reported. Measured against
;; brainyard's own public releases (grumatic/brainyard: the same API, the same
;; asset flow a private repo takes WITH a token) and a private repo with no
;; releases (what a private repo answers WITHOUT one).
(require '[clojure.string :as str]
         '[jolt.http-client :as http]
         '[brainyard.json :as json]
         '[brainyard.effect :as fx])

(def public-repo "grumatic/brainyard")
(def private-repo (or (System/getenv "SPIKE_PRIVATE_REPO") "owner/a-private-repo"))
(def token (not-empty (System/getenv "GH_TOKEN")))

(defn- timed [f]
  (let [t0 (System/nanoTime) r (f)]
    [r (quot (- (System/nanoTime) t0) 1000000)]))

(defn- median-of-3 [f]
  (let [runs (repeatedly 3 #(timed f))]
    [(first (last runs)) (nth (sort (map second runs)) 1)]))

(defn- api [path & {:keys [auth? accept]}]
  (http/get (str "https://api.github.com" path)
            {:headers (cond-> {"Accept" (or accept "application/vnd.github+json")
                               "User-Agent" "byj-spike"}
                        (and auth? token) (assoc "Authorization" (str "Bearer " token)))
             :socket-timeout 15000 :conn-timeout 5000 :throw-exceptions false}))

(defn- row [label [r ms] said]
  (println (format "%-58s %5s  %5d ms  %s" label (str (:status r)) ms said)))

(defn- tag-of [r] (some-> (:body r) json/read-str :tag_name))

(println "token present:" (boolean token))
(println)
(println "-- GitHub Releases API: releases/latest")
(let [r (median-of-3 #(api (str "/repos/" public-repo "/releases/latest")))]
  (row (str public-repo ", no token") r
       (str "tag " (tag-of (first r)) ", rate limit "
            (get-in (first r) [:headers "x-ratelimit-remaining"]) "/"
            (get-in (first r) [:headers "x-ratelimit-limit"]))))
(row (str private-repo " (private), no token")
     (median-of-3 #(api (str "/repos/" private-repo "/releases/latest"))) "— a private repo is invisible")
(when token
  (let [r (median-of-3 #(api (str "/repos/" public-repo "/releases/latest") :auth? true))]
    (row (str public-repo ", token") r
         (str "tag " (tag-of (first r)) ", rate limit "
              (get-in (first r) [:headers "x-ratelimit-limit"]))))
  (row (str private-repo " (private), token")
       (median-of-3 #(api (str "/repos/" private-repo "/releases/latest") :auth? true))
       "— no release yet: 404 here too, so 404 cannot say 'no access'")
  (let [r (median-of-3 #(api (str "/repos/" private-repo "/tags?per_page=5") :auth? true))]
    (row (str private-repo " (private), token, /tags") r
         (str (count (some-> (:body (first r)) json/read-str)) " tags visible"))))

(println)
(println "-- an asset, by the API with a token (a private repo's only way)")
(when token
  (let [rel (json/read-str (:body (api (str "/repos/" public-repo "/releases/latest") :auth? true)))
        sums (first (filter #(= "SHA256SUMS" (:name %)) (:assets rel)))
        [r ms] (timed #(api (str "/repos/" public-repo "/releases/assets/" (:id sums))
                            :auth? true :accept "application/octet-stream"))
        body (str (:body r))]
    (row (str "SHA256SUMS of " (:tag_name rel) " (asset " (:id sums) ")") [r ms]
         (str (count body) " bytes, "
              (count (re-seq #"(?m)^[0-9a-f]{64}\s+\S+" body)) " checksum lines"
              (when-not (= 200 (:status r)) (str " — " (subs body 0 (min 160 (count body)))))))))

(println)
(println "-- git ls-remote --tags, over the user's own git credentials")
(doseq [[label url] [[public-repo (str "https://github.com/" public-repo ".git")]
                     [(str private-repo " (private)") (str "git@github.com:" private-repo ".git")]]]
  (let [[r ms] (median-of-3 #(try (fx/sh {:out :string :err :string} "git" "ls-remote" "--tags" "--refs" url)
                                  (catch Throwable e {:exit -1 :err (ex-message e)})))
        tags (->> (str/split-lines (str (:out r)))
                  (keep #(second (re-find #"refs/tags/(v\d+\.\d+\.\d+)$" %))))]
    (println (format "%-58s %5s  %5d ms  %s" label (str "exit " (:exit r)) ms
                     (if (seq tags)
                       (str (count tags) " semver tags, newest " (last (sort-by #(mapv parse-long (re-seq #"\d+" %)) tags)))
                       (str/trim (str (:err r))))))))

(println)
(println "-- gh CLI (handles a private repo's auth and asset download itself)")
(let [[r ms] (median-of-3 #(try (fx/sh {:out :string :err :string} "gh" "release" "view" "-R" public-repo "--json" "tagName" "-q" ".tagName")
                                (catch Throwable e {:exit -1 :err (ex-message e)})))]
  (println (format "%-58s %5s  %5d ms  %s" (str "gh release view " public-repo) (str "exit " (:exit r)) ms (str/trim (str (:out r) (:err r))))))
