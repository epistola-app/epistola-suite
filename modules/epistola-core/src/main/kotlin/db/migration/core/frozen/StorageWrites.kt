// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

import tools.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.Timestamp

/**
 * How the 2.0 upgrade writes what it converted into JSON storage (ADR 0033), frozen: against the
 * storage tables as the chain creates them (step 3), before their triggers exist (step 16). Content is
 * stored by its content hash ([CanonicalJson]), with what it refers to ([ReferenceWalker]) and the
 * files it needs; a version's row names it.
 */
class StorageWrites(private val connection: Connection) {

    /** One row of a version, as [row] writes it. */
    class Row(
        val tenant: String,
        val catalogId: String,
        val versionId: String,
        val type: String,
        val key: String,
        val resourceId: String,
        val contentHash: String,
        val name: String,
        val description: String? = null,
        val parentId: String? = null,
        val revision: Long = 1,
        val createdAt: Timestamp? = null,
        val updatedAt: Timestamp? = null,
        val createdBy: String? = null,
        val updatedBy: String? = null,
    )

    /** Stores [payload] of [kind] for [tenant] under its content hash, with [references]. @return the hash. */
    fun store(tenant: String, kind: String, payload: JsonNode, references: Set<ReferenceWalker.Reference> = emptySet()): String {
        val canonical = CanonicalJson.canonical(payload)
        val hash = CanonicalJson.contentHash(canonical)
        update(
            "INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, ?, ?::jsonb) ON CONFLICT (tenant_key, content_hash) DO NOTHING",
            tenant,
            hash,
            kind,
            canonical,
        )
        references.forEach { reference(tenant, hash, it) }
        return hash
    }

    /** Records that content [hash] refers to [reference]. */
    fun reference(tenant: String, hash: String, reference: ReferenceWalker.Reference) {
        update(
            "INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            tenant,
            hash,
            reference.alias,
            reference.type,
            reference.key,
        )
    }

    /** Links content [hash] to the file [binaryHash], when its bytes are stored. */
    fun binary(tenant: String, hash: String, binaryHash: String) {
        update(
            "INSERT INTO resource_binaries (tenant_key, content_hash, binary_hash) SELECT ?, ?, content_hash FROM asset_content WHERE content_hash = ? ON CONFLICT DO NOTHING",
            tenant,
            hash,
            binaryHash,
        )
    }

    /** Writes [row]; its timestamps default to now. */
    fun row(row: Row) {
        connection.prepareStatement(
            """
            INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id, parent_id,
                content_hash, name, description, revision, created_at, updated_at, created_by, updated_by)
            VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, COALESCE(?, NOW()), COALESCE(?, NOW()), ?::uuid, ?::uuid)
            """,
        ).use {
            it.setString(1, row.tenant)
            it.setString(2, row.catalogId)
            it.setString(3, row.versionId)
            it.setString(4, row.type)
            it.setString(5, row.key)
            it.setString(6, row.resourceId)
            it.setString(7, row.parentId)
            it.setString(8, row.contentHash)
            it.setString(9, row.name)
            it.setString(10, row.description)
            it.setLong(11, row.revision)
            it.setTimestamp(12, row.createdAt)
            it.setTimestamp(13, row.updatedAt)
            it.setString(14, row.createdBy)
            it.setString(15, row.updatedBy)
            it.executeUpdate()
        }
    }

    /** Marks part [part] of resource [resourceId] ready in version [versionId], at [digest]. */
    fun ready(tenant: String, versionId: String, resourceId: String, part: String, digest: String) {
        update(
            "INSERT INTO resource_ready_marks (tenant_key, version_id, resource_id, part, digest) VALUES (?, ?::uuid, ?::uuid, ?, ?)",
            tenant,
            versionId,
            resourceId,
            part,
            digest,
        )
    }

    /** Runs [sql] with [values] bound in order. @return the rows it changed. */
    fun update(sql: String, vararg values: Any?): Int = connection.prepareStatement(sql).use { st ->
        values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeUpdate()
    }

    /** The rows [sql] returns with [values] bound, each mapped by [map]. */
    fun <T> query(sql: String, vararg values: Any?, map: (java.sql.ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { st ->
        values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = -1890658724
    }
}
