---
type: feat
scopes: [editor, generation]
title: A conditional has an else branch.
---

A conditional block has a second slot, `else`, beside its body: the body renders when the condition
holds, the else slot when it does not. One choice no longer needs two mirrored conditionals. A new
conditional is created with both; a conditional made earlier keeps the body it has.
