(ns brainyard.clipboard
  "Put text on the user's clipboard from inside the full-screen renderer —
  brainyard's `agent-tui.clipboard/copy!`, ported.

  Which mechanism is right depends on where the user's clipboard IS:

    tmux    `tmux load-buffer -w -` fills tmux's buffer and has tmux forward an
            OSC 52 to the real terminal itself, which is more reliable than
            relaying ours (tmux only passes one on with `set-clipboard` on).
    OSC 52  the terminal does the write, so the text reaches the machine the
            user sits at: the only thing that works over ssh. Unverifiable, and
            ignored by Terminal.app, GNOME Terminal and Konsole.
    native  `pbcopy` / `wl-copy` / `xclip` / `xsel` / `clip.exe`: verifiable,
            but it writes the clipboard of the host this process runs on, which
            over ssh is the wrong machine.

  `copy!` picks in that order of preference and says which one ran, so a
  caller can say \"copied\" when that is true and \"sent\" when it is a hope."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [jolt.process :as p]))

(def osc52-max-source-bytes
  "The most a single OSC 52 can carry. xterm caps a control string at 74994
  bytes of BASE64, and a terminal past its cap truncates silently — a partial
  copy that looks whole — so a copy past it is refused rather than corrupted.
  Base64 pads to whole 4-byte groups, hence the floor."
  (* 3 (quot 74994 4)))

(defn- env [k] (System/getenv k))

(defn- remote? [] (boolean (or (env "SSH_TTY") (env "SSH_CONNECTION") (env "SSH_CLIENT"))))

(defn- tmux? [] (boolean (env "TMUX")))

