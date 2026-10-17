---
type: feat
scopes: [demo]
title: The demo catalog shows stencils inside stencils and the scope rules of stencils by reference.
---

The demo catalog (6.1.0) declares its dependency on the system catalog, so an installed copy exports. Its Letter Shell stencil now contains the Letterhead stencil and passes its company name to the body that templates fill; a new Customer Address stencil reads the using template's data; and a new Stencil Showcase template puts them together. A data example may leave out a required field that has a default, as rendering fills it in.
