// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpgradeCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.mcp.dto.CatalogDependencyInfo
import app.epistola.suite.mcp.dto.CatalogDependencyInput
import app.epistola.suite.mcp.dto.CatalogInfo
import app.epistola.suite.mcp.dto.CatalogUpgradeDiffInfo
import app.epistola.suite.mcp.support.CATALOG_VERSION_DESCRIPTION
import app.epistola.suite.mcp.support.catalogVersionRef
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class CatalogMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_catalogs",
        description = "List catalogs available in the current tenant. " +
            "Catalogs hold templates, themes, and stencils; most other tools " +
            "require a `catalogId` argument that comes from this list. " +
            "AUTHORED catalogs are editable; SUBSCRIBED catalogs are read-only mirrors of remote sources.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCatalogs(): List<CatalogInfo> = mediator.query(ListCatalogs(mcpTenantKey()))
        .map { CatalogInfo.from(it) }

    @McpTool(
        name = "create_catalog",
        description = "Create an empty AUTHORED catalog in the current tenant, to hold the templates, themes, " +
            "stencils and images you then create in it (pass its key as `catalogId`). Fails when a catalog " +
            "with that key exists. Requires the CATALOG_MANAGE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createCatalog(
        @McpToolParam(
            description = "Key for the new catalog: 3-50 lowercase letters, digits and single hyphens, " +
                "starting with a letter, e.g. `sociaal-domein`.",
        )
        catalogId: String,
        @McpToolParam(description = "Display name.")
        name: String,
        @McpToolParam(description = "Optional description.", required = false)
        description: String?,
    ): CatalogInfo = CatalogInfo.from(
        CreateCatalog(
            tenantKey = mcpTenantKey(),
            id = CatalogKey.of(catalogId),
            name = name,
            description = description?.takeIf { it.isNotBlank() },
        ).execute(),
    )

    @McpTool(
        name = "preview_catalog_upgrade",
        description = "Preview what upgrading a SUBSCRIBED catalog to its source's latest release " +
            "would do, WITHOUT applying it. Re-fetches the source manifest and diffs it against the " +
            "installed release: added / removed / changed / unchanged resources (each `type/slug`), " +
            "plus any cross-catalog conflicts that would block removals. Read-only — the upgrade " +
            "action itself is not available over MCP. `catalogId` comes from `list_catalogs` " +
            "(SUBSCRIBED catalogs only).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun previewCatalogUpgrade(
        @McpToolParam(description = "Catalog key of a SUBSCRIBED catalog. From `list_catalogs`.")
        catalogId: String,
    ): CatalogUpgradeDiffInfo = CatalogUpgradeDiffInfo.from(
        mediator.query(PreviewCatalogUpgrade(mcpTenantKey(), CatalogKey.of(catalogId))),
    )

    @McpTool(
        name = "list_catalog_dependencies",
        description = "List the catalogs a catalog declares it depends on: each with the exact `release` it " +
            "pins, which the catalog's next release is cut with and renders with, or the draft of it it builds on " +
            "(`draftId`, `draftName`). A catalog that uses another catalog's themes, stencils, fonts, images or " +
            "code lists must declare that catalog before it can be released.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCatalogDependencies(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<CatalogDependencyInfo> = mediator.query(ListCatalogDependencies(mcpTenantKey(), CatalogKey.of(catalogId), catalogVersion = catalogVersionRef(catalogId, catalogVersion)))
        .map(CatalogDependencyInfo::from)

    @McpTool(
        name = "update_catalog_dependencies",
        description = "Replace the catalogs an AUTHORED catalog depends on, in the order given. Each names " +
            "another catalog of this tenant by key and pins the exact `release` of it to render with (not a " +
            "range), or, to build on work not released yet, one of its drafts by `draftId`. Neither: its highest " +
            "release. Releasing a catalog that builds on a draft needs that draft released first, or along " +
            "(`release_draft` with `releaseDependencies`). Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateCatalogDependencies(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "Every dependency, as `{catalogId, release?, draftId?}`; an empty list declares none.")
        dependencies: List<CatalogDependencyInput>,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<CatalogDependencyInfo> {
        val tenant = mcpTenantKey()
        val declared = dependencies.map { dependency ->
            val key = CatalogKey.of(dependency.catalogId)
            val target = mediator.query(GetCatalog(tenant, key)) ?: throw CatalogNotFoundException(key)
            val release = dependency.release?.trim()?.takeIf { it.isNotEmpty() }
            val draft = dependency.draftId?.trim()?.takeIf { it.isNotEmpty() }
            DeclaredCatalogDependency(
                target.registry,
                target.namespace,
                key.value,
                releaseVersion = release ?: if (draft == null) target.releasedVersion ?: target.installedReleaseVersion else null,
                versionId = draft,
            )
        }
        return UpdateCatalogDependencies(tenant, CatalogKey.of(catalogId), declared, catalogVersion = catalogVersionRef(catalogId, catalogVersion)).execute().map(CatalogDependencyInfo::from)
    }

    @McpTool(
        name = "upgrade_catalog_dependencies",
        description = "Move every dependency of an AUTHORED catalog to the highest release of it, as " +
            "upgrading a library does. The catalog's next release is cut with the result. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun upgradeCatalogDependencies(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<CatalogDependencyInfo> = UpgradeCatalogDependencies(mcpTenantKey(), CatalogKey.of(catalogId), catalogVersion = catalogVersionRef(catalogId, catalogVersion)).execute()
        .map(CatalogDependencyInfo::from)
}
