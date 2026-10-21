// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.commands.ContinueEverydayWork
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.MergeSide
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.versions.AssessDraftNumber
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.catalog.versions.RenameCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.CatalogReleaseInfo
import app.epistola.suite.mcp.dto.CatalogVersionInfo
import app.epistola.suite.mcp.dto.DraftMergeInfo
import app.epistola.suite.mcp.dto.MergedReleaseInfo
import app.epistola.suite.mcp.dto.ReleaseNumberInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * The versions of an AUTHORED catalog (contract ADR 0002): its drafts, each released on its own, and
 * its releases. Every version has a `versionId`, which a draft keeps when it is released. Which draft
 * is the next version, where everyday work goes and which the content tools address by default, is a
 * convention of this suite. Each tool dispatches the command the UI and REST API use, so the same
 * refusals apply.
 */
@Component
class CatalogVersionMcpTools {

    @McpTool(
        name = "list_catalog_versions",
        description = "List a catalog's versions: its drafts, the next version first (`isNext`, where everyday work " +
            "goes, and the one the content tools use when `catalogVersion` is omitted), then the other drafts, then its " +
            "releases, highest first. Every version has a `versionId`; a draft has no `version` until it is released. " +
            "Pass a draft's `versionId` as `catalogVersion` to the content tools to read or change it. A draft lists the " +
            "releases it is `behind` for the number it would be released as: it does not contain their changes.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCatalogVersions(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "`DRAFT` or `RELEASED` to list only those. Omit for both.", required = false)
        status: String?,
    ): List<CatalogVersionInfo> = ListCatalogVersions(mcpTenantKey(), CatalogKey.of(catalogId), status(status), withBehind = true).query()
        .map(CatalogVersionInfo::from)

    @McpTool(
        name = "start_draft",
        description = "Start a draft of an AUTHORED catalog, named (e.g. \"Redesign 2027\" or \"Fixes 1.4\"), released on " +
            "its own. With `from` a release (its number), it starts as a copy of that release, its base: a fix to an older " +
            "line is a draft from a release of that line. With `from` a draft (`next` or its `versionId`), it starts as a " +
            "copy of that draft, unreleased changes included. Without `from`, a copy of the highest release. Fails when " +
            "another draft has the name. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun startDraft(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "What people call the draft. Unique among the catalog's drafts.")
        name: String,
        @McpToolParam(description = "A release number, `next`, or a draft's `versionId` to copy. From `list_catalog_versions`.", required = false)
        from: String?,
    ): CatalogVersionInfo = CatalogVersionInfo.from(
        CreateCatalogDraft(mcpTenantKey(), CatalogKey.of(catalogId), name, from?.trim()?.takeIf { it.isNotEmpty() }).execute(),
    )

