// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Lifts an ignore, so the finding counts again.
 *
 * Keyed on the **ignore** rather than on a finding row, because the two do not always coexist: an
 * ignored finding that got fixed is RESOLVED (or, for a subject that stopped being checked, may have
 * no live row at all) while its ignore lives on, ready for the problem's return. You must be able to
 * withdraw such an ignore. [ofFinding] is the convenience the UI uses when it does have a row.
 *
 * ### Why this soft-deletes
 *
 * Sources read dispositions back with a `since` cursor
 * ([app.epistola.suite.quality.queries.GetFindingDispositions]). A hard `DELETE` would simply vanish
 * from that feed — the source would never learn the ignore was lifted and would go on suppressing
 * the finding forever, or would have to re-scan every ignore each cycle, which is what the cursor
 * exists to avoid. Revoking instead **bumps `updated_at`**, so lifting an ignore is an observable
 * event on the same feed that delivered it.
 */
data class UnignoreFinding(
    override val tenantKey: TenantKey,
    val ignoreScopeUrn: String,
    val sourceId: QualitySourceId,
    val ruleId: String,
    val fingerprint: String,
) : Command<Boolean>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_EDIT

    companion object {
        /**
         * Resolves the ignore key from a live finding row. Prefer this from the UI, which is always
         * looking at a finding; use the primary constructor when only the ignore itself remains.
         */
        fun ofFinding(
            tenantKey: TenantKey,
            findingKey: QualityFindingKey,
        ): UnignoreFindingByFinding = UnignoreFindingByFinding(tenantKey, findingKey)
    }
}

/** [UnignoreFinding] addressed by a finding row — resolves to the same ignore key. */
data class UnignoreFindingByFinding(
    override val tenantKey: TenantKey,
    val findingKey: QualityFindingKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_EDIT
}

@Component
class UnignoreFindingHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UnignoreFinding, Boolean> {
    override fun handle(command: UnignoreFinding): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        revoke(
            handle = handle,
            tenantKey = command.tenantKey,
            ignoreScopeUrn = command.ignoreScopeUrn,
            sourceId = command.sourceId.value,
            ruleId = command.ruleId,
            fingerprint = command.fingerprint,
        )
    }
}

@Component
class UnignoreFindingByFindingHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UnignoreFindingByFinding, Boolean> {
    override fun handle(command: UnignoreFindingByFinding): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        val key = handle.createQuery(
            """
            SELECT ignore_scope_urn, source_id, rule_id, fingerprint
            FROM quality_findings
            WHERE tenant_key = :tenantKey AND id = :findingKey
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("findingKey", command.findingKey.value)
            .map { rs, _ ->
                listOf(
                    rs.getString("ignore_scope_urn"),
                    rs.getString("source_id"),
                    rs.getString("rule_id"),
                    rs.getString("fingerprint"),
                )
            }
            .findOne()
            .orElseThrow { IllegalArgumentException("No quality finding ${command.findingKey} in tenant ${command.tenantKey}") }

        revoke(handle, command.tenantKey, key[0], key[1], key[2], key[3])
    }
}

/**
 * Marks the ignore revoked. Returns false when there was no live ignore to lift, so a double-submit
 * is a no-op rather than an error. Guarded on `revoked_at IS NULL` so re-revoking cannot bump
 * `updated_at` again and replay a no-op event onto every source's disposition cursor.
 */
private fun revoke(
    handle: org.jdbi.v3.core.Handle,
    tenantKey: TenantKey,
    ignoreScopeUrn: String,
    sourceId: String,
    ruleId: String,
    fingerprint: String,
): Boolean {
    val actor = currentUserIdOrNull()?.value
    val updated = handle.createUpdate(
        """
        UPDATE quality_finding_ignores
        SET revoked_at = :now, revoked_by = :actor
        WHERE tenant_key = :tenantKey AND ignore_scope_urn = :ignoreScopeUrn
          AND source_id = :sourceId AND rule_id = :ruleId AND finding_fingerprint = :fingerprint
          AND revoked_at IS NULL
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("ignoreScopeUrn", ignoreScopeUrn)
        .bind("sourceId", sourceId)
        .bind("ruleId", ruleId)
        .bind("fingerprint", fingerprint)
        .bind("actor", actor)
        .bind("now", EpistolaClock.instant())
        .execute()
    return updated > 0
}
