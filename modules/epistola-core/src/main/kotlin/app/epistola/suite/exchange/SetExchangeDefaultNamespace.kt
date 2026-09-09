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

data class SetExchangeDefaultNamespace(
    override val tenantKey: TenantKey,
    val namespace: String,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

@Component
class SetExchangeDefaultNamespaceHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetExchangeDefaultNamespace, Unit> {
    override fun handle(command: SetExchangeDefaultNamespace) {
        jdbi.useHandle<Exception> { handle ->
            val updated = handle.createUpdate(
                """
                UPDATE exchange_tenant_connections
                SET default_namespace = :namespace, updated_at = NOW()
                WHERE tenant_key = :tenantKey AND status = 'ACTIVE' AND :namespace = ANY(namespaces)
                """,
            ).bind("tenantKey", command.tenantKey).bind("namespace", command.namespace).execute()
            validate("namespace", updated == 1, ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE) {
                "That namespace is not available to this Exchange connection."
            }
        }
    }
}
