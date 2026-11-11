(ns brainyard.version-test
  "Which version this is (RFC-029): the stamp a build embeds, git in a
  checkout, and dev when there is neither."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.main :as main]
            [brainyard.version :as version]))

(def ^:private stamp
  "{:version \"v0.1.0\" :sha \"76225d14d36b\" :sha-short \"76225d1\" :branch \"main\" :commit-time \"2026-09-25T23:02:19+09:00\" :dirty? false :built-at \"2026-09-25T14:13:26Z\"}")

(deftest a-version-comes-from-the-checkout-the-stamp-or-nowhere
  (testing "a binary: no checkout, so its stamp"
    (let [info (version/read-info stamp nil)]
      (is (= "v0.1.0" (:version info)))
      (is (= "v0.1.0 (76225d1, main, 2026-09-25T23:02:19+09:00)" (version/version-long info)))))
  (testing "neither: dev"
    (is (= {:version "dev"} (version/read-info nil nil)))
    (is (= "dev" (version/version-long {:version "dev"}))))
  (testing "an unreadable stamp is no stamp"
    (is (= {:version "dev"} (version/read-info "{:version" nil))))
  (testing "this checkout: git, over a stamp left behind by a build"
    (let [root (version/source-root)
          info (version/read-info stamp root)]
      (is (some? root) "the tests run from a checkout")
      (is (:from-git? info))
      (is (re-find #"^v\d+\.\d+\.\d+" (:version info)) "semver tags, v0.1.0 onwards")
      (is (str/ends-with? (version/version-long info) ", from the checkout)")))))

(deftest the-cli-says-its-version
  (is (true? (:version (main/parse-args ["--version"]))))
  (is (true? (:version (main/parse-args ["-V"]))))
  (is (str/starts-with? (with-out-str (#'main/main* "--version")) "byj v")))

(deftest git-is-asked-only-about-its-own-top
  ;; a checkout vendored inside another repository reported the parent's
  ;; version (byj-gly.232)
  (is (some? (brainyard.version/from-git ".")))
  (is (nil? (brainyard.version/from-git "src")) "a directory below the top is not this repository's version"))
