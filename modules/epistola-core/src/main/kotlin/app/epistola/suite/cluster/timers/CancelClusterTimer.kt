// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster.timers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * Cancels a one-shot cluster timer.
 *
 * When `tenantKey` is supplied, the cancel is tenant-scoped and will not delete
 * a system timer or another tenant's timer with the same key.
 */
data class CancelClusterTimer(
    val timerKey: String,
    val tenantKey: TenantKey? = null,
) : Command<Boolean>,
    SystemInternal

/**
 * Command handler for the mediator-facing timer cancellation API.
 */
@Component
class CancelClusterTimerHandler(
    private val registry: ClusterTimerRegistry,
) : CommandHandler<CancelClusterTimer, Boolean> {
    override fun handle(command: CancelClusterTimer): Boolean = registry.cancel(command.timerKey, command.tenantKey)
}
