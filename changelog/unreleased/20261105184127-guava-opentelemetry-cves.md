---
type: fix
scopes: [deps, security]
issues: [1189]
title: Guava and OpenTelemetry are upgraded past two upstream CVEs.
---

The images shipped Guava 33.6.0, brought in by the Epistola Hub client's gRPC, which has a denial of
service while deserializing (CVE-2026-102554), and OpenTelemetry 1.62.0, managed by Spring Boot,
whose W3C `tracestate` parser can take quadratic CPU (CVE-2026-118259). The apps now resolve Guava
33.7.2 and OpenTelemetry 1.67.0. Neither finding was shown to be reachable; the upgrades cost less
than proving it.
