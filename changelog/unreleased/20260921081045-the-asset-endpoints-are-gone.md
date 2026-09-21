---
type: feat
scopes: [api]
audience: user
breaking: true
title: The asset endpoints are removed; images have their own.
---

`listAssets`, `uploadAsset`, `downloadAssetContent` and `deleteAsset` are gone, with `AssetDto` and `AssetListResponse`. `/images` replaces them and is strictly better: it addresses an image by its slug, a plain string, and lists images only, where the asset operations mixed in the font-face binaries that back a font family. The asset operations could not describe an image named readably — `AssetDto.id` is `format: uuid` — so they had begun omitting the `id` for those, and for font faces too once a face's key became its content hash. Keeping them meant carrying that hole; removing them closes it. The `assets` table is untouched: it is the content-addressed store images and font faces both draw on, and from wire v7 a font face's binary is deliberately not addressable from outside. These have shipped since contract 1.0.0, so this is a real break of a GA surface, and it goes out in a MINOR by decision rather than waiting for 2.0.0: nothing consumes them. No known integration calls the asset operations, the UI never did — it uses UI routes — and the only client that could was generated from the spec. Carrying them to a major would mean keeping a surface that cannot name half the rows it returns, for an audience of nobody.
