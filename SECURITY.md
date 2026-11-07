# Security policy

## Reporting a vulnerability

Report it privately through GitHub:
[**Report a vulnerability**](https://github.com/wagoebv/wagoe/security/advisories/new).
Do not open a public issue, discussion or pull request for it.

Please include:

- the Wagoe version (`com.wagoe/*` artifact and version, or the installer you ran),
- how to reproduce it, ideally a minimal app or request,
- what an attacker gains.

## What happens next

Wagoe has one maintainer. These are the timelines one person can keep:

| Step | Within |
|---|---|
| We confirm we received it | 3 working days |
| We assess it and tell you the plan | 10 working days |
| The fix ships | the next patch release |

We publish only once the fix is out. If a fix takes longer than 90 days after
your report, we publish the problem with a mitigation anyway.

## How a fix reaches you

- A `### Security` entry in [CHANGELOG.md](CHANGELOG.md) and in the release
  post: the impact, the affected versions, and what to do.
- A [GitHub Security Advisory](https://github.com/wagoebv/wagoe/security/advisories),
  with a CVE, for anything of CVE-worthy impact.
- The reporter is credited if they want to be.

## Supported versions

Only the latest minor release gets security fixes; earlier lines are not
patched. The current version is on the
[stability page](https://wagoe.org/docs/stability.html).

## Scope

In scope:

- the `com.wagoe/*` artifacts on Clojars,
- the installer: `get.wagoe.org` and [`scripts/install.sh`](scripts/install.sh).

Out of scope: applications built with Wagoe (report those to their owners), and
the `wagoe.org` website.
