---
type: fix
scopes: [catalog]
audience: user
title: A reference into a catalog of another namespace keeps naming that namespace.
---

A catalog that refers to a resource in a catalog of another namespace or registry, such as a theme,
code list or font in the bundled `system` catalog (namespace `epistola`) or a stencil in a catalog
of another publisher, now names that namespace (and the registry, where it differs) in what it
releases and exports. Before, such a reference named the other catalog by key alone, which on the
wire means a catalog in the referring catalog's own namespace. This covers a catalog's default theme, a
template's theme, a code-list binding, a theme override, a stencil insertion and a font in a template, stencil or theme.
An import, a draft started from a release and an export of it keep the reference as it came, and the
resource fingerprints as it did in the release. An image reference still names its catalog by key
only, as the wire allows nothing more. A default theme in another catalog of the same registry no
longer repeats that registry, as no other reference does.
