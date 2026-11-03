---
type: fix
scopes: [ui]
audience: user
title: Card titles keep their own size, without an empty strip beneath them.
---

The page's heading style gave every card title in the page content the page's larger 20px heading
and a 16px margin below it, leaving an empty strip under the title. It now styles only plain
headings in the page, so card titles are the card's own 16px heading again. The catalog page's
headings and the catalog overview on a tenant's home page lose that extra space the same way.
