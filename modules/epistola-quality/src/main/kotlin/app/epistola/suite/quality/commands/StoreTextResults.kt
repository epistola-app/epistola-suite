// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.text.TextDiagnostic
import app.epistola.suite.quality.text.TextDiagnosticJson
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** How many results were new; the rest were already cached, written by a check that ran at the same time. */
data class StoredTextResults(
    val stored: Int,
)

/**
 * Caches what [checkerId] reported for each block hash (ADR 0044). A key already present keeps its
 * row: the same key is the same question, so a concurrent check of the same text found the same answer.
 */
data class StoreTextResults(
    override val tenantKey: TenantKey,
    val checkerId: QualitySourceId,
    val configuration: String,
    val locale: String,
    val byBlockHash: Map<String, List<TextDiagnostic>>,
) : Command<StoredTextResults>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_EDIT
}

@Component
class StoreTextResultsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<StoreTextResults, StoredTextResults> {
    override fun handle(command: StoreTextResults): StoredTextResults {
        if (command.byBlockHash.isEmpty()) return StoredTextResults(0)
        val now = EpistolaClock.offsetDateTime()
        val stored = jdbi.withHandle<IntArray, Exception> { handle ->
            val batch = handle.prepareBatch(
                """
                INSERT INTO quality_text_results (tenant_key, checker_id, configuration, locale, block_hash, diagnostics, checked_at)
                VALUES (:tenantKey, :checkerId, :configuration, :locale, :hash, CAST(:diagnostics AS JSONB), :checkedAt)
                ON CONFLICT DO NOTHING
                """,
            )
            command.byBlockHash.forEach { (hash, diagnostics) ->
                batch.bind("tenantKey", command.tenantKey)
                    .bind("checkerId", command.checkerId.value)
                    .bind("configuration", command.configuration)
                    .bind("locale", command.locale)
                    .bind("hash", hash)
                    .bind("diagnostics", objectMapper.writeValueAsString(TextDiagnosticJson.write(diagnostics, objectMapper)))
                    .bind("checkedAt", now)
                    .add()
            }
            batch.execute()
        }
        return StoredTextResults(stored.sum())
    }
}
