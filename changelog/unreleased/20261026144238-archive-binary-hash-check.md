---
type: fix
scopes: [catalog]
title: A received catalog whose file does not match its declared hash is refused.
---

Importing or installing a catalog archive now checks every binary (an image, a font face) against
the `contentHash` its resource declares, and refuses the archive with
`CATALOG_ASSET_CONTENT_HASH_MISMATCH` when the bytes differ. Before, the contract's validator skipped
that check, so an archive could claim to carry bytes it did not.
