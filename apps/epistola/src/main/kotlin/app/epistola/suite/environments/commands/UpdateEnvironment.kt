package app.epistola.suite.environments.commands

import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class UpdateEnvironment(
    val tenantId: Long,
    val id: Long,
    val name: String,
) : Command<Environment?> {
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 100) { "Name must be 100 characters or less" }
    }
}

@Component
class UpdateEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateEnvironment, Environment?> {
    override fun handle(command: UpdateEnvironment): Environment? = jdbi.withHandle<Environment?, Exception> { handle ->
        handle.createQuery(
            """
                UPDATE environments
                SET name = :name
                WHERE id = :id AND tenant_id = :tenantId
                RETURNING *
                """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .bind("name", command.name)
            .mapTo<Environment>()
            .findOne()
            .orElse(null)
    }
}
