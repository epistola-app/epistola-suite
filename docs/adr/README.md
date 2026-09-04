# Architecture Decision Records

Each ADR records one decision: the context that forced it, the options weighed,
what was chosen, and what that costs. They are **append-only history** — an ADR
is never rewritten to match a later change of mind. A decision that is revisited
gets a new ADR that supersedes the old one, or an explicit addendum inside it.

Back to the [documentation index](../README.md).

| ADR                                                                                  | Status                         | Decision                                                                    |
| ------------------------------------------------------------------------------------ | ------------------------------ | --------------------------------------------------------------------------- |
| [0001](0001-stencil-placeholders.md) Stencil placeholders                            | Accepted — implemented         | First-class `placeholder` node with default + fill slots.                   |
| [0002](0002-stencil-parameters.md) Stencil parameters                                | Accepted — implemented (v0.20) | Stencils declare a parameter schema; consumers bind values on insert.       |
| [0003](0003-stencil-version-in-export.md) Stencil version in export                  | Accepted                       | Catalog exports carry the stencil version a template was authored against.  |
| [0004](0004-rfc7807-problem-details.md) RFC 7807 problem details                     | Accepted                       | Errors are RFC 9457/7807 `ProblemDetail`, swapped at one seam.              |
| [0005](0005-feedback-storage-local-vs-remote.md) Feedback storage                    | Accepted — implemented         | Local store as source of truth, hub sync through a port.                    |
| [0006](0006-shipping-logs-and-metrics-to-hub.md) Logs & metrics to hub               | Accepted — Option A            | One unified OTLP pipeline for both logs and metrics.                        |
| [0007](0007-catalog-wire-format-migrations.md) Catalog wire-format migrations        | Accepted                       | Catalog-wide `schemaVersion` with forward migrations per version.           |
| [0008](0008-header-footer-height-minimum.md) Header/footer height                    | Accepted                       | Page header/footer `height` is a minimum; bands auto-grow.                  |
| [0009](0009-event-log-vs-generation-results.md) `event_log` vs. `generation_results` | Accepted — Option A            | Keep `event_log`, exclude the generation path from it.                      |
| [0010](0010-strict-script-src-csp.md) Strict `script-src` CSP                        | Accepted — Option A            | No `'unsafe-inline'`; behavior lives in static JS with delegated listeners. |
| [0011](0011-quality-check-input-model.md) Quality-check input model                  | Draft                          | Discussion record on what a check receives; not accepted.                   |
| [0012](0012-check-run-lifecycle.md) Check-run lifecycle                              | Draft                          | Discussion record on debounce, coalescing and run-state; not accepted.      |
| [0013](0013-editor-plugin-selection-intents.md) Editor plugin selection intents      | Draft                          | Discussion record on selection intents; not accepted.                       |
| [0014](0014-safe-catalog-resource-relocation.md) Safe resource relocation            | Draft                          | Discussion record on relocating authored resources; not accepted.           |
| [0015](0015-iframe-embedding-bridge.md) Iframe embedding bridge                      | Accepted                       | Iframe + `postMessage` bridge, demo-mode only.                              |
| [0019](0019-demo-api-shared-secret.md) Demo API shared secret                        | Accepted — implemented         | All-tenant credential for the demo website, gated on the `demo` profile.    |

## Adding an ADR

1. Take the next free number; filename `NNNN-kebab-case-title.md`.
2. Open with the standard metadata block, then `## Context`,
   `## Considered options`, `## Decision`, `## Consequences`, `## References`:

   ```markdown
   # ADR 0016: Title

   - **Status:** Proposed
   - **Date:** YYYY-MM-DD
   - **Deciders:** Epistola team
   - **Tags:** comma, separated, topics
   ```

3. Move **Status** to `Accepted` (or `Rejected`/`Superseded by ADR NNNN`) when the
   decision is taken, and append `— implemented` once it ships. Keep the row in
   the table above in step with the file.
4. Add the row here **and** link the ADR from the feature doc it governs.
