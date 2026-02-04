package app.epistola.suite.loadtest

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.loadtest.commands.CancelLoadTest
import app.epistola.suite.loadtest.commands.StartLoadTest
import app.epistola.suite.loadtest.model.LoadTestRunId
import app.epistola.suite.loadtest.queries.GetLoadTestRequests
import app.epistola.suite.loadtest.queries.GetLoadTestRun
import app.epistola.suite.loadtest.queries.ListLoadTestRuns
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class LoadTestHandler(
    private val objectMapper: ObjectMapper,
) {
    /**
     * List all load test runs for a tenant.
     */
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val runs = ListLoadTestRuns(tenantId = tenantId, limit = 50).query()

        return ServerResponse.ok().render(
            "loadtest/list",
            mapOf(
                "tenantId" to tenantId,
                "runs" to runs,
            ),
        )
    }

    /**
     * Show form to configure a new load test.
     */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))

        // Fetch templates for dropdown
        val templates = ListDocumentTemplates(tenantId = tenantId).query()

        return ServerResponse.ok().render(
            "loadtest/new",
            mapOf(
                "tenantId" to tenantId,
                "templates" to templates,
            ),
        )
    }

    /**
     * Start a new load test.
     */
    fun start(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val params = request.params()

        val templateId = TemplateId.of(params.getFirst("templateId") ?: "")
        val variantId = VariantId.of(params.getFirst("variantId") ?: "")

        // Parse version or environment
        val versionIdStr = params.getFirst("versionId")
        val environmentIdStr = params.getFirst("environmentId")

        val versionId = if (!versionIdStr.isNullOrBlank()) VersionId.of(versionIdStr.toInt()) else null
        val environmentId = if (!environmentIdStr.isNullOrBlank()) EnvironmentId.of(environmentIdStr) else null

        val targetCount = params.getFirst("targetCount")?.toIntOrNull() ?: 100
        val concurrencyLevel = params.getFirst("concurrencyLevel")?.toIntOrNull() ?: 10

        // Parse test data JSON
        val testDataStr = params.getFirst("testData") ?: "{}"
        val testData = objectMapper.readTree(testDataStr) as tools.jackson.databind.node.ObjectNode

        try {
            val run = StartLoadTest(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = versionId,
                environmentId = environmentId,
                targetCount = targetCount,
                concurrencyLevel = concurrencyLevel,
                testData = testData,
            ).execute()

            // Redirect to detail page (both HTMX and non-HTMX)
            return redirect("/tenants/$tenantId/load-tests/${run.id}")
        } catch (e: Exception) {
            // Show error and reload form
            return ServerResponse.badRequest().render(
                "loadtest/new",
                mapOf(
                    "tenantId" to tenantId,
                    "error" to (e.message ?: "Failed to start load test"),
                ),
            )
        }
    }

    /**
     * Show load test run detail with metrics.
     */
    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val runId = LoadTestRunId.of(UUID.fromString(request.pathVariable("runId")))

        val run = GetLoadTestRun(tenantId = tenantId, runId = runId).query()
            ?: return ServerResponse.notFound().build()

        // Fetch template name for display
        val template = GetDocumentTemplate(tenantId = tenantId, id = run.templateId).query()

        return ServerResponse.ok().render(
            "loadtest/detail",
            mapOf(
                "tenantId" to tenantId,
                "run" to run,
                "template" to template,
            ),
        )
    }

    /**
     * HTMX fragment endpoint for polling metrics during test execution.
     */
    fun metrics(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val runId = LoadTestRunId.of(UUID.fromString(request.pathVariable("runId")))

        val run = GetLoadTestRun(tenantId = tenantId, runId = runId).query()
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("loadtest/detail", "metrics-section") {
                "run" to run
            }
            onNonHtmx { redirect("/tenants/$tenantId/load-tests/$runId") }
        }
    }

    /**
     * Show detailed request log for a load test run.
     */
    fun requests(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val runId = LoadTestRunId.of(UUID.fromString(request.pathVariable("runId")))

        val run = GetLoadTestRun(tenantId = tenantId, runId = runId).query()
            ?: return ServerResponse.notFound().build()

        // Parse pagination params
        val offset = request.param("offset").orElse("0").toInt()
        val limit = request.param("limit").orElse("100").toInt()

        val requests = GetLoadTestRequests(
            tenantId = tenantId,
            runId = runId,
            offset = offset,
            limit = limit,
        ).query()

        return ServerResponse.ok().render(
            "loadtest/requests",
            mapOf(
                "tenantId" to tenantId,
                "run" to run,
                "requests" to requests,
                "offset" to offset,
                "limit" to limit,
            ),
        )
    }

    /**
     * Cancel a running load test.
     */
    fun cancel(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val runId = LoadTestRunId.of(UUID.fromString(request.pathVariable("runId")))

        try {
            CancelLoadTest(tenantId = tenantId, runId = runId).execute()
        } catch (e: Exception) {
            // Ignore errors (e.g., test already completed)
        }

        // Redirect back to detail page (both HTMX and non-HTMX)
        return redirect("/tenants/$tenantId/load-tests/$runId")
    }
}
