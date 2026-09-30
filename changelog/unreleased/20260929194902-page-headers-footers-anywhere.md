---
type: feat
scopes: [generation, editor, catalog]
audience: user
issues: [1020]
title: Page headers and footers can go anywhere, in any number.
---

A template may now hold any number of page headers and footers, anywhere in the document: inside a stencil (a letterhead that carries the letter's header and footer), a conditional or a loop. Page breaks divide the document into sections. A header applies to what comes after it, from its own page at the start of a section and from the next page after content; several headers in a row form a first-page variant. A footer applies from the page it lands on until another lands, and the first footer also covers the pages before it. The editor labels each header and footer with the pages it gets. Existing documents keep their layout, and versions published earlier keep rendering as before. Catalogs that use several footers need this version to import correctly.
