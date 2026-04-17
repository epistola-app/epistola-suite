package app.epistola.suite.templates.validation

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.model.RequestStatus
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

data class RecentUsageValidationIssue(
    val requestId: String,
    val createdAt: OffsetDateTime,
    val correlationKey: String?,
    val status: RequestStatus,
    val errors: List<ValidationError>,
)

data class RecentUsageCompatibilityResult(
    val compatible: Boolean,
    val available: Boolean,
    val checkedCount: Int,
    val incompatibleCount: Int,
    val issues: List<RecentUsageValidationIssue>,
    val unavailableReason: String? = null,
) {
    companion object {
        fun compatible(checkedCount: Int) = RecentUsageCompatibilityResult(
            compatible = true,
            available = true,
            checkedCount = checkedCount,
            incompatibleCount = 0,
            issues = emptyList(),
        )

        fun incompatible(
            checkedCount: Int,
            issues: List<RecentUsageValidationIssue>,
        ) = RecentUsageCompatibilityResult(
            compatible = false,
            available = true,
            checkedCount = checkedCount,
            incompatibleCount = issues.size,
            issues = issues,
        )

        fun unavailable(reason: String? = null) = RecentUsageCompatibilityResult(
            compatible = false,
            available = false,
            checkedCount = 0,
            incompatibleCount = 0,
            issues = emptyList(),
            unavailableReason = reason,
        )
    }
}

@Component
@EnableConfigurationProperties(TemplateSchemaCompatibilityProperties::class)
class TemplateRecentUsageCompatibilityService(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
    private val properties: TemplateSchemaCompatibilityProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun analyze(
        tenantKey: TenantKey,
        templateKey: TemplateKey,
        schema: ObjectNode,
    ): RecentUsageCompatibilityResult = try {
        analyzeInternal(tenantKey, templateKey, schema)
    } catch (e: Exception) {
        logger.warn(
            "Recent usage compatibility check unavailable for tenant={} template={}",
            tenantKey,
            templateKey,
            e,
        )
        RecentUsageCompatibilityResult.unavailable(
            reason = "Recent usage compatibility check is temporarily unavailable.",
        )
    }

    private fun analyzeInternal(
        tenantKey: TenantKey,
        templateKey: TemplateKey,
        schema: ObjectNode,
    ): RecentUsageCompatibilityResult {
        val samples = fetchRecentSamples(tenantKey, templateKey)
        if (samples.isEmpty()) {
            return RecentUsageCompatibilityResult.compatible(checkedCount = 0)
        }

        val issues = samples.mapNotNull { sample ->
            val errors = jsonSchemaValidator.validate(schema, sample.data)
            if (errors.isEmpty()) {
                null
            } else {
                RecentUsageValidationIssue(
                    requestId = sample.requestId,
                    createdAt = sample.createdAt,
                    correlationKey = sample.correlationKey,
                    status = sample.status,
                    errors = errors,
                )
            }
        }

        if (issues.isEmpty()) {
            return RecentUsageCompatibilityResult.compatible(checkedCount = samples.size)
        }

        return RecentUsageCompatibilityResult.incompatible(
            checkedCount = samples.size,
            issues = issues,
        )
    }

    private fun fetchRecentSamples(
        tenantKey: TenantKey,
        templateKey: TemplateKey,
    ): List<RecentUsageSample> = jdbi.withHandle<List<RecentUsageSample>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, data, created_at, correlation_key, status
            FROM document_generation_requests
            WHERE tenant_key = :tenantKey
              AND template_key = :templateKey
              AND created_at >= now() - make_interval(days => :windowDays)
              AND status IN (<statuses>)
            ORDER BY created_at DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .bind("tenantKey", tenantKey)
            .bind("templateKey", templateKey)
            .bind("windowDays", properties.recentUsageWindowDays)
            .bind("limit", properties.recentUsageSampleLimit)
            .bindList("statuses", properties.statuses.map { it.name })
            .map { rs, _ ->
                val requestId = rs.getObject("id").toString()
                try {
                    RecentUsageSample(
                        requestId = requestId,
                        data = objectMapper.readValue(rs.getString("data"), ObjectNode::class.java),
                        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                        correlationKey = rs.getString("correlation_key"),
                        status = RequestStatus.valueOf(rs.getString("status")),
                    )
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Failed to parse recent usage sample requestId=$requestId",
                        e,
                    )
                }
            }
            .list()
    }

    private data class RecentUsageSample(
        val requestId: String,
        val data: ObjectNode,
        val createdAt: OffsetDateTime,
        val correlationKey: String?,
        val status: RequestStatus,
    )
}
