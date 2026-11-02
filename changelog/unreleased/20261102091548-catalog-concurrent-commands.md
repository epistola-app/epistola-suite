---
type: fix
scopes: [catalog]
title: Two people changing catalogs at the same moment can no longer leave catalogs using each other, or a release pinning one that was deleted.
---

Two catalogs declaring each other at the same moment both got through the check that catalogs never use each other, leaving two catalogs neither of which could be released. A release deleted while another catalog's draft or release was pinning it at that moment was deleted anyway, leaving that version pinned to a release that is gone, so it failed to render. A catalog released while another of its changes was being saved could take that change into the release half done: a dependency declared at that moment was released without the release it pins. All three are now handled as they are when the two happen one after the other. Found by a TLA+ model of catalog commands running at the same time.
