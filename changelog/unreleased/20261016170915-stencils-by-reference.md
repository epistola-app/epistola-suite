---
type: feat
scopes: [stencils, templates, generation, editor]
audience: user
breaking: true
title: Templates use stencils by reference, and a stencil has no versions of its own.
---

A stencil no longer has versions, and templates no longer copy it: an instance names its stencil and keeps only its bindings and placeholder fills, so a change to a stencil reaches every template that uses it, in the same catalog version. A stencil's page lists the templates using it and whether each instance still fits; one that does not (a fill for a placeholder the stencil lost, a required parameter left unbound) blocks publishing until its template is edited. Stencils may contain stencils, and the editor refuses an insert or move that would make a stencil contain itself. A stencil sees only its parameters unless it declares that it reads the template's data, and a placeholder can pass the stencil's parameters to its fill. In the template editor the stencil picker offers stencils, not versions; an instance shows the stencil as it is in the version you work in, and editing it in place changes it for every template at once. **On upgrade** each stencil keeps its draft, or else its latest published content, and an instance pinned to an older stencil version renders the stencil as it is now; an instance whose copy had been edited, or whose stencil is gone, becomes plain content in its place, so nothing that rendered is lost. The upgrade logs every rewrite. **A catalog archive exported from 1.x** imports with the scope it rendered with: each of its stencils reads the template's data, and each placeholder passes the stencil's parameters to its fill.
