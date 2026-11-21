// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.commands

import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.interactive.ReturnUrlPrefix
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Sets where an application's interactive generation requests may send a person back to, or link
 * to (contract ADR 0011, "Coming back"): the starts of the URLs its own frontend lives on, such as
 * `https://cases.example.org/zaken`. A URL is allowed when it starts with one, as [ReturnUrlPrefix]
 * defines it (same origin, a path under it at a `/`). Anything else is refused when the request is
 * made, so a leaked key cannot turn Epistola's form into an open redirect. The whole list is
 * replaced; entries are stored normalised.
 *
 * Returns false when the tenant has no such application.
 */
data class SetApplicationReturnUrls(
    override val tenantKey: TenantKey,
    val id: ApplicationKey,
    val urls: List<String>,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE

    init {
        validate("urls", urls.size <= MAX_URLS) { "At most $MAX_URLS addresses" }
        urls.forEachIndexed { i, url ->
            validate("urls[$i]", url.length <= MAX_URL_LENGTH && ReturnUrlPrefix.parse(url) != null) {
                "$url is not an address forms can return to: give an http(s) address without a query, such as https://cases.example.org/zaken"
            }
        }
    }

    /** The entries as they are stored and matched. */
    val normalised: List<String> get() = urls.mapNotNull(ReturnUrlPrefix::parse).map(ReturnUrlPrefix::toString).distinct().sorted()

    companion object {
        const val MAX_URLS = 20
        const val MAX_URL_LENGTH = 500
    }
}

@Component
class SetApplicationReturnUrlsHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetApplicationReturnUrls, Boolean> {
    override fun handle(command: SetApplicationReturnUrls): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate("UPDATE applications SET allowed_return_urls = :urls WHERE id = :id AND tenant_key = :tenantKey")
            .bind("id", command.id)
            .bind("tenantKey", command.tenantKey)
            .bindArray("urls", String::class.java, *command.normalised.toTypedArray())
            .execute() > 0
    }
}
