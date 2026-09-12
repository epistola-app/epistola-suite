---
type: feat
scopes: [catalog]
audience: dev
title: Somewhere to keep what a subscribed catalog's source is offering.
---

Checking for updates has only ever been a live fetch made while rendering the catalogs page, so nothing could be said about a catalog nobody happened to be looking at, and the initial state of every row was literally `UNCHECKED`. `catalog_upstream_checks` records the last answer per catalog. Its own table rather than columns on `catalogs`, because `catalogs` is in the tenant backup set and knowledge about somebody else's server would then be captured and restored — telling an operator months later that v2.1 is available when v3 shipped or that release was withdrawn. Source-agnostic on purpose: a manifest URL and an Exchange coordinate differ in how they are asked, not in what the answer means.
