---
type: fix
scopes: [deps, security]
issues: [1189]
title: The images no longer carry Guava and OpenTelemetry versions with two upstream CVEs.
---

The images shipped Guava 33.6.0, brought in by the Epistola Hub client's gRPC, which has a denial of
service while deserializing (CVE-2026-102554), and OpenTelemetry 1.62.0, managed by Spring Boot,
whose W3C `tracestate` parser can take quadratic CPU (CVE-2026-118259). The Hub client is gone, so
the apps ship no Guava at all, and they resolve OpenTelemetry 1.67.0. Neither finding was shown to
be reachable.
