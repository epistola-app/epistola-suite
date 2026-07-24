// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

/**
 * Opt-out marker for commands that must **not** produce an `event_log` entry.
 *
 * `event_log` is the stream of successfully-executed commands ("this command
 * succeeded") that event-driven behaviour will react to. Every command dispatched
 * through the mediator is recorded there except those carrying this marker. Use it
 * for high-volume, low-signal work that nothing should react to and that is already
 * tracked in its own system of record — currently the document-generation path
 * (`generation_results` is its record).
 *
 * Independent of [NotAudited] on purpose: `NotAudited` opts out of the PII-free
 * `audit_log` ("who did what, when"); `NotEventLogged` opts out of the `event_log`
 * event stream. A command can warrant one and not the other, so the two markers are
 * kept separate rather than conflated. Compile-time marker interface — the same idiom
 * as [TenantScoped] / [NotAudited] — interpreted by `EventLogSubscriber`.
 */
interface NotEventLogged
