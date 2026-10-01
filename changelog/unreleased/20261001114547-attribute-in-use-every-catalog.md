---
type: fix
scopes: [templates, catalog]
audience: user
issues: [1022]
title: An attribute that variants still use can no longer be deleted or narrowed from under them.
---

Deleting an attribute definition, or removing values from its allowed list, was refused only when a variant in the same catalog named it by its bare key. Variants that used the qualified key such as `acme.brand`, the normal form, or that belonged to a template in another catalog, were not counted, so the definition or value could be removed and the variant was left with an attribute that no longer validated. Both checks, and the conflict report of a catalog upgrade that drops a definition, now count both key forms across all of the tenant's catalogs.
