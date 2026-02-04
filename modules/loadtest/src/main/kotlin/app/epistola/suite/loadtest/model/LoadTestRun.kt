package app.epistola.suite.loadtest.model

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * A load test run that generates N documents concurrently to measure performance.
 *
 * The test generates documents using the same template/variant/version and test data
 * for all requests, measuring response times, throughput, and success rates.
 *
 * @property id Unique load test run identifier
 * @property tenantId Tenant that owns this load test
 * @property templateId Template to use for document generation
 * @property variantId Variant of the template to use
 * @property versionId Explicit version to use (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 * @property targetCount Number of documents to generate (1-10000)
 * @property concurrencyLevel Number of concurrent document generation requests (1-500)
 * @property testData JSON data to use for all document generation requests
 * @property status Current status of the load test
 * @property claimedBy Instance identifier (hostname-pid) that claimed this run
 * @property claimedAt When the run was claimed for processing
 * @property completedCount Number of requests that completed successfully
 * @property failedCount Number of requests that failed
 * @property metrics Aggregated performance metrics (populated after completion)
 * @property createdAt When the load test was created
 * @property startedAt When the load test execution started
 * @property completedAt When the load test execution finished
 */
data class LoadTestRun(
    val id: LoadTestRunId,
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId?,
    val environmentId: EnvironmentId?,
    val targetCount: Int,
    val concurrencyLevel: Int,
    val testData: ObjectNode,
    val status: LoadTestStatus,
    val claimedBy: String?,
    val claimedAt: OffsetDateTime?,
    val completedCount: Int,
    val failedCount: Int,
    val metrics: LoadTestMetrics?,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
) {
    init {
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
        require(targetCount in 1..10000) {
            "Target count must be between 1 and 10000, got $targetCount"
        }
        require(concurrencyLevel in 1..500) {
            "Concurrency level must be between 1 and 500, got $concurrencyLevel"
        }
    }
}
