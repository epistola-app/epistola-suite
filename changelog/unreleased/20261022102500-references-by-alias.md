---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: A catalog names the catalogs it uses by an alias in what it releases and exports.
---

Catalog archives (wire v8) declare each catalog a catalog uses once, in `catalogDependencies`, under an alias with its registry, namespace and key, and every reference into another catalog (a theme, stencil, image, font, code list, a variant attribute such as `system.locale`, the default theme) names that alias and no registry or namespace, so renaming or mirroring a dependency changes its declaration, not the content. Epistola declares every catalog under its key, so references keep their values; an archive naming a dependency by another alias is refused for now. A migration drops namespace and registry from stored references, which resolve as before. A variant using another catalog's attribute now declares that catalog.
