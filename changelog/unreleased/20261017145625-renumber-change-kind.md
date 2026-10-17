---
type: feat
scopes: [catalog]
audience: user
title: Renumbering a draft warns when the number is too low for its changes, and a release remembers its base.
---

Renumbering a catalog draft to a patch while it removes a template, narrows a data contract, drops a stencil placeholder or changes an attribute, code list or dependency now warns and lists each change, as does a minor for breaking changes or a patch for additions; the renumber goes ahead once acknowledged. A release now records the release its draft started from, so what a release changed is measured against that base. Shown in the renumber dialog, the REST renumber preview and the MCP renumber tool.
