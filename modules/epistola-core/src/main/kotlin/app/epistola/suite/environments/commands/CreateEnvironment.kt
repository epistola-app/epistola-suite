package app.epistola.suite.environments.commands

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class CreateEnvironment(
    val id: EnvironmentId,
    val tenantId: TenantId,
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
    override fun handle(command: CreateEnvironment): Environment = executeOrThrowDuplicate("environment", command.id.value) {
        jdbi.withHandle<Environment, Exception> { handle ->
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
}
