---
type: feat
scopes: [version-check]
audience: user
title: The release check shows the support status Epistola publishes for the running version's line, evaluated whenever the page is shown.
---

The release metadata now lists every release line with its support status (supported, ending,
ended), decided by Epistola. The Suite looks up its own line and shows that status with the line's
own end date, so a change to the support policy reaches running installations without an upgrade,
and there is no 90-day warning window in the Suite any more. The update banner offers the line's own
newest patch first, then the newest release.

The banner is now evaluated whenever the tenant home page is shown, from the Suite's entry of the
last successful check: after an upgrade it shows the new version's status at once instead of the
previous build's verdict until the next daily check. Metadata more than seven days old says nothing
about support. Older metadata without lines keeps the minimum-version check.
