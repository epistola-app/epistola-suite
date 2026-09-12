---
type: test
scopes: [architecture]
audience: dev
title: An undefined severity or text class fails the build.
---

A class the stylesheets do not define renders as plain text — the page still loads and the markup still reads as deliberate, so it survives review. `DesignSystemClassTest` checks every `alert`, `badge` and `text-` class used in Thymeleaf templates and Lit components against the stylesheets.
