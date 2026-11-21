# ADR 0055: The interactive generation form, a page without sign-in

- **Status:** Accepted — implemented (alpha, behind the `interactive-generation` toggle)
- **Date:** 2026-11-20
- **Deciders:** Epistola team
- **Tags:** security, authentication, generation, forms
- **Related:** contract ADR 0011 (interactive generation), epistola-contract#134 (short-lived
  links), [ADR 0010](0010-strict-script-src-csp.md) (strict CSP), [ADR 0054](0054-applications-credentials-and-grants.md)
  (applications), [`docs/interactive-generation.md`](../interactive-generation.md),
  [`docs/auth.md`](../auth.md#the-interactive-generation-form-no-sign-in)

## Context

Contract 2.1 lets a caller ask that a person complete a document's data in a form before it renders.
That person has no Epistola account: the caller (a case system, a portal) authenticated them and
hands them a link. So Suite serves, for the first time, a page reachable without signing in — and
behind it a preview, which runs the renderer.

This is the surface to review hardest. Three questions decide its shape:

1. What does the link prove, for how long, and what happens when it leaks (browser history, a
   forwarded email, a screenshot, a `Referer`)?
2. What keeps a post to the form from being forged by another site, without Spring's session-bound
   CSRF token?
3. What keeps the preview from being a free, anonymous renderer?

A fourth arose when the web UI started jobs with a form: a signed-in person returning from the form
must see their own job, while `DOCUMENT_VIEW` reads every document in the tenant. The same holds for
the job status over REST, which hands out links (§8).

## Decision

### 1. Links are short-lived, single-use codes; the form runs on a session

- **The link** is `/forms/{requestId}?authCode={code}`. The code is signed (HMAC-SHA256) by the
  installation over the request, its own expiry (`link-lifetime`, 15 minutes by default) and a
  nonce. Nothing is stored when one is handed out, so `getGenerationJobStatus` hands out a fresh one
  on every call; a caller fetches one when the person is about to open it.
- **Opening it redeems it once:** the nonce is recorded (`generation_form_link_redemptions`), and a
  second opening finds it used. The response sets a session cookie and redirects to the URL without
  the code, so the code never stays in the address bar or the history.
- **The session** is a signed token in an `HttpOnly`, `SameSite=Lax` cookie scoped to
  `/forms/{requestId}` (`Secure` behind an https public base URL). Its expiry slides with use
  (`session-idle-timeout`) and never passes the job's own wait.

A leaked link is therefore dead within minutes, and after the person has opened it, at once.

### 2. A guard in the page instead of Spring's CSRF token

The form's chain (`InteractiveFormSecurityConfig`) is stateless: no HTTP session, no login
redirect, no Spring CSRF. Every post (submit, cancel, preview) carries a guard signed over the
request, written only into the form's own page. Another site can make the browser send the
`SameSite=Lax` cookie only on a top-level GET, and cannot read the page to learn the guard.

### 3. One key, a purpose per kind of token

One random key per installation, stored in `app_metadata`, signs every kind of token: link codes,
sessions, guards, and the UI's job tokens (below). Each kind has its own purpose label inside the
MAC, so none passes for another. Deleting the key rotates all of them at once. Nothing is stored per
token except the redeemed nonces.

### 4. Nothing reaches a form by its id alone, and failures look alike

The core commands (`GetInteractiveForm`, `SubmitInteractiveForm`, `CancelInteractiveForm`,
`PreviewInteractiveForm`) check the session themselves; they are `SystemInternal` because the person
has no principal, and the session is the authority over one form and nothing else. An unknown
request, a request without a form, a forged, expired or used code, and a closed form all get one
neutral page, so the page does not reveal which forms exist. Responses send
`Referrer-Policy: no-referrer`, `Cache-Control: no-store` and the UI's strict CSP.

### 5. The preview renders only the job's own target, within bounds

The preview's body is data only: the job's bound release or draft, variant and theme are fixed when
the job is accepted. Data that does not validate is not rendered (the answer lists what is missing).
Each form gets one preview at a time and 20 a minute; each node renders `preview-concurrency` (4)
at once over all forms, on the request thread, outside the job queue. Beyond that: 429.

### 6. Where the person returns is the caller's, within allowed addresses

A `returnUrl` or link must start with an allowed address: one of the calling application's own
(ADR 0054, set on its page), or for a caller without an application one of the installation's
`allowed-origins`, or this installation's own origin. "Starts with" means the same scheme, host and
port, and a path under the entry's at a `/` after resolving dot segments (`ReturnUrlPrefix`); never
a string prefix, which a look-alike host (`cases.example.org.evil.example`), a sibling path or a
`..` would get past, and never a path that hides a dot segment behind percent-encoding.

### 7. The UI follows its own jobs by a token, never a bare id

When the web UI starts a job (with or without a form), it follows it at
`…/templates/{key}/jobs/{token}`. The token is signed with the same key under its own purpose, over
the request, the signed-in person's user id and an expiry. Every job route answers 404 unless the
token is valid, unexpired, names the signed-in person and names a job of that tenant and template.
The form's return URL carries it, so it shows up in history; to anyone else it is useless.

### 8. Only the caller that asked gets a link from the job status

A link controls the form: whoever opens it submits the data the document renders from, or cancels.
`DOCUMENT_VIEW` reads every job of the tenant for a person, a bearer token and an application that
sees every document, so handing a link to every reader of a job would let a reader write into
another application's flow. The request records who asked (`requested_by`: the caller's
application, so any of its keys, or its user id without one), and the job status hands a link only
to that caller. Every other reader sees the job wait, without one.

## Alternatives considered

- **Long-lived links, or the request id as the secret.** Simple, and the contract allowed it at first,
  but a link in an email or a history outlives its purpose. Rejected for short-lived single-use
  codes (epistola-contract#134).
- **Tokens stored in the database**, looked up per request. Revocable one by one, but every link
  handed out writes a row, and the status call hands one out each time. Signed tokens store nothing;
  only redemptions are recorded, to make links single-use.
- **JWTs.** The same idea with a larger format and algorithm choices to get wrong. An HMAC over a
  fixed binary payload is all that is needed.
- **Spring Security's session and CSRF token on the public chain.** Would create an HTTP session per
  anonymous visitor and tie the form to the UI's session store. The guard does the job without it.
- **Rendering incomplete data in the preview leniently.** Kinder to the person, but then the preview
  is not what submitting produces. Deferred until authors ask.

## Consequences

- Suite has an anonymous surface. It stays alpha until a security review of the chain, the tokens
  and the preview bounds.
- Rotating the key (deleting it) invalidates every open link, session and UI job token together;
  there is no revoking one token on its own, but closing a form (submit, cancel, expiry) makes its
  session useless.
- Every opened link adds a redeemed nonce; the expiry sweep deletes it once the link would have
  expired anyway, so the table holds at most `link-lifetime` worth of openings.
- The editor's static bundles (`/editor/**`) are reachable without signing in, because the form loads
  one. They are code, not data.
- A new kind of token signed with the installation key must take a purpose of its own, and a test that
  the other kinds do not pass for it.
