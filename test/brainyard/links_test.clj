(ns brainyard.links-test
  "Clickable targets (byj-gly.270): what counts as a path or a URL, how a
  wrapped one is recovered without joining rows, and how an editor is told
  where to go — brainyard's `links_test.clj`, on the port's `brainyard.links`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.links :as links]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-links-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(deftest a-target-is-found-under-the-click
  (let [s "see src/a.clj:12:3 and https://x.dev/Foo_(bar)."]
    (testing "a path with its line and column"
      (is (= {:kind :file :path "src/a.clj" :line 12 :col 3 :text "src/a.clj:12:3" :start 4 :end 18}
             (links/detect-at s 6))))
    (testing "a URL keeps its own parens and drops the sentence's full stop"
      (is (= "https://x.dev/Foo_(bar)" (:text (links/detect-at s 30)))))
    (testing "a URL wins where a path would also match"
      (is (= :url (:kind (links/detect-at "https://a.b/c.clj" 12))))))
  (testing "a wrapping paren is not the URL's"
    (is (= "https://x.dev/a" (:text (links/detect-at "(see https://x.dev/a)" 8)))))
  (testing "a bare word is not a path, and a click past the text is nothing"
    (is (nil? (links/detect-at "README here" 2)))
    (is (= "README.md" (:path (links/detect-at "README.md here" 2))))
    (is (nil? (links/detect-at "abc" 9)))))

(deftest a-column-is-a-character-by-its-width
  (let [width #(if (re-matches #"[가-힣]" %) 2 1)]
    (is (= 0 (links/column->index "한a" 0 width)))
    (is (= 0 (links/column->index "한a" 1 width)) "the second cell of a wide character is it too")
    (is (= 1 (links/column->index "한a" 2 width)))
    (is (nil? (links/column->index "한a" 3 width)))))

(deftest a-wrapped-target-is-recovered-from-its-entry-not-from-its-rows
  (let [row-fragment {:kind :url :text "https://x.dev/a/very/lo" :start 0 :end 23}]
    (is (= {:kind :url :text "https://x.dev/a/very/long/path"}
           (links/recover-target row-fragment "go to https://x.dev/a/very/long/path now"))))
  (testing "two lines are never joined into one target"
    ;; https://safe.com ending one row and @evil.com/x starting the next would
    ;; join into a URL that goes to evil.com
    (let [t {:kind :url :text "https://safe.com" :start 0 :end 16}]
      (is (= t (links/recover-target t "https://safe.com\n@evil.com/x")))))
  (testing "a fragment in two candidates is left as it was"
    (let [t {:kind :file :path "src/a" :text "src/a" :start 0 :end 5}]
      (is (= t (links/recover-target t "src/a.clj and src/a.edn")))))
  (testing "a path is widened and parsed again"
    (is (= {:path "src/brainyard/tui.clj" :line 42 :kind :file :text "src/brainyard/tui.clj:42"}
           (links/recover-target {:kind :file :path "src/brain" :text "src/brain" :start 0 :end 9}
                                 "at src/brainyard/tui.clj:42")))))

(deftest a-path-opens-only-when-it-is-a-file
  (let [d (fresh-dir "resolve")]
    (spit (io/file d "a.clj") "x")
    (.mkdirs (io/file d "sub"))
    (is (= (.getCanonicalPath (io/file d "a.clj")) (links/resolve-file "a.clj" d)))
    (is (= (.getCanonicalPath (io/file d "a.clj")) (links/resolve-file (.getPath (io/file d "a.clj")) "/")))
    (is (nil? (links/resolve-file "sub" d)) "a directory is not opened")
    (is (nil? (links/resolve-file "missing.clj" d)))
    (is (nil? (links/resolve-file "" d)))))

(deftest only-http-urls-are-opened
  (is (links/openable-url? "https://x.dev"))
  (is (links/openable-url? "HTTP://x.dev"))
  (is (not (links/openable-url? "file:///etc/passwd")))
  (is (not (links/openable-url? "javascript:alert(1)")))
  (is (not (links/open-url! "file:///etc/passwd")) "and nothing is run for one that is not"))

(deftest an-editor-is-told-where-to-go-in-its-own-convention
  (is (= ["vi" "+12" "/a.clj"] (links/editor-argv "vi" "/a.clj" 12 3)) "vi: +line, no column")
  (is (= ["/usr/bin/nvim" "+12" "/a.clj"] (links/editor-argv "/usr/bin/nvim" "/a.clj" 12 nil)))
  (is (= ["code" "--wait" "--goto" "/a.clj:12:3"] (links/editor-argv "code --wait" "/a.clj" 12 3))
      "VS Code: --goto path:line:col, its own flags kept")
  (is (= ["hx" "/a.clj:12"] (links/editor-argv "hx" "/a.clj" 12 nil)))
  (is (= ["less" "/a.clj"] (links/editor-argv "less" "/a.clj" nil nil)) "no line, just the path")
  (testing "$VISUAL, then $EDITOR, then less"
    (is (= "code --wait" (links/editor {"VISUAL" "code --wait" "EDITOR" "vi"})))
    (is (= "vi" (links/editor {"VISUAL" "" "EDITOR" "vi"})))
    (is (= "less" (links/editor {})))))

(deftest a-mark-goes-to-what-reads-as-a-location
  (let [d (fresh-dir "marks")]
    (spit (io/file d "deps.edn") "{}")
    (.mkdirs (io/file d "src"))
    (spit (io/file d "src" "a.clj") "x")
    (let [s "deps.edn and src/a.clj:3 and src/gone.clj:1 and https://x.dev/y."]
      (is (= (map #(subs s (first %) (second %)) (links/mark-spans s d))
             ["src/a.clj:3" "https://x.dev/y"])
          "a path with a line that resolves, and the URL; not a bare deps.edn, nor a missing file"))))

(deftest a-url-s-second-row-opens-the-url
  ;; the port's: brainyard left a URL's scheme-less second half inert
  ;; (divergence link-open-lifts-brainyard-limits)
  (let [t (links/detect-at "aaaaa/end" 2)]
    (is (= :file (:kind t)) "on its own row it reads as a path")
    (is (= {:kind :url :text "https://x.dev/aaaaa/end"}
           (links/recover-target t "docs: https://x.dev/aaaaa/end")))
    (is (= t (links/recover-target t "https://x.dev/aaaaa/end https://y.dev/aaaaa/end"))
        "and in two URLs, neither")))

(deftest an-edit-waits-in-an-editor-never-the-pager
  ;; byj-gly.327, live: with no $EDITOR the pager opened, and nothing could change
  (is (= ["vi" "/t/m.md"] (links/edit-argv {} "/t/m.md")) "vi, not less")
  (is (= ["nvim" "-u" "NONE" "/t/m.md"] (links/edit-argv {"EDITOR" "nvim -u NONE"} "/t/m.md")))
  (is (= ["hx" "/t/m.md"] (links/edit-argv {"VISUAL" "hx" "EDITOR" "vim"} "/t/m.md")) "VISUAL first")
  (is (= ["/usr/local/bin/code" "--wait" "/t/m.md"] (links/edit-argv {"EDITOR" "/usr/local/bin/code"} "/t/m.md"))
      "a GUI editor is told to wait")
  (is (= ["subl" "-w" "/t/m.md"] (links/edit-argv {"EDITOR" "subl -w"} "/t/m.md")) "and not twice"))
