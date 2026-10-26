// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.templates.TemplateModels
import app.epistola.suite.templates.TemplateStore
import db.migration.core.V20261025170700__core_templates_to_storage
import db.migration.core.V20261026085934__core_variant_description_in_resource
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Proves, for every template of [catalog]'s next version, that the migration that moved templates to
 * JSON storage (`V20261025170700`), followed by the one that moved a variant's description into its
 * released form (`V20261026085934`), converts a template's 1.x columns exactly as the suite now stores
 * it (ADR 0033, ADR 0035): each variant, its model given as 1.x kept it (references into its own
 * catalog named with it), converts to the content its part names, with the references the suite
 * indexed, and the template's content converts to its row's content hash.
 */
object TemplateStorageConversion {

    fun verify(jdbi: Jdbi, objectMapper: ObjectMapper, templates: TemplateStore, catalog: CatalogId) {
        val migration = V20261025170700__core_templates_to_storage.Companion
        val softly = SoftAssertions()
        var seen = 0
        jdbi.useHandle<Exception> { handle ->
            val keys = handle.createQuery("SELECT id FROM current_templates WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
                .bind("t", catalog.tenantKey).bindCatalog("c", catalog).mapTo(String::class.java).list()
            for (key in keys) {
                seen++
                val loaded = checkNotNull(templates.get(handle, TemplateId(TemplateKey.of(key), catalog)))
                val template = loaded.template
                val variants = template.variants.map { variant ->
                    val model = checkNotNull(templates.model(handle, catalog.tenantKey, variant))
                    // As 1.x stored it: qualified with its catalog.
                    val qualified = objectMapper.writeValueAsString(TemplateModels.read(model, catalog.key.value, objectMapper))
                    val converted = V20261025170700__core_templates_to_storage.Companion.Variant(
                        key = variant.key,
                        title = variant.title,
                        description = variant.description,
                        attributes = objectMapper.writeValueAsString(variant.attributes),
                        isDefault = variant.isDefault,
                        metadata = null,
                        model = qualified,
                    )
                    val part = V20261026085934__core_variant_description_in_resource.moved(
                        migration.part(converted, migration.releasedModel(objectMapper.readTree(qualified), catalog.key.value)),
                    )
                    softly.assertThat(migration.contentHash(migration.canonical(part))).`as`("variant $key/${variant.key}").isEqualTo(variant.contentHash)
                    val refs = handle.createQuery(
                        "SELECT COALESCE(alias, '') || '/' || resource_type || '/' || resource_key FROM resource_references WHERE tenant_key = :t AND content_hash = :h",
                    ).bind("t", catalog.tenantKey).bind("h", variant.contentHash).mapTo(String::class.java).list().sorted()
                    softly.assertThat(migration.references(part).map { "${it.alias.orEmpty()}/${it.type}/${it.key}" }.sorted())
                        .`as`("references of $key/${variant.key}").isEqualTo(refs)
                    converted
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
                )
                softly.assertThat(migration.contentHash(migration.canonical(converted))).`as`("content of template $key").isEqualTo(loaded.contentHash)
            }
        }
        softly.assertThat(seen).`as`("templates checked").isPositive()
        softly.assertAll()
    }
}
