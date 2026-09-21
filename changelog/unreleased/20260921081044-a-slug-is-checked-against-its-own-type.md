---
type: fix
scopes: [catalog]
audience: dev
title: A resource slug is checked against its own type's limit.
---

Catalog validation checked one loose pattern for every resource type and no length at all, so a catalog naming a theme with 30 characters passed, published, and then failed on install with a `value too long for type character varying(20)` database error — the publisher got a green light and every consumer got a crash. `CatalogSlugs` declared the per-type bounds all along and nothing read it; validation now looks the rule up by type and reports `CATALOG_RESOURCE_SLUG_INVALID` naming the limit. Because Exchange's publication gate and the suite's importer run the same validator, the mistake is caught before the upload. Every bound mirrors one the suite already enforces, so no archive any suite produced is newly refused.
