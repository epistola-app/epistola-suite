---
type: test
scopes: [catalogs]
audience: dev
title: Guard against registering a render-time-resolved type as movable.
---

Stencil references are provenance — content is inlined at insert — so moving a stencil cannot break generation. Themes, fonts and assets are resolved by address while rendering, so registering one before its runtime lookup follows aliases would break every published template that uses it. The guard makes that a build failure; all three have since been registered, each recording the lookup that follows aliases and the test that proves it.
