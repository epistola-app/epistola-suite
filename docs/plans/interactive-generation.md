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
- A PDF preview on the form, authorised by the same token (step 4).
- An expiry sweep, a default and maximum expiry, and allowed return/link origins kept per
  application (step 2).
- **Generate from the UI** (_changed_ 2026-11-20): "Generate document" on the template page, with a
  form as an option; the form opens in the same tab and returns to the job's own page, which follows
  the job to its PDF.
- A per-tenant alpha feature toggle.

**Deferred (the ADR allows each to come later)**

- Saving progress on the form ("may" in the ADR). Until then, a reload keeps nothing the person typed.
- Variant choice (`interactive.variantChoice`), which the ADR itself defers.
- Forms that authors configure themselves. v1 generates every form, but is built so that a configured
  form can replace the generated one later without touching the job, the token or the API (decision 8).
- Exposing an application's allowed origins through the contract's consumer resource. Suite does not
  implement that resource yet.

**Surfaces**

- REST: implements the contract.
- Web UI: the form page plus the "try it" entry point.
- MCP: left out on purpose. MCP may preview documents but never generate them, and an interactive job
  is a generation.

## Decisions (confirmed 2026-11-19)

1. **Feature toggle.** `KnownFeatures.INTERACTIVE_GENERATION`, ALPHA, off by default. While it is off,
   Suite ignores `interactive` exactly as a pre-2.1 server would: no echo, an ordinary `pending` job.
   The echo rule makes this safe for callers, and it gives each tenant an opt-in and a kill switch.
2. **Where the form's URL comes from.** Suite has no setting for its public URL today. Building the
   link from the incoming request's host would put a value from a `Host` header into a link that
   `getGenerationJobStatus` hands out again. Recommendation: a required
   `epistola.generation.interactive.public-base-url`. Without it, the feature cannot be switched on
   and the boot log says why. The demo profile sets it to `http://localhost:4000`.
