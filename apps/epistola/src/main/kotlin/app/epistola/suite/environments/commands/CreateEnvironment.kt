package app.epistola.suite.environments.commands

import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

data class CreateEnvironment(
    val id: UUID,
    val tenantId: UUID,
    val name: String,
) : Command<Environment> {
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 100) { "Name must be 100 characters or less" }
    }
}

@Component
class CreateEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateEnvironment, Environment> {
    override fun handle(command: CreateEnvironment): Environment = jdbi.withHandle<Environment, Exception> { handle ->
        handle.createQuery(
            """
                INSERT INTO environments (id, tenant_id, name, created_at)
                VALUES (:id, :tenantId, :name, NOW())
                RETURNING *
                """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .bind("name", command.name)
            .mapTo<Environment>()
            .one()
    }
}
