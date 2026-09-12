---
type: feat
scopes: [exchange]
audience: user
title: Exchange setup now follows secure browser redirects.
---

Suite registers state and S256 PKCE, redirects to Exchange to create or select an OAuth application and tenant connection, and completes through an authenticated callback. New and recovered application secrets, access tokens, refresh tokens, and PKCE verifiers are encrypted at rest; the local profile uses `localhost:4000` by default, while self-hosted deployments may configure any browser-reachable HTTPS callback.
