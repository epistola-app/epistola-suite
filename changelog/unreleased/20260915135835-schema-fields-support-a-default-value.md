---
type: feat
scopes: [data-contract]
audience: user
issues: [836]
title: Schema fields support a `default` value.
---

The Schema Definition form has a "Default value" control for scalar fields (string, number,
integer, boolean, date, date-time), type-aware and validated against the field's own format and
range, both client- and server-side. Imported schemas using `default` are now represented in the
visual editor instead of falling back to read-only JSON mode; a `default` on an array or object
field still round-trips but stays JSON-only.
