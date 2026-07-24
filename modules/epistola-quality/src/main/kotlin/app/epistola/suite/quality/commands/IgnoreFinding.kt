// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Marks a finding irrelevant, with a stated reason.
 *
 * The ignore is recorded against the finding's **scope + fingerprint**, not against its row. Three
 * consequences, all deliberate:
 *
 *  - it **carries forward across versions** — publish a new version and the ignore still applies,
 *    instead of every previously-dismissed finding resurfacing on every publish;
 *  - it **survives a resolve/resurface cycle** — a problem that comes back comes back ignored;
 *  - it **lapses when the problem materially changes** — that yields a new fingerprint, which no
 *    ignore matches, so it resurfaces for a fresh look. An ignore can never silently swallow a
 *    different problem than the one a human actually looked at.
 *
 * `requireCatalogEditable` is deliberately not called: ignoring is a judgement about a *finding*,
 * not an edit to content. It must work on a frozen published version and in a read-only system
 * catalog — the very places an author cannot "just fix it".
 */
data class IgnoreFinding(
    override val tenantKey: TenantKey,
    val findingKey: QualityFindingKey,
    val reason: String,
) : Command<Unit>,
    RequiresPermission {
    init {
        validate("reason", reason.isNotBlank()) { "A reason is required — an ignore without one is indistinguishable from a bug" }
        validate("reason", reason.length <= MAX_REASON_LENGTH) { "Reason must be $MAX_REASON_LENGTH characters or less" }
    }

    override val permission: Permission get() = Permission.TEMPLATE_EDIT

    companion object {
        const val MAX_REASON_LENGTH = 1000
    }
}

@Component
class IgnoreFindingHandler(
    private val jdbi: Jdbi,
) : CommandHandler<IgnoreFinding, Unit> {
    override fun handle(command: IgnoreFinding) {
        val now = EpistolaClock.instant()
        val actor = currentUserIdOrNull()?.value

        jdbi.useTransaction<Exception> { handle ->
            // Read the finding to derive the ignore key. The ignore then stands on its own: no FK
            // back to this row, so it outlives the finding and can pre-exist a resurface.
            val key = handle.createQuery(
                """
                SELECT ignore_scope_urn, source_id, rule_id, fingerprint, catalog_key, template_key
                FROM quality_findings
                WHERE tenant_key = :tenantKey AND id = :findingKey
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("findingKey", command.findingKey.value)
                .map { rs, _ ->
                    IgnoreKey(
                        ignoreScopeUrn = rs.getString("ignore_scope_urn"),
                        sourceId = rs.getString("source_id"),
                        ruleId = rs.getString("rule_id"),
                        fingerprint = rs.getString("fingerprint"),
                        catalogKey = rs.getString("catalog_key"),
                        templateKey = rs.getString("template_key"),
                    )
                }
                .findOne()
                .orElseThrow { IllegalArgumentException("No quality finding ${command.findingKey} in tenant ${command.tenantKey}") }

            handle.createUpdate(
                """
                INSERT INTO quality_finding_ignores (
                    tenant_key, ignore_scope_urn, source_id, rule_id, finding_fingerprint,
                    catalog_key, template_key,
                    reason, ignored_by, ignored_at, revoked_by, revoked_at
                ) VALUES (
                    :tenantKey, :ignoreScopeUrn, :sourceId, :ruleId, :fingerprint,
                    :catalogKey, :templateKey,
                    :reason, :actor, :now, NULL, NULL
                )
                ON CONFLICT (tenant_key, ignore_scope_urn, source_id, rule_id, finding_fingerprint) DO UPDATE SET
                    reason      = EXCLUDED.reason,
                    ignored_by  = EXCLUDED.ignored_by,
                    ignored_at  = EXCLUDED.ignored_at,
                    -- Re-ignoring un-revokes: the row is a soft-deleted tombstone after an unignore,
                    -- and this is how it comes back to life (rather than a second row appearing).
                    revoked_by  = NULL,
                    revoked_at  = NULL
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("ignoreScopeUrn", key.ignoreScopeUrn)
                .bind("sourceId", key.sourceId)
                .bind("ruleId", key.ruleId)
                .bind("fingerprint", key.fingerprint)
                // Carried from the finding purely so the database can collect this row when the
                // template goes; never read back.
                .bind("catalogKey", key.catalogKey)
                .bind("templateKey", key.templateKey)
                .bind("reason", command.reason)
                .bind("actor", actor)
                .bind("now", now)
                .execute()
        }
    }

    private data class IgnoreKey(
        val ignoreScopeUrn: String,
        val sourceId: String,
        val ruleId: String,
        val fingerprint: String,
        val catalogKey: String,
        val templateKey: String,
    )
}
