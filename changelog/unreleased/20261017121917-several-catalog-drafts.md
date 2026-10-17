---
type: feat
scopes: [catalog, rest-api]
audience: user
title: A catalog can have several drafts at once, each its own copy.
---

Next to the draft you are working on you can open another, say 2.0.0 for a large change while 1.5.0 ships a few fixes. Each draft is an independent copy of its base release, of another draft or of any release, and keeps its own content, ready marks and dependencies; resources keep their identity across drafts. A draft whose base is older than a release below its number is behind that release, and releasing it asks you to acknowledge each such release. Releasing a draft with another draft above it closes it instead of opening the next patch. Over REST, `createCatalogVersion` and `deleteCatalogVersion` now work, and every `…/versions/{catalogVersion}/…` path reads and writes the draft it names; the web UI and MCP work on the highest draft.
