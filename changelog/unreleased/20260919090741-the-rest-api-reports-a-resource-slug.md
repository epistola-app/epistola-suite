---
type: feat
scopes: [api]
audience: user
title: The REST API reports a resource's slug, not only its id.
---

Ten read models addressed a resource by a field called `id` whose value was always a readable slug — `TemplateDto.id` was `invoice`, documented in the spec as _"Slug identifier of the template"_. Seven others already called the same thing `slug`, and the portable catalog format uses `slug` for every resource type. Those responses now carry both: `slug` alongside the `id` or `key` it duplicates, with the same value. Nothing changes for a client reading `id`, and a client can adopt `slug` today. The deprecated halves go in the next major, along with the request bodies and path parameter names, which are unchanged here.
