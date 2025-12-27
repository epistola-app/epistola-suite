package app.epistola.suite.environments.commands

import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class CreateEnvironment(
    val tenantId: Long,
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
                INSERT INTO environments (tenant_id, name, created_at)
                VALUES (:tenantId, :name, NOW())
                RETURNING *
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("name", command.name)
            .mapTo<Environment>()
            .one()
    }
}
