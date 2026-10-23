// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.registry.InstallReleaseRegistryRelease
import app.epistola.suite.catalog.registry.ListReleaseRegistries
import app.epistola.suite.catalog.registry.ListReleaseRegistryReleases
import app.epistola.suite.catalog.registry.PublishReleaseToReleaseRegistry
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.RegistryInstallInfo
import app.epistola.suite.mcp.dto.RegistryPublicationInfo
import app.epistola.suite.mcp.dto.RegistryReleasesInfo
import app.epistola.suite.mcp.dto.ReleaseRegistriesInfo
import app.epistola.suite.mcp.support.mcpCatalogKey
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Moving catalog releases between installations through a release registry (alpha, ADR 0032,
 * contract ADR 0008): a container registry configured for the installation, or the Epistola Exchange
 * the tenant is connected to (named `exchange`). Each tool dispatches the command the UI uses, so
 * the same checks and refusals apply.
 */
@Component
class CatalogRegistryMcpTools {

    @McpTool(
        name = "list_release_registries",
        description = "Alpha. The release registries this tenant can publish releases to and install them from: the " +
            "container registries configured for this installation (`kind` `container`) and Epistola Exchange when the " +
            "tenant is connected to it (`kind` `exchange`, named `exchange`). Names to use with the other registry tools.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listReleaseRegistries(): ReleaseRegistriesInfo = ReleaseRegistriesInfo.from(ListReleaseRegistries(mcpTenantKey()).query())

    @McpTool(
        name = "list_registry_releases",
        description = "Alpha. The releases of a catalog a release registry holds (a container registry or Epistola " +
            "Exchange), highest number first, with what the registry says of each (fingerprint, size) and whether this " +
            "tenant has it (`present`). `conflict` says why installing would be refused here (the catalog key is used " +
            "by another catalog). The registries are those `list_release_registries` names; an unknown name is refused.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listRegistryReleases(
        @McpToolParam(description = "The release registry's name. From `list_release_registries`.")
        registry: String,
        @McpToolParam(description = "The catalog's namespace, e.g. `acme`.")
        namespace: String,
        @McpToolParam(description = "The catalog's key.")
        catalogKey: String,
        @McpToolParam(description = "The authority of the catalog's name, e.g. `local` or `gemeente-x.nl`. Omit for the default registry.", required = false)
        authority: String? = null,
    ): RegistryReleasesInfo = RegistryReleasesInfo.from(ListReleaseRegistryReleases(mcpTenantKey(), registry, namespace, catalogKey, authority?.takeIf { it.isNotBlank() }).query())

    @McpTool(
        name = "publish_release_to_registry",
        description = "Alpha. Publish a release of a catalog to a release registry so another installation can install " +
            "it: a container registry (as `<authority>/<namespace>/<catalogKey>:<version>`, `status` `published`) or Epistola " +
            "Exchange (`status` `queued`: the Exchange decides later; also when it was queued already). When the " +
            "registry already has that number with the same fingerprint nothing is sent (`already-published`); with " +
            "other content it is refused: one number names one release. The release leaves this installation, so " +
            "publish only when the user asks to. Requires the CATALOG_PUBLISH permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun publishReleaseToRegistry(
        @McpToolParam(description = "Catalog key. From `list_catalogs`.")
        catalogId: String,
        @McpToolParam(description = "The release number, MAJOR.MINOR.PATCH. From `list_catalog_releases`.")
        version: String,
        @McpToolParam(description = "The release registry's name, from `list_release_registries`. Optional when there is exactly one.", required = false)
        registry: String?,
    ): RegistryPublicationInfo {
        val tenantKey = mcpTenantKey()
        val name = registry?.trim()?.takeIf { it.isNotEmpty() }
            ?: ListReleaseRegistries(tenantKey).query().registries.singleOrNull()?.name
            ?: ""
        return RegistryPublicationInfo.from(PublishReleaseToReleaseRegistry(tenantKey, mcpCatalogKey(catalogId), version.trim(), name).execute())
    }

    @McpTool(
        name = "install_release_from_registry",
        description = "Alpha. Install a release of a catalog from a release registry (a container registry or Epistola " +
            "Exchange), as importing its archive does. " +
            "The download is verified against its digest and the fingerprint is recomputed from the content; the same " +
            "number with other content, missing dependencies and a too new catalog format are refused. A catalog this " +
            "tenant does not have yet is created, receiving its releases from that registry. Requires the CATALOG_MANAGE " +
            "permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun installReleaseFromRegistry(
        @McpToolParam(description = "The release registry's name. From `list_release_registries`.")
        registry: String,
        @McpToolParam(description = "The catalog's namespace.")
        namespace: String,
        @McpToolParam(description = "The catalog's key.")
        catalogKey: String,
        @McpToolParam(description = "The release number. From `list_registry_releases`.")
        version: String,
        @McpToolParam(description = "The authority of the catalog's name, e.g. `local` or `gemeente-x.nl`. Omit for the default registry.", required = false)
        authority: String? = null,
    ): RegistryInstallInfo = RegistryInstallInfo.from(InstallReleaseRegistryRelease(mcpTenantKey(), registry, namespace, catalogKey, version.trim(), authority?.takeIf { it.isNotBlank() }).execute(), version.trim())
}
