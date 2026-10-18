// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.AssessDraftNumber
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.catalog.versions.PromoteCatalogDraft
import app.epistola.suite.catalog.versions.RenameCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.CatalogReleaseInfo
import app.epistola.suite.mcp.dto.CatalogVersionInfo
import app.epistola.suite.mcp.dto.ReleaseNumberInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The versions of an AUTHORED catalog (contract ADR 0002, amendment 2): its next version, where
 * everyday work goes and which the content tools address by default, long-term work next to it, and
 * its releases. A draft is numbered when it is released. Each tool dispatches the command the UI and
 * REST API use, so the same refusals apply.
 */
@Component
class CatalogVersionMcpTools {

    @McpTool(
        name = "list_catalog_versions",
        description = "List a catalog's versions: its drafts, the next version first (`isNext`, where everyday work " +
            "goes, and the one the content tools use when `catalogVersion` is omitted), then long-term work (named, " +
            "kept apart until it is ready), then its releases, highest first. A draft has no `version` until it is " +
            "released; pass a long-term draft's `draftId` as `catalogVersion` to the content tools to read or change " +
            "it. A draft lists the releases it is `behind`: released after its base, so it does not contain their changes.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCatalogVersions(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "`DRAFT` or `RELEASED` to list only those. Omit for both.", required = false)
        status: String?,
    ): List<CatalogVersionInfo> = ListCatalogVersions(mcpTenantKey(), CatalogKey.of(catalogId), status(status)).query()
        .map(CatalogVersionInfo::from)

    @McpTool(
        name = "start_long_term_work",
        description = "Start long-term work on an AUTHORED catalog: a named draft next to the next version, for a " +
            "large change that is not ready to be released with everyday work (e.g. \"Redesign 2027\"). Without " +
            "`from` it starts as a copy of the next version, unreleased changes included; with `from`, a release, as a " +
            "copy of that release. It is not released until it becomes the next version. Fails when another draft has " +
            "the name. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun startLongTermWork(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "What people call the work, e.g. `Redesign 2027`. Unique among the catalog's drafts.")
        name: String,
        @McpToolParam(description = "A release to copy instead of the next version, by its number. From `list_catalog_versions`.", required = false)
        from: String?,
    ): CatalogVersionInfo = CatalogVersionInfo.from(
        CreateCatalogDraft(mcpTenantKey(), CatalogKey.of(catalogId), name, from?.trim()?.takeIf { it.isNotEmpty() }).execute(),
    )

