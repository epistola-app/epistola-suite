---
type: feat
scopes: [stencils, templates, generation, editor]
audience: user
breaking: true
title: Templates use stencils by reference, and a stencil has one editable version.
---

A stencil no longer has versions, and templates no longer copy it: an instance names its stencil and keeps only its bindings and placeholder fills, so editing a stencil changes every template using it. Publishing a template refuses an instance that no longer fits (a fill for a placeholder the stencil lost, a required parameter left unbound), and a stencil's page lists the templates using it and whether each fits. Stencils may contain stencils. A stencil sees only its parameters unless it declares it reads the template's data, and a placeholder can pass the stencil's parameters to its fill. On upgrade each stencil keeps its draft, or else its latest published content, and instances pinned to an older version render the current stencil; the upgrade report lists every rewrite. In the template editor the stencil picker offers stencils, not versions; an instance shows the stencil's working copy, and editing it in place saves that working copy (and can mark it ready) for every template at once. The editor refuses an insert or move that would make a stencil contain itself.
