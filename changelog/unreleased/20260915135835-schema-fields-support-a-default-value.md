---
type: feat
scopes: [data-contract]
audience: user
issues: [836]
title: Schema fields support a `default` value.
---

The Schema Definition form has a **Default value** control for scalar fields, checked against the
field's type, format and range; schemas that already use `default` now open in the visual editor
instead of read-only JSON mode. Generation, preview and data validation fill in a field's default
when the data leaves it out, and data examples are validated the same way, so a required field with
a default is not a breaking change — removing that default is. Two effects on existing contracts: one
that already declared a `default` now renders it where the field used to be left blank, and a
`default` that does not match its own schema, accepted until now, is rejected on save, publish and
catalog import. The editor also checks examples against `minimum` and `maximum` before saving.
