// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ResolveCatalogDependencies
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.versions.CatalogVersionInUseException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionReleasedException
import app.epistola.suite.catalog.versions.PreviewCatalogVersionRenumber
import app.epistola.suite.catalog.versions.RenumberCatalogVersion
import app.epistola.suite.catalog.versions.RenumberWarning
import app.epistola.suite.catalog.versions.RenumberWarningsNotAcknowledgedException
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/** One row of the dependency dialog: the catalog chosen (`registry~namespace/key`) and its range. */
data class DependencyRowView(val catalog: String, val version: String)

/** A catalog the dependency dialog offers. */
data class DependencyOptionView(val value: String, val label: String)

/**
 * The actions that prepare an authored catalog's next release, from its page's "Next release"
 * section (`catalogs/next-release.html`): marking everything ready, renumbering the draft, and
 * declaring the catalogs it depends on. Releasing itself is [CatalogHandler.release].
 */
@Component
class CatalogReleaseHandler {

    /** `POST /{catalogId}/ready`: marks every changed resource ready; a refusal is shown in the section. */
    fun markReady(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val error = try {
            MarkCatalogReady(tenantId.key, segment.key, catalogVersion = segment.ref(tenantId.key)).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        return nextRelease(request, tenantId, segment, error)
    }

    // ── Renumber ───────────────────────────────────────────────────────────

    fun renumberForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        val draft = draftVersion(tenantId, segment) ?: return ServerResponse.notFound().build()
        return request.htmx {
            fragment("catalogs/next-release", "renumber-dialog") {
                "tenantId" to tenantId.key
                "catalogId" to segment.value
                "draftVersion" to draft
            }
            onFullPage { redirect(browseUrl(tenantId, segment)) }
        }
    }

    /**
     * `POST /{catalogId}/renumber`. The renumber is previewed first: warnings come back in the dialog
     * to acknowledge, refusals (a number in use, not above every release) as an error on the field.
     */
    fun renumber(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        val catalogKey = segment.key
        val bar = drafts(tenantId, segment)
        val draft = bar.chosen?.version ?: return ServerResponse.notFound().build()

        val form = request.form {
            field("to") {
                required()
                maxLength(50)
                pattern("^\\d+\\.\\d+\\.\\d+$")
            }
        }
        val acknowledged = request.params().getFirst("acknowledgeWarnings") == "true"

        fun reRender(result: FormData, warnings: List<RenumberWarning> = emptyList()): ServerResponse = request.htmx {
            dialogFieldErrors(
                template = "catalogs/next-release",
                fragmentName = "renumber-form",
                formTarget = "#renumber-catalog-form",
                formData = result,
            ) {
                "tenantId" to tenantId.key
                "catalogId" to segment.value
                "draftVersion" to draft
                "warnings" to warnings
            }
            onFullPage { redirect(browseUrl(tenantId, segment)) }
        }

        if (form.hasErrors()) {
            return reRender(FormData(form.formData, mapOf("to" to "Version must be SemVer — MAJOR.MINOR.PATCH (e.g. 1.4.0).")))
        }
        val to = form["to"].trim()

        try {
            if (!acknowledged) {
                val warnings = PreviewCatalogVersionRenumber(tenantId.key, catalogKey, draft, to).query()
                if (warnings.isNotEmpty()) return reRender(form, warnings)
            }
            RenumberCatalogVersion(tenantId.key, catalogKey, draft, to, acknowledgeWarnings = acknowledged).execute()
        } catch (e: RenumberWarningsNotAcknowledgedException) {
            return reRender(form, e.warnings)
        } catch (e: RuntimeException) {
            val message = renumberRefusal(e) ?: throw e
            return reRender(FormData(form.formData, mapOf("to" to message)))
        }

        // With several drafts the page names the draft it works in, and that name just changed: land on
        // the renumbered draft. With one, the section is re-rendered in place, as it always was.
        if (bar.severalDrafts) {
            val renumbered = segment.at(if (drafts(tenantId, segment.at(null)).current?.version == to) null else to)
            return request.htmx {
                dialogLocation(browseUrl(tenantId, renumbered))
                onFullPage { redirect(browseUrl(tenantId, renumbered)) }
            }
        }
        return request.htmx {
            dialogSuccess("catalogs/next-release", "next-release", browseUrl(tenantId, segment)) {
                nextReleaseModel(tenantId, segment)
            }
            onFullPage { redirect(browseUrl(tenantId, segment)) }
        }
    }

    /** The message of a renumber the catalog refuses, or null when [e] is not such a refusal. */
    private fun renumberRefusal(e: RuntimeException): String? = when (e) {
        is CatalogVersionInUseException,
        is CatalogVersionReleasedException,
        is CatalogVersionNotFoundException,
        is ValidationException,
        -> e.message

        else -> null
    }

    // ── Dependencies ───────────────────────────────────────────────────────

