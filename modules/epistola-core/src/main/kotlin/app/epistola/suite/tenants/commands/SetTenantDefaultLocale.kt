// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.commands

import app.epistola.suite.attributes.codelists.queries.CodeListEntryExists
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Routable
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.Tenant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Sets or clears the tenant's default locale override. A null [locale] clears
 * the override, reverting the tenant to the application-wide default
 * (`epistola.i18n.default-locale`). When non-null, the value must exist as an
 * entry in the `system.bcp-47` code list.
 */
data class SetTenantDefaultLocale(
    override val tenantId: TenantKey,
    val locale: String?,
) : Command<Tenant>,
    TenantScoped,
    EntityIdentifiable,
    Routable,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
    override val tenantKey: TenantKey get() = tenantId
    override val entityId: String get() = tenantId.value
    override val routingKey: String get() = tenantId.value
}

class InvalidLocaleException(
    val locale: String,
) : RuntimeException("Locale '$locale' is not a valid code in system.bcp-47")

@Component
class SetTenantDefaultLocaleHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetTenantDefaultLocale, Tenant> {
    @Transactional
    override fun handle(command: SetTenantDefaultLocale): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
        if (command.locale != null) {
            val exists = CodeListEntryExists(
                tenantKey = command.tenantId,
                catalogKey = SYSTEM_CATALOG_KEY,
                codeListSlug = CodeListKey.of("bcp-47"),
                code = command.locale,
            ).query()
            if (!exists) {
                throw InvalidLocaleException(command.locale)
            }
        }

        handle.createQuery(
            """
            UPDATE tenants
            SET default_locale = :locale
            WHERE id = :tenantId
            RETURNING *
            """,
        )
            .bind("tenantId", command.tenantId)
            .bind("locale", command.locale)
            .mapTo<Tenant>()
            .one()
    }
}
