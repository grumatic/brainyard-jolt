# 0002 — Errors are JSON

Status: accepted

Every error the server answers — 400, 404, 405, 500 — is JSON, with
`content-type: application/json` and the body `{"error": "<what went wrong>"}`.
Never a plain-text or HTML error page.

Why: the page and the tests read errors the same way as every other answer.
