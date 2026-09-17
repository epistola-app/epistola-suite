---
type: fix
scopes: [ui]
audience: user
title: Search boxes and create buttons appear only where they apply.
---

A search box sat in the page header of every list, including lists with nothing in them; it is now
shown once there is something to search, on templates, images, environments, stencils, themes and
tenants. A search that matches nothing still keeps its box, so the term can always be cleared. The
templates page also showed **New Template** twice on a first visit — once in the header and once in
the middle of the page; the empty state now points at the header action instead of repeating it.
Anchors and buttons scrolled into view no longer land underneath the sticky navigation bar.
