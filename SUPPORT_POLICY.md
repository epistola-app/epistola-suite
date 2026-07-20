# Epistola Release & Support Policy

Epistola follows a **continuous delivery** model.

Rather than maintaining many historical versions for years, Epistola invests in
**easy upgrades**, **automated migrations**, **excellent documentation**,
**predictable releases**, and **rapid bug and security fixes**.

Customers are expected to remain on a **supported release**. That is a
deliberate trade-off: instead of long-lived legacy support, upgrading should be
inexpensive and operationally straightforward.

Epistola’s value proposition is **rapid innovation with low-friction upgrades**,
not long-term maintenance of old versions.

## Versioning

Epistola uses [Semantic Versioning](https://semver.org/).

| Change              | Version                      | Meaning                                            |
| ------------------- | ---------------------------- | -------------------------------------------------- |
| **Patch** (`x.y.z`) | Bug fixes, security fixes    | No breaking changes                                |
| **Minor** (`x.y.0`) | New functionality            | Backwards compatible within the same major version |
| **Major** (`x.0.0`) | May include breaking changes | Migration guidance is always provided              |

## Release cadence

There is **no fixed calendar**. Releases are published when ready.

Typical expectation:

- **Patch** releases — frequent (possibly weekly)
- **Minor** releases — regularly
- **Major** releases — only when needed

## Supported versions

Only a **limited number of release lines** are supported at any time.

This policy does **not** hardcode support windows (for example “12 months” or
“N minor versions”). The set of currently supported versions is published
separately (website and/or repository release metadata) so the support window
can evolve without rewriting this document.

**Supported** versions receive:

- Bug fixes
- Security fixes
- Documentation updates
- Technical support (where a support arrangement applies)

**Unsupported** versions receive **no** updates.

Installations can also learn support status via the in-product
[version check](docs/version-check.md), which reads public release metadata
(including a support floor when published).

## Upgrade philosophy

Customers are expected to **upgrade regularly**.

Epistola commits to:

- **Direct upgrades** from any **supported** version to the latest supported
  version
- **No requirement** to install intermediate releases
- **Upgrade guides** for every release
- **Automated database migrations** whenever possible (forward-only Flyway
  migrations; see [docs/migrations.md](docs/migrations.md))
- **Clearly documented manual actions** when automation is impossible

Engineering optimizes for **easy upgrades** instead of maintaining many legacy
versions.

## Rollback

**Rollback of a failed upgrade is supported.**

A rollback means the upgrade did **not** complete successfully.

Before every upgrade, create a backup. If the upgrade fails before completion,
restore from that backup.

## Downgrades

**Downgrades are not supported.**

Once an upgrade has completed successfully **and** the installation has
processed production data, there is no guarantee that an older version can run
again.

Database migrations are **forward-only**.

## Compatibility

Within a major version, Epistola aims to preserve:

- Public APIs
- Templates
- Configuration

Deprecated functionality should be announced before removal whenever reasonably
possible. Breaking changes are called out explicitly in release notes (for
example conventional-commit `BREAKING CHANGE` / major bumps).

## Customer responsibilities

Customers should:

- Stay on a **supported** version
- Follow the upgrade instructions for each release
- Create **backups before upgrading**
- Report issues against **supported** releases

## Release communication

Because frequent upgrades are expected, release information must be easy to
consume.

Subscription and discovery mechanisms include (as available):

- [GitHub Releases](https://github.com/epistola-app/epistola-suite/releases)
- Email notifications
- RSS/Atom feed
- Security advisory channels
- Partner communication channels

Every release should document:

- Summary
- New features
- Bug fixes
- Security fixes
- Breaking changes
- Required actions
- Upgrade guide
- Known issues

See also the repository [CHANGELOG.md](CHANGELOG.md).

## Future direction

Epistola may expose richer **machine-readable release metadata** (for example a
JSON endpoint) so installations can notify administrators that:

- a newer version exists
- whether it is a security update
- whether manual actions are required
- where to find the upgrade guide

An initial form of this already exists via public release metadata consumed by
the [version check](docs/version-check.md)
(`https://epistola.app/.well-known/epistola/releases.json`). Further fields and
operator tooling may expand over time; they are not required for this policy to
apply.
