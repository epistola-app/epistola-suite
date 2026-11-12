---
type: fix
scopes: [catalog]
audience: user
title: Resource keys follow the catalog format's rule, so a valid catalog installs and what you create can be released.
---

A catalog whose template, theme or other resource has a key starting with a digit, such as
`2024-letter`, is valid in the catalog format but failed to install: the install was aborted with
"must match pattern: start with letter". It now installs, and documents can be generated from such a
template. The other way round, a code list or font could be created with a key the catalog format
does not allow — longer than 50 characters, or a font key of two — and the release then failed with
"non-publishable content". A new key is now checked against that rule when it is created, in the
create dialogs, the REST API and MCP, and a new key may start with a digit. Keys created before keep
working.
