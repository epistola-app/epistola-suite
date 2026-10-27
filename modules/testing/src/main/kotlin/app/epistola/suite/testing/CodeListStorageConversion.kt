// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import db.migration.core.frozen.CanonicalJson
import db.migration.core.frozen.Envelopes
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper

/**
 * Proves, for every code list of [catalog]'s next version, that the 2.0 upgrade's conversion of code
 * lists to JSON storage (`V20261101000600`, [Envelopes.codeList]) converts a code list's 1.x columns and entries exactly as the
 * suite now stores it, both ways (ADR 0033, ADR 0035):
 *
 * - **columns to storage**: the columns the suite kept for the list before, converted by the
 *   migration, have the content hash the suite stored it under (the credential named by the id it has);
 * - **storage to suite**: the migration's content, read by the one reader ([CodeListStore.fieldsOf]),
 *   says what the suite reads from the stored content.
 */
object CodeListStorageConversion {

    fun verify(jdbi: Jdbi, objectMapper: ObjectMapper, codeLists: CodeListStore, catalog: CatalogId) {
        val softly = SoftAssertions()
        var seen = 0
        jdbi.useHandle<Exception> { handle ->
            val stored = handle.createQuery(
                """
                SELECT t.id, r.content_hash, t.payload::text AS payload FROM current_code_lists t
                JOIN release_resources r ON r.tenant_key = t.tenant_key AND r.version_id = t.version_id AND r.resource_type = 'codeList' AND r.resource_key = t.id
                WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")}
                """,
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .map { rs, _ -> Triple(rs.getString("id"), rs.getString("content_hash"), rs.getString("payload")) }
                .list()
            for ((key, hash, payload) in stored) {
                seen++
                val fields = codeLists.fieldsOf(objectMapper.readTree(payload))
                val source = fields.source
                // The entries as the columns held them: in the order the old reads gave them.
                val converted = Envelopes.codeList(
                    key = key,
                    name = fields.name,
                    description = fields.description,
                    entries = fields.entries.sortedWith(compareBy({ it.sortOrder }, { it.code })).map { Envelopes.CodeListEntry(it.code, it.label, it.sortOrder, it.hidden) },
                    sourceType = source?.type?.name ?: "INLINE",
                    sourceUrl = source?.url,
                    authType = source?.auth?.name ?: "NONE",
                    credentialId = source?.credentialId?.toString(),
                )
                softly.assertThat(CanonicalJson.contentHash(CanonicalJson.canonical(converted))).`as`("content hash of code list $key").isEqualTo(hash)
                softly.assertThat(codeLists.fieldsOf(objectMapper.readTree(objectMapper.writeValueAsString(converted)))).`as`("code list $key read from what the migration stores").isEqualTo(fields)
            }
        }
        softly.assertThat(seen).`as`("code lists checked").isPositive()
        softly.assertAll()
    }
}
