// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.writableVersionOf
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Declares [dependency] in a draft of [catalog], so its resources can be used there (contract ADR
 * 0003): the step a picker's "Depend on" takes before one of another catalog's resources is chosen.
 * It names [dependency]'s next version when that can be changed here, else pins its highest release,
 * as a write that starts using a catalog declares it ([UsedCatalogDeclarer]). A catalog already
 * declared is left as it is.
 *
 * @return the declarations added: empty when it was declared already.
 */
data class DeclareCatalogDependency(
    val catalog: CatalogId,
    val dependency: CatalogId,
    /** The draft this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : CatalogReleaseScoped,
    Command<List<CatalogDependencyDeclaration>>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey

    // As UpdateCatalogDependencies: which catalogs a draft uses is part of authoring it.
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        validate("dependency", dependency.tenantKey == catalog.tenantKey, ValidationCode.CATALOG_DEPENDENCY_INVALID) { "The catalog is of the same tenant" }
        validate("dependency", dependency != catalog, ValidationCode.CATALOG_DEPENDENCY_INVALID) { "A catalog cannot depend on itself" }
    }
}

@Component
class DeclareCatalogDependencyHandler(
    private val jdbi: Jdbi,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<DeclareCatalogDependency, List<CatalogDependencyDeclaration>> {
    override fun handle(command: DeclareCatalogDependency): List<CatalogDependencyDeclaration> {
        val at = jdbi.withHandle<app.epistola.suite.catalog.releases.ContentVersion, Exception> { handle ->
            handle.findCatalog(command.catalog) ?: throw CatalogNotFoundException(command.catalog.key)
            handle.findCatalog(command.dependency) ?: throw CatalogNotFoundException(command.dependency.key)
            requireCatalogEditable(command.catalog)
            handle.writableVersionOf(command.catalog, command)
        }
        return usedCatalogs.declare(at, command.dependency)
    }
}
