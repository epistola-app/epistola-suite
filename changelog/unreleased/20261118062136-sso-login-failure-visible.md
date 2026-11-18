---
type: fix
scopes: [auth]
issues: [714]
title: A failed SSO sign-in is logged at WARN and no longer reported as a wrong password.
---

When a sign-in failed after the identity provider redirected back, for example because the UserInfo
endpoint answered in an unexpected format, nothing was logged at the default level and the login
page said "Invalid username or password". The cause is now one WARN line naming the OAuth2 error
code and its description, without tokens or response bodies, and the login page says that sign-in
failed. Form-login failures keep the credentials message.
