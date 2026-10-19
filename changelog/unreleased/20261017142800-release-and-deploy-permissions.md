---
type: feat
scopes: [security]
breaking: true
title: Publishing and deploying have their own permissions; template and stencil publish are gone.
---

Following contract 2.0, `CATALOG_RELEASE` and `ENVIRONMENT_DEPLOY` replace `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH`. `CATALOG_RELEASE` publishes the next version, makes parallel work the next version and discards work; `ENVIRONMENT_DEPLOY` deploys and undeploys versions and chooses which version an environment uses or follows. The content publisher role grants both, so nobody's access changes. Marking content ready, starting and renaming parallel work and editing a catalog's dependencies are part of editing (`TEMPLATE_EDIT`), so an author can do them. A refusal names the new permission (`catalog-release`, `environment-deploy`).
