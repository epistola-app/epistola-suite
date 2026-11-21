---
type: feat
scopes: [ui, generation]
title: A template's page can generate a document from it and follow the job to its PDF.
---

"Generate document" on a template's page asks where to generate from (an environment serving the
catalog, or its latest release), the variant, and the data, starting from the template's first data
example. An environment following a draft of the catalog renders that draft, so deploying a draft to
a test environment is how to generate from it. With the `interactive-generation` toggle on, it can
also ask for the data in a form first: the form opens in the same tab, and submitting or cancelling
it comes back to the job. It is offered to whoever may generate documents in the tenant.

Each job has its own page: what it renders, its status (updating by itself), the data, the fields
changed in the form, and the PDF to download once it is ready. The page's link carries a token
signed for the person who started the job, never a bare job id, so a link that leaks shows nothing
to anyone else.
