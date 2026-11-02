---
type: fix
scopes: [stencils, api]
title: Deleting a stencil that is still in use through the REST API answers 409, not 500.
---

`deleteStencil` refuses to delete a stencil a template or another stencil still uses, but the REST API answered that refusal with `500 internal-error`. It now answers `409 conflict`, and the problem's `detail` names what uses the stencil; `listStencilUsage` lists it beforehand. The web UI was not affected, and MCP has no stencil delete.
