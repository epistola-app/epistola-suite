---
type: feat
scopes: [stencils, editor]
audience: user
title: A renamed stencil parameter and a binding of the wrong type are caught before you publish.
---

When a stencil's parameter is renamed, templates still bind the old name, which is ignored, so the new parameter rendered empty without a warning if it was optional. Such a template is now reported on the stencil's usage page, and marking it ready or publishing the catalog is refused until it binds the new name. A binding whose type is certain, a literal or a field the template's data contract types, must also match the type the parameter declares: a text bound to a number parameter is reported the same way. Saving **Configure parameters…** in the editor now drops bindings for parameters the stencil no longer has.
