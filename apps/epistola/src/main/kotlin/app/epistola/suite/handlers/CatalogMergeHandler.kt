// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.merge.DraftMergePreview
import app.epistola.suite.catalog.merge.MergeBaseMissingException
import app.epistola.suite.catalog.merge.MergeChange
import app.epistola.suite.catalog.merge.MergeConflictsUnresolvedException
import app.epistola.suite.catalog.merge.MergeNotApplicableException
import app.epistola.suite.catalog.merge.MergeOutcome
import app.epistola.suite.catalog.merge.MergePreviewOutdatedException
import app.epistola.suite.catalog.merge.MergeRefusedException
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.MergeResolution
import app.epistola.suite.catalog.merge.MergeResource
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * One resource of the merge dialog, in words: what it is called, what kind it is, and what the
 * merge does with it. [field] is the radio group's name for a conflict.
 */
data class MergeRowView(
    val contentKey: String,
    val kind: String,
    val name: String,
    /** What happened to it, as a short sentence (`Changed in v1.1.0`). */
    val what: String,
    /** For a conflict, the choice that takes the published version's side, in words. */
    val takeLabel: String? = null,
    /** For a conflict, the choice that keeps the draft's side, in words. */
    val keepLabel: String? = null,
) {
    val field: String get() = "${CatalogMergeHandler.RESOLUTION_PREFIX}$contentKey]"
}

/**
 * Merging a published version into the draft a page works in (ADR 0032, section 5; alpha), from
 * the "Published since you started" list on the draft's page (`catalogs/next-release.html`). The
 * dialog previews what merging does per resource ([PreviewDraftMerge]) and asks a side for each
 * conflict; submitting runs [MergeReleaseIntoDraft].
 */
@Component
class CatalogMergeHandler {

