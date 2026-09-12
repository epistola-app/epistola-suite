---
type: refactor
scopes: [catalogs]
audience: dev
title: A resource address reads as a path.
---

`stencil:letters:header` became `stencil:letters/header`, matching `EntityId.path()` — a colon after the type, slashes within the address — so the organise deep link looks like every other identifier in the suite. The tenant stays out of it because every surface carrying one already names the tenant in its URL.
