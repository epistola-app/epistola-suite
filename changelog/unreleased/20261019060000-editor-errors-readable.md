---
type: fix
scopes: [editor]
title: A failed save or preview in the editor says so plainly.
---

When saving fails, the toolbar now says "Not saved:" with the reason, visible and announced to screen readers, and the button reads "Retry save"; before, only an icon changed and the reason was in a tooltip. When the server answers with a web page instead of an error (a sign-in page after the session ended, a proxy's error page), the editor says what probably happened instead of showing the page's markup. A preview of a variant that no longer exists says so instead of "Not Found".
