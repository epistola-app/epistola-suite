package app.epistola.suite.loadtest

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.htmxTriggerName
import app.epistola.suite.htmx.isHtmx
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
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.tenants.queries.GetTenant
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
        val tenant = GetTenant(tenantId).query() ?: return ServerResponse.notFound().build()
        val runs = ListLoadTestRuns(tenantId = tenantId, limit = 50).query()

        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "loadtest/list",
                "pageTitle" to "Load Tests - Epistola",
                "tenant" to tenant,
                "tenantId" to tenantId.value,
                "runs" to runs,
            ),
        )
    }

    /**
     * Show form to configure a new load test.
     *
     * For non-HTMX requests: renders the full page with template/environment dropdowns.
     * For HTMX requests: returns partial fragments for variant, version, and data example
     * dropdowns based on the current form selection. Uses HX-Trigger-Name to determine
     * which field triggered the request (templateId, variantId, or exampleId).
     */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))

        if (!request.isHtmx) {
            val templates = ListDocumentTemplates(tenantId = tenantId).query()
            val environments = ListEnvironments(tenantId = tenantId).query()

            return ServerResponse.ok().render(
                "layout/shell",
                mapOf(
                    "contentView" to "loadtest/new",
                    "pageTitle" to "Start Load Test - Epistola",
                    "tenantId" to tenantId.value,
                    "templates" to templates,
                    "environments" to environments,
                ),
            )
        }

        // HTMX request: return template-options fragment
        val templateIdStr = request.param("templateId").orElse("")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return request.htmx {
                fragment("loadtest/new", "template-options-empty")
            }

        val variants = ListVariants(tenantId = tenantId, templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = tenantId, id = templateId).query()
        val dataExamples = template?.dataExamples ?: emptyList()
        val environments = ListEnvironments(tenantId = tenantId).query()

        val triggerName = request.htmxTriggerName
        val resetsAll = triggerName == "templateId"

        val selectedVariantId = if (resetsAll) {
            variants.firstOrNull { it.isDefault }?.id?.value ?: variants.firstOrNull()?.id?.value ?: ""
        } else {
            request.param("variantId").orElse("")
        }

        val selectedExampleId = if (resetsAll) {
            ""
        } else {
            request.param("exampleId").orElse("")
        }

        // Load versions for the selected variant
        val versions = if (selectedVariantId.isNotBlank()) {
            ListVersions(
                tenantId = tenantId,
                templateId = templateId,
                variantId = VariantId.of(selectedVariantId),
            ).query()
        } else {
            emptyList()
        }

        // Version/environment mutual exclusion:
        // - selecting a version clears the environment
        // - selecting an environment clears the version
        // - template/variant change resets both
        val selectedVersionId = when {
            resetsAll || triggerName == "variantId" -> ""
            triggerName == "environmentId" -> ""
            else -> request.param("versionId").orElse("")
        }
        val selectedEnvironmentId = when {
            resetsAll || triggerName == "variantId" -> ""
            triggerName == "versionId" -> ""
            else -> request.param("environmentId").orElse("")
        }

        // If an example is selected, pretty-print its data as test data
        val testData = if (selectedExampleId.isNotBlank()) {
            dataExamples.firstOrNull { it.id == selectedExampleId }?.let {
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.data)
            } ?: ""
        } else {
            ""
        }

        return request.htmx {
            fragment("loadtest/new", "template-options") {
                "variants" to variants
                "versions" to versions
                "dataExamples" to dataExamples
                "environments" to environments
                "selectedVariantId" to selectedVariantId
                "selectedVersionId" to selectedVersionId
                "selectedExampleId" to selectedExampleId
                "selectedEnvironmentId" to selectedEnvironmentId
                "testData" to testData
                "tenantId" to tenantId.value
            }
        }
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

        // Parse test data JSON
        val testDataStr = params.getFirst("testData")?.takeIf { it.isNotBlank() } ?: "{}"
        val testData = objectMapper.readTree(testDataStr) as tools.jackson.databind.node.ObjectNode

        try {
            val run = StartLoadTest(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = versionId,
                environmentId = environmentId,
                targetCount = targetCount,
                concurrencyLevel = 1, // Legacy field, not used with batch submission
                testData = testData,
            ).execute()

            return redirect("/tenants/$tenantId/load-tests/${run.id}")
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Failed to start load test"

            return request.htmx {
                fragment("loadtest/new", "form-error") {
                    "error" to errorMessage
                }
                onNonHtmx {
                    val templates = ListDocumentTemplates(tenantId = tenantId).query()
                    val environments = ListEnvironments(tenantId = tenantId).query()
                    ServerResponse.badRequest().render(
                        "layout/shell",
                        mapOf(
                            "contentView" to "loadtest/new",
                            "pageTitle" to "Start Load Test - Epistola",
                            "tenantId" to tenantId.value,
                            "templates" to templates,
                            "environments" to environments,
                            "error" to errorMessage,
                        ),
                    )
                }
            }
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
            "layout/shell",
            mapOf(
                "contentView" to "loadtest/detail",
                "pageTitle" to "Load Test Details - Epistola",
                "tenantId" to tenantId.value,
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
            "layout/shell",
            mapOf(
                "contentView" to "loadtest/requests",
                "pageTitle" to "Load Test Request Log - Epistola",
                "tenantId" to tenantId.value,
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
