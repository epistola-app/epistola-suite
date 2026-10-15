---
type: fix
scopes: [fonts]
audience: user
title: The bundled Merriweather font renders upright text upright and italic text italic.
---

The bundled Merriweather files were mislabelled: regular text rendered in Merriweather Italic and
italic text in the upright face, and bold and bold italic were swapped the same way. Documents using
Merriweather now render in the faces they ask for, which changes how existing documents in that font
look. The other bundled families were checked and are unaffected.
