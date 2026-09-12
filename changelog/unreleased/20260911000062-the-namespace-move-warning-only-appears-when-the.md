---
type: fix
scopes: [exchange]
audience: user
title: The namespace-move warning only appears when the namespace is actually changed.
---

Opening the catalog's publication settings to change the release policy confronted anyone with a published catalog with a warning about moving it, and a checkbox not to tick. It now follows the selection: shown on a real change, gone again if it is undone. The command already treated an unchanged namespace as a no-op, so only the dialog was noisy.
