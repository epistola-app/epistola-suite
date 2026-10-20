---
type: feat
scopes: [catalog]
audience: user
title: The default catalog can be deleted like any other catalog.
---

A new tenant still starts with a catalog called `default`, but nothing depends on it any more: it can be deleted once nothing uses it, like any other catalog. The tenant home's Getting started points at a catalog the tenant has, or asks to create one. Only the system catalog cannot be deleted, since the suite installs it in every tenant.
