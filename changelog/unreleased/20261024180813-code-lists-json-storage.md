---
type: feat
scopes: [code-lists, catalog, storage]
title: Code lists are stored as JSON, and a release's code lists read like a draft's.
---

A code list is now stored the way a release keeps it: its entries and name as the released form, and beside them the source it is refreshed from, once per tenant by content hash. A release's code lists are read through the same queries as a draft's, so the REST and MCP code list reads take a release number and a release's code list opens read-only at `…/catalogs/{namespace}/{key}@1.2.0/code-lists/{key}`. A source's credential is stored apart, encrypted, and named by id; rotating the encryption key re-encrypts it without changing the list, and one no list uses any more is removed by the nightly collector. A refresh that fetches the same entries only records the refresh, so the list keeps its revision and stays ready. The upgrade to 2.0 converts every code list in the migration step; the `code_lists` and `code_list_entries` tables are gone.
