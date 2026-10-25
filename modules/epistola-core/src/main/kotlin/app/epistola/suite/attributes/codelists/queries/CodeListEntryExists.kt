// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.codeListAt
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.versionAt
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
) : Query<Boolean>,
    SystemInternal {
    val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

@Component
class CodeListEntryExistsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CodeListEntryExists, Boolean> {
    override fun handle(query: CodeListEntryExists): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        // Containment on the content's entries, in SQL (ADR 0033).
        handle.createQuery(
            """
            SELECT 1 FROM current_code_lists t
            WHERE ${codeListAt()}
              AND t.entries @> jsonb_build_array(jsonb_build_object('code', CAST(:code AS TEXT)))
            """,
        )
            .bind("t", query.tenantKey)
            .bindCatalog("c", query.catalog)
            .bind("key", query.codeListSlug.value)
            .bind("code", query.code)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}
