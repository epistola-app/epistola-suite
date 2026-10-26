---
type: feat
scopes: [catalog]
title: Moving a catalog to new rendering defaults is a change you can release.
---

A draft renders with the newest rendering defaults; its last release keeps the ones it was cut
with. When those differ, the draft has something to release even if nothing else changed: the next
release shows "Rendering defaults V6 → V7: documents may look different", with what the new version
changes, and it is at least a minor release. In the web UI, REST and MCP alike. A draft with the same
defaults and no other change still has nothing to release.
