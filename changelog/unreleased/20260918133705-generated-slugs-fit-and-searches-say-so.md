---
type: fix
scopes: [ui]
audience: user
title: A generated ID stays within its limit, and a search that finds nothing says so.
---

Typing a long name filled the ID field beside it with a value longer than that field allows, so you
had to shorten something you never typed. The generated ID is now shortened to fit, on every form
that derives one — templates, variants, themes, environments, stencils, catalogs, attributes, code
lists and tenants. Editing the ID yourself still stops it from following the name.

A search that matched nothing left an empty list with no explanation on the themes, stencils and
environments screens, and on the images screen it said "No images yet" and offered to upload one, as
though there were none at all. All four now say that nothing matched.
