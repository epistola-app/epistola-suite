---
name: release
description: Create a new release. Use when the user wants to release a new version, cut a release, or publish a version.
---

Release a new version of epistola-suite (the app image). The app version lives in
`gradle.properties` `version=` (source of truth); during development it carries the
next version with **`-SNAPSHOT`**. A release strips the suffix, finalizes the
CHANGELOG, and pushes a matching `vX.Y.Z` tag; CI (`.github/workflows/build.yml`)
reacts to the tag, asserts `tag == gradle.properties version`, and builds/signs/
publishes the image. Then you re-open the next `-SNAPSHOT`. Chart releases
(`epistola-*` tags) are a separate stream and never cross-fire.

## Prerequisites

- Chart changes are merged; you're releasing from `main` (or a `release/*.x`
  maintenance branch, which has its own `-SNAPSHOT` lineage).

## Steps

### 1. Determine the release version

`gradle.properties` `version` holds the next version with `-SNAPSHOT`. The release
version is that value minus `-SNAPSHOT`. Sanity-check it against the commits since
the last release and bump if it's behind:

```bash
git fetch --tags
LATEST_TAG=$(git tag -l "v*" --sort=-v:refname | head -1)
grep '^version=' gradle.properties
git log "$LATEST_TAG"..HEAD --oneline
```

- `feat!:`/`BREAKING CHANGE:` → MAJOR · `feat:` → MINOR · else → PATCH (pre-1.0, a breaking change is a MINOR bump).

### 2. Release commit — version + CHANGELOG

- Set `gradle.properties` `version=` to `X.Y.Z` (strip `-SNAPSHOT`).
- Move `[Unreleased]` in `CHANGELOG.md` to `## [X.Y.Z] - YYYY-MM-DD`, and add a
  fresh empty `[Unreleased]` above it.

  **Write a release summary.** Immediately under the new version heading (blank
  line, prose, blank line before the first entry), add a **1–3 sentence**
  plain-prose summary — the headline user-facing changes and any notable breaking
  change/theme. This is parsed by the in-app Changelog dialog (`ChangelogRenderer`)
  and shown above the entries, so write it for end users. Derive it from the
  entries you just moved (lead with `**[user]**`/untagged `feat`/`fix`; skip deep
  `**[dev]**` internals). Do **not** start the summary with `- ` or `### `.

  ```
  ## [0.23.0] - 2026-06-15

  This release adds audience/type/scope filtering to the in-app changelog and
  locale-aware number formatting in the editor, and fixes bullet glyphs vanishing
  in generated PDFs.

  - **[user]** feat(changelog): **…**
  - **[user]** fix(pdf): **…**
  ```

- Commit on a branch, open a PR, merge to `main` (never push to `main` directly):
  `chore(release): vX.Y.Z`.

### 3. Confirm, then tag the merge commit

Show the user the version, the commits, and the CHANGELOG entry; ask permission.
Then, with `$RELEASE_BODY` = the `[X.Y.Z]` CHANGELOG section:

```bash
COMMIT_SHA=$(git rev-parse origin/main)   # the merged release commit
gh release create vX.Y.Z --title "vX.Y.Z" --notes "$RELEASE_BODY" --target "$COMMIT_SHA"
```

This creates the tag + GitHub Release. CI validates `tag == gradle.properties` and
builds the image. (A tag whose commit still says `-SNAPSHOT` **fails** the check —
that's the guard against releasing a snapshot.)

### 4. Re-open the next -SNAPSHOT

In a follow-up PR, set `gradle.properties` `version=` to the next dev version
(default `X.Y.(Z+1)-SNAPSHOT`; raise to minor/major when the next cycle's first
`feat`/breaking change lands). Merge.

### 5. Verify

```bash
gh run list --workflow=build.yml --limit 1
gh release view vX.Y.Z
```

## Important

- **Tag = `vX.Y.Z`**, disjoint from chart tags (`epistola-*`). CI reacts to the
  `v*` tag via `push: tags: ['v*']` — **not** the `release: published` event.
- The three must agree: `gradle.properties version` == CHANGELOG `[X.Y.Z]` == the
  tag. CI enforces `tag == gradle.properties`.
- Never skip the CHANGELOG update or the release-summary prose.
- Dev always carries `-SNAPSHOT`; a release is the strip → tag → re-open cycle.
