---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: A catalog installs and upgrades as one unit.
---

Installing a subscribed catalog took an optional list of resources, and an upgrade then preserved whatever subset you had chosen — so "installed 1.2.0" meant something different on every installation, when the publisher's version and fingerprint describe a whole release. Installing now installs the whole manifest, and an upgrade reconciles it: resources added since the installed release arrive, changed ones are updated, and ones the publisher withdrew are removed under the existing in-use conflict checks. The per-resource Install button and the "also install new resources" checkbox are gone, and the upgrade preview now names what is new, what is updated and what is removed. The REST upgrade request still accepts `includeNewSlugs` and now ignores it. An installation left partial by the old behaviour is topped up by its next install or upgrade. (#850)
