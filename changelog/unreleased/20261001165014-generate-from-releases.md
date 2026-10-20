---
type: feat
scopes: [generation, catalog, loadtest]
audience: user
breaking: true
title: Documents are generated from a published catalog version, never from a template version.
---

Generating a document renders a published version of the template's catalog, chosen when the request is accepted, so a version published while the request waits in the queue does not change what it renders. Through an environment it renders what that environment uses (see the environment entry); without one, the latest published version, or the highest that matches the request's `version`, which takes an exact version or a range such as `^1.4.0`. The variant, theme, fonts, images, stencils and data contract all come from that version, and what it uses from other catalogs from the versions it was published with. A catalog with no published version fails the request with a message naming it, and a template deleted from the next version keeps generating from the versions that still contain it. Asking for a template version is refused. Preview renders a published version too, or work in progress when asked, and data validation checks the contract of the version that would render. A load test renders what generation would, and its version choice is gone. **Action on upgrade:** a request still queued under 1.x names a template version and fails; drain the queue before upgrading and resubmit what failed.
