---
type: fix
scopes: [editor]
audience: user
title: The table inspector no longer offers cell styles that cannot be saved.
---

Selecting a table cell showed background, text-align and border controls, but the server rejects
per-cell styles (`cellStyles` is not part of the table component in the current template format),
so the draft could not be saved after using them. The controls are hidden, with a note to style the
table or the block inside the cell instead, until the template format supports per-cell styles in
2.0.
