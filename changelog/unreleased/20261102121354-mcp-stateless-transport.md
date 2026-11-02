---
type: fix
scopes: [mcp]
issues: [1241]
title: The MCP endpoint works with more than one replica, without sticky routing.
---

`/api/mcp` kept MCP sessions in the memory of the pod that answered `initialize`, so with two or
more replicas every request routed to another pod failed with `404 Session not found` and MCP
clients reported the server as failed to connect. The endpoint now uses the stateless Streamable
HTTP transport: it assigns no session and any replica serves any request. A source-IP or
`Mcp-Session-Id` affinity route added for MCP can be removed. `GET /api/mcp`, an event stream no
tool used, now answers `405`.
