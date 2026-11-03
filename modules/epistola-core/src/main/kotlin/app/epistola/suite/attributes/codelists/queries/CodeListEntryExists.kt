// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.BoundCodeListVersion
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Single-row existence check for a code in a code list — used by variant
 * attribute validation, on the hot path for every variant write. Hidden
 * entries match too: existing variants whose codes have since been hidden
 * remain valid.
 *
 * Marked `SystemInternal` because the caller (`validateAttributes`) is
 * already inside a permission-checked command — no need to re-authorise here.
 */
data class CodeListEntryExists(
    val catalog: CatalogId,
    val codeListSlug: CodeListKey,
    val code: String,
    /**
     * The catalog of the attribute bound to the list, when one is: its declarations, in the version
     * this addresses of it, say which version of the list's catalog it means (BoundCodeListVersion).
     * Null reads the list in the version this addresses of its catalog.
     */
    val boundFrom: CatalogId? = null,
    /** The catalog version the check is made for (the writer's); null for each catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : CatalogReleaseScoped,
    Query<Boolean>,
    SystemInternal {
    val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

@Component
class CodeListEntryExistsHandler(
    private val jdbi: Jdbi,
    private val boundVersion: BoundCodeListVersion,
) : QueryHandler<CodeListEntryExists, Boolean> {
    override fun handle(query: CodeListEntryExists): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val at = query.boundFrom?.let { boundVersion.of(handle, handle.versionOf(it, query), query.catalog) }
            ?: handle.versionOf(query.catalog, query)
        // Containment on the content's entries, in SQL (ADR 0033), of the version the binding means.
        handle.createQuery(
            """
            SELECT 1 FROM version_code_lists t
            WHERE t.tenant_key = :t AND t.version_id = :v AND t.id = :key
              AND t.entries @> jsonb_build_array(jsonb_build_object('code', CAST(:code AS TEXT)))
            """,
        )
            .bind("t", query.tenantKey)
            .bindVersion("v", at.versionId)
            .bind("key", query.codeListSlug.value)
            .bind("code", query.code)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}
