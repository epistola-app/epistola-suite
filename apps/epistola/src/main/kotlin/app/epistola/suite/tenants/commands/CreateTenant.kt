package app.epistola.suite.tenants.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

data class CreateTenant(
    val id: UUID,
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
                INSERT INTO tenants (id, name, created_at)
                VALUES (:id, :name, NOW())
                RETURNING *
                """,
        )
            .bind("id", command.id)
            .bind("name", command.name)
            .mapTo<Tenant>()
            .one()
    }
}
