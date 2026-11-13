# Convention examples

Read when directed by [AGENTS.md](../../AGENTS.md). Paths in commands and inline code are relative to the repository root.

## Critical Conventions

### 1. ALWAYS Use kebab-case Internally

See [root naming rules](../../AGENTS.md#architecture-and-naming).

```clojure
;; ✅ CORRECT - Convert ONLY at persistence boundary using shared utilities
(require '[wagoe.core.utils.case-conversion :as cc])

;; At persistence boundary - DB to Clojure
(cc/snake-case->kebab-case-map db-record)

;; At persistence boundary - Clojure to DB
(cc/kebab-case->snake-case-map entity)

;; Alternative: Manual transformation
(defn db->user-entity [db-record]
  (-> db-record
      (set/rename-keys {:created_at :created-at
                        :password_hash :password-hash
                        :updated_at :updated-at})))
```

**Why**: Recent bug caused authentication failures because service layer used `:password_hash` but entities had `:password-hash`.

### 2. Layer Responsibilities

| Layer | Allowed | Never |
|-------|---------|-------|
| **Core** | Pure functions, calculations | I/O, logging, exceptions |
| **Shell** | I/O, validation, side effects | Business logic |
| **Ports** | Protocol definitions | Implementations |

### 3. Dependency Rules

See [root architecture rules](../../AGENTS.md#architecture-and-naming).

### 4. Write Short

Everything written for someone else to read — comments, docstrings, commit
messages, PR descriptions, PR and review comments, issue descriptions — is
short, human and to the point. Say the thing, then stop. Someone has to read
all of it.

- **No slop.** No filler, no restating the diff, no recap of what you just
  said, no stacked caveats, no "comprehensive" or "robust".
- **No attributions.** No "Generated with …" footers, no `Co-Authored-By`
  trailers, no tool signatures.
- **Comments and docstrings** — explain what is not obvious from the code.
  Usually why, not what. One or two sentences. If a comment needs paragraphs,
  the code probably needs a rethink, or the reasoning belongs in an ADR.
- **Commit messages** — a subject line that says what changed, and a body only
  when the reason is not evident from the diff. Reference the ticket.
- **PR descriptions** — what changed and why, in a few lines. Reviewers read
  the diff; do not narrate it back to them. Include verification output only
  when a reviewer would otherwise have to re-run it.
- **PR and review comments** — the answer or the finding, and the evidence in
  a line. Point to the commit; do not retell it.
- **Issue descriptions** — the problem, how to reproduce it, and what done
  looks like.
- **CHANGELOG entries** — **two lines, hard limit.** What changed, and what a
  user does about it. The reasoning, the measurements and the history belong in
  the ticket and the commit; a changelog nobody finishes reading is worse than a
  short one. Reference the ticket instead of explaining it.

```markdown
<!-- too long -->
- **Any `Authorization: Bearer` value authenticated.** JWT validation returns a
  map on both outcomes and the middleware branched on the map itself, so the
  rejection branch was unreachable. Two further bugs in the same expression:
  the claims live under `:claims`, and the id claim is `:sub`, not `:user-id`…

<!-- right -->
- **Any `Authorization: Bearer` value authenticated** (BOU-374). Upgrade if you
  use JWT auth.
```

Existing long comments in this repo are not a target for rewriting — leave
them. This applies to what you write next. **The CHANGELOG is the exception:**
it is read start to finish by people deciding whether to upgrade, so entries
are trimmed in place when they are found over the limit (BOU-371).

### 5. A Stable Port Only Grows by New Protocols

A protocol in a stable library's `ports` is public API (see
[Stability](../../docs/modules/ROOT/pages/stability.adoc)). Callers use it, and
applications implement it with their own `defrecord` or `reify`. Changing it
breaks one of the two, and an implementer breaks at runtime: the record
compiles, and the first call the framework makes to what it lacks throws.
`wagoe list modules` shows which libraries are stable.

In a 1.x release, in a stable library:

- **A new capability is a new protocol.** Never a new method on an existing
  one, and never a new arity on an existing method: both break implementers.
- **Check it once, where the component starts.** `satisfies?` in `init-key`,
  then either take the old path, or refuse to boot with a `WGE-` error that
  names what is missing when no old path is safe (a lockout, a lock), and add
  the code to `libs/devtools/resources/wagoe/devtools/error_catalog.edn` so
  `bb guide error` explains it. Not per call, and never a runtime
  `AbstractMethodError`.
- **Nothing is removed, renamed or moved.** Not a protocol, a method or an
  arity. Deprecate it (`^:deprecated` and `### Deprecated`) and keep it until 2.0.
- **A break that cannot wait** (a security fix) is named in backticks under
  `[Unreleased]` `### Breaking`, method by method, and recorded as an exception
  on the stability page. That is a decision for the release, not for the PR:
  ask before writing one.

```clojure
;; ports.clj — beside IUserRepository, not inside it
(defprotocol ILoginAttempts
  (claim-login-attempts [this user-id n lockout]))

;; shell — decided once, when the component starts
(defmethod ig/init-key :wagoe/user-service [_ {:keys [user-repository] :as opts}]
  (when-not (satisfies? ports/ILoginAttempts user-repository)
    (throw (ex-info "WGE-NNN: the user repository does not implement ILoginAttempts"
                    {:type :configuration-error :missing ['claim-login-attempts]})))
  (service/create opts))
```

Incubating libraries may change their ports in a minor; say so under
`### Changed`. `bb check:ports-diff` holds all of this against the last final
release tag and fails CI on a stable change nobody named.

---
