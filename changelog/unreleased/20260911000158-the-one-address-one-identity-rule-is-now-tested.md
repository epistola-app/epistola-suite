---
type: test
scopes: [catalogs]
audience: dev
title: The one-address-one-identity rule is now tested.
---

The sync trigger's insert path reads the registry then writes it, and nothing in the trigger stops two transactions registering different identities at the same address — the unique constraint on the address does. The test makes the overlap real rather than hoping for it: one create holds an open transaction with the address's index entry uncommitted while the second attempts it, proving the contender blocks and is then refused.
