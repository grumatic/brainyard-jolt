(ns brainyard.cost-test
  "What a call cost (RFC-025). Mostly a test of the two ways a cost goes
  wrong quietly: charging full rate for tokens a cache made cheap, and
  reporting an unpriced model as free."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [brainyard.cost :as cost]
            [brainyard.llm :as llm]
            [brainyard.trajectory :as tr]))

(defn- fresh-dir [n]
  (let [d (io/file "target" n)]
    (when (.isDirectory d) (run! io/delete-file (reverse (file-seq d))))
    (.mkdirs d)
    d))

(def ^:private t (delay (cost/table {})))

;; --- finding the rates --------------------------------------------------------------------

(deftest a-model-is-found-by-its-id-then-by-its-shape
  (testing "exactly, first"
    (is (= 3.0 (:input (cost/rates-for @t :anthropic "claude-sonnet-4-6")))))

  (testing "then without its date stamp, which a vendor appends and a caller passes on"
    (is (= 3.0 (:input (cost/rates-for @t :anthropic "claude-sonnet-4-6-20250929"))))
    (is (= "claude-sonnet-4-5" (cost/strip-date "claude-sonnet-4-5-20250929"))))

  (testing "and for Bedrock, without the region and version it wraps an id in"
    (is (some? (cost/rates-for @t :bedrock "us.anthropic.claude-sonnet-4-20250514-v1:0")))
    (is (= "anthropic.claude-sonnet-4"
           (cost/normalize-bedrock "us.anthropic.claude-sonnet-4-20250514-v1:0")))
    (testing "…which is why the undated id is in the table separately — it
              normalises to claude-sonnet-4, not to the -4-0 alias"
      (is (some? (cost/rates-for @t :bedrock "anthropic.claude-sonnet-4")))))

  (testing "a model nobody priced has no rates, and says so with nil"
    (is (nil? (cost/rates-for @t :anthropic "claude-invented-9")))
    (is (nil? (cost/rates-for @t :openrouter "anthropic/claude-sonnet-4.5")))
    (is (nil? (cost/rates-for @t nil "gpt-4o")))))

;; --- the arithmetic -----------------------------------------------------------------------

(deftest a-cached-token-is-not-charged-as-a-fresh-one
  ;; :input-tokens is the TOTAL input, cache included (llm/anthropic-usage).
  ;; Pricing it whole charges full rate for every token a cache hit just made
  ;; cheap — worst on the runs where caching is working best.
  (let [c (cost/of @t {:input-tokens 10000 :output-tokens 1000
                       :cache {:read-tokens 8000 :write-tokens 1000}}
                   :anthropic "claude-sonnet-4-6")]
    (testing "only the 1000 FRESH tokens pay the input rate"
      (is (= 0.003 (:input-cost c)))
      (is (not= 0.03 (:input-cost c)) "which is what pricing the total would have charged"))
    (is (= 0.0024 (:cache-read-cost c)) "8000 at the read rate")
    (is (= 0.00375 (:cache-write-cost c)) "1000 at the write rate")
    (is (= 0.015 (:output-cost c)))
    (is (< 0.02414 (:total-cost c) 0.02416))
    (is (= :table (:source c))))

  (testing "a call with no cache prices its whole input"
    (is (= 0.03 (:input-cost (cost/of @t {:input-tokens 10000 :output-tokens 0}
                                      :anthropic "claude-sonnet-4-6")))))

  (testing "a model without cache rates costs nothing for cache it never had"
    (let [c (cost/of @t {:input-tokens 1000 :output-tokens 100}
                     :bedrock "meta.llama3-1-8b-instruct")]
      (is (= 0.0 (:cache-read-cost c)))
      (is (pos? (:total-cost c))))))

(deftest what-the-provider-billed-beats-what-a-table-guesses
  ;; the claude CLI reports total_cost_usd: it knows its own caching, its own
  ;; plan and its own discounts, and no table here can improve on that
  (let [c (cost/of @t {:input-tokens 999999 :output-tokens 999999 :cost-usd 0.0421}
                   :claude-code "opus")]
    (is (= 0.0421 (:total-cost c)))
    (is (= :provider (:source c)) "and it says where the number came from")))

