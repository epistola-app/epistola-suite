---
type: fix
scopes: [ui, templates]
audience: user
title: Search boxes appear only where they apply, and New Template opens on the catalog you filtered to.
---

A search box sat in the page header of every list, including lists with nothing in them; it is now
shown once there is something to search, on templates, images, environments, stencils, themes and
tenants. A search that matches nothing still keeps its box, so the term can always be cleared.

Opening **New Template** while the list was filtered to a catalog ignored that filter and selected
whichever catalog sorted first, so a template could be created somewhere other than the one on
screen. Every trigger now carries the active catalog and the dialog opens on it. With no filter
active the first catalog is still selected, as before.

Anchors and buttons scrolled into view no longer land underneath the sticky navigation bar.
