---
type: feat
scopes: [catalog]
issues: [990]
title: A catalog release retains the content it contained.
---

A release recorded a fingerprint and a promise: the content behind it lived only in the working
copy, so an earlier release could not be reproduced. Releasing now stores an immutable, deduplicated
copy of every resource. Nothing reads it yet — reproducing and exporting an earlier release comes
next — but two things change today.

Storage grows with genuine change rather than with releases: re-releasing unchanged content stores
nothing, and a release whose only edit is one resource stores one resource. And the bytes a release
needs are no longer collected when the image or font they belong to is deleted from the working
copy; a tenant backup carries them too.
