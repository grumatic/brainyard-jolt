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

## 3. Make it callable

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
