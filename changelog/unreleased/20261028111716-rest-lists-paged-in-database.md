---
type: perf
scopes: [rest-api]
audience: dev
title: REST lists page in the database, so a page costs the page instead of the whole list.
---

Listing tenants, catalogs, catalog versions, environments, and a catalog version's fonts, code lists, attributes, themes and a template's variants used to read the whole list and cut the page out of it. They now read only the page, with its total from the same query. The responses are unchanged; ties in the order are now broken by key, so the same request always gives the same page.
