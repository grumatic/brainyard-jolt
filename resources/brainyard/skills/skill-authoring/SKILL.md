---
name: skill-authoring
description: Write a skill for byj — where its SKILL.md goes, what its frontmatter says, and how it is found and shadowed.
tags: skill, skills, authoring, SKILL.md
---

# Writing a skill

A skill is a directory holding `SKILL.md`: a procedure you follow when you call
`skill$<name>`. The prompt names every skill in one line; its body arrives only
when it is called. Write one for a procedure that is repeated and has steps
worth getting right, not for a fact.

## 1. Pick the name and the place

- The directory name is the skill's name: lowercase-kebab, such as
  `deploy-staging`.
- A project's skill goes in `<project>/.brainyard/skills/<name>/SKILL.md`; one
  for every project goes in `~/.brainyard/skills/<name>/SKILL.md`.
- Search first with `skills$find`, so you do not write a second skill for what
  one already does.

## 2. Write SKILL.md

```markdown
---
name: deploy-staging
description: Deploy the service to staging and check it answers.
tags: deploy, staging
---

# Deploy to staging

1. …
2. …
```

- `description` is what the one-line listing and `skills$find` read: say what
  the skill is FOR, in words a person asking for it would use.
- `tags` are comma-separated search words.
- `dispatch: agent` runs the procedure in a child agent that returns its answer,
  instead of reading it into this turn: for a long procedure whose steps need
  not stay in the conversation.
- The body is numbered steps, each an action you can check. Name the exact
  commands and files.

## 3. Add scripts and resources (when the procedure needs them)

A skill's directory may hold two more directories beside `SKILL.md`:

```
deploy-staging/
  SKILL.md
  scripts/     commands a step runs, such as scripts/check.sh
  resources/   files a step reads: templates, checklists, reference tables
```

- Nothing in them is loaded or run on its own. Calling `skill$<name>` returns
  `SKILL.md` and its `:path`; the body says which step uses which file, by a
  path relative to the skill's directory (the directory of `:path`).
- A script is run with `bash`. Write the step as the command, with what it
  prints and what counts as success:
  `3. Run bash scripts/check.sh <url>; it prints ok, anything else is a failure.`
  Put in a script what is easier to get right once than to type every time,
  and keep the judgement in `SKILL.md`.
- A resource is read with `read-file` by the step that needs it:
  `4. Fill in resources/report-template.md and show it.` Long reference
  material belongs here, not in `SKILL.md`, so it costs nothing until a step
  needs it.
- `skills$write` writes them with `create` or `update`: `scripts` and
  `resources`, each a map of file name to text.

  ```json
  {"op": "create", "skill-name": "deploy-staging",
   "content": "<the whole SKILL.md>",
   "scripts": {"check.sh": "<the script>"},
   "resources": {"report-template.md": "<the template>"}}
  ```

  Only a file's own name is kept, so an extra cannot land outside its skill or
  in a subdirectory. A `.sh` is made executable. `update` with only `scripts`
  or `resources` adds or replaces those files and leaves `SKILL.md` alone.
- A built-in skill carries no scripts, because an embedded file is not on disk
  for `bash` to run, and its resources cannot be opened with `read-file`. Its
  index entry (`{:name "<name>" :resources ["file" …]}` in
  `resources/brainyard/skills/index.edn`) names the files that calling it
  returns under `:resources`, file name to text. Keep them small.

## 4. Make it callable

`skills$write` with `op` `create`, `skill-name` and `content` (the whole file,
frontmatter included) writes it and registers it at once. A SKILL.md written
another way is registered by `skills$reload`. Call `skill$<name>` to check what
it says.

## Precedence and built-in skills

A name defined twice keeps the most local as `skill$<name>`: the project's,
then the user's, then the skills shipped with byj (`builtin`). The others stay
reachable as `skill$<type>$<name>`. A built-in skill, this one included, is
read-only: to change one, a person copies its SKILL.md into the project's
`.brainyard/skills/<name>/`, where it shadows the shipped one.
