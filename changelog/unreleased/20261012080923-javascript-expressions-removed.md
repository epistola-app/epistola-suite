---
type: fix
scopes: [generation, templates]
breaking: true
title: JavaScript expressions are no longer supported; use JSONata.
---

JavaScript expressions ran without limits on time or memory, so one expression could tie up a render worker. The editor never offered them; they could only arrive through the API, a catalog import or MCP. Saving or importing one is now refused, and a stored one fails to render with a clear error. Nothing is deleted: rewrite such expressions in JSONata. The generation guide has a query that finds them.
