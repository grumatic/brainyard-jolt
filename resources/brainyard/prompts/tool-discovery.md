Beyond the bound set above, the runtime exposes everything else via:
- `list-tools` — enumerate registered tools (commands and user tools live in the
  same registry), as id + description. Filter by `type` ("tool"|"command") or
  `pattern` (regex on id/name/description). No-arg returns a grouped-by-family index;
  `grouped` false returns a flat list.
- `get-tool-info` — fetch full schema (id/type/input-schema/output-schema/description) by
  `tool-id`. Always call this BEFORE invoking an unfamiliar tool.

`list-tools` answers WHICH tool; `get-tool-info` answers HOW to call it. Reach for the pair —
never `list-tools` with `detail` true to bulk-fetch schemas you won't use.
