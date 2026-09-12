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

- `feat!:`/`fix!:`/`BREAKING CHANGE:` → MAJOR · `feat:` → MINOR · else → PATCH (pre-1.0, a breaking change is a MINOR bump).
- **Feature-maturity exception:** a breaking change scoped **entirely to an alpha or beta feature** is a **MINOR**, not a major — only breaking changes to **GA (stable)** features force a MAJOR bump. So a `feat!`/`fix!` that touches only an experimental/preview surface (e.g. an alpha storage backend) does **not** by itself trigger a major; verify the `!`/`BREAKING CHANGE` commits since the last release are GA-scoped before choosing MAJOR, and note the alpha/beta scope in the CHANGELOG entry.

### 2. Release commit — version + CHANGELOG

- Set `gradle.properties` `version=` to `X.Y.Z` (strip `-SNAPSHOT`).

- **Curate the fragments first — this is the substantive step.** Read every file in
  `changelog/unreleased/` as a set, the way a reader meets them: as one release, not as the
  order they were written in. Entries accumulate over months and arrive over-detailed and
  overlapping, so edit the fragment files before assembling:

  - **Merge what is one change to a reader.** Several fragments often describe one feature
    from different angles — the feature, its follow-up fix, its UI, its migration. Combine
    them into a single entry that says what changed and why it matters; delete the others.
  - **Cut implementation detail.** A changelog entry is not a commit message. Drop the
    internals, the rationale a reader cannot act on, and anything that reads as a note to
    ourselves. Long bodies are the usual symptom: `checkChangelogFragments` warns past 900
    characters, and most entries want far less.
  - **Fold routine churn.** Dependency bumps, test infrastructure and formatting passes
    collapse into one `**[dev]**` entry, or are dropped if nothing observable changed.
  - **Check the audience badge.** Anything deep-internal is `**[dev]**`, so the dialog's
    default Users view stays readable.

  Keep the curation in the fragment files so it lands in the release PR diff, reviewable.

- **Assemble** the curated set into the dated section and remove the fragments:

  ```bash
  ./gradlew releaseChangelog -PreleaseVersion=X.Y.Z
  ```

  This writes `## [X.Y.Z] - YYYY-MM-DD` above the previous release, with breaking changes
  first, then by type and scope. One section per release; `CHANGELOG.md` holds released
  history only.

- **Write the release summary** over the placeholder the task leaves under the version
  heading: a **1–3 sentence** plain-prose summary of the headline user-facing changes and any
  notable breaking change or theme. The in-app dialog (`ChangelogRenderer`) shows it above the
  entries, so write it for end users — lead with `**[user]**`/untagged `feat`/`fix` material
  and skip deep `**[dev]**` internals. Do **not** start it with `- ` or `### `.

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
