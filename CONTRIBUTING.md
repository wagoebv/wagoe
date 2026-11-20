# Contributing to Wagoe Framework

Thank you for your interest in contributing to Wagoe! This guide will help you understand our development process and how to make effective contributions.

## Quick Start for Contributors

**📖 Start Here**: Read the [Developer Guide (AGENTS.md)](./AGENTS.md) for comprehensive setup and development information.

## Development Setup

```zsh
# Prerequisites: JDK 21+, Clojure CLI, Babashka, Docker
brew install openjdk clojure/tools/clojure borkdude/brew/babashka  # macOS

git clone https://github.com/wagoebv/wagoe.git
cd wagoe
bb install-hooks            # pre-commit: FC/IS and ports; commit-msg: ticket id; pre-push to main: bb check
apm install                 # agent skills, reviewers and hooks from ai/.apm/

bb test:services up         # Redis + MySQL for the adapter sweeps (docker compose)
bb test:all                 # every test surface; PostgreSQL runs embedded
bb check                    # pre-push gate: quality gates + tests of what changed
bb check-full               # gates, CSS, bb test:all and bb e2e; CI on main adds the smokes
clojure -M:repl-clj         # development REPL (nREPL on 7888)
```

`bb check-full` takes much longer: run it when a change reaches far, or leave
it to CI on main. External contributors can skip `bb install-hooks`: its hooks
enforce the core team's flow, and CI runs the same gates on a pull request.

