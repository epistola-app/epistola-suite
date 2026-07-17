package app.epistola.suite.quality.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.pathId
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.queryParamInt
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.EffectiveQualityStatus
import app.epistola.suite.quality.QualityFindingPage
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySourceRegistry
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.commands.AddFindingComment
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.RecordManualFinding
import app.epistola.suite.quality.commands.ResolveManualFinding
import app.epistola.suite.quality.commands.UnignoreFindingByFinding
import app.epistola.suite.quality.queries.GetFindingComments
import app.epistola.suite.quality.queries.GetQualityFinding
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.quality.queries.QualityFindingSort
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * The tenant-wide quality report: every finding from every source, filtered and paged, plus the
 * human half of the ledger — ignore-with-a-reason, comments, and findings raised by hand.
 *
 * Templates live in this module under `templates/quality` and merge onto the host app's classpath at
 * runtime, reusing the app's `layout/shell` chrome — the same arrangement `epistola-support-feedback`
 * uses. Visibility is gated by the **alpha** `quality` feature toggle; the nav item hides with it.
 *
 * Reads need `TEMPLATE_VIEW`, writes `TEMPLATE_EDIT` — enforced by the commands and queries
 * themselves, not re-implemented here.
 */
@Component
class QualityHandler(
    private val registry: QualitySourceRegistry,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val filters = request.filters()
        val page = filters.toQuery(tenantId).query()

        return ServerResponse.ok().page("quality/list") {
            "pageTitle" to "Quality - Epistola"
            "tenant" to tenant
            "activeNavSection" to "quality"
            "stage" to KnownFeatures.metadata[KnownFeatures.QUALITY]?.stage
            "templates" to ListDocumentTemplates(tenantId, limit = TEMPLATE_FILTER_LIMIT).query()
            "sources" to registry.availableFor(tenantId.key).map { SourceOption(it.sourceId.value, it.displayName) }
            findingsModel(tenantId, page, filters)
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val filters = request.filters()
        val page = filters.toQuery(tenantId).query()

        return request.htmx {
            fragment("quality/list", "findings") {
                findingsModel(tenantId, page, filters)
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/quality") }
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val findingKey = request.findingKey() ?: return ServerResponse.badRequest().build()
        val finding = GetQualityFinding(tenantId.key, findingKey).query()
            ?: return ServerResponse.notFound().build()
        val tenant = GetTenant(tenantId.key).query()

        return ServerResponse.ok().page("quality/detail") {
            "pageTitle" to "${finding.ruleId} - Quality - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "activeNavSection" to "quality"
            "finding" to finding
            "comments" to GetFindingComments(tenantId.key, findingKey).query()
            "sourceName" to sourceName(finding.sourceId)
        }
    }

    fun ignore(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val findingKey = request.findingKey() ?: return ServerResponse.badRequest().build()

        val form = request.form { field("reason") { required() } }
        if (form.hasErrors()) {
            // Retargeted into the open dialog. The success path swaps the whole detail body, which
            // disposes of the dialog — so rendering the error there would put it somewhere the
            // reader can no longer see. The command validates the reason too; this is the round
            // trip that keeps the message next to the box that produced it.
            val finding = GetQualityFinding(tenantId.key, findingKey).query()
                ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("quality/detail", "ignore-form") {
                    "tenantId" to tenantId.key
                    "finding" to finding
                    "formData" to form.formData
                    "errors" to form.errors
                }
                retarget("#quality-ignore-dialog-body")
                onNonHtmx { redirect("/tenants/${tenantId.key}/quality/${findingKey.value}") }
            }
        }

        IgnoreFinding(tenantId.key, findingKey, form["reason"]).execute()
        return refreshedDetail(request, tenantId.key, findingKey)
    }

    fun unignore(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val findingKey = request.findingKey() ?: return ServerResponse.badRequest().build()

        UnignoreFindingByFinding(tenantId.key, findingKey).execute()
        return refreshedDetail(request, tenantId.key, findingKey)
    }

    /**
     * Resolve by hand. Only ever offered for a manual finding — a reconciling source's finding
     * closes when the source stops reporting it, and a Resolve button there would be a lie the next
     * sweep overwrites. The command enforces the same restriction.
     */
    fun resolve(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val findingKey = request.findingKey() ?: return ServerResponse.badRequest().build()

        ResolveManualFinding(tenantId.key, findingKey).execute()
        return refreshedDetail(request, tenantId.key, findingKey)
    }

    fun addComment(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val findingKey = request.findingKey() ?: return ServerResponse.badRequest().build()

        val form = request.form { field("body") { required() } }
        if (form.hasErrors()) return ServerResponse.badRequest().build()

        AddFindingComment(tenantId.key, findingKey, form["body"]).execute()

        return request.htmx {
            fragment("quality/detail", "comments-section") {
                "tenantId" to tenantId.key
                "finding" to GetQualityFinding(tenantId.key, findingKey).query()
                "comments" to GetFindingComments(tenantId.key, findingKey).query()
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/quality/${findingKey.value}") }
        }
    }

    /** The form for raising a finding by hand, loaded into the list page's dialog. */
    fun manualForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        return request.htmx {
            fragment("quality/manual-form") {
                "tenantId" to tenantId.key
                "templates" to ListDocumentTemplates(tenantId, limit = TEMPLATE_FILTER_LIMIT).query()
                "severities" to QualitySeverity.entries
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/quality") }
        }
    }

    /**
     * The variants of one template, for the manual form's second picker.
     *
     * Loaded on demand rather than shipping every template's variants with the form: that would be
     * one `ListVariants` per template just to open a dialog, and the reader picks exactly one.
     */
    fun variantOptions(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateRef = request.templateRef(tenantId)
            ?: return request.htmx { fragment("quality/manual-form", "variant-options") { "variants" to emptyList<Any>() } }

        return request.htmx {
            fragment("quality/manual-form", "variant-options") {
                "variants" to ListVariants(templateRef).query()
            }
        }
    }

    /**
     * The `template` query param, as the `catalogKey/templateKey` pair it carries.
     *
     * A template key is unique only within its catalog, so the two always travel together — as a
     * value in one `<option>`, and split apart here.
     */
    private fun ServerRequest.templateFilter(): Pair<CatalogKey, TemplateKey>? {
        val raw = queryParam("template")?.takeIf { it.isNotBlank() } ?: return null
        val parts = raw.split('/', limit = 2).takeIf { it.size == 2 } ?: return null
        return runCatching { CatalogKey.of(parts[0]) to TemplateKey.of(parts[1]) }.getOrNull()
    }

    private fun ServerRequest.templateRef(tenantId: TenantId): TemplateId? {
        val (catalog, template) = templateFilter() ?: return null
        return TemplateId(template, CatalogId(catalog, tenantId))
    }

    fun createManual(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("template") { required() }
            field("variantKey") { required() }
            field("message") { required() }
            field("severity") { required() }
        }
        if (form.hasErrors()) return ServerResponse.badRequest().build()

        // `template` carries catalogKey/templateKey as one value; a malformed pair is a crafted
        // request, not something the form can produce.
        val parts = form["template"].split('/', limit = 2)
        if (parts.size != 2) return ServerResponse.badRequest().build()

        val variantId = runCatching {
            VariantId(
                VariantKey.of(form["variantKey"]),
                TemplateId(TemplateKey.of(parts[1]), CatalogId(CatalogKey.of(parts[0]), tenantId)),
            )
        }.getOrNull() ?: return ServerResponse.badRequest().build()

        val severity = runCatching { QualitySeverity.valueOf(form["severity"]) }.getOrNull()
            ?: return ServerResponse.badRequest().build()

        RecordManualFinding(
            subject = QualitySubject.of(variantId),
            message = form["message"],
            severity = severity,
        ).execute()

        // HX-Redirect rather than a swap: the new finding belongs in the list only if the active
        // filters admit it, and re-running the query is the one way to know. Navigating also
        // disposes of the dialog. A 303 would be followed transparently by the XHR and the header
        // lost — the reason BackupsHandler does the same.
        return ServerResponse.ok().header("HX-Redirect", "/tenants/${tenantId.key}/quality").build()
    }

    /**
     * Re-reads and re-renders the detail body after a write.
     *
     * Deliberately re-queries rather than patching the model in place: `effectiveStatus` is derived
     * from a live ignore row, so the only way to render what a reader will actually see next is to
     * ask the same query the page load asks.
     */
    private fun refreshedDetail(
        request: ServerRequest,
        tenantKey: TenantKey,
        findingKey: QualityFindingKey,
    ): ServerResponse {
        val finding = GetQualityFinding(tenantKey, findingKey).query()
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("quality/detail", "finding-content") {
                "tenantId" to tenantKey
                "finding" to finding
                "comments" to GetFindingComments(tenantKey, findingKey).query()
                "sourceName" to sourceName(finding.sourceId)
            }
            onNonHtmx { redirect("/tenants/$tenantKey/quality/${findingKey.value}") }
        }
    }

    /**
     * A source's human name, falling back to its id.
     *
     * The fallback is the normal case for a remote source, not an edge case: findings outlive the
     * source that made them and a remote checker has no bean here at all, so there is frequently no
     * `displayName` to look up. Showing the raw id beats showing nothing.
     */
    private fun sourceName(sourceId: QualitySourceId): String = when (sourceId) {
        QualitySourceId.MANUAL -> "Raised by hand"
        else -> registry.byId(sourceId)?.displayName ?: sourceId.value
    }

    private fun ServerRequest.findingKey(): QualityFindingKey? = pathId("findingId") { runCatching { QualityFindingKey.of(it) }.getOrNull() }

    /**
     * Absent means OPEN, empty means all.
     *
     * The report defaults to what is actionable — opening on a wall of resolved findings would bury
     * the ones a reader can do something about. Seeing everything stays available, but as a choice
     * the reader makes (the "All" option submits `status=`), which is why absent and empty must mean
     * different things here rather than collapsing into one null.
     */
    private fun ServerRequest.statusFilter(): EffectiveQualityStatus? {
        val raw = queryParam("status") ?: return EffectiveQualityStatus.OPEN
        return raw.toEnumOrNull()
    }

    private fun ServerRequest.filters() = QualityFilters(
        // The template filter carries `catalogKey/templateKey`, and sets *both* query filters — a
        // template key is unique only within its catalog, so filtering on the key alone would also
        // match a same-named template in another catalog.
        catalogKey = templateFilter()?.first,
        templateKey = templateFilter()?.second,
        sourceId = queryParam("source")?.takeIf { it.isNotBlank() }?.let { QualitySourceId(it) },
        severity = queryParam("severity").toEnumOrNull<QualitySeverity>(),
        status = statusFilter(),
        searchTerm = queryParam("q")?.takeIf { it.isNotBlank() },
        sort = QualityFindingSort.fromParam(queryParam("sort")),
        descending = queryParam("dir")?.let { it == "desc" } ?: QualityFindingSort.fromParam(queryParam("sort")).defaultDescending,
        page = queryParamInt("page", 1).coerceAtLeast(1),
    )

    private companion object {
        /**
         * The template filter's dropdown. Not a sweep: it populates a picker, so a tenant beyond
         * this many templates gets a truncated *filter list*, never a truncated report — the report
         * itself pages in SQL over everything.
         */
        const val TEMPLATE_FILTER_LIMIT = 500
        const val PAGE_SIZE = 50
    }

    /**
     * The model the list page and its HTMX fragment share, so a filter added in one cannot go
     * missing from the other.
     *
     * A [ModelBuilder] extension rather than a `Map` the callers merge: the DSL takes entries only
     * through its `infix String.to`, and routing a map through it invites the silent fall-through to
     * `kotlin.to` that [ModelBuilder]'s own KDoc warns about — where the pair is discarded as an
     * expression-statement and the key never reaches the model, with no signal at all.
     */
    private fun ModelBuilder.findingsModel(
        tenantId: TenantId,
        page: QualityFindingPage,
        filters: QualityFilters,
    ) {
        // Ceiling division; an empty report is still page 1 of 1 rather than 1 of 0.
        val totalPages = if (page.total == 0) 1 else (page.total + PAGE_SIZE - 1) / PAGE_SIZE

        "tenantId" to tenantId.key
        "findings" to page.items
        "total" to page.total
        "page" to filters.page
        "totalPages" to totalPages
        "hasPrev" to (filters.page > 1)
        "hasNext" to (filters.page < totalPages)
        "prevPage" to (filters.page - 1)
        "nextPage" to (filters.page + 1)
        "severities" to QualitySeverity.entries
        "statuses" to EffectiveQualityStatus.entries
        "selectedSeverity" to filters.severity
        "selectedStatus" to filters.status
        "selectedSource" to filters.sourceId?.value
        // Round-trips the same `catalogKey/templateKey` the option carries, so the picker
        // re-selects after a swap.
        "selectedTemplate" to filters.templateKey?.let { "${filters.catalogKey?.value}/${it.value}" }
        "searchTerm" to filters.searchTerm
        "sort" to filters.sort.param
        "sortDir" to if (filters.descending) "desc" else "asc"
    }

    /** A source the reader can filter by. A pair would render as `getFirst()`/`getSecond()`. */
    data class SourceOption(
        val id: String,
        val name: String,
    )

    private data class QualityFilters(
        val catalogKey: CatalogKey?,
        val templateKey: TemplateKey?,
        val sourceId: QualitySourceId?,
        val severity: QualitySeverity?,
        val status: EffectiveQualityStatus?,
        val searchTerm: String?,
        val sort: QualityFindingSort,
        val descending: Boolean,
        val page: Int,
    ) {
        fun toQuery(tenantId: TenantId) = ListQualityFindings(
            tenantKey = tenantId.key,
            catalogKey = catalogKey,
            templateKey = templateKey,
            sourceId = sourceId,
            severity = severity,
            status = status,
            searchTerm = searchTerm,
            sort = sort,
            descending = descending,
            limit = PAGE_SIZE,
            offset = (page - 1) * PAGE_SIZE,
        )
    }
}

/** An unrecognised filter value means "no filter", never a 500 — query strings are user input. */
private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? = this?.takeIf { it.isNotBlank() }?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
