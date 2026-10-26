---
type: fix
scopes: [docker, security]
title: The container images build on Paketo's maintained Ubuntu Noble run image.
---

Every image up to and including 1.5.0 built on `paketobuildpacks/run-noble-base`, which Paketo
stopped updating in April 2024, so its operating-system packages never received Ubuntu's security
updates (OpenSSL among them). The images now build on `paketobuildpacks/ubuntu-noble-run`, which
is rebuilt as Ubuntu ships fixes. The default user stays uid 1002, but its group is now 1001
instead of 1000. The Helm chart sets its own `runAsUser` and `fsGroup`, so it is not affected.
