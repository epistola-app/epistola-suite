// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import db.migration.core.V20261025134500__core_images_to_storage
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi

/**
 * Proves, for every image of [catalog]'s next version, that the migration that moved images to
 * JSON storage (`V20261025134500`) converts an image's 1.x row exactly as the suite now stores it
 * (ADR 0033, ADR 0035): the converted content has the content hash the suite stored it under.
 */
object ImageStorageConversion {

    fun verify(jdbi: Jdbi, catalog: CatalogId) {
        val migration = V20261025134500__core_images_to_storage.Companion
        val softly = SoftAssertions()
        var seen = 0
        jdbi.useHandle<Exception> { handle ->
            handle.createQuery(
                """
                SELECT a.id, a.name, a.media_type, a.width, a.height, a.content_hash AS bytes, r.content_hash FROM current_images a
                JOIN release_resources r ON r.tenant_key = a.tenant_key AND r.version_id = a.version_id AND r.resource_type = 'image' AND r.resource_key = a.id
                WHERE a.tenant_key = :t AND a.catalog_id = ${catalogAt("t", "c")}
                """,
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .map { rs, _ ->
                    seen++
                    val converted = migration.payload(
                        key = rs.getString("id"),
                        name = rs.getString("name"),
                        mediaType = rs.getString("media_type"),
                        width = rs.getObject("width") as Int?,
                        height = rs.getObject("height") as Int?,
                        contentHash = rs.getString("bytes"),
                    )
                    softly.assertThat(migration.contentHash(migration.canonical(converted))).`as`("content hash of image ${rs.getString("id")}").isEqualTo(rs.getString("content_hash").trim())
                }.list()
        }
        softly.assertThat(seen).`as`("images checked").isPositive()
        softly.assertAll()
    }
}
