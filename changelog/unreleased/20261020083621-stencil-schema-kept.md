---
type: fix
scopes: [stencils, api]
title: Saving a stencil's content through the REST API no longer removes its parameters when the request leaves them out.
---

`updateStencilContent` used to clear the parameter schema when `parameterSchema` was omitted or null, so a client that only changed the content silently removed the parameters every template using the stencil binds; since templates use a stencil by reference, all of them lost those values at once. Leaving it out now keeps the schema. To remove the parameters, send a schema that declares none (`{}`). The same holds for creating a stencil and for MCP, and the editor was not affected.
