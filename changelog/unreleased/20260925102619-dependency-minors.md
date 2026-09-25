---
type: build
scopes: [deps]
audience: dev
title: Dependencies raised to their latest minor and patch releases.
---

Every npm dependency moves to its latest release within its current major, among them Vite 8.3,
Vitest 5.0.1 and the Scalar API reference 1.69. On the Gradle side, the AWS SDK moves to 2.55.0,
TwelveMonkeys WebP to 3.15.1 and the GraalVM native build tools to 1.1.14. Scalar 1.69 renders
schemas on `/api-docs` as a collapsible tree instead of nested cards. Versions younger than the
seven-day release-age gate are left for the next round.
