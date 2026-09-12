---
type: feat
scopes: [exchange]
audience: dev
title: Suite can read Exchange's catalog listings, not only write to them.
---

The published client has carried `searchCatalogs`, `getCatalog`, `listCatalogReleases` and `downloadCatalogRelease` since 0.1.0 and none of them had ever been called. Three things the generated code cannot do on its own are handled here: it applies no authentication despite declaring it, it hands back the archive route as an unexpanded path template, and it types the archive as a `File` through a JSON converter. Release archives also get their own HTTP client — `RestClient.mutate()` copies the request factory, so the read timeout is exactly what it cannot change, and a multi-megabyte download should not be governed by the thirty seconds sized for an OAuth round-trip.
