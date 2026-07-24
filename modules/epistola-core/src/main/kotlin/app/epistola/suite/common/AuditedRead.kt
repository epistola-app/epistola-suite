// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

/**
 * Opt-in marker for queries whose execution should produce an audit-log entry
 * (a READ row) — the "who read what" side of the trail.
 *
 * Read auditing is **opt-in**, the opposite polarity of command auditing (which
 * is opt-out via [NotAudited]). Queries are overwhelmingly high-volume internal
 * reads — nav, feature toggles, list pages, several per page render — so auditing
 * them all would bury the signal. Only mark the handful of genuine, sensitive
 * **data-access** reads (e.g. retrieving a stored document), not browse/list/
 * preview queries.
 *
 * A compile-time marker — the same idiom as [TenantScoped] / [EntityIdentifiable]
 * — interpreted by the audit feature module, which depends on core; the marker
 * lives here so core query types can declare it without a dependency cycle.
 */
interface AuditedRead
