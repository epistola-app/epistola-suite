---
type: build
scopes: [deps]
audience: dev
title: Dependencies and toolchain raised to their latest minor and patch releases.
---

Every npm dependency moves to its latest release within its current major, among them Vite 8.3,
Vitest 5.0.1 and the Scalar API reference 1.69. On the Gradle side, the AWS SDK moves to 2.55.0,
TwelveMonkeys WebP to 3.15.1 and the GraalVM native build tools to 1.1.14. Scalar 1.69 renders
schemas on `/api-docs` as a collapsible tree instead of nested cards.

The toolchain moves to Kotlin 2.4.20, Gradle 9.7.1, Node 24.21.0 and pnpm 12.4.2. ktlint is pinned
to engine 1.8.0 and now runs on the Kotlin compiler it was built against. Before this, in modules
using the Spring dependency-management plugin, the Spring Boot BOM moved that compiler up to the
project's Kotlin version, and ktlint could not start on Kotlin 2.4. The newer engine's formatting
is applied. Versions younger than the seven-day release-age gate are left for the next round.
