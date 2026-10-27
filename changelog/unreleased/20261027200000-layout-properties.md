---
type: feat
scopes: [editor, generation]
audience: user
title: Min height, vertical alignment, column styles and cell styles render in the PDF and have editor controls.
---

The layout properties contract `2.0.0-alpha.3` declares (contract #97) now do what they say. A
container takes a **Min Height**: it is at least that tall and grows with taller content. Table
cells, data table columns and columns take a **Vertical Align** (top, middle or bottom). Each column
of a columns block can have its own background, padding, border and vertical alignment (**Column
Styles** in the inspector), and the columns of a row are equally tall, so a column's background
fills the row. Selected table cells can be styled again (**Cell Style**: background, padding,
border, vertical align); the per-cell text align is gone, as the contract does not allow it.
Templates that use none of these render exactly as before.
