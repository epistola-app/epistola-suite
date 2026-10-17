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
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.catalog.versions.PreviewCatalogVersionRenumber
import app.epistola.suite.catalog.versions.RenumberCatalogVersion
import app.epistola.suite.catalog.versions.RenumberWarning
import app.epistola.suite.catalog.versions.RenumberWarningsNotAcknowledgedException
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.FormData
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
        val catalogKey = request.catalogKey()
        val error = try {
            MarkCatalogReady(tenantId.key, catalogKey).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        return nextRelease(request, tenantId, catalogKey, error)
    }

    // ── Renumber ───────────────────────────────────────────────────────────

    fun renumberForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_PUBLISH)
        val catalogKey = request.catalogKey()
        val draft = draftVersion(tenantId, catalogKey) ?: return ServerResponse.notFound().build()
        return request.htmx {
            fragment("catalogs/next-release", "renumber-dialog") {
                "tenantId" to tenantId.key
                "catalogId" to catalogKey.value
                "draftVersion" to draft
            }
            onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
        }
    }

    /**
     * `POST /{catalogId}/renumber`. The renumber is previewed first: warnings come back in the dialog
     * to acknowledge, refusals (a number in use, not above every release) as an error on the field.
     */
    fun renumber(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_PUBLISH)
        val catalogKey = request.catalogKey()
        val draft = draftVersion(tenantId, catalogKey) ?: return ServerResponse.notFound().build()

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
                "catalogId" to catalogKey.value
                "draftVersion" to draft
                "warnings" to warnings
            }
            onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
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

        return request.htmx {
            dialogSuccess("catalogs/next-release", "next-release", browseUrl(tenantId, catalogKey)) {
                nextReleaseModel(tenantId, catalogKey)
            }
            onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
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
        requirePermission(tenantId.key, Permission.TEMPLATE_PUBLISH)
        val catalogKey = request.catalogKey()
        val rows = ListCatalogDependencies(tenantId.key, catalogKey).query()
            .map { DependencyRowView("${it.registry}~${it.namespace}/${it.catalogKey}", it.version) }
        return request.htmx {
            fragment("catalogs/next-release", "dependencies-dialog") {
                dependenciesModel(tenantId, catalogKey, rows)
            }
            onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
        }
    }

    /**
     * `POST /{catalogId}/dependencies` with repeated `catalog` (`registry~namespace/key`) and
     * `version` (npm range) fields, in order. A row without a catalog is dropped; a row without a
     * range depends on any release (`*`).
     */
    fun updateDependencies(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_PUBLISH)
        val catalogKey = request.catalogKey()

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
                dependenciesModel(tenantId, catalogKey, rows, withBlankRow = false)
            }
            onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
        }

        val dependencies = declared.map { (index, row) ->
            parseDependency(row) ?: return reRender(mapOf("dependencies[$index]" to "Choose a catalog from the list."))
        }

        try {
            UpdateCatalogDependencies(tenantId.key, catalogKey, dependencies).execute()
        } catch (e: ValidationException) {
            // `dependencies[i]…` names the i-th declaration; show it on the row it came from.
            val declarationIndex = Regex("""^dependencies\[(\d+)]""").find(e.field)?.groupValues?.get(1)?.toIntOrNull()
            val rowIndex = declarationIndex?.let { declared.getOrNull(it)?.index }
            return if (rowIndex != null) {
                reRender(mapOf("dependencies[$rowIndex]" to e.message))
            } else {
                request.htmx {
                    dialogFormError("catalog-dependencies-error", e.message)
                    onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
                }
            }
        }

        return request.htmx {
            dialogSuccess("catalogs/next-release", "next-release", browseUrl(tenantId, catalogKey)) {
                nextReleaseModel(tenantId, catalogKey)
            }
            onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
        }
    }

    /** `POST /{catalogId}/dependencies/resolve`: moves every lock to the highest matching release. */
    fun resolveDependencies(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogKey()
        val error = try {
            ResolveCatalogDependencies(tenantId.key, catalogKey).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        return nextRelease(request, tenantId, catalogKey, error)
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
        catalogKey: CatalogKey,
        rows: List<DependencyRowView>,
        withBlankRow: Boolean = true,
    ) {
        val options = ListCatalogs(tenantId.key).query()
            .filter { it.id != catalogKey }
            .map { DependencyOptionView("${it.registry}~${it.namespace}/${it.id.value}", "${it.name} (${it.namespace}/${it.id.value})") }
        "tenantId" to tenantId.key
        "catalogId" to catalogKey.value
        "rows" to if (withBlankRow || rows.isEmpty()) rows + DependencyRowView("", "") else rows
        "dependencyOptions" to options
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private fun nextRelease(request: ServerRequest, tenantId: TenantId, catalogKey: CatalogKey, error: String?): ServerResponse = request.htmx {
        fragment("catalogs/next-release", "next-release") {
            nextReleaseModel(tenantId, catalogKey)
            if (error != null) "nextReleaseError" to error
        }
        onFullPage { redirect(browseUrl(tenantId, catalogKey)) }
    }

    private fun app.epistola.suite.htmx.ModelBuilder.nextReleaseModel(tenantId: TenantId, catalogKey: CatalogKey) {
        "tenantId" to tenantId.key
        "catalogId" to catalogKey.value
        "readiness" to CatalogReleaseReadiness.of(tenantId.key, catalogKey)
    }

    private fun draftVersion(tenantId: TenantId, catalogKey: CatalogKey): String? = ListCatalogVersions(tenantId.key, catalogKey, CatalogVersionStatus.DRAFT).query().firstOrNull()?.version

    private fun ServerRequest.catalogKey(): CatalogKey = CatalogKey.of(pathVariable("catalogId"))

    private fun browseUrl(tenantId: TenantId, catalogKey: CatalogKey) = "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse"
}
