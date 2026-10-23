---
type: fix
scopes: [catalog, ui]
title: Publishing a draft with nothing changed is refused in the web UI, as over REST and MCP.
---

A draft whose content is the same as the release it is based on has nothing to publish: the publish dialog already offered no Publish for it, and a request sent anyway now says the draft has nothing to release instead of publishing new notes as a new version.
