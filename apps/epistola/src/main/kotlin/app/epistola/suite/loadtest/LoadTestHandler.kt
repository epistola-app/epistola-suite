package app.epistola.suite.loadtest

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.FormInputException
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.htmxCurrentUrl
import app.epistola.suite.htmx.htmxTriggerName
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParamInt
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.urlWithCreateParam
import app.epistola.suite.loadtest.commands.CancelLoadTest
import app.epistola.suite.loadtest.commands.StartLoadTest
import app.epistola.suite.loadtest.model.LoadTestRunKey
import app.epistola.suite.loadtest.queries.GetLoadTestRequests
import app.epistola.suite.loadtest.queries.GetLoadTestRun
import app.epistola.suite.loadtest.queries.ListLoadTestRuns
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
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
        val tenantId = TenantId(tenantKey)
        val tenant = GetTenant(tenantKey).query() ?: return ServerResponse.notFound().build()
        val runs = ListLoadTestRuns(tenantId = tenantKey, limit = 50).query()

        // `?create` deep-links the dialog open: render it inline (with the model
        // its dropdowns need). Only loaded on the deep-link path, not every list view.
        val createOpen = request.param("create").isPresent
        return ServerResponse.ok().page("loadtest/list") {
            "pageTitle" to "Load Tests - Epistola"
            "tenant" to tenant
            "tenantId" to tenantKey
            "runs" to runs
            "createOpen" to createOpen
            if (createOpen) {
                "templates" to ListDocumentTemplates(tenantId = tenantId).query()
                "environments" to ListEnvironments(tenantId = tenantId).query()
            }
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

        // Dialog-only: a direct (non-HTMX) navigation has no dialog host, so
        // bounce to the list instead of rendering a full page.
        if (!request.isHtmx) {
            return redirect("/tenants/$tenantKey/load-tests")
        }

        // The endpoint is dual-purpose. Opening the dialog (the trigger <a> has
        // no field name) returns the dialog shell; the cascade selects each carry
        // a field name and get the template-options fragment swapped into
        // #template-options-section.
        val cascadeFields = setOf("templateId", "variantId", "versionId", "environmentId", "exampleId")
        if (request.htmxTriggerName !in cascadeFields) {
            // Dialog-open branch only: push `?create` so the open dialog is
            // deep-linkable. The cascade fetches below also hit this endpoint, so
            // the push MUST stay guarded here — never on a cascade update.
            return ServerResponse.ok()
                .header("HX-Push-Url", urlWithCreateParam(request.htmxCurrentUrl, "/tenants/$tenantKey/load-tests"))
                .render(
                    "loadtest/new :: createDialog",
                    mapOf(
                        "tenantId" to tenantKey,
                        "templates" to ListDocumentTemplates(tenantId = tenantId).query(),
                        "environments" to ListEnvironments(tenantId = tenantId).query(),
                    ),
                )
        }

        // Cascade update. Template select value is "catalogKey/templateKey".
        val templateIdStr = request.param("templateId").orElse("")
        val slashIdx = templateIdStr.indexOf('/')
        val catalogKey = if (slashIdx > 0) app.epistola.suite.common.ids.CatalogKey.of(templateIdStr.substring(0, slashIdx)) else null
        val templateKeyStr = if (slashIdx > 0) templateIdStr.substring(slashIdx + 1) else templateIdStr
        val templateKey = TemplateKey.validateOrNull(templateKeyStr)

        // No template selected yet → reset to the empty options.
        if (templateKey == null) {
            return ServerResponse.ok().render("loadtest/new :: template-options-empty", emptyMap<String, Any>())
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

        return ServerResponse.ok().render(
            "loadtest/new :: template-options",
            mapOf(
                "variants" to variants,
                "versions" to versions,
                "dataExamples" to dataExamples,
                "environments" to environments,
                "selectedVariantId" to selectedVariantId,
                "selectedVersionId" to selectedVersionId,
                "selectedExampleId" to selectedExampleId,
                "selectedEnvironmentId" to selectedEnvironmentId,
                "testData" to testData,
                "tenantId" to tenantKey,
            ),
        )
    }

    /**
     * Start a new load test.
     */
    fun start(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val params = request.params()

        // Field-keyable problems (invalid JSON, a missing/invalid template/variant/version)
        // accumulate per field and render inline as per-field OOB spans with HX-Reswap: none,
        // so the dialog's cascade selections and typed JSON survive. Only an operational
        // StartLoadTest failure (below) is a non-field error, thrown to the shared
        // #dialog-error region by the central resolver. See ADR 0011.
        val errors = linkedMapOf<String, String>()

        fun fieldErrors(): ServerResponse = request.htmx {
            reswap(HxSwap.NONE)
            oob("loadtest/new", "error-fields") { "errors" to errors }
        }

        // templateId — composite "catalogKey/templateKey"
        val rawTemplateId = params.getFirst("templateId")?.trim().orEmpty()
        var templateCatalogKey: CatalogKey? = null
        var templateKey: TemplateKey? = null
        if (rawTemplateId.isBlank()) {
            errors["templateId"] = "Template is required"
        } else {
            val slashIdx = rawTemplateId.indexOf('/')
            if (slashIdx <= 0) {
                errors["templateId"] = "Select a template."
            } else {
                templateCatalogKey = CatalogKey.of(rawTemplateId.substring(0, slashIdx))
                templateKey = TemplateKey.validateOrNull(rawTemplateId.substring(slashIdx + 1))
                if (templateKey == null) errors["templateId"] = "Select a valid template."
            }
        }

        // variantId
        val rawVariantId = params.getFirst("variantId")?.trim().orEmpty()
        var variantKey: VariantKey? = null
        if (rawVariantId.isBlank()) {
            errors["variantId"] = "Variant is required"
        } else {
            variantKey = VariantKey.validateOrNull(rawVariantId)
            if (variantKey == null) errors["variantId"] = "Select a valid variant."
        }

        // versionId (optional) — rendered only when no environment is chosen
        var versionId: VersionKey? = null
        val versionIdStr = params.getFirst("versionId")
        if (!versionIdStr.isNullOrBlank()) {
            val parsed = versionIdStr.toIntOrNull()
            if (parsed == null) errors["versionId"] = "Select a valid version." else versionId = VersionKey.of(parsed)
        }

        // environmentId (optional)
        val environmentIdStr = params.getFirst("environmentId")
        val environmentId = if (!environmentIdStr.isNullOrBlank()) EnvironmentKey.of(environmentIdStr) else null

        // testData JSON — invalid or non-object JSON is a field error on testData.
        val testDataStr = params.getFirst("testData")?.takeIf { it.isNotBlank() } ?: "{}"
        var testData: ObjectNode? = null
        try {
            testData = objectMapper.readTree(testDataStr) as? ObjectNode
            if (testData == null) {
                errors["testData"] = "Test data must be a JSON object, e.g. {\"customer\": \"Acme\"}."
            }
        } catch (e: Exception) {
            errors["testData"] = "Test data must be valid JSON."
        }

        if (errors.isNotEmpty()) return fieldErrors()

        val targetCount = request.queryParamInt("targetCount", 100)

        // A backend rejection (template/version not found, conflict, …) is NOT a field
        // problem: it is thrown and surfaced in the shared #dialog-error region.
        val run = try {
            StartLoadTest(
                tenantId = tenantKey,
                catalogKey = templateCatalogKey!!,
                templateId = templateKey!!,
                variantId = variantKey!!,
                versionId = versionId,
                environmentId = environmentId,
                targetCount = targetCount,
                concurrencyLevel = 1, // Legacy field, not used with batch submission
                testData = testData!!,
            ).execute()
        } catch (e: Exception) {
            throw FormInputException(e.message ?: "Failed to start load test")
        }

        val url = "/tenants/$tenantKey/load-tests/${run.id}"
        return if (request.isHtmx) {
            ServerResponse.ok().header("HX-Redirect", url).build()
        } else {
            redirect(url)
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
