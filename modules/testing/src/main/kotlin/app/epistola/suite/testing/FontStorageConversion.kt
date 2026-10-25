// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.fonts.FontStore
import db.migration.core.V20261025105724__core_fonts_to_storage
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi

/**
 * Proves, for every font of [catalog]'s next version, that the migration that moved fonts to JSON
 * storage (`V20261025105724`) converts a font's 1.x rows exactly as the suite now stores it (ADR
 * 0033, ADR 0035): the converted content has the content hash the suite stored it under, and its
 * faces are the ones the suite reads.
 */
object FontStorageConversion {

    fun verify(jdbi: Jdbi, fonts: FontStore, catalog: CatalogId) {
        val migration = V20261025105724__core_fonts_to_storage.Companion
        val softly = SoftAssertions()
        var seen = 0
        jdbi.useHandle<Exception> { handle ->
            val stored = handle.createQuery(
                """
                SELECT f.slug, f.name, f.kind, f.variants::text AS variants, r.content_hash FROM current_fonts f
                JOIN release_resources r ON r.tenant_key = f.tenant_key AND r.version_id = f.version_id AND r.resource_type = 'font' AND r.resource_key = f.slug
                WHERE f.tenant_key = :t AND f.catalog_id = ${catalogAt("t", "c")}
                """,
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .map { rs, _ -> listOf(rs.getString("slug"), rs.getString("name"), rs.getString("kind"), rs.getString("variants"), rs.getString("content_hash").trim()) }
                .list()
            for ((slug, name, kind, variants, hash) in stored) {
                seen++
                val faces = fonts.facesOf(variants)
                // The rows as 1.x kept them: the faces in no particular order.
                val converted = migration.payload(slug, name, kind, faces.reversed().map { V20261025105724__core_fonts_to_storage.Face(it.weight, it.italic, it.mediaType, it.contentHash) })
                softly.assertThat(migration.contentHash(migration.canonical(converted))).`as`("content hash of font $slug").isEqualTo(hash)
                softly.assertThat(fonts.facesOf(converted.path("resource").path("variants").toString())).`as`("faces of font $slug").isEqualTo(faces)
            }
        }
        softly.assertThat(seen).`as`("fonts checked").isPositive()
        softly.assertAll()
    }
}
