// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Moves every dependency pin of an authored catalog to the highest release of that catalog the
 * tenant has, as upgrading a library does. A declaration of a catalog with no release keeps its pin,
 * and stays unpinned when it had none; one naming a draft is left as it is. The next release is cut with the pins this leaves.
 */
data class UpgradeCatalogDependencies(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<List<CatalogDependencyDeclaration>>,
    RequiresPermission {
    // Which releases of its dependencies the next release is cut with is part of releasing.
    override val permission get() = Permission.TEMPLATE_EDIT
}

@Component
class UpgradeCatalogDependenciesHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : CommandHandler<UpgradeCatalogDependencies, List<CatalogDependencyDeclaration>> {

    override fun handle(command: UpgradeCatalogDependencies): List<CatalogDependencyDeclaration> = jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
        val catalog = handle.findCatalog(command.tenantKey, command.catalogKey)
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.type == CatalogType.SUBSCRIBED) throw CatalogReadOnlyException(command.catalogKey)
        val resolved = store.declarations(handle, command.tenantKey, command.catalogKey).map { declaration ->
            // A declaration naming a draft builds on that draft, live; there is no pin to move.
            if (declaration.namesDraft) return@map declaration
            store.highest(handle, command.tenantKey, declaration)
                ?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
                ?: declaration
        }
        store.replace(handle, command.tenantKey, command.catalogKey, resolved)
        resolved
    }
}
