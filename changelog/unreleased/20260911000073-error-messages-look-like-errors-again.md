---
type: fix
scopes: [ui]
audience: user
title: Error messages look like errors again.
---

Eight alerts across the Exchange and catalog pages used `alert-danger`, a class the design system has never defined — `.alert` supplies only layout, so the colour, the border and the colour-blind-safe severity icon all come from the variant, and these rendered as plain text with nothing marking them as failures. They now use `alert-error`. Every one was on an error path, which is why it survived: the twenty-two alerts that already used the right class looked correct. A test now holds the variants used in templates against the ones the design system defines, the way `ExchangeStatusBadgeTest` already does for badges.
