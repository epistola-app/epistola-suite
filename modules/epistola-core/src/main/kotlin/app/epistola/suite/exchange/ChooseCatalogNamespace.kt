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
 * Chooses where a catalog publishes, at the moment of publishing.
 *
 * A connection granting several namespaces leaves the tenant default unset, so the first release of
 * a catalog can otherwise queue with nowhere to go and simply wait. Asking at the point of publishing
 * beats sending the author to a settings page to discover what was missing.
 *
 * Deliberately `TEMPLATE_PUBLISH`, not `CATALOG_MANAGE`: deciding where a catalog first publishes is
 * part of publishing it. Moving an *existing* binding is a different act with different
 * consequences, and stays with [RebindCatalogNamespace].
 */
data class ChooseCatalogNamespace(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val namespace: String,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_PUBLISH
}

@Component
class ChooseCatalogNamespaceHandler(
    private val jdbi: Jdbi,
    private val namespaceBinder: ExchangeNamespaceBinder,
) : CommandHandler<ChooseCatalogNamespace, Unit> {

    override fun handle(command: ChooseCatalogNamespace) = jdbi.useTransaction<Exception> { handle ->
        validate(
            "namespace",
            namespaceBinder.existingBinding(handle, command.tenantKey, command.catalogKey) == null,
            ValidationCode.EXCHANGE_NAMESPACE_LOCKED,
        ) { "This catalog already publishes to a namespace; changing it is a separate action." }

        validate(
            "namespace",
            command.namespace in namespaceBinder.grantedNamespaces(handle, command.tenantKey),
            ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE,
        ) { "That namespace is not available to this Exchange connection." }

        namespaceBinder.rebind(handle, command.tenantKey, command.catalogKey, command.namespace)
    }
}
