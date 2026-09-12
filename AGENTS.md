# Agent Instructions

Read [`CLAUDE.md`](CLAUDE.md) before making changes. It is the source of truth for repository
architecture, coding rules, build/test commands, and current project constraints. Its **Where to
look** table routes each area to the page that covers it and names the test that enforces it — read
that page before writing code in an area.

Epistola contract dependency upgrades are atomic across backend and frontend. Update the shared
`epistola-contract` version in `gradle/libs.versions.toml`, `@epistola.app/epistola-catalog` in
`modules/editor/package.json`, and `pnpm-lock.yaml` together. Run
`./gradlew checkContractVersionAlignment` before committing; CI runs it as well.

Integration and route tests must create and mutate domain state through production commands or the
shared fixture/scenario DSL. Direct SQL bypasses validation, events, authorization and other domain
invariants, and couples tests to the storage schema. The **Seed test state through commands, not
raw SQL** section of [`CLAUDE.md`](CLAUDE.md) states the narrow exceptions and how to justify one;
follow it rather than a second, differently worded rule here.

`CHANGELOG.md` is a 700 KB file whose `[Unreleased]` section is always prepended to, so never read
it whole: read the first dozen lines and insert there. Entries are
`- [**[user|dev]** ]type(scope)[!]: **Title.** …`, and a test rejects any other shape.

Vulnerability-record authoring and publication rules are maintained in the **Handle vulnerabilities
privately; publish repository-owned records** item under **When Making Changes** in
[`CLAUDE.md`](CLAUDE.md). Follow that canonical workflow instead of adding parallel instructions
here.
