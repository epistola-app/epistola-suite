---
type: fix
scopes: [security, editor, deps]
title: Jackson security updates, and editor paths can no longer touch the JavaScript object prototype.
---

Jackson is upgraded to 3.1.7 and 2.21.7 for seven upstream CVEs (`EPIS-DEP-2026-002`). A data-contract field name such as `__proto__` can no longer write to `Object.prototype` in the author's browser.
