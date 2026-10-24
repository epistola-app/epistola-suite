// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.commands.ContinueEverydayWork
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.merge.GetDraftMergeSides
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.MergeResolution
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.releases.AssessDraftNumber
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DeleteCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.releases.ListReleasesByContentHash
import app.epistola.suite.catalog.releases.RenameCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.CatalogReleaseInfo
import app.epistola.suite.mcp.dto.DraftMergeInfo
import app.epistola.suite.mcp.dto.MergeSidesInfo
import app.epistola.suite.mcp.dto.MergedReleaseInfo
import app.epistola.suite.mcp.dto.ReleaseNumberInfo
import app.epistola.suite.mcp.dto.ReleasedDraftInfo
import app.epistola.suite.mcp.support.mcpCatalog
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The versions of a catalog that can be changed here (contract ADR 0002): its drafts, each released on its own, and
 * its releases. Every version has a `versionId`, which a draft keeps when it is released. Which draft
 * is the next release, where everyday work goes and which the content tools address by default, is a
 * convention of this suite. Each tool dispatches the command the UI and REST API use, so the same
 * refusals apply.
 */
@Component
class CatalogReleaseMcpTools(private val objectMapper: ObjectMapper) {

    @McpTool(
        name = "list_catalog_releases",
        description = "List a catalog's drafts and releases: the next release first (`isNext`, where everyday work " +
            "goes, and the one the content tools use when `catalogVersion` is omitted), then the other drafts, then its " +
            "releases, highest first. Every draft and release has a `versionId`; a draft has no `version` number until it is released. " +
            "Pass a draft's `versionId` as `catalogVersion` to the content tools to read or change it. A draft lists the " +
            "releases it is `behind` for the number it would be released as: it does not contain their changes. " +
            "With `contentHash`, only the releases with that content (none: an empty list).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCatalogReleases(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "`DRAFT` or `RELEASED` to list only those. Omit for both.", required = false)
        status: String?,
        @McpToolParam(description = "A release's `contentHash` (`v6:` and 64 hex digits) to list only the releases with that content.", required = false)
        contentHash: String? = null,
    ): List<CatalogReleaseInfo> {
        val wanted = contentHash?.trim()?.takeIf { it.isNotEmpty() }
        // As REST `listCatalogReleases?contentHash=`: a filter, and anything but a content hash is refused.
        if (wanted != null && !(CONTENT_HASH.matches(wanted) && CatalogCanonicalizer.isContentHash(wanted))) {
            throw ValidationException("contentHash", "'$wanted' is not a content hash: `v6:` followed by 64 lowercase hex digits, as a release's `contentHash`")
        }
        val only = status(status)
        val releases = when {
            wanted == null -> ListCatalogReleases(mcpCatalog(catalogId), only, withBehind = true).query()

            // A draft has no content hash.
            only == CatalogReleaseStatus.DRAFT -> emptyList()

            // Found in the database, as REST does it.
            else -> ListReleasesByContentHash(mcpCatalog(catalogId), wanted).query().items
        }
        return releases.map(CatalogReleaseInfo::from)
    }

