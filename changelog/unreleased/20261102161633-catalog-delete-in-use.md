---
type: fix
scopes: [catalogs]
title: A catalog another catalog's draft still uses is not deleted.
---

Deleting a catalog checked only templates in other catalogs' current version that used its themes,
stencils or images. A stencil nested in another catalog's stencil, a parallel draft, or any other
reference went unseen. The delete went through and left that draft naming something that no longer
existed, which then could not be released. Deleting is now refused while any draft of another
catalog uses something of the catalog, and the refusal names each reference.
