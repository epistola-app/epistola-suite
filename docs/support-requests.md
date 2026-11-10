# Support requests

> **Status:** Current for the local feature; sending to Epistola support is alpha, like the
> [support protocol](support-protocol.md) it uses (requests are protocol v2, scope `requests`).
> Called feedback until November 2026 (ADR 0053 §2).

Users report problems, ask questions and suggest things from inside the suite, as support requests.
They are stored in the installation's own database, which stays the source of truth. The
installation's own **support desk** handles them, or hands them to Epistola support over the
installation's support connection when the tenant consents; replies from Epistola come back onto the
request.

Code: `modules/epistola-support-requests` (domain, sync, UI, migrations). The connection itself is
`modules/epistola-support` (`SupportConnection`).
[ADR 0005](adr/0005-feedback-storage-local-vs-remote.md) records why the local store stays the source
of truth, and [ADR 0050](adr/0050-support-connection.md) §9 why sending needs the tenant's consent.

## Using it

- A floating button (`templates/support-requests/footer-fab.html`, `static/support-requests/support-request-fab.js`) opens
  the submit dialog on every tenant page while the `support-requests` feature toggle is on.
- The dialog takes a title, description, category and the priority the submitter asks for (the
  requested priority; whoever handles the request sets the priority it works with, which comes back
  here and shows beside the requested one). It also fills in hidden fields: the
  page address (`window.location.href`, query string included), the page's last 100 browser
  console entries (`support-request-capture.js`), browser metadata (application version, user agent,
  language, platform, viewport, screen size, pixel ratio, time; `support-requests.js`) and, when the user
  takes one, a screenshot as a JPEG of at most 1 MB (`support-request-screenshot.js`).
- `/tenants/{t}/support/requests` lists the tenant's support requests; the detail page shows the description,
  console log, screenshot, comments and status. The detail page is for the submitter and for those
  with `SUPPORT_REQUEST_HANDLE`.

The `support-requests` toggle defaults to on and only shows or hides the support request UI. **It is not
consent**: it sends nothing.

## The support desk

Platform → Support requests (`/platform/support-requests`) is the installation's inbox of support
requests across tenants (ADR 0053 §3–4), for the `SUPPORT_DESK` platform role (identity-provider
group `/epistola/platform/support-desk`). Who handles new requests stays the installation
administrator's to set on the Epistola support page; the desk shows it read only
(`GetSupportDeskHandling`). It lists requests newest first, paged in the database, filtered by
status, priority, tenant and who handles them (`ListSupportInbox`). The tenant filter offers every
tenant with requests (`ListSupportInboxTenants`), not only those the reader is a member of; a
tenant's own Support requests page shows that tenant's requests only.

- **Who handles a request.** Each request has a handler: the inbox (`support_requests.handler_connection`
  null) or the connection it went to. Only the handler sets the status and the final priority: the
  desk on a request the inbox handles (`SetSupportDeskStatus`, `SetSupportDeskPriority`), the
  connection's changes feed on one it handles (`SyncSupportRequestStatus` and `…Priority` apply only
  then). The tenant always closes its request, or reopens one that is closed or resolved, from its
  page, whoever handles it (`UpdateSupportRequestStatus` takes only `CLOSED` and `OPEN`); for a
  request a connection handles, that change is sent there.
- **Who handles new requests.** The inbox by default. An installation administrator chooses on
  Platform → Support connections (`/platform/support`, the panel **New support requests**, which
  `epistola-support-requests` adds through the `SupportPlatformPanel` SPI; `SetSupportRequestHandling`, stored as `support_connections.handles_new_requests` and
  marked saved in `app_metadata` key `support.requests.handling-saved`); until then the configuration
  applies (`epistola.support.requests.handled-by`, Helm `support.requests.handledBy`: `inbox` or
  `epistola`), and **Follow the configuration** drops a saved choice (`ResetSupportRequestHandling`).
  With Epistola handling new requests, a new one goes there when the tenant consents, as in the pilot;
  otherwise it stays in the inbox. Requests already handed on stay where they are when this changes.
- **Replies and internal notes.** The desk replies (`AddSupportDeskMessage`), which the requester sees
  and which goes to the connection with the request, through its ledger like any reply; or it
  writes an internal note (message kind `NOTE`), which only the desk sees. A note enters no ledger,
  and every query that picks messages to send reads `REPLY` only, so a note never leaves the
  installation (`SupportDeskIntegrationTest` proves it).
