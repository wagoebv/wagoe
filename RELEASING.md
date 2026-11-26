# Releasing Wagoe

How a version gets from `main` to Clojars, and who decides when. Detailed
reference: [publishing guide](dev-docs/reference/publishing.adoc).

## When

We release on content, about once a week: when `[Unreleased]` in
`CHANGELOG.md` holds something worth shipping. A security fix goes out as soon
as it lands. The release manager decides.

## Who

A release manager is a required reviewer of the GitHub environment `release`:
they approve the publish workflow. To become one, ask a repository admin to add
you there. One release manager runs `bb release`; the approval may come from the
same person or another.

## Before the release

1. Read `## [Unreleased]` in `CHANGELOG.md`: every entry says what changed and
   what a user must do. Every release needs a `### Security` section; write
   `None.` under it when there is nothing.
2. Check the public API. `bb check:ports-diff` fails on a port break in a
   stable library unless `### Breaking` names it, and then the major version
   goes up.
3. Choose the version by semver: a break bumps the major, a new capability the
   minor, only fixes the patch. A pre-release is `1.3.0-rc-1`, never a
   pre-release of a patch.
4. Point a consuming project at the `green` commit (see below), run its tests,
   and run the first-run matrix against it:

   ```bash
   gh workflow run first-run-matrix.yml -f reason="pre-release gate for 1.3.0"
   ```

## The release

From an up-to-date, clean `main`, with `gh` logged in and `LINEAR_API_KEY` and
`GITHUB_TOKEN` set:

```bash
bb release 1.3.0
```

It refuses on another branch, with local changes or unpushed commits, behind
`origin/main`, with nothing under `[Unreleased]`, or when `1.3.0` is tagged
already. It moves `[Unreleased]` to `## [1.3.0] — <date>`, bumps the version in
every place `bb check:versions` knows, and runs `bb release:check`: no open
Urgent or High security issue or bug in Linear, no known vulnerability, and a
`### Security` section. `main` has no bypass, so it commits `Release 1.3.0` on
the branch `release/1.3.0`, opens a pull request with auto-merge and switches
back to `main`. The PR touches code: a core member adds the `approved` label
after reading it; `bb release` never adds it. Once the merge queue merged it,
`bb release` waits for CI on main to pass on the PR's merge commit and pushes
the tag `1.3.0` there, so PRs merged after it are not in the release. Run it
again after an interruption: while the PR is open it waits for the merge, and
once the release commit is on main it only waits and tags.

The tag starts `publish.yml`. A release manager approves the `release`
environment; the workflow then checks that CI on main passed on the tagged
commit and that every library carries the version, publishes to Clojars, checks
every artifact landed, and creates the GitHub Release with this version's
CHANGELOG section. Do not `gh release create` or `bb deploy` by hand; the
workflow does both. Deploying by hand is only the fallback when the workflow
cannot run: see "Deploying locally" in the publishing guide.

## The `green` tag

`green` points at the newest commit on `main` whose CI passed. A green run on
main moves it forward; a red or cancelled run leaves it where it was. It shows
what is safe to build on; a release does not tag it, because `bb release`
waits for CI on its own release commit.

```bash
git fetch --tags --force && git log -1 green
```

## Using `main` before a release

Use a green commit as a git dependency. No build step is needed: each library
loads straight from the repository.

```clojure
;; deps.edn
{:deps {com.wagoe/wagoe-platform
        {:git/url   "https://github.com/wagoebv/wagoe.git"
         :git/sha   "<the sha of green>"
         :deps/root "libs/platform"}}}
```

`git ls-remote https://github.com/wagoebv/wagoe.git refs/tags/green` prints the
sha. Every library you use directly needs its own entry with its `:deps/root`,
in place of its `com.wagoe` Maven coordinate: both at once put two versions on
the classpath. Sibling libraries resolve from the same sha.

## Snapshots

Between releases `main` carries the next minor with `-SNAPSHOT`: after
`1.2.0`, `bb bump 1.3.0-SNAPSHOT` in a pull request. A release manager publishes a green `main` as that snapshot:

```bash
gh workflow run publish.yml --ref main
```

The workflow reads the version from source, requires green CI on the commit and
the same security checks as a release (the `### Security` section under
`[Unreleased]`), republishes every library, and creates no tag and no GitHub
Release. A snapshot is overwritten by the next one: use it to try a coming
release (`{:mvn/version "1.3.0-SNAPSHOT"}`), never in production. `bb release
1.3.0` then moves the snapshot to `1.3.0`; bump `main` to the next
`-SNAPSHOT` after it is tagged.

## When it goes wrong

- **CI fails on the release's merge commit:** no tag. Rerun that run
  (`gh run rerun <id>`) and run `bb release` again. If the failure is real, fix
  main and release the next patch.
- **A newer push cancelled CI:** no tag. Rerun the cancelled run and run
  `bb release` again.
- **The release PR closed without merging:** no tag. Reopen it, or run
  `bb release` again to open a new one.
- **Something was published broken:** a version on Clojars is never overwritten
  or deleted. Fix forward with a patch release.
