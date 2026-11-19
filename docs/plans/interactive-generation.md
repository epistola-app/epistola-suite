# Plan: interactive generation, version 1

> **Status:** Design, 2026-11-19. Not built yet. Implements contract 2.1.0's
> interactive generation (contract ADR 0011, alpha).

Contract 2.1.0 adds `interactive` to `generateDocument` (contract ADR 0011, alpha). The caller sends
the data it has. When data is missing, or always when the caller asks, the job waits in
`awaiting-input`, and the response carries a URL to a form that Suite serves. The person fills in the
form, the job moves to `pending`, and it renders like any other job. This plan covers Suite's first
version of that, plus a way to start the flow from the authenticated UI so an author can try it
without an external system.

The ADR's own "Fit with Suite" section already checked the generation code. This plan builds on it
and only repeats its findings where they shape a step.

## Scope

**In v1**

- `interactive` on `generateDocument`, with both triggers (`incomplete`, `always`), the echo,
  `awaiting-input`, `cancelReason`, `data` and `editedFields` on items and collect lines, and a
  `cancelled` collect line.
- The public form page: derived from the bound release's data contract, pre-filled, validated on the
  server, submitted once, cancellable, with `returnUrl` and the prefixed return parameters.
- An expiry sweep, a default and maximum expiry, and an allowlist of return/link origins.
- **Try it from the UI**: start an interactive job from the template page against an environment,
  open the form in a new tab, and watch the job in generation history.
- A per-tenant alpha feature toggle.

**Deferred (the ADR allows each to come later)**

- Saving progress on the form ("may" in the ADR). Until then, a reload keeps nothing the person typed.
- A PDF preview on the form. It is useful, but it puts the renderer on the public surface. Add it in
  a follow-up once the token surface has been reviewed.
- Variant choice (`interactive.variantChoice`), which the ADR itself defers.
- An origin allowlist per application. v1 uses one installation-wide setting, as the ADR proposes.

**Surfaces**

- REST: implements the contract.
- Web UI: the form page plus the "try it" entry point.
- MCP: left out on purpose. MCP may preview documents but never generate them, and an interactive job
  is a generation.

## Decisions to confirm before building

1. **Feature toggle.** `KnownFeatures.INTERACTIVE_GENERATION`, ALPHA, off by default. While it is off,
   Suite ignores `interactive` exactly as a pre-2.1 server would: no echo, an ordinary `pending` job.
   The echo rule makes this safe for callers, and it gives each tenant an opt-in and a kill switch.
2. **Where the form's URL comes from.** Suite has no setting for its public URL today. Building the
   link from the incoming request's host would put a value from a `Host` header into a link that
   `getGenerationJobStatus` hands out again. Recommendation: a required
   `epistola.generation.interactive.public-base-url`. Without it, the feature cannot be switched on
   and the boot log says why. The demo profile sets it to `http://localhost:4000`.
3. **Storing the token.** Generate 32 random bytes, encoded base64url. Store a SHA-256 hash, indexed,
   to look the token up, and the token encrypted with the credential encryption so it can be handed
   out again while the job waits. Register the column with `EncryptedCredentialContributor` so
   re-keying covers it.
4. **Where the form state lives.** In a sibling table, `generation_request_interactions`, partitioned
   by `created_at` like `document_generation_requests` and registered in `CorePartitionedTables`.
   Retention then drops both together ("goes with the job"), and the hot table the poller scans does
   not grow wide columns of personal text.

## Steps

Each step is one PR on `feat/data-collection-form`, or stacked on the step before it.

### 1. Contract bump to 2.1.0 (no behaviour)

Use the `contract-bump` skill. Bump `epistola-contract` to `2.1.0` in `libs.versions.toml`. Map the
new enum values (`AWAITING_INPUT`, `cancelled` on `GenerationResult`) in `ApiEnumConversion`. The new
response members stay null for now. Check that `checkContractVersionAlignment` and
`ApiExceptionMappingsConsistencyTest` pass. The new `url-not-allowed` validation code goes in
`ApiValidationCodes`.

### 2. Domain and schema (epistola-core)

Migration `V<ts>__core_interactive_generation.sql`. It is forward-only, and it declares rollback
compatibility as `RollbackCompatibilityDeclarationTest` requires.

- Widen the status `CHECK` on `document_generation_requests` with `AWAITING_INPUT`. This means
  dropping and re-adding the named constraint on the partitioned parent.
- Add `cancel_reason VARCHAR(16) NULL CHECK (cancel_reason IN ('CALLER','USER','EXPIRED'))`.
- Add `submitted_at TIMESTAMPTZ NULL`, for the queue-wait metric (below).
- `generation_request_interactions(request_id, created_at, tenant_key, trigger, token_hash,
  token_encrypted, expires_at, return_url, title, instructions, links jsonb, locale,
  missing_fields jsonb, caller_data jsonb, edited_fields jsonb, submitted_at)`, partitioned by
  `created_at`, with an index on `token_hash` and a partial index on `expires_at` for waiting rows.