- **Passing a request on** (`EscalateSupportRequest`): the request gets a delivery to the connection,
  which handles it from then on; it is sent as any request is, with replies written so far, and its
  replies come back. Refused, with the reason on the page, when the tenant does not share support
  requests with that connection now, or no connection takes requests.

## Sending to Epistola support

A support request leaves the installation only when it goes to a connection (Epistola handles new
requests, or the desk passed it on; see [The support desk](#the-support-desk)), and all of these hold:

1. the support connection is on (`epistola.support.enabled=true`) and Epistola has activated the
   installation and granted it the `requests` scope, and an installation administrator selected the
   support request service (Platform → Support connections, or `epistola.support.services`);
2. the tenant consented, on its Support page (`/tenants/{t}/support`, `INTEGRATIONS_MANAGE`).

`SupportRequestSyncGate` is the one place that checks this before any call; every driver asks it.

### What is sent, and to whom

To the support service the installation is connected to (`epistola.support.endpoint`, by default
`https://support.{product-domain}`), run by Epistola Nederland B.V., over the protocol in
[`support-protocol.md`](support-protocol.md#support-requests--scope-requests-v2):

| What                                                                                               | When                                 |
| -------------------------------------------------------------------------------------------------- | ------------------------------------ |
| tenant key (or its reference¹), the request's id                                                   | with every item                      |
| who submitted it, as a person reference³                                                           | when the item is submitted           |
| title, kind, requested priority, status, question                                                  | when the item is submitted           |
| page address including query string                                                                | when the item is submitted           |
| console log (≤ 256 KiB; oldest entries dropped)                                                    | when the item is submitted           |
| server log of the last requests², when attached                                                    | when the item is submitted           |
| browser metadata (≤ 16 KiB; left out when larger)                                                  | when the item is submitted           |
| screenshot (PNG, JPEG or WebP, ≤ 2 MiB)                                                            | after the item, as a separate upload |
| comments with their author's person reference³, and name and email address while tenants are named | when written in the suite            |
| status changes                                                                                     | when changed in the suite            |

¹ While the installation keeps its tenants confidential (the default, [support](support.md#privacy)),
the tenant goes as its reference (`t-` and 16 hex digits), which also replaces the tenant key in the
page address, console log and metadata.

³ A person reference (ADR 0053 §3) is `p-` and 16 hex digits, an HMAC of the user's id under the same
installation secret as the tenant references (`TenantReferences.personReference`), over an input no
tenant key can have. It is the same for every request and comment of one person, so whoever handles
requests sees that they come from one person, and never who. The name of who submitted a request is
never sent; a comment's author goes by reference alone while tenants are confidential
(`messages.author_id` keeps who wrote it here).

² The suite's log lines of the submitter's last 20 requests on the page, only this tenant's, at most
200 lines, when the submitter leaves "Attach the server log of my last requests" ticked (it is by
default). Pseudonymised when the installation pseudonymises telemetry: each line's message as written
in the code without its values, and exceptions without their messages. The application log keeps its
lines for about a week, so it covers requests sent soon after the problem.

Not sent: who submitted the item (neither name nor id). Epistola's replies (comments, status
changes) come back through the update feed.

The settings page lists the same, says to whom, and states why consent cannot be given when it
cannot: the connection is off, not connected, waiting to be activated, or without the support request service.

### Consent

**Not allowed.** An installation administrator can make sharing support requests **not allowed** under
Platform → Support connections (`SetSharingRestriction`, ADR 0052 §5). That withdraws every tenant's
consent at once, its own choice included, and stops what waits to be sent; `GrantSupportRequestSyncConsent`
then refuses, and the Support page says why and who decides. Support requests
cannot be made Required: sending tenant content needs the tenant's own opt-in.

**Where a tenant sets it.** The tenant's **Support** page is the one place (`SupportSharingHandler`,
with one `TenantSharingSection` per module): a **setup wizard** while nothing is set up (introduction,
support requests, catalogs, agree on the tenant's behalf), and afterwards who set it up and what is shared,
with **Change**, which reopens the wizard with the choices filled in. While a tenant shares support requests
that cannot be sent yet (the connection waits to be activated, is paused or unreachable, or does not grant
the service), the Support page says why under that choice, for each connection.

**The installation's setup.** An installation administrator can set it up for every tenant under
Platform → Support connections (Change, then agree on the tenants' behalf when the setup shares
anything) (`SetSupportRequestSharingDefault`, `ClearSupportRequestSharingDefault`,
`INSTALLATION_ADMINISTRATOR`), or the deployment can (`epistola.support.tenant-defaults.requests`,
Helm `support.tenantDefaults.requests`, applied at startup only while none is saved). Tenants that
have not chosen themselves then skip the wizard. It applies at once, and to tenants created later
(in the transaction that creates them), written into the tenant's consent row (`follows_default`) as
given or withdrawn by that administrator, or by the configuration, so every rule below holds
unchanged. A tenant's own choice wins until it uses the installation's setup again
(`FollowSupportRequestSharingDefault`); removing the preset stops what it shared and those tenants set
themselves up.

- **Granting** (`GrantSupportRequestSyncConsent`, `INTEGRATIONS_MANAGE`) applies to requests submitted
  from then on. Requests submitted before keep `NOT_CONFIGURED` and are not sent unless the desk
  passes one on: consent is not applied retroactively. What of a request may go to a connection is
  its ledger, `support_request_delivered_items`, the same for every connection, Epistola support's
  included (`SupportRequestLedger`): when a request is handed to a connection while the tenant shares
  with it, everything it holds so far goes along; afterwards a message or screenshot goes when it is
  written while the tenant still shares (`mayGoTo`). A request is exchanged while the tenant shares
  with the connection it is delivered to (`exchangedWith`, `IsSupportRequestExchangedUnderConsent`).
  The page offers consent only once the installation can send (connected with the support request
  service), so the recipient can be named.
- **Withdrawing** (`WithdrawSupportRequestSyncConsent`) is always possible while consent is in force. It
  stops everything not sent, in the same transaction: requests still waiting to be sent (`PENDING`,
  or `FAILED` after refusals) moves to `NOT_CONFIGURED`, a status change not sent yet is no longer
  marked unsent (`status_unsent`), and comments and assets not sent yet leave the connection's
  ledger, so granting again later sends none of them. A write landing at the same moment is not missed: the withdrawal
  locks the consent row from its first statement, and every write that may be sent reads that row
  `FOR SHARE` (`lockedConsentInForce`), so it either waits and finds consent withdrawn or commits
  first and is stopped with the rest. A sending round already under way reads each item again right
  before it sends it (`IsSupportRequestItemStillToSend`), so it starts nothing after the withdrawal; only a
  call already on its way at that moment completes. When Epistola takes it, Epistola holds that item;
  when it fails, the item stays stopped like the rest (`RecordSupportRequestSyncFailure` changes only
  requests still waiting). Replies from
  Epistola are no longer applied (the poll reads past them, and does not apply them later). What
  Epistola already holds stays there.
- **Status changes while sharing is off.** A status changed while consent is not in force is stored
  locally only: `UpdateSupportRequestStatus` marks it unsent only while the request is exchanged with
  the connection, and `UpdateSupportRequestSyncRef` does the same for a change made while the request
  was on its way. It is never sent, also not once consent is given again; Epistola keeps the last
  status it received until the status is changed again once the tenant shares again.
- **Giving consent again** carries on with requests Epistola already received: comments, screenshots
  and status changes made from then on are sent, and replies from then on are applied again. What was
  written or changed while sharing was off is never sent, and requests submitted then stay local.
- **Disconnecting** the installation (Platform → Support connections, `DisconnectSupportConnection`)
  forgets every tenant's choice and the installation's setup (`ForgetSupportRequestSharing`): what waits
  to be sent stops as on a withdrawal, and after registering again every tenant sets itself up
  again. The requests stay here. Epistola support keeps what it holds for a retention period after
  the disconnect, then deletes it ([protocol](support-protocol.md#disconnecting)).
- `GetSupportRequestSyncConsent` reads it; `IsSupportRequestSyncConsentInForce` is the `SystemInternal` form the
  drivers use. Both commands are recorded in the audit log, which holds the full history; the table
  holds the latest state.

### How it is sent

`SupportRequestSyncPort` is the seam. `RestSupportRequestSyncAdapter` implements it over
`SupportConnection.restClient()` while `epistola.support.enabled=true`; otherwise
`NoOpSupportRequestSyncAdapter` keeps everything local.

- **Submit.** `CreateSupportRequest` marks an item `PENDING` only when the port is enabled and the tenant's
  consent is in force (read in the same `INSERT`), else `NOT_CONFIGURED`. `OnSupportRequestCreated`
  starts a sending round in the background (`SupportRequestSyncKick`), which sends it
  (`PUT {requests}/{tenantKey}/{suiteRequestId}`, then its description as the QUESTION message,
  `PUT {requests}/{requestId}/messages/{questionId}`, an id derived from the request's) and stores the
  service's `requestId` as `external_ref` (`SYNCED`). `{requests}` is the metadata's
  `epistola_requests_endpoint` (protocol v2). Nothing is sent on the user's request, so a slow or failing service never
  makes a page wait.
- **Screenshot.** The submit handler stores the screenshot after `CreateSupportRequest`.
  `OnSupportRequestAssetAdded` starts a round that sends it (`PUT {requests}/{requestId}/attachments/{assetId}`)
  once the request is `SYNCED`, and stamps `synced_at`.
- **Comments and status.** `OnSupportRequestCommentAdded` and `OnSupportRequestStatusChanged` start a round
  that sends local comments as REPLY messages (`PUT …/messages/{commentId}`) and status changes as
  STATUS messages (an id derived from the request, the status and when it was set, so a repeat is the
  same message).
  A local status change on a sent request is stored as unsent (`status_unsent`) until the service took
  it, so a change made while the service is down is sent once it is back; a change made while
  consent is not in force is not marked (see Consent). A change made while the request itself is on
  its way is caught when the service's id is stored: `UpdateSupportRequestSyncRef` compares the status it
  was sent with and leaves a different one unsent. A refused status backs off like any other item,
  counting its refusals in `status_sync_attempts`.
- **Retry sweep** (`SupportRequestSyncScheduler`, task `feedback.sync.retry`, one node, run off the
  scheduler thread): `PENDING` requests, then unsent assets, unsent comments and unsent statuses,
  each query filtered on consent in SQL (`exchangedUnderConsent`, `createdUnderConsent`) and on its
  back-off (`next_sync_at`), and each item read again right before it is sent. A refusal backs the
  item off, from one minute doubling to six hours; nothing is given up for good, and a request refused
  five times only reads `FAILED`. A failure that means "not now" ends the round without counting; a
  `500` on one item lets it wait a minute and the round goes on. A round spends at most `round-budget-ms`.
  Every sending round, the ones a change starts included, holds a cluster-wide lease
  (`SupportRequestRoundLock`, the `app_metadata` row `feedback.sync.round`, stamped by the database's
  clock): a node whose round finds another node's running waits for it (at most twice
  `round-budget-ms`, looking again every 250 ms) and then goes, so no item is sent by two nodes at
  once and two status changes made on different nodes reach the service in order. No connection is
  held while a round runs, so it works behind a pooler in transaction mode and trips no leak
  detection. A node that dies mid-round keeps the lease until it runs out (`round-budget-ms` plus the
  deadline of the largest call plus a minute, about two minutes by default); the next sweep then
  sends what is left. See
  [support-protocol.md](support-protocol.md#reliability).
- **Poll** (`SupportRequestPollScheduler`, task `feedback.sync.poll`, one node, on by default; it does
  nothing until the connection holds the `requests` scope): calls `POST {requests}/changes` and
  applies replies (`SyncSupportRequestComment`), status (`SyncSupportRequestStatus`) and the
  priority the handler set (`SyncSupportRequestPriority`), none of which echo back out. The
  connection keeps the position, as with collecting generated documents: each call acknowledges
  (`acknowledgeUpTo`) what the previous page brought, and a round calls until a page comes back
  empty. An update that fails to apply is not acknowledged, nor anything after it, so it comes
  again; changes of unknown types are skipped and still acknowledged.

Every write carries the suite's own id, so a repeat after a lost response is harmless. A failure
that means "not now" (the service unreachable or answering 5xx/429, a token without the `requests`
scope, a refused registration) ends the round without counting an attempt. A `403
insufficient_scope` also tells the connection that Epistola withdrew `requests`, so the drivers stop
asking until the next check finds it granted again; the request stays pending. The event handlers run in
the `IMMEDIATE` phase; their commands are `SelfManagedTransaction`, so no call is made inside a
database transaction.

## Configuration

| Property                                                 | Default  | Meaning                                                                               |
| -------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------- |
| `epistola.support.enabled`                               | `false`  | The support connection; without it nothing is sent                                    |
| `epistola.support.tenant-defaults.requests`              | empty    | Preset for tenants: `share` or `no` (see above)                                       |
| `epistola.support.requests.sync.enabled`                 | `true`\* | Run the retry sweep                                                                   |
| `epistola.support.requests.sync.retry-interval-ms`       | `60000`  | Sweep interval                                                                        |
| `epistola.support.requests.sync.round-budget-ms`         | `30000`  | Most one sweep or poll spends on the service                                          |
| `epistola.support.requests.sync.polling.enabled`         | `true`   | Read replies from Epistola                                                            |
| `epistola.support.requests.sync.polling.interval-ms`     | `60000`  | Poll interval while replies come in                                                   |
| `epistola.support.requests.sync.polling.max-interval-ms` | `300000` | After rounds that find nothing the wait doubles, up to this                           |
| `epistola.support.requests.handled-by`                   | `inbox`  | Who handles new requests until an administrator saves a choice: `inbox` or `epistola` |

\* `true` in the app's `application.yaml`; the code default is `false`. The connection's own
settings are in [`support-protocol.md`](support-protocol.md).

## Tables

In `db/migration/support-requests/`: `V20261108213145__support_requests.sql` creates them, and `V20261108215636__support_requests_desk.sql` adds the desk (a
handler per request, internal notes, message authors), giving every request already sent the
connection it went to as its handler. Support requests start empty: the feedback alpha (released in
`db/migration/feedback/`, which keeps its path) is not moved, and its tables (`feedback`,
`feedback_comments`, `feedback_assets`) stay in place, unused, because a migration never deletes data.

| Table                             | Holds                                                                                                                                                                                                             |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `support_requests`                | the requests: title, category, status, requested and current priority, the page context                                                                                                                           |
| `support_request_messages`        | its messages in `seq` order: the QUESTION (the description; its id is the request's) and REPLY messages, written here or arriving over a connection                                                               |
| `support_request_assets`          | screenshots as `BYTEA`                                                                                                                                                                                            |
| `support_request_deliveries`      | per request and support connection: `PENDING`, `SYNCED` or `FAILED`, the connection's reference, the back-off, and a status not yet taken                                                                         |
| `support_request_delivered_items` | per message or screenshot and connection: the connection's id for a message, or when a screenshot was sent; the attempts and back-off until then                                                                  |
| `support_sharing_choice`          | section `requests`: one row per tenant that ever chose: `given_at`/`_by`, and `withdrawn_at`/`_by` after a withdrawal; in force while `withdrawn_at` is null. Shared with the other services (`epistola-support`) |
| `support_sharing_default`         | section `requests`: the installation default, and whether it came from the configuration                                                                                                                          |

A request without a delivery is not going anywhere: it reads `NOT_CONFIGURED`.

## Upgrading from the hub

The gRPC hub this replaced was not used in practice. Feedback sent through it stays in the feedback
tables, unread (see Tables), and the old cursor `feedback.sync.lastSeq` in `app_metadata` is left
unread too.

## The host contract

Support requests are a module of the suite today, but it is meant to be movable to another application, or out
on its own (ADR 0052 §7). What it needs from the application hosting it is kept to this list, so that a
move is a move and not a rewrite:

| Need                        | How the suite provides it                                                                                                           |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| The page's request trace    | `<meta name="traceparent" content="00-…">` in every page (W3C Trace Context; the shell renders it)                                  |
| Each answer's request trace | a `traceresponse` header on every answer (W3C Trace Context level 2, set by `TraceFilter`)                                          |
| The browser console         | `support-request-capture.js` keeps the last 100 entries in `window.__epistola_console_buffer`                                       |
| The last requests           | `support-request-capture.js` keeps the last 20 trace ids in `window.__epistola_trace_buffer`, from the two above                    |
| The server log of a request | `SupportRequestLogSource`: lines of a tenant by trace id, shaped by the host's privacy rules (`ApplicationLogSupportRequestSource`) |
| Where requests go           | `SupportRequestSyncPort`: the connector to Epistola support (`RestSupportRequestSyncAdapter`), or none                              |
| Who may do what             | `SUPPORT_REQUEST_SUBMIT`, `SUPPORT_REQUEST_HANDLE`, `INTEGRATIONS_MANAGE`, per tenant                                               |

Anything else the module uses of the suite (the mediator, the tenant model, the page chrome) is how it
is built today, not part of what it needs. A new dependency on the host goes in this table, or it is a
reason to reconsider it.