    @McpTool(
        name = "start_draft",
        description = "Start a draft of a catalog that can be changed here, named (e.g. \"Redesign 2027\" or \"Fixes 1.4\"), released on " +
            "its own. With `from` a release (its number), it starts as a copy of that release, its base: a fix to an older " +
            "line is a draft from a release of that line. With `from` a draft (`next` or its `versionId`), it starts as a " +
            "copy of that draft, unreleased changes included. Without `from`, a copy of the highest release. Fails when " +
            "another draft has the name. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun startDraft(
        @McpToolParam(description = "Catalog key of a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "What people call the draft. Unique among the catalog's drafts.")
        name: String,
        @McpToolParam(description = "A release number, `next`, or a draft's `versionId` to copy. From `list_catalog_releases`.", required = false)
        from: String?,
    ): CatalogReleaseInfo = CatalogReleaseInfo.from(
        CreateCatalogDraft(mcpCatalog(catalogId), name, from?.trim()?.takeIf { it.isNotEmpty() }).execute(),
    )

    @McpTool(
        name = "rename_draft",
        description = "Give a draft another name; its `versionId` does not change. The next release has no name of its own. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun renameDraft(
        @McpToolParam(description = "Catalog key of a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "The draft's `versionId`. From `list_catalog_releases`.")
        draft: String,
        @McpToolParam(description = "The new name.")
        name: String,
    ): CatalogReleaseInfo = CatalogReleaseInfo.from(
        RenameCatalogDraft(mcpCatalog(catalogId), draftRefOf(draft), name).execute(),
    )

    @McpTool(
        name = "discard_draft",
        description = "Discard a draft of a catalog that can be changed here and everything changed in it. Discarding the next release " +
            "(`next`) throws its changes away: it starts again as a copy of the latest release. Releases cannot be " +
            "deleted here. Refused while another catalog's draft builds on it, and while the draft alone holds something " +
            "used outside it, such as the tenant's default theme; the error names it. Confirm with the user first: this " +
            "cannot be undone. Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
    )
    fun discardDraft(
        @McpToolParam(description = "Catalog key of a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "`next`, or a draft's `versionId`. From `list_catalog_releases`.")
        draft: String,
    ): List<CatalogReleaseInfo> {
        val catalog = mcpCatalog(catalogId)
        val catalogKey = catalog.key
        DeleteCatalogDraft(catalog, draftRefOf(draft)).execute()
        return ListCatalogReleases(catalog).query().map(CatalogReleaseInfo::from)
    }

    @McpTool(
        name = "preview_release_number",
        description = "The number releasing a draft would give it, without releasing: from the kind of change against " +
            "its base (`NONE` a patch, `ADDITIVE` a minor, `BREAKING` a major), the number that needs (`needed`) and the " +
            "number it gets (`suggested`: the next free one of that kind when another release has it), with the " +
            "`reasons` that make it more than a fix. Show the user the number and the reasons before releasing.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun previewReleaseNumber(
        @McpToolParam(description = "Catalog key of a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "`next` (the default) or a draft's `versionId`.", required = false)
        draft: String?,
    ): ReleaseNumberInfo {
        val catalog = mcpCatalog(catalogId)
        val catalogKey = catalog.key
        val ref = draft?.takeIf { it.isNotBlank() }?.let(::draftRefOf) ?: DraftRef.Next
        return ReleaseNumberInfo.from(AssessDraftNumber(catalog, ref).query() ?: throw CatalogReleaseNotFoundException(catalogKey, ref.toString()))
    }

    @McpTool(
        name = "release_draft",
        description = "Release a draft of a catalog that can be changed here (the next release unless `draft` names another), numbered " +
            "as its changes need against its base (`preview_release_number`) unless `version` asks for a higher free " +
            "number. A draft that changed nothing against its base (no resource, no catalog-level content) has nothing " +
            "to release and is refused. Its changed resources must be marked ready (`get_catalog_changes`). A draft behind releases is " +
            "released only when `acknowledgedBehind` names every one. One that builds on drafts of other catalogs is " +
            "refused unless `releaseDependencies` releases those first. The draft becomes the release, keeping its " +
            "`versionId`; the next release then continues on it. Parallel work released with `continueEverydayWork` " +
            "becomes the next release on its release instead, and the previous next release is discarded with its " +
            "changes, or kept as parallel work under `keepPreviousAs`; nothing is merged, so confirm with the user. A release is immutable and can be deployed for " +
            "generation, so release only when the user asks to. Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun releaseDraft(
        @McpToolParam(description = "Catalog key of a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "`next` (the default) or a draft's `versionId`.", required = false)
        draft: String?,
        @McpToolParam(description = "A higher free number than its changes need, MAJOR.MINOR.PATCH, to mark e.g. a major on purpose. Omit for the suggested number.", required = false)
        version: String?,
        @McpToolParam(description = "Release notes.", required = false)
        notes: String?,
        @McpToolParam(description = "The releases the user confirms the draft may be behind.", required = false)
        acknowledgedBehind: List<String>?,
        @McpToolParam(description = "Also release the drafts of other catalogs this draft builds on, first.", required = false)
        releaseDependencies: Boolean?,
        @McpToolParam(description = "Releasing parallel work: everyday work continues from this release, which becomes the next release.", required = false)
        continueEverydayWork: Boolean? = null,
        @McpToolParam(description = "With `continueEverydayWork`: keep the previous next release as parallel work under this name. Omit to discard it.", required = false)
        keepPreviousAs: String? = null,
    ): ReleasedDraftInfo {
        val catalog = mcpCatalog(catalogId)
        val catalogKey = catalog.key
        val ref = draft?.takeIf { it.isNotBlank() }?.let(::draftRefOf)
        return ReleasedDraftInfo.from(
            ReleaseCatalogDraft(
                catalog = catalog,
                version = version?.trim()?.takeIf { it.isNotEmpty() },
                notes = notes?.takeIf { it.isNotBlank() },
                acknowledgedBehind = acknowledgedBehind.orEmpty().map { it.trim() }.toSet(),
                catalogVersion = ref?.let { CatalogReleaseRef(catalog, it) },
                releaseDependencies = releaseDependencies == true,
                continueEverydayWork = if (continueEverydayWork == true) ContinueEverydayWork(keepPreviousAs?.trim()?.takeIf { it.isNotEmpty() }) else null,
                // As REST: a draft that changed nothing against its base has nothing to release.
                requireChange = true,
            ).execute(),
        )
    }

    @McpTool(
        name = "preview_merge",
        description = "Alpha. What merging a release of the catalog into a draft (the next release unless `draft` names " +
            "another) would do, without changing anything: per resource a side changed, `THEIRS` (only the release " +
            "changed it: taken), `OURS` (only the draft did: it stays) or `CONFLICT` (both did: the user chooses). The " +
            "catalog's settings are entries too: `catalog/defaultTheme`, `catalog/presets`, and one `catalogDependency/<catalogKey>` " +
            "per catalog it uses (adding, removing or re-pinning one is a change of its own). An entry's `needs` names the " +
            "entries it goes with (a template's theme, the dependency a reference resolves through): choose them together. Use it " +
            "for a release the draft is `behind` (`list_catalog_releases`), and show the user the conflicts before " +
            "`merge_release`; pass its `token` there. For a conflict whose sides both made changes worth keeping, read them " +
            "with `get_merge_sides` and propose merged content to the user. When this installation lacks the release both come from, " +
            "`missingBase` names it and nothing is compared: get it first. Without any release both come from, every " +
            "difference is a `CONFLICT`; `notice` says either.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun previewMerge(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The release to merge in, its number. From `list_catalog_releases`.")
        release: String,
        @McpToolParam(description = "`next` (the default) or a draft's `versionId`.", required = false)
        draft: String?,
    ): DraftMergeInfo {
        val catalog = mcpCatalog(catalogId)
        val catalogKey = catalog.key
        return DraftMergeInfo.from(PreviewDraftMerge(catalog, release.trim(), draftScope(catalog, draft)).query())
    }

    @McpTool(
        name = "get_merge_sides",
        description = "Alpha. The three sides of one entry of a merge (from `preview_merge`), to combine a CONFLICT instead of " +
            "choosing a side: `base` (the entry in the release both come from, `mergeBase`), `draft` (ours) and `release` " +
            "(theirs), each in the portable catalog format, null where that side does not have it. A resource is the " +
            "`resource` object of its resource detail (with its `type` and `key`); `catalog/defaultTheme` is " +
            "`{\"defaultTheme\": …}`, `catalog/presets` `{\"presets\": […]}`, and `catalogDependency/<catalogKey>` the " +
            "declaration (`registry`, `namespace`, `catalogKey`, `releaseVersion`, `releaseFingerprint`, or `releaseId` " +
            "naming a draft). Compare `draft` and `release` against `base` to see what each side changed, combine both " +
            "changes into one object of the same form, show it to the user, and only once the user agrees pass it to " +
            "`merge_release` as `mergedContent`, with this `token`. Changes nothing.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getMergeSides(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The release to merge in, its number, as given to `preview_merge`.")
        release: String,
        @McpToolParam(description = "The entry, `type/key`, as `preview_merge` lists it (`template/invoice`, `catalog/presets`).")
        entry: String,
        @McpToolParam(description = "`next` (the default) or a draft's `versionId`.", required = false)
        draft: String?,
    ): MergeSidesInfo {
        val catalog = mcpCatalog(catalogId)
        val catalogKey = catalog.key
        return MergeSidesInfo.from(GetDraftMergeSides(catalog, release.trim(), entry.trim(), draftScope(catalog, draft)).query())
    }

    @McpTool(
        name = "merge_release",
        description = "Alpha. Merge a release of the catalog into a draft (the next release unless `draft` names another): " +
            "what only the release changed is taken from it, what only the draft changed stays, and every `CONFLICT` from " +
            "`preview_merge` is resolved by `resolutions` (a side) or by `mergedContent` (content combined from " +
            "`get_merge_sides`), which the user chooses and agrees to; one of the two per conflict, not both. Merged " +
            "content is written as given and is not marked ready: review it with the user, and mark it ready, before " +
            "releasing the draft. Afterwards the draft is no longer behind the release. Refused when a conflict has no " +
            "resolution, when merged content is not valid for its entry (the error names `mergedContent.<type/key>` and " +
            "the path in it), when the result would hold a reference that does not resolve (a removal still used, or " +
            "merged content naming what the result lacks), when the draft already contains the release, when this " +
            "installation lacks the release both come from (get it first), and when `token` is given and the draft or " +
            "the release changed since that preview (preview again). Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun mergeRelease(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The release to merge in, its number.")
        release: String,
        @McpToolParam(description = "`next` (the default) or a draft's `versionId`.", required = false)
        draft: String?,
        @McpToolParam(description = "For each conflict, its `resource` (`type/key`) to `OURS` (keep the draft's) or `THEIRS` (take the release's).", required = false)
        resolutions: Map<String, String>?,
        @McpToolParam(description = "The `token` of the `preview_merge` (or `get_merge_sides`) the user confirmed; the merge is refused if anything changed since.", required = false)
        token: String? = null,
        @McpToolParam(
            description = "For a conflict resolved to merged content instead of a side, its `resource` (`type/key`) to the entry " +
                "as the draft is to hold it: an object in the form `get_merge_sides` returns a side (or that object as a JSON string).",
            required = false,
        )
        mergedContent: Map<String, Any?>? = null,
    ): MergedReleaseInfo {
        val catalog = mcpCatalog(catalogId)
        val catalogKey = catalog.key
        val sides = resolutions.orEmpty().mapValues { (resource, side) ->
            MergeResolution.ofWire(side)
                ?: throw ValidationException("resolutions", "The resolution of `$resource` is `OURS` or `THEIRS`")
        }
        val content = mergedContent.orEmpty().mapValues { (entry, value) -> contentObject(entry, value) }
        val scope = draftScope(catalog, draft)
        val result = MergeReleaseIntoDraft(catalog, release.trim(), sides, scope, expectedToken = token?.trim()?.ifEmpty { null }, mergedContent = content).execute()
        val unused = GetCatalogBorrowing(catalog, scope).query().unused.map { it.alias }
        return MergedReleaseInfo.from(result, unused)
    }

    /** [value], merged content for [entry], as an object: given as one, or as a JSON object string. */
    @Suppress("UNCHECKED_CAST")
    private fun contentObject(entry: String, value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value as Map<String, Any?>

        is String -> try {
            objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
        } catch (e: tools.jackson.core.JacksonException) {
            throw ValidationException("mergedContent.$entry", "The merged content of `$entry` is a JSON object")
        }

        else -> throw ValidationException("mergedContent.$entry", "The merged content of `$entry` is a JSON object")
    }

    private fun draftScope(catalog: app.epistola.suite.common.ids.CatalogId, draft: String?): CatalogReleaseRef? = draft?.takeIf { it.isNotBlank() }?.let { CatalogReleaseRef(catalog, draftRefOf(it)) }

    private fun draftRefOf(raw: String): DraftRef = DraftRef.parse(raw.trim())
        ?: throw ValidationException("draft", "`draft` is `next` or a draft's `versionId` from `list_catalog_releases`")

    private fun status(raw: String?): CatalogReleaseStatus? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CatalogReleaseStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ValidationException("status", "`status` is `DRAFT` or `RELEASED`")
    }

    private companion object {
        /** The contract's `ContentHash` pattern. */
        val CONTENT_HASH = Regex("^v6:[0-9a-f]{64}$")
    }
}
