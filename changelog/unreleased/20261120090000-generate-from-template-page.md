---
type: feat
scopes: [ui, generation]
title: A template's page can generate a document from it and follow the job to its PDF.
---

"Generate document" on a template's page asks where to generate from (an environment serving the
catalog, or its latest release), the variant, and the data, starting from the template's first data
example. With the `interactive-generation` toggle on, it can also ask for the data in a form first:
the form opens in the same tab, and submitting or cancelling it returns to the template page. The
page then follows the job until its PDF can be downloaded. It is offered to whoever may generate
documents in the tenant.

The page follows a job by a token signed for the person who started it, never by a bare job id, so
a link that leaks shows nothing to anyone else.
