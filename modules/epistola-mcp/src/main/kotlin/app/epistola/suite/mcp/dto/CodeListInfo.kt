// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import java.time.OffsetDateTime

/**
 * A `{code, label}` collection within a tenant's catalog. Attributes can bind
 * to a code list as their value constraint (e.g. `system.locale` →
 * `system.bcp-47`).
 *
 * `catalogType` indicates whether the catalog is editable here (AUTHORED) or
 * a read-only mirror (SUBSCRIBED — e.g. the bundled `system` catalog). The
 * `readOnly` flag is the explicit signal AI clients should respect.
 */
data class CodeListInfo(
    val slug: String,
    val catalog: String,
    val displayName: String,
    val description: String?,
    /** INLINE (entries baked into this row) or URL (fetched via refresh). */
    val sourceType: String,
    val sourceUrl: String?,
    val authType: String,
    val catalogType: String,
    val readOnly: Boolean,
    val lastRefreshedAt: OffsetDateTime?,
    val lastRefreshError: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(codeList: CodeList): CodeListInfo = CodeListInfo(
            slug = codeList.slug.value,
            catalog = codeList.catalogKey.value,
            displayName = codeList.displayName,
            description = codeList.description,
            sourceType = codeList.sourceType.name,
            sourceUrl = codeList.sourceUrl,
            authType = codeList.authType.name,
            // Code lists imported by the bundled catalog land with INLINE
            // source but their catalog is SUBSCRIBED — clients should
            // gate edit affordances off `readOnly`/`catalogType`, not
            // `sourceType`.
            catalogType = codeList.catalogType?.name ?: "AUTHORED",
            readOnly = codeList.catalogType?.name == "SUBSCRIBED",
            lastRefreshedAt = codeList.lastRefreshedAt,
            lastRefreshError = codeList.lastRefreshError,
            createdAt = codeList.createdAt,
            updatedAt = codeList.updatedAt,
        )
    }
}

/**
 * Single `{code, label}` entry of a code list. `hidden` entries remain valid
 * for existing variants but are filtered from default listings.
 */
data class CodeListEntryInfo(
    val code: String,
    val label: String,
    val sortOrder: Int,
    val hidden: Boolean,
) {
    companion object {
        fun from(entry: CodeListEntry): CodeListEntryInfo = CodeListEntryInfo(
            code = entry.code,
            label = entry.label,
            sortOrder = entry.sortOrder,
            hidden = entry.hidden,
        )
    }
}
