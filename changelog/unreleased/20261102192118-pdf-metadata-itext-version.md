---
type: fix
scopes: [generation]
issues: [1245]
title: A PDF's metadata names the iText version that produced it.
---

The engine string in every PDF's metadata said `itext-9.5.0` while the build used iText 9.8.0. It
now reads the version from the iText library itself, so it cannot fall behind an upgrade again.
