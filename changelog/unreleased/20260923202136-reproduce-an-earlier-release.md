---
type: feat
scopes: [catalog]
issues: [990]
title: An earlier catalog release can be rebuilt from what it retained.
---

A release recorded a fingerprint; the content behind it lived only in the working copy, so editing a
theme or deleting an image changed what an earlier release would export. A release now records which
resources it contained, at which revision, and can be rebuilt from them — byte for byte, to the
fingerprint it was cut with, whatever the working copy has done since.

Two things follow today. The review before a release can name a resource that has been deleted since
the last one, which it previously showed as an address with no name. And nothing else yet: exporting
a named release, and publishing the retained archive rather than rebuilding it, come next.
