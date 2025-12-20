package app.epistola.suite.tenants.commands

import app.epistola.suite.tenants.Tenant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class CreateTenant(
    val name: String,
)

@Component
class CreateTenantHandler(
    private val jdbi: Jdbi,
) {
    fun handle(command: CreateTenant): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
        handle.createQuery(
            """
                INSERT INTO tenants (name, created_at)
                VALUES (:name, NOW())
                RETURNING *
                """,
        )
            .bind("name", command.name)
            .mapTo<Tenant>()
            .one()
    }
}
