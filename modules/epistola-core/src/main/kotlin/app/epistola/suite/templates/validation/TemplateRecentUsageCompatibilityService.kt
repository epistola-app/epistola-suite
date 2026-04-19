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
import java.time.Instant
import java.time.OffsetDateTime

data class RecentUsageWindow(
    val maxDays: Int,
    val sampleLimit: Int,
    val checkedFrom: Instant,
    val checkedTo: Instant,
)

data class RecentUsageSummary(
    val checkedCount: Int,
    val compatibleCount: Int,
    val incompatibleCount: Int,
)

data class RecentUsageSampleResult(
    val requestId: String,
    val sampleRank: Int,
    val createdAt: OffsetDateTime,
    val correlationKey: String?,
    val status: RequestStatus,
    val compatible: Boolean,
    val errorCount: Int,
)

data class RecentUsageValidationIssue(
    val requestId: String,
    val sampleRank: Int,
    val createdAt: OffsetDateTime,
    val correlationKey: String?,
    val status: RequestStatus,
    val errors: List<ValidationError>,
)

data class RecentUsageCompatibilityResult(
    val compatible: Boolean,
    val available: Boolean,
    val window: RecentUsageWindow,
    val summary: RecentUsageSummary,
    val samples: List<RecentUsageSampleResult>,
    val issues: List<RecentUsageValidationIssue>,
    val unavailableReason: String? = null,
) {
    companion object {
        fun compatible(
            window: RecentUsageWindow,
            samples: List<RecentUsageSampleResult>,
        ) = RecentUsageCompatibilityResult(
            compatible = true,
            available = true,
            window = window,
            summary = RecentUsageSummary(
                checkedCount = samples.size,
                compatibleCount = samples.size,
                incompatibleCount = 0,
            ),
            samples = samples,
            issues = emptyList(),
        )

        fun incompatible(
            window: RecentUsageWindow,
            samples: List<RecentUsageSampleResult>,
            issues: List<RecentUsageValidationIssue>,
        ) = RecentUsageCompatibilityResult(
            compatible = false,
            available = true,
            window = window,
            summary = RecentUsageSummary(
                checkedCount = samples.size,
                compatibleCount = samples.count { it.compatible },
                incompatibleCount = samples.count { !it.compatible },
            ),
            samples = samples,
            issues = issues,
        )

        fun unavailable(
            window: RecentUsageWindow,
            reason: String? = null,
        ) = RecentUsageCompatibilityResult(
            compatible = false,
            available = false,
            window = window,
            summary = RecentUsageSummary(
                checkedCount = 0,
                compatibleCount = 0,
                incompatibleCount = 0,
            ),
            samples = emptyList(),
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
    ): RecentUsageCompatibilityResult {
        val window = currentWindow()

        return try {
            analyzeInternal(tenantKey, templateKey, schema, window)
        } catch (e: Exception) {
            logger.warn(
                "Recent usage compatibility check unavailable for tenant={} template={}",
                tenantKey,
                templateKey,
                e,
            )
            RecentUsageCompatibilityResult.unavailable(
                window = window,
                reason = "Recent usage compatibility check is temporarily unavailable.",
            )
        }
    }

    private fun analyzeInternal(
        tenantKey: TenantKey,
        templateKey: TemplateKey,
        schema: ObjectNode,
        window: RecentUsageWindow,
    ): RecentUsageCompatibilityResult {
        val samples = fetchRecentSamples(tenantKey, templateKey)
        if (samples.isEmpty()) {
            return RecentUsageCompatibilityResult.compatible(window = window, samples = emptyList())
        }

        val sampleResults = mutableListOf<RecentUsageSampleResult>()
        val issues = samples.mapIndexedNotNull { index, sample ->
            val errors = jsonSchemaValidator.validate(schema, sample.data)
            val sampleRank = index + 1
            sampleResults += RecentUsageSampleResult(
                requestId = sample.requestId,
                sampleRank = sampleRank,
                createdAt = sample.createdAt,
                correlationKey = sample.correlationKey,
                status = sample.status,
                compatible = errors.isEmpty(),
                errorCount = errors.size,
            )

            if (errors.isEmpty()) {
                null
            } else {
                RecentUsageValidationIssue(
                    requestId = sample.requestId,
                    sampleRank = sampleRank,
                    createdAt = sample.createdAt,
                    correlationKey = sample.correlationKey,
                    status = sample.status,
                    errors = errors,
                )
            }
        }

        if (issues.isEmpty()) {
            return RecentUsageCompatibilityResult.compatible(window = window, samples = sampleResults)
        }

        return RecentUsageCompatibilityResult.incompatible(
            window = window,
            samples = sampleResults,
            issues = issues,
        )
    }

    private fun currentWindow(now: Instant = Instant.now()): RecentUsageWindow = RecentUsageWindow(
        maxDays = properties.recentUsageWindowDays,
        sampleLimit = properties.recentUsageSampleLimit,
        checkedFrom = now.minusSeconds(properties.recentUsageWindowDays.toLong() * 24 * 60 * 60),
        checkedTo = now,
    )

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