`apm install` needs [APM](https://github.com/microsoft/apm) 0.33 or later. It writes
`.claude/`, `.cursor/`, `.codex/` and `.agents/`, which are generated and ignored:
edit the sources in `ai/.apm/` and run it again.

`clojure -M:test` alone does not cover the standalone libraries (`wagoe-cli`,
`wagoe-mcp`, `tools`); `bb test:all` does. Run one suite with
`clojure -M:test:test/all :admin`.

### The FC/IS rules, in short

- `core/` is pure: no reads, writes, clock or mutable state, no logging yet,
  no exceptions. It
  returns typed error values (`{:error {:type … :message …}}`).
- `shell/` does the I/O, validation and side effects, and calls `core/` with
  clean data.
- Every module defines its protocols in `ports.clj`; shells depend on ports,
  never on another module's shell.
- `bb check:fcis` and `bb check:ports` enforce this, locally and in CI. See
  [ADR-021](dev-docs/adr/ADR-021-fcis-boundary-rules.adoc) and
  [the FC/IS guide](docs/modules/architecture/pages/fc-is.adoc).

## How to Contribute

### 🐛 **Bug Reports**

When reporting bugs:

- Include steps to reproduce
- Provide relevant system information (OS, JDK version, Clojure version)
- Include error messages and stack traces
- Test against the latest version

### 💡 **Feature Requests**

Before proposing new features:

- Check existing issues and PRD documentation
- Ensure alignment with [Functional Core / Imperative Shell principles](docs/modules/architecture/pages/fc-is.adoc)
- Consider how it fits the module-centric architecture
- Discuss in an issue before implementing

### 🔧 **Code Contributions**

#### Architecture Guidelines

Wagoe follows strict architectural principles:

**Functional Core Requirements:**
- Pure functions only - no side effects
- Deterministic behavior
- Immutable data structures
- Dependencies only on ports (abstractions)

**Imperative Shell Requirements:**
- Handle ALL side effects (I/O, logging, configuration)
- Implement concrete adapters for ports
- Validate input before calling core functions
- No business logic in shell layer

#### Module Structure

When adding new functionality:

1. **Choose appropriate module** (user, payments, workflow) or create new one
2. **Start with core functions** - implement pure business logic
3. **Define ports** if external capabilities needed
4. **Implement adapters** for concrete implementations  
5. **Add shell services** to orchestrate core and adapters
6. **Create interfaces** (HTTP, CLI) using shell services

#### Code Style

- Follow existing naming conventions
- Use comprehensive docstrings for public functions
- Include schema validation for all inputs
- Write tests at appropriate architectural layers

#### Testing Requirements

- **Core Functions**: Pure unit tests, no mocks required
- **Shell Services**: Integration tests with mock adapters
- **Adapters**: Contract tests against actual external systems
- `bb check` must pass; CI on main runs everything else

## Development Workflow

### 👥 **Core team: straight to main**

Since 20 November 2026 (ADR-044):

1. Take a Linear ticket and work in small steps, each leaving `main`
   releasable ("Unfinished work on main").
2. Review each step before it goes to main ("Review before main" below).
3. `git pull --rebase`, then push at once. The pre-push hook checks the review
   trailers and runs `bb check`, about 5 minutes per changed library. If main
   moved meanwhile, pull and push again.
4. CI on main then runs everything.

**Stop the line.** When main goes red, CI opens a "main is red" issue that
mentions the pusher. Revert first, debug afterwards. `git revert <sha>` needs no
ticket or review trailers as long as its subject stays `Revert "..."`; if
waiting for `bb check` is too slow, `git push --no-verify`, since CI on main tests
it anyway. Nobody pushes on top of a red main but that revert.

**Review afterwards.** Each working day one core member reads the commits that
reached main on the previous working day (on a Monday, Friday's), for example
`git fetch && git log --since='yesterday 00:00' --until='today 00:00' origin/main`,
and files a Linear ticket per finding. The team agrees the rotation each week.

**Still a pull request**, reviewed by another core member, when the change is
breaking or touches a stable port, is security-sensitive (auth, tenancy,
secrets, crypto), adds a library or a framework migration, or whenever the
author wants a second pair of eyes.

### 🌍 **External contributors: fork and pull request**

1. Fork the repository, branch, and open a pull request against `main`.
2. CI runs the fast layer on it: the quality gates and lint, the CSS build, the
   tools tests, the app and security suites, and the suites of the libraries
   your change reaches. A docs-only change skips the code jobs. e2e, the MySQL
   suites, the smokes, the isolation matrix and the uberjar run on main after
   the merge.
3. A core team member reviews and merges. Name the Linear ticket if there is
   one; otherwise describe the problem and the fix.

### ✍️ **Commits and the changelog**

- Every core team commit names its Linear ticket at the end of the subject:
  `Move the green tag to the newest green commit (BOU-1028)`. The commit-msg
  hook refuses a subject without one; merges, reverts and fixups are exempt.
- A change to shipped code adds a line under `## [Unreleased]` in
  `CHANGELOG.md`, with the ticket id: what changed and what a user must do.
  `bb check` fails a branch that changes `src/` without one. A release turns
  `[Unreleased]` into its version.
- The commit that finishes a ticket says `Fixes BOU-123` in its body. Once the
  Linear GitHub integration is connected (BOU-1032), that closes the ticket when
  the commit reaches main; until then, close it by hand.

### 🚧 **Unfinished work on main**

`main` must be releasable at every commit, so unfinished work lands without
becoming public API (BOU-1033):

- **A new library** starts `:incubating` in `modules-catalogue.edn`, outside the
  [breaking-change promise](https://wagoe.org/docs/stability.html#_not_every_library_though_tiers);
  `webhooks` is one.
- **In a stable library** unfinished code, a new protocol for it included, goes
  in a namespace marked `^:no-doc`:

  ```clojure
  (ns ^:no-doc wagoe.user.shell.passkeys
    "Unfinished (BOU-123): not public API until that ticket is done.")
  ```

  Never add a method to an existing protocol of a stable library: add a new
  protocol. `bb check:ports-diff` fails CI on a gained method.
- **The commit that finishes the ticket** removes `^:no-doc` (or promotes the
  library) and adds its CHANGELOG line.

Only `check:ports-diff` is automated; `^:no-doc` and the tiers rely on review.

### 🔍 **Review before main**

For the core team: without a pull request there is no review before `main`, so it happens
locally, per ticket, before the push (BOU-1041):

1. Finish the work and run `simplify` on the diff against `origin/main`.
2. Run `review-pr local` on the same diff, after simplify, so it sees the final code.
3. Fix the blocking findings (CRITICAL, IMPORTANT, a failing gate). Fix each non-blocking one, file a Linear
   ticket for it, or reject it with a reason. A fix that changes logic or structure gets `review-pr` again on the
   changes since; a small fix needs only `bb check`.
4. Commit with the ticket id and the trailers that record it:

   ```
   Keep the tenant through after-commit (BOU-987)

   Simplified-by: simplify
   Reviewed-by: review-pr
   ```

   A trivial commit (a typo, a docs line) carries `Review-skipped: <reason>` instead, so the history shows it.
   A `git revert` needs neither: it is the way out of a red main.
5. Push. The pre-push hook refuses a commit to main without either, then runs `bb check`. A red main afterwards:
   see "Stop the line".

The trailer proves the review ran, not that it was good; review on `main` afterwards still counts.

### 🚀 **Releases**

[RELEASING.md](RELEASING.md) covers when we release, who does it, and how to use
`main` from another project before a release.

### 📝 **Documentation Updates**

#### When to Update AGENTS.md

**🚨 Always update [AGENTS.md](./AGENTS.md) when making these changes:**

- [ ] **New Module Added**: Update module structure examples and lists
- [ ] **Build System Changes**: Update command examples and build instructions  
- [ ] **Development Workflow Changes**: Update REPL startup, system lifecycle instructions
- [ ] **Configuration Changes**: Update configuration examples and environment setup
- [ ] **New Dependencies**: Update key technologies section and rationale
- [ ] **Architecture Changes**: Update principles, dependency rules, or ADRs

#### How to Update AGENTS.md

1. **Test all commands**: Ensure every command in the guide works
2. **Verify links**: Check all internal documentation links (`bb check-links`)
3. **Update code examples**: Refresh examples to match actual source code
4. **Test with fresh environment**: Have someone unfamiliar verify the quick start

#### Architecture Documentation

For changes to core architecture:

- Update relevant files in [docs/modules/architecture/pages/](docs/modules/architecture/pages/)
- Update architecture diagrams if structural changes occur

#### Citing work in published docs

`docs/modules/**` is rendered on wagoe.org, where our tracker is not readable —
cite the pull request (`#384`), which GitHub resolves for anyone. `bb docs:lint`
fails on a tracker id there. Everywhere else — docstrings, ADRs, `CHANGELOG.md`,
`dev-docs/`, the `.wagoe/*.edn` burn-down files — keep the ticket id; that is the
audit trail, and readers of those files have access to it.

## Module Development Guidelines

### 🏗️ **Creating a New Module**

When adding a new domain module, use the scaffolding tool:

```bash
bb scaffold                                          # Interactive wizard
bb scaffold ai "product module with name, price"    # AI-assisted (with confirmation)
bb scaffold ai "product module with name, price" --yes  # AI-assisted (non-interactive)
```

This writes the module, its tests and its migration under `src/wagoe/{module}/`,
`test/` and `migrations/` of the directory you run it in, not under `libs/`:

```
src/wagoe/new-module/
├── core/           # Pure business logic
├── ports.clj       # Abstract interfaces
├── schema.clj      # Domain schemas
└── shell/          # persistence, service, http, web handlers, module wiring
```

A new framework library under `libs/` is set up by hand. Once it exists:

1. **Update the root `deps.edn` and `tests.edn`**: its paths and a test suite entry
2. **Update CI**: see "Adding a New Library to CI" in [the maintenance guide](dev-docs/agents/maintenance.md)
3. **Add it to `modules-catalogue.edn`** and run `bb agents:gen`
4. **Update documentation**: Reference in architecture docs if applicable

### 🔌 **Port and Adapter Development**

When adding new external integrations:

1. **Define port (abstract interface)** in appropriate module's `ports.clj`
2. **Implement adapter** under `shell/adapters/`
3. **Add configuration** for the new adapter
4. **Create mock implementation** for testing
5. **Update system wiring** to inject the adapter

## Quality Standards

### ✅ **Definition of Done**

All contributions must meet these standards:

- [ ] **Architecture Compliance**: Follows FC/IS principles
- [ ] **Tests Pass**: `bb check` passes; CI on main runs the rest
- [ ] **Code Quality**: Passes linting with no errors
- [ ] **Documentation**: Updates AGENTS.md if needed
- [ ] **Schema Validation**: All inputs validated with Malli schemas
- [ ] **Error Handling**: Proper error handling and logging
- [ ] **Commit**: names its Linear ticket; shipped code has a CHANGELOG line
- [ ] **Review**: simplified and reviewed before main ("Review before main"), or reviewed on its pull request

### 🧪 **Testing Standards**

- **Core functions**: Must be pure and testable without mocks
- **Shell services**: Test orchestration logic with mock adapters
- **Adapters**: Test integration with actual external systems
- **End-to-end**: Test complete workflows across architectural boundaries

### 📐 **Code Review Criteria**

Reviewers will check for:

- Architectural boundary compliance (no core → shell dependencies)
- Proper separation of concerns
- Comprehensive error handling
- Schema validation at boundaries
- Adequate test coverage
- Clear documentation
- Performance considerations

## Getting Help

### 📚 **Resources**

- **[Developer Guide (AGENTS.md)](./AGENTS.md)**: Complete development reference
- **[Architecture Documentation](docs/modules/architecture/pages/)**: Detailed architectural guides
- **[Library Documentation](docs/modules/libraries/pages/)**: Per-library reference pages

### 💬 **Communication**

- **GitHub Issues**: Bug reports and feature requests
- **Pull Requests**: external contributions, and the core team's changes that still need one
- **Architecture Questions**: Reference architecture documentation first

### 🔍 **Common Issues**

**Build Problems**: 
```zsh
rm -rf .cpcache target
clojure -M:test:test/all
```

**REPL Issues**:
```zsh
user=> (ig-repl/halt)
user=> (ig-repl/go)
```

**Configuration Issues**: Check `resources/conf/dev/config.edn` matches expected structure

## Recognition

Contributors who make significant improvements will be recognized in:

- Release notes
- Project documentation  
- Contributor list

Thank you for helping make Wagoe better! 🚀

---

For questions about this contributing guide, please open an issue.
