---
type: feat
scopes: [catalog, templates]
audience: user
breaking: true
issues: [988]
title: Templates are no longer versioned; you work in a catalog's next release and publish it.
---

In 1.x every template, data contract and stencil had versions of its own. In 2.0 a catalog's release is the only version. Everyday work goes into the catalog's **Next release**. **Publish…**, on every page of the catalog, lists what changed by name, says what that means for whoever uses the catalog, and numbers the release from it: corrections raise the patch, additions the minor, breaking changes the major. A large change that is not ready goes into **parallel work**, a named draft you can try on a test environment and publish on its own; a fix to an older release is parallel work started from it. **Publish several…** on the catalogs list publishes catalogs that build on each other in one go, dependencies first, and offers to deploy them. Over REST and MCP you mark changes ready before releasing; the web UI does that for you.
