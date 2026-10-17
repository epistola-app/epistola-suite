// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogVersion
import app.epistola.suite.catalog.versions.DeleteCatalogVersion
import app.epistola.suite.catalog.versions.GetCatalogVersion
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.catalog.versions.PreviewCatalogVersionRenumber
import app.epistola.suite.catalog.versions.RenumberCatalogVersion
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.CatalogReleaseInfo
import app.epistola.suite.mcp.dto.CatalogVersionInfo
import app.epistola.suite.mcp.dto.CatalogVersionRenumberInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * The versions of an AUTHORED catalog (contract ADR 0002): its open drafts, each an independent copy
 * of the catalog's content that the content tools address with `catalogVersion`, and its releases.
 * Each tool dispatches the command the UI and REST API use, so the same refusals apply.
 */
@Component
class CatalogVersionMcpTools {

    @McpTool(
        name = "list_catalog_versions",
        description = "List a catalog's versions, highest first: its open drafts (`DRAFT`) and its releases " +
            "(`RELEASED`). A catalog can have several drafts at once, e.g. a maintenance line next to the next " +
            "major; pass a draft's number as `catalogVersion` to the content tools to read or change it. A draft " +
            "lists the releases it is `behind`: released after its base and below its number, so it does not " +
            "contain their changes.",
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
        name = "create_catalog_version",
        description = "Open a new draft of an AUTHORED catalog, numbered `version`. Without `from` it starts as a " +
            "copy of its base: the highest release below its number (empty when there is none). With `from` it " +
            "starts as a copy of that draft or release. Fails when the number is in use. Requires the " +
            "TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createCatalogVersion(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The new draft's number: MAJOR.MINOR.PATCH, e.g. `2.0.0`.")
        version: String,
        @McpToolParam(description = "A draft or release to copy instead of the base. From `list_catalog_versions`.", required = false)
        from: String?,
    ): CatalogVersionInfo = CatalogVersionInfo.from(
        CreateCatalogVersion(mcpTenantKey(), CatalogKey.of(catalogId), version.trim(), from?.trim()?.takeIf { it.isNotEmpty() }).execute(),
    )

    @McpTool(
        name = "delete_catalog_version",
        description = "Discard a draft of an AUTHORED catalog and everything changed in it. Releases cannot be " +
            "deleted here. Refused while the draft alone holds something used outside it, such as the tenant's " +
            "default theme or a code list another catalog's attribute binds; the error names it. Confirm with " +
            "the user first: this cannot be undone. Requires the CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
    )
    fun deleteCatalogVersion(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The draft's number. From `list_catalog_versions`.")
        version: String,
    ): List<CatalogVersionInfo> {
        val catalogKey = CatalogKey.of(catalogId)
        DeleteCatalogVersion(mcpTenantKey(), catalogKey, version.trim()).execute()
        return ListCatalogVersions(mcpTenantKey(), catalogKey).query().map(CatalogVersionInfo::from)
    }

    @McpTool(
        name = "renumber_catalog_version",
        description = "Give a draft another number; its content stays. When renumbering has consequences " +
            "(moving up past a release it does not contain, down below releases it contains, past another " +
            "draft, or to a lower kind of version than its changes against its base need: breaking changes need a " +
            "major, additions at least a minor, each warning listing its `reasons`), nothing changes and the `warnings` come back: show them to the user and call again with " +
            "`acknowledgeWarnings: true` to renumber anyway. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun renumberCatalogVersion(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The draft's number now. From `list_catalog_versions`.")
        version: String,
        @McpToolParam(description = "The new number: MAJOR.MINOR.PATCH.")
        to: String,
        @McpToolParam(description = "True to renumber despite the warnings a previous call returned.", required = false)
        acknowledgeWarnings: Boolean?,
    ): CatalogVersionRenumberInfo {
        val tenant = mcpTenantKey()
        val catalogKey = CatalogKey.of(catalogId)
        if (acknowledgeWarnings != true) {
            val warnings = PreviewCatalogVersionRenumber(tenant, catalogKey, version.trim(), to.trim()).query()
            if (warnings.isNotEmpty()) return CatalogVersionRenumberInfo.warnings(warnings)
        }
        val renumbered = RenumberCatalogVersion(tenant, catalogKey, version.trim(), to.trim(), acknowledgeWarnings = acknowledgeWarnings == true).execute()
        return CatalogVersionRenumberInfo(renumbered = true, version = CatalogVersionInfo.from(renumbered), warnings = emptyList())
    }

    @McpTool(
        name = "release_catalog_version",
        description = "Release a draft of an AUTHORED catalog under its own number. Its changed resources must be " +
            "marked ready (`get_catalog_changes`) and its dependencies resolved. A draft that is behind releases " +
            "is released only when `acknowledgedBehind` names every one of them (`list_catalog_versions` shows " +
            "them, with their changes). When no higher draft is open the draft carries on as the next patch " +
            "(`openedDraft`); otherwise it is discarded, its content being in the release. A release is " +
            "immutable and can be deployed for generation, so release only when the user asks to. Requires the " +
            "CATALOG_RELEASE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun releaseCatalogVersion(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The draft to release, by its number. From `list_catalog_versions`.")
        version: String,
        @McpToolParam(description = "Release notes.", required = false)
        notes: String?,
        @McpToolParam(description = "The releases the user confirms this draft may be behind.", required = false)
        acknowledgedBehind: List<String>?,
    ): CatalogReleaseInfo {
        val tenant = mcpTenantKey()
        val catalogKey = CatalogKey.of(catalogId)
        val number = version.trim()
        // Releasing a number no draft has would release the current draft renumbered; here a draft is
        // released by the number it has, so another number is refused instead.
        val draft = GetCatalogVersion(tenant, catalogKey, number).query()
        if (draft == null) throw CatalogVersionNotFoundException(catalogKey, number)
        if (draft.status != CatalogVersionStatus.DRAFT) throw ValidationException("version", "Version $number is released already")
        return CatalogReleaseInfo.from(
            ReleaseCatalogVersion(
                tenantKey = tenant,
                catalogKey = catalogKey,
                version = number,
                notes = notes?.takeIf { it.isNotBlank() },
                acknowledgedBehind = acknowledgedBehind.orEmpty().map { it.trim() }.toSet(),
            ).execute(),
        )
    }

    private fun status(raw: String?): CatalogVersionStatus? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CatalogVersionStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ValidationException("status", "`status` is `DRAFT` or `RELEASED`")
    }
}
