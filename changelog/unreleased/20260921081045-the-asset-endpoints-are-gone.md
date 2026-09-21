---
type: feat
scopes: [api]
audience: user
breaking: true
title: The asset endpoints are removed; images have their own.
---

`listAssets`, `uploadAsset`, `downloadAssetContent` and `deleteAsset` are gone, with `AssetDto` and `AssetListResponse`. `/images` replaces them and is strictly better: it addresses an image by its slug, a plain string, and lists images only, where the asset operations mixed in the font-face binaries that back a font family. The asset operations could not describe an image named readably — `AssetDto.id` is `format: uuid` — so they had begun omitting the `id` for those, and for font faces too once a face's key became its content hash. Keeping them meant carrying that hole; removing them closes it. The `assets` table is untouched: it is the content-addressed store images and font faces both draw on, and from wire v7 a font face's binary is deliberately not addressable from outside. These have shipped since contract 1.0.0 and suite 1.1.0 serves all four, so this breaks a live GA surface, which normally needs a major release and a deprecation path. It ships in a MINOR as an explicit decision, resting on one thing: nothing calls them. No integration we know of uses the asset operations, the UI never did — it uses UI routes, not `/api/**` — and the only client that could was generated from the spec. Holding them to 2.0.0 would mean carrying a surface that cannot name half the rows it returns, for an audience of nobody.
