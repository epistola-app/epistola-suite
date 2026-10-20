---
type: fix
scopes: [upgrade, stencils]
title: The 2.0 upgrade report names the parameters a stencil copy reads when it becomes plain content.
---

A stencil instance whose copy differs from its stencil version, or whose stencil is gone, becomes plain content in the upgrade. Plain content gets no stencil parameters, so expressions such as `params.companyName` in it render empty. The upgrade report now names each such instance and the parameters it reads, so they can be changed by hand.
