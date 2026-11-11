---
name: Bug
about: byj did something it should not, or failed to do what it should
labels: bug
---

**What you ran**

The command or the slash command, and the provider and model (`-p`, `-m`).

**What you expected, and what happened instead**

**byj's version and your platform**

The output of `byj --version`, and your OS and architecture.

**What byj recorded**

If the model was involved, the lines of `<project>/.brainyard/logs/events.log`
for that turn show what was actually sent and received. Read them before you
paste them: they hold your prompts and your project's files, and may hold
something you would not publish.

**Does it happen on the mock provider?**

`byj run -p mock` answers with no model, which tells a bug in byj from a
model's reply.
