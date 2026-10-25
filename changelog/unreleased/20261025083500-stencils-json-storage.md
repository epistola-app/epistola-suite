---
type: feat
scopes: [stencils, catalog, storage]
title: Stencils are stored as JSON and read a release like a draft.
---

A stencil is now stored the way a release keeps it, and what its document uses (other stencils, images, fonts, a theme) is indexed by the catalog name and key it is written with. Listing, searching and filtering stencils by tag read a release the same way as a draft, in the database, so the REST stencil list of a release is paged there too, and the MCP `list_stencils` and `get_stencil` tools take a release number. A stencil read from a release reports the description its content holds. A tag containing a quote no longer breaks the stencil list and count. The upgrade to 2.0 converts every stencil in the migration step; the `stencils` table is gone.
