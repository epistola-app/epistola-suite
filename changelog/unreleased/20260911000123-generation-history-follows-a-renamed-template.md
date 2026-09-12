---
type: fix
scopes: [documents]
audience: user
title: Generation history follows a renamed template.
---

Listing and counting a template's documents matched on the template key alone, so a document generated before its template was renamed vanished from the template's history. Both queries now also match on the `template_resource_id` the insert trigger records, which is what the identity index was added for; rows older than that column still match by key.
