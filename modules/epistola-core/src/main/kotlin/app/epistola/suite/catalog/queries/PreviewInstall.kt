// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * What installing this catalog would bring in, for the confirmation dialog.
 *
 * A catalog installs whole (see [app.epistola.suite.catalog.commands.InstallFromCatalog]), so this
 * is the manifest's resource list and nothing is resolved or expanded. `DependencyResolver` used to
 * run here to grow a chosen subset until it was self-consistent; with the whole manifest selected it
 * can add nothing, and it fetched every resource detail over HTTP to discover that, which made
 * opening the dialog as expensive as the install.
 */
data class PreviewInstall(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<PreviewInstallResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class PreviewInstallResult(
    val resources: List<ResourceEntry>,
)

@Component
class PreviewInstallHandler(
    private val catalogClient: CatalogClient,
) : QueryHandler<PreviewInstall, PreviewInstallResult> {

    override fun handle(query: PreviewInstall): PreviewInstallResult {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${query.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog has no source URL: ${query.catalogKey}")

        val migratedManifest = catalogClient.fetchMigratedManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        val manifest = migratedManifest.manifest

        return PreviewInstallResult(resources = manifest.resources)
    }
}
