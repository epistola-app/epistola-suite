---
type: fix
scopes: [support]
title: Epistola support is found where the product domain says, and Add connection takes a preset naming an issuer.
---

With `epistola.support.endpoint` blank, the Suite went to `https://support.{product-domain}` and never
read the `support.issuer` the product domain's discovery document names, although the documentation
said it did. It now takes that issuer (Epistola support is at `https://connect.epistola.app`), found
once per start, and falls back to `support.{product-domain}` only without one. Looking up a domain
under **Add connection** refused such a document ("names no endpoint for its support connection"): a
preset naming `issuer` now fills in a connection that registers through OAuth, one naming `endpoint` a
connection with a token, and one naming both or neither is refused.
