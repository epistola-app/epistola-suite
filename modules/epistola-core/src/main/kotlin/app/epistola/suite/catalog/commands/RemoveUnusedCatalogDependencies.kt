// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Removes the declarations of other catalogs, by [aliases], that nothing in the version uses any more
 * ([app.epistola.suite.catalog.dependencies.CatalogBorrowing.unused]): what a merge can leave behind,
 * offered for removal rather than removed unasked. A declaration still used, or not declared, is
 * refused, so a stale question never removes what a later edit started using.
 */
data class RemoveUnusedCatalogDependencies(
    val catalog: CatalogId,
    val aliases: List<String>,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<RemovedCatalogDependencies>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT
}

/** @property removed the aliases whose declarations went, in the order asked. */
data class RemovedCatalogDependencies(val removed: List<String>)

@Component
class RemoveUnusedCatalogDependenciesHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : CommandHandler<RemoveUnusedCatalogDependencies, RemovedCatalogDependencies> {

    override fun handle(command: RemoveUnusedCatalogDependencies): RemovedCatalogDependencies {
        val unused = GetCatalogBorrowing(command.catalog, command.catalogVersion).query().unused.map { it.alias }.toSet()
        command.aliases.firstOrNull { it !in unused }?.let { alias ->
            throw ValidationException("aliases", "$alias is used by this version, or not declared, so its declaration stays.", ValidationCode.CATALOG_DEPENDENCY_INVALID)
        }
        return jdbi.inTransaction<RemovedCatalogDependencies, Exception> { handle ->
            handle.findCatalog(command.catalog) ?: throw CatalogNotFoundException(command.catalogKey)
            requireCatalogEditable(command.catalog)
            val declarations = store.declarations(handle, command.catalog)
            store.replace(handle, command.catalog, declarations.filterNot { it.alias in command.aliases })
            RemovedCatalogDependencies(command.aliases.distinct())
        }
    }
}
