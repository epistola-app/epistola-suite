---
type: fix
scopes: [catalog]
title: The system catalog can no longer be deleted.
---

The catalogs page offered to delete the system catalog, and where nothing used it the delete went through. It did not last: the suite installs the system catalog in every tenant again on every start. It is now refused like the default catalog, and its delete button is disabled. A catalog that is in use was already refused, and still is.
