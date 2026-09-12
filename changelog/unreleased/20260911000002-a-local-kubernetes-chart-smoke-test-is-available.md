---
type: test
scopes: [chart]
audience: dev
title: A local Kubernetes chart smoke test is available.
---

`scripts/test-helm-chart.sh` creates and removes a disposable Kind cluster, starts one ephemeral PostgreSQL container, installs the application chart, validates migration/readiness/HPA admission, and prints diagnostics on failure. It is intentionally not part of CI yet.
