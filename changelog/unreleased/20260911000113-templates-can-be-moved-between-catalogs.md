---
type: feat
scopes: [catalogs]
audience: user
title: Templates can be moved between catalogs.
---

Variants, versions, contract versions, environment activations, quality findings and load-test runs follow the template. Generation history does not — it records the catalog a document was produced from, and that stays true, while a new `template_resource_id` keeps the link to the template itself. Deleting a template therefore no longer purges its generation history. Alpha, behind the `resource-relocation` toggle.
