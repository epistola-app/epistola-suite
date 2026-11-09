---
type: feat
scopes: [security, api]
issues: [749]
title: Each calling application receives only the documents it requested.
---

A tenant's API keys now belong to applications, managed under Operations → Applications. An
application holds the roles its keys act with and owns the documents it requests, so rotating a key
no longer changes who owns them, and a new key carries on collecting where the old one stopped. An
application that is not marked as seeing every document collects,
lists, downloads and deletes only its own results; another application's are not found. On upgrade,
every existing key becomes an application of its own that still sees every document, so integrations
keep working unchanged until an administrator narrows them on the application's page. Documents from
before the upgrade belong to the application of a tenant's only key; with several keys, an
administrator chooses which application owns them.
