---
type: feat
scopes: [rest-api]
audience: user
breaking: true
title: The REST API addresses a catalog by registry, namespace and key, and its content by catalog version.
---

Every catalog resource is now reached under `/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}/…`, with `~` for the installation's default registry. Writes go to the draft the address names and are refused with 409 on a released version; reads of a released version answer with what that release holds. Templates, variants, stencils and catalogs are marked ready instead of published, `getCatalogChanges` lists what a release would contain, and releasing a version opens the next draft. Deploying a release with its dependencies, and importing under another name or into a named draft, are refused with 501 until they are supported.