    fun dependenciesForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        val rows = ListCatalogDependencies(tenantId.key, segment.key, catalogVersion = segment.ref(tenantId.key)).query()
            .map { DependencyRowView("${it.registry}~${it.namespace}/${it.catalogKey}", it.version) }
        return request.htmx {
            fragment("catalogs/next-release", "dependencies-dialog") {
                dependenciesModel(tenantId, segment, rows)
            }
            onFullPage { redirect(browseUrl(tenantId, segment)) }
        }
    }

    /**
     * `POST /{catalogId}/dependencies` with repeated `catalog` (`registry~namespace/key`) and
     * `version` (npm range) fields, in order. A row without a catalog is dropped; a row without a
     * range depends on any release (`*`).
     */
    fun updateDependencies(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()

        val catalogs = request.params()["catalog"].orEmpty()
        val versions = request.params()["version"].orEmpty()
        val rows = catalogs.mapIndexed { index, catalog -> DependencyRowView(catalog.trim(), versions.getOrNull(index)?.trim().orEmpty()) }
        // Which submitted row each declaration came from, so an error lands on the row it is about.
        val declared = rows.withIndex().filter { it.value.catalog.isNotEmpty() }

        fun reRender(errors: Map<String, String>): ServerResponse = request.htmx {
            dialogFieldErrors(
                template = "catalogs/next-release",
                fragmentName = "dependencies-form",
                formTarget = "#catalog-dependencies-form",
                formData = FormData(emptyMap(), errors),
            ) {
                dependenciesModel(tenantId, segment, rows, withBlankRow = false)
            }
            onFullPage { redirect(browseUrl(tenantId, segment)) }
        }

        val dependencies = declared.map { (index, row) ->
            parseDependency(row) ?: return reRender(mapOf("dependencies[$index]" to "Choose a catalog from the list."))
        }

        try {
            UpdateCatalogDependencies(tenantId.key, segment.key, dependencies, catalogVersion = segment.ref(tenantId.key)).execute()
        } catch (e: ValidationException) {
            // `dependencies[i]…` names the i-th declaration; show it on the row it came from.
            val declarationIndex = Regex("""^dependencies\[(\d+)]""").find(e.field)?.groupValues?.get(1)?.toIntOrNull()
            val rowIndex = declarationIndex?.let { declared.getOrNull(it)?.index }
            return if (rowIndex != null) {
                reRender(mapOf("dependencies[$rowIndex]" to e.message))
            } else {
                request.htmx {
                    dialogFormError("catalog-dependencies-error", e.message)
                    onFullPage { redirect(browseUrl(tenantId, segment)) }
                }
            }
        }

        // Back on the Dependencies page, which now lists what was declared.
        val dependenciesPage = "/tenants/${tenantId.key}/catalogs/${segment.value}/dependencies"
        return request.htmx {
            dialogLocation(dependenciesPage)
            onFullPage { redirect(dependenciesPage) }
        }
    }

    /** `POST /{catalogId}/dependencies/resolve`: moves every lock to the highest matching release. */
    fun resolveDependencies(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val error = try {
            ResolveCatalogDependencies(tenantId.key, segment.key, catalogVersion = segment.ref(tenantId.key)).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        return nextRelease(request, tenantId, segment, error)
    }

    private fun parseDependency(row: DependencyRowView): DeclaredCatalogDependency? {
        val registry = row.catalog.substringBefore('~', "").ifEmpty { return null }
        val rest = row.catalog.substringAfter('~', "")
        val namespace = rest.substringBefore('/', "").ifEmpty { return null }
        val key = rest.substringAfter('/', "").ifEmpty { return null }
        return DeclaredCatalogDependency(registry, namespace, key, row.version.ifEmpty { "*" })
    }

    private fun app.epistola.suite.htmx.ModelBuilder.dependenciesModel(
        tenantId: TenantId,
        segment: CatalogSegment,
        rows: List<DependencyRowView>,
        withBlankRow: Boolean = true,
    ) {
        val options = ListCatalogs(tenantId.key).query()
            .filter { it.id != segment.key }
            .map { DependencyOptionView("${it.registry}~${it.namespace}/${it.id.value}", "${it.name} (${it.namespace}/${it.id.value})") }
        "tenantId" to tenantId.key
        "catalogId" to segment.value
        "rows" to if (withBlankRow || rows.isEmpty()) rows + DependencyRowView("", "") else rows
        "dependencyOptions" to options
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private fun nextRelease(request: ServerRequest, tenantId: TenantId, segment: CatalogSegment, error: String?): ServerResponse = request.htmx {
        fragment("catalogs/next-release", "next-release") {
            nextReleaseModel(tenantId, segment)
            if (error != null) "nextReleaseError" to error
        }
        onFullPage { redirect(browseUrl(tenantId, segment)) }
    }

    private fun app.epistola.suite.htmx.ModelBuilder.nextReleaseModel(tenantId: TenantId, segment: CatalogSegment) {
        "tenantId" to tenantId.key
        "catalogId" to segment.value
        "readiness" to CatalogReleaseReadiness.of(drafts(tenantId, segment))
    }

    private fun drafts(tenantId: TenantId, segment: CatalogSegment): CatalogDraftBar = CatalogDraftBar.of(tenantId.key, segment) { browseUrl(tenantId, it) }

    private fun draftVersion(tenantId: TenantId, segment: CatalogSegment): String? = drafts(tenantId, segment).chosen?.version

    private fun browseUrl(tenantId: TenantId, segment: CatalogSegment) = CatalogPages.draft(tenantId, segment)
}
