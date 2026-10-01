---
type: fix
scopes: [editor]
audience: user
title: Moving a block no longer blanks the text blocks it contains.
---

After dragging or moving a block, the rich text inside it, or in a moved text block itself, could disappear from the canvas until the page was reloaded. The content was never lost, only no longer shown. The text editor now rebuilds itself when a move re-attaches it.
