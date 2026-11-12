---
type: build
scopes: [deps]
audience: dev
title: Dependencies are brought up to date, and the images ship no Guava.
---

The suite builds against epistola-contract 2.0.0. An old security override that held the embedded Tomcat two patch releases behind Spring Boot is gone, and so is the support hub's gRPC client, which brought in Guava. OpenTelemetry is 1.67.0. The toolchain, npm and JVM dependencies are current.
