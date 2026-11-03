---
type: feat
scopes: [api]
title: A validation error says why each field failed, with a code from the contract.
---

Each entry of a `validation-error`'s `errors[]` carries a `code` from the contract's registry where one applies: the schema keyword a value broke (`required`, `pattern`, `max-length`, `maximum`, `type`, `enum`, ...), a finding in template or stencil content (`stencil-recursion`), or a rule the server applies (`catalog-resources-not-ready`, `catalog-namespace-not-held`). An entry without a registered code has none. The generated clients list the codes as `KnownValidationCodes`.
