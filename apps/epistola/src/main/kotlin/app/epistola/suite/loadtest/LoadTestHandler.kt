package app.epistola.suite.loadtest

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.htmxTriggerName
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParamInt
import app.epistola.suite.htmx.redirect
import app.epistola.suite.loadtest.commands.CancelLoadTest
import app.epistola.suite.loadtest.commands.StartLoadTest
import app.epistola.suite.loadtest.model.LoadTestRunKey
import app.epistola.suite.loadtest.queries.GetLoadTestRequests
import app.epistola.suite.loadtest.queries.GetLoadTestRun
import app.epistola.suite.loadtest.queries.ListLoadTestRuns
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.contracts.GetLatestContractVersion
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
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenant = GetTenant(tenantKey).query() ?: return ServerResponse.notFound().build()
        val runs = ListLoadTestRuns(tenantId = tenantKey, limit = 50).query()

        return ServerResponse.ok().page("loadtest/list") {
            "pageTitle" to "Load Tests - Epistola"
            "tenant" to tenant
            "tenantId" to tenantKey
            "runs" to runs
        }
    }

    /**
     * Show form to configure a new load test.
     *
     * Uses unified HTMX DSL pattern:
     * - Non-HTMX requests: renders the full page with template/environment dropdowns.
     * - HTMX requests: returns partial fragments for variant, version, and data example
     *   dropdowns based on the current form selection. Uses HX-Trigger-Name to determine
     *   which field triggered the request (templateId, variantId, or exampleId).
     */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)

        // Check if this is a template selection (HTMX cascade update)
        // Template select value is "catalogKey/templateKey"
        val templateIdStr = request.param("templateId").orElse("")
        val slashIdx = templateIdStr.indexOf('/')
        val catalogKey = if (slashIdx > 0) app.epistola.suite.common.ids.CatalogKey.of(templateIdStr.substring(0, slashIdx)) else null
        val templateKeyStr = if (slashIdx > 0) templateIdStr.substring(slashIdx + 1) else templateIdStr
        val templateKey = TemplateKey.validateOrNull(templateKeyStr)

        // If no template selected, return empty fragment for HTMX or full page for non-HTMX
        if (templateKey == null) {
            return request.htmx {
                onNonHtmx {
                    page("loadtest/new") {
                        "pageTitle" to "Start Load Test - Epistola"
                        "tenantId" to tenantKey
                        "templates" to ListDocumentTemplates(tenantId = tenantId).query()
                        "environments" to ListEnvironments(tenantId = tenantId).query()
                    }
                }
                fragment("loadtest/new", "template-options-empty")
            }
        }

        val templateId = TemplateId(templateKey, CatalogId(catalogKey ?: return ServerResponse.badRequest().build(), tenantId))

        // Template selected - prepare cascade data
        val variants = ListVariants(templateId = templateId).query()
        val contractVersion = GetLatestContractVersion(templateId = templateId).query()
        val dataExamples = contractVersion?.dataExamples ?: DataExamples.EMPTY
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
            val variantId = VariantId(VariantKey.of(selectedVariantId), templateId)
            ListVersions(variantId = variantId).query()
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
            onNonHtmx {
                page("loadtest/new") {
                    "pageTitle" to "Start Load Test - Epistola"
                    "tenantId" to tenantKey
                    "templates" to ListDocumentTemplates(tenantId = tenantId).query()
                    "environments" to ListEnvironments(tenantId = tenantId).query()
                }
            }
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
                "tenantId" to tenantKey
            }
        }
    }

    /**
     * Start a new load test.
     */
    fun start(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)

        val form = request.form {
            field("templateId") {
                required()
                // No asTemplateId() — value is composite "catalogKey/templateKey", parsed manually below
            }
            field("variantId") {
                required()
                asVariantId()
            }
        }

        if (form.hasErrors()) {
            val errorMessage = form.errors.values.firstOrNull() ?: "Form validation failed"
            return request.htmx {
                fragment("loadtest/new", "form-error") {
                    "error" to errorMessage
                }
                onNonHtmx {
                    val templates = ListDocumentTemplates(tenantId = tenantId).query()
                    val environments = ListEnvironments(tenantId = tenantId).query()
                    ServerResponse.badRequest().page("loadtest/new") {
                        "pageTitle" to "Start Load Test - Epistola"
                        "tenantId" to tenantKey
                        "templates" to templates
                        "environments" to environments
                        "error" to errorMessage
                    }
                }
            }
        }

        // Parse composite templateId (catalogKey/templateKey)
        val rawTemplateId = form.formData["templateId"] ?: ""
        val templateSlashIdx = rawTemplateId.indexOf('/')
        val templateCatalogKey = if (templateSlashIdx > 0) app.epistola.suite.common.ids.CatalogKey.of(rawTemplateId.substring(0, templateSlashIdx)) else return ServerResponse.badRequest().build()
        val templateKeyStr = if (templateSlashIdx > 0) rawTemplateId.substring(templateSlashIdx + 1) else rawTemplateId
        val templateKey = TemplateKey.validateOrNull(templateKeyStr) ?: return ServerResponse.badRequest().build()
        val variantKey = form.getVariantId("variantId")!!

        // Parse version or environment
        val params = request.params()
        val versionIdStr = params.getFirst("versionId")
        val environmentIdStr = params.getFirst("environmentId")

        val versionId = if (!versionIdStr.isNullOrBlank()) VersionKey.of(versionIdStr.toInt()) else null
        val environmentId = if (!environmentIdStr.isNullOrBlank()) EnvironmentKey.of(environmentIdStr) else null

        val targetCount = request.queryParamInt("targetCount", 100)

        // Parse test data JSON
        val testDataStr = params.getFirst("testData")?.takeIf { it.isNotBlank() } ?: "{}"
        val testData = objectMapper.readTree(testDataStr) as tools.jackson.databind.node.ObjectNode

        try {
            val run = StartLoadTest(
                tenantId = tenantKey,
                catalogKey = templateCatalogKey,
                templateId = templateKey,
                variantId = variantKey,
                versionId = versionId,
                environmentId = environmentId,
                targetCount = targetCount,
                concurrencyLevel = 1, // Legacy field, not used with batch submission
                testData = testData,
            ).execute()

            val url = "/tenants/$tenantKey/load-tests/${run.id}"
            return if (request.isHtmx) {
                ServerResponse.ok().header("HX-Redirect", url).build()
            } else {
                redirect(url)
            }
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Failed to start load test"

            return request.htmx {
                fragment("loadtest/new", "form-error") {
                    "error" to errorMessage
                }
                onNonHtmx {
                    val templates = ListDocumentTemplates(tenantId = tenantId).query()
                    val environments = ListEnvironments(tenantId = tenantId).query()
                    ServerResponse.badRequest().page("loadtest/new") {
                        "pageTitle" to "Start Load Test - Epistola"
                        "tenantId" to tenantKey
                        "templates" to templates
                        "environments" to environments
                        "error" to errorMessage
                    }
                }
            }
        }
    }

    /**
     * Show load test run detail with metrics.
     */
    fun detail(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val runId = LoadTestRunKey.of(UUID.fromString(request.pathVariable("runId")))

        val run = GetLoadTestRun(tenantId = tenantKey, runId = runId).query()
            ?: return ServerResponse.notFound().build()

        // Fetch template name for display
        val templateId = TemplateId(run.templateKey, CatalogId(run.catalogKey, tenantId))
        val template = GetDocumentTemplate(id = templateId).query()

        return ServerResponse.ok().page("loadtest/detail") {
            "pageTitle" to "Load Test Details - Epistola"
            "tenantId" to tenantKey
            "run" to run
            "template" to template
        }
    }

    /**
     * HTMX fragment endpoint for polling metrics during test execution.
     */
    fun metrics(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val runId = LoadTestRunKey.of(UUID.fromString(request.pathVariable("runId")))

        val run = GetLoadTestRun(tenantId = tenantKey, runId = runId).query()
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("loadtest/detail", "metrics-section") {
                "run" to run
            }
            onNonHtmx { redirect("/tenants/$tenantKey/load-tests/$runId") }
        }
    }

    /**
     * Show detailed request log for a load test run.
     */
    fun requests(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val runId = LoadTestRunKey.of(UUID.fromString(request.pathVariable("runId")))

        val run = GetLoadTestRun(tenantId = tenantKey, runId = runId).query()
            ?: return ServerResponse.notFound().build()

        // Parse pagination params
        val offset = request.queryParamInt("offset", 0)
        val limit = request.queryParamInt("limit", 100)

        val requests = GetLoadTestRequests(
            tenantId = tenantKey,
            runId = runId,
            offset = offset,
            limit = limit,
        ).query()

        return ServerResponse.ok().page("loadtest/requests") {
            "pageTitle" to "Load Test Request Log - Epistola"
            "tenantId" to tenantKey
            "run" to run
            "requests" to requests
            "offset" to offset
            "limit" to limit
        }
    }

    /**
     * Cancel a running load test.
     */
    fun cancel(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val runId = LoadTestRunKey.of(UUID.fromString(request.pathVariable("runId")))

        try {
            CancelLoadTest(tenantId = tenantKey, runId = runId).execute()
        } catch (e: Exception) {
            // Ignore errors (e.g., test already completed)
        }

        // Redirect back to detail page (both HTMX and non-HTMX)
        return redirect("/tenants/$tenantKey/load-tests/$runId")
    }
}
