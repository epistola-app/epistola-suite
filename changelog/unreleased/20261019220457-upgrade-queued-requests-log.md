---
type: fix
scopes: [generation, upgrade]
title: Requests still queued at the 1.x upgrade log one warning each, not an error with a stack trace.
---

Every generation request still queued when an installation is upgraded from 1.x fails, as it names a template version that 2.0 no longer renders; the upgrade guide says to resubmit them. Each used to log an error with a full stack trace, hundreds at once after a busy upgrade; each is now one warning line. The upgrade guide also says which documents can look different afterwards: an environment that ran an older version serves what was last published once deployed, and a stencil pinned to an older version renders as it is now.
