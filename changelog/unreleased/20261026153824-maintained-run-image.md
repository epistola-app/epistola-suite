---
type: fix
scopes: [docker, security]
title: The container images build on Paketo's maintained Ubuntu Noble run image.
---

Every image up to and including 1.5.0 built on `paketobuildpacks/run-noble-base`, which Paketo
stopped updating in April 2024, so its operating-system packages never received Ubuntu's security
updates (OpenSSL among them). The images now build on `paketobuildpacks/ubuntu-noble-run`, which
is rebuilt as Ubuntu ships fixes. The container still runs as uid 1002, gid 1000.
