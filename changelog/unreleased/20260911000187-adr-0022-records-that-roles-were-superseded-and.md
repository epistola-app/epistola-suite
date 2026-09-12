---
type: docs
scopes: [catalog]
audience: dev
title: ADR 0022 records that roles were superseded, and eight errata.
---

The record proposed marking a _role_ at every reference site and rewriting stored content on each install; marking the _resource_ overridable reaches the same outcome with one boolean on the wire, one table instead of three, and no rewriting — an override keyed on a resource's stable identity survives an upgrade because the identity trigger adopts it. The errata correct claims the code does not keep, the sharpest being that the proposed wire-schema bump would have made the Suite refuse every release already published on Exchange. It also withdraws the tenant-profile idea: sender details are data the caller supplies, and #921 is closed.
