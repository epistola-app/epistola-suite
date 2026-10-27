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
import db.migration.core.frozen.CanonicalJson
import db.migration.core.frozen.DataExampleKeys
import db.migration.core.frozen.Envelopes
import db.migration.core.frozen.ReferenceWalker
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Proves, for every template of [catalog]'s next version, that the 2.0 upgrade's conversion of
 * templates to JSON storage (`V20261101000800`: [Envelopes.template], [Envelopes.variant]) converts a
 * template's 1.x columns exactly as the suite now stores it (ADR 0033, ADR 0035): each variant, its
 * model given as 1.x kept it (references into its own catalog named with it), converts to the content
 * its part names, with the references the suite indexed, and the template's content converts to its
 * row's content hash.
 */
object TemplateStorageConversion {

    fun verify(jdbi: Jdbi, objectMapper: ObjectMapper, templates: TemplateStore, catalog: CatalogId) {
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
                    val qualified = objectMapper.readTree(objectMapper.writeValueAsString(TemplateModels.read(model, catalog.key.value, objectMapper)))
                    val part = Envelopes.variant(variant.title, ReferenceWalker.releasedModel(qualified, catalog.key.value), metadata = null, description = variant.description)
                    softly.assertThat(CanonicalJson.contentHash(CanonicalJson.canonical(part))).`as`("variant $key/${variant.key}").isEqualTo(variant.contentHash)
                    val refs = handle.createQuery(
                        "SELECT COALESCE(alias, '') || '/' || resource_type || '/' || resource_key FROM resource_references WHERE tenant_key = :t AND content_hash = :h",
                    ).bind("t", catalog.tenantKey).bind("h", variant.contentHash).mapTo(String::class.java).list().sorted()
                    softly.assertThat(ReferenceWalker.references(part).map { "${it.alias.orEmpty()}/${it.type}/${it.key}" }.sorted())
                        .`as`("references of $key/${variant.key}").isEqualTo(refs)
                    Envelopes.VariantEntry(variant.key, objectMapper.valueToTree(variant.attributes), variant.isDefault)
                }
                // The examples as 1.x kept them: each with an id beside its key.
                val examples = objectMapper.valueToTree<ArrayNode>(template.dataExamples).also { list ->
                    list.forEach { (it as ObjectNode).put("id", "00000000-0000-0000-0000-000000000000") }
                }
                val converted = Envelopes.template(
                    key = key,
                    name = template.name,
                    description = template.description,
                    themeKey = template.themeKey,
                    themeCatalogKey = template.themeCatalogKey,
                    pdfaEnabled = template.pdfaEnabled,
                    dataModel = template.dataModel?.let { objectMapper.readTree(objectMapper.writeValueAsString(it)) },
                    dataExamples = DataExampleKeys.wireForm(examples),
                    variants = variants,
                )
                softly.assertThat(CanonicalJson.contentHash(CanonicalJson.canonical(converted))).`as`("content of template $key").isEqualTo(loaded.contentHash)
            }
        }
        softly.assertThat(seen).`as`("templates checked").isPositive()
        softly.assertAll()
    }
}
