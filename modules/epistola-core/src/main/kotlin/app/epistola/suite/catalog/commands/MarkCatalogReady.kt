// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.queries.CatalogResourceChanges
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Marks every new or modified resource of an authored catalog ready in one action (contract
 * `markCatalogReady`): a type with checks of its own (templates, stencils) through them, so the same
 * checks apply, every other resource directly. Stops at the first resource that cannot be marked.
 *
 * @return the changes that remain, with their readiness.
 */
data class MarkCatalogReady(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<CatalogResourceChanges>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT
}

@Component
class MarkCatalogReadyHandler(
    private val jdbi: Jdbi,
    private val readyMarks: ReadyMarks,
    private val types: app.epistola.suite.catalog.types.ResourceTypes,
) : CommandHandler<MarkCatalogReady, CatalogResourceChanges> {
    override fun handle(command: MarkCatalogReady): CatalogResourceChanges {
        requireCatalogEditable(command.catalog)
        val catalogId = command.catalog
        for (change in GetCatalogResourceChanges(command.catalog).query().notReady) {
            // A type with checks of its own marks through them (ADR 0039); every other change is marked here.
            if (types.find(change.type)?.markReady(catalogId, change.slug) == true) continue
            jdbi.useTransaction<Exception> { handle ->
                readyMarks.resourceId(handle, command.catalog, change.type, change.slug)
                    ?.let { readyMarks.mark(handle, command.tenantKey, it) }
            }
        }
        return GetCatalogResourceChanges(command.catalog).query()
    }
}
