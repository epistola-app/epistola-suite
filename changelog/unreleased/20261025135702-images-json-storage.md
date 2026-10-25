---
type: feat
scopes: [images, catalog, storage]
title: Images are stored as JSON, every image's bytes once per installation, and read a release like a draft.
---

An image is now stored the way a release keeps it, its bytes linked to it and kept once per installation, whoever uploaded them. The `sensitive` option, which kept an image's bytes apart per tenant and was never offered on the UI, REST or catalog formats, is gone: the upgrade moves such bytes into the one store. The REST image list and download read a release the same way as a draft, paged in the database, and the REST upload ignores its `sensitive` field until the contract drops it. The upgrade converts every image in the migration step; the `assets` table is gone.
