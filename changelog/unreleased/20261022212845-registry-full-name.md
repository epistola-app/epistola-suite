---
type: feat
scopes: [catalog, api, mcp]
breaking: true
maturity: alpha
title: A container registry keeps a release under the catalog's full name, and local catalogs travel.
---

A release pushed to a container registry is stored as `<prefix>/<authority>/<namespace>/<key>:<version>`, so catalogs of two authorities with one namespace and key no longer share a repository. The REST registry paths name the authority (`…/release-registries/{name}/catalogs/{registry}/{namespace}/{catalogKey}/…`), `ReleasePublicationDto.registry` is now `releaseRegistry`, and a registry listing names both `releaseRegistry` and the catalog's `registry`. MCP `list_registry_releases` and `install_release_from_registry` take an optional `authority`. A catalog under `local` is now exported, snapshotted and moved through container registries like any other; only Epistola Exchange refuses it. **Action on upgrade:** releases pushed before are under `<prefix>/<namespace>/<key>`; push them again to find them under the new name.
