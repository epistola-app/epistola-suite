// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.stencils.StencilStore
import db.migration.core.V20261025083103__core_stencils_to_storage
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper

/**
 * Proves, for every stencil of [catalog]'s next version, that the migration that moved stencils to
 * JSON storage (`V20261025083103`) converts a stencil's 1.x columns exactly as the suite now stores
 * it, both ways (ADR 0033, ADR 0035): the converted content has the content hash the suite stored it
 * under, the references it finds are the ones the suite indexed, and the one reader
 * ([StencilStore.fieldsOf]) reads from it what it reads from the stored content.
 */
object StencilStorageConversion {

    fun verify(jdbi: Jdbi, objectMapper: ObjectMapper, stencils: StencilStore, catalog: CatalogId) {
        val migration = V20261025083103__core_stencils_to_storage.Companion
        val softly = SoftAssertions()
        var seen = 0
        jdbi.useHandle<Exception> { handle ->
            val stored = handle.createQuery(
                """
                SELECT s.id, r.content_hash, s.payload::text AS payload,
                       (SELECT string_agg(COALESCE(x.alias, '') || '/' || x.resource_type || '/' || x.resource_key, ',')
                        FROM resource_references x WHERE x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash) AS refs
                FROM current_stencils s
                JOIN release_resources r ON r.tenant_key = s.tenant_key AND r.version_id = s.version_id AND r.resource_type = 'stencil' AND r.resource_key = s.id
                WHERE s.tenant_key = :t AND s.catalog_id = ${catalogAt("t", "c")}
                """,
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .map { rs, _ -> listOf(rs.getString("id"), rs.getString("content_hash"), rs.getString("payload"), rs.getString("refs").orEmpty().split(",").filter { it.isNotEmpty() }.sorted().joinToString(",")) }
                .list()
            for ((key, hash, payload, refs) in stored) {
                seen++
                val fields = stencils.fieldsOf(objectMapper.readTree(payload))
                // The columns as the suite kept them: tags, content and schema as JSONB.
                val converted = migration.payload(
                    key = key,
                    catalogKey = catalog.key.value,
                    name = fields.name,
                    description = fields.description,
                    tags = objectMapper.writeValueAsString(fields.tags),
                    content = objectMapper.writeValueAsString(fields.content),
                    parameterSchema = fields.parameterSchema?.let(objectMapper::writeValueAsString),
                    readsTemplateData = fields.readsTemplateData,
                )
                softly.assertThat(migration.contentHash(migration.canonical(converted))).`as`("content hash of stencil $key").isEqualTo(hash)
                softly.assertThat(migration.references(converted).map { "${it.alias.orEmpty()}/${it.type}/${it.key}" }.sorted().joinToString(","))
                    .`as`("references of stencil $key").isEqualTo(refs)
                softly.assertThat(stencils.fieldsOf(objectMapper.readTree(objectMapper.writeValueAsString(converted)))).`as`("stencil $key read from what the migration stores").isEqualTo(fields)
            }
        }
        softly.assertThat(seen).`as`("stencils checked").isPositive()
        softly.assertAll()
    }
}
