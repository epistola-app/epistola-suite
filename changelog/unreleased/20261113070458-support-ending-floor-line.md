---
type: fix
scopes: [version-check]
audience: user
title: Only a version on the line whose support is ending shows the "support ending" banner.
---

The release check's support end date belongs to the oldest supported line, but every supported
version showed the amber "support ending" banner when that date came near, the newest release
included: a 2.0.0 installation was told to plan an upgrade because 1.4 and 1.5 were ending. Now only
an installation on that line sees it; a newer line shows no end date it does not have.
