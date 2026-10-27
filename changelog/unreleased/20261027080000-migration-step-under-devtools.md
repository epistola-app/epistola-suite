---
type: fix
scopes: [migrations]
audience: dev
title: The migration step runs under `bootRun` again.
---

Started through `bootRun` (devtools), the migration step (`EPISTOLA_MIGRATION_MODE=migrate` or
`--migrate`) failed with `Invalid epistola.migration.mode='migrate,migrate'`: devtools' restart calls
`main` again with the arguments the migration context was started with, and the launcher appended
the mode a second time. It now drops its own options from the arguments before setting them. The
Helm Job and init container, and `java -jar`, were never affected: they pass the mode by environment
variable and run without devtools.
