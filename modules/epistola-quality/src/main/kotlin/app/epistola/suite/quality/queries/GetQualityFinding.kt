// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.quality.QualityFinding
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * One finding by key, for the report's detail page.
 *
 * A finding of any check, also one no version shows any more, as long as it is kept; `outdated` when
 * the variant reads differently now in its catalog's next version.
 *
 * Derives `effective_status` the same way [ListQualityFindings] and [GetFindingsForSubject] do — the
 * `IGNORED` state is a live ignore row, not a stored status, so every read has to join for it. The
 * three queries share [mapFinding] so a column added to the row model reaches all of them at once.
 *
 * Null when no such finding exists in the tenant. Scoping the lookup by `tenant_key` as well as `id`
 * is what makes a guessed id from another tenant a 404 rather than a leak.
 */
data class GetQualityFinding(
    override val tenantKey: TenantKey,
    val findingKey: QualityFindingKey,
) : Query<QualityFinding?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetQualityFindingHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetQualityFinding, QualityFinding?> {
    override fun handle(query: GetQualityFinding): QualityFinding? = jdbi.withHandle<QualityFinding?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT f.*, ${QualityFindingSql.OUTDATED} AS outdated, ${QualityFindingSql.columns("f")}
            FROM quality_findings f
            ${QualityFindingSql.decisions("f")}
            WHERE f.tenant_key = :tenantKey AND f.id = :findingKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("findingKey", query.findingKey.value)
            .bind("manualSourceId", QualitySourceId.MANUAL.value)
            .map { rs, _ -> mapFinding(rs, objectMapper) }
            .findOne()
            .orElse(null)
    }
}
