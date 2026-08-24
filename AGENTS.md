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

Vulnerability-record authoring and publication rules are maintained in the **Vulnerability
records** item under **When Making Changes** in [`CLAUDE.md`](CLAUDE.md). Follow that canonical
workflow instead of adding parallel instructions here.
