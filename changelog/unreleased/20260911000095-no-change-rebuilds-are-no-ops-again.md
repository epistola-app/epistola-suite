---
type: perf
scopes: [build]
audience: dev
title: No-change rebuilds are no-ops again.
---

Spring Boot's `build-info.properties` carried `build.time`, which defaults to "now" and made `bootBuildInfo` — and through `resources/main` also `jar`, three fat jars totalling half a gigabyte, and every test task of `apps:epistola` and `apps:epistola-demo` — never up-to-date. Nothing read the timestamp; it is excluded, and the Kotlin convention plugin additionally ignores it in runtime-classpath normalization so it can never invalidate downstream test runs again.
