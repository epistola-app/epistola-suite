---
type: feat
scopes: [security]
breaking: true
title: Releasing and deploying have their own permissions; template and stencil publish are gone.
---

Following contract 2.0, `CATALOG_RELEASE` (cut a release, discard a draft) and `ENVIRONMENT_DEPLOY` (choose what an environment serves) replace `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH`. The content publisher role grants both, so nobody's access changes. Opening and renumbering a draft and editing its dependencies are part of editing (`TEMPLATE_EDIT`), so an author can do them. A refusal names the new permission (`catalog-release`, `environment-deploy`).
