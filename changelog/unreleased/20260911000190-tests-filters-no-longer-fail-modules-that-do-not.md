---
type: fix
scopes: [build]
audience: dev
title: `--tests` filters no longer fail modules that do not have the test.
---

The `test` task was the only one that still failed a module when a `--tests` filter matched nothing there, so the documented `./gradlew test --tests SomeTest` broke as soon as it was run from the root — which is exactly how the repo-wide guard tests are invoked.