    @McpTool(
        name = "rename_draft",
        description = "Give a draft another name; its `versionId` does not change. The next version has no name of its own. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun renameDraft(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The draft's `versionId`. From `list_catalog_versions`.")
        draft: String,
        @McpToolParam(description = "The new name.")
        name: String,
    ): CatalogVersionInfo = CatalogVersionInfo.from(
        RenameCatalogDraft(mcpTenantKey(), CatalogKey.of(catalogId), draftRefOf(draft), name).execute(),
    )

    @McpTool(
        name = "discard_draft",
        description = "Discard a draft of an AUTHORED catalog and everything changed in it. Discarding the next version " +
            "(`next`) throws its changes away: it starts again as a copy of the latest release. Releases cannot be " +
            "deleted here. Refused while another catalog's draft builds on it, and while the draft alone holds something " +
            "used outside it, such as the tenant's default theme; the error names it. Confirm with the user first: this " +
            "cannot be undone. Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
    )
    fun discardDraft(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "`next`, or a draft's `versionId`. From `list_catalog_versions`.")
        draft: String,
    ): List<CatalogVersionInfo> {
        val catalogKey = CatalogKey.of(catalogId)
        DeleteCatalogDraft(mcpTenantKey(), catalogKey, draftRefOf(draft)).execute()
        return ListCatalogVersions(mcpTenantKey(), catalogKey).query().map(CatalogVersionInfo::from)
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
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "`next` (the default) or a draft's `versionId`.", required = false)
        draft: String?,
    ): ReleaseNumberInfo {
        val catalogKey = CatalogKey.of(catalogId)
        val ref = draft?.takeIf { it.isNotBlank() }?.let(::draftRefOf) ?: DraftRef.Next
        return ReleaseNumberInfo.from(AssessDraftNumber(mcpTenantKey(), catalogKey, ref).query() ?: throw CatalogVersionNotFoundException(catalogKey, ref.toString()))
    }

    @McpTool(
        name = "release_draft",
        description = "Release a draft of an AUTHORED catalog (the next version unless `draft` names another), numbered " +
            "as its changes need against its base (`preview_release_number`) unless `version` asks for a higher free " +
            "number. Its changed resources must be marked ready (`get_catalog_changes`). A draft behind releases is " +
            "released only when `acknowledgedBehind` names every one. One that builds on drafts of other catalogs is " +
            "refused unless `releaseDependencies` releases those first. The draft becomes the release, keeping its " +
            "`versionId`; the next version then continues on it. Parallel work released with `continueEverydayWork` " +
            "becomes the next version on its release instead, and the previous next version is discarded with its " +
            "changes, or kept as parallel work under `keepPreviousAs`; nothing is merged, so confirm with the user. A release is immutable and can be deployed for " +
            "generation, so release only when the user asks to. Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun releaseDraft(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
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
        @McpToolParam(description = "Releasing parallel work: everyday work continues from this release, which becomes the next version.", required = false)
        continueEverydayWork: Boolean? = null,
        @McpToolParam(description = "With `continueEverydayWork`: keep the previous next version as parallel work under this name. Omit to discard it.", required = false)
        keepPreviousAs: String? = null,
    ): CatalogReleaseInfo {
        val catalogKey = CatalogKey.of(catalogId)
        val ref = draft?.takeIf { it.isNotBlank() }?.let(::draftRefOf)
        return CatalogReleaseInfo.from(
            ReleaseCatalogVersion(
                tenantKey = mcpTenantKey(),
                catalogKey = catalogKey,
                version = version?.trim()?.takeIf { it.isNotEmpty() },
                notes = notes?.takeIf { it.isNotBlank() },
                acknowledgedBehind = acknowledgedBehind.orEmpty().map { it.trim() }.toSet(),
                catalogVersion = ref?.let { CatalogVersionRef(mcpTenantKey(), catalogKey, it) },
                releaseDependencies = releaseDependencies == true,
                continueEverydayWork = if (continueEverydayWork == true) ContinueEverydayWork(keepPreviousAs?.trim()?.takeIf { it.isNotEmpty() }) else null,
            ).execute(),
        )
    }

    @McpTool(
        name = "preview_merge",
        description = "Alpha. What merging a release of the catalog into a draft (the next version unless `draft` names " +
            "another) would do, without changing anything: per resource a side changed, `THEIRS` (only the release " +
            "changed it: taken), `OURS` (only the draft did: it stays) or `CONFLICT` (both did: the user chooses). Use it " +
            "for a release the draft is `behind` (`list_catalog_versions`), and show the user the conflicts before " +
            "`merge_release`.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun previewMerge(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The release to merge in, its number. From `list_catalog_versions`.")
        release: String,
        @McpToolParam(description = "`next` (the default) or a draft's `versionId`.", required = false)
        draft: String?,
    ): DraftMergeInfo {
        val catalogKey = CatalogKey.of(catalogId)
        return DraftMergeInfo.from(PreviewDraftMerge(mcpTenantKey(), catalogKey, release.trim(), draftScope(catalogKey, draft)).query())
    }

    @McpTool(
        name = "merge_release",
        description = "Alpha. Merge a release of the catalog into a draft (the next version unless `draft` names another): " +
            "what only the release changed is taken from it, what only the draft changed stays, and every `CONFLICT` from " +
            "`preview_merge` is resolved by `resolutions`, which the user chooses. Afterwards the draft is no longer behind " +
            "the release. Refused when a conflict has no resolution, when the release removes something the draft still " +
            "uses, and when the draft already contains the release. Requires the TEMPLATE_EDIT permission.",
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
    ): MergedReleaseInfo {
        val catalogKey = CatalogKey.of(catalogId)
        val sides = resolutions.orEmpty().mapValues { (resource, side) ->
            MergeSide.entries.firstOrNull { it.name.equals(side.trim(), ignoreCase = true) }
                ?: throw ValidationException("resolutions", "The resolution of `$resource` is `OURS` or `THEIRS`")
        }
        return MergedReleaseInfo.from(MergeReleaseIntoDraft(mcpTenantKey(), catalogKey, release.trim(), sides, draftScope(catalogKey, draft)).execute())
    }

    private fun draftScope(catalogKey: CatalogKey, draft: String?): CatalogVersionRef? = draft?.takeIf { it.isNotBlank() }?.let { CatalogVersionRef(mcpTenantKey(), catalogKey, draftRefOf(it)) }

    private fun draftRefOf(raw: String): DraftRef = DraftRef.parse(raw.trim())
        ?: throw ValidationException("draft", "`draft` is `next` or a draft's `versionId` from `list_catalog_versions`")

    private fun status(raw: String?): CatalogVersionStatus? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CatalogVersionStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ValidationException("status", "`status` is `DRAFT` or `RELEASED`")
    }
}
