---
type: fix
scopes: [ui, attributes, code-lists]
audience: user
title: Authors create attributes and code lists in the UI, as they could over REST.
---

The create buttons and forms for attributes and code lists required the tenant-settings
permission, while creating one requires editing reference data. Authors could create them over
REST but not in the UI, and administrators who are not authors were offered a form the save then
refused. The UI now asks for what the save requires.
