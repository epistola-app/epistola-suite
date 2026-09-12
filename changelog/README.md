# Changelog fragments

Every notable change adds **one small file here** instead of editing `CHANGELOG.md`. At release time
the fragments are assembled into a dated section and removed.

This exists because `CHANGELOG.md` is ~700 KB: prepending to it meant opening a huge file for a
one-line addition, and concurrent branches all inserted at the same spot. A fragment per change has
neither problem.

## Adding one

Create `changelog/unreleased/<timestamp>-<slug>.md`, with the timestamp from the same generator the
migrations use:

```bash
echo "changelog/unreleased/$(date -u +%Y%m%d%H%M%S)-catalog-install-unit.md"
```

```markdown
---
type: feat
scopes: [catalog, exchange]
audience: user
breaking: true
issues: [927]
title: A catalog installs and upgrades as one unit.
---

Installing a subscribed catalog took an optional list of resources, so "installed 1.2.0" meant
something different on every installation. Installing now installs the whole manifest, and an
upgrade reconciles it.
```

## Fields

| Field      | Required | Meaning                                                                          |
| ---------- | -------- | -------------------------------------------------------------------------------- |
| `type`     | yes      | One of `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore` |
| `scopes`   | yes      | One or more lowercase kebab-case areas, e.g. `[editor]`, `[catalog, exchange]`   |
| `title`    | yes      | One sentence, ending in a full stop. It becomes the bold lead-in                 |
| `audience` | no       | `user` or `dev`. Omit when the change matters to everyone                        |
| `breaking` | no       | `true` for a breaking change — this replaces the old `!` marker                  |
| `maturity` | no       | `alpha` or `beta`, when a breaking change is to a non-GA surface                 |
| `issues`   | no       | Issue numbers, e.g. `[927]`                                                      |

The body is one paragraph written for the in-app changelog reader: what changed and why it matters,
not how it was implemented. The check warns past 900 characters.

Helm chart changes go to `charts/epistola/CHANGELOG.md` instead; that changelog is not shown in the
app and keeps its own format.

## What enforces this

- `./gradlew checkChangelogFragments` validates every fragment, and fails a change to
  `*/src/main/**` or `charts/**` that adds none. Add the `no-changelog` label to a PR that genuinely
  needs no entry.
- `./gradlew renderChangelog` produces the combined markdown the app ships; the in-app dialog reads
  that, never this directory directly.
- At release, `./gradlew releaseChangelog -PreleaseVersion=X.Y.Z` writes the dated section into
  `CHANGELOG.md` and deletes the fragments it consumed.
