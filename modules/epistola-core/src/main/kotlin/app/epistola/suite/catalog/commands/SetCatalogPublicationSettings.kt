// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class SetCatalogPublicationSettings(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val policy: CatalogPublicationPolicy,
) : Command<Catalog>,
    RequiresPermission {
    override val permission = Permission.CATALOG_MANAGE
}

@Component
class SetCatalogPublicationSettingsHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetCatalogPublicationSettings, Catalog> {
    override fun handle(command: SetCatalogPublicationSettings): Catalog {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        return jdbi.withHandle<Catalog, Exception> { handle ->
            handle.createQuery(
                """
                UPDATE catalogs
                SET exchange_publication_policy = :policy
                WHERE tenant_key = :tenantKey AND id = :catalogKey
                RETURNING *
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("policy", command.policy)
                .mapTo<Catalog>()
                .findOne()
                .orElseThrow { CatalogNotFoundException(command.catalogKey) }
        }
    }
}
