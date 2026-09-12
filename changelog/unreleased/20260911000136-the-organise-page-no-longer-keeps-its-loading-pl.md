---
type: fix
scopes: [catalogs]
audience: user
title: The organise page no longer keeps its loading placeholder on screen.
---

"Loading resources…" sat in a panel above the browser that had replaced it. The element renders into light DOM so the app's stylesheet reaches it, and Lit appends to a render root rather than replacing what is already there, so the pre-upgrade placeholder simply stayed.
