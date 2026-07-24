// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

/**
 * Opt-in marker for a command/query that supplies short, structured **audit details** — recorded as
 * a key/value map in the `audit_log.details` (JSONB) column so an entry can carry concrete context,
 * e.g. `RestoreTenantBackup` → `{"backupId": "0193ef00-…"}`. Structured (rather than free text) so the
 * values can be shown as chips and, later, filtered on.
 *
 * **Contract: the details MUST be PII-free.** The audit log is otherwise PII-free by construction (it
 * stores no command payload, only curated metadata); this is the one author-supplied field, so — like
 * the machine-readable `error_code` — it is the author's responsibility to keep keys/values to
 * identifiers, counts, and fixed phrasing, never names/emails/free user input. Built from the
 * command's own fields at record time (the handler result is not available to the recorder).
 *
 * Follows the existing marker-interface idiom ([TenantScoped], [EntityIdentifiable], [NotAudited],
 * [AuditedRead]); a compile-time tag, no annotations or reflection.
 */
interface AuditDetailed {
    /** Short, **PII-free** key/value details for the audit trail (empty to record none). */
    val auditDetails: Map<String, String>
}
