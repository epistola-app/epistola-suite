---
type: fix
scopes: [security]
title: Smaller security fixes to caching, logging and remote sources.
---

A tenant's images and fonts were served as public and cacheable for a year, so a shared proxy could keep one read with a session and serve it to anyone. They are now `private, no-cache` with an entity tag. A logged value with a line break, such as a typed name, could start what looked like a separate entry in the console log; line breaks are now written as `\n`. A catalog or code-list source that sends its answer slowly is given up on after 60 seconds in all, instead of holding a thread for as long as it keeps sending.
