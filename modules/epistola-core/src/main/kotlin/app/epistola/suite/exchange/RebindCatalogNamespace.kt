// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
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
 * Moves a catalog to a different Exchange namespace, while that is still meaningful.
 *
 * A catalog publishes into exactly one namespace for its whole life, and that has to stay true —
 * released coordinates cannot move. But the guarantee only starts once Exchange has seen a release.
 * Until then the binding is a local choice, and freezing a mistake forever is a worse answer than
 * letting an administrator correct it.
 */
data class RebindCatalogNamespace(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val namespace: String,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.CATALOG_MANAGE
}

@Component
class RebindCatalogNamespaceHandler(
    private val jdbi: Jdbi,
    private val store: CatalogPublicationStore,
    private val namespaceBinder: ExchangeNamespaceBinder,
) : CommandHandler<RebindCatalogNamespace, Unit> {

    override fun handle(command: RebindCatalogNamespace) = jdbi.useTransaction<Exception> { handle ->
        validate(
            "namespace",
            !namespaceBinder.isLocked(handle, command.tenantKey, command.catalogKey),
            ValidationCode.EXCHANGE_NAMESPACE_LOCKED,
        ) { "A release of this catalog has already reached Exchange, so its namespace is fixed." }

        validate(
            "namespace",
            command.namespace in namespaceBinder.grantedNamespaces(handle, command.tenantKey),
            ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE,
        ) { "That namespace is not available to this Exchange connection." }

        namespaceBinder.rebind(handle, command.tenantKey, command.catalogKey, command.namespace)
        // Queued work follows the catalog: none of it has been submitted anywhere yet.
        store.repointUnsubmitted(handle, command.tenantKey, command.catalogKey, command.namespace)
    }
}
