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
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.namespaces.ListHeldNamespaces
import app.epistola.suite.catalog.queries.CatalogEditability
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleaseContentHashes
import app.epistola.suite.catalog.queries.ReleaseContentHashes
import app.epistola.suite.catalog.sources.AvailableCatalogReleases
import app.epistola.suite.catalog.sources.ListAvailableCatalogReleases
import app.epistola.suite.mcp.dto.CatalogDependencyInfo
import app.epistola.suite.mcp.dto.CatalogDependencyInput
import app.epistola.suite.mcp.dto.CatalogInfo
import app.epistola.suite.mcp.dto.NamespacesInfo
import app.epistola.suite.mcp.support.CATALOG_VERSION_DESCRIPTION
import app.epistola.suite.mcp.support.catalogEditability
import app.epistola.suite.mcp.support.catalogVersionRef
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
            "Each says whether it can be changed here (`editable`), and if not why (`readOnlyReason`): the catalogs " +
            "Epistola supplies never are, and none are in an installation that only runs releases. `source` says " +
            "where its releases come from; `releasedVersion` is its highest release, released here or received, and " +
            "`contentHash` that release's content hash.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCatalogs(): List<CatalogInfo> {
        val editability = catalogEditability()
        val hashes = ListReleaseContentHashes(mcpTenantKey()).query()
        return mediator.query(ListCatalogs(mcpTenantKey()))
            .map { CatalogInfo.from(it, editability.of(it.id) ?: CatalogEditability(null), hashes) }
    }

    @McpTool(
        name = "create_catalog",
        description = "Create an empty catalog in the current tenant, to hold the templates, themes, " +
            "stencils and images you then create in it (pass its key as `catalogId`). It is named in a namespace " +
            "the tenant holds (`list_namespaces`), or under `local` with a namespace you choose (`authority` " +
            "`local`), which never leaves this organisation. Without a namespace it is named in the one the " +
            "tenant holds; when it holds none or several, name one. Fails when a catalog with that key exists. " +
            "Requires the CATALOG_MANAGE permission.",
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
        @McpToolParam(
            description = "The namespace to name it in: one this tenant holds (`list_namespaces`), or one of your own " +
                "under `local`. Omit it for the one the tenant holds.",
            required = false,
        )
        namespace: String? = null,
        @McpToolParam(
            description = "The authority of `namespace`: `local` for a namespace of your own; omit it for the one the " +
                "tenant holds the namespace under.",
            required = false,
        )
        authority: String? = null,
    ): CatalogInfo {
        val catalog = CreateCatalog(
            tenantKey = mcpTenantKey(),
            id = CatalogKey.of(catalogId),
            name = name,
            description = description?.takeIf { it.isNotBlank() },
            namespace = namespace?.trim()?.takeIf { it.isNotEmpty() },
            registry = authority?.trim()?.takeIf { it.isNotEmpty() },
        ).execute()
        return CatalogInfo.from(catalog, GetCatalogEditability(catalog.tenantKey, catalog.id).query(), ReleaseContentHashes(emptyMap()))
    }

    @McpTool(
        name = "list_namespaces",
        description = "List the namespaces the current tenant holds, which `create_catalog` names a new catalog in, " +
            "and the one it uses when given none (`default`). Any tenant may also name a catalog under `local` with a " +
            "namespace of its own (`localInUse` lists those its catalogs use); a local catalog never leaves this " +
            "organisation. Releasing a catalog needs its namespace to be held. A platform administrator grants namespaces.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listNamespaces(): NamespacesInfo = NamespacesInfo.from(ListHeldNamespaces(mcpTenantKey()).query())

    @McpTool(
        name = "list_available_catalog_releases",
        description = "List the releases a catalog's source offers that this tenant has not installed, " +
            "lowest first, older lines included (a fix 1.4.1 while 2.0.0 is installed). Each is compared to the " +
            "closest installed release below it (`comparedTo`), with the resources it added, modified or removed " +
            "against that one and the releases of other catalogs it pins, each with whether it is here. Read-only: " +
            "installing one is not available over MCP. `catalogId` comes from `list_catalogs` (catalogs with a `source` only).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listAvailableCatalogReleases(
        @McpToolParam(description = "Catalog key of a catalog with a `source`. From `list_catalogs`.")
        catalogId: String,
    ): AvailableCatalogReleases = mediator.query(ListAvailableCatalogReleases(mcpTenantKey(), CatalogKey.of(catalogId)))

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
        .withContentHashes()

    @McpTool(
        name = "update_catalog_dependencies",
        description = "Replace the catalogs a catalog that can be changed here depends on, in the order given. Each names " +
            "another catalog of this tenant by key and pins the exact `release` of it to render with (not a " +
            "range), or, to build on work not released yet, one of its drafts by `draftId`. Neither: its highest " +
            "release. Using another catalog's resource declares that catalog by itself, naming its next release. " +
            "Releasing a catalog that builds on a draft needs that draft released first, or along " +
            "(`release_draft` with `releaseDependencies`). Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateCatalogDependencies(
        @McpToolParam(description = "Catalog key of a catalog that can be changed here. From `list_catalogs`.")
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
                releaseVersion = release ?: if (draft == null) target.highestReleaseVersion else null,
                versionId = draft,
            )
        }
        return UpdateCatalogDependencies(tenant, CatalogKey.of(catalogId), declared, catalogVersion = catalogVersionRef(catalogId, catalogVersion)).execute().withContentHashes()
    }

    @McpTool(
        name = "upgrade_catalog_dependencies",
        description = "Move every dependency pin of a catalog that can be changed here to the highest release of it, as " +
            "upgrading a library does; a dependency naming a draft stays on that draft. The catalog's next " +
            "release is cut with the result, and its drafts render with it. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun upgradeCatalogDependencies(
        @McpToolParam(description = "Catalog key of a catalog that can be changed here. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<CatalogDependencyInfo> = UpgradeCatalogDependencies(mcpTenantKey(), CatalogKey.of(catalogId), catalogVersion = catalogVersionRef(catalogId, catalogVersion)).execute()
        .withContentHashes()

    /** Each declaration with the content hash of the release it pins. */
    private fun List<CatalogDependencyDeclaration>.withContentHashes(): List<CatalogDependencyInfo> {
        if (isEmpty()) return emptyList()
        val hashes = ListReleaseContentHashes(mcpTenantKey()).query()
        return map { CatalogDependencyInfo.from(it, hashes) }
    }
}
