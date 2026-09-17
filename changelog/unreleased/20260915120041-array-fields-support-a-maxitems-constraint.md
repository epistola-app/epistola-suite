---
type: feat
scopes: [data-contract]
audience: user
issues: [840]
title: Array fields support a `maxItems` constraint.
---

The Schema Definition form now has a "Max items" input alongside "Min items", validated live
(rejecting `maxItems` below `minItems`, and either bound being negative, with an inline message and
a validation banner, both client- and server-side) instead of only on save. Imported schemas with
`maxItems` are now represented in the visual editor instead of falling back to read-only JSON mode.
