---
type: feat
scopes: [catalogs, api, mcp]
audience: user
title: Old addresses keep working after a move.
---

Only export and the resource graph consulted aliases; every other surface answered 404 at a moved template's, stencil's or attribute's previous `(catalog, key)`, which would have broken each integration generating from a moved template. REST and MCP now resolve the address to the canonical one before dispatching — through an authorisation-free `ResolveCanonicalResourceAddress`, so a generate-only key is not refused at the resolution step — and UI `GET`s redirect to the canonical URL, variants and versions included.
