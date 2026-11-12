---
type: fix
scopes: [catalog, themes, code-lists, environments]
audience: user
title: Every resource now refuses the same reserved keys, such as `new`, when it is created.
---

Templates, variants and stencils refused a few reserved words as their key, environments a shorter
list, and themes, code lists, attributes, images and fonts none. A theme or code list keyed `new`
could then be created and never opened, as `new` is where their list page opens the create form.
One list now applies to every resource — `admin`, `api`, `www`, `system`, `internal`, `null`,
`undefined`, `new`, `create`, `edit` and `delete` — in the create dialogs, the REST API and MCP.
It is checked only when a key is chosen: a resource that already has such a key, or arrives with one
in a catalog import, keeps working, and a catalog import now accepts one where it used to fail.
