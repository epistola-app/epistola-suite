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
 * While nothing has published, this is routine: the binding is a local decision and changing it
 * costs nothing. Once a release has reached Exchange it becomes consequential, and
 * [acknowledgeAlreadyPublished] is required — a move is possible, but never accidental.
 *
 * What a move does and does not do is the important part. Versions already published stay exactly
 * where they are: Exchange holds them under the old namespace and Suite cannot and should not move
 * them. Only *future* releases go somewhere new. The cost is downstream — anyone consuming the old
 * namespace keeps seeing the versions already there and never sees another one, with nothing to tell
 * them the catalog continued elsewhere.
 *
 * TODO(#exchange-move-impact): Exchange is the only side that knows whether the old namespace
 *  actually has consumers. Before a move it should be asked, so a move nobody depends on can be
 *  waved through and one that would strand subscribers can say how many. Longer term Exchange also
 *  needs a way to express "this catalog continues at X" so they can follow. Both are tracked as
 *  deferred work in `docs/catalog-exchange-publication.md`.
 *
 * `CATALOG_PUBLISH`, the permission for sending a release out of this installation at all — a
 * namespace is where it lands, so choosing one is part of the same act.
 */
data class SetCatalogPublicationNamespace(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val namespace: String,
    /** Explicit confirmation that already-published versions stay under the previous namespace. */
    val acknowledgeAlreadyPublished: Boolean = false,
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

        val alreadyPublished = namespaceBinder.isLocked(handle, command.tenantKey, command.catalogKey)
        validate(
            "namespace",
            !alreadyPublished || command.acknowledgeAlreadyPublished,
            ValidationCode.EXCHANGE_NAMESPACE_LOCKED,
        ) {
            "A release of this catalog has already reached Exchange. Moving it leaves those versions " +
                "under '$existing' and only affects future releases; confirm that explicitly to proceed."
        }

        validate(
            "namespace",
            command.namespace in namespaceBinder.grantedNamespaces(handle, command.tenantKey),
            ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE,
        ) { "That namespace is not available to this Exchange connection." }

        namespaceBinder.bind(handle, command.tenantKey, command.catalogKey, command.namespace)
        // The new namespace has published nothing, so the catalog is freely movable again until it
        // does. What was published under the old one stays there and is not tracked here — Exchange
        // holds it, which is also why only Exchange can say who still depends on it.
        namespaceBinder.clearPublished(handle, command.tenantKey, command.catalogKey)
        // Queued work follows the catalog: none of it has been submitted anywhere yet.
        store.repointUnsubmitted(handle, command.tenantKey, command.catalogKey, command.namespace)
    }
}
