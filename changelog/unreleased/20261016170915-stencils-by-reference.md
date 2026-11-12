---
type: feat
scopes: [stencils, templates, generation, editor]
audience: user
breaking: true
title: Templates use stencils by reference, and stencils can contain stencils.
---

A stencil no longer has versions and templates no longer copy it: a change to a stencil reaches every template that uses it. A stencil's page lists the templates using it and whether each still fits; one that does not, such as a binding to a renamed parameter or of the wrong type, blocks publishing until its template is fixed. A stencil may contain stencils, sees only its parameters unless it declares that it reads the template's data, and a placeholder can pass the stencil's parameters to its fill. **On upgrade** an instance pinned to an older stencil version renders the stencil as it is now, and an instance whose copy had been edited becomes plain content; the upgrade report lists each case.
