---
type: fix
scopes: [exchange]
audience: user
title: Releasing no longer offers to publish when there is nowhere to publish to.
---

Asking to publish made the namespace field required, and with no Exchange connection — or an organization that has granted none — that field had no options, so the release form refused to submit and said only that a selection was needed, with no way forward. Publishing is now shown as unavailable with the reason and whose problem it is to fix: connect the tenant, ask the organization for a namespace, or re-point a catalog bound to one the connection has since lost. Releasing locally is unaffected, and the publish control is no longer shown at all to someone without permission to use it.
