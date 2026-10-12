---
type: fix
scopes: [generation, templates]
breaking: true
title: JavaScript expressions are no longer supported; use JSONata.
---

Expressions with `language: "javascript"` were evaluated without any limit on running time or memory, so a single expression could tie up a shared render worker. The editor never offered them, so they could only arrive through the REST API, a catalog import or MCP authoring. The JavaScript engine has been removed: saving, importing or authoring a template or stencil that contains a JavaScript expression is now rejected with `TEMPLATE_EXPRESSION_INVALID`, and a template stored by an earlier release still opens but fails to render with a clear error rather than being evaluated as another language. Nothing is migrated or deleted; rewrite such expressions in JSONata. The generation guide has a query that lists affected template and stencil versions.
