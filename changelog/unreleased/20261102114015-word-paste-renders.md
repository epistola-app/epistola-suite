---
type: fix
scopes: [generation, editor]
audience: user
title: Text pasted from Word no longer fails a PDF/A document, and a character no font can draw is named.
---

Word's manual line break, page break, special hyphens and Symbol or Wingdings bullets failed a PDF/A document with "The '' glyph isn't defined in embedded font". They now render as a line break, a hyphen and a bullet, from template text and data alike, and the editor converts them as you paste. Any other character no font can draw, such as an emoji, still fails a PDF/A document, but the message names the character, the font and where it is.
