---
type: refactor
scopes: [catalog]
audience: dev
title: Every read and write of catalog content names the version it means.
---

Every version of a catalog lives in the same tables, and which one a statement read used to depend on settings a message left on its database transaction, inherited by whatever it dispatched. Now each statement names the version it reads or writes by its `version_id` (ADR 0049): a message carries the version it addresses, its handler resolves it, and a message that names none means its catalog's next version. Nothing changes for callers; the upgrade from 2.0.0-alpha.1 needs its repair script (see the alpha.1 repair entry).
