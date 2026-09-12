---
type: fix
scopes: [design-system]
audience: dev
title: The `hidden` attribute now actually hides.
---

A component that sets its own `display` sits in the `components` layer and beat `[hidden]` on layer order, so hiding an alert or a flex row left it on screen. Three components had each patched this for themselves; the general rule replaces the need to.
