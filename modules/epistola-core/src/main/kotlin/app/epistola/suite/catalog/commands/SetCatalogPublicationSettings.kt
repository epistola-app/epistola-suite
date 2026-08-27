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
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class SetCatalogPublicationSettings(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val policy: CatalogPublicationPolicy,
    val namespacePreference: String?,
) : Command<Catalog>,
    RequiresPermission {
    override val permission = Permission.CATALOG_MANAGE

    init {
        validate(
            "namespacePreference",
            namespacePreference == null || namespacePreference.matches(Regex("^${CatalogPublicationPolicy.NAMESPACE_PATTERN}$")),
        ) { "Exchange namespace must be a lowercase slug." }
    }
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
                SET exchange_publication_policy = :policy,
                    exchange_namespace_preference = :namespacePreference
                WHERE tenant_key = :tenantKey AND id = :catalogKey
                RETURNING *
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("policy", command.policy)
                .bind("namespacePreference", command.namespacePreference?.trim()?.ifBlank { null })
                .mapTo<Catalog>()
                .findOne()
                .orElseThrow { CatalogNotFoundException(command.catalogKey) }
        }
    }
}
