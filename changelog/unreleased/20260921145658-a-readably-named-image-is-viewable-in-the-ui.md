---
type: fix
scopes: [ui]
audience: user
title: An image named by a readable slug is viewable again.
---

The images page parsed its `assetId` path variable with `UUID.fromString`, so every thumbnail of an image whose key is a name rather than a generated UUID came back 500 — and the delete action on the same route failed the same way. An asset key has been text since #930, and the importer and the REST surface both had this assumption removed; the UI route was missed because nothing exercised it with a text key. The demo catalog names its images `epistola-logo` and `catalog-preview`, which is how it surfaced.
