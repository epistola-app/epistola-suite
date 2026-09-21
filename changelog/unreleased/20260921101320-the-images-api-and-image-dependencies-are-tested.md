---
type: test
scopes: [api, catalog]
audience: dev
title: The images API and image dependencies have tests.
---

`/images` replaced the asset operations and shipped with no integration coverage — the test file for the endpoints it succeeded was deleted with them. It now has one: a readable slug round-trips through upload, download and delete; listing reports images only, proven by seeding a font alongside one and checking its face binary stays out; a font binary offered to the image upload is refused; and an image in another catalog is a 404 rather than someone else's.

The importer's dependency probe had no test at all, which is how a qualified image dependency came to read as missing in every case — the probe was rewritten to yield `image:<catalog>:<slug>` while the check still asked for `asset:<slug>`. Two tests now pin both directions: found when the image is installed in the catalog the archive names, and refused by name when it is not.
