# Agent Instructions

Read [`CLAUDE.md`](CLAUDE.md) before making changes. It is the source of truth
for repository architecture, coding rules, build/test commands, and current
project constraints.

Epistola contract dependency upgrades are atomic across backend and frontend.
Update the shared `epistola-contract` version in `gradle/libs.versions.toml`,
`@epistola.app/epistola-catalog` in `modules/editor/package.json`, and
`pnpm-lock.yaml` together. Run `./gradlew checkContractVersionAlignment` before
committing.

For impact analysis on an unfamiliar or cross-cutting change, consult the
appropriate local Graphify scope before broad source inspection. Follow the
workflow and verification rules in [`docs/graphify.md`](docs/graphify.md);
Graphify results identify candidates and never replace source, build, or test
verification.
