---
type: fix
scopes: [generation]
audience: user
issues: [1308]
title: Only PDF/A documents claim PDF/UA-1 conformance.
---

Every generated document carried the PDF/UA-1 identifier, but a template with PDF/A turned off renders
with the standard fonts without embedding them, which PDF/UA-1 does not allow. Those documents no
longer carry the identifier; they are still tagged for screen readers exactly as before. PDF/A
documents, the default for templates, are unchanged and are now validated against PDF/UA-1 and
PDF/A-2b with veraPDF in the build. Turn PDF/A on for a template whose documents must claim PDF/UA.
