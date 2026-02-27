package app.epistola.suite.attributes.model

import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

data class VariantAttributeDefinition(
    val id: AttributeKey,
    val tenantKey: TenantKey,
    val displayName: String,
    @Json val allowedValues: List<String> = emptyList(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
