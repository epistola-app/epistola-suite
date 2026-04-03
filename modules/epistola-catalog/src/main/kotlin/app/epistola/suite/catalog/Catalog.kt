package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class Catalog(
    val id: CatalogKey,
    val tenantKey: TenantKey,
    val name: String,
    val description: String? = null,
    val type: CatalogType,
    val sourceUrl: String? = null,
    val sourceAuthType: AuthType = AuthType.NONE,
    val sourceAuthCredential: String? = null,
    val installedReleaseVersion: String? = null,
    val installedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

enum class CatalogType {
    LOCAL,
    IMPORTED,
}

enum class AuthType {
    NONE,
    API_KEY,
    BEARER,
}
