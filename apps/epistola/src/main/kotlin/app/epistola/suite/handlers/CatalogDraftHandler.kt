// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.CatalogReleaseNotRetainedException
import app.epistola.suite.catalog.versions.CatalogDraftIsNextException
import app.epistola.suite.catalog.versions.CatalogDraftNameInUseException
import app.epistola.suite.catalog.versions.CatalogVersionHeldException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.OpenNextVersion
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
import app.epistola.suite.validation.FieldLimits
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Starting and discarding drafts of an authored catalog (contract ADR 0002, amendment 2), from the
 * catalog page and from the empty state of a catalog without a next version. Publishing is
 * [CatalogHandler]; which draft a page works in is the route's catalog segment ([CatalogSegment]).
 */
@Component
class CatalogDraftHandler(
    private val catalogs: CatalogHandler,
) {

    /**
     * `GET /{catalogId}/drafts/new`: the dialog starting long-term work, from the next version or, with
     * `from`, the published version it was opened on.
     */
    fun createForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment().at(null)
        val from = request.param("from").orElse("").trim()
        return request.htmx {
            fragment("catalogs/drafts", "create-dialog") {
                "tenantId" to tenantId.key
                "bar" to bar(tenantId, segment)
                "formData" to (if (from.isEmpty()) emptyMap() else mapOf(Pair("from", from)))
            }
            onFullPage { redirect(CatalogPages.browse(tenantId, segment)) }
        }
    }

    /**
     * `POST /{catalogId}/drafts`. With `name` (and optionally `from`, a published version) it starts
     * long-term work and lands on its page; a refusal comes back on its field. Without one, from the
     * empty state of a catalog without a next version (a plain form), it opens the next version on the
     * latest release, and a refusal comes back on the catalog page.
     */
    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment().at(null)
        val form = request.form {
            field("name") { maxLength(FieldLimits.MAX_NAME_LENGTH) }
        }
        val fromDialog = request.headers().firstHeader("HX-Target") == "create-draft-form"
        if (!fromDialog) return openNext(request, tenantId, segment)

        fun refused(field: String, message: String): ServerResponse = request.htmx {
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

        if (form.hasErrors()) return refused("name", "A name is at most ${FieldLimits.MAX_NAME_LENGTH} characters.")
        val from = form.formData["from"]?.trim()?.ifBlank { null }
        val created = try {
            CreateCatalogDraft(tenantId.key, segment.key, form.formData["name"].orEmpty(), from).execute()
        } catch (e: CatalogDraftNameInUseException) {
            return refused("name", "Other work is called that already. Choose another name.")
        } catch (e: CatalogVersionNotFoundException) {
            return refused("from", e.message ?: "There is no such version to start from.")
        } catch (e: CatalogReleaseNotRetainedException) {
            return refused("from", e.message ?: "That published version kept no content to start from.")
        } catch (e: ValidationException) {
            return refused(if (e.field == "from") "from" else "name", e.message)
        }
        val target = CatalogPages.draft(tenantId, segment.at(created.draftId))
        return request.htmx {
            dialogLocation(target)
            onFullPage { redirect(target) }
        }
    }

    /** Opens the next version where there is none, and lands on it. */
    private fun openNext(request: ServerRequest, tenantId: TenantId, segment: CatalogSegment): ServerResponse = try {
        OpenNextVersion(tenantId.key, segment.key).execute()
        ServerResponse.status(303).header("Location", CatalogPages.draft(tenantId, segment)).build()
    } catch (e: CatalogReleaseNotRetainedException) {
        catalogs.browseWithError(request, segment, e.message ?: "The latest published version kept no content to start from.")
    } catch (e: CatalogDraftIsNextException) {
        ServerResponse.status(303).header("Location", CatalogPages.draft(tenantId, segment)).build()
    }

    /**
     * `POST /{catalogId}/drafts/discard`: discards the draft the segment names (or the next version)
     * and lands on the catalog page. Discarding the next version throws its changes away: it starts
     * again on the latest release. A refusal, such as the draft alone holding the tenant's default
     * theme, is shown on the catalog page in the draft.
     */
    fun discard(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_RELEASE)
        val segment = request.catalogSegment()
        val draft = bar(tenantId, segment).chosen?.draftRef ?: return ServerResponse.notFound().build()
        return try {
            DeleteCatalogDraft(tenantId.key, segment.key, draft).execute()
            // From the Versions page, back there; else to the catalog.
            val back = if (request.param("from").orElse("") == "versions") {
                "/tenants/${tenantId.key}/catalogs/${segment.key.value}/versions"
            } else {
                CatalogPages.browse(tenantId, segment.at(null))
            }
            ServerResponse.status(303).header("Location", back).build()
        } catch (e: CatalogVersionHeldException) {
            catalogs.browseWithError(request, segment, e.message ?: "The draft cannot be discarded.")
        }
    }

    private fun bar(tenantId: TenantId, segment: CatalogSegment): CatalogDraftBar = CatalogDraftBar.of(tenantId.key, segment) { CatalogPages.draft(tenantId, it) }
}
