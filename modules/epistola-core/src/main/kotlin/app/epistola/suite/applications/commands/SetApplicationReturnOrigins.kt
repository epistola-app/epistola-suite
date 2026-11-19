// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.commands

import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.interactive.Origins
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Sets where an application's interactive generation requests may send a person back to, or link
 * to (contract ADR 0011, "Coming back"): the origins its own frontend lives on. Anything else is
 * refused when the request is made, so a leaked key cannot turn Epistola's form into an open
 * redirect. Each entry is an origin alone (`https://cases.example.org`, a port when not the
 * default), stored normalised; a path, query or wildcard is refused.
 *
 * Returns false when the tenant has no such application.
 */
data class SetApplicationReturnOrigins(
    override val tenantKey: TenantKey,
    val id: ApplicationKey,
    val origins: List<String>,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE

    init {
        validate("origins", origins.size <= MAX_ORIGINS) { "At most $MAX_ORIGINS origins" }
        origins.forEachIndexed { i, origin ->
            validate("origins[$i]", Origins.normalise(origin) != null) {
                "$origin is not an origin: give the scheme and host only, such as https://cases.example.org"
            }
        }
    }

    /** The origins as they are stored and compared. */
    val normalised: List<String> get() = origins.mapNotNull(Origins::normalise).distinct().sorted()

    companion object {
        const val MAX_ORIGINS = 20
    }
}

@Component
class SetApplicationReturnOriginsHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetApplicationReturnOrigins, Boolean> {
    override fun handle(command: SetApplicationReturnOrigins): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate("UPDATE applications SET allowed_return_origins = :origins WHERE id = :id AND tenant_key = :tenantKey")
            .bind("id", command.id)
            .bind("tenantKey", command.tenantKey)
            .bindArray("origins", String::class.java, *command.normalised.toTypedArray())
            .execute() > 0
    }
}
