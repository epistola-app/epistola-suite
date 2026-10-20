// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.versions.CatalogDraftInUseException
import app.epistola.suite.catalog.versions.CatalogDraftIsNextException
import app.epistola.suite.catalog.versions.CatalogDraftNameInUseException
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionHeldException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.EnvironmentDeploymentRefusedException
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.environments.commands.UndeployCatalogVersion
import app.epistola.suite.environments.queries.ListDraftDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
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
 * Starting and discarding drafts of an authored catalog (contract ADR 0002), from the catalog page. Publishing is
 * [CatalogHandler]; which draft a page works in is the route's catalog segment ([CatalogSegment]).
 */
@Component
class CatalogDraftHandler(
    private val catalogs: CatalogHandler,
) {

    /**
     * `GET /{catalogId}/drafts/new`: the dialog starting parallel work, from the next version or, with
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
     * a draft and lands on its page; a refusal comes back on its field.
     */
    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment().at(null)
        val form = request.form {
            field("name") { maxLength(FieldLimits.MAX_NAME_LENGTH) }
        }

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
        } catch (e: ValidationException) {
            return refused(if (e.field == "from") "from" else "name", e.message)
        }
        val target = CatalogPages.draft(tenantId, segment.at(created.versionId))
        return request.htmx {
            dialogLocation(target)
            onFullPage { redirect(target) }
        }
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
            redirect(back)
        } catch (e: CatalogVersionHeldException) {
            catalogs.browseWithError(request, segment, e.message ?: "The draft cannot be discarded.")
        }
    }

    /**
     * `GET /{catalogId}/follow` at a draft: the dialog for trying it on an environment, which then
     * uses it, live, as its latest version of the catalog (an override). Production environments are
     * listed, not offered: they use only published versions.
     */
    fun followForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
        val segment = request.catalogSegment()
        val bar = bar(tenantId, segment)
        if (!bar.offerFollow) return ServerResponse.notFound().build()
        return request.htmx {
            fragment("catalogs/drafts", "follow-dialog") { followModel(tenantId, segment, bar) }
            onFullPage { redirect(CatalogPages.draft(tenantId, segment)) }
        }
    }

    /**
     * `POST /{catalogId}/follow` at a draft, with `environment`: that environment follows the draft;
     * `POST /{catalogId}/unfollow` with `environment` ends it. Both land back on the draft.
     */
    fun follow(request: ServerRequest): ServerResponse = changeFollowing(request, follow = true)

    fun unfollow(request: ServerRequest): ServerResponse = changeFollowing(request, follow = false)

    private fun changeFollowing(request: ServerRequest, follow: Boolean): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
        val segment = request.catalogSegment()
        val bar = bar(tenantId, segment)
        val draftId = bar.chosen?.versionId?.takeIf { bar.offerFollow } ?: return ServerResponse.notFound().build()
        val environment = request.param("environment").orElse(null)?.let { runCatching { EnvironmentKey.of(it) }.getOrNull() }
            ?: return ServerResponse.badRequest().build()
        val environmentId = EnvironmentId(environment, tenantId)
        return try {
            // Trying a draft on an environment is deploying it there (contract ADR 0003).
            if (follow) {
                DeployCatalogVersion(environmentId, segment.key, draftId).execute()
            } else {
                UndeployCatalogVersion(environmentId, segment.key, draftId).execute()
            }
            val target = CatalogPages.draft(tenantId, segment)
            request.htmx {
                dialogLocation(target)
                onFullPage { redirect(target) }
            }
        } catch (e: EnvironmentDeploymentRefusedException) {
            request.htmx {
                fragment("catalogs/drafts", "follow-dialog") {
                    followModel(tenantId, segment, bar)
                    "error" to e.message
                }
                reswap(HxSwap.INNER_HTML)
                status(422)
                onFullPage { redirect(CatalogPages.draft(tenantId, segment)) }
            }
        }
    }

    private fun ModelBuilder.followModel(tenantId: TenantId, segment: CatalogSegment, bar: CatalogDraftBar) {
        val drafts = ListDraftDeployments(tenantId.key, catalogKey = segment.key).query().associateBy { it.environmentKey }
        "tenantId" to tenantId.key
        "segment" to segment.value
        "draftName" to bar.chosen?.label
        "environments" to ListEnvironments(tenantId).query().map { environment ->
            val deployed = drafts[environment.id]
            FollowEnvironmentView(
                key = environment.id.value,
                name = environment.name,
                production = environment.production,
                following = deployed != null && deployed.draftId == bar.chosen?.versionId,
                usesNow = deployed?.draftLabel,
            )
        }
    }

    /**
     * `GET /{catalogId}/switch?from=<page>` to a version: the page someone was on, in that version
     * ([VersionSwitch]); `from` is set by nav.js when the version menu is used, the referrer stands in
     * without it, and the version's overview when neither names a page of this catalog.
     */
    fun switchVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val from = request.param("from").orElse(null)
            ?: request.headers().firstHeader("Referer")?.let { runCatching { java.net.URI(it) }.getOrNull() }
                ?.takeIf { it.host == null || it.host == request.uri().host }
                ?.let { uri -> uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "") }
        val target = from?.let { VersionSwitch.samePage(it, tenantId.key.value, segment) }
            ?: if (segment.draft == null) CatalogPages.browse(tenantId, segment) else CatalogPages.draft(tenantId, segment)
        // A version switch changes the whole workspace: a boosted click loads the page, not a fragment.
        return request.htmx {
            dialogRedirect(target)
            onFullPage { redirect(target) }
        }
    }

    private fun bar(tenantId: TenantId, segment: CatalogSegment): CatalogDraftBar = CatalogDraftBar.of(tenantId.key, segment) { CatalogPages.draft(tenantId, it) }
}

/**
 * An environment in the dialog for trying a draft on one.
 *
 * @property following it follows this draft already.
 * @property usesNow what its override names now (`v1.0.0`, or a draft's name); null when it uses the
 *   highest release it serves.
 */
data class FollowEnvironmentView(
    val key: String,
    val name: String,
    val production: Boolean,
    val following: Boolean,
    val usesNow: String?,
)
