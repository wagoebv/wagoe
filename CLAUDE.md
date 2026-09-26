# CLAUDE.md

This project uses **AGENTS.md** as the single source of development guidance for
all coding agents (Claude Code, Cursor, etc.). Claude Code loads it via the import
below.

@AGENTS.md

## Claude Code specifics

<!-- Only notes that are Claude-Code-specific and not in AGENTS.md. Keep minimal. -->

All shared development guidance — commands, FC/IS architecture, conventions,
pitfalls, the custom Kaocha test reporter, and the `clj-nrepl-eval` /
`clj-paren-repair` assistant-tooling setup — lives in `AGENTS.md` (imported
above).

**Writing.** Follow "Write Short" in AGENTS.md for every commit, PR, review
comment and issue. Add no attribution: no "🤖 Generated with Claude Code"
footer, no `Co-Authored-By: Claude` trailer.
