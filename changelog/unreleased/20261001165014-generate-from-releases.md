---
type: feat
scopes: [generation, catalog]
audience: user
breaking: true
title: Documents are generated from a catalog release, never from a template version or the working copy.
---

Generating a document now renders the latest release of the template's catalog, chosen when the
request is accepted, so a release cut while it waits in the queue does not change what it renders.
The theme, fonts, images and data contract all come from that release, and a resource from another
catalog comes from the release of that catalog it pinned when it was cut. Releasing a catalog that
uses another one is refused until that other catalog has a release. Asking for a specific template
version is refused. Preview renders the latest release as well, and can render the working copy
instead when asked. Requests that name an environment still use its activations until environments
deploy releases.
