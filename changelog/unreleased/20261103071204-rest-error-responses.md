---
type: feat
scopes: [api, security]
breaking: true
title: REST errors use the contract's registered problem types, and a client mistake never answers 500.
---

A body or parameter that breaks the contract, a NUL character or a refused header answers `400`, and an unsupported upload `415`. Every refusal is `https://epistola.app/errors/forbidden`, naming `requiredPermission` or `requiredPlatformRole`. Types the contract does not register become the generic type of their status (`not-found`, `conflict`, `validation-error`). Problems name resources by key (`templateKey`, not `templateId`), a `not-found` says what was `missing`, each validation error carries a `code`, and every problem links a `help` page. **Action on upgrade:** a client that switches on old problem types or members switches on `status`, the generic type and `missing`.
