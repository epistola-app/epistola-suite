// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import app.epistola.catalog.protocol.CatalogKeys
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Gives every stored data example a key (wire v8), derived from its name in document order and
 * unique within its template -- with the contract's own function, so that a template stored here and
 * the same template migrated from a catalog-v7 archive get the same keys. Examples that already have
 * a key keep it.
 *
 * Kotlin rather than SQL for that reason: the derivation (Unicode decomposition, the fallback and
 * suffix rules) must be the contract's, not a second copy of it.
 */
@Suppress("ClassName")
class V20261016161423__core_data_example_keys : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val mapper = JsonMapper.builder().build()
        val connection = context.connection
        val rows = connection.prepareStatement(
            "SELECT tenant_key, template_resource_id, id, data_examples::text FROM contract_versions WHERE data_examples IS NOT NULL",
        ).use { select ->
            select.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Row(rs.getString(1), rs.getObject(2), rs.getObject(3), rs.getString(4)))
                }
            }
        }
        connection.prepareStatement(
            "UPDATE contract_versions SET data_examples = ?::jsonb WHERE tenant_key = ? AND template_resource_id = ? AND id = ?",
        ).use { update ->
            for (row in rows) {
                val examples = mapper.readTree(row.dataExamples) as? ArrayNode ?: continue
                val objects = examples.filterIsInstance<ObjectNode>()
                if (objects.all { it.has("key") }) continue
                val taken = objects.mapNotNullTo(mutableSetOf()) { it["key"]?.takeIf { key -> key.isString }?.asString() }
                for (example in objects) {
                    if (example.has("key")) continue
                    val name = example["name"]?.takeIf { it.isString }?.asString() ?: continue
                    val key = CatalogKeys.deriveDataExampleKey(name, taken)
                    taken += key
                    example.put("key", key)
                }
                update.setString(1, mapper.writeValueAsString(examples))
                update.setString(2, row.tenantKey)
                update.setObject(3, row.templateResourceId)
                update.setObject(4, row.id)
                update.addBatch()
            }
            update.executeBatch()
        }
    }

    private data class Row(val tenantKey: String, val templateResourceId: Any, val id: Any, val dataExamples: String)
}
