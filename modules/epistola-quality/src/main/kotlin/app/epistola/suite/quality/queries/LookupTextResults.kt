// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.text.TextDiagnostic
import app.epistola.suite.quality.text.TextDiagnosticJson
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** The cached diagnostics found for some of the asked hashes; a hash absent here was never checked under this key. */
data class CachedTextResults(
    val byBlockHash: Map<String, List<TextDiagnostic>>,
)

/**
 * What [checkerId] reported earlier for each of [blockHashes], under [configuration] and [locale]
 * (ADR 0044): the result cache's read. Only the hashes it has not seen go to the checker.
 */
data class LookupTextResults(
    override val tenantKey: TenantKey,
    val checkerId: QualitySourceId,
    val configuration: String,
    val locale: String,
    val blockHashes: Set<String>,
) : Query<CachedTextResults>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class LookupTextResultsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<LookupTextResults, CachedTextResults> {
    override fun handle(query: LookupTextResults): CachedTextResults {
        if (query.blockHashes.isEmpty()) return CachedTextResults(emptyMap())
        val rows = jdbi.withHandle<List<Pair<String, String>>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT block_hash, diagnostics::text AS diagnostics
                FROM quality_text_results
                WHERE tenant_key = :tenantKey AND checker_id = :checkerId AND configuration = :configuration
                  AND locale = :locale AND block_hash = ANY(:hashes)
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bind("checkerId", query.checkerId.value)
                .bind("configuration", query.configuration)
                .bind("locale", query.locale)
                .bind("hashes", query.blockHashes.toTypedArray())
                .map { rs, _ -> rs.getString("block_hash") to rs.getString("diagnostics") }
                .list()
        }
        return CachedTextResults(rows.associate { (hash, json) -> hash to TextDiagnosticJson.read(objectMapper.readTree(json)) })
    }
}