3. **Links and sessions** (_changed:_ contract 2.1.1, epistola-contract#134). A link is
   `/forms/{requestId}?authCode={code}`. The code is signed (HMAC with a random installation key in
   `app_metadata`) over the request, its expiry (`link-lifetime`, 15 minutes) and a nonce, and works
   once. Opening it starts a signed session, kept in a cookie scoped to the form, that is renewed on
   every request until `session-idle-timeout` (60 minutes) or the job's wait ends. Nothing is stored
   per link, so `getGenerationJobStatus` hands out a fresh link on every call; only a redeemed
   code's nonce is kept, until the code would have expired. The core commands check the session
   themselves (`RedeemFormLink`, then `GetInteractiveForm`/`Submit…`/`Cancel…` with the session).
4. **Where the form state lives.** In a sibling table, `generation_request_interactions`, partitioned
   by `created_at` like `document_generation_requests` and registered in `CorePartitionedTables`.
   Retention then drops both together ("goes with the job"), and the hot table the poller scans does
   not grow wide columns of personal text.
5. **Expiry.** Default 24h, installation maximum 7d, with the boot check on retention (step 2).
6. **Preview on the form, by the token.** The token already lets its holder see the pre-filled data,
   so a PDF of that data against the same release grants nothing new. It is limited to the job's
   bound release (or live draft) and variant, works only while the job is `awaiting-input`, and is
   rate-limited (step 4).
7. **Allowed return origins belong to the application** (ADR 0054). The job records the application
   from the credential, never from the body. Where a redirect may go describes the calling system,
   meaning where its frontend lives, so a leaked key can only send a browser back to its own system.
   The installation setting stays only for callers without an application (bearer tokens and the demo
   secret, until ADR 0054 step 2 gives them one). Suite's own origin is always allowed.
8. **Prepare for configured forms: the page renders a form definition, never the schema directly.**
   Later, authors may design the form themselves: field order, sections, labels, help text, which
   fields to show. v1 lays the seam for that and builds only the generated case:
   - A `FormDefinition` model: sections, each with fields, each field naming a JSON Pointer, a widget,
     a label, help text and whether it is required.
   - A `FormDefinitionResolver` with one implementation that generates the definition from the bound
     release's data contract. A later implementation looks for a form the author made in the same
     release first. It then follows the release the way templates do, so a job always shows the form
     of the release it renders.
   - The `data-form` bundle renders a `FormDefinition`, and reuses the example form's widgets for
     individual fields.
   - **The data contract stays the only source of validity.** A form is presentation. Submission is
     validated against the contract, whatever the form shows. A configured form that leaves out a
     required field gets it added in a final section, so a form can never make a job impossible to
     submit.
   - Nothing about the definition goes into the REST contract or the catalog wire format in v1. The
     contract leaves the form's look to the server. A configured form becomes a catalog resource type
     later, as an additive wire-format change with an ADR of its own.

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
- `applications.allowed_return_origins TEXT[] NOT NULL DEFAULT '{}'`. Each entry is an origin only
  (scheme, host, optional port), with no path, and is normalised when saved. Matching is an exact
  origin comparison, never a prefix or a wildcard.

Applications: _built as_ a separate `SetApplicationReturnOrigins` command, so the existing edit form
does not have to submit the origins; each entry is validated as an origin.
The application page in the UI gets an editable list, under the same permission that edits the
application.

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
  - It validates the expiry against the configured maximum (`maximum`). It checks `returnUrl` and
    every link against the allowed origins (`url-not-allowed`): those of the submitting application,
    or the installation's when there is no application, plus Suite's own. It throws a
    `ValidationException` carrying those codes.
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
  NDJSON map adds `cancelReason`, `data` and `editedFields`. _Built:_ the emit copies `data` and
  `editedFields` onto the result row, so the collect poll needs no join across two partitioned
  tables. Results have the shorter retention, which fits "for as long as the result is kept".
- Expiry sweep: a `ClusterScheduledTaskHandler` (the pattern `StaleJobRecovery` uses, but scoped to
  one node). It runs every minute: `UPDATE … SET status='CANCELLED', cancel_reason='EXPIRED'
  WHERE status='AWAITING_INPUT' AND expires_at <= :now RETURNING …`, then emits `cancelled` lines.
  _Built:_ the expiry is application time (`EpistolaClock`). It sets the `expiresAt` the caller is
  told, and both the form page and the sweep compare with it, so a form closes the moment its link
  expires even before the sweep runs, and tests can move the clock.
- Queue wait: `epistola.generation.queue.wait` measures from `COALESCE(submitted_at, created_at)`.
  Claim order stays `created_at`. A submitted form going to the front of the queue is harmless, so
  document it rather than change it.
- Configuration (`InteractiveGenerationProperties`, `epistola.generation.interactive.*`):
  `public-base-url`, `default-expiry` (24h), `max-expiry` (7d, never above the contract's 30 days,
  checked at boot), and `allowed-origins`, which applies only to callers without an application.
  Suite's own public origin is always allowed, which the "try it" flow needs. At boot, refuse a partition retention shorter than `max-expiry` plus one
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

**Preview.** `POST /forms/{token}/preview` with the form's current data returns a PDF, shown in a
pane beside the fields.

- The handler loads the job by its token and renders the job's own target: its bound release or live
  draft, its variant, and the tenant's theme, through the existing preview renderer. Nothing in the
  request chooses a template, release or variant: the body is data only.
- Data that does not validate is not rendered. The pane lists what is still missing instead, the way
  `previewDocument` refuses invalid data, so the preview is always what submitting would produce.
  Rendering incomplete data leniently can come later if authors ask for it.
- Limits, because this is the renderer on a public route:
  - one preview at a time per token;
  - a per-token rate limit held in a bounded Caffeine cache;
  - a request body limit;
  - the renderer's existing timeout.
    Over a limit, the answer is 429, and the pane says to try again.
- Each preview is counted in a metric. It is not a document: no row, no result, no usage record.
- The PDF is served with `Content-Disposition: inline`, `no-store`, and a CSP that forbids framing
  except by the form's own page.

**Tests.** Handler tests for each state and for the headers. Preview tests:

- a waiting job renders;
- a submitted, cancelled or unknown token is refused;
- invalid data returns the missing fields;
- the rate limit answers 429;
- a body naming another template changes nothing. A `UiTest` that opens the link, fills in
  a missing field, submits, and lands on the return URL. A guard test that `/forms/**` is reachable
  without a session and that nothing else newly is.

### 5. Generate from the UI (apps/epistola) — done

_Changed 2026-11-20:_ a general "Generate document" action rather than a form-only one, and no job
page in generation history (which only holds statistics today).

The UI cannot call `/api/**`, so `TemplateGenerationHandler` dispatches the same `GenerateDocument`
through UI routes under `…/templates/{key}`.

- **The action** is in the template page's header for whoever has `DOCUMENT_GENERATE`. The dialog
  asks where to generate from (an environment serving the catalog, or the catalog's latest release
  cut here), the variant (the default preselected), the data (the first data example), and, only
  while the toggle is on, whether to ask for the data in a form (no / when incomplete / always).
  With nothing to generate from it says so and links to the catalog. An environment following a
  draft is offered as "staging (draft: …)" and renders that draft; there is no option to render a
  draft directly, since `GenerateDocument` renders only a release or the draft an environment
  follows (contract ADR 0003). The dialog says so: deploy the draft to a test environment.
- **Without a form** the dialog goes to the job's page, `…/templates/{key}/jobs/{token}`. **With a
  form** the browser goes to the form, whose `returnUrl` is that same page; the form adds `epistolaRequestId` and
  `epistolaRequestStatus`. The UI chooses the request id up front (`GenerateDocument.requestId`),
  because the token in the return URL is signed over it.
- **The job's page** shows the status, a fresh form link and Cancel while the form waits, the PDF
  once done, the reason when cancelled or failed; what it renders (release or draft, environment),
  when it was requested, submitted and finished, the fields changed in the form, and the data. It
  refreshes itself every two seconds while rendering and every five while the form waits.
- **Access:** the job routes take a `GenerationJobTokens` token, never a bare request id: signed with
  the form links' key under its own purpose, over the request, the person's user id and an expiry.
  Anything else gets 404 (`docs/auth.md`).

Tests: `GenerationJobTokensIT` (another person, expiry, tampering, a form session; the user and expiry
checks shown failing without them) and `TemplateGenerationHandlerTest` (the action and dialog, both
flows over HTTP, and the 404s; shown failing with a bare id accepted and with the user check removed).

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

| Risk                                          | Handling                                                                                                                                                        |
| --------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A public surface on Suite                     | Separate filter chain, token-only, neutral errors. Preview bound to the job's target and rate-limited. Ask for a security review before the toggle leaves alpha |
| Open redirect through `returnUrl` or links    | Origins per application (installation list only for callers without one), refused with `url-not-allowed`                                                        |
| A waiting job outlives its partition          | Boot check on retention against `max-expiry`                                                                                                                    |
| Dashboards assume every job heads to a worker | `awaiting-input` excluded from in-flight and queue metrics. Generation statistics reviewed                                                                      |
| A double submission from two tabs             | A conditional `UPDATE` on status. The loser sees the outcome                                                                                                    |
| The token leaks through Referer or logs       | `no-referrer`, the token never logged, the URL only in the page and the API response                                                                            |

## Order and size

1 → 2 → 3 gives a working API with no page, which can be tested by posting the form with curl in
integration tests. 4 makes it usable. 5 makes it demonstrable in the UI. 6 runs alongside. Steps 2
and 4 are the large ones.
