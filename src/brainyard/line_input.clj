;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.line-input
  "Reading a person's line from a terminal in an inline session, without the
  kernel's line editing (byj-gly.315.12).

  In canonical mode the terminal driver keeps the line until return, and on
  macOS drops what is past MAX_CANON (1024 bytes): a longer line never
  completes, so it is lost — no turn, no error. A pasted prompt is often that
  long. So the line is read with the terminal out of canonical mode and its
  echo off, a character at a time, and edited here (`step`): typing,
  backspace by display width (a Hangul syllable is two columns), ctrl-U,
  ctrl-W, return, ctrl-D on an empty line for the end of input, ctrl-Q on any
  line for the same (what `run --help` promises; it reaches us only with IXON
  off, where the driver otherwise takes it as XON — byj-j5v), and an escape
  sequence (an arrow key) swallowed. ctrl-C still interrupts: ISIG is left on.
  The mode is set for the read and restored after it, so a turn's output and
  a program a turn runs see the terminal as it was. fd 0 is read itself, a
  byte at a time, so whether a key is waiting is known (`key-waiting?`) and a
  parked turn can run while the prompt is idle (byj-gly.315.15).

  Not a line editor: no cursor movement, no history. And a backspace that
  crosses a wrapped line's edge leaves the earlier row as it was on most
  terminals, though the line itself is right. The full-screen renderer reads
  keys itself and is not affected."
  (:require [clojure.string :as str]
            [brainyard.effect :as fx]
            [glimmer-tui.text :as text]
            [jolt.ffi :as ffi]))

(defn- erase [s] (apply str (repeat (max 1 (text/width s)) "\b \b")))

(defn step
  "Apply one input character `c` (a codepoint, as a string) to `st`
  (`{:buf [chars…]}`): `[st' echo]`, `st'` holding `:line` once return is
  pressed, or `:eof` for ctrl-D on an empty line or ctrl-Q anywhere."
  [{:keys [buf esc] :as st} c]
  (let [buf (or buf [])]
    (cond
      ;; inside an escape sequence: ESC [ … final byte, or ESC O x — swallowed
      (= esc :start) [(assoc st :esc (case c "[" :csi "O" :ss3 nil)) ""]
      (= esc :ss3) [(assoc st :esc nil) ""]
      (= esc :csi) [(assoc st :esc (when-not (re-matches #"[@-~]" c) :csi)) ""]
      (= c "\u001b") [(assoc st :esc :start) ""]
      (#{"\r" "\n"} c) [(assoc st :line (apply str buf)) "\n"]
      (#{"\u007f" "\b"} c) (if (seq buf)
                             [(assoc st :buf (pop buf)) (erase (peek buf))]
                             [st ""])
      (= c "\u0015") [(assoc st :buf []) (apply str (map erase buf))]
      (= c "\u0011") [(assoc st :eof true) ""]
      (= c "\u0017") (let [trimmed (vec (reverse (drop-while #(= " " %) (reverse buf))))
                           kept (vec (reverse (drop-while #(not= " " %) (reverse trimmed))))
                           gone (subvec buf (count kept))]
                       [(assoc st :buf kept) (apply str (map erase gone))])
      (= c "\u0004") (if (empty? buf) [(assoc st :eof true) "\n"] [st ""])
      (= c "\t") [(assoc st :buf (conj buf "\t")) "\t"]
      ;; any other control character is not text
      (< (.codePointAt ^String c 0) 32) [st ""]
      :else [(assoc st :buf (conj buf c)) c])))

(defn- stty!
  "Run stty on the controlling terminal; its output trimmed, or nil."
  [& args]
  (let [r (fx/sh {:out :string :err :string} "sh" "-c" (str "stty " (str/join " " args) " < /dev/tty"))]
    (when (zero? (:exit r)) (str/trim (str (:out r))))))

;; --- the terminal, read from fd 0 itself (byj-gly.315.15) ------------------------------

(ffi/defcfn ^:private c-read "read" [:int :pointer :int] :int :blocking)
(ffi/defcfn ^:private c-poll "poll" [:pointer :int :int] :int :blocking)

(defn key-waiting?
  "Is a byte waiting on fd 0 within `ms`? poll(2), since neither jolt's
  `.ready` (true before anything is typed) nor Chez's `input-port-ready?`
  (false with input waiting) can say — measured, byj-gly.315.15."
  [ms]
  (let [pf (ffi/alloc 8)]
    (try
      (dotimes [i 8] (ffi/write pf :uint8 0 i))
      (ffi/write pf :int 0 0)
      (ffi/write pf :short 1 4)                  ; POLLIN
      (pos? (c-poll pf 1 ms))
      (finally (ffi/free pf)))))

(defn- read-byte
  "The next byte from fd 0, or nil at its end. One at a time, past every
  buffer: what is typed is not held where `key-waiting?` cannot see it."
  []
  (let [b (ffi/alloc 1)]
    (try (when (pos? (c-read 0 b 1)) (ffi/read b :uint8 0))
         (finally (ffi/free b)))))

(defn utf8-length
  "How many bytes the UTF-8 sequence that `lead` begins takes: 1 for an
  ASCII byte or one that cannot lead (it is taken alone)."
  [lead]
  (cond (< lead 0x80) 1 (= (bit-and lead 0xE0) 0xC0) 2 (= (bit-and lead 0xF0) 0xE0) 3 (= (bit-and lead 0xF8) 0xF0) 4 :else 1))

(defn decode
  "The character `bytes` (one UTF-8 sequence) encode, as a string."
  [bytes]
  (String. (byte-array (map unchecked-byte bytes)) "UTF-8"))

(defn- read-key
  "The next character from fd 0, or nil at its end."
  []
  (when-let [lead (read-byte)]
    (let [n (utf8-length lead)]
      (loop [bs [lead]]
        (if (< (count bs) n)
          (if-let [b (read-byte)] (recur (conj bs b)) (decode bs))
          (decode bs))))))

(defn read-terminal-line
  "A line from the terminal, edited here (`step`) and echoed to `out`, with
  the terminal out of canonical mode for the read: fd 0 read a byte at a time, so that
  between keys — while nothing has been typed — `poll` is asked whether a
  parked turn or a channel's is ready, and is answered by a real test of
  whether a key is waiting (`key-waiting?`). The line, nil at the end of
  input, or `{:polled x}`."
  [^java.io.Writer out & [{:keys [poll]}]]
  (let [saved (stty! "-g")]
    (try
      (stty! "-icanon" "-echo" "-ixon" "min" "1" "time" "0")
      (loop [st {:buf []}]
        (cond
          (:line st) (:line st)
          (:eof st) nil
          (and poll (empty? (:buf st)) (not (key-waiting? 60)))
          (if-let [x (poll)] {:polled x} (recur st))
          :else
          (if-let [c (read-key)]
            (let [[st' echo] (step st c)]
              (when (seq echo) (.write out ^String echo) (.flush out))
              (recur st'))
            (when (seq (:buf st)) (apply str (:buf st))))))
      (finally (when saved (stty! saved))))))
