---
type: feat
scopes: [exchange]
audience: user
title: Publishing asks which namespace when there is no default.
---

A connection granting several namespaces leaves the tenant default unset, so a first release had nowhere to go and simply waited. The release dialog and the catalog's publish action now offer the granted namespaces directly, so choosing where a catalog publishes is part of publishing it rather than a setting to go and find. Moving an already-bound catalog stays a separate, management-level action.
