---
type: feat
scopes: [api]
breaking: true
title: Over REST, another catalog is named by the alias the catalog declares it under.
---

The REST API names a catalog's references as its content does: a default theme is `{key, catalogKey}`, an attribute's `codeListBinding` is `{catalogKey, key}` (no `catalogKey` for a code list of its own catalog) and a variant selection attribute's `catalogKey` is that alias, none of them with a `registry` or `namespace`. Dependencies say their `alias`; in `updateCatalogDependencies` it may be left out and defaults to the catalog's key, which is the only alias Epistola accepts for now. Binding a code list of a catalog the draft does not declare is refused with a 400 naming `codeListBinding.catalogKey`. **Action on upgrade:** drop `registry` and `namespace` from these references in REST clients.
