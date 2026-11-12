---
type: feat
scopes: [support]
title: Telemetry and usage go to support connections, shaped by each connection's privacy settings.
---

With `telemetry` granted, the suite sends its log lines and metrics to the support service over OTLP/HTTP, starting and stopping with the grant; `epistola.support.telemetry.enabled=false` opts out. With `usage` granted, it reports each tenant's documents and previews every hour, for the tenants that share it, and catches up on missed hours (`epistola.support.usage.backlog`, default `72h`). Under **Privacy** an administrator decides what each connection sees: by default tenants are confidential references and log messages are sent without their values. What a tenant shares is set on its Support page, and an administrator can preset, require or forbid it for every tenant.