(defn- on-path? [cmd]
  (boolean
   (when-let [path (env "PATH")]
     (some #(let [f (io/file % cmd)] (and (.isFile f) (.canExecute f)))
           (str/split path #":")))))

(defn- native-argv
  "The platform's clipboard-writing command, or nil. The display server in use
  wins: a Wayland session with XWayland has both wl-copy and xclip, and only
  wl-copy reaches the compositor the user is looking at."
  []
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond
      (str/includes? os "mac") (when (on-path? "pbcopy") ["pbcopy"])
      (str/includes? os "win") (when (on-path? "clip.exe") ["clip.exe"])
      (and (env "WSL_DISTRO_NAME") (on-path? "clip.exe")) ["clip.exe"]
      (and (env "WAYLAND_DISPLAY") (on-path? "wl-copy")) ["wl-copy"]
      (and (env "DISPLAY") (on-path? "xclip")) ["xclip" "-selection" "clipboard"]
      (and (env "DISPLAY") (on-path? "xsel")) ["xsel" "--input" "--clipboard"]
      :else nil)))

(defn- pipe!
  "Run `argv` with `text` on its stdin; true on a clean exit. Bounded: a helper
  that hangs — a stale X11 connection is the classic — must not wedge the
  loop thread the renderer copies from."
  [argv text]
  (try
    (let [proc (apply fx/start {:in text :out :string :err :string} argv)]
      (let [r (deref proc 3000 ::timeout)]
        (if (= ::timeout r)
          (do (p/destroy-tree proc) false)
          (zero? (:exit r)))))
    (catch Throwable _ false)))

(defn- paste-argv
  "The platform's clipboard-reading command, or nil — `native-argv`'s other
  half, in the same order: the display server in use wins. Under tmux with no
  native one, tmux's own buffer, which `copy!` fills."
  []
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond
      (str/includes? os "mac") (when (on-path? "pbpaste") ["pbpaste"])
      (and (env "WAYLAND_DISPLAY") (on-path? "wl-paste")) ["wl-paste" "--no-newline"]
      (and (env "DISPLAY") (on-path? "xclip")) ["xclip" "-selection" "clipboard" "-o"]
      (and (env "DISPLAY") (on-path? "xsel")) ["xsel" "--output" "--clipboard"]
      (and (or (str/includes? os "win") (env "WSL_DISTRO_NAME")) (on-path? "powershell.exe"))
      ["powershell.exe" "-NoProfile" "-Command" "Get-Clipboard"]
      (and (tmux?) (on-path? "tmux")) ["tmux" "save-buffer" "-"]
      :else nil)))

(defn read-text
  "The user's clipboard as text, for ctrl+v: `{:text s}`, or `{:detail why}`
  where it cannot be read. A terminal's own paste (cmd+v) arrives as a
  bracketed paste and needs none of this. Over ssh it reads this host's
  clipboard, which is the wrong one — OSC 52 reads are rarely allowed."
  []
  (if-let [argv (paste-argv)]
    (try
      (let [proc (apply fx/start {:out :string :err :string} argv)
            r (deref proc 3000 ::timeout)]
        (cond (= ::timeout r) (do (p/destroy-tree proc) {:detail (str (first argv) " did not answer")})
              (zero? (:exit r)) {:text (str (:out r))}
              :else {:detail (str (first argv) " failed")}))
      (catch Throwable e {:detail (ex-message e)}))
    {:detail "no clipboard command here (pbpaste, wl-paste, xclip, xsel)"}))

(defn osc52
  "The OSC 52 sequence that asks the terminal to set its clipboard to `text`."
  [text]
  (str "\033]52;c;" (.encodeToString (java.util.Base64/getEncoder) (.getBytes (str text) "UTF-8")) "\007"))

(defn- write-osc52! [text]
  (try (print (osc52 text)) (flush) true (catch Throwable _ false)))

(defn copy!
  "Copy `text` to the user's clipboard: `{:ok? :via :bytes :verified? :detail}`.
  `:via` is what ran — `:tmux`, `:tmux-buffer` (a tmux without `-w`: its own
  buffer only), `:osc52` or the native command. `:verified?` false means it
  cannot confirm the text reached the system clipboard: OSC 52 always."
  [text]
  (let [text (str text)
        n (alength (.getBytes text "UTF-8"))
        done (fn [via verified?] {:ok? true :via via :bytes n :verified? verified?})
        ;; OSC 52's size limit is OSC 52's: pbcopy, xclip and tmux take a
        ;; selection of any size (byj-gly.213)
        too-big {:ok? false :via :osc52 :bytes n :verified? false
                 :detail (str "too large for the terminal's clipboard (" n " bytes; the limit is " osc52-max-source-bytes ")")}
        osc52! #(cond (> n osc52-max-source-bytes) too-big
                      (write-osc52! text) (done :osc52 false)
                      :else {:ok? false :via :osc52 :bytes n :verified? false :detail "could not write to the terminal"})]
    (cond
      (str/blank? text) {:ok? false :via nil :bytes 0 :verified? false :detail "nothing to copy"}
      (and (tmux?) (pipe! ["tmux" "load-buffer" "-w" "-"] text)) (done :tmux true)
      ;; a tmux older than 3.2 has no -w: the text is in tmux's own buffer,
      ;; which is not the system clipboard, and saying "copied" overclaimed
      (and (tmux?) (pipe! ["tmux" "load-buffer" "-"] text)) (done :tmux-buffer false)
      (remote?) (osc52!)
      :else (let [argv (native-argv)]
              (if (and argv (pipe! argv text))
                (done (keyword (first argv)) true)
                (osc52!))))))

(defn describe
  "One line on what `copy!` did: copied when it can say so, sent when it
  cannot, and why not when it did not."
  [{:keys [ok? via bytes verified? detail]}]
  (cond
    (not ok?) (str "not copied: " detail)
    verified? (str "copied " bytes " bytes · " (name via))
    (= :tmux-buffer via) (str "put " bytes " bytes in tmux's paste buffer, not the system clipboard · tmux")
    :else (str "sent " bytes " bytes to the terminal's clipboard · " (name via))))
