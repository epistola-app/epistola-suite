---
type: feat
scopes: [catalog, api, mcp]
title: A draft is numbered when it is published; every catalog has one next version, and long-term work next to it.
---

Following contract ADR 0002, amendment 2. Everyday work goes into a catalog's **next version**, which has no number until it is published: publishing picks the number from what changed since the latest release (fixes a patch, additions a minor, breaking changes a major), and may raise it but never lower it. Only what others rely on counts: a catalog starting to use another is an addition, and moving to a newer version of a catalog it uses is breaking only when that version breaks something it uses, whatever the jump in that catalog's number. After publishing, the next version carries on. A large change that is not ready goes into **long-term work**, a named draft started from the next version or a published version and kept apart until it is ready; it is not published yet (making it the next version follows). Discarding the next version throws its changes away and starts it again from the latest published version.

Drafts lose their numbers on upgrade: the highest-numbered draft becomes the next version, any other becomes long-term work named after its old number, with its content and base kept. Renumbering and the in-between version ("fix what is deployed") are gone; hotfixes return with ADR 0001.

REST: `{catalogVersion}` takes `next`, a draft id or a release number; `createCatalogDraft`, `renameCatalogDraft`, `deleteCatalogDraft`, `previewCatalogDraftRelease` and `releaseCatalogDraft` replace the numbered draft operations; preview names a draft as `draft`. MCP: `start_long_term_work`, `rename_long_term_work`, `discard_catalog_work`, `preview_release_number` and `release_next_version` replace the numbered version tools, and `catalogVersion` is `next` or a draft id. The web UI addresses long-term work as `letters@<draft id>`.
