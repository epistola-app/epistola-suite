# Interactive generation

> **Status:** Alpha, behind the `interactive-generation` feature toggle (off by default). Implements
> contract 2.1's interactive generation (contract ADR 0011). It may change in a minor release.

A caller asks for a document with the data it has. When data is missing, or every time if the
caller asks, the job waits for a person to complete the data in a form that Suite serves. The person
fills in the form, with a preview, and submits; the job then renders like any other.

The form is the only Suite page reachable without signing in. How it is secured is in
[`auth.md`](auth.md#the-interactive-generation-form-no-sign-in) and
[ADR 0055](adr/0055-interactive-generation-form-without-sign-in.md). Where a request keeps its form is [ADR 0056](adr/0056-where-an-interactive-request-keeps-its-form.md).

## The flow

1. **The caller** sends `generateDocument` with `interactive`:
   - `trigger`: `incomplete` (ask only when the data does not satisfy the contract) or `always`;
   - optionally `returnUrl`, `title`, `instructions`, `links`, `locale` and `expiresInMinutes`.

   With `incomplete` and valid data, the job renders straight away. Otherwise it waits in
   `awaiting-input`, and the response carries the form's link (`interactive.url`).

2. **The person** opens the link. The link works once and for minutes; the caller gets a fresh one
   from `getGenerationJobStatus` whenever it needs one. Only the caller that asked does: its
   application (any of its keys), or the same user without one. Anyone else who may read the job
   sees it waiting, without a link. Opening it starts a session on the form.
3. **The form** shows the fields the template's data contract asks for, pre-filled with what the
   caller sent, beside a preview of the document. The server validates every preview and the
   submission; nothing in the browser is trusted.
4. **Submitting** moves the job to `pending`, and it renders. **Cancelling** cancels it
   (`cancelReason: user`). A form nobody completes in time is cancelled by a sweep every minute
   (`cancelReason: expired`).
5. **Back to the caller:** with a `returnUrl`, the browser goes there with `epistolaRequestId` and
   `epistolaRequestStatus` (`pending` or `cancelled`) added. Without one, the page says the document
   is on its way, or that it was cancelled.

The job's items and collect lines carry the `data` the document rendered with and `editedFields`,
the JSON Pointers of the fields the person changed. A job with a form gets a `cancelled` line on the
collect stream when it is cancelled.

The job binds its release when it is accepted, like every request: a release cut or deployed while
the form waits does not change what the job renders, nor the contract the form shows.

## The form

The fields are generated from the bound release's data contract (`FormDefinition.Generated`),
rendered for the person filling them in (`modules/editor/.../data-form/respondent-form.ts`):

- labels come from each property's `title`, or else the key in words (`invoiceNumber` becomes
  "Invoice number"); the `description` is the help text under the label;
- groups (objects) are always open, so what the caller sent is visible; lists have add and remove;
- inputs follow the type and format: email, date, number, a select or radio buttons for an `enum`,
  a checkbox for a boolean;
- an empty required field is marked only once the person has left it, previewed or tried to submit;
  a wrong value the caller sent is marked at once;
- on a wide screen the preview stays beside the fields and renders by itself once nothing required
  is missing; on a phone it opens below the fields on request;
- the actions stay at the bottom of the screen, with a count of the required fields left, and the
  form's expiry is shown in the reader's own time.

To make a good form, give the data contract's properties a `title` and a `description`.

The fields appear in the order the stored schema has them, which is not the order the contract was
written in (it is stored as PostgreSQL `jsonb`). A form designer will set the order, labels and
grouping; until then the form is generated.

## From the web UI

