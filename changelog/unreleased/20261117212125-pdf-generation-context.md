---
type: feat
scopes: [generation]
title: A generated PDF records where it came from in its metadata.
---

Every generated document now carries its generation context in its XMP metadata: the tenant, catalog, template and variant, the release (or draft) rendered, the environment, the request and batch ids, the render time, the locale and the engine. Anyone holding the PDF can tell what produced it without asking Epistola, and a calling system that keeps its own submission knows which release to send it to again. The submitted data and the correlation id are never included. PDF/A documents stay valid PDF/A-2b and PDF/UA-1; the properties are documented in `docs/generation.md`.
