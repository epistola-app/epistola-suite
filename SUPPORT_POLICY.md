# Epistola Release & Support Policy

Epistola Suite is published and supported by **Epistola Nederland B.V.** and
follows a **continuous delivery** model.

Rather than maintaining many historical versions for years, Epistola Nederland
B.V. invests in **easy upgrades**, **automated migrations**, **excellent
documentation**, **predictable releases**, and **rapid bug and security fixes**.

Customers are expected to remain on a **supported release**. That is a
deliberate trade-off: instead of long-lived legacy support, upgrading should be
inexpensive and operationally straightforward.

Epistola Suite’s value proposition is **rapid innovation with low-friction
upgrades**, not long-term maintenance of old versions.

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

Within a major version, only the **newest minor release line** is patched. A
line stays supported for a fixed time after its successor ships, so there is
always room to upgrade:

| When                                             | The line it replaces stays supported for |
| ------------------------------------------------ | ---------------------------------------- |
| A new **minor** ships (1.5.0 after 1.4.x)        | **30 days**                              |
| A new **major** ships (2.0.0 after the last 1.x) | **3 months**                             |

A major therefore has at most two supported minor lines: the newest, and for 30
days the one before it. Shipping another minor ends the older one's period
early.

**The newest minor line** receives:

- Bug fixes
- Security fixes
- Documentation updates
- Technical support (where an arrangement with Epistola Nederland B.V. applies)

**The previous minor line, during its 30 days,** receives technical support.
Its fixes ship in the newest minor, which is backwards compatible: upgrading to
it is the fix.

**The last minor line of the previous major, during its three months,** receives
security fixes and fixes for data loss or corruption, as patch releases (1.5.x),
and technical support. It receives no other bug fixes and no new features.

**Pre-releases** (alpha, beta, release candidate) of the next major are for
evaluation, not production. A fix reaches them in the next pre-release.

**Unsupported** versions receive **no** updates.

### End dates

The end of a major's last line is announced when the next major's first release
candidate ships, as "three months after X.0.0", and the date itself is
published on the day X.0.0 ships. An end date may be **extended, never brought
forward**. Customers with a support arrangement can ask for an extension.

The currently supported versions are published as release metadata, and
installations learn their status through the in-product
[version check](docs/version-check.md): below the published floor
(`support.minVersion`) an installation shows that it is unsupported, and in the
last 90 days before the floor's end date (`support.until`) it shows that
support is ending. A daily check compares that published floor with this policy
([docs/sbom.md](docs/sbom.md#daily-cve-watch)).

## Upgrade philosophy

Customers are expected to **upgrade regularly**.

Epistola Nederland B.V. commits to:

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

Within a major version, Epistola Nederland B.V. aims to preserve:

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

Epistola Nederland B.V. may expose richer **machine-readable release metadata**
(for example a JSON endpoint) so installations can notify administrators that:

- a newer version exists
- whether it is a security update
- whether manual actions are required
- where to find the upgrade guide

An initial form of this already exists via public release metadata consumed by
the [version check](docs/version-check.md), which finds it through
`https://epistola.app/.well-known/epistola.json`. Further fields and
operator tooling may expand over time; they are not required for this policy to
apply.
