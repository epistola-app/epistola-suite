---
type: feat
scopes: [catalogs, api, mcp]
audience: user
title: An address a resource has moved away from keeps working.
---

Every surface resolves a resource's previous address to its canonical one, so an integration that generates from a template does not break when someone reorganises catalogs. REST and MCP resolve before dispatching — through an authorisation-free step, so a generate-only key is not refused at the resolution itself — and a UI `GET` redirects to the canonical URL, variants, versions and contract included. Content naming a renamed theme, font or asset still finds it, and a renamed template's generation history stays findable.
