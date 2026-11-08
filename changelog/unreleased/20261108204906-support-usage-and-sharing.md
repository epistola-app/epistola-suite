---
type: feat
scopes: [support]
audience: user
title: Usage goes to Epistola support every hour, for the tenants that share it.
---

**Usage** is a support service (scope `usage`) for installations Epistola bills by use, such as a
partner hosting its clients. Every hour, at a moment within the first ten minutes past the hour fixed
per installation, the suite reports the hour before: for each tenant that shares usage, the documents
generated and the previews rendered, with running totals, under its key or confidential reference. It
counts from the generation statistics, waits until they hold the whole hour, catches up on up to 72
missed hours, and reports from one node at a time, never from one whose clock is off.

What a tenant shares is set in one place: the tenant's Support page, with a setup wizard while nothing
is set up and afterwards what is shared, by whom, with Change. Under **Setup for every tenant** on
Platform → Epistola support, an installation administrator sets usage to Required (every tenant, not
shown on tenants' pages), on or off by default, or **not allowed**, which withdraws every tenant's
choice. A default that shares asks the administrator to agree on the tenants' behalf. A service the
installation has not selected is not offered to tenants. Disconnecting from Epistola support clears
every tenant's choice and the setup for every tenant, Required and Not allowed included. Hours while
usage was not granted or not allowed are never sent later.
