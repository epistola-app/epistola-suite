// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Opens a draft numbered [version], from its base or from [from] (contract `createCatalogVersion`).
 * Not yet: an authored catalog always has its one draft, and a second needs content kept per draft.
 */
data class CreateCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val from: String? = null,
) : Command<CatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH
}

/** Discards a draft (contract `deleteCatalogVersion`). Not yet: the one draft is the working copy. */
data class DeleteCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH
}

@Component
class CreateCatalogVersionHandler : CommandHandler<CreateCatalogVersion, CatalogVersion> {
    override fun handle(command: CreateCatalogVersion): CatalogVersion = throw SingleCatalogDraftException(
        command.catalogKey,
        "Catalog '${command.catalogKey.value}' has its one draft; several drafts at once are not supported yet. Renumber the draft instead.",
    )
}

@Component
class DeleteCatalogVersionHandler : CommandHandler<DeleteCatalogVersion, Unit> {
    override fun handle(command: DeleteCatalogVersion): Unit = throw SingleCatalogDraftException(
        command.catalogKey,
        "The draft of catalog '${command.catalogKey.value}' is its working copy and cannot be discarded yet.",
    )
}
