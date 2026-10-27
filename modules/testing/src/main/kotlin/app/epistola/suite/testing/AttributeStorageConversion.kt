// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.attributes.AttributeStore
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import db.migration.core.frozen.CanonicalJson
import db.migration.core.frozen.Envelopes
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper

/**
 * Proves, for every attribute of [catalog]'s next version, that the 2.0 upgrade's conversion of
 * attributes to JSON storage (`V20261101000600`, [Envelopes.attribute]) converts an attribute's 1.x columns exactly as the suite now
 * stores it, both ways (ADR 0033, ADR 0035): the converted content has the content hash the suite
 * stored it under, and the one reader ([AttributeStore.fieldsOf]) reads from it what it reads from
 * the stored content.
 */
object AttributeStorageConversion {

    fun verify(jdbi: Jdbi, objectMapper: ObjectMapper, attributes: AttributeStore, catalog: CatalogId) {
        val softly = SoftAssertions()
        var seen = 0
        jdbi.useHandle<Exception> { handle ->
            val stored = handle.createQuery(
                """
                SELECT t.id, r.content_hash, t.payload::text AS payload FROM current_attributes t
                JOIN release_resources r ON r.tenant_key = t.tenant_key AND r.version_id = t.version_id AND r.resource_type = 'attribute' AND r.resource_key = t.id
                WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")}
                """,
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .map { rs, _ -> Triple(rs.getString("id"), rs.getString("content_hash"), rs.getString("payload")) }
                .list()
            for ((key, hash, payload) in stored) {
                seen++
                val fields = attributes.fieldsOf(objectMapper.readTree(payload))
                // The columns as the suite kept them: the allowed values as JSONB, the binding resolved to an address.
                val converted = Envelopes.attribute(
                    key = key,
                    name = fields.name,
                    allowedValues = objectMapper.readTree(objectMapper.writeValueAsString(fields.allowedValues)),
                    bindingAlias = fields.binding?.catalogKey,
                    bindingKey = fields.binding?.key,
                )
                softly.assertThat(CanonicalJson.contentHash(CanonicalJson.canonical(converted))).`as`("content hash of attribute $key").isEqualTo(hash)
                softly.assertThat(attributes.fieldsOf(objectMapper.readTree(objectMapper.writeValueAsString(converted)))).`as`("attribute $key read from what the migration stores").isEqualTo(fields)
            }
        }
        softly.assertThat(seen).`as`("attributes checked").isPositive()
        softly.assertAll()
    }
}
