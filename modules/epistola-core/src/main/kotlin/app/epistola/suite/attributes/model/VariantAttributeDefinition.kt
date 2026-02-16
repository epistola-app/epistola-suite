package app.epistola.suite.attributes.model

import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.TenantId
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

data class VariantAttributeDefinition(
    val id: AttributeId,
    val tenantId: TenantId,
    val displayName: String,
    @Json val allowedValues: List<String> = emptyList(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
