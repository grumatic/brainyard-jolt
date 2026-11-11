(ns brainyard.links
  "Clickable targets in the transcript: file locations (`path:line:col`) and
  http(s) URLs — brainyard's `ai.brainyard.agent-tui.links` (byj-gly.270).

  DETECTION IS DONE AT CLICK TIME, from the text on the row clicked, rather than
  registered by whoever wrote the text. It works on every entry already said —
  answers, tool results, tracebacks — with nothing at the places that say them,
  survives a resize because it re-runs against what is on screen now, and
  cannot go stale against the text being looked at.

  What it costs is precision, and the file case pays with an EXISTENCE CHECK
  rather than a stricter pattern: `path-candidate-re` is deliberately loose, and
  a candidate that does not resolve to a real file does nothing. A pattern tight
  enough to avoid every false positive would miss real paths; a false positive
  costs nothing.

  Detection runs on ONE row, so a target the renderer wrapped is only a fragment
  there. `recover-target` widens it against the same entry laid out where nothing
  wraps — never by joining the rows on screen, which would let whoever wrote the
  text choose the seam and forge a target.

  Mechanism only: detection and resolution are pure, `editor-argv` builds the
  command, and `open-url!` hands a URL to the OS. Suspending the screen for an
  editor, and saying what was opened, is the renderer's (`brainyard.tui`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]))

;; --- detection ---------------------------------------------------------------------

(def url-re
  "http(s) URLs. The trailing class excludes whitespace, quotes, and the
  bracket and brace pairs that wrap a URL in prose. Parentheses are NOT
  excluded — `…/wiki/Foo_(bar)` is a real URL — and a wrapping `(see …)` is
  handled after the match by `strip-url-junk`, which can count them."
  #"https?://[^\s\"'`<>\[\]{}|\\^]+")

(def ^:private url-trailing-junk #"[.,;:!?]+$")

(def path-candidate-re
  "A maximal run of path-ish characters, including the `:` of a `:line:col`
  suffix. Loose on purpose: whether the file exists decides what a click does."
  #"[A-Za-z0-9._/~+@:-]+")

(defn- strip-url-junk
  "A URL match without the sentence punctuation after it, or an unbalanced
  closing paren: `(see https://x.com/a)` does not open `…/a)`."
  [u]
  (let [u (str/replace u url-trailing-junk "")]
    (if (and (str/ends-with? u ")")
             (< (count (re-seq #"\(" u)) (count (re-seq #"\)" u))))
      (subs u 0 (dec (count u)))
      u)))

(defn- parse-path-candidate
  "A candidate as `{:path :line :col}`, its `:LINE` and `:LINE:COL` peeled off;
  nil when what is left is not path-shaped. The path must hold a `/` or a `.`,
  or every bare word in prose would be a stat(): `README` is not a path,
  `./README` and `README.md` are."
  [token]
  (let [[_ path line col] (re-matches #"(.*?)(?::(\d+))?(?::(\d+))?" token)]
    (when (and (seq path)
               (or (str/includes? path "/") (str/includes? path "."))
               (not (str/includes? path "://")))
      (cond-> {:path path}
        line (assoc :line (parse-long line))
        col (assoc :col (parse-long col))))))

(defn- match-spans
  "Every `[start end text]` of `re` in `s`."
  [re s]
  (let [m (re-matcher re s)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc [(.start m) (.end m) (.group m)]))
        acc))))

(defn detect-at
  "The target covering index `idx` of the plain line `s`, or nil:

    {:kind :url  :text \"https://…\" :start i :end j}
    {:kind :file :path \"src/a.clj\" :line 42 :col 7 :text \"…\" :start i :end j}

  A URL wins over a path wherever both match: a URL is also a perfectly good
  path candidate, and resolving it as one would be nonsense."
  [s idx]
  (when (and s idx (nat-int? idx) (< idx (count s)))
    (let [in? (fn [[st en _]] (and (>= idx st) (< idx en)))]
      (or (when-let [[st _ raw] (first (filter in? (match-spans url-re s)))]
            (let [text (strip-url-junk raw)]
              ;; the trim can pull the end back past the click: a click on the
              ;; trailing `)` of `(https://x)` is a click on the paren
              (when (< idx (+ st (count text)))
                {:kind :url :text text :start st :end (+ st (count text))})))
          (when-let [[st en raw] (first (filter in? (match-spans path-candidate-re s)))]
            (when-let [parsed (parse-path-candidate raw)]
              (assoc parsed :kind :file :text raw :start st :end en)))))))

(defn column->index
  "The index into `s` of the character drawn at display column `col` (0-based),
  where `width` says how many cells a character takes; nil past the text."
  [s col width]
  (loop [i 0 at 0]
    (when (< i (count s))
      (let [w (max 1 (width (subs s i (inc i))))]
        (if (< col (+ at w)) i (recur (inc i) (+ at w)))))))

;; --- recovering a target the wrap split ------------------------------------------

(declare recover-within)

(defn recover-target
  "Widen `t`, found on ONE row, against `unwrapped` — the same entry laid out at
  a width where nothing wraps. `t` as it was when nothing better is found.

  NOT ROW CONCATENATION, and the difference is a security property: joining
  row N's tail to row N+1's head lets whoever wrote the text choose the seam,
  and `https://safe.com` ending one row and `@evil.com/x` starting the next join
  into a URL that goes to evil.com while both halves read as harmless. Here the
  whole comes from the renderer's own layout, and the fragment must appear
  inside it. A fragment found in two different candidates gives `t`: there is
  no knowing which one was looked at."
  [t unwrapped]
  (if (or (nil? t) (str/blank? unwrapped))
    t
    (let [frag (:text t)
          ;; a URL's second row has no scheme and reads as a path: when exactly
          ;; one URL in the entry holds it, it is that URL (brainyard left the
          ;; second half inert — divergence link-open-lifts-brainyard-limits)
          in-urls (when (= :file (:kind t))
                    (->> (match-spans url-re unwrapped)
                         (map (fn [[_ _ raw]] (strip-url-junk raw)))
                         (filter #(str/includes? % frag))
                         distinct))]
      (if (= 1 (count in-urls))
        {:kind :url :text (first in-urls)}
        (recover-within t unwrapped)))))

(defn- recover-within
  "`recover-target` within the target's own kind: the one candidate in
  `unwrapped` that holds the fragment and is longer than it."
  [t unwrapped]
  (let [url? (= :url (:kind t))
        frag (:text t)
        hits (->> (match-spans (if url? url-re path-candidate-re) unwrapped)
                  (map (fn [[_ _ raw]] (if url? (strip-url-junk raw) raw)))
                  (filter #(str/includes? % frag))
                  distinct)]
    (if (and (= 1 (count hits)) (> (count (first hits)) (count frag)))
      (let [full (first hits)]
        (if url?
          {:kind :url :text full}
          (or (some-> (parse-path-candidate full) (assoc :kind :file :text full)) t)))
      t)))

;; --- resolution --------------------------------------------------------------------

(defn- expand-home [p]
  (if (or (= p "~") (str/starts-with? p "~/"))
    (str (System/getProperty "user.home") (subs p 1))
    p))

(defn resolve-file
  "The absolute path of `path` when it names an EXISTING regular file, else nil.
  A relative path resolves against `base-dir`. A directory is nil: `.` and `..`
  are the loose pattern's commonest false positives, and an editor on a
  directory is rarely what a click meant."
  [path base-dir]
  (when (seq path)
    (try
      (let [p (expand-home path)
            f (let [f0 (io/file p)] (if (.isAbsolute f0) f0 (io/file (or base-dir ".") p)))]
        (when (.isFile f) (.getCanonicalPath f)))
      (catch Throwable _ nil))))

;; --- actions -----------------------------------------------------------------------

(defn openable-url?
  "Only http(s): the text is the model's, so the scheme is an allowlist —
  `file:`, `javascript:` and every handler a machine has registered stay out
  of reach."
  [u]
  (boolean (and u (re-matches #"(?i)https?://.+" u))))

(defn- mac? [] (str/starts-with? (str (System/getProperty "os.name")) "Mac"))

(defn open-url!
  "Hand `url` to the OS opener; true when it ran. argv, never a shell string:
  the URL is model-authored, and `sh -c` would make its metacharacters
  commands."
  [url]
  (when (openable-url? url)
    (try
      (fx/sh {:out :string :err :string} (if (mac?) "open" "xdg-open") url)
      true
      (catch Throwable _ false))))

(defn editor
  "The editor a file opens in: `$VISUAL`, then `$EDITOR`, then less. brainyard
  reads `$EDITOR` alone (divergence `link-open-lifts-brainyard-limits`)."
  [env]
  (or (not-empty (get env "VISUAL")) (not-empty (get env "EDITOR")) "less"))

(defn editor-argv
  "The command that opens `path` at `line` and `col` in `editor` (a command
  line: its first word is the program, the rest flags). Three conventions, and
  no telling which an unknown editor speaks: VS Code and its forks take
  `--goto path:line:col`, Sublime and Helix `path:line:col`, the vi, emacs,
  nano and less family `+line` before the path — the oldest, and what an
  unknown editor gets. brainyard's `editor-argv-suffix`, with the column where
  the convention has one (vi and less take none)."
  [editor path line col]
  (let [words (str/split (str/trim editor) #"\s+")
        base (str/replace (first words) #".*/" "")
        at (str path ":" line (when col (str ":" col)))]
    (into words
          (cond
            (nil? line) [path]
            (#{"code" "code-insiders" "codium" "cursor" "windsurf"} base) ["--goto" at]
            (#{"subl" "sublime_text" "hx" "helix"} base) [at]
            :else [(str "+" line) path]))))

(defn edit-argv
  "The command that EDITS `path` and returns only once the person is done —
  what `/memory edit` stores the result of (byj-gly.327). Not `editor`'s
  fallback: less shows a file and cannot change it, so with neither
  `$VISUAL` nor `$EDITOR` set it is vi. A GUI editor returns at once unless
  told to wait, and the file would be read back unchanged, so VS Code and its
  forks and Sublime get `--wait`."
  [env path]
  (let [editor (or (not-empty (get env "VISUAL")) (not-empty (get env "EDITOR")) "vi")
        words (str/split (str/trim editor) #"\s+")
        base (str/replace (first words) #".*/" "")
        waits? (some #{"--wait" "-w"} words)]
    (cond-> words
      (and (not waits?) (#{"code" "code-insiders" "codium" "cursor" "windsurf" "subl" "sublime_text"} base))
      (conj "--wait")
      :always (conj path))))

;; --- marking what is clickable -----------------------------------------------------

(defn- worth-marking?
  "Whether a resolvable path earns an underline. Marking every one is too loud:
  a bare `deps.edn` mid-sentence is being mentioned, not offered. So the mark
  goes to what reads as a LOCATION — a `:line` suffix, or an absolute path. The
  mark is a hint; a click on an unmarked `deps.edn` still opens it."
  [{:keys [path line]}]
  (boolean (or line (str/starts-with? path "/") (str/starts-with? path "~"))))

(defn mark-spans
  "`[[start end] …]` over the plain line `s` for every target worth marking, in
  order and never overlapping: every URL, and every path `worth-marking?` that
  resolves against `base-dir` — a mark on a path that does not resolve would
  promise a click that does nothing."
  [s base-dir]
  (let [urls (map (fn [[st _ raw]] [st (+ st (count (strip-url-junk raw)))]) (match-spans url-re s))
        in-url? (fn [i] (some (fn [[a b]] (and (>= i a) (< i b))) urls))
        files (keep (fn [[st en raw]]
                      (when-not (in-url? st)
                        (when-let [p (parse-path-candidate raw)]
                          (when (and (worth-marking? p) (resolve-file (:path p) base-dir))
                            [st en]))))
                    (match-spans path-candidate-re s))]
    (vec (sort-by first (concat urls files)))))
