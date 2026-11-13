# CLAUDE.md

This project uses **AGENTS.md** as the single source of development guidance for
all coding agents (Claude Code, Cursor, etc.). Claude Code loads it via the import
below.

@AGENTS.md

## Claude Code specifics

<!-- Only notes that are Claude-Code-specific and not in AGENTS.md. Keep minimal. -->

The core rules, commands and FC/IS architecture live in `AGENTS.md` (imported
above). Pitfalls, the Kaocha test reporter, the `clj-nrepl-eval` /
`clj-paren-repair` setup and the full conventions are in `dev-docs/agents/`,
which is not imported: `AGENTS.md` says when to read each file.

**Writing.** Follow "Write short" in AGENTS.md for every commit, PR, review
comment and issue. Add no attribution: no "🤖 Generated with Claude Code"
footer, no `Co-Authored-By: Claude` trailer.
