---
type: feat
scopes: [catalog]
audience: dev
breaking: true
title: An image is a catalog resource; a binary is not.
---

A catalog addressed its binaries by slug, and an image's slug was a generated UUID that named nothing — the readable name was already on `name`, and the identity was always the bytes. Wire v7 stops naming binaries: an image is a resource with a slug, and the bytes behind it — an image's own, or a font face's — are identified by their content hash. A face carries its binary directly instead of pointing at a separate asset, so a font's binaries are no longer separately addressable. The `assets` table is unchanged and keeps its name: it is the content-addressed store both draw on, which is why a binary stopped being a resource in the first place. Bundled catalogs move to wire v7, and the `system` catalog's release version and fingerprint move with them.
