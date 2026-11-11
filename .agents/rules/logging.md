---
paths:
  - "**/src/main/**/*.kt"
  - "**/logback*.xml"
---

# Logging

A log line is read by people (the console, the in-app Logs page) and by tools (Loki, Elastic,
pseudonymised telemetry), so every message has the same shape.

```kotlin
private val log = LoggerFactory.getLogger(javaClass)

log.warn("Catalog upgrade failed catalog={} tenant={} reason=\"{}\"", catalogKey, tenantKey, e.message)
// Catalog upgrade failed catalog=acme tenant=t-42 reason="Connection refused"
```

- **The message is a constant pattern; values go in as `{}` arguments.** Telemetry sends the
  pattern without its arguments (ADR 0052 §2), so a value built in with `$` or `+` leaks.
  Enforced by `LogMessageTemplateTest`.
- **Every value is named: `key={}`.** A short sentence first, then the pairs. The name tells a
  reader what the value is, makes `catalog=acme` searchable, and survives in the pattern telemetry
  sends. Keys are lower camelCase nouns: `tenant`, `catalog`, `templateId`.
- **Quote free text, and put it last: `reason="{}"`.** Names, titles, `e.message` and anything a user
  typed may hold spaces or be empty. Identifiers, numbers and enums stay bare.
- **Keep values in the message, not in `addKeyValue`.** The in-app Logs page stores only the
  formatted message and the MDC (`ApplicationLogAppender`), so key/values alone would vanish there.
- **Do not repeat the context.** The tenant, and the command or query with its catalog, are already
  on every line through the principal and the MDC (`docs/application-logs.md`).
- **A throwable goes last, without a placeholder:** `log.error("Render failed job={}", id, e)`.
- **The field is `log`.**

Line breaks in values cannot forge an entry: the console pattern escapes them and JSON output
encodes them (`ConsoleLogPatternTest`). `DriftRatchetTest` holds the field name at zero
exceptions and ratchets unnamed placeholders: their count may fall, never rise.

## Verify

```bash
./gradlew :modules:guards:test --tests "*LogMessageTemplateTest" --tests "*DriftRatchetTest"
```
