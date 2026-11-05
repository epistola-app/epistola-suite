---
type: fix
scopes: [generation]
audience: user
issues: [752]
title: The address block is structure-tagged instead of unmarked.
---

The address block is drawn at absolute coordinates outside the normal layout flow, on its own
content stream, with no artifact marking and no structure tree entry — a PDF/UA-1 violation on every
shipped document with an address block. Unlike the page header, footer and preview watermark, the
recipient address is meaningful content, so it is tagged into the structure tree rather than marked
as an artifact, which would have hidden it from screen readers.
