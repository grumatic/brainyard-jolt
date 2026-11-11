;;; byj.el --- what byj does in an Emacs it reaches  -*- lexical-binding: t; -*-

;;; Commentary:
;; Loaded by byj (brainyard-jolt) into the Emacs `:emacs-server' names, the
;; first time it calls and whenever this file changes (RFC-067). byj runs
;;
;;   emacsclient -s SERVER --eval "(progn (load …) (byj-call 'OP ARGS OUT))"
;;
;; with ARGS and OUT files of its own: the arguments are read from ARGS as
;; JSON and the answer written to OUT as JSON, {"ok": …} or {"error": "…"},
;; so nothing a model wrote is ever part of the form Emacs evaluates. OP is
;; looked up in `byj-ops'; an op not there is an error.
;;
;; Behaviour as data (RFC-001): a project's .brainyard/emacs/byj.el replaces
;; this file. Every op must be quick: Emacs evaluates on its one thread, and
;; the person's Emacs waits while it does. `eval' is the exception, and byj
;; asks the person before it.

;;; Code:

(require 'json)
(require 'subr-x)
(require 'cl-lib)

(defvar byj-el-loaded nil
  "The version of byj.el byj last loaded here; byj's form sets it.")

(defvar byj--sessions (make-hash-table :test 'equal)
  "The byj session last heard from, by project directory.")

(defvar byj-max-chars 32000
  "The most text an op returns; the start, when there is more.")

(defvar byj--show-overlay nil
  "The lines `emacs$show' highlighted last, cleared by the next show.")

(defface byj-show
  '((t :inherit highlight :extend t))
  "Lines byj's agent is showing the person.")

;;; --- the plumbing ---------------------------------------------------------------

(defun byj--clip (s)
  (if (and (stringp s) (> (length s) byj-max-chars))
      (concat (substring s 0 byj-max-chars) "…")
    s))

(defun byj--read-args (file)
  (with-temp-buffer
    (let ((coding-system-for-read 'utf-8))
      (insert-file-contents file))
    (goto-char (point-min))
    (if (eobp)
        nil
      (json-parse-buffer :object-type 'alist :null-object nil :false-object nil))))

(defun byj--write (file value)
  (let ((coding-system-for-write 'utf-8)
        (text (json-encode value)))
    (with-temp-file file (insert text))))

(defun byj--bool (x) (if x t :json-false))

(defun byj--buffer-for (file buffer)
  "The live buffer visiting FILE, or named BUFFER; nil when there is none."
  (or (and file (find-buffer-visiting (expand-file-name file)))
      (and buffer (get-buffer buffer))))

(defun byj--window ()
  "The window the person is in: the one they left for the minibuffer, if so."
  (or (minibuffer-selected-window) (selected-window)))

;;; --- the ops --------------------------------------------------------------------

(defun byj-op-status (args)
  "Which Emacs this is, and the project's buffers with their unsaved state."
  (let* ((project (alist-get 'project (alist-get '_byj args)))
         ;; true names: byj's directory is canonical (/private/tmp on
         ;; macOS) where Emacs may have visited the file through a symlink
         (root (and project (file-name-as-directory (file-truename project))))
         (bufs (seq-filter
                (lambda (b) (let ((f (buffer-file-name b)))
                              (and f (or (null root) (string-prefix-p root (file-truename f))))))
                (buffer-list))))
    `((emacs . ,emacs-version)
      (server . ,(and (boundp 'server-name) server-name))
      (daemon . ,(byj--bool (daemonp)))
      (byj-el . ,byj-el-loaded)
      (org . ,(and (featurep 'org) (fboundp 'org-version) (org-version)))
      (buffers . ,(vconcat
                   (mapcar (lambda (b)
                             `((file . ,(buffer-file-name b))
                               (modified . ,(byj--bool (buffer-modified-p b)))))
                           bufs))))))

(declare-function org-get-outline-path "org" (&optional with-self use-cache))
(declare-function org-element-context "org-element" (&optional element))
(declare-function org-element-type "org-element" (element))
(declare-function org-element-lineage "org-element" (datum &optional types with-self))
(declare-function org-element-property "org-element" (property element))

(defun byj--org-context ()
  "In an org buffer, the heading path at point and the src block point is in."
  (when (derived-mode-p 'org-mode)
    (require 'org-element)
    (let* ((path (ignore-errors (org-get-outline-path t t)))
           (el (ignore-errors (org-element-context)))
           (el (and el (if (memq (org-element-type el) '(src-block)) el
                         (ignore-errors (org-element-lineage el '(src-block)))))))
      `((heading . ,(and path (vconcat path)))
        (block . ,(when el
                    `((language . ,(org-element-property :language el))
                      (name . ,(org-element-property :name el))
                      (line . ,(line-number-at-pos (org-element-property :begin el)))
                      (parameters . ,(org-element-property :parameters el))
                      (body . ,(byj--clip (org-element-property :value el))))))))))

(defun byj-op-context (_args)
  "What the person is looking at: the file or buffer, the cursor, unsaved
or not, the selection, the major mode, the other windows' files, and in org
the heading path and the src block at point."
  (with-selected-window (byj--window)
    `((file . ,(buffer-file-name))
      (buffer . ,(buffer-name))
      (line . ,(line-number-at-pos))
      (column . ,(1+ (current-column)))
      (modified . ,(byj--bool (buffer-modified-p)))
      (mode . ,(symbol-name major-mode))
      (region . ,(when (use-region-p)
                   (byj--clip (buffer-substring-no-properties (region-beginning) (region-end)))))
      (windows . ,(vconcat (delq nil (mapcar (lambda (w) (buffer-file-name (window-buffer w)))
                                             (window-list (selected-frame) 'no-minibuf)))))
      (org . ,(byj--org-context)))))

(defun byj-op-read (args)
  "A buffer's text as Emacs has it, unsaved edits included, or lines START
to END of it; a file no buffer visits is read from the disk, and says so."
  (let* ((file (alist-get 'file args))
         (buffer (alist-get 'buffer args))
         (start (alist-get 'start args))
         (end (alist-get 'end args))
         (b (byj--buffer-for file buffer))
         (slice (lambda ()
                  (save-restriction
                    (widen)
                    (let* ((total (count-lines (point-min) (point-max)))
                           (from (max 1 (or start 1)))
                           (to (min total (or end total)))
                           (beg (save-excursion (goto-char (point-min)) (forward-line (1- from)) (point)))
                           (fin (save-excursion (goto-char (point-min)) (forward-line to) (point))))
                      `((start . ,from) (end . ,to) (lines . ,total)
                        (text . ,(byj--clip (buffer-substring-no-properties beg fin)))))))))
    (cond
     (b (with-current-buffer b
          `((file . ,(buffer-file-name)) (buffer . ,(buffer-name)) (visited . t)
            (modified . ,(byj--bool (buffer-modified-p)))
            ,@(funcall slice))))
     ((and file (file-readable-p file))
      (with-temp-buffer
        (insert-file-contents file)
        `((file . ,(expand-file-name file)) (visited . :json-false) (modified . :json-false)
          ,@(funcall slice))))
     (t (error "No buffer visits %s, and it cannot be read" (or file buffer))))))

(defun byj-op-show (args)
  "Visit FILE at LINE in the window the person is in, highlight LINE to END
until the next show, and put MESSAGE in the echo area."
  (let* ((file (alist-get 'file args))
         (line (or (alist-get 'line args) 1))
         (end (or (alist-get 'end args) line))
         (message (alist-get 'message args)))
    (unless (and file (or (find-buffer-visiting (expand-file-name file)) (file-exists-p file)))
      (error "No file %s" file))
    (with-selected-window (byj--window)
      (find-file (expand-file-name file))
      (widen)
      (goto-char (point-min))
      (forward-line (1- line))
      (when (overlayp byj--show-overlay) (delete-overlay byj--show-overlay))
      (setq byj--show-overlay
            (make-overlay (line-beginning-position)
                          (save-excursion (forward-line (max 0 (- end line))) (line-beginning-position 2))))
      (overlay-put byj--show-overlay 'face 'byj-show)
      (recenter))
    (when message (message "byj: %s" message))
    `((file . ,(expand-file-name file)) (line . ,line) (end . ,end))))

(defun byj-op-open (args)
  "Visit FILE at LINE and COLUMN in the window the person is in."
  (let ((file (alist-get 'file args))
        (line (or (alist-get 'line args) 1))
        (column (or (alist-get 'column args) 1)))
    (with-selected-window (byj--window)
      (find-file (expand-file-name file))
      (goto-char (point-min))
      (forward-line (1- line))
      (move-to-column (1- column)))
    `((file . ,(expand-file-name file)) (line . ,line))))

(defun byj-op-revert (_args)
  "Revert each buffer whose file changed on disk and that holds no unsaved
edits: what the agent's tools wrote, so a frame never shows the old text."
  (let (reverted)
    (dolist (b (buffer-list))
      (with-current-buffer b
        (when (and buffer-file-name (not (buffer-modified-p))
                   (file-exists-p buffer-file-name)
                   (not (verify-visited-file-modtime b)))
          (when (ignore-errors (revert-buffer t t t) t)
            (push buffer-file-name reverted)))))
    `((reverted . ,(vconcat (nreverse reverted))))))

(defun byj-op-eval (args)
  "Evaluate CODE, every form in it, lexically; the last value, printed."
  (let* ((code (or (alist-get 'code args) (error "No code")))
         (form (car (read-from-string (concat "(progn\n" code "\n)"))))
         (value (eval form t)))
    `((value . ,(byj--clip (prin1-to-string value))))))

;;; --- literate org (RFC-067 phase 2, byj-gly.460) ---------------------------------

(declare-function org-babel-get-src-block-info "ob-core" (&optional no-eval datum))
(declare-function org-babel-where-is-src-block-result "ob-core" (&optional insert info hash))
(declare-function org-babel-find-named-block "ob-core" (name))
(declare-function org-babel-execute-src-block "ob-core" (&optional arg info params executor-type))
(declare-function org-babel-effective-tangled-filename "ob-tangle" (buffer-fn src-lang src-tfile))
(declare-function org-babel-tangle "ob-tangle" (&optional arg target-file lang-re))
(declare-function org-babel-tangle-collect-blocks "ob-tangle" (&optional lang-re tangle-file))
(declare-function org-element-map "org-element" (data types fun &optional info first-match no-recursion with-affiliated))
(declare-function org-element-parse-buffer "org-element" (&optional granularity visible-only keep-deferred))
(declare-function org-in-commented-heading-p "org" (&optional no-inheritance element))
(defvar org-babel-noweb-wrap-start)
(defvar org-babel-noweb-wrap-end)
(defvar org-confirm-babel-evaluate)
(defvar org-mode-hook)
(declare-function org-element-at-point "org-element" (&optional epom cached-only))

(defvar byj-max-entries 500
  "The most headings, or blocks, an outline lists.")

(defun byj--fresh (b)
  "Make buffer B what its file holds when it holds no unsaved edits: byj's
own tools may have just written the file, and the revert after them runs
off byj's thread. Edits on both sides are a conflict, refused."
  (with-current-buffer b
    (when (and buffer-file-name (file-exists-p buffer-file-name)
               (not (verify-visited-file-modtime b)))
      (if (buffer-modified-p)
          (error "%s has unsaved edits and was changed on disk too" buffer-file-name)
        (revert-buffer t t t))))
  b)

(defmacro byj--in-org-file (file &rest body)
  "Run BODY in an org buffer of FILE: the buffer visiting it, unsaved edits
and all; else a temporary one read from the disk, no mode hooks run, so
nothing is added to the person's buffers."
  (declare (indent 1))
  `(let* ((path (expand-file-name ,file))
          (visiting (find-buffer-visiting path)))
     (require 'org)
     (require 'ob-tangle)
     (if visiting
         (with-current-buffer (byj--fresh visiting)
           (unless (derived-mode-p 'org-mode) (error "%s is not in org-mode" path))
           (save-excursion (save-restriction (widen) ,@body)))
       (unless (file-readable-p path) (error "No file %s" path))
       (with-temp-buffer
         (insert-file-contents path)
         (setq default-directory (file-name-directory path))
         (let ((org-mode-hook nil)) (delay-mode-hooks (org-mode)))
         ,@body))))

(defun byj--org-buffer (file)
  "The buffer visiting org FILE, visited now when none does: tangling and
running a block need one, and results land in it."
  (let* ((path (expand-file-name file))
         (b (or (find-buffer-visiting path)
                (and (file-readable-p path) (find-file-noselect path)))))
    (unless b (error "No file %s" path))
    (byj--fresh b)
    (with-current-buffer b
      (unless (derived-mode-p 'org-mode) (error "%s is not in org-mode" path)))
    b))

(defun byj--block-target (path lang info)
  "Where the block at point tangles to, absolute, or nil."
  (let ((tfile (cdr (assq :tangle (nth 2 info)))))
    (when (and lang tfile (not (equal tfile "no")) (fboundp 'org-babel-effective-tangled-filename))
      (org-babel-effective-tangled-filename path lang tfile))))

(defun byj--noweb-refs (body)
  (let ((re (concat (regexp-quote org-babel-noweb-wrap-start) "\\([^ \t\n]+?\\)"
                    (regexp-quote org-babel-noweb-wrap-end)))
        refs (start 0))
    (while (and body (string-match re body start))
      (push (match-string 1 body) refs)
      (setq start (match-end 0)))
    (vconcat (delete-dups (nreverse refs)))))

(defun byj-op-org-outline (args)
  "FILE's headings and src blocks, as Emacs has the file: each block's name,
language, lines, header arguments as in force (inherited ones included),
where it tangles to, the noweb references in it, and whether it has results."
  (let ((file (or (alist-get 'file args) (error "No file"))))
    (byj--in-org-file file
      (let* ((tree (org-element-parse-buffer))
             (headings (org-element-map tree 'headline
                         (lambda (h)
                           `((level . ,(org-element-property :level h))
                             (title . ,(org-element-property :raw-value h))
                             (line . ,(line-number-at-pos (org-element-property :begin h)))
                             (todo . ,(org-element-property :todo-keyword h))
                             (tags . ,(vconcat (org-element-property :tags h)))))))
             (blocks (org-element-map tree 'src-block
                       (lambda (b)
                         (goto-char (org-element-property :begin b))
                         (let* ((info (ignore-errors (org-babel-get-src-block-info 'no-eval)))
                                (lang (org-element-property :language b))
                                (params (nth 2 info)))
                           `((name . ,(org-element-property :name b))
                             (language . ,lang)
                             (line . ,(line-number-at-pos (org-element-property :begin b)))
                             (end . ,(line-number-at-pos (org-element-property :end b)))
                             (header . ,(org-element-property :parameters b))
                             (results-param . ,(cdr (assq :results params)))
                             (tangle . ,(and (not (org-in-commented-heading-p))
                                             (byj--block-target path lang info)))
                             (noweb . ,(byj--noweb-refs (org-element-property :value b)))
                             (has-results . ,(byj--bool (ignore-errors (org-babel-where-is-src-block-result)))))))))
             (cap (lambda (xs) (vconcat (seq-take xs byj-max-entries)))))
        `((file . ,path)
          (modified . ,(byj--bool (and (find-buffer-visiting path) (buffer-modified-p (find-buffer-visiting path)))))
          (headings . ,(funcall cap headings))
          (blocks . ,(funcall cap blocks))
          (more . ,(byj--bool (or (> (length headings) byj-max-entries) (> (length blocks) byj-max-entries)))))))))

(defun byj--tangle-targets ()
  "The files the current org buffer tangles to, absolute, as org itself
collects them."
  (let ((blocks (org-babel-tangle-collect-blocks)))
    (sort (delete-dups (delq nil (mapcar (lambda (b) (and (stringp (car b)) (expand-file-name (car b)))) blocks)))
          #'string<)))

(defun byj-op-org-tangle-targets (args)
  "The files tangling FILE would write."
  (with-current-buffer (byj--org-buffer (or (alist-get 'file args) (error "No file")))
    `((file . ,(buffer-file-name)) (targets . ,(vconcat (byj--tangle-targets))))))

(defun byj-op-org-tangle (args)
  "Tangle FILE, refusing when its buffer holds unsaved edits or its
targets are no longer EXPECT, the ones byj checked; the files written."
  (with-current-buffer (byj--org-buffer (or (alist-get 'file args) (error "No file")))
    (let ((now (byj--tangle-targets))
          (expect (sort (mapcar #'expand-file-name (append (alist-get 'expect args) nil)) #'string<)))
      (when (buffer-modified-p)
        ;; org saves the buffer before tangling (`org-babel-pre-tangle-hook'),
        ;; which would save the person's edits for them
        (error "%s has unsaved edits: they save it, then it can be tangled" (buffer-file-name)))
      (unless (equal now expect)
        (error "The files it tangles to changed since they were checked: %s" (string-join now ", ")))
      (let ((written (org-babel-tangle)))
        `((file . ,(buffer-file-name))
          (modified . ,(byj--bool (buffer-modified-p)))
          (written . ,(vconcat (sort (delete-dups (mapcar #'expand-file-name written)) #'string<))))))))

(defun byj--goto-block (name line)
  "Point to the src block named NAME, or the one at LINE."
  (cond
   (name (let ((pos (org-babel-find-named-block name)))
           (unless pos (error "No src block named %s" name))
           (goto-char pos)))
   (line (goto-char (point-min)) (forward-line (1- line))
         (unless (org-element-lineage (org-element-context) '(src-block) t)
           (error "Line %d is not in a src block" line))
         (goto-char (org-element-property :begin (org-element-lineage (org-element-context) '(src-block) t))))
   (t (error "Give a block's name or line"))))

(defun byj-op-org-execute (args)
  "Run one src block of FILE, by NAME or LINE, through org-babel; its
results go where org puts them, the buffer saved only with SAVE, and not
over unsaved edits. byj asked the person before calling this, so org's own
confirmation is not asked."
  (let ((b (byj--org-buffer (or (alist-get 'file args) (error "No file")))))
    (with-current-buffer b
      (save-excursion
        (save-restriction
          (widen)
          (byj--goto-block (alist-get 'name args) (alist-get 'line args))
          (when (and (alist-get 'save args) (buffer-modified-p))
            (error "%s has unsaved edits: saving the results would save them too" (buffer-file-name)))
          (let* ((info (org-babel-get-src-block-info))
                 (value (let ((org-confirm-babel-evaluate nil))
                          (org-babel-execute-src-block nil info)))
                 (where (org-babel-where-is-src-block-result nil info))
                 (results (when where
                            (save-excursion
                              (goto-char where)
                              (let ((el (org-element-at-point)))
                                (buffer-substring-no-properties
                                 (point) (org-element-property :end el)))))))
            (when (alist-get 'save args) (save-buffer))
            `((file . ,(buffer-file-name))
              (block . ,(or (nth 4 info) (line-number-at-pos)))
              (language . ,(nth 0 info))
              (value . ,(byj--clip (if (stringp value) value (prin1-to-string value))))
              (results . ,(byj--clip results))
              (modified . ,(byj--bool (buffer-modified-p))))))))))

;;; --- coaching: notes and an undoable edit (RFC-067 phase 3, byj-gly.461) ---------

(defface byj-note
  '((t :inherit shadow :slant italic))
  "A note byj's agent left at the end of a line.")

(defvar byj--notes nil
  "The overlays of byj's notes, newest first.")

(defconst byj-notes-buffer "*byj-notes*"
  "Where byj's notes are listed, in compilation-mode, for `next-error'.")

(defun byj--file-buffer (file)
  "The buffer visiting FILE, visited now when none does, and fresh."
  (let* ((path (expand-file-name file))
         (b (or (find-buffer-visiting path)
                (and (file-readable-p path) (find-file-noselect path)))))
    (unless b (error "No file %s" path))
    (byj--fresh b)))

(defun byj--notes-refresh ()
  "List the notes still in buffers in `byj-notes-buffer', and make it the
buffer `next-error' walks."
  (setq byj--notes (seq-filter #'overlay-buffer byj--notes))
  (let ((b (get-buffer-create byj-notes-buffer)))
    (with-current-buffer b
      (let ((inhibit-read-only t))
        (erase-buffer)
        (insert (format "byj's notes: %d — M-g n and M-g p walk them\n\n" (length byj--notes)))
        (dolist (ov (reverse byj--notes))
          (with-current-buffer (overlay-buffer ov)
            (let ((line (line-number-at-pos (overlay-start ov)))
                  (file (buffer-file-name)))
              (with-current-buffer b
                (insert (format "%s:%d: %s: %s\n" file line
                                (overlay-get ov 'byj-kind) (overlay-get ov 'byj-text))))))))
      (unless (derived-mode-p 'compilation-mode) (compilation-mode))
      (setq next-error-last-buffer b))
    b))

(defun byj-op-note (args)
  "Leave each of NOTES — {file, line, text, kind} — at the end of its line,
dimmed, and list them all in `byj-notes-buffer'."
  (let ((n 0))
    (dolist (note (append (alist-get 'notes args) nil))
      (let ((file (alist-get 'file note))
            (line (or (alist-get 'line note) 1))
            (text (replace-regexp-in-string "[\n\r]+" " " (or (alist-get 'text note) "")))
            (kind (or (alist-get 'kind note) "note")))
        (with-current-buffer (byj--file-buffer file)
          (save-excursion
            (save-restriction
              (widen)
              (goto-char (point-min))
              (forward-line (1- line))
              (let ((ov (make-overlay (line-beginning-position) (line-end-position))))
                (overlay-put ov 'byj-note t)
                (overlay-put ov 'byj-kind kind)
                (overlay-put ov 'byj-text text)
                (overlay-put ov 'help-echo (format "byj %s: %s" kind text))
                (overlay-put ov 'after-string (propertize (format "  ◂ byj %s: %s" kind text) 'face 'byj-note))
                (push ov byj--notes)
                (setq n (1+ n))))))))
    (byj--notes-refresh)
    `((added . ,n) (notes . ,(length byj--notes)) (buffer . ,byj-notes-buffer))))

(defun byj-op-notes-clear (args)
  "Take away byj's notes: FILE's, or all of them."
  (let* ((file (alist-get 'file args))
         (path (and file (expand-file-name file)))
         (gone (seq-filter (lambda (ov)
                             (or (null path)
                                 (and (overlay-buffer ov)
                                      (equal path (buffer-file-name (overlay-buffer ov))))))
                           byj--notes)))
    (mapc #'delete-overlay gone)
    (byj--notes-refresh)
    `((cleared . ,(length gone)) (notes . ,(length byj--notes)))))

(defun byj-op-edit (args)
  "Replace OLD, found exactly once in FILE's buffer, with NEW, as one undo
step, the buffer left unsaved and the new text highlighted until the next
show: the person sees it, keeps it by saving, or undoes it."
  (let* ((file (or (alist-get 'file args) (error "No file")))
         (old (or (alist-get 'old args) (error "No old text")))
         (new (or (alist-get 'new args) ""))
         (b (byj--file-buffer file)))
    (with-current-buffer b
      (save-restriction
        (widen)
        (let ((case-fold-search nil) (hits 0) at)
          (save-excursion
            (goto-char (point-min))
            (while (search-forward old nil t)
              (setq hits (1+ hits))
              (unless at (setq at (match-beginning 0)))))
          (unless (= hits 1)
            (error "The text to replace is in %s %d times, not once" (buffer-file-name) hits))
          (undo-boundary)
          (goto-char at)
          (delete-region at (+ at (length old)))
          (insert new)
          (undo-boundary)
          (when (overlayp byj--show-overlay) (delete-overlay byj--show-overlay))
          (setq byj--show-overlay (make-overlay at (+ at (length new))))
          (overlay-put byj--show-overlay 'face 'byj-show)
          `((file . ,(buffer-file-name)) (line . ,(line-number-at-pos at))
            (modified . ,(byj--bool (buffer-modified-p)))))))))

;;; --- what Emacs knows (phase 5) ------------------------------------------------

(defvar byj-max-items 200
  "The most places or diagnostics an op returns.")

(declare-function xref-find-backend "xref" ())
(declare-function xref-backend-identifier-at-point "xref" (backend))
(declare-function xref-backend-definitions "xref" (backend identifier))
(declare-function xref-backend-references "xref" (backend identifier))
(declare-function xref-item-location "xref" (item))
(declare-function xref-item-summary "xref" (item))
(declare-function xref-location-group "xref" (location))
(declare-function xref-location-line "xref" (location))
(declare-function xref-location-marker "xref" (location))
(declare-function xref-file-location-p "xref" (x))
(declare-function xref-file-location-column "xref" (x))
(declare-function project-current "project" (&optional maybe-prompt directory))
(defvar tags-file-name)
(defvar tags-table-list)

(defun byj--goto (line column identifier)
  "Put point at LINE and COLUMN, or at IDENTIFIER on LINE, or at its first
place in the buffer as a symbol when there is no LINE."
  (goto-char (point-min))
  (when line (forward-line (1- line)))
  (cond
   (identifier
    (let ((re (concat "\\_<" (regexp-quote identifier) "\\_>")))
      (unless (if line
                  (re-search-forward re (line-end-position) t)
                (re-search-forward re nil t))
        (error "%s is not %s" identifier (if line (format "on line %d" line) "in the buffer")))
      (goto-char (match-beginning 0))))
   (column (move-to-column (max 0 (1- column))))))

(defun byj--xref-place (item)
  "ITEM as a place: file, line, column and the text xref shows for it."
  (let* ((loc (xref-item-location item))
         (line (ignore-errors (xref-location-line loc)))
         (col (and line (xref-file-location-p loc) (xref-file-location-column loc)))
         (file (ignore-errors (xref-location-group loc))))
    (unless line
      ;; a place known by a marker alone (elisp's): where the marker is
      (let ((m (ignore-errors (xref-location-marker loc))))
        (when (and m (marker-buffer m))
          (with-current-buffer (marker-buffer m)
            (save-excursion
              (goto-char m)
              (setq line (line-number-at-pos)
                    col (current-column)
                    file (or (buffer-file-name) file)))))))
    `((file . ,file) (line . ,line) (column . ,(and col (1+ col)))
      (text . ,(string-trim (substring-no-properties (or (xref-item-summary item) "")))))))

(defun byj-op-xref (args)
  "Where IDENTIFIER (or the one at LINE and COLUMN) of FILE is defined, or
used with REFERENCES, as the buffer's xref backend knows: eglot or
lsp-mode's server, elisp, a tags table. Says which backend answered, and
that there is none rather than nothing found."
  (require 'xref)
  (let* ((file (or (alist-get 'file args) (error "No file")))
         (refs (alist-get 'references args))
         (b (byj--file-buffer file)))
    (with-current-buffer b
      (save-excursion
        (byj--goto (alist-get 'line args) (alist-get 'column args) (alist-get 'identifier args))
        (let ((backend (xref-find-backend)))
          (if (or (null backend)
                  ;; etags with no table asks the person for one: none here
                  (and (eq backend 'etags)
                       (not (bound-and-true-p tags-file-name))
                       (not (bound-and-true-p tags-table-list))))
              `((backend . nil)
                (note . ,(format "no xref backend in %s (%s): eglot or lsp-mode, or a tags table, gives one"
                                 (buffer-name) major-mode)))
            (let ((id (xref-backend-identifier-at-point backend)))
              (unless id (error "No identifier at line %d" (line-number-at-pos)))
              (when (and refs (memq backend '(elisp etags)) (not (project-current nil)))
                ;; their references are a search of the project, asked for otherwise
                (error "Finding references with %s searches the project, and %s is in none (no version control)"
                       backend (buffer-file-name)))
              (let* ((searched (and refs (memq backend '(elisp etags))))
                     (items (cond
                             (searched
                              ;; the project's files alone: elisp would also
                              ;; grep every directory on the load-path
                              (cl-letf (((symbol-function 'project-external-roots) (lambda (_) nil)))
                                (xref-backend-references backend id)))
                             (refs (xref-backend-references backend id))
                             (t (xref-backend-definitions backend id))))
                     (places (mapcar #'byj--xref-place (seq-take items byj-max-items))))
                `((backend . ,(symbol-name backend))
                  ,@(when searched '((scope . "the project's files, searched")))
                  ;; the name, not a backend's token for it (eglot's says
                  ;; "LSP identifier at point")
                  (identifier . ,(or (alist-get 'identifier args)
                                     (thing-at-point 'symbol t)
                                     (substring-no-properties (format "%s" id))))
                  (kind . ,(if refs "references" "definitions"))
                  (places . ,(vconcat places))
                  (total . ,(length items)))))))))))

(declare-function flymake-diagnostics "flymake" (&optional beg end))
(declare-function flymake-diagnostic-beg "flymake" (diag))
(declare-function flymake-diagnostic-type "flymake" (diag))
(declare-function flymake-diagnostic-text "flymake" (diag))
(declare-function flymake-diagnostic-backend "flymake" (diag))
(declare-function flymake-running-backends "flymake" ())
(declare-function flymake-reporting-backends "flymake" ())
(declare-function flymake-start "flymake" (&optional deferred force))
(defvar flymake-diagnostic-functions)
(defvar flycheck-current-errors)
(declare-function flycheck-error-line "ext:flycheck" (err))
(declare-function flycheck-error-column "ext:flycheck" (err))
(declare-function flycheck-error-level "ext:flycheck" (err))
(declare-function flycheck-error-message "ext:flycheck" (err))
(declare-function flycheck-error-checker "ext:flycheck" (err))

(defun byj--severity (type)
  "A flymake diagnostic TYPE as error, warning or note."
  (let ((cat (if (keywordp type) type (get type 'flymake-category))))
    (pcase cat
      ((or :error 'flymake-error) "error")
      ((or :warning 'flymake-warning) "warning")
      (_ "note"))))

(defun byj-op-diagnostics (args)
  "FILE's diagnostics as Emacs has them now, for the buffer as it stands:
flymake's (eglot's among them) or flycheck's. Says which answered, which
checkers have not reported yet, and that there is none rather than none
found."
  (let* ((file (or (alist-get 'file args) (error "No file")))
         (b (byj--file-buffer file)))
    (with-current-buffer b
      (cond
       ((bound-and-true-p flymake-mode)
        (let* ((started (when (and (null (flymake-running-backends)) flymake-diagnostic-functions)
                          ;; never run here (it runs when Emacs is idle in the
                          ;; buffer): run it now, as idleness would
                          (flymake-start)
                          t))
               (ds (flymake-diagnostics))
               (pending (seq-difference (flymake-running-backends) (flymake-reporting-backends))))
          `((backend . "flymake")
            ,@(when started '((note . "flymake had not run in this buffer: started now; what its checkers found so far is here, and :pending names those still running")))
            (modified . ,(byj--bool (buffer-modified-p)))
            (checkers . ,(vconcat (mapcar #'symbol-name (flymake-running-backends))))
            (pending . ,(vconcat (mapcar #'symbol-name pending)))
            (diagnostics
             . ,(vconcat
                 (mapcar (lambda (d)
                           (save-excursion
                             (goto-char (flymake-diagnostic-beg d))
                             `((line . ,(line-number-at-pos)) (column . ,(1+ (current-column)))
                               (severity . ,(byj--severity (flymake-diagnostic-type d)))
                               (text . ,(byj--clip (flymake-diagnostic-text d)))
                               (checker . ,(format "%s" (flymake-diagnostic-backend d))))))
                         (seq-take (sort ds (lambda (x y) (< (flymake-diagnostic-beg x) (flymake-diagnostic-beg y))))
                                   byj-max-items))))
            (total . ,(length ds)))))
       ((bound-and-true-p flycheck-mode)
        (let ((es flycheck-current-errors))
          `((backend . "flycheck")
            (modified . ,(byj--bool (buffer-modified-p)))
            (diagnostics
             . ,(vconcat
                 (mapcar (lambda (e)
                           `((line . ,(flycheck-error-line e))
                             (column . ,(flycheck-error-column e))
                             (severity . ,(format "%s" (flycheck-error-level e)))
                             (text . ,(byj--clip (flycheck-error-message e)))
                             (checker . ,(format "%s" (flycheck-error-checker e)))))
                         (seq-take es byj-max-items))))
            (total . ,(length es)))))
       (t `((backend . nil)
            (note . ,(format "no flymake or flycheck in %s (%s): nothing checks it in Emacs"
                             (buffer-name) major-mode))))))))

;;; --- what this Emacs says about itself (phase 7) -------------------------------
;;
;; A model names keys from memory, and this person's Emacs — its init, Doom's
;; or Spacemacs's, evil's states — is not the one it remembers. These answer
;; from the keymaps active in the buffer asked about, as the person would get
;; them there.

(defun byj--describe-buffer (file)
  "FILE's buffer, else the one in front of the person."
  (if file (byj--file-buffer file) (window-buffer (byj--window))))

(defun byj--doc-line (sym)
  "SYM's documentation, its first line; nil when it has none."
  (let ((d (ignore-errors (documentation sym))))
    (and (stringp d) (car (split-string d "\n")))))

(defun byj--real-key-p (key)
  "Whether KEY is one a person types, not a menu or tool bar's entry."
  (not (memq (aref key 0) '(menu-bar tool-bar tab-bar header-line mode-line remap))))

(defun byj--keys-for (sym)
  "The keys that run command SYM here, as Emacs writes them."
  (mapcar #'key-description
          (seq-take (seq-filter #'byj--real-key-p (where-is-internal sym nil nil t)) 5)))

(defun byj--binding-name (b)
  "What binding B is, as a name: a command's, a prefix, or another kind."
  (cond ((keymapp b) "prefix")
        ((and (symbolp b) b) (symbol-name b))
        ((stringp b) "keyboard macro")
        (t "an anonymous command")))

(defun byj--prefix-keys (prefix)
  "The keys that continue PREFIX here, from every active keymap, the one
that takes precedence winning, as ((key . name) ...) sorted by key."
  (let ((seen (make-hash-table :test 'equal)) out)
    (dolist (m (current-active-maps t))
      (let ((sub (if (equal prefix []) m (lookup-key m prefix))))
        (when (keymapp sub)
          (map-keymap
           (lambda (ev b)
             (unless (or (consp ev) (memq ev '(menu-bar tool-bar tab-bar remap))
                         (gethash ev seen))
               (puthash ev t seen)
               (unless (or (null b) (eq b 'undefined))
                 (push (cons (key-description (vconcat prefix (vector ev))) (byj--binding-name b)) out))))
           sub))))
    (sort out (lambda (a b) (string< (car a) (car b))))))

(defun byj-op-describe (args)
  "What this Emacs says about itself in a buffer — FILE's, else the one in
front of the person — so that the keymaps of its modes answer: KEY, the
command it runs; COMMAND, its keys, doc line and whether it is one; MODES,
the major mode and the minor modes on; PREFIX, the keys that continue it;
APROPOS, commands whose names have all its words, with their keys."
  (let ((key (alist-get 'key args))
        (command (alist-get 'command args))
        (prefix (alist-get 'prefix args))
        (words (alist-get 'apropos args))
        (modes (alist-get 'modes args)))
    (with-current-buffer (byj--describe-buffer (alist-get 'file args))
      (append
       `((buffer . ,(buffer-name)) (mode . ,(symbol-name major-mode)))
       (cond
        (key
         (let* ((k (kbd key))
                (b (key-binding k t)))
           (cond ((null b) `((key . ,(key-description k)) (command . nil)
                             (note . ,(format "%s runs nothing in %s" (key-description k) (buffer-name)))))
                 ((keymapp b) `((key . ,(key-description k)) (prefix . t)
                                (keys . ,(vconcat (mapcar (lambda (c) `((key . ,(car c)) (command . ,(cdr c))))
                                                          (seq-take (byj--prefix-keys k) byj-max-items))))))
                 (t `((key . ,(key-description k)) (command . ,(byj--binding-name b))
                      (doc . ,(and (symbolp b) (byj--doc-line b))))))))
        (command
         (let ((sym (intern-soft command)))
           (if (not (and sym (fboundp sym)))
               `((command . ,command) (exists . :json-false)
                 (note . ,(format "no function %s in this Emacs: its package may not be installed or loaded" command)))
             `((command . ,command) (exists . t)
               (interactive . ,(byj--bool (commandp sym)))
               (keys . ,(vconcat (byj--keys-for sym)))
               (doc . ,(byj--doc-line sym))))))
        (prefix
         (let* ((k (kbd prefix))
                (keys (byj--prefix-keys k)))
           (if (null keys)
               `((prefix . ,(key-description k)) (keys . []) (note . ,(format "%s is not a prefix in %s" (key-description k) (buffer-name))))
             `((prefix . ,(key-description k))
               (keys . ,(vconcat (mapcar (lambda (c) `((key . ,(car c)) (command . ,(cdr c))))
                                         (seq-take keys byj-max-items))))
               (total . ,(length keys))))))
        (words
         (let* ((ws (split-string (downcase words) "[ \t-]+" t))
                (found (and ws (apropos-internal (regexp-quote (car ws)) #'commandp)))
                (found (seq-filter (lambda (s) (let ((n (symbol-name s)))
                                                 (seq-every-p (lambda (w) (string-search w n)) ws)))
                                   found))
                (rows (mapcar (lambda (s) (list s (byj--keys-for s))) found))
                ;; bound ones first, then the shorter names
                (rows (sort rows (lambda (a b) (if (eq (null (cadr a)) (null (cadr b)))
                                                   (< (length (symbol-name (car a))) (length (symbol-name (car b))))
                                                 (cadr a))))))
           `((apropos . ,words)
             (commands . ,(vconcat (mapcar (lambda (r) `((command . ,(symbol-name (car r)))
                                                         (keys . ,(vconcat (cadr r)))
                                                         (doc . ,(byj--doc-line (car r)))))
                                           (seq-take rows 50))))
             (total . ,(length rows)))))
        (modes
         `((minor . ,(vconcat (sort (mapcar #'symbol-name
                                            (seq-filter (lambda (m) (and (boundp m) (symbol-value m))) minor-mode-list))
                                    #'string<)))
           (doc . ,(byj--doc-line major-mode))))
        (t (error "Give key, command, prefix, apropos or modes")))))))

;;; --- the session in the mode line (phase 7) -------------------------------------

(defcustom byj-mode-line t
  "Whether the mode line shows the byj session's state for the buffer's project."
  :type 'boolean :group 'byj)

(defcustom byj-turn-message t
  "Whether a turn's end is said in the echo area."
  :type 'boolean :group 'byj)

(defvar byj--turns (make-hash-table :test 'equal)
  "The last state each project's session told, by project directory: an
alist of state, turn, question, at, elapsed and how it ended.")

(defun byj--turn-entry ()
  "The state told for the deepest project holding `default-directory'."
  (let ((here (file-name-as-directory (file-truename default-directory)))
        best)
    (maphash (lambda (dir st)
               (let ((d (file-name-as-directory (file-truename dir))))
                 (when (and (string-prefix-p d here)
                            (or (null best) (> (length d) (length (car best)))))
                   (setq best (cons d st)))))
             byj--turns)
    (cdr best)))

(defun byj--mode-line-text ()
  "What the mode line says of byj here, or nil."
  (when-let* ((byj-mode-line)
              (st (ignore-errors (byj--turn-entry))))
    (let ((turn (alist-get 'turn st)))
      (propertize (if (equal (alist-get 'state st) "busy")
                      (format " byj⏳%s" (or turn ""))
                    (format " byj✓%s%s" (or turn "")
                            (let ((n (or (alist-get 'files st) 0)))
                              (if (> n 0) (format " ✎%d" n) ""))))
                  'help-echo (format "byj session %s%s"
                                     (or (alist-get 'session st) "")
                                     (if-let ((q (alist-get 'question st))) (concat ": " q) ""))))))

(defvar byj--mode-line-construct '(:eval (byj--mode-line-text))
  "The mode-line element byj adds to `global-mode-string'.")
(put 'byj--mode-line-construct 'risky-local-variable t)

(defun byj-op-turn (args)
  "A turn of the project's session started (STATE busy, TURN, QUESTION) or
ended (STATE idle, ELAPSED-MS, FILES its tools changed, and ENDED when it
ended otherwise than by answering — cancelled, out of iterations): kept for
the mode line, an end said in the echo area. One told before another it was
sent after is ignored."
  (let* ((meta (alist-get '_byj args))
         (dir (or (alist-get 'project meta) (error "No project")))
         (old (gethash dir byj--turns))
         (at (or (alist-get 'at args) 0)))
    (if (and old (> (or (alist-get 'at old) 0) at))
        `((ignored . t))
      (let ((st `((state . ,(alist-get 'state args))
                  (turn . ,(or (alist-get 'turn args) (alist-get 'turn old)))
                  (question . ,(if (equal (alist-get 'state args) "busy")
                                   (alist-get 'question args)
                                 (alist-get 'question old)))
                  (session . ,(alist-get 'session meta))
                  (at . ,at)
                  (elapsed . ,(alist-get 'elapsed-ms args))
                  (files . ,(alist-get 'files args))
                  (ended . ,(alist-get 'ended args)))))
        (puthash dir st byj--turns)
        (when (and byj-mode-line (not (member 'byj--mode-line-construct global-mode-string)))
          (setq global-mode-string (append (or global-mode-string '("")) '(byj--mode-line-construct))))
        (force-mode-line-update t)
        (when (and byj-turn-message (equal (alist-get 'state args) "idle"))
          (let ((ended (alist-get 'ended args))
                (secs (/ (or (alist-get 'elapsed-ms args) 0) 1000)))
            (let ((files (or (alist-get 'files args) 0))
                  (key (lambda (cmd) (let ((k (where-is-internal cmd nil t))) (and k (key-description k))))))
              (message "byj: turn %s %s (%ds)%s" (or (alist-get 'turn st) "")
                       (if (member ended '(nil "")) "done" (format "ended: %s" ended))
                       secs
                       (cond ((> files 0)
                              (format ", %d file%s changed%s" files (if (= files 1) "" "s")
                                      (if (funcall key 'byj-review) (format " — %s to review" (funcall key 'byj-review)) "")))
                             ((funcall key 'byj-ask) (format " — %s to ask" (funcall key 'byj-ask)))
                             (t ""))))))
        `((state . ,(alist-get 'state st)) (turn . ,(alist-get 'turn st)))))))

;;; --- learning Emacs (phase 9) ---------------------------------------------------
;;
;; `byj-observe-mode' is the person's to turn on (RFC-067 D9): it keeps, in this
;; Emacs's memory alone, which commands they run — the command's name, the key
;; it was run by when that key is the command's own binding, the major mode,
;; whether it came through M-x — and never what they type: a self-inserting
;; command keeps no key, a run of one command is one entry with a count, and
;; nothing is written to disk. `emacs$habits' reads a summary of it.

(defcustom byj-observe-size 3000
  "The most entries `byj-observe-mode' keeps, a run of one command being one."
  :type 'integer :group 'byj)

(defcustom byj-run-length 6
  "How many times in a row a command must run to count as a run worth a tip."
  :type 'integer :group 'byj)

(defvar byj--observed nil
  "What `byj-observe-mode' saw, newest first: vectors
[COMMAND KEY MODE COUNT VIA-MX TIME].")

(defvar byj--observed-n 0 "How many entries `byj--observed' has.")
(defvar byj--observe-since nil "When observing began.")

(defvar byj--tips nil
  "Tips byj gave, newest first: ((command . C) (why . W) (at . TIME)).")

(defun byj--own-key (cmd)
  "The keys that ran CMD, when they are its binding here; else nil, so what
the person typed is never kept."
  (let ((keys (this-command-keys-vector)))
    (and (> (length keys) 0)
         (not (string-match-p "self-insert" (symbol-name cmd)))
         (eq (key-binding keys t) cmd)
         (key-description keys))))

(defun byj--observe ()
  "Note the command just run (`post-command-hook')."
  (ignore-errors
    (let ((cmd this-command))
      (when (and cmd (symbolp cmd))
        (let* ((via-mx (and (memq real-this-command '(execute-extended-command execute-extended-command-for-buffer))
                            (not (eq cmd real-this-command))))
               (key (unless via-mx (byj--own-key cmd)))
               (last (car byj--observed)))
          (if (and last (eq (aref last 0) cmd) (equal (aref last 1) key) (eq (aref last 4) via-mx))
              (aset last 3 (1+ (aref last 3)))
            (push (vector cmd key (symbol-name major-mode) 1 via-mx (float-time)) byj--observed)
            (setq byj--observed-n (1+ byj--observed-n))
            (when (> byj--observed-n byj-observe-size)
              (setcdr (nthcdr (1- byj-observe-size) byj--observed) nil)
              (setq byj--observed-n byj-observe-size))))))))

;;;###autoload
(define-minor-mode byj-observe-mode
  "Let byj see which commands you use, to suggest better ones (/emacs tips,
and an Emacs lesson's checks). It keeps command names, the keys bound to
them, the major mode and whether M-x ran them, in this Emacs's memory only:
never what you type, never on disk. Turning it off forgets it all."
  :global t :group 'byj :lighter " byj👁"
  (if byj-observe-mode
      (progn (setq byj--observe-since (float-time))
             (add-hook 'post-command-hook #'byj--observe))
    (remove-hook 'post-command-hook #'byj--observe)
    (setq byj--observed nil byj--observed-n 0 byj--observe-since nil)))

(defun byj--tip-used (tip)
  "How many times TIP's command ran since the tip was given."
  (let ((cmd (intern-soft (alist-get 'command tip))) (n 0))
    (when cmd
      (dolist (e byj--observed)
        (when (and (eq (aref e 0) cmd) (> (aref e 5) (alist-get 'at tip)))
          (setq n (+ n (aref e 3))))))
    n))

(defun byj--tips-said ()
  (vconcat (mapcar (lambda (tip) `((command . ,(alist-get 'command tip))
                                   (why . ,(alist-get 'why tip))
                                   (minutes-ago . ,(round (/ (- (float-time) (alist-get 'at tip)) 60)))
                                   (used-since . ,(if byj-observe-mode (byj--tip-used tip) nil))))
                   byj--tips)))

(defun byj-op-habits (args)
  "What `byj-observe-mode' saw, summarised: commands by count with the keys
used and bound, runs a better command replaces, bound commands run through
M-x; and the tips given, with whether each was taken up. With TIP (a
command) and WHY, record a tip instead."
  (cond
   ((alist-get 'tip args)
    (let ((cmd (alist-get 'tip args)))
      (unless (fboundp (intern-soft cmd)) (error "No command %s in this Emacs" cmd))
      (push `((command . ,cmd) (why . ,(alist-get 'why args)) (at . ,(float-time))) byj--tips)
      `((tips . ,(length byj--tips)))))
   ((not byj-observe-mode)
    `((on . :json-false)
      (note . "byj-observe-mode is off: nothing is observed. The person turns it on with M-x byj-observe-mode; it keeps command names in their Emacs's memory only, never what they type.")
      (tips . ,(byj--tips-said))))
   (t
    (let ((counts (make-hash-table :test 'eq))
          (used (make-hash-table :test 'eq))
          (mx (make-hash-table :test 'eq))
          (runs (make-hash-table :test 'eq))
          (total 0))
      (dolist (e byj--observed)
        (let ((cmd (aref e 0)) (n (aref e 3)))
          (setq total (+ total n))
          (puthash cmd (+ n (gethash cmd counts 0)) counts)
          (when (aref e 1) (puthash cmd (delete-dups (cons (aref e 1) (gethash cmd used))) used))
          (when (aref e 4) (puthash cmd (+ n (gethash cmd mx 0)) mx))
          (when (>= n byj-run-length)
            (let ((r (gethash cmd runs '(0 . 0))))
              (puthash cmd (cons (1+ (car r)) (max n (cdr r))) runs)))))
      (with-current-buffer (window-buffer (byj--window))
        (let (rows run-rows mx-rows)
          (maphash (lambda (cmd n) (unless (string-match-p "self-insert" (symbol-name cmd)) (push (cons cmd n) rows))) counts)
          (setq rows (seq-take (sort rows (lambda (a b) (> (cdr a) (cdr b)))) 30))
          (maphash (lambda (cmd r) (push `((command . ,(symbol-name cmd)) (runs . ,(car r)) (longest . ,(cdr r))) run-rows)) runs)
          (maphash (lambda (cmd n)
                     (let ((keys (byj--keys-for cmd)))
                       (when keys (push `((command . ,(symbol-name cmd)) (times . ,n) (keys . ,(vconcat keys))) mx-rows))))
                   mx)
          `((on . t)
            (minutes . ,(round (/ (- (float-time) (or byj--observe-since (float-time))) 60)))
            (commands-run . ,total)
            (top . ,(vconcat (mapcar (lambda (r) `((command . ,(symbol-name (car r))) (times . ,(cdr r))
                                                    (keys-used . ,(vconcat (gethash (car r) used)))
                                                    (keys . ,(vconcat (byj--keys-for (car r))))
                                                    (via-mx . ,(gethash (car r) mx 0))))
                                     rows)))
            (runs . ,(vconcat run-rows))
            (mx-with-keys . ,(vconcat mx-rows))
            (tips . ,(byj--tips-said)))))))))

;; What a command is, put in front of the person with a button to run it: the
;; keys are this Emacs's, looked up here, never the agent's word for them

(defvar byj-howto-buffer "*byj-howto*" "Where `emacs$suggest' shows a command.")

(defun byj-op-suggest (args)
  "Show COMMAND in `byj-howto-buffer' with WHY, its keys in the buffer the
person is in, its doc line, and a button that runs it there."
  (let* ((name (or (alist-get 'command args) (error "No command")))
         (cmd (intern-soft name))
         (win (byj--window)))
    (unless (and cmd (commandp cmd)) (error "No command %s in this Emacs" name))
    (let ((keys (with-current-buffer (window-buffer win) (byj--keys-for cmd)))
          (b (get-buffer-create byj-howto-buffer)))
      (with-current-buffer b
        (let ((inhibit-read-only t))
          (erase-buffer)
          (special-mode)
          (let ((inhibit-read-only t))
            (insert (propertize (or (alist-get 'why args) name) 'face 'bold) "\n\n")
            (insert "  Command  " (propertize name 'face 'font-lock-function-name-face) "\n")
            (insert "  Keys     " (if keys (string-join keys ", ") (format "none here: M-x %s" name)) "\n")
            (when-let ((d (byj--doc-line cmd))) (insert "  What     " d "\n"))
            (insert "\n  ")
            (insert-text-button (format "Run %s" name)
                                'action (lambda (_)
                                          (when (window-live-p win) (select-window win))
                                          (call-interactively cmd))
                                'follow-link t)
            (insert "  — or press the keys yourself, which is how it sticks\n"))
          (goto-char (point-min))))
      (display-buffer b '(display-buffer-in-side-window (side . bottom) (window-height . fit-window-to-buffer)))
      `((command . ,name) (keys . ,(vconcat keys)) (buffer . ,byj-howto-buffer)))))

;;; --- set up, and where the person has been (phase 10) ----------------------------

(defvar eglot-server-programs)

(defun byj--eglot-server (mode)
  "The language server eglot would start for MODE, as written in
`eglot-server-programs', or nil."
  (when (require 'eglot nil t)
    (let ((entry (seq-find (lambda (e)
                             (let ((k (car e)))
                               (if (listp k)
                                   (memq mode (mapcar (lambda (x) (if (consp x) (car x) x)) k))
                                 (eq k mode))))
                           eglot-server-programs)))
      (and entry (cdr entry)))))

(defun byj-op-features (args)
  "What this Emacs has, to set it up: its version and init file; for each of
FEATURES whether it is loaded or loadable, for each of PACKAGES whether it
is installed, for each of PROGRAMS where it is on `exec-path'; with FILE, its
buffer's major mode, the server eglot would start for it and whether that is
on `exec-path'. Loads nothing but eglot's table of servers."
  (let ((file (alist-get 'file args)))
    (append
     `((emacs . ,emacs-version)
       (init-file . ,user-init-file)
       (package-archives . ,(vconcat (mapcar #'car (bound-and-true-p package-archives))))
       (features . ,(vconcat (mapcar (lambda (f)
                                       (let ((sym (intern f)))
                                         `((name . ,f) (loaded . ,(byj--bool (featurep sym)))
                                           (loadable . ,(byj--bool (locate-library f))))))
                                     (alist-get 'features args))))
       (packages . ,(vconcat (mapcar (lambda (p)
                                       `((name . ,p)
                                         (installed . ,(byj--bool (and (fboundp 'package-installed-p)
                                                                       (progn (require 'package) (package-installed-p (intern p))))))
                                         (built-in . ,(byj--bool (and (fboundp 'package-built-in-p) (package-built-in-p (intern p)))))))
                                     (alist-get 'packages args))))
       (programs . ,(vconcat (mapcar (lambda (p) `((name . ,p) (path . ,(executable-find p))))
                                     (alist-get 'programs args)))))
     (when file
       (with-current-buffer (byj--file-buffer file)
         (let* ((server (byj--eglot-server major-mode))
                (program (and (consp server) (stringp (car server)) (car server))))
           `((mode . ,(symbol-name major-mode))
             (eglot-server . ,(and server (format "%S" server)))
             (eglot-server-found . ,(byj--bool (and program (executable-find program))))
             (eglot-on . ,(byj--bool (bound-and-true-p eglot--managed-mode))))))))))

(defvar byj-trail-size 10 "The most places `@trail' names.")

(defun byj-op-places (args)
  "With VISIBLE, each window on the person's frame: its file or buffer, the
lines it shows, the cursor's line, and which one they are in. With TRAIL,
the files they visited most recently, each at its cursor's line."
  (let ((sel (byj--window)))
    (append
     (when (alist-get 'visible args)
       `((visible
          . ,(vconcat
              (mapcar (lambda (w)
                        (with-current-buffer (window-buffer w)
                          `((file . ,(buffer-file-name)) (buffer . ,(buffer-name))
                            (start . ,(line-number-at-pos (window-start w)))
                            (end . ,(line-number-at-pos (max (window-start w) (1- (window-end w t)))))
                            (line . ,(line-number-at-pos (window-point w)))
                            (selected . ,(byj--bool (eq w sel))))))
                      (window-list (window-frame sel) 'no-minibuf))))))
     (when (alist-get 'trail args)
       `((trail
          . ,(vconcat
              (mapcar (lambda (b)
                        (with-current-buffer b
                          `((file . ,(buffer-file-name))
                            (line . ,(line-number-at-pos))
                            (minutes-ago . ,(and buffer-display-time
                                                 (round (/ (float-time (time-subtract nil buffer-display-time)) 60)))))))
                      (seq-take (seq-filter #'buffer-file-name (buffer-list)) byj-trail-size)))))))))

(defconst byj-ops
  '((status . byj-op-status)
    (context . byj-op-context)
    (read . byj-op-read)
    (show . byj-op-show)
    (open . byj-op-open)
    (revert . byj-op-revert)
    (eval . byj-op-eval)
    (org-outline . byj-op-org-outline)
    (org-tangle-targets . byj-op-org-tangle-targets)
    (org-tangle . byj-op-org-tangle)
    (org-execute . byj-op-org-execute)
    (note . byj-op-note)
    (notes-clear . byj-op-notes-clear)
    (edit . byj-op-edit)
    (hello . byj-op-hello)
    (xref . byj-op-xref)
    (diagnostics . byj-op-diagnostics)
    (describe . byj-op-describe)
    (turn . byj-op-turn)
    (habits . byj-op-habits)
    (suggest . byj-op-suggest)
    (features . byj-op-features)
    (places . byj-op-places))
  "What byj may call, by name.")

(defun byj-op-hello (_args)
  "Nothing but being called: `byj-call' remembers the session that did, so
the person's commands know which session to ask (phase 4)."
  `((server . ,(and (boundp 'server-name) server-name))))

(defun byj-call (op args-file out-file)
  "Run byj's OP on the arguments in ARGS-FILE and write its answer to
OUT-FILE: {\"ok\": value} or {\"error\": why}. Remembers which byj session
asked, for the project it asked from."
  (condition-case err
      (let* ((args (byj--read-args args-file))
             (meta (alist-get '_byj args))
             (fn (or (alist-get op byj-ops) (error "byj.el has no op %s" op))))
        ;; a byj with no session (a one-off ask) leaves the one remembered
        (when (and (alist-get 'project meta) (stringp (alist-get 'session meta)))
          (puthash (alist-get 'project meta) (alist-get 'session meta) byj--sessions))
        (byj--write out-file `((ok . ,(funcall fn args)))))
    (error (byj--write out-file `((error . ,(error-message-string err))))))
  t)

;;; --- the person asks byj (phase 4) ----------------------------------------------
;;
;; Each command is a turn of the project's live byj session, sent with
;;
;;   byj -C DIR ask --attach SESSION --json -t SECS -q QUESTION
;;
;; as an async process, so Emacs never waits; the answer is shown in `byj-buffer'
;; and is in the session's transcript, as every turn over the session channel is
;; (RFC-031). The session is the one byj last called this Emacs from for the
;; project (`byj--sessions'), else one `byj session list --live --json' names.
;; Nothing is bound globally: bind `byj-command-map' where you like, e.g.
;;
;;   (global-set-key (kbd "C-c b") 'byj-command-map)

(defgroup byj nil
  "byj's agent, asked from Emacs."
  :group 'tools)

(defcustom byj-program "byj"
  "The byj program the commands run."
  :type 'string)

(defcustom byj-answer-timeout 300
  "Seconds a question may take before byj gives up on it: byj ask's -t."
  :type 'integer)

(defcustom byj-questions
  '((explain . "Explain this, from %s at line %d: what it does, and why it is written this way.\n\n%s")
    (check . "Check my work in %s (I am at line %d): what is wrong, what to try next, and what is right.")
    (hint . "I am stuck in %s at line %d: give me the next hint, one step further than the last.")
    (review . "I reviewed what turn %d changed (%s; `%s` shows it).%s\n\nI may have reverted or changed some of it since, maybe in buffers not yet saved: read those files as they are now, through my Emacs (emacs$read) where one is open. Say what you understand from what I said, and ask before changing anything again.")
    (how . "In my Emacs, how do I %s? Find the command with emacs$describe, put it in front of me with emacs$suggest, tell me its key here, and let me run it myself.")
    (fix-diagnostic . "Fix this %s in %s at line %d: %s\nRead the buffer as my Emacs has it (emacs$read), make the change with emacs$edit so I see it and can undo it, and say why it was wrong.")
    (do-region . "In %s, lines %d to %d: %s\nChange only that, in my buffer with emacs$edit (I keep it by saving, or undo it), and say what you changed.\n\n%s")
    (review-unsaved . "Review my unsaved changes in %s: compare the buffer as my Emacs has it (emacs$read) with the file on disk (read-file), and leave what you find as notes on the lines (emacs$note). Change nothing.")
    (todo-at-point . "Do this, from %s at line %d: %s")
    (todo-done . "I did this step of the checklist %s: %s. Check it if you can, and say what is next.")
    (explain-error . "Explain this error from my Emacs's %s: what went wrong, why, and what I can do about it. Before naming a key or a command, check it in my Emacs with emacs$describe.\n\n%s")
    (here . " @here"))
  "What each command asks, with the file and line put in. `here' is added
when byj reaches this Emacs, so the turn is also told what the person is
looking at; otherwise the question carries the place alone."
  :type '(alist :key-type symbol :value-type string))

(defvar byj-buffer "*byj*"
  "Where the answers are shown.")

(defun byj--question (key &rest args)
  (apply #'format (or (alist-get key byj-questions) (error "No byj question %s" key)) args))

(defun byj--project-entry ()
  "(DIR . SESSION): the session byj last called from for the project holding
`default-directory', the deepest such project; nil when none has."
  (let ((here (file-name-as-directory (file-truename default-directory)))
        best)
    (maphash (lambda (dir sid)
               (let ((d (file-name-as-directory (file-truename dir))))
                 (when (and (stringp sid) (not (string-empty-p sid))
                            (string-prefix-p d here)
                            (or (null best) (> (length d) (length (car best)))))
                   (setq best (cons d sid)))))
             byj--sessions)
    best))

(defun byj--forget (dir)
  "Forget the session remembered for DIR: it is not live any more."
  (let ((d (file-name-as-directory (file-truename dir))) gone)
    (maphash (lambda (k _) (when (equal d (file-name-as-directory (file-truename k))) (push k gone)))
             byj--sessions)
    (dolist (k gone) (remhash k byj--sessions))))

(declare-function project-roots "project" (project))
(declare-function project-root "project" (project))

(defun byj--project-dir ()
  "Where to ask from when byj has not called: the project, else here."
  (file-name-as-directory
   (expand-file-name
    (or (and (fboundp 'project-current)
             (let ((p (project-current)))
               (and p (if (fboundp 'project-root) (project-root p) (car (project-roots p))))))
        default-directory))))

(defun byj--live-sessions (dir)
  "DIR's live byj sessions, as `byj session list --live --json' lists them."
  (with-temp-buffer
    (let ((default-directory dir))
      (when (eq 0 (call-process byj-program nil '(t nil) nil "-C" dir "session" "list" "--live" "--json"))
        (goto-char (point-min))
        (condition-case nil
            (append (json-parse-buffer :object-type 'alist :array-type 'list
                                       :null-object nil :false-object nil)
                    nil)
          (error nil))))))

(defun byj--pick-session (dir)
  "A live session of DIR's: the only one, or the one the person picks."
  (let* ((rows (byj--live-sessions dir))
         (ids (mapcar (lambda (r)
                        (let ((id (alist-get 'session-id r)) (label (alist-get 'label r)))
                          (cons (if label (format "%s  %s" id label) id) id)))
                      rows)))
    (cond ((null ids) (user-error "No live byj session in %s: start one there with byj run" dir))
          ((null (cdr ids)) (cdar ids))
          (t (cdr (assoc (completing-read "byj session: " ids nil t) ids))))))

(defun byj--show (heading text)
  "Add HEADING and TEXT to `byj-buffer' and show it."
  (let ((b (get-buffer-create byj-buffer)))
    (with-current-buffer b
      (unless (derived-mode-p 'special-mode) (special-mode))
      (let ((inhibit-read-only t))
        (goto-char (point-max))
        (unless (bobp) (insert "\n"))
        (insert (propertize heading 'face 'bold) "\n")
        (when text (insert text "\n"))))
    (display-buffer b)
    b))

(defun byj--send (question &optional dir session retried)
  "Ask QUESTION as a turn of the live session; the answer goes to `byj-buffer'.
DIR and SESSION are the project and session, found when not given; RETRIED
is set on the one retry after a remembered session turned out not to be live."
  (let* ((entry (and (not session) (byj--project-entry)))
         (dir (or dir (car entry) (byj--project-dir)))
         (session (or session (cdr entry) (byj--pick-session dir)))
         (out (generate-new-buffer " *byj-answer*"))
         (err (generate-new-buffer " *byj-stderr*"))
         (default-directory dir))
    (unless retried (byj--show (concat "› " question) nil))
    (make-process
     :name "byj-ask" :buffer out :stderr err :noquery t :connection-type 'pipe
     :command (list byj-program "-C" dir "ask" "--attach" session "--json"
                    "-t" (number-to-string byj-answer-timeout) "-q" question)
     :sentinel (lambda (proc _event)
                 (when (memq (process-status proc) '(exit signal))
                   (byj--answered proc err question dir session retried))))
    (message "byj: asked %s" session)
    session))

(defun byj--gist (question)
  "QUESTION's first line, short: which question an answer is to."
  (truncate-string-to-width (car (split-string question "\n")) 60 nil nil "…"))

(defun byj--answered (proc err question dir session retried)
  "Show what PROC, a byj ask, answered; once, retry a session not live."
  (let* ((a (with-current-buffer (process-buffer proc)
              (goto-char (point-min))
              (condition-case nil
                  (json-parse-buffer :object-type 'alist :null-object nil :false-object nil)
                (error nil))))
         (why (or (alist-get 'error a)
                  (string-trim (with-current-buffer err (buffer-string)))
                  "")))
    (kill-buffer (process-buffer proc))
    (when (buffer-live-p err)
      (when (get-buffer-process err) (delete-process (get-buffer-process err)))
      (kill-buffer err))
    (cond
     ((and a (alist-get 'success a))
      (byj--show (format "‹ %s · %s" session (byj--gist question)) (or (alist-get 'answer a) "")))
     ((and (not retried) (string-match-p "no live session" why))
      (byj--forget dir)
      (byj--send question dir (byj--pick-session dir) t))
     (t (byj--show (format "‹ %s could not answer · %s" session (byj--gist question))
                   (if (string-empty-p why) (format "byj exited %s" (process-exit-status proc)) why))))))

(defun byj--place ()
  "The file (or buffer) and line the person is at."
  (list (or (buffer-file-name) (buffer-name)) (line-number-at-pos)))

(defun byj--with-here (question)
  "QUESTION, and `here' when byj reaches this Emacs, so @here reads it."
  (if (byj--project-entry) (concat question (byj--question 'here)) question))

;;;###autoload
(defun byj-ask (question)
  "Ask the project's live byj session QUESTION."
  (interactive (list (read-string "Ask byj: ")))
  (when (string-empty-p (string-trim question)) (user-error "Nothing to ask"))
  (byj--send question))

;;;###autoload
(defun byj-explain ()
  "Ask byj to explain the region, or the defun at point."
  (interactive)
  (let ((text (if (use-region-p)
                  (buffer-substring-no-properties (region-beginning) (region-end))
                (thing-at-point 'defun t)))
        (line (line-number-at-pos (if (use-region-p) (region-beginning) (point)))))
    (unless text (user-error "No region, and no defun at point"))
    (byj--send (byj--with-here (byj--question 'explain (car (byj--place)) line (byj--clip text))))))

;;;###autoload
(defun byj-check ()
  "Ask byj, coaching, to check what you wrote, as Emacs has it."
  (interactive)
  (byj--send (byj--with-here (apply #'byj--question 'check (byj--place)))))

;;;###autoload
(defun byj-hint ()
  "Ask byj, coaching, for the next hint."
  (interactive)
  (byj--send (byj--with-here (apply #'byj--question 'hint (byj--place)))))

;; byj-explain-error (phase 7): where the newest error is, in the order a
;; person would look

(defvar byj-error-lines 60
  "The most lines of an error's buffer a question carries.")

(defun byj--tail (buffer n)
  "BUFFER's last N lines, without properties."
  (with-current-buffer buffer
    (save-excursion
      (goto-char (point-max))
      (forward-line (- n))
      (buffer-substring-no-properties (point) (point-max)))))

(defun byj--head (buffer n)
  "BUFFER's first N lines, without properties."
  (with-current-buffer buffer
    (save-excursion
      (goto-char (point-min))
      (forward-line n)
      (buffer-substring-no-properties (point-min) (point)))))

(defun byj--error-sources ()
  "Where an error is to be read now, newest kind first, as ((LABEL . TEXT) ...):
a backtrace, a compilation that failed, warnings, the end of *Messages*."
  (let (out)
    (let ((b (get-buffer "*Backtrace*")))
      (when (and b (> (buffer-size b) 0))
        (push (cons "*Backtrace*" (byj--head b byj-error-lines)) out)))
    (let ((c (seq-find (lambda (b)
                         (with-current-buffer b
                           (and (derived-mode-p 'compilation-mode)
                                (not (derived-mode-p 'grep-mode))
                                (not (equal (buffer-name) (bound-and-true-p byj-notes-buffer)))
                                (save-excursion
                                  (goto-char (point-max))
                                  (re-search-backward "exited abnormally\\|with code [1-9]\\|interrupt" nil t)))))
                       (buffer-list))))
      (when c (push (cons (buffer-name c) (byj--tail c byj-error-lines)) out)))
    (let ((w (get-buffer "*Warnings*")))
      (when (and w (> (buffer-size w) 0))
        (push (cons "*Warnings*" (byj--tail w byj-error-lines)) out)))
    (push (cons "*Messages*" (byj--tail (messages-buffer) 30)) out)
    (nreverse out)))

;;;###autoload
(defun byj-explain-error (&optional choose)
  "Ask byj what the newest error means: a backtrace, a failed compilation,
warnings, else the end of *Messages*. With a prefix argument, CHOOSE which."
  (interactive "P")
  (let* ((sources (byj--error-sources))
         (s (if (and choose (cdr sources))
                (assoc (completing-read "Explain the error in: " (mapcar #'car sources) nil t) sources)
              (car sources))))
    (byj--send (byj--question 'explain-error (car s) (byj--clip (string-trim (cdr s)))))))

;;;###autoload
(defun byj-how (what)
  "Ask byj how to do WHAT in this Emacs: it finds the command and shows it,
with its key here and a button, for you to run."
  (interactive (list (read-string "How do I… ")))
  (when (string-empty-p (string-trim what)) (user-error "Nothing to ask"))
  (byj--send (byj--with-here (byj--question 'how (string-trim what)))))

;; Work handed over from where it is (phase 10)

(declare-function flymake-diagnostics "flymake" (&optional beg end))
(declare-function flymake-diagnostic-text "flymake" (diag))
(declare-function flymake-diagnostic-type "flymake" (diag))
(declare-function flycheck-overlay-errors-at "ext:flycheck" (pos))

(defun byj--diagnostic-here ()
  "(SEVERITY . TEXT) of the diagnostic at point, or on its line; nil if none."
  (cond
   ((bound-and-true-p flymake-mode)
    (let ((d (or (car (flymake-diagnostics (point)))
                 (car (flymake-diagnostics (line-beginning-position) (line-end-position))))))
      (and d (cons (byj--severity (flymake-diagnostic-type d)) (flymake-diagnostic-text d)))))
   ((bound-and-true-p flycheck-mode)
    (let ((e (car (flycheck-overlay-errors-at (point)))))
      (and e (cons (format "%s" (flycheck-error-level e)) (flycheck-error-message e)))))))

;;;###autoload
(defun byj-fix-diagnostic ()
  "Ask byj to fix the error or warning at point, as a change you can undo."
  (interactive)
  (let ((d (or (byj--diagnostic-here) (user-error "No diagnostic at point"))))
    (byj--send (byj--with-here (byj--question 'fix-diagnostic (car d) (car (byj--place)) (line-number-at-pos) (cdr d))))))

;;;###autoload
(defun byj-do-region (what)
  "Ask byj to do WHAT to the region, as a change in this buffer you can undo."
  (interactive (progn (unless (use-region-p) (user-error "No region"))
                      (list (read-string "Do what with the region? "))))
  (when (string-empty-p (string-trim what)) (user-error "Nothing asked"))
  (let ((a (line-number-at-pos (region-beginning)))
        (b (line-number-at-pos (region-end)))
        (text (buffer-substring-no-properties (region-beginning) (region-end))))
    (byj--send (byj--question 'do-region (car (byj--place)) a b (string-trim what) (byj--clip text)))))

;;;###autoload
(defun byj-review-unsaved ()
  "Ask byj to review this buffer's unsaved edits, as notes on the lines."
  (interactive)
  (unless (buffer-modified-p) (user-error "No unsaved edits here"))
  (byj--send (byj--question 'review-unsaved (car (byj--place)))))

;;;###autoload
(defun byj-todo-at-point ()
  "Hand byj the TODO at point: a TODO or FIXME comment, or an org TODO heading."
  (interactive)
  (let ((line (string-trim (buffer-substring-no-properties (line-beginning-position) (line-end-position)))))
    (unless (string-match-p "\\(TODO\\|FIXME\\)" line) (user-error "No TODO on this line"))
    (byj--send (byj--with-here (byj--question 'todo-at-point (car (byj--place)) (line-number-at-pos) line)))))

;; The session's checklist (RFC-023's todo file, the /todo and coaching
;; checklists) is a markdown file under the project's .brainyard/todos/: it is
;; opened as it is, kept in step with the disk, and a step ticked there tells
;; byj. The file is the one source; no copy of it is kept here.

(defvar-local byj--todo-dir nil "The project whose checklist this buffer is.")

(defvar byj-todo-mode-map
  (let ((m (make-sparse-keymap)))
    (define-key m (kbd "C-c C-c") #'byj-todo-toggle)
    m))

(define-minor-mode byj-todo-mode
  "A byj checklist: \\<byj-todo-mode-map>\\[byj-todo-toggle] ticks the step at point, saves, and tells byj."
  :lighter " byj-todo"
  (when byj-todo-mode (auto-revert-mode 1)))

;;;###autoload
(defun byj-todo ()
  "Show the project's byj checklist — the newest, or the one you pick — beside
your work, kept in step with what byj writes to it."
  (interactive)
  (let* ((dir (or (car (byj--project-entry)) (byj--project-dir)))
         (todos (expand-file-name ".brainyard/todos" dir))
         (files (and (file-directory-p todos)
                     (sort (directory-files todos t "\\.md\\'")
                           (lambda (a b) (time-less-p (file-attribute-modification-time (file-attributes b))
                                                      (file-attribute-modification-time (file-attributes a)))))))
         (file (cond ((null files) (user-error "No byj checklist in %s yet" dir))
                     ((or (null (cdr files)) (not current-prefix-arg)) (car files))
                     (t (expand-file-name (completing-read "Checklist: " (mapcar #'file-name-nondirectory files) nil t) todos))))
         (b (find-file-noselect file)))
    (with-current-buffer b
      (setq byj--todo-dir dir)
      (byj-todo-mode 1))
    (display-buffer b '(display-buffer-in-side-window (side . right) (window-width . 0.3)))
    b))

(defun byj-todo-toggle ()
  "Tick the step at point (or untick it), save, and tell byj a step was done."
  (interactive)
  (save-excursion
    (beginning-of-line)
    (cond
     ((re-search-forward "^\\(\\s-*- \\[\\) \\]" (line-end-position) t)
      (replace-match "\\1x]")
      (save-buffer)
      (let ((step (string-trim (replace-regexp-in-string "^\\s-*- \\[x\\]\\s-*" "" (buffer-substring-no-properties (line-beginning-position) (line-end-position))))))
        (byj--send (byj--question 'todo-done (file-name-base (buffer-file-name)) step) byj--todo-dir)))
     ((re-search-forward "^\\(\\s-*- \\[\\)x\\]" (line-end-position) t)
      (replace-match "\\1 ]")
      (save-buffer)
      (message "byj: unticked"))
     (t (user-error "No step on this line")))))

;;;###autoload
(defun byj-notes ()
  "Show byj's notes; M-g n and M-g p walk them."
  (interactive)
  (byj--notes-refresh)
  (pop-to-buffer byj-notes-buffer))

;;;###autoload
(defun byj-notes-clear (&optional all)
  "Take away byj's notes in this file; with a prefix argument, ALL of them."
  (interactive "P")
  (let ((r (byj-op-notes-clear (unless (or all (null (buffer-file-name)))
                                 `((file . ,(buffer-file-name)))))))
    (message "byj: %d notes taken away, %d left" (alist-get 'cleared r) (alist-get 'notes r))))

;;; --- the agent's change, reviewed (phase 8) ---------------------------------------
;;
;; byj snapshots the project's git work tree as each turn begins and ends —
;; commit objects made through an index of its own, HEAD, the index and the
;; stash untouched — and keeps a turn that changed files as the ref
;; refs/byj/turns/SESSION/TURN, whose parent is the turn's start (RFC-067
;; D10). A turn's change is git's to show, whatever wrote it: `byj-review'
;; opens it in magit (`magit-diff-range'), or in vc's diff where there is no
;; magit, and their own keys revert, stage and commit. `byj-review-mode' adds
;; two: say something about the line at point, and send what was said back as
;; the next turn.

(declare-function vc-diff-internal "vc")
(declare-function diff-hunk-file-names "diff-mode")

(defcustom byj-review-with-magit t
  "Whether `byj-review' shows a turn's change in magit when magit is there;
else, or when nil, in vc's diff."
  :type 'boolean :group 'byj)

(defvar byj-review-buffer "*byj-review*"
  "Where vc's diff of a turn goes, when magit does not show it.")

(defvar-local byj--review nil
  "(DIR SESSION TURN BEFORE AFTER) of the turn this buffer shows.")
(put 'byj--review 'permanent-local t)

(defvar byj--review-said nil
  "What the person said in reviews, newest first:
\((SESSION . TURN) FILE HUNK LINE TEXT).")

(defvar byj-review-mode-map
  (let ((m (make-sparse-keymap)))
    (define-key m (kbd "C-c ;") #'byj-review-comment)
    (define-key m (kbd "C-c '") #'byj-review-send)
    m)
  "Keys of `byj-review-mode', clear of magit's and diff-mode's own.")

(define-minor-mode byj-review-mode
  "A turn of byj's, shown by magit or vc. \\<byj-review-mode-map>\\[byj-review-comment] says something about
the line at point; \\[byj-review-send] sends what you said to byj as the next turn.
Reverting, staging and committing are magit's or vc's own keys."
  :lighter " byj-review" :keymap byj-review-mode-map)
(put 'byj-review-mode 'permanent-local t)

(defun byj--changes (dir session turn)
  "What `byj emacs changes' says of SESSION's TURN (its last that changed
files when nil), an alist, or nil when byj said nothing it could read."
  (with-temp-buffer
    (let ((default-directory dir))
      (apply #'call-process byj-program nil '(t nil) nil
             (append (list "-C" dir "emacs" "changes" session)
                     (and turn (list (number-to-string turn)))
                     (list "--json"))))
    (goto-char (point-min))
    (condition-case nil
        (json-parse-buffer :object-type 'alist :array-type 'list :null-object nil :false-object nil)
      (error nil))))

(defun byj--review-show (root before after)
  "Show the change from BEFORE to AFTER in the repository at ROOT, in magit
or vc's diff; the buffer."
  (let ((default-directory root))
    (if (and byj-review-with-magit
             (or (featurep 'magit) (require 'magit nil t))
             (fboundp 'magit-diff-range))
        (let ((b (magit-diff-range (format "%s..%s" before after))))
          (if (bufferp b) b (current-buffer)))
      (require 'vc)
      (require 'vc-git)
      (let ((b (get-buffer-create byj-review-buffer)))
        (vc-diff-internal nil (list 'Git (list root)) before after nil b)
        (with-current-buffer b (setq default-directory root))
        (pop-to-buffer b)
        b))))

;;;###autoload
(defun byj-review (&optional turn)
  "Show what the last turn of the project's byj session changed — with a
prefix argument, ask which TURN — in magit or vc, to revert or comment on
and send back."
  (interactive (list (and current-prefix-arg (read-number "Review turn: "))))
  (let* ((entry (byj--project-entry))
         (dir (or (car entry) (byj--project-dir)))
         (session (or (cdr entry) (byj--pick-session dir)))
         (a (byj--changes dir session turn)))
    (cond
     ((null a) (user-error "byj could not say what %s changed" session))
     ((alist-get 'error a) (user-error "%s" (alist-get 'error a)))
     ((not (stringp (alist-get 'after a)))
      (user-error "byj's reply about %s names no snapshot: is `%s' (%s) older than this byj.el?"
                  session byj-program (or (executable-find byj-program) "not on PATH")))
     (t
      (let* ((before (alist-get 'before a))
             (after (alist-get 'after a))
             (files (length (alist-get 'files a)))
             (b (byj--review-show (file-name-as-directory (alist-get 'root a)) before after)))
        (with-current-buffer b
          (setq byj--review (list dir session (alist-get 'turn a) before after))
          (byj-review-mode 1))
        (message "byj: turn %s of %s, %d file%s (%s) — C-c ; says something about a line, C-c ' sends it"
                 (alist-get 'turn a) session files (if (= files 1) "" "s") (alist-get 'ref a))
        b)))))

(defun byj--review-place ()
  "(FILE HUNK LINE) at point in a diff, magit's or diff-mode's."
  (save-excursion
    (let ((line (string-trim-right (buffer-substring-no-properties (line-beginning-position) (line-end-position))))
          (file (or (and (fboundp 'magit-file-at-point) (ignore-errors (magit-file-at-point)))
                    (and (derived-mode-p 'diff-mode)
                         (ignore-errors (car (diff-hunk-file-names))))))
          (hunk (and (re-search-backward "^@@ .* @@" nil t) (match-string-no-properties 0))))
      (list (and file (replace-regexp-in-string "\\`[ab]/" "" file)) hunk line))))

(defun byj-review-comment (text)
  "Say TEXT about the line at point, for `byj-review-send'."
  (interactive (list (read-string "About this line: ")))
  (unless byj--review (user-error "Not a byj review: M-x byj-review shows a turn"))
  (when (string-empty-p (string-trim text)) (user-error "Nothing said"))
  (pcase-let ((`(,_ ,session ,turn) byj--review))
    (push (append (list (cons session turn)) (byj--review-place) (list text)) byj--review-said))
  (message "byj: noted, %s sends it" (substitute-command-keys "\\<byj-review-mode-map>\\[byj-review-send]")))

(defun byj-review-send ()
  "Send what you said in this review to byj, as the next turn of the
session whose turn it was."
  (interactive)
  (unless byj--review (user-error "Not a byj review: M-x byj-review shows a turn"))
  (pcase-let* ((`(,dir ,session ,turn ,before ,after) byj--review)
               (key (cons session turn))
               (said (reverse (seq-filter (lambda (x) (equal (car x) key)) byj--review-said))))
    (unless said (user-error "Nothing said yet: C-c ; says something about the line at point"))
    (let ((files (delete-dups (delq nil (mapcar #'cadr said))))
          (lines (mapconcat
                  (lambda (x)
                    (pcase-let ((`(,_ ,file ,hunk ,line ,text) x))
                      (format "\n\nAbout `%s` in %s%s: %s" line (or file "the diff")
                              (if hunk (format " (%s)" hunk) "") text)))
                  said "")))
      (byj--send (byj--question 'review turn (string-join files ", ")
                                (format "git diff %s %s" before after) lines)
                 dir session)
      (setq byj--review-said (seq-remove (lambda (x) (equal (car x) key)) byj--review-said)))))

(defvar byj-command-map
  (let ((m (make-sparse-keymap)))
    (define-key m "a" #'byj-ask)
    (define-key m "e" #'byj-explain)
    (define-key m "c" #'byj-check)
    (define-key m "h" #'byj-hint)
    (define-key m "n" #'byj-notes)
    (define-key m "N" #'byj-notes-clear)
    (define-key m "x" #'byj-explain-error)
    (define-key m "r" #'byj-review)
    (define-key m "?" #'byj-how)
    (define-key m "o" #'byj-observe-mode)
    (define-key m "d" #'byj-fix-diagnostic)
    (define-key m "w" #'byj-do-region)
    (define-key m "u" #'byj-review-unsaved)
    (define-key m "t" #'byj-todo-at-point)
    (define-key m "l" #'byj-todo)
    m)
  "byj's commands, to bind under a prefix of your own:
\(global-set-key (kbd \"C-c b\") \\='byj-command-map).")
(fset 'byj-command-map byj-command-map)

(provide 'byj)
;;; byj.el ends here
