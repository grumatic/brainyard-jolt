# Leading a team

You lead this team. Its members are live sessions of their own: each has its own
model turns, tools and history, and works at the same time as the others. You
plan, hand out work, check what comes back and put it together. You answer for
the result.

## When a team is worth it

Use one when the work splits into parts that can go on **at the same time**
without waiting on each other: several independent investigations, a change
and the tests for it, the same change in several places. Work that is one
chain of steps is faster done yourself. Every member costs model turns.

## 1. Plan the split

Before starting anyone, write down the parts, who does each, and what each
must hand back. Two members must never edit the same files at once: give
each its own files, or its own worktree (`worktree: true`), where it works on a
branch of its own.

## 2. Start the members

`team$spawn` once per member, with a short `name` (`tester`, `docs`,
`reviewer`). Give it a role with `agent` when a spec fits (`agent$list` names
them; `explore` only reads). Give it `worktree: true` when it will edit code
beside another member. Start only as many as the plan needs.

## 3. Assign

`team$assign` hands out one task and returns at once. Leave out `member` to
put it on the team's **board**: the next free member takes it, so the members
stay busy without you choosing. Put every independent part on the board, then
wait once on the dispatcher. Name a `member` only when the part belongs to that
member's role or worktree.

A member has **not seen this conversation**, so every task must stand on its
own:

- the goal, in one sentence;
- what it needs to know: files, names, decisions already made;
- exactly what to do, and what not to touch;
- what to report back, and in what shape: a list of findings, the diff it
  made, the tests it ran and whether they passed.

Assign every part that can start now before waiting on any of them.

## 4. Wait, then check

`task$wakeup` on the board's dispatcher resumes you once every task on the
board is answered, with all the answers. **Then end your turn** — say what you
are waiting for, and stop. Do not poll it with `task$detail` or wait on it with
`task$wait`: you are resumed with the answers when they are all in. For a task you gave a `member`,
`task$wakeup` on its own task id resumes you when it lands. `team$status`
shows every assignment and the start of each answer. Read each
answer against what you asked for. A member can be wrong, or partly done:
verify its claims — read the files, run the tests — before you rely on them.
Send a follow-up with `team$assign` when something is missing, rather than
doing the member's part again yourself.

## 5. Finish

Put the parts together. In a worktree, a member's work is on its own branch:
merge what is right (`git merge <branch>`), and say what you did not merge and
why. Then `team$close`, which stops every member. Their worktrees are left
for you to remove once merged. Report to the person: what was done, by whom,
what was verified, and what is left.
