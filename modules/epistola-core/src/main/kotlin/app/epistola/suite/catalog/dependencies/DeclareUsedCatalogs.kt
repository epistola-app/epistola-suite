// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Declares every catalog a catalog version uses but does not declare, locked to its highest release
 * ([MissingDependencyDeclarer]). For publishing: someone who picked a theme from another catalog in
 * the editor should not have to know that a release has to declare it.
 *
 * @return the declarations added.
 */
data class DeclareUsedCatalogs(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<List<CatalogDependencyDeclaration>>,
    RequiresPermission {
    // As UpdateCatalogDependencies: which releases of its dependencies a release is cut with is part of releasing.
    override val permission get() = Permission.TEMPLATE_EDIT
}

@Component
class DeclareUsedCatalogsHandler(
    private val declarer: MissingDependencyDeclarer,
) : CommandHandler<DeclareUsedCatalogs, List<CatalogDependencyDeclaration>> {
    override fun handle(command: DeclareUsedCatalogs): List<CatalogDependencyDeclaration> = declarer.declareMissing(command.tenantKey, command.catalogKey)
}
