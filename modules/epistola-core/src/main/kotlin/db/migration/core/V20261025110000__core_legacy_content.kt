// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection

/**
 * Finishes moving the 1.x shared `content_store` into the stores that replaced it (#738), so the
 * next migration can drop it: what the runtime `ContentBackfillRunner` did after startup, now in the
 * migration step (ADR 0035), for an installation that upgrades before that job ran or finished.
 *
 * 1. **Documents**: every legacy `documents/<tenant>/<id>` blob whose document exists goes into
 *    `document_content`, one owning month at a time, its partition created first.
 * 2. **Assets**: every asset without a `content_hash` gets its legacy `assets/<tenant>/<id>` bytes
 *    stored in `asset_content`, under the shared scope, or its tenant's when it is sensitive, and the
 *    hash stamped. An asset whose legacy bytes are missing is logged and left as it is: never refused.
 *
 * It runs after the fonts are converted (`V20261025105724`), which hashes the legacy bytes of a 1.x face
 * that names no hash itself, so such a face has already joined its font; what is left here is every
 * other asset and document.
 *
 * Self-contained (ADR 0035): JDBC only. The content hash is SHA-256 of the bytes, lowercase hex
 * ([sha256Hex], pinned by a golden vector in `LegacyContentMigrationTest`), the one the suite stores
 * assets under. Runs outside Flyway's single transaction, a batch per transaction, so a large
 * installation does not hold every blob in one. Every step is idempotent, so what committed before a
 * failure is kept and running it again only does the rest; but Flyway records a failed
 * non-transactional migration as failed, so it runs again only after `flyway repair` removes that
 * record.
 */
@Suppress("ClassName")
class V20261025110000__core_legacy_content : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            documents(connection)
            assets(connection)
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    private fun documents(connection: Connection) {
        val months = connection.prepareStatement(
            """
            SELECT DISTINCT to_char(date_trunc('month', d.created_at), 'YYYY-MM-DD')
            FROM content_store cs
            JOIN documents d ON cs.key = 'documents/' || d.tenant_key || '/' || d.id::text
            ORDER BY 1
            """,
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        connection.commit()
        var copied = 0
        for (month in months) {
            connection.prepareStatement("SELECT epistola_create_partition('document_content'::regclass, ?::date)").use {
                it.setString(1, month)
                it.executeQuery().close()
            }
            copied += connection.prepareStatement(
                """
                INSERT INTO document_content (key, content, content_type, size_bytes, created_at)
                SELECT cs.key, cs.content, cs.content_type, cs.size_bytes, d.created_at
                FROM content_store cs
                JOIN documents d ON cs.key = 'documents/' || d.tenant_key || '/' || d.id::text
                WHERE date_trunc('month', d.created_at) = ?::date
                ON CONFLICT (key, created_at) DO NOTHING
                """,
            ).use {
                it.setString(1, month)
                it.executeUpdate()
            }
            connection.commit()
        }
        if (copied > 0) log.info("Moved {} legacy document blob(s) into document_content across {} month(s)", copied, months.size)
    }

    private data class Asset(val id: String, val tenant: String, val mediaType: String, val sensitive: Boolean)

    private fun assets(connection: Connection) {
        var moved = 0
        var missing = 0
        var last = ""
        while (true) {
            val batch = connection.prepareStatement(
                "SELECT id, tenant_key, media_type, sensitive FROM assets WHERE content_hash IS NULL AND id > ? ORDER BY id LIMIT $BATCH",
            ).use { st ->
                st.setString(1, last)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(Asset(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))) } }
            }
            if (batch.isEmpty()) break
            for (asset in batch) {
                val bytes = connection.prepareStatement("SELECT content FROM content_store WHERE key = ?").use { st ->
                    st.setString(1, "assets/${asset.tenant}/${asset.id}")
                    st.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
                }
                if (bytes == null) {
                    missing++
                    log.warn("Asset {} of tenant {} has no legacy bytes; left without content", asset.id, asset.tenant)
                    continue
                }
                val hash = sha256Hex(bytes)
                connection.prepareStatement(
                    """
                    INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                ).use {
                    it.setString(1, if (asset.sensitive) asset.tenant else "global")
                    it.setString(2, hash)
                    it.setBytes(3, bytes)
                    it.setString(4, asset.mediaType)
                    it.setLong(5, bytes.size.toLong())
                    it.executeUpdate()
                }
                connection.prepareStatement("UPDATE assets SET content_hash = ? WHERE tenant_key = ? AND id = ? AND content_hash IS NULL").use {
                    it.setString(1, hash)
                    it.setString(2, asset.tenant)
                    it.setString(3, asset.id)
                    it.executeUpdate()
                }
                moved++
            }
            connection.commit()
            last = batch.last().id
        }
        if (moved > 0) log.info("Moved {} legacy asset blob(s) into asset_content", moved)
        if (missing > 0) log.warn("{} asset(s) have no legacy bytes and were left without content", missing)
    }

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 1805446445

        private const val BATCH = 100

        /** SHA-256 of [bytes], lowercase hex: what an asset's bytes are stored under. */
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
