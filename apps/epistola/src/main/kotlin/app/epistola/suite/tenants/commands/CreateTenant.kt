package app.epistola.suite.tenants.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class CreateTenant(
    val name: String,
) : Command<Tenant> {
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 255) { "Name must be 255 characters or less" }
    }
}

@Component
class CreateTenantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateTenant, Tenant> {
    override fun handle(command: CreateTenant): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
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
