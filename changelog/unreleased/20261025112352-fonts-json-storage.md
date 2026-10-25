---
type: feat
scopes: [fonts, catalog, storage]
title: Fonts are stored as JSON, their faces as the font's own files, and read a release like a draft.
---

A font family is now stored the way a release keeps it, and each face's file belongs to the font rather than being an uploaded image: font files no longer appear as assets anywhere, and deleting a font takes its files with it once nothing else uses them. The bundled system fonts are stored once per installation, so every face is found the same way. The REST font reads take a release number like a draft, in the database. The upgrade to 2.0 converts every font in the migration step, keeps every face's bytes, and removes the asset rows that only held a font file; the `fonts` and `font_variants` tables are gone.