- `generation_results`: widen the status check with `CANCELLED`, and add `cancel_reason`.

Code changes:

- `RequestStatus.AWAITING_INPUT`. `isCancellable` includes it. Neither the poller nor stale-job
  recovery ever selects it: both filter on `PENDING`/`IN_PROGRESS` today. Prove that with a test, do
  not assume it.
- `GenerateDocument` gains `interactive: InteractiveGeneration?`, a domain type rather than the
  contract DTO. The handler, in the same transaction as the insert, works like this:
  - It runs the analysis against the **bound** target (the `ReleaseTarget` it already resolves). It
    never resolves again and never falls back to a data example. Extract the core of
    `AnalyzeTemplateDataHandler` into a component that both use. `AnalyzeTemplateData` keeps its
    example fallback and its `TEMPLATE_VIEW` permission. The job path needs only `DOCUMENT_GENERATE`.
  - It decides the status: `AWAITING_INPUT` for `always`, or for `incomplete` with missing or invalid
    fields. Otherwise `PENDING`.
  - It validates the expiry against the configured maximum (`maximum`) and every URL against the
    allowlist (`url-not-allowed`), and throws a `ValidationException` carrying those codes.
  - It inserts the interaction row, including `caller_data` = the data as sent.
  - It returns a named result (`GenerationAccepted` with the request and an `InteractiveState?`), not
    a pair.
- `CancelGenerationJob` accepts `AWAITING_INPUT` and writes `cancel_reason = CALLER`. When the job
  has an interaction, it also emits a `cancelled` result.
- New commands, all `SystemInternal` because the token is the authority, not a principal:
  - `SubmitInteractiveForm(token, data)`: validate against the bound release's contract on the
    server, compute `editedFields` (a JSON Pointer diff of `caller_data` against the submitted data,
    including cleared fields), and overwrite `request.data`. Then set `PENDING` and `submitted_at`.
    All of this is one conditional `UPDATE … WHERE status = 'AWAITING_INPUT'`, so a second
    submission or a replay finds nothing to update and is told the outcome.
  - `CancelInteractiveForm(token)`: sets `CANCELLED` with reason `USER`, and emits a result.
  - `ExpireInteractiveForms`: the sweep's body.
- New queries: `GetInteractiveForm(token)`, which returns the form model or "gone" plus the reason.
  `GetGenerationJob`, `ListGenerationJobs` and the item queries carry the interaction state.
  `url` is decrypted only while the job is waiting.
- `EmitGenerationResult` permits `CANCELLED` (no document and no error, but a `cancelReason`). The
  NDJSON map adds `cancelReason`, `data` and `editedFields`. Join `data` from the request when the
  line is fetched, so it is not stored twice.
- Expiry sweep: a `ClusterScheduledTaskHandler` (the pattern `StaleJobRecovery` uses, but scoped to
  one node). It runs every minute: `UPDATE … SET status='CANCELLED', cancel_reason='EXPIRED'
  WHERE status='AWAITING_INPUT' AND expires_at < NOW() RETURNING …`, then emits `cancelled` lines.
  Use database `NOW()`, because it is a database-owned comparison.
- Queue wait: `epistola.generation.queue.wait` measures from `COALESCE(submitted_at, created_at)`.
  Claim order stays `created_at`. A submitted form going to the front of the queue is harmless, so
  document it rather than change it.
- Configuration (`InteractiveGenerationProperties`, `epistola.generation.interactive.*`):
  `public-base-url`, `default-expiry` (24h), `max-expiry` (7d, never above the contract's 30 days,
  checked at boot), and `allowed-origins`. Suite's own public origin is always allowed, which the
  "try it" flow needs. At boot, refuse a partition retention shorter than `max-expiry` plus one
  partition month, so a waiting job's partition cannot be dropped under it.

Tests, at the core integration tier and seeded through commands:

- Trigger matrix: `incomplete` with complete data gives `pending`, with missing data
  `awaiting-input`, with invalid data `awaiting-input`. `always` always waits. Empty data gives an
  empty form, never an example.
- A waiting job is never claimed, and never recovered.
- Submitting once gives `pending`. A second submission is refused. Invalid submitted data is
  refused, and the job keeps waiting.
- `editedFields` covers a changed field, an added field, a cleared field, and undeclared members
  passed through unchanged.
- Cancel by caller, by user and by expiry. Each sets its reason and emits one `cancelled` line with
  `data` null.
- A `failed` result after submission still carries `data`.
- The expiry and allowlist refusals carry the right codes.
- Visibility: another application's job is not found by its token query either. The token is the
  only key, but the job must still belong to the tenant it names.

### 3. REST (rest-api)

- `GenerateDocumentRequest.toCommand` maps `interactive`. It is ignored when the tenant's toggle is
  off.
- `toJobResponse`, `toJobDto` and `toItemDto` fill `interactive`, `cancelReason`, `data` and
  `editedFields`. The echo is present exactly when the request was honoured, including when nothing
  was asked.
