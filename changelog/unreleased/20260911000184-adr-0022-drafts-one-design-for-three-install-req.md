---
type: docs
scopes: [exchange, catalog]
audience: dev
title: ADR 0022 drafts one design for three install requests.
---

Installing a catalog under a different key (#919), seeing and satisfying its dependencies before the download (#917), and letting a publisher leave the logo, theme, fonts or header stencil for the installer to supply (#918) turned out to be one problem: an installed catalog has no identity of its own, and everything it references is written in the publisher's terms. The draft makes the source the identity, has the importer apply per-installation bindings on every install and upgrade rather than resolving them at render time (a stencil's content is a copy, so it could not be late-bound anyway), and has dependencies name a source instead of the publisher's local key. It also walks five ways an installer re-uses a catalog against the code as it stands, which is where two needs outside the design surfaced: deploying an installed or upgraded catalog is one action per template, variant and environment (deliberate; #920 asks for doing it in one go), and there is no way to copy a shared template into a catalog of your own. Nothing in it is built; the installation guide points at it from its list of what is not there.
