---
type: fix
scopes: [generation, editor]
audience: user
title: Text pasted from Word no longer fails a PDF/A document, and a character no font can draw is named.
---

Word puts characters in text that no font has a glyph for: its manual line break (Shift+Enter), page
break, non-breaking and optional hyphens, and the Symbol or Wingdings bullets of a pasted list. A
PDF/A document containing one failed with "The '' glyph isn't defined in embedded font", where the
quotes held an invisible character. These now render as a line break, a hyphen and a bullet, from
template text and from data alike, and the editor converts them as you paste.

Any other character a font cannot draw, such as an emoji, still fails a PDF/A document, but the
message now names the character, the font that lacks it and where it is: the template block with an
excerpt, or the path into the data. The editor preview shows it instead of a general error.
