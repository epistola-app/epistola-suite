---
type: build
scopes: [build]
audience: dev
title: CI runs the checks the conventions claimed it ran, and gets out of the way faster.
---

`ktlintCheck` and `checkContractVersionAlignment` had hung off Gradle's `check` for months without CI ever calling it, so neither was enforced; both now run in the compile job alongside stylesheet linting. The test jobs wait only for compilation instead of the SBOM, two slow steps stopped redoing their downloads, no-change rebuilds are no-ops again, test JVMs are bounded and get the flags they were meant to have, and there are fewer Spring test contexts. A `--tests` filter matching nothing in a module no longer fails that module.
