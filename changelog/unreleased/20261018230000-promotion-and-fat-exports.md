---
type: feat
scopes: [catalog, exchange, api]
audience: user
title: A catalog version can be exported with the catalogs it uses.
---

**Export with what it uses**, on a published version's page or as an option of the export dialog, writes one file with that version and the versions of the catalogs it uses, transitively, so it installs where those catalogs are not, such as an acceptance or production installation without Exchange. Importing it installs what is missing first and leaves what is already there; it is refused, naming each, when it would move a catalog the installation has at another version. Over REST, `importCatalog` accepts such a bundle and reports `installedDependencies` and `presentDependencies`.
