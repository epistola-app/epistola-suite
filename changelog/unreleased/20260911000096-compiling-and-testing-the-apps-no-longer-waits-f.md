---
type: perf
scopes: [build]
audience: dev
title: Compiling and testing the apps no longer waits for the SBOM.
---

`cyclonedxDirectBom` was a dependency of `apps:epistola`'s (and `apps:pdfrender`'s) `processResources`, which put every module jar and the CycloneDX run on the path of `classes` and therefore of every test task — in CI each job rebuilt all 22 jars and the SBOM before the app tests could start, about fifty seconds on the test job's critical path. The SBOM is now embedded at archive level (`jar` and `bootJar`, so the same `META-INF/sbom/bom.json` still ships, nested in the demo image too) and nothing else depends on it.
