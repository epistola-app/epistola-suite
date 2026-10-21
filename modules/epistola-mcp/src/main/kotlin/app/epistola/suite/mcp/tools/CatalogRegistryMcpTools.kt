// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.registry.InstallReleaseFromRegistry
import app.epistola.suite.catalog.registry.ListCatalogRegistries
import app.epistola.suite.catalog.registry.ListRegistryReleases
import app.epistola.suite.catalog.registry.PublishReleaseToRegistry
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.RegistryInstallInfo
import app.epistola.suite.mcp.dto.RegistryPublicationInfo
import app.epistola.suite.mcp.dto.RegistryReleasesInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Moving catalog releases between installations through a container registry (alpha, ADR 0032).
 * The registries are configured per installation; each tool dispatches the command the UI uses, so
 * the same checks and refusals apply.
 */
@Component
class CatalogRegistryMcpTools {

    @McpTool(
        name = "list_registry_releases",
        description = "Alpha. The releases of a catalog a container registry holds, highest number first, with each " +
            "one's fingerprint label, digest and size, and whether this tenant has it (`present`). `conflict` says why " +
            "installing would be refused here (the catalog key is used by another catalog). The registries are those " +
            "configured for this installation; an unknown name is refused with the configured ones.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listRegistryReleases(
        @McpToolParam(description = "The registry's configured name.")
        registry: String,
        @McpToolParam(description = "The catalog's namespace, e.g. `acme`.")
        namespace: String,
        @McpToolParam(description = "The catalog's key.")
        catalogKey: String,
    ): RegistryReleasesInfo = RegistryReleasesInfo.from(ListRegistryReleases(mcpTenantKey(), registry, namespace, catalogKey).query())

    @McpTool(
        name = "publish_release_to_registry",
        description = "Alpha. Publish a release of a catalog to a container registry, as `<namespace>/<catalogKey>:<version>`, " +
            "so another installation can install it. When the registry already has that number with the same " +
            "fingerprint nothing is pushed (`alreadyPublished`); with other content it is refused: one number names " +
            "one release. The release leaves this installation, so publish only when the user asks to. Requires the " +
            "CATALOG_PUBLISH permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun publishReleaseToRegistry(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The release number, MAJOR.MINOR.PATCH. From `list_catalog_versions`.")
        version: String,
        @McpToolParam(description = "The registry's configured name. Optional when exactly one is configured.", required = false)
        registry: String?,
    ): RegistryPublicationInfo {
        val tenantKey = mcpTenantKey()
        val name = registry?.trim()?.takeIf { it.isNotEmpty() }
            ?: ListCatalogRegistries(tenantKey).query().registries.singleOrNull()?.name
            ?: ""
        return RegistryPublicationInfo.from(PublishReleaseToRegistry(tenantKey, CatalogKey.of(catalogId), version.trim(), name).execute())
    }

    @McpTool(
        name = "install_release_from_registry",
        description = "Alpha. Install a release of a catalog from a container registry, as importing its archive does. " +
            "The download is verified against its digest and the fingerprint is recomputed from the content; the same " +
            "number with other content, missing dependencies and a too new catalog format are refused. A catalog this " +
            "tenant does not have yet is created, receiving its releases from that registry. Requires the CATALOG_MANAGE " +
            "permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun installReleaseFromRegistry(
        @McpToolParam(description = "The registry's configured name.")
        registry: String,
        @McpToolParam(description = "The catalog's namespace.")
        namespace: String,
        @McpToolParam(description = "The catalog's key.")
        catalogKey: String,
        @McpToolParam(description = "The release number. From `list_registry_releases`.")
        version: String,
    ): RegistryInstallInfo = RegistryInstallInfo.from(InstallReleaseFromRegistry(mcpTenantKey(), registry, namespace, catalogKey, version.trim()).execute())
}
