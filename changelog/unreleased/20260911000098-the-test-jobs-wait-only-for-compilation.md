---
type: ci
scopes: [build]
audience: dev
title: The test jobs wait only for compilation.
---

The pipeline's critical path was the whole `build` job (frontend lint, format, license and unit checks, then compile, then SBOMs, notices and two Trivy scans) followed by the test job, so about two minutes of work the tests never needed ran in front of them on every run — five minutes and more on the days npm was slow. `compile` now does only what the test jobs consume (`pnpm build` and a single `gradle checkMigrationVersions testClasses` invocation, since every extra Gradle call on CI pays the full configuration phase again), while `frontend-checks` and `sbom` run alongside the tests. Publishing still gates on all of them.
