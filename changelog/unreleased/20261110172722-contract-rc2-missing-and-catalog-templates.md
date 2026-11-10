---
type: feat
scopes: [api]
title: The REST API serves contract 2.0.0-rc.2 — a catalog's templates across releases, and what a 404 did not find.
---

`GET /tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/templates` lists a catalog's
templates across the releases a client can reach, each with the releases that contain it, draft
first, then highest release first. With `environmentKey` it lists what that environment serves,
its deployed draft included. Without it, it lists every release, plus the newest draft when the
catalog was never released. A client that offers templates for a version range no longer has to
read each release in turn, and can tell a template that was added or removed in a later release.

A `not-found` problem now carries `missing`, which says what did not exist and the context the
request named: `{"kind": "release", "environment": "production", "versionRange": "^2.0.0"}`, or
`{"kind": "template", "release": "1.4.0", "template": "besluit"}`. Its `type` is unchanged, and
its `help` link adds `missing=<kind>`, so the error page can give the hints for that case.
