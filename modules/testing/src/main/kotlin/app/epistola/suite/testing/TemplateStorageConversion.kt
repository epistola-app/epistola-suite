// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.templates.TemplateModels
import app.epistola.suite.templates.TemplateStore
import db.migration.core.V20261025170700__core_templates_to_storage
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Proves, for every template of [catalog]'s next version, that the migration that moved templates to
 * JSON storage (`V20261025170700`) converts a template's 1.x columns exactly as the suite now stores
 * it (ADR 0033, ADR 0035): each model, given as 1.x kept it (references into its own catalog named
 * with it), converts to the content hash the suite stored it under, with the references the suite
 * indexed, and the template's content converts to its row's content hash.
 */
object TemplateStorageConversion {

    fun verify(jdbi: Jdbi, objectMapper: ObjectMapper, templates: TemplateStore, catalog: CatalogId) {
        val migration = V20261025170700__core_templates_to_storage.Companion
        val softly = SoftAssertions()
        var seen = 0
        jdbi.useHandle<Exception> { handle ->
            val stored = handle.createQuery(
                "SELECT id, content_hash, payload::text AS payload FROM current_templates WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .map { rs, _ -> Triple(rs.getString("id"), rs.getString("content_hash"), rs.getString("payload")) }
                .list()
            for ((key, hash, payload) in stored) {
                seen++
                val template = templates.of(objectMapper.readTree(payload))
                val variants = template.variants.map { variant ->
                    val model = checkNotNull(templates.model(handle, catalog.tenantKey, variant.modelHash))
                    // As 1.x stored it: qualified with its catalog.
                    val qualified = objectMapper.writeValueAsString(TemplateModels.read(model, catalog.key.value, objectMapper))
                    val converted = migration.releasedModel(objectMapper.readTree(qualified), catalog.key.value)
                    softly.assertThat(migration.contentHash(migration.canonical(converted))).`as`("model of $key/${variant.key}").isEqualTo(variant.modelHash)
                    val refs = handle.createQuery(
                        "SELECT COALESCE(alias, '') || '/' || resource_type || '/' || resource_key FROM resource_references WHERE tenant_key = :t AND content_hash = :h",
                    ).bind("t", catalog.tenantKey).bind("h", variant.modelHash).mapTo(String::class.java).list().sorted()
                    softly.assertThat(migration.references(converted).map { "${it.alias.orEmpty()}/${it.type}/${it.key}" }.sorted())
                        .`as`("references of $key/${variant.key}").isEqualTo(refs)
                    V20261025170700__core_templates_to_storage.Companion.Variant(
                        key = variant.key,
                        title = variant.title,
                        description = variant.description,
                        attributes = objectMapper.writeValueAsString(variant.attributes),
                        isDefault = variant.isDefault,
                        metadata = variant.metadata?.let(objectMapper::writeValueAsString),
                        model = qualified,
                    )
                }
                // The examples as 1.x kept them: each with an id beside its key.
                val examples = objectMapper.valueToTree<tools.jackson.databind.node.ArrayNode>(template.dataExamples).also { list ->
                    list.forEach { (it as ObjectNode).put("id", "00000000-0000-0000-0000-000000000000") }
                }
                val converted = migration.payload(
                    key = key,
                    name = template.name,
                    description = template.description,
                    themeKey = template.themeKey,
                    themeCatalogKey = template.themeCatalogKey,
                    pdfaEnabled = template.pdfaEnabled,
                    dataModel = template.dataModel?.let(objectMapper::writeValueAsString),
                    dataExamples = objectMapper.writeValueAsString(examples),
                    variants = variants,
                    modelHashes = template.variants.associate { it.key to it.modelHash },
                )
                softly.assertThat(migration.contentHash(migration.canonical(converted))).`as`("content of template $key").isEqualTo(hash)
            }
        }
        softly.assertThat(seen).`as`("templates checked").isPositive()
        softly.assertAll()
    }
}
