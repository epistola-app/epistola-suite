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
 * Sets where a catalog publishes, whether or not it has been set before.
 *
 * Choosing a namespace for the first time and moving an existing choice are the same act while
 * nothing has been published: the binding is a local decision until a release reaches Exchange, and
 * permanent immediately afterwards. Both the publish forms and the catalog's publication settings
 * go through here, so there is one rule about when the destination may change.
 *
 * `CATALOG_PUBLISH`, the permission for sending a release out of this installation at all — a
 * namespace is where it lands, so choosing one is part of the same act.
 */
data class SetCatalogPublicationNamespace(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val namespace: String,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.CATALOG_PUBLISH
}

@Component
class SetCatalogPublicationNamespaceHandler(
    private val jdbi: Jdbi,
    private val store: CatalogPublicationStore,
    private val namespaceBinder: ExchangeNamespaceBinder,
) : CommandHandler<SetCatalogPublicationNamespace, Unit> {

    override fun handle(command: SetCatalogPublicationNamespace) = jdbi.useTransaction<Exception> { handle ->
        val existing = namespaceBinder.existingBinding(handle, command.tenantKey, command.catalogKey)
        // The publish forms always submit the destination they displayed, so re-confirming the
        // current one must be a no-op rather than an error — including once it is locked.
        if (existing == command.namespace) return@useTransaction

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

        namespaceBinder.bind(handle, command.tenantKey, command.catalogKey, command.namespace)
        // Queued work follows the catalog: none of it has been submitted anywhere yet.
        store.repointUnsubmitted(handle, command.tenantKey, command.catalogKey, command.namespace)
    }
}
