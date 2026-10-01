---
type: fix
scopes: [templates, generation, api]
audience: user
issues: [1021]
title: Choosing a variant by attributes looks in the template's own catalog.
---

Generation, batch generation and preview that chose a variant by attributes looked up the template in the `default` catalog, whatever catalog the request named. For a template in another catalog the request failed with `404 NO_MATCHING_VARIANT`, or, when `default` held a template with the same key, silently rendered a variant of the requested template that matched the other template's attributes, which could be the wrong language. Selection now uses the template in the requested catalog.
