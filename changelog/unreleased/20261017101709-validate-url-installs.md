---
type: fix
scopes: [catalog]
breaking: true
title: A catalog installed from a URL is validated as a ZIP import is.
---

Installing or upgrading a subscribed catalog from its URL now holds it to every rule of a valid catalog, as importing a ZIP always did, inside the install's transaction: a catalog that breaks one, for instance by referencing another catalog it does not declare, is refused whole, with the findings, and nothing of it is installed. Before, such a catalog installed and was refused only later, on export or release.
