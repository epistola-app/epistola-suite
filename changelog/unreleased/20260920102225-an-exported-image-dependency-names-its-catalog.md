---
type: fix
scopes: [catalog]
audience: dev
title: An exported image dependency names the catalog it points at.
---

Exporting a catalog declared a cross-catalog image dependency with no catalog attached, even though the image node carried the marker saying which catalog it meant — the stencil branch of the same scanner read that marker, the image branch ignored it. Such an entry named no catalog, so nothing could ever check it against anything. It now reads the marker, like stencils do. A reference that does not carry one declares no dependency rather than an unnameable one: an unqualified image reference resolves across the whole tenant at render time, so it never identified a particular catalog's image to depend on.
