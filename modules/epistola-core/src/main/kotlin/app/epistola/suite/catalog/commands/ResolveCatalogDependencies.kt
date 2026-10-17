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
 * Moves every dependency lock of an authored catalog to the highest release its selector matches,
 * as upgrading a library does. A declaration that matches no release keeps its lock, and stays
 * unlocked when it had none. The next release is cut with the locks this leaves.
 */
data class ResolveCatalogDependencies(
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
class ResolveCatalogDependenciesHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : CommandHandler<ResolveCatalogDependencies, List<CatalogDependencyDeclaration>> {

    override fun handle(command: ResolveCatalogDependencies): List<CatalogDependencyDeclaration> = jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
        val catalog = handle.findCatalog(command.tenantKey, command.catalogKey)
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.type == CatalogType.SUBSCRIBED) throw CatalogReadOnlyException(command.catalogKey)
        val resolved = store.declarations(handle, command.tenantKey, command.catalogKey).map { declaration ->
            store.highestMatch(handle, command.tenantKey, declaration)
                ?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
                ?: declaration
        }
        store.replace(handle, command.tenantKey, command.catalogKey, resolved)
        resolved
    }
}
