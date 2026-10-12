---
type: fix
scopes: [editor, security]
title: Editor property and example-data paths can no longer write to the JavaScript object prototype.
---

The helpers that set a nested value by dot-path (the inspector's component properties and the data
contract's example form) followed inherited properties, so a path such as `__proto__.x` or
`constructor.prototype.x` — field names come from the data contract — could write to
`Object.prototype` in the author's browser. They now follow own properties only and ignore
`__proto__`; a field named after an inherited property is an ordinary key.
