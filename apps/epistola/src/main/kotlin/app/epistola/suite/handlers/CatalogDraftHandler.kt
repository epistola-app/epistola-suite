// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.CatalogReleaseNotRetainedException
import app.epistola.suite.catalog.versions.CatalogVersionHeldException
import app.epistola.suite.catalog.versions.CatalogVersionInUseException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionReleasedException
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogVersion
import app.epistola.suite.catalog.versions.DeleteCatalogVersion
import app.epistola.suite.catalog.versions.ListCatalogVersions
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

/**
 * Opening and discarding drafts of an authored catalog (contract ADR 0002), from the catalog page and
 * from the empty state of a catalog without one. Renumbering and releasing a draft are
 * [CatalogReleaseHandler] and [CatalogHandler]; which draft a page works in is the route's catalog
 * segment ([CatalogSegment]).
 */
@Component
class CatalogDraftHandler(
    private val catalogs: CatalogHandler,
) {

    /** `GET /{catalogId}/drafts/new`: the dialog opening a draft. */
    fun createForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        return request.htmx {
            fragment("catalogs/drafts", "create-dialog") {
                "tenantId" to tenantId.key
                "bar" to bar(tenantId, segment)
            }
            onFullPage { redirect(CatalogPages.browse(tenantId, segment)) }
        }
    }

    /**
     * `POST /{catalogId}/drafts` with `version` and optionally `from`: opens the draft and lands on the
     * catalog page in it. From the dialog a refusal comes back on its field; from the empty state, which
     * is a plain form, it comes back on the catalog page.
     */
    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        val form = request.form {
            field("version") {
                required()
                maxLength(50)
                pattern("^\\d+\\.\\d+\\.\\d+$")
            }
        }
        val from = form.formData["from"]?.trim()?.ifBlank { null }
        val fromDialog = request.headers().firstHeader("HX-Target") == "create-draft-form"

        fun refused(field: String, message: String): ServerResponse = if (fromDialog) {
            request.htmx {
                dialogFieldErrors(
                    template = "catalogs/drafts",
                    fragmentName = "create-form",
                    formTarget = "#create-draft-form",
                    formData = FormData(form.formData, mapOf(field to message)),
                ) {
                    "tenantId" to tenantId.key
                    "bar" to bar(tenantId, segment)
                }
                onFullPage { redirect(CatalogPages.browse(tenantId, segment)) }
            }
        } else {
            catalogs.browseWithError(request, segment.at(null), message)
        }

        if (form.hasErrors()) return refused("version", "Version must be SemVer — MAJOR.MINOR.PATCH (e.g. 2.0.0).")
        val version = form["version"].trim()
        try {
            CreateCatalogVersion(tenantId.key, segment.key, version, from).execute()
        } catch (e: CatalogVersionInUseException) {
            return refused("version", e.message ?: "That number is in use.")
        } catch (e: CatalogVersionNotFoundException) {
            return refused("from", e.message ?: "There is no such version to copy.")
        } catch (e: CatalogReleaseNotRetainedException) {
            return refused("from", e.message ?: "That release kept no content to copy.")
        } catch (e: ValidationException) {
            return refused(if (e.field == "from") "from" else "version", e.message)
        }
        val target = CatalogPages.browse(tenantId, landing(tenantId, segment, version))
        return if (fromDialog) {
            request.htmx {
                dialogLocation(target)
                onFullPage { redirect(target) }
            }
        } else {
            ServerResponse.status(303).header("Location", target).build()
        }
    }

    /**
     * `POST /{catalogId}/drafts/discard`: discards the draft the segment names (or the current one) and
     * lands on the catalog page at its current version. A refusal, such as the draft alone holding the
     * tenant's default theme, is shown on the catalog page in the draft.
     */
    fun discard(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_RELEASE)
        val segment = request.catalogSegment()
        val draft = bar(tenantId, segment).chosen ?: return ServerResponse.notFound().build()
        return try {
            DeleteCatalogVersion(tenantId.key, segment.key, draft.version).execute()
            ServerResponse.status(303).header("Location", CatalogPages.browse(tenantId, segment.at(null))).build()
        } catch (e: CatalogVersionHeldException) {
            catalogs.browseWithError(request, segment, e.message ?: "The draft cannot be discarded.")
        } catch (e: CatalogVersionReleasedException) {
            catalogs.browseWithError(request, segment.at(null), e.message ?: "The version is released.")
        }
    }

    private fun bar(tenantId: TenantId, segment: CatalogSegment): CatalogDraftBar = CatalogDraftBar.of(tenantId.key, segment) { CatalogPages.browse(tenantId, it) }

    /** The segment of draft [version] after it was opened: plain when it is the current (highest) draft. */
    private fun landing(tenantId: TenantId, segment: CatalogSegment, version: String): CatalogSegment {
        val highest = ListCatalogVersions(tenantId.key, segment.key, CatalogVersionStatus.DRAFT).query().firstOrNull()?.version
        return if (highest == version) segment.at(null) else segment.at(version)
    }
}
