---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: A release is identified by its content hash, whatever number or route it came by.
---

New releases are fingerprinted with V6 (`v6:…`), which covers a release's content only, not the catalog's name, number or presentation, and every archive Epistola writes (exports, the Exchange, registries, snapshots) names a release by it, as catalog wire v8 requires. Releases from before keep the fingerprint they were cut with and get their content hash beside it, computed at startup. Receiving a release the tenant has, a dependency's pin, rendering a pinned release, the merge base and a release's base and merged releases are all decided by it, so a release fingerprinted V5 here and the same release received as V6, or under another number, are one release. Consumers comparing release fingerprints must expect `v6:`.
