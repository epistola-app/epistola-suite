// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.themes.Theme
import app.epistola.suite.themes.ThemeContent
import db.migration.core.frozen.CanonicalJson
import db.migration.core.frozen.Envelopes
import db.migration.core.frozen.ReferenceWalker
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Proves, for every theme of [catalog]'s next version, that the 2.0 upgrade's conversion of themes
 * to JSON storage (`V20261101000600`, [Envelopes.theme]) converts a theme's 1.x columns exactly as the suite now stores it, both
 * ways (ADR 0033, ADR 0035):
 *
 * - **columns to storage**: the columns the suite wrote for the theme before (each JSON field as
 *   JSONB, the spacing unit as `NUMERIC(6, 2)`), converted by the migration, have the content hash the
 *   suite stored it under, and the references it indexed;
 * - **storage to suite**: the migration's content, read by the one mapper ([ThemeContent]), is the
 *   theme the suite reads.
 *
 * Runs on a theme the suite stored itself, so the columns are rebuilt from it the way the old
 * commands wrote them.
 */
object ThemeStorageConversion {

    fun verify(jdbi: Jdbi, objectMapper: ObjectMapper, catalog: CatalogId, themes: List<Theme>) {
        val softly = SoftAssertions()
        jdbi.useHandle<Exception> { handle ->
            fun jsonb(value: Any?): String? = value?.let {
                handle.createQuery("SELECT CAST(CAST(:v AS JSONB) AS TEXT)").bind("v", objectMapper.writeValueAsString(it)).mapTo(String::class.java).one()
            }
            for (theme in themes) {
                // A 2.0 setting the 1.x columns never held (its block spacing, contract #59): the
                // conversion is held to the rest, and the content it stores, which has it, is not compared.
                val from1x = theme.defaultBlockStyles == null
                val stored = handle.createQuery(
                    """
                    SELECT r.content_hash FROM release_resources r
                    JOIN catalog_current_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id
                    WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.resource_type = 'theme' AND r.resource_key = :k
                    """,
                ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("k", theme.id.value).mapTo(String::class.java).one()
                val storedReferences = handle.createQuery("SELECT alias, resource_type, resource_key FROM resource_references WHERE tenant_key = :t AND content_hash = :h")
                    .bind("t", catalog.tenantKey).bind("h", stored)
                    .map { rs, _ -> ReferenceWalker.Reference(rs.getString(1), rs.getString(2), rs.getString(3)) }
                    .set()

                val converted = Envelopes.theme(
                    key = theme.id.value,
                    name = theme.name,
                    description = theme.description,
                    documentStyles = jsonb(theme.documentStyles)?.let(objectMapper::readTree),
                    pageSettings = jsonb(theme.pageSettings)?.let(objectMapper::readTree),
                    blockStylePresets = jsonb(theme.blockStylePresets)?.let(objectMapper::readTree),
                    spacingUnit = theme.spacingUnit?.let { BigDecimal(it.toString()).setScale(2, RoundingMode.HALF_UP) },
                )
                if (from1x) {
                    softly.assertThat(CanonicalJson.contentHash(CanonicalJson.canonical(converted))).`as`("content hash of theme ${theme.id.value}").isEqualTo(stored)
                    softly.assertThat(ReferenceWalker.references(converted)).`as`("references of theme ${theme.id.value}").isEqualTo(storedReferences)
                }

                val read = ThemeContent.theme(
                    objectMapper,
                    app.epistola.suite.catalog.revisions.StoredContent.resourceOf(objectMapper.readTree(objectMapper.writeValueAsString(converted))),
                    theme.tenantKey,
                    theme.catalogKey,
                    theme.namespace,
                    theme.createdAt,
                    theme.updatedAt,
                    theme.createdBy,
                    theme.updatedBy,
                    theme.revision,
                )
                softly.assertThat(read).`as`("theme ${theme.id.value} read from what the migration stores").isEqualTo(theme.copy(defaultBlockStyles = null))
            }
        }
        softly.assertAll()
    }
}
