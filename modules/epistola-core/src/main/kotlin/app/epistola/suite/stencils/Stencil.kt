package app.epistola.suite.stencils

import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.stencils.model.StencilVersionSummary
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * Stencil entity — a reusable template component.
 */
data class Stencil(
    val id: StencilKey,
    val tenantKey: TenantKey,
    val name: String,
    val description: String? = null,
    @Json val tags: List<String> = emptyList(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Stencil with version summaries for API responses.
 */
data class StencilWithVersions(
    val id: StencilKey,
    val tenantKey: TenantKey,
    val name: String,
    val description: String? = null,
    @Json val tags: List<String> = emptyList(),
    val versions: List<StencilVersionSummary>,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