;; --- unknown is not free --------------------------------------------------------------------

(deftest an-unpriced-call-costs-nil-not-zero
  ;; brainyard returns an all-zero cost map and names the hazard itself: a model
  ;; that is catalogued but unpriced "reports a cost of 0.0, which reads as free
  ;; rather than as unknown"
  (is (nil? (cost/of @t {:input-tokens 1000 :output-tokens 100} :openrouter "x/y")))
  (is (nil? (cost/of @t {:input-tokens 1000} :anthropic "claude-invented-9")))

  (testing "and a turn that had one says its total is partial"
    (let [usage (-> {}
                    (cost/add (cost/of @t {:input-tokens 1000 :output-tokens 100}
                                       :anthropic "claude-sonnet-4-6"))
                    (cost/add nil)
                    (cost/add nil))]
      (is (= 2 (:unpriced usage)))
      (is (pos? (:cost usage)))
      (is (true? (:partial? (cost/summary usage))))))

  (testing "a turn where everything was priced claims nothing of the sort"
    (let [usage (cost/add {} (cost/of @t {:input-tokens 1000} :anthropic "claude-sonnet-4-6"))]
      (is (nil? (:partial? (cost/summary usage))))))

  (testing "and a turn where nothing could be priced has no cost at all —
            an absent key says unknown, where 0.0 would say free"
    (is (nil? (cost/summary {})))
    (is (= {:partial? true :unpriced 1} (cost/summary (cost/add {} nil))))))

(deftest free-is-an-answer-where-unknown-is-not
  ;; a local model and a fake one cost nothing, and nobody is billing for them
  (is (= {:total-cost 0.0 :source :free} (cost/of @t {:input-tokens 9999} :mock "mock")))
  (is (= {:total-cost 0.0 :source :free} (cost/of @t {:input-tokens 9999} :ollama "gemma4:12b")))

  (testing "so a turn of them reports zero dollars and claims nothing is missing"
    (let [usage (-> {} (cost/add (cost/of @t {:input-tokens 10} :mock "mock"))
                    (cost/add (cost/of @t {:input-tokens 10} :mock "mock")))]
      (is (= 0.0 (:cost usage)))
      (is (nil? (:partial? (cost/summary usage))))
      (is (nil? (:unpriced usage)))))

  (testing "which is the whole distinction: a provider nobody priced is still unknown"
    (is (nil? (cost/of @t {:input-tokens 10} :openrouter "x/y")))))

(deftest the-record-omits-a-cost-it-cannot-state
  (testing "a priced turn carries its dollars"
    (let [r (tr/record {:question "q" :usage {:input-tokens 10 :cost 0.5}})]
      (is (= 0.5 (:cost r)))
      (is (not (contains? r :cost-partial?)))))

  (testing "a partly priced one says so beside the number"
    (let [r (tr/record {:question "q" :usage {:input-tokens 10 :cost 0.5 :unpriced 2}})]
      (is (= 0.5 (:cost r)))
      (is (true? (:cost-partial? r)))))

  (testing "and an unpriced one has no :cost key rather than a zero"
    (is (not (contains? (tr/record {:question "q" :usage {:input-tokens 10}}) :cost)))))

;; --- the table is a file ---------------------------------------------------------------------

(deftest a-project-can-correct-a-price-without-a-rebuild
  ;; a price is not a fact about this program — it changes when a vendor changes
  ;; it, and a rebuild to fix one would be the wrong shape (RFC-001)
  (let [d (fresh-dir "cost-override")
        c {:project-dir (.getPath d)}]
    (.mkdirs (io/file d ".brainyard"))
    (spit (io/file d ".brainyard" "prices.edn")
          (pr-str {:anthropic {"claude-sonnet-4-6" {:input 99.0 :output 1.0}}
                   :madeup {"my-model" {:input 1.0 :output 2.0}}}))
    (let [t (cost/table c)]
      (is (= 99.0 (:input (cost/rates-for t :anthropic "claude-sonnet-4-6"))) "corrected")
      (is (= 1.0 (:input (cost/rates-for t :madeup "my-model"))) "and extended")
      (testing "while the models it said nothing about are untouched"
        (is (= 1.0 (:input (cost/rates-for t :anthropic "claude-haiku-4-5"))))
        (is (some? (cost/rates-for t :openai "gpt-4o")))))))

