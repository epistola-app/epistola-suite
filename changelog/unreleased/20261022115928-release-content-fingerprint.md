---
type: feat
scopes: [catalog, ui, mcp]
audience: user
title: A release page shows the release's content fingerprint and the releases with the same content.
---

Under "As published", "Content fingerprint" shows what identifies the release, short with the whole of it on hover and a copy button, or "Being computed" while a release from before content fingerprints waits for its own; such a release also shows its original fingerprint and says it has been given one. "The same release as" links the other releases of the tenant with the same content, in any catalog and under any number, such as a release published again with only new notes. A draft's dependencies, and the catalog's Dependencies page, show the name its content uses for each ("Named in content as"), and MCP `list_catalog_releases` returns each release's `contentHash`.
