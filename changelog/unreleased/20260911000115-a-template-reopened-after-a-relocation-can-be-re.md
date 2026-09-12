---
type: fix
scopes: [catalogs]
audience: user
title: A template reopened after a relocation can be republished again.
---

Reopening copied the published model verbatim, so the new draft still named the address the moved resource had left; publish validation then looked for it there and refused, leaving the template permanently unpublishable with nothing in the move preview hinting at it. Mutable content is now canonicalised through the alias when it is written, while published versions keep their original bytes.
