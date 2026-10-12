---
type: fix
scopes: [catalog, codelists]
title: Catalog and code-list sources are restricted to what an operator allows.
---

Catalog subscriptions and URL code lists could read any local file or internal address, including the binaries a catalog's manifest names. Every such read is now checked: network sources must resolve to public addresses (redirects included), file: sources work only inside directories listed in `epistola.catalog.file-roots`, classpath: reads only the catalogs Epistola ships, and a manifest may name its resources only by a path relative to itself. On upgrade, an installation that reads catalogs from disk must set `file-roots`, and one whose catalogs or code lists live on an intranet must set `epistola.catalog.outbound.allow-private-networks` or `allowed-hosts`. The Helm chart exposes these as `catalog.fileRoots`, `catalog.outbound.allowPrivateNetworks` and `catalog.outbound.allowedHosts`. Responses are capped at `max-decompressed-size`, and a failed code-list refresh no longer shows the raw error.
