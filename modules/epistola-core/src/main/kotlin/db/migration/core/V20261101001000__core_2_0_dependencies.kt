// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import db.migration.core.frozen.FrozenToolkit
import db.migration.core.frozen.StorageWrites
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/**
 * The 2.0 upgrade, step 10 of 16 (docs/migrations.md, "The 2.0 chain"): every draft declares the
 * catalogs its content names. 1.x had no declarations; in 2.0 content names another catalog by an
 * alias, and an alias means only what a declaration says (contract ADR 0003, ADR 0034). Each is
 * declared by name, under the catalog's key, and pins nothing: releasing pins each to its catalog's
 * highest release.
 *
 * 1. Every draft declares the catalog of a code list an attribute of its own binds in another catalog.
 * 2. A draft of a catalog its tenant authored declares, in order of alias, every other catalog of the
 *    tenant its content references (the aliases in `resource_references`, by the frozen reference
 *    walker of steps 6 to 9) and a variant attribute's qualifier names (`system.locale` names
 *    `system`). An alias no catalog of the tenant has, or two share, is left: releasing refuses what it
 *    cannot resolve.
 *
 * None is an edit of the draft: a migrated draft starts at revision 1.
 *
 * Self-contained (ADR 0035): JDBC and the frozen toolkit.
 */
@Suppress("ClassName")
class V20261101001000__core_2_0_dependencies : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    /** Pinned to this source and the toolkit (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = FrozenToolkit.pinned(CHECKSUM)

    private class Draft(val tenant: String, val catalogId: String, val catalogKey: String, val versionId: String, val authored: Boolean)

    override fun migrate(context: Context) {
        val store = StorageWrites(context.connection)
        // The next draft before the named one of the same catalog.
        val drafts = store.query(
            """
            SELECT v.tenant_key, v.catalog_id::text, c.catalog_key::text, v.version_id::text, c.type = 'AUTHORED'
            FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id
            ORDER BY v.tenant_key, c.catalog_key, v.name NULLS FIRST
            """,
        ) { Draft(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getBoolean(5)) }
        var declared = 0
        for (draft in drafts) {
            declared += bindings(store, draft)
            if (draft.authored) declared += used(store, draft)
        }
        if (declared > 0) log.info("2.0 upgrade: {} catalog dependency declaration(s) added", declared)
    }

    /** Declares the catalog of each code list an attribute of [draft] binds in another catalog, in order of attribute. */
    private fun bindings(store: StorageWrites, draft: Draft): Int {
        val bound = store.query(
            """
            SELECT bc.registry, bc.namespace, bc.catalog_key::text
            FROM release_resources a
            JOIN resource_contents p ON p.tenant_key = a.tenant_key AND p.content_hash = a.content_hash
            JOIN catalogs bc ON bc.tenant_key = a.tenant_key AND bc.catalog_key = p.payload -> 'resource' -> 'codeListBinding' ->> 'catalogKey'
            WHERE a.tenant_key = ? AND a.version_id = ?::uuid AND a.resource_type = 'attribute'
            ORDER BY a.resource_key
            """,
            draft.tenant,
            draft.versionId,
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        var added = 0
        for ((registry, namespace, key) in bound) {
            val inserted = store.update(
                """
                INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias, position)
                VALUES (?, ?::uuid, ?::uuid, ?, ?, ?, ?,
                        (SELECT COALESCE(max(position) + 1, 0) FROM catalog_version_dependencies WHERE tenant_key = ? AND version_id = ?::uuid))
                ON CONFLICT DO NOTHING
                """,
                draft.tenant, draft.catalogId, draft.versionId, registry, namespace, key, key, draft.tenant, draft.versionId,
            )
            added += inserted
        }
        return added
    }

    /** Declares every other catalog of the tenant [draft]'s content names, in order of alias. */
    private fun used(store: StorageWrites, draft: Draft): Int {
        val aliases = sortedSetOf<String>()
        aliases += store.query(
            """
            SELECT DISTINCT rr.alias FROM release_resources r
            JOIN resource_references rr ON rr.tenant_key = r.tenant_key AND rr.content_hash = r.content_hash
            WHERE r.tenant_key = ? AND r.version_id = ?::uuid AND rr.alias IS NOT NULL
            """,
            draft.tenant,
            draft.versionId,
        ) { it.getString(1) }
        store.query(
            """
            SELECT c.payload::text FROM release_resources r
            JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
            WHERE r.tenant_key = ? AND r.version_id = ?::uuid AND r.resource_type = 'template'
            """,
            draft.tenant,
            draft.versionId,
        ) { it.getString(1) }.forEach { payload ->
            mapper.readTree(payload).path("resource").path("variants").forEach { variant ->
                variant.path("attributes").propertyNames().forEach { key -> key.substringBefore('.', "").takeIf { it.isNotEmpty() }?.let { aliases += it } }
            }
        }
        aliases.remove(draft.catalogKey)
        var added = 0
        for (alias in aliases) {
            val target = store.query("SELECT registry, namespace FROM catalogs WHERE tenant_key = ? AND catalog_key = ?", draft.tenant, alias) { it.getString(1) to it.getString(2) }
                .singleOrNull() ?: continue
            val inserted = store.update(
                """
                INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias, position)
                SELECT ?, ?::uuid, ?::uuid, ?, ?, ?, ?,
                       (SELECT COALESCE(max(position) + 1, 0) FROM catalog_version_dependencies WHERE tenant_key = ? AND version_id = ?::uuid)
                WHERE NOT EXISTS (SELECT 1 FROM catalog_version_dependencies WHERE tenant_key = ? AND version_id = ?::uuid AND alias = ?)
                """,
                draft.tenant, draft.catalogId, draft.versionId, target.first, target.second, alias, alias,
                draft.tenant, draft.versionId, draft.tenant, draft.versionId, alias,
            )
            if (inserted > 0) added++
        }
        return added
    }

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 842510304
    }
}
