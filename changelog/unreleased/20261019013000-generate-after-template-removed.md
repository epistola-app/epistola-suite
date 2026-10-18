---
type: fix
scopes: [generation]
title: A template removed from the next version still generates from the published version that has it.
---

Deleting a template while working on the next version made every request for it fail, even from an environment still serving a published version with that template. Requests now record the template as the version they render has it.
