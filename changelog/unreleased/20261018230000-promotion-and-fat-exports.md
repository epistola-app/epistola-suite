---
type: feat
scopes: [catalog, exchange, api]
audience: user
title: A catalog version can be exported with the catalogs it uses.
---

**Export with what it uses**, on a published version's page or as an option of the export dialog, writes one file with that version and the versions of the catalogs it uses, transitively, so it installs where those catalogs are not, such as an acceptance or production installation without Exchange. The versions it uses travel inside the catalog's own file. Importing it installs what is missing first and leaves what is already there; it is refused when a catalog the installation has holds that version with other content.
