**What changed, and why**

**The issue it closes or touches**

**Written with a coding agent?** Say which, or "no".

**Checked with**

- [ ] Every commit signed off (`git commit -s`)
- [ ] `jolt test:byj`
- [ ] `jolt test-serial:byj` (global state, or a large change)
- [ ] `dev/cold-load.sh` (a namespace or dependency added or removed)
- [ ] `jolt test:sdk` (`byj acp` or the SDK)
- [ ] by hand, as described below (a screen, a live model)

**Kept with it**

- [ ] The RFC of every layer whose API, protocol or invariants changed
- [ ] `docs/divergences.md` and `.edn`, for an intended difference from brainyard
- [ ] `docs/provenance.md`, for a guard added because of a specific failure
- [ ] `CHANGELOG.md`'s Unreleased section, for what a person running byj notices
- [ ] For a change to a bundled loop (`resources/brainyard/loops/`): the evidence RFC-071 "Promotion to the bundled loops" asks for
