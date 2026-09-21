---
type: fix
scopes: [catalog]
audience: user
title: A font face is no longer published as an image.
---

Exporting a catalog containing a font wrote each face's binary twice: once inside the font, where wire v7 puts it, and again as a standalone `image` resource whose media type was `font/ttf` — the separately addressable binary v7 exists to remove. The export listed every row of the `assets` table, which is the content-addressed store images and faces share, so it could not tell them apart. It now exports images only, matching what `/images` lists.

Fixing that uncovered a second fault it had been hiding: the exporter looked a face's bytes up under a key only the _importer_ mints, from the content hash, and skipped silently when it missed — so a font uploaded through this suite, whose faces carry generated keys, would have exported with its binaries absent. A face's bytes are now resolved by content hash, which names the same binary whichever way it arrived.
