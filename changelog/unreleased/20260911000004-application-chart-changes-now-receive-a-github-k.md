---
type: test
scopes: [chart]
audience: dev
title: Application-chart changes now receive a GitHub Kind smoke test.
---

Pull requests run the local chart installation harness only when the Epistola chart, its test harness, or its workflow changes; unrelated application and chart changes do not create a Kubernetes cluster.
