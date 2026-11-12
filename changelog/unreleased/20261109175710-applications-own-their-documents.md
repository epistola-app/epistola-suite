---
type: feat
scopes: [security, api]
issues: [749]
title: API keys belong to applications, and each application receives only the documents it requested.
---

Under Operations → Applications, an application holds the roles its keys act with and owns the documents it requests, so rotating a key no longer changes who owns them. An application that is not marked as seeing every document collects, lists, downloads and deletes only its own. On upgrade every existing key becomes an application of its own that still sees every document, so integrations keep working until an administrator narrows them. Documents from before belong to the application of a tenant's only key; with several keys, an administrator chooses. This upgrade cannot be rolled back.
