// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.springframework.stereotype.Component

/**
 * The release registries this tenant has (contract `listReleaseRegistries`, alpha): the configured
 * container registries, and the Epistola Exchange when the tenant is connected to it. Names, kinds
 * and locations only; credentials never leave the configuration.
 */
data class ListReleaseRegistries(
    override val tenantKey: TenantKey,
) : Query<ReleaseRegistryList>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class ReleaseRegistryList(val registries: List<ReleaseRegistrySummary>) {
    /** Whether there is any release registry, which is what offers the route at all. */
    val available: Boolean get() = registries.isNotEmpty()
}

@Component
class ListReleaseRegistriesHandler(
    private val directory: ReleaseRegistryDirectory,
) : QueryHandler<ListReleaseRegistries, ReleaseRegistryList> {
    override fun handle(query: ListReleaseRegistries): ReleaseRegistryList = ReleaseRegistryList(directory.all(query.tenantKey).map { it.summary })
}

/**
 * The releases of [namespace]/[catalogKey] that release registry [registry] holds (contract
 * `listRegistryReleases`, alpha), highest first, each with what the registry says of it.
 */
data class ListReleaseRegistryReleases(
    override val tenantKey: TenantKey,
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    /** The authority of the catalog's name; null for the default registry. */
    val authority: String? = null,
) : Query<ReleaseRegistryListing>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListReleaseRegistryReleasesHandler(
    private val directory: ReleaseRegistryDirectory,
) : QueryHandler<ListReleaseRegistryReleases, ReleaseRegistryListing> {
    override fun handle(query: ListReleaseRegistryReleases): ReleaseRegistryListing = directory.named(query.tenantKey, query.registry).list(query.tenantKey, query.namespace.trim(), query.catalogKey.trim(), query.authority?.trim())
}

/**
 * Installs release [version] of [namespace]/[catalogKey] from release registry [registry] (contract
 * `installRegistryRelease`, alpha), exactly as importing its archive does. [Permission.CATALOG_MANAGE],
 * as importing is. `SelfManagedTransaction`: the download must not hold a database connection.
 */
data class InstallReleaseRegistryRelease(
    override val tenantKey: TenantKey,
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val version: String,
    /** The authority of the catalog's name; null for the default registry. */
    val authority: String? = null,
) : Command<ReleaseRegistryInstall>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_MANAGE

    init {
        validate("version", SemVer.parseOrNull(version)?.toString() == version.trim(), ValidationCode.REGISTRY_REFERENCE_INVALID) {
            "A release number is MAJOR.MINOR.PATCH, not '$version'."
        }
    }
}

@Component
class InstallReleaseRegistryReleaseHandler(
    private val directory: ReleaseRegistryDirectory,
) : CommandHandler<InstallReleaseRegistryRelease, ReleaseRegistryInstall> {
    override fun handle(command: InstallReleaseRegistryRelease): ReleaseRegistryInstall = directory.named(command.tenantKey, command.registry)
        .install(command.tenantKey, command.namespace.trim(), command.catalogKey.trim(), command.version.trim(), command.authority?.trim())
}

/**
 * Publishes released release [version] of [catalogKey] to release registry [registry] (contract
 * `publishReleaseToRegistry`, alpha): a container registry publishes while asked, the Exchange
 * queues it. [Permission.CATALOG_PUBLISH]: the release leaves the installation.
 * `SelfManagedTransaction`: the push must not hold a database connection.
 */
data class PublishReleaseToReleaseRegistry(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val registry: String,
) : Command<ReleaseRegistryPublication>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_PUBLISH

    init {
        validate("version", SemVer.parseOrNull(version)?.toString() == version.trim(), ValidationCode.REGISTRY_REFERENCE_INVALID) {
            "A release number is MAJOR.MINOR.PATCH, not '$version'."
        }
    }
}

@Component
class PublishReleaseToReleaseRegistryHandler(
    private val directory: ReleaseRegistryDirectory,
) : CommandHandler<PublishReleaseToReleaseRegistry, ReleaseRegistryPublication> {
    override fun handle(command: PublishReleaseToReleaseRegistry): ReleaseRegistryPublication = directory.named(command.tenantKey, command.registry)
        .publish(command.tenantKey, command.catalogKey, command.version.trim())
}