- `cancelGenerationJob` accepts a waiting job.
- Handler tests for each conformance scenario in the contract (400–406). If the contract's
  conformance runner can target a running Suite, run it against the demo app once as well.

### 4. The public form page (apps/epistola + editor)

**Route and security.** `/forms/{token}` (GET), `/forms/{token}/submit` and `/forms/{token}/cancel`
(POST). These get a filter chain of their own, ordered before the UI chain:

- Stateless with no session, and no login redirect.
- CSRF is not session-based here. A cross-site POST cannot succeed without the token, which is
  unguessable and sits in the path and the body, so the token is the CSRF defence. Write that down in
  `docs/auth.md`.
- `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, and the strict CSP. `frame-ancestors
  'none'` unless embedding is enabled.
- An unknown, expired, cancelled or submitted token gets one neutral page (410 or 404), so the page
  does not reveal which tokens exist. Count lookups that fail.
- Logs never contain the token. Log the request id, or a hash prefix.

**Rendering.** A Thymeleaf page in the page chrome, without the authenticated navigation. It shows
the caller's `title`, `instructions` (escaped, line breaks kept) and `links` (`rel="noreferrer
noopener"`), in `locale` or else the tenant's default locale. The fields are a new Vite entry,
`data-form`, that reuses `renderExampleForm` from the data-contract editor. It needs a small wrapper
that takes the schema and the pre-filled data from a `data-*` JSON island, renders the form, and
posts JSON back with the token. It has no inline script, as the CSP requires. Required fields are
marked, and the analysis's missing fields are highlighted, but every field stays editable.

**Submit.** The server validates (the browser is not trusted). Errors re-render the form with the
messages for each path. On success it redirects to `returnUrl` with `epistolaRequestId` and
`epistolaRequestStatus`: appended after the existing query, replacing a parameter of the same name,
keeping the fragment. Without a `returnUrl` it shows a "document on its way" page, or a "cancelled"
page.

**Tests.** Handler tests for each state and for the headers. A `UiTest` that opens the link, fills in
a missing field, submits, and lands on the return URL. A guard test that `/forms/**` is reachable
without a session and that nothing else newly is.

### 5. Try it from the UI (apps/epistola)

The UI cannot call `/api/**`, so it dispatches the same `GenerateDocument` command through a UI
route.

- On the template page, add a **Try with a form** action, visible when the toggle is on and the
  person has `DOCUMENT_GENERATE`. The dialog (`ui-page` skill, dialog-form pattern) asks for the
  environment, the variant (defaulting to the template's default), the trigger, and optional starting
  data (an editor seeded from a data example, or empty).
- It submits to `POST /tenants/{tenantId}/…/templates/{templateKey}/interactive-jobs`, which
  dispatches `GenerateDocument(interactive = …)`. `returnUrl` is the job's page in generation
  history, so the full round trip is exercised, including the return parameters.
- The response opens `interactive.url` in a new tab through a delegated listener on a `data-*` hook.
  If nothing was asked (`incomplete` with complete data), it goes straight to the job.
- Generation history shows `awaiting-input` and `cancelled` with a reason. A waiting job gets an
  **Open form** link (the URL is fetched when the page renders, never stored client-side) and a
  **Cancel** action. A finished interactive job shows its `data` and `editedFields`.

This also covers the ADR's "an author can try the form against a draft deployed to a test
environment": an environment that follows a draft binds the draft, as generation already does.

### 6. Demo, docs, changelog

- Demo catalog: a template whose contract has required fields that the example leaves empty, such
  as the ADR's objection decision with `motivation` and `decisionDate`. Bump `release.version` and
  regenerate `release.fingerprint`. Turn the toggle on for the demo tenant.
- Docs: a new `docs/interactive-generation.md`, indexed in `docs/README.md`, covering the flow, the
  settings, the token model and retention. Update `docs/generation.md` for the new status, and
  `docs/auth.md` for the public route.
- A Suite ADR for the first unauthenticated page and its token model, since it is the surface to
  review hardest.
- A changelog fragment per PR. The feature is alpha, so mark it that way.

## Risks and how v1 handles them

| Risk                                          | Handling                                                                                                                      |
| --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| A public surface on Suite                     | Separate filter chain, token-only, neutral errors, no preview in v1. Ask for a security review before the toggle leaves alpha |
| Open redirect through `returnUrl` or links    | Installation allowlist, refused with `url-not-allowed`. Own origin only by default                                            |
| A waiting job outlives its partition          | Boot check on retention against `max-expiry`                                                                                  |
| Dashboards assume every job heads to a worker | `awaiting-input` excluded from in-flight and queue metrics. Generation statistics reviewed                                    |
| A double submission from two tabs             | A conditional `UPDATE` on status. The loser sees the outcome                                                                  |
| The token leaks through Referer or logs       | `no-referrer`, the token never logged, the URL only in the page and the API response                                          |

## Order and size

1 → 2 → 3 gives a working API with no page, which can be tested by posting the form with curl in
integration tests. 4 makes it usable. 5 makes it demonstrable in the UI. 6 runs alongside. Steps 2
and 4 are the large ones.
