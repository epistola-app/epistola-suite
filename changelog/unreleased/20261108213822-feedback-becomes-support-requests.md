---
type: feat
scopes: [support]
audience: user
breaking: true
title: Feedback becomes support requests, sent to Epistola support with each tenant's consent.
---

What users report from inside the suite (problems, questions, suggestions) is now a **support
request**: under Support → Support requests, at `/tenants/{t}/support/requests`. A request is kept in
the installation as messages (its question, then replies) with a delivery per support connection
recording what was sent where.

With `requests` selected under Platform → Support connections and granted by Epistola, a tenant's
requests go to the Epistola support service over protocol v2, but only for a tenant that agreed to
share them: a step of the setup wizard on its Support page, or a default an installation administrator
sets for every tenant (Helm `support.tenantDefaults.requests`). Status and replies come back through a
changes feed the suite acknowledges, so the service keeps how far it got. The submitter's priority is
the **requested priority**; the priority Epistola works with shows beside it when it differs.
Withdrawing consent stops all sharing at once: nothing written while sharing is off is ever sent. When
the support service is down, requests, comments and screenshots wait in the installation and are never
given up.

A request can carry the **server log of the submitter's last requests**: every page and answer now
names its trace (`<meta name="traceparent">` and a `traceresponse` header), the form remembers the
traces of the last 20 requests on the page, and on submitting the suite attaches this tenant's log
lines of those requests written for that user, at most 200, pseudonymised when telemetry is. It is on
by default and can be unticked in the form.

Existing feedback is not moved: support requests start empty, and the `feedback`, `feedback_comments`
and `feedback_assets` tables stay in place, unused. Feedback was alpha, so this changes in a minor
release: the module is `epistola-support-requests`; the feature toggle is `support-requests` (was
`support-feedback`, configuration `epistola.features.support-requests`), and a tenant's saved setting
moves with it; the sync properties are `epistola.support.requests.sync.*` (were
`epistola.feedback.sync.*`). See docs/support-requests.md.
