// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component

/**
 * Installs a catalog published on Epistola Exchange into this tenant.
 *
 * `SelfManagedTransaction` because the release archive is fetched mid-command and a multi-megabyte
 * download must not be holding a pooled database connection while it runs. The import itself is
 * dispatched through the mediator and gets its own transaction.
 *
 * The permission here is [Permission.CATALOG_MANAGE] — the same one that registers, upgrades and
 * unregisters a catalog. The effective requirement is that **and** [Permission.TEMPLATE_EDIT],
 * because the nested `ImportCatalogZip` carries the latter and authorization is enforced on every
 * dispatch. That is not new (applying any subscribed upgrade has always needed both), but the two
 * are not held by the same role, so callers should offer this only where both are present rather
 * than letting someone discover it after a download.
 */
data class InstallExchangeCatalog(
    override val tenantKey: TenantKey,
    val namespace: String,
    val catalogKey: String,
    /** The release to install, or null for the newest Exchange offers. */
    val version: String? = null,
) : Command<ExchangeInstallResult>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class InstallExchangeCatalogHandler(
    private val installer: ExchangeCatalogInstaller,
) : CommandHandler<InstallExchangeCatalog, ExchangeInstallResult> {
    override fun handle(command: InstallExchangeCatalog): ExchangeInstallResult = installer.install(command.tenantKey, command.namespace, command.catalogKey, command.version)
}

/**
 * Moves an installed Exchange catalog to another of its releases.
 *
 * A separate command from installing only so the two read differently where they are audited and
 * offered; both are the same import, and `ImportCatalogZip` decides for itself whether a catalog is
 * being created or mirrored forward. The coordinates are not passed in — they come from what the
 * catalog already records, so an upgrade cannot silently re-point a mirror at a different source.
 *
 * The target release may be *older* than the installed one. That is deliberate: when a release is
 * withdrawn, going back to the newest one still offered is the only way forward.
 */
data class UpgradeExchangeCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String? = null,
) : Command<ExchangeInstallResult>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class UpgradeExchangeCatalogHandler(
    private val installer: ExchangeCatalogInstaller,
) : CommandHandler<UpgradeExchangeCatalog, ExchangeInstallResult> {
    override fun handle(command: UpgradeExchangeCatalog): ExchangeInstallResult {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw ValidationException(
                "catalogKey",
                "No catalog '${command.catalogKey.value}' in this tenant.",
                ValidationCode.EXCHANGE_CATALOG_KEY_TAKEN,
            )
        val coordinates = ExchangeSourceUri.parse(catalog.sourceUrl)
            ?: throw ValidationException(
                "catalogKey",
                "Catalog '${command.catalogKey.value}' was not installed from Epistola Exchange.",
                ValidationCode.EXCHANGE_RELEASE_UNAVAILABLE,
            )
        return installer.install(command.tenantKey, coordinates.namespace, coordinates.catalogKey, command.version)
    }
}
