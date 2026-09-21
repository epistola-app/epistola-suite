---
type: feat
scopes: [api, catalog]
audience: user
title: Images have their own API, and a catalog may name them readably.
---

`GET/POST/DELETE /tenants/{t}/catalogs/{c}/images` and `/images/{imageSlug}/content` address an image by its slug — a plain string — and list images only, where the asset operations they succeed mixed in the font-face binaries that back a font family. Installing a catalog that names its images the way a person would, such as `municipality-mark`, now works: the importer refused those because the asset endpoints declare `id` as a UUID and could not describe them. That field is optional now and is simply absent for an image it cannot name, so the deprecated endpoints keep working for everything else. Listing images pages in the database rather than loading a catalog's images to return one page of them. The demo catalog demonstrates it: its logo and preview are `epistola-logo` and `catalog-preview` instead of the generated UUIDs they carried, and its release version and fingerprint move with them.
