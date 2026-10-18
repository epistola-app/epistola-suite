// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.CatalogReleaseNotRetainedException
import app.epistola.suite.catalog.versions.CatalogDraftIsNextException
import app.epistola.suite.catalog.versions.CatalogDraftNameInUseException
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionBehindException
import app.epistola.suite.catalog.versions.CatalogVersionHeldException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.catalog.versions.OpenNextVersion
import app.epistola.suite.catalog.versions.PromoteCatalogDraft
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.ClearEnvironmentOverride
import app.epistola.suite.environments.EnvironmentOverrideRefusedException
import app.epistola.suite.environments.ListEnvironmentOverrides
import app.epistola.suite.environments.SetEnvironmentOverride
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.ModelBuilder
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

    /** `GET /{catalogId}/promote` at long-term work: the dialog making it the next version. */
    fun promoteForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_RELEASE)
        val segment = request.catalogSegment()
        val bar = bar(tenantId, segment)
        val work = bar.chosen?.takeIf { bar.onLongTermWork } ?: return ServerResponse.notFound().build()
        return request.htmx {
            fragment("catalogs/drafts", "promote-dialog") {
                promoteModel(tenantId, segment, bar, work)
            }
            onFullPage { redirect(CatalogPages.draft(tenantId, segment)) }
        }
    }

    /**
     * `POST /{catalogId}/promote` at long-term work, with `previous` (`keep` with `keepAs`, or
     * `discard`) and `acknowledgedBehind` per release it is behind: makes it the next version and lands
     * on the catalog. A refusal comes back in the dialog.
     */
    fun promote(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_RELEASE)
        val segment = request.catalogSegment()
        val bar = bar(tenantId, segment)
        val work = bar.chosen?.takeIf { bar.onLongTermWork } ?: return ServerResponse.notFound().build()
        val keep = request.param("previous").orElse("keep") != "discard"
        val keepAs = request.param("keepAs").orElse("").trim().ifBlank { null }
        val acknowledged = request.params()["acknowledgedBehind"].orEmpty().map { it.trim() }.toSet()
        fun refused(message: String) = request.htmx {
            fragment("catalogs/drafts", "promote-dialog") {
                promoteModel(tenantId, segment, bar, work)
                "error" to message
            }
            onFullPage { redirect(CatalogPages.draft(tenantId, segment)) }
        }
        if (keep && keepAs == null) return refused("Give the current next version a name to keep it under.")
        return try {
            PromoteCatalogDraft(tenantId.key, segment.key, requireNotNull(work.draftId), keepPreviousAs = keepAs.takeIf { keep }, acknowledgedBehind = acknowledged).execute()
            val target = CatalogPages.browse(tenantId, segment.at(null))
            request.htmx {
                dialogLocation(target)
                onFullPage { redirect(target) }
            }
        } catch (e: CatalogVersionBehindException) {
            refused("Confirm each version published since this work started: " + e.behind.joinToString { "v$it" } + ".")
        } catch (e: CatalogDraftNameInUseException) {
            refused("Other work is called that already. Choose another name.")
        } catch (e: CatalogVersionHeldException) {
            refused(e.message ?: "The next version cannot be discarded.")
        } catch (e: ValidationException) {
            refused(e.message)
        }
    }

    private fun ModelBuilder.promoteModel(tenantId: TenantId, segment: CatalogSegment, bar: CatalogDraftBar, work: CatalogVersion) {
        "tenantId" to tenantId.key
        "bar" to bar
        "segment" to segment.value
        "workName" to work.label
        "behind" to work.behind
        "keepAsSuggestion" to "Next version before ${work.label}".take(100)
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
        val draftId = bar.chosen?.draftId?.takeIf { bar.offerFollow } ?: return ServerResponse.notFound().build()
        val environment = request.param("environment").orElse(null)?.let { runCatching { EnvironmentKey.of(it) }.getOrNull() }
            ?: return ServerResponse.badRequest().build()
        val environmentId = EnvironmentId(environment, tenantId)
        return try {
            if (follow) {
                SetEnvironmentOverride(environmentId, segment.key, draft = DraftRef.Id(draftId)).execute()
            } else {
                ClearEnvironmentOverride(environmentId, segment.key).execute()
            }
            val target = CatalogPages.draft(tenantId, segment)
            request.htmx {
                dialogLocation(target)
                onFullPage { redirect(target) }
            }
        } catch (e: EnvironmentOverrideRefusedException) {
            request.htmx {
                fragment("catalogs/drafts", "follow-dialog") {
                    followModel(tenantId, segment, bar)
                    "error" to e.message
                }
                onFullPage { redirect(CatalogPages.draft(tenantId, segment)) }
            }
        }
    }

    private fun ModelBuilder.followModel(tenantId: TenantId, segment: CatalogSegment, bar: CatalogDraftBar) {
        val overrides = ListEnvironmentOverrides(tenantId.key, catalogKey = segment.key).query().associateBy { it.environmentKey }
        "tenantId" to tenantId.key
        "segment" to segment.value
        "draftName" to bar.chosen?.label
        "environments" to ListEnvironments(tenantId).query().map { environment ->
            val override = overrides[environment.id]
            FollowEnvironmentView(
                key = environment.id.value,
                name = environment.name,
                production = environment.production,
                following = override?.draftId != null && override.draftId == bar.chosen?.draftId,
                usesNow = override?.label,
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
        return if (request.headers().firstHeader("HX-Request") == "true") {
            ServerResponse.ok().header("HX-Redirect", target).build()
        } else {
            ServerResponse.seeOther(java.net.URI.create(target)).build()
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
