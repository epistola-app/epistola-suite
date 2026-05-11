package app.epistola.suite.attributes.codelists.queries

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
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val codeListSlug: CodeListKey,
    val code: String,
) : Query<Boolean>,
    SystemInternal

@Component
class CodeListEntryExistsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CodeListEntryExists, Boolean> {
    override fun handle(query: CodeListEntryExists): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            """
            SELECT 1
            FROM code_list_entries
            WHERE tenant_key = :tenantKey
              AND catalog_key = :catalogKey
              AND code_list_slug = :slug
              AND code = :code
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .bind("slug", query.codeListSlug)
            .bind("code", query.code)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}
