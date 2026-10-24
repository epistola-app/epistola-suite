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
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.commands.MarkStencilReady
import app.epistola.suite.templates.commands.MarkTemplateReady
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Marks every new or modified resource of an authored catalog ready in one action (contract
 * `markCatalogReady`): templates and stencils through their own commands, so the same checks
 * apply, every other resource directly. Stops at the first resource that cannot be marked.
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
) : CommandHandler<MarkCatalogReady, CatalogResourceChanges> {
    override fun handle(command: MarkCatalogReady): CatalogResourceChanges {
        requireCatalogEditable(command.catalog)
        val catalogId = command.catalog
        for (change in GetCatalogResourceChanges(command.catalog).query().notReady) {
            when (change.type) {
                "template" -> MarkTemplateReady(TemplateId(TemplateKey.of(change.slug), catalogId)).execute()

                "stencil" -> MarkStencilReady(StencilId(StencilKey.of(change.slug), catalogId)).execute()

                else -> jdbi.useTransaction<Exception> { handle ->
                    readyMarks.resourceId(handle, command.catalog, change.type, change.slug)
                        ?.let { readyMarks.mark(handle, command.tenantKey, it) }
                }
            }
        }
        return GetCatalogResourceChanges(command.catalog).query()
    }
}
