---
type: feat
scopes: [generation, catalog]
audience: user
breaking: true
title: Documents are generated from a catalog release, never from a template version.
---

A request renders a release chosen when it is accepted, so publishing while it waits in the queue does not change what it renders. Through an environment it renders what that environment serves; without one, the latest release, or the highest matching `versionRange` (an exact version or a range such as `^1.4.0`). The variant, theme, fonts, images, stencils and data contract all come from that release, and what it uses from other catalogs from the releases it was published with. A template deleted from the next release keeps generating from the releases that still contain it. **Action on upgrade:** a request still queued under 1.x names a template version and fails; drain the queue before upgrading and resubmit what failed.
