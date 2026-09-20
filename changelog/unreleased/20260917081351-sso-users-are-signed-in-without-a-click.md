---
type: feat
scopes: [auth]
audience: user
title: Users already signed in to single sign-on no longer have to click to sign in.
---

When a user already has a session at the identity provider, the login page now signs them in
straight away and takes them to the page they asked for. It no longer waits for a click on "Sign in
with SSO". Users without a provider session see the login page as before. Renewing an expired
session in the pop-up works the same way. The behaviour is on by default and can be turned off with
`epistola.auth.oidc.silent-login=false` (Helm: `oidc.silentLogin`) if a provider handles it badly.
Signing out now ends the identity-provider session on deployments that configure
`backchannel-base-url` as well.
