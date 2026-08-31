// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Withdraws a publication an administrator no longer wants.
 *
 * Queueing the wrong release is an ordinary mistake, and without this the only ways out were to wait
 * for Exchange to decide, let the retries run out, or disconnect the whole tenant. Cancelling
 * releases the retained archive and leaves the attempt in the history rather than deleting it.
 *
 * A publication Exchange is already holding cannot be cancelled: dropping it locally would not stop
 * Exchange publishing it, it would only stop Suite from ever learning what happened.
 */
data class CancelCatalogPublication(
    override val tenantKey: TenantKey,
    val publicationId: UUID,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.CATALOG_PUBLISH
}

@Component
class CancelCatalogPublicationHandler(
    private val jdbi: Jdbi,
    private val store: CatalogPublicationStore,
) : CommandHandler<CancelCatalogPublication, Unit> {

    override fun handle(command: CancelCatalogPublication) = jdbi.useTransaction<Exception> { handle ->
        val publication = store.find(handle, command.publicationId)
        validate("publication", publication != null, ValidationCode.PUBLICATION_UNAVAILABLE) {
            "That publication no longer exists."
        }
        validate("publication", publication!!.tenantKey == command.tenantKey, ValidationCode.PUBLICATION_UNAVAILABLE) {
            "That publication no longer exists."
        }
        validate("publication", publication.status.isCancellable, ValidationCode.PUBLICATION_NOT_CANCELLABLE) {
            if (publication.status == CatalogPublicationStatus.SUBMITTED) {
                "Exchange is already processing this release; wait for its decision."
            } else {
                "This publication has already finished."
            }
        }
        store.cancel(handle, publication.id, "Withdrawn before it was published.")
    }
}