    @McpTool(
        name = "rename_long_term_work",
        description = "Give long-term work another name. The next version has no name of its own. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun renameLongTermWork(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The draft's `draftId`. From `list_catalog_versions`.")
        draftId: String,
        @McpToolParam(description = "The new name.")
        name: String,
    ): CatalogVersionInfo = CatalogVersionInfo.from(
        RenameCatalogDraft(mcpTenantKey(), CatalogKey.of(catalogId), draftIdOf(draftId), name).execute(),
    )

    @McpTool(
        name = "discard_catalog_work",
        description = "Discard a draft of an AUTHORED catalog and everything changed in it. Discarding the next version " +
            "(`next`) throws its changes away: it starts again as a copy of the latest release. Discarding long-term " +
            "work removes it. Releases cannot be deleted here. Refused while the draft alone holds something used " +
            "outside it, such as the tenant's default theme or a code list another catalog's attribute binds; the " +
            "error names it. Confirm with the user first: this cannot be undone. Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
    )
    fun discardCatalogWork(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "`next`, or a long-term draft's `draftId`. From `list_catalog_versions`.")
        draft: String,
    ): List<CatalogVersionInfo> {
        val catalogKey = CatalogKey.of(catalogId)
        DeleteCatalogDraft(mcpTenantKey(), catalogKey, draftRefOf(draft)).execute()
        return ListCatalogVersions(mcpTenantKey(), catalogKey).query().map(CatalogVersionInfo::from)
    }

    @McpTool(
        name = "preview_release_number",
        description = "The number releasing the next version would give it, without releasing: from the kind of change " +
            "against the latest release (`NONE` a patch, `ADDITIVE` a minor, `BREAKING` a major), with the `reasons` " +
            "that make it more than a fix, each naming a resource. Show the user the number and the reasons before releasing.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun previewReleaseNumber(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
    ): ReleaseNumberInfo {
        val catalogKey = CatalogKey.of(catalogId)
        return ReleaseNumberInfo.from(AssessDraftNumber(mcpTenantKey(), catalogKey).query() ?: throw CatalogVersionNotFoundException(catalogKey, DraftRef.NEXT))
    }

    @McpTool(
        name = "release_next_version",
        description = "Release the next version of an AUTHORED catalog, numbered as its changes need " +
            "(`preview_release_number`) unless `version` asks for a higher number. Its changed resources must be " +
            "marked ready (`get_catalog_changes`) and its dependencies resolved. A next version behind releases is " +
            "released only when `acknowledgedBehind` names every one of them. After the release the next version " +
            "continues on it; long-term work is untouched and now behind it (`draftsBehind`). A release is immutable " +
            "and can be deployed for generation, so release only when the user asks to. Long-term work is not released: " +
            "it is made the next version first (`promote_long_term_work`). Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun releaseNextVersion(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "A higher number than its changes need, MAJOR.MINOR.PATCH, to mark e.g. a major on purpose. Omit for the number its changes need.", required = false)
        version: String?,
        @McpToolParam(description = "Release notes.", required = false)
        notes: String?,
        @McpToolParam(description = "The releases the user confirms the next version may be behind.", required = false)
        acknowledgedBehind: List<String>?,
    ): CatalogReleaseInfo = CatalogReleaseInfo.from(
        ReleaseCatalogVersion(
            tenantKey = mcpTenantKey(),
            catalogKey = CatalogKey.of(catalogId),
            version = version?.trim()?.takeIf { it.isNotEmpty() },
            notes = notes?.takeIf { it.isNotBlank() },
            acknowledgedBehind = acknowledgedBehind.orEmpty().map { it.trim() }.toSet(),
        ).execute(),
    )

    @McpTool(
        name = "promote_long_term_work",
        description = "Make long-term work the catalog's next version, when it is ready to go out with everyday work. It " +
            "must contain every release published since it started, or `acknowledgedBehind` names those it leaves out " +
            "(`list_catalog_versions` shows what it is `behind`). The previous next version is discarded with its changes, " +
            "or kept as long-term work under `keepPreviousAs`. Confirm with the user first. Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
    )
    fun promoteLongTermWork(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The long-term work's `draftId`. From `list_catalog_versions`.")
        draftId: String,
        @McpToolParam(description = "Keep the previous next version as long-term work under this name. Omit to discard it.", required = false)
        keepPreviousAs: String?,
        @McpToolParam(description = "The releases the user confirms the work may leave out.", required = false)
        acknowledgedBehind: List<String>?,
    ): CatalogVersionInfo = CatalogVersionInfo.from(
        PromoteCatalogDraft(
            tenantKey = mcpTenantKey(),
            catalogKey = CatalogKey.of(catalogId),
            draftId = draftIdOf(draftId),
            keepPreviousAs = keepPreviousAs?.trim()?.takeIf { it.isNotEmpty() },
            acknowledgedBehind = acknowledgedBehind.orEmpty().map { it.trim() }.toSet(),
        ).execute(),
    )

    private fun draftRefOf(raw: String): DraftRef = DraftRef.parse(raw.trim())
        ?: throw ValidationException("draft", "`draft` is `next` or a `draftId` from `list_catalog_versions`")

    private fun draftIdOf(raw: String): UUID = (draftRefOf(raw) as? DraftRef.Id)?.id
        ?: throw ValidationException("draftId", "`draftId` is a long-term draft's id from `list_catalog_versions`; the next version has no name of its own")

    private fun status(raw: String?): CatalogVersionStatus? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CatalogVersionStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ValidationException("status", "`status` is `DRAFT` or `RELEASED`")
    }
}
