---
type: fix
scopes: [catalog, exchange]
audience: user
title: Warning boxes render as warnings again.
---

An alert lays out its severity icon beside its content, so it takes one block; five alerts written as loose bold-plus-text became a row of narrow columns instead, with parts of the message pushed outside the dialog. They now use the design system's title-and-body structure. The "this release can no longer be published" message was also missing from the catalog page entirely — the markup edit that added it silently did not apply.
