---
type: fix
scopes: [security, assets, fonts]
title: A tenant's images and fonts are cached by the browser only, and revalidated when used.
---

They were served as public and immutable for a year: a shared cache such as a proxy could keep an
image or font read with a session and serve it to anyone, and a replaced image could stay stale in
the browser. They are now `private, no-cache` with an entity tag, so the browser keeps them and asks
again with a small request answered 304 while they are unchanged. The suite's static resources keep
their long-lived caching.
