// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateStore
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DeleteDocumentTemplate(
    val id: TemplateId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class DeleteDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val templates: TemplateStore,
) : CommandHandler<DeleteDocumentTemplate, Boolean> {
    override fun handle(command: DeleteDocumentTemplate): Boolean {
        requireCatalogEditable(command.id.catalogId)
        return jdbi.withHandle<Boolean, Exception> { handle -> templates.delete(handle, command.id) }
    }
}
