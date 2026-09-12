---
type: docs
scopes: [chart]
audience: dev
title: VPA is documented as an operator-evaluated feature.
---

Start in recommendation-only mode; production uses CPU HPA while VPA remains disabled because its controller behavior has not yet been runtime-tested and the chart cannot yet restrict VPA to memory-only control. Use the suite's built-in Load Tests facility to gather representative recommendations.
