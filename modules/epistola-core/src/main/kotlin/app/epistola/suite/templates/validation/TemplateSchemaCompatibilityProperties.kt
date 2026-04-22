package app.epistola.suite.templates.validation

import app.epistola.suite.documents.model.RequestStatus
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "epistola.templates.schema-compatibility")
data class TemplateSchemaCompatibilityProperties(
    val recentUsageWindowDays: Int = 7,
    val recentUsageSampleLimit: Int = 100,
    val statuses: Set<RequestStatus> = setOf(
        RequestStatus.PENDING,
        RequestStatus.IN_PROGRESS,
        RequestStatus.COMPLETED,
        RequestStatus.FAILED,
    ),
) {
    init {
        require(recentUsageWindowDays > 0) { "recentUsageWindowDays must be > 0" }
        require(recentUsageSampleLimit > 0) { "recentUsageSampleLimit must be > 0" }
        require(statuses.isNotEmpty()) { "statuses must not be empty" }
    }
}
