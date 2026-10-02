---
type: feat
scopes: [loadtest]
audience: user
breaking: true
title: A load test renders a catalog release instead of a template version.
---

A load test now renders what generation renders: the release the chosen environment serves, or the
catalog's latest release when no environment is chosen. The version choice is gone from the form,
and a run with nothing to render is refused when it starts, with the same error generation gives.
