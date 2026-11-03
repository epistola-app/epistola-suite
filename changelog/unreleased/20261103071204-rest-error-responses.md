---
type: fix
scopes: [api]
breaking: true
title: The REST API answers client mistakes with the contract's problem types, never a 500.
---

A body breaking the contract's constraints, a missing parameter, an unparseable query string, a header or path the firewall refuses, or a NUL character now answers `400` with a problem, not a `500` or Spring Boot's error body. A taken key answers `409 conflict`, a template a release lacks `404`, and the Consumers API `501 operation-not-implemented`. Types the contract does not register become the generic type of their status: per-resource `…-not-found` types `not-found`, `…-in-use` and `catalog-version-released` `conflict`, `type-mismatch`, `unsupported-sort` and every validation code `validation-error` (`errors[]` names the field), `payload-too-large` and `asset-too-large` `content-too-large`. The members naming the resource stay. **Action on upgrade:** a client switching on those types switches on `status` and the generic type.
