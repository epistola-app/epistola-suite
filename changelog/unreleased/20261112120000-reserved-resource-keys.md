---
type: fix
scopes: [catalog]
audience: user
title: Every resource refuses the same reserved keys, such as `new`.
---

Themes, code lists, attributes, images and fonts accepted any key, so a theme keyed `new` could be created and never opened. Every resource now refuses `admin`, `api`, `www`, `system`, `internal`, `null`, `undefined`, `new`, `create`, `edit` and `delete` when its key is chosen, in the web UI, REST and MCP. A resource that already has such a key, or arrives with one in an import, keeps working.