(deftest a-broken-price-file-costs-a-number-not-a-turn
  (let [d (fresh-dir "cost-broken")]
    (.mkdirs (io/file d ".brainyard"))
    (spit (io/file d ".brainyard" "prices.edn") "{:anthropic {oops")
    (let [t (cost/table {:project-dir (.getPath d)})]
      (is (= 3.0 (:input (cost/rates-for t :anthropic "claude-sonnet-4-6")))
          "the bundled table still stands"))))

(deftest every-bundled-price-is-a-usable-rate
  (doseq [[provider models] @cost/bundled
          [model rates] models]
    (testing (str provider "/" model)
      (is (pos? (:input rates)))
      (is (pos? (:output rates)))
      (is (every? #(or (nil? %) (pos? %)) [(:cache-read rates) (:cache-write rates)])
          "a cache rate is present and positive, or absent — never zero, which
           would be indistinguishable from free caching")
      (when-let [lc (:long-context rates)]
        (is (pos? (:over lc)))
        (is (every? pos? ((juxt :input :output) lc)) "a long-context tier prices both ways")))))

(deftest a-bedrock-global-endpoint-is-priced-apart-from-a-regional-one
  ;; byj-ewq: AWS bills Claude 4.5 and later 10% more through a regional
  ;; endpoint than through `global.`
  (let [t @cost/bundled
        in #(:input (cost/rates-for t :bedrock %))]
    (is (= 2.00 (in "global.anthropic.claude-sonnet-5-v1:0")) "global: Anthropic's own price")
    (is (= 2.20 (in "us.anthropic.claude-sonnet-5-v1:0")) "a geographic profile: regional")
    (is (= 2.20 (in "anthropic.claude-sonnet-5-v1:0")) "in-region")
    (is (= 3.00 (in "global.anthropic.claude-sonnet-4-20250514-v1:0"))
        "a model with one price: a global id falls back to it")))

(deftest a-long-prompt-is-priced-at-its-tier
  ;; byj-ewq: Claude Haiku 5.5 bills a call over 100,000 input tokens — cache
  ;; included — at its long-context rates
  (let [t @cost/bundled
        cost #(:total-cost (cost/of t {:input-tokens % :output-tokens 0} :anthropic "claude-haiku-5-5"))]
    (is (< (Math/abs (- 0.01 (cost 100000))) 1e-9) "at the line: the standard $0.10/MTok")
    (is (< (Math/abs (- 0.0500005 (cost 100001))) 1e-9) "over it: $0.50/MTok, the whole call")
    (is (< (Math/abs (- 0.00500005 (:total-cost (cost/of t {:input-tokens 100001 :output-tokens 0 :cache {:read-tokens 100001}}
                                                    :anthropic "claude-haiku-5-5"))))
           1e-9)
        "cache reads count toward the length, and are priced at the tier's read rate")
    (is (nil? (:long-context (cost/rates-at {:input 1 :long-context {:over 5 :input 9}} 3))) "the tier is not a rate")))

(deftest every-curated-claude-model-is-priced
  ;; byj-gly.323: Haiku 5.5 was priced (byj-ewq) but not offered by name, and
  ;; the 5.5 models were neither — a model /model lists must have a price
  (doseq [{:keys [provider model]} @llm/curated-models
          :when (and (#{:anthropic :bedrock} provider) (re-find #"claude" model))]
    (is (some? (cost/rates-for @cost/bundled provider model)) (str (name provider) "/" model)))
  (testing "a bare id names its curated entry"
    (is (= "anthropic/claude-haiku-5-5" (llm/model-label "claude-haiku-5-5")))
    (is (= "anthropic/claude-opus-5-5" (llm/model-label "claude-opus-5-5")))))

