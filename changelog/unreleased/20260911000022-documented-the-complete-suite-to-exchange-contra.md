---
type: docs
scopes: [exchange]
audience: dev
title: Documented the complete Suite-to-Exchange contract.
---

The canonical guide covers deployment and tenant gates, redirect application authorization and credential recovery, setting precedence, immutable namespace binding, the transactional outbox and state machine, retry and credential behavior, backup exclusions, operations, and explicitly deferred inbound/REST/MCP work. ADR 0018 records why publication is asynchronous, why the catalog domain reaches the outbox through a port instead of calling the integration, and why encryption columns are domain-contributed instead of hard-coded in core rotation.