A template's page has **Generate document** for whoever may generate documents in the tenant. The
dialog asks where to generate from (the catalog's latest release, or an environment, which renders
the release deployed there or the draft it follows), the variant, the data (starting from the
template's first data example) and, with the toggle on and a public base URL set, whether to ask for
the data in a form (no, when incomplete, always).

Each job then has a page of its own: its status, what it renders, the data, the fields changed in the
form, and the PDF to download. The form returns there. The page's link carries a token signed for the
person who started the job; see [`auth.md`](auth.md#following-a-generation-job-from-the-ui).

## Trying it in the demo

The demo tenant has the toggle on, and the demo catalog's **Objection Decision** template is built
for it:

- from its page, **Generate document** with "Always" opens the form, pre-filled with the example, for
  a person to review and edit;
- through the REST API, send the example without `motivation` and `decisionDate`, with
  `trigger: incomplete`: the form asks for exactly those two.

The demo needs `epistola.generation.interactive.public-base-url` set to the address the browser uses,
such as `http://localhost:4000`. Without it, the dialog offers no form and a request's `interactive`
is ignored: the job renders as if it had not been sent.

## Settings

Under `epistola.generation.interactive`:

| Setting                | Default | What it does                                                                                                                                       |
| ---------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `public-base-url`      | none    | The address people reach this installation at; links are built on it. **Required**: without it `interactive` is ignored, whatever the toggle says. |
| `default-expiry`       | 24h     | How long a job waits for the person when the caller names no `expiresInMinutes`.                                                                   |
| `max-expiry`           | 7d      | The longest wait a caller may ask for; longer is refused. At most 30 days.                                                                         |
| `link-lifetime`        | 15m     | How long a link can be opened. Between 1 minute and 1 hour.                                                                                        |
| `session-idle-timeout` | 60m     | How long a person's session on the form lasts without a request; never past the job's wait.                                                        |
| `allowed-origins`      | none    | Origins a caller **without** an application (a bearer token, the demo secret) may use as `returnUrl` or in `links`.                                |
| `preview-concurrency`  | 4       | Previews this node renders at once, over all forms. Each form also gets one preview at a time and 20 a minute. Beyond that the form gets 429.      |

**Retention.** A waiting job lives in the partition of the month it was created in, so
`epistola.partitions.retention-months` must keep that partition for the longest wait. The boot is
refused when it would not.

**Personal data.** A form holds the caller's data and texts, and a result of a job with a form
carries the submitted data and edited fields. The form is dropped with its request: by retention,
and when the request is deleted with its tenant, template, variant, version or environment (a
trigger, ADR 0056). Results survive their tenant so collectors can still read them, but deleting the
tenant clears the data and edited fields they carry (`ForgetDeletedTenantResultData`); otherwise
results retention (`generation-results-retention-months`, 1 by default) removes them.

**Return addresses.** A `returnUrl` or link must start with an allowed address
(`ReturnUrlPolicy`):

- the calling application's own list, set on its page under **Form return addresses**
  (`applications.allowed_return_urls`, ADR 0054), such as `https://cases.example.org/zaken`;
- for a caller without an application (a person in the web UI, a bearer token, the demo secret),
  the installation's `allowed-origins`;
- always this installation's own origin, which the web UI's "Generate document" returns to.

"Starts with" is not a string prefix (`ReturnUrlPrefix`): the scheme, host and port must be the
entry's exactly, and the path, with dot segments resolved, must be the entry's path or lie under it
at a `/`. So `https://cases.example.org/zaken` allows `/zaken/Z-1` but not `/zakenlijst`,
`/zaken/../admin` or `https://cases.example.org.evil.example/zaken`; a path hiding a dot segment
or separator behind `%2e`, `%2f`, `%5c` or a backslash never matches. An entry of an origin alone
allows every page there. Anything else is refused with `url-not-allowed`.

**Rollback.** The migration that adds forms is not rollback-compatible: an installation that has
used the feature cannot go back to an earlier release.

## Where the code is

| Part                         | Where                                                                                                 |
| ---------------------------- | ----------------------------------------------------------------------------------------------------- |
| Accepting, waiting, expiring | `modules/epistola-core/.../documents/interactive/`, `GenerateDocument`, `ExpireInteractiveForms`      |
| Links, sessions, guard       | `InteractiveFormLinks` (one HMAC key per installation, a purpose per kind of token)                   |
| The public routes            | `apps/epistola/.../forms/InteractiveFormHandler.kt`, `InteractiveFormSecurityConfig`                  |
| The form's fields            | `modules/editor/src/main/typescript/data-form/`, the `data-form` bundle                               |
| The page                     | `templates/forms/form.html`, `static/js/pages/interactive-form.js`, `static/css/interactive-form.css` |
| Generating from the UI       | `apps/epistola/.../handlers/TemplateGenerationHandler.kt`, `GenerationJobTokens`                      |
| What it costs the pipeline   | [Interactive generation load impact](interactive-generation-load-impact.md)                           |
