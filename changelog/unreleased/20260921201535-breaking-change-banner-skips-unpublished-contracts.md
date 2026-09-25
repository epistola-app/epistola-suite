---
type: fix
scopes: [editor]
audience: user
issues: [961]
title: The breaking-change banner no longer fires while authoring a template's first data contract.
---

Adding a required field with no default while authoring a template's first data contract was
flagged as a breaking change, even though no contract had ever been published for that template to
break. The banner now only appears once a contract has actually been published.
