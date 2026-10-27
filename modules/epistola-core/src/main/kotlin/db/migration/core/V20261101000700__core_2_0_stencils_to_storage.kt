// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import db.migration.core.frozen.Envelopes
import db.migration.core.frozen.FrozenToolkit
import db.migration.core.frozen.OneXContent
import db.migration.core.frozen.ReferenceWalker
import db.migration.core.frozen.StorageWrites
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp

/**
 * The 2.0 upgrade, step 7 of 16 (docs/migrations.md, "The 2.0 chain"): a 1.x catalog's stencils become
 * its next draft's content in JSON storage (ADR 0033, step 4).
 *
 * A stencil has one working copy, versioned with its catalog (contract ADR 0002, ADR 0006): what its
 * author was working on, its draft, else its latest published version ([OneXContent]). In wire v8,
 * with stencil instances by reference: a placeholder a using template's fill reads the parameters of
 * passes them, and a stencil whose own content reads the template's data says so
 * ([db.migration.core.frozen.TemplateContentV8]). Its document is stored in the released form a
 * release keeps ([ReferenceWalker.releasedForm]). A stencil without a draft was what it last
 * published, so it is ready; one with a draft is not.
 *
 * Self-contained (ADR 0035): JDBC and the frozen toolkit.
 */
@Suppress("ClassName")
class V20261101000700__core_2_0_stencils_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    /** Pinned to this source and the toolkit (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = FrozenToolkit.pinned(CHECKSUM)

    private class Stencil(
        val tenant: String,
        val catalogId: String,
        val catalogKey: String,
        val versionId: String,
        val resourceId: String,
        val key: String,
        val name: String,
        val description: String?,
        val tags: String?,
        val createdAt: Timestamp,
        val updatedAt: Timestamp,
        val createdBy: String?,
        val updatedBy: String?,
    )

    override fun migrate(context: Context) {
        val store = StorageWrites(context.connection)
        val content = OneXContent.load(context.connection)
        val stencils = store.query(
            """
            SELECT s.tenant_key, c.catalog_id::text, c.catalog_key::text, c.next_version_id::text, s.resource_id::text, s.id, s.name, s.description,
                   s.tags::text, s.created_at, s.updated_at, s.created_by::text, s.updated_by::text
            FROM stencils s
            JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.catalog_key = s.catalog_key
            ORDER BY s.tenant_key, s.catalog_key, s.id
            """,
        ) { rs ->
            Stencil(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getTimestamp(10), rs.getTimestamp(11), rs.getString(12), rs.getString(13),
            )
        }
        for (stencil in stencils) {
            val converted = content.stencil(stencil.tenant, stencil.resourceId)
            val payload = Envelopes.stencil(
                key = stencil.key,
                catalogKey = stencil.catalogKey,
                name = stencil.name,
                description = stencil.description,
                tags = stencil.tags?.let(mapper::readTree),
                content = converted.content,
                parameterSchema = converted.parameterSchema,
                readsTemplateData = converted.readsTemplateData,
            )
            val hash = store.store(stencil.tenant, "stencil", payload, ReferenceWalker.references(payload))
            store.row(
                StorageWrites.Row(
                    stencil.tenant, stencil.catalogId, stencil.versionId, "stencil", stencil.key, stencil.resourceId, hash, stencil.name, stencil.description,
                    createdAt = stencil.createdAt, updatedAt = stencil.updatedAt, createdBy = stencil.createdBy, updatedBy = stencil.updatedBy,
                ),
            )
            if (!content.hasStencilDraft(stencil.tenant, stencil.resourceId)) store.ready(stencil.tenant, stencil.versionId, stencil.resourceId, "", hash)
        }
        log.info("2.0 upgrade: {} stencil(s) moved to JSON storage", stencils.size)
    }

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = -1425800639
    }
}
