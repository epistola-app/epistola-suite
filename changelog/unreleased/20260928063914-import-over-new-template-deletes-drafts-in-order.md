---
type: fix
scopes: [catalog]
audience: user
issues: [1014]
title: Importing a catalog over a newly created template no longer fails.
---

Importing a catalog ZIP over a template that had been created but never
published failed with a generic 500: the import deleted the template's draft
contract while its draft version still referenced it, and the database
refused. Draft versions are now deleted first, so the import completes and
the template is updated as expected.
