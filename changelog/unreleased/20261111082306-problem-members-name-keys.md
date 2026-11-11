---
type: fix
scopes: [api]
audience: user
breaking: true
title: REST problems name a resource by its key, as every 2.0 body does.
---

A problem's members said `tenantId`, `catalogId`, `templateId`, `variantId`, `environmentId`, `stencilId`, `themeId`, `attributeId`, `fontId`, `codeListId` and `assetId`, although each carries the resource's key. They are now `tenantKey`, `catalogKey`, `templateKey` and so on, as the contract names them in request and response bodies and in `ForbiddenProblemDetail`. `requestId`, `documentId`, `releaseId` and `versionId` keep their names: those are ids. A client that reads which resource a `not-found` problem is about should read `missing`, which the contract defines.
