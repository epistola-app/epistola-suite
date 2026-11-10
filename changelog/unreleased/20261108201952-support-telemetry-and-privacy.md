---
type: feat
scopes: [support]
audience: user
title: Telemetry goes to Epistola support over OTLP, shaped by the installation's privacy settings.
---

With `telemetry` selected and granted under Platform → Support connections, the suite sends its log lines
(INFO and above, `epistola.support.telemetry.log-level`) and its metrics to the support service over
OTLP/HTTP, with the support connection's token. It stops and starts with the grant, without a
restart; while the support service cannot be reached, each leg logs one WARN when exports start
failing and one INFO when they succeed again, and telemetry that cannot be sent is dropped. Spring
Boot's own OTLP metrics export, and a bring-your-own OpenTelemetry agent, keep working beside it.
`epistola.support.telemetry.enabled=false` (Helm `support.telemetry.enabled`) opts out.

An installation administrator decides under **Privacy** on Platform → Support connections what Epistola
sees (Helm `support.privacy`). **Confidential**, the default, sends every tenant as a reference that
only the installation can trace back; each tenant's Support page shows its own. **Named** sends tenant
keys. **Pseudonymised** telemetry, the default, sends log messages as written in the code without
their values and errors without their message; **everything** sends them as written. Log messages in
the suite no longer build values into their text, so the pattern carries none, and a guard keeps it
that way.