    /** `GET /{catalogId}/merge?release=1.1.0`: the preview, as a dialog. */
    fun mergeDialog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        // Merging changes the draft's content: the dialog is offered to whoever may do that (E9).
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        val release = request.param("release").orElse("").trim()
        if (release.isEmpty()) return ServerResponse.badRequest().build()
        val preview = PreviewDraftMerge(tenantId.key, segment.key, release, segment.ref(tenantId.key)).query()
        return request.htmx {
            fragment("catalogs/merge", "merge-dialog") {
                mergeModel(tenantId, segment, preview, choices = emptyMap(), error = null)
            }
            onFullPage { redirect(CatalogPages.draft(tenantId, segment)) }
        }
    }

    /**
     * `POST /{catalogId}/merge` with `release`, the preview's `token`, and, per conflict,
     * `resolution[<type>/<key>]` set to `OURS` or `THEIRS`. On success lands on the draft's page,
     * which no longer lists the version as missing; a refusal shows the dialog again with why, and the
     * choices already made. When the draft or the release changed since the dialog was opened, the
     * merge is refused and the dialog shows the merge as it is now.
     */
    fun merge(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        val release = request.param("release").orElse("").trim()
        if (release.isEmpty()) return ServerResponse.badRequest().build()
        val choices = resolutions(request)
        val token = request.param("token").orElse("").trim().ifEmpty { null }

        fun reRender(message: String): ServerResponse {
            val preview = PreviewDraftMerge(tenantId.key, segment.key, release, segment.ref(tenantId.key)).query()
            return request.htmx {
                dialogFieldErrors(
                    template = "catalogs/merge",
                    fragmentName = "merge-form",
                    formTarget = "#catalog-merge-form",
                    formData = FormData(emptyMap(), mapOf("merge" to message)),
                ) {
                    mergeModel(tenantId, segment, preview, choices, error = message)
                }
                onFullPage { redirect(CatalogPages.draft(tenantId, segment)) }
            }
        }

        try {
            MergeReleaseIntoDraft(tenantId.key, segment.key, release, choices, segment.ref(tenantId.key), expectedToken = token).execute()
        } catch (e: MergePreviewOutdatedException) {
            return reRender("Something changed in this draft or in v$release since you opened this. Check the merge below again, then merge.")
        } catch (e: MergeConflictsUnresolvedException) {
            return reRender(
                if (e.conflicts.size == 1) {
                    "Choose which one to keep for the change made on both sides."
                } else {
                    "Choose which one to keep for each of the ${e.conflicts.size} changes made on both sides."
                },
            )
        } catch (e: MergeBaseMissingException) {
            return reRender(e.message ?: "This installation does not have the release both come from.")
        } catch (e: MergeRefusedException) {
            return reRender(e.message)
        } catch (e: MergeNotApplicableException) {
            return reRender(e.message)
        } catch (e: CatalogReadOnlyException) {
            return reRender(e.message ?: "This catalog cannot be changed here.")
        }

        // The draft's own page, rebuilt: what was taken shows among its changes, and the version is
        // no longer listed as missing.
        val draftPage = CatalogPages.draft(tenantId, segment)
        return request.htmx {
            dialogLocation(draftPage)
            onFullPage { redirect(draftPage) }
        }
    }

    /** The side chosen per conflict, from `resolution[<type>/<key>]` fields; anything else is ignored. */
    private fun resolutions(request: ServerRequest): Map<String, MergeResolution> = request.params().entries
        .filter { (name, _) -> name.startsWith(RESOLUTION_PREFIX) && name.endsWith("]") && name.length > RESOLUTION_PREFIX.length + 1 }
        .mapNotNull { (name, values) ->
            val side = MergeResolution.SIDES.firstOrNull { it.wire == values.firstOrNull()?.trim() } ?: return@mapNotNull null
            name.removePrefix(RESOLUTION_PREFIX).removeSuffix("]") to side
        }
        .toMap()

    private fun ModelBuilder.mergeModel(
        tenantId: TenantId,
        segment: CatalogSegment,
        preview: DraftMergePreview,
        choices: Map<String, MergeResolution>,
        error: String?,
    ) {
        val release = "v${preview.release}"
        "tenantId" to tenantId.key
        "catalogId" to segment.value
        "preview" to preview
        "releaseLabel" to release
        "taken" to preview.taken.map { row(it, theirsWords(it.theirs, release)) }
        "kept" to preview.resources.filter { it.outcome == MergeOutcome.OURS }.map { row(it, oursWords(it.ours)) }
        "conflicts" to preview.conflicts.map { conflict ->
            row(conflict, "${oursWords(conflict.ours)}, and it was ${changeWord(conflict.theirs)} in $release").copy(
                keepLabel = if (conflict.ours == MergeChange.REMOVED) "Keep it removed" else "Keep yours",
                takeLabel = if (conflict.theirs == MergeChange.REMOVED) "Remove it, as $release did" else "Take $release’s",
            )
        }
        "untouchedCount" to preview.resources.count { it.outcome == MergeOutcome.UNCHANGED || it.outcome == MergeOutcome.SAME }
        // By field name, as the radios are named: SpEL reads a plain map most simply.
        "choices" to LinkedHashMap(choices.entries.associate { (key, side) -> Pair("$RESOLUTION_PREFIX$key]", side.wire) })
        "mergeError" to error
    }

    private fun row(resource: MergeResource, what: String) = MergeRowView(
        contentKey = resource.contentKey,
        kind = CatalogReleaseReadiness.typeLabel(resource.type),
        name = resource.name ?: resource.key,
        what = what,
    )

    private fun theirsWords(change: MergeChange?, release: String): String = when (change) {
        MergeChange.ADDED -> "Added in $release"
        MergeChange.REMOVED -> "Removed in $release"
        else -> "Changed in $release"
    }

    private fun oursWords(change: MergeChange?): String = when (change) {
        MergeChange.ADDED -> "You added it here"
        MergeChange.REMOVED -> "You removed it here"
        else -> "You changed it here"
    }

    private fun changeWord(change: MergeChange?): String = when (change) {
        MergeChange.ADDED -> "added"
        MergeChange.REMOVED -> "removed"
        else -> "changed"
    }

    companion object {
        /** A conflict's form field is `resolution[<type>/<key>]`. */
        const val RESOLUTION_PREFIX = "resolution["
    }
}
