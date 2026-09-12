# CI workflows

`build.yml` is the PR gate. Its shape follows one rule: the test jobs wait for nothing they do not
need. Tool versions come from `.mise.toml` via `mise-action`.

| Job                     | Waits for           | Runs                                                                                                                                                        |
| ----------------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `compile`               | nothing             | `pnpm build`, then one Gradle call: `checkMigrationVersions ktlintCheck checkContractVersionAlignment testClasses`; ships the build cache to the other jobs |
| `frontend-checks`       | nothing             | `pnpm lint:check`, `lint:css`, `format:check`, `license:check`, `pnpm test`                                                                                 |
| `sbom`                  | `compile`           | CycloneDX SBOMs, third-party notices, VEX export, Trivy (critical fails)                                                                                    |
| `test-unit-integration` | `compile`           | `gradle test koverXmlReport`                                                                                                                                |
| `test-ui`               | `compile`           | `gradle uiTest` (Playwright)                                                                                                                                |
| `coverage`              | both test jobs      | coverage badge, `main` pushes only                                                                                                                          |
| `docker`                | tests, checks, sbom | image build and publish, `v*` tags or a PR labelled `publish`                                                                                               |

Other gates: `gitleaks.yml` (secret scan), `codeql.yml`, plus `vulnerability-advisories.yml`,
`helm.yml` and `epistola-chart-smoke.yml` on their path filters.

## Rules

- **One Gradle invocation per job.** The configuration cache is never reused across CI jobs, so each
  extra `gradle` call pays a full configuration phase. Add a task to an existing line rather than a
  new step.
- **A check that is not in a workflow is not enforced**, whatever the docs say. `ktlintCheck` and
  `checkContractVersionAlignment` hung off Gradle's `check` for months without running, because CI
  stopped calling `check`. If you document something as enforced, wire it here in the same change.
- Required checks are `compile`, `frontend-checks`, `sbom` and both test jobs
  ([`docs/github.md`](../docs/github.md)).
- Workflow files are owned via `CODEOWNERS`; understand a workflow's blast radius before changing
  it, and say in the PR what you verified.
- Pin third-party actions by commit SHA, as the existing steps do.
