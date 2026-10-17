---
type: feat
scopes: [rest-api, stencils]
audience: user
breaking: true
title: Stencil usage says whether each instance fits, and a breaking data-model edit is saved to the draft.
---

A stencil change reaches every template that uses it, so nothing is held back any more. `listStencilUsage` reports `fits` and `problems` for each template variant instead of `heldBack`, and marking a stencil ready returns the instances that do not fit as `notFitting`; those block the catalog release until their variant is edited. Updating a template's data model is no longer refused with 409 when the change is breaking: the draft is never rendered, so `forceUpdate` has no effect, and validating data still checks the release generation renders.
