---
type: refactor
scopes: [catalogs]
audience: dev
title: Fonts and assets are keyed by identity, not by where they live.
---

A font face named its family and its backing binary through one shared `catalog_key`, so neither could move without dragging the other's reference along — the reason `asset_catalog_key` was added in the first place. A face now names both by identity, which dissolves that by construction: the extra column is gone, so is the undocumented rule that a face's binary had to live in the family's own catalog, and moving either resource leaves the other untouched. The resource-graph now reports a face's asset in the asset's own catalog rather than assuming the family's.
