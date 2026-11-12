---
type: fix
scopes: [catalog, themes]
audience: user
title: Changing the house theme no longer leaves catalogs tied to the old one.
---

"Apply house theme to all catalogs" kept every catalog declaring the catalog the replaced theme came
from, even when nothing used it any more, and that declaration could hold publishing until the old
theme's catalog was published too. It is now dropped where nothing else uses it. Moving the house
theme to a catalog created while the old one was the house theme no longer fails as "catalogs never
use each other". Deleting the catalog that holds the house theme is refused with a message saying so,
instead of a database error.
