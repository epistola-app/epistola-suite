---
type: feat
scopes: [ui, catalog]
audience: user
breaking: true
title: Every page says where you are, with a side nav for the catalog you work in.
---

The top menus give way to a context bar and a side nav. Inside a catalog the bar reads **Acme / Letters / Next release** on every page, the editor included, with what is not published yet and **Publish…**; the side nav holds the catalog's overview, templates and other resources, and its **Releases**, **Deployments**, **Dependencies** and **Settings**. Lists inside a catalog show its own resources, then those of the catalogs it uses. Every resource page, list and create dialog moved under its catalog, at `/tenants/{tenant}/catalogs/{namespace}/{key}/…`, and the lists across all catalogs are gone. **Action on upgrade:** update bookmarks, and embedding hosts that build page paths themselves; the bridge's `navigate` message is unchanged.
