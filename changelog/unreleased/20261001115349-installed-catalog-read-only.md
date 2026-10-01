---
type: fix
scopes: [catalog, templates, stencils, fonts]
audience: user
issues: [1023]
title: Variants, stencil versions and fonts in an installed catalog can no longer be changed.
---

An installed (subscribed) catalog is read-only, but five actions skipped that check: renaming or re-attributing a variant, changing the default variant, archiving or publishing a stencil version, and uploading a font into it. Over REST, MCP or the UI these changed the installed copy, which then drifted from its release and could be overwritten by, or conflict with, the next upgrade. They are now refused like every other edit to an installed catalog. Installing and upgrading a catalog still write into it as before.
