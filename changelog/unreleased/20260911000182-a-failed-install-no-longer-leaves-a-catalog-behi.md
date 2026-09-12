---
type: fix
scopes: [exchange]
audience: user
title: A failed install no longer leaves a catalog behind.
---

The ZIP import creates the catalog row before it installs anything into it, and its abort path does not undo that — so an install that failed said "nothing was changed" while leaving an empty catalog occupying the ID, which then made the retry look like a collision with somebody else's catalog. An aborted **first** install now removes what it created; an aborted upgrade still leaves the existing catalog exactly where it was, and the two now say which happened rather than both claiming nothing did. The refusal also names why a resource could not be imported instead of only that it could not.
