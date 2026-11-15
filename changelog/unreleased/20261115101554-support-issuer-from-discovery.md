---
type: fix
scopes: [support]
breaking: true
maturity: alpha
title: Epistola support is found where the product domain says, and a support preset says how to sign in.
---

With `epistola.support.endpoint` blank, the Suite went to `https://support.{product-domain}` and never
read the `support.issuer` the product domain's discovery document names, although the documentation
said it did. It now takes that issuer (Epistola support is at `https://connect.epistola.app`), found
once per start, and falls back to `support.{product-domain}` only without one. Looking up a domain
under **Add connection** refused such a document ("names no endpoint for its support connection"). A
preset now says how to sign in with a required `auth`: `private_key_jwt` with an `issuer` fills in a
connection that registers through OAuth, `bearer_token` with an `endpoint` one with a token. A preset
without `auth`, with one the Suite does not know, or with the other kind's address is refused, saying
why. A partner publishing a preset adds `"auth": "bearer_token"`.
