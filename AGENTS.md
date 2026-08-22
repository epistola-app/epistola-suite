# Agent Instructions

Read [`CLAUDE.md`](CLAUDE.md) before making changes. It is the source of truth
for repository architecture, coding rules, build/test commands, and current
project constraints.

Epistola contract dependency upgrades are atomic across backend and frontend.
Update the shared `epistola-contract` version in `gradle/libs.versions.toml`,
`@epistola.app/epistola-catalog` in `modules/editor/package.json`, and
`pnpm-lock.yaml` together. Run `./gradlew checkContractVersionAlignment` before
committing.

Integration and route tests must create and mutate domain state through production commands or the
shared fixture/scenario DSL. Do not use direct SQL for test setup: it bypasses validation, events,
authorization, and other domain invariants, and couples tests to the storage schema. Direct SQL is
only appropriate when the test's explicit subject is database infrastructure, persistence mapping,
or a migration.

## Vulnerability records

The repository is the source of truth for published vulnerability information. GitHub Security
Advisories are synchronized publication mirrors, not the canonical records.

- Add one dated Markdown file per vulnerability under `vulnerabilities/`, named
  `YYYY-MM-DD-<lowercase-id>.md`, for example `2026-06-25-epis-2026-001.md`. The date must match the
  frontmatter's `published` timestamp and the filename must contain the advisory ID.
- Start the file with strict JSON frontmatter between `---` delimiters. JSON is also valid YAML 1.2
  and carries the OSV-compatible metadata. Put the complete human-readable description in the
  Markdown body, beginning with one level-one heading.
- Use a full 40-character commit hash for every OSV `GIT` range event. Record affected release
  ranges, severity, CWE IDs, references, mitigation, and the first patched release when known.
- Do not edit `VULNERABILITIES.md` manually. Run `pnpm vulnerabilities:render` after changing a
  source record; this regenerates the index. Run `pnpm vulnerabilities:check` before committing.
  `pnpm vulnerabilities:export` produces portable OSV JSON under `build/osv/`.
- GitHub synchronization is opt-in per record through `database_specific.github.sync`. New mirrors
  must use `state: draft`. Set `state: published` only after `patched_versions` is populated; the
  validator rejects premature publication. Add assigned GHSA/CVE identifiers to `aliases` in the
  repository record.
- The synchronization workflow never deletes advisories or requests CVEs. It requires the
  `SECURITY_ADVISORY_TOKEN` repository secret with only `Repository security advisories: write`
  permission. Local validation, rendering, and export must remain independent of GitHub.

See [`VULNERABILITIES.md`](VULNERABILITIES.md) and
[`scripts/vulnerability_advisories.py`](scripts/vulnerability_advisories.py) for the generated index
and tooling contract.
