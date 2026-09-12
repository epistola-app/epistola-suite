---
type: fix
scopes: [backup]
audience: dev
title: A restored tenant keeps its resource identities and its aliases.
---

`RestoreTenantSnapshot` purges and re-imports, and neither identity table was in the snapshot — so a restore handed every resource a fresh `resource_id`. That dangled the `template_resource_id` on every generation record (deliberately unprotected by a foreign key, so it just stops joining) and cascade-deleted every retained alias, meaning historical addresses silently stopped resolving. Neither is visible until someone looks for a document by its template or follows a bookmark to a moved resource. The snapshot archive now carries `identities.json` beside the catalog ZIPs (schema version 2), and restore plants those identities before importing so the sync trigger adopts each one. A version-1 archive restores exactly as it did before.
