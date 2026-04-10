package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class ImportAttribute(
    val tenantId: TenantId,
    val slug: String,
    val displayName: String,
    val allowedValues: List<String> = emptyList(),
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ImportAttributeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<ImportAttribute, InstallStatus> {

    override fun handle(command: ImportAttribute): InstallStatus {
        val attributeKey = AttributeKey.of(command.slug)
        val allowedValuesJson = objectMapper.writeValueAsString(command.allowedValues)

        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) > 0 FROM variant_attribute_definitions WHERE id = :id AND tenant_key = :tenantKey")
                .bind("id", attributeKey)
                .bind("tenantKey", command.tenantKey)
                .mapTo(Boolean::class.java)
                .one()
        }

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO variant_attribute_definitions (id, tenant_key, display_name, allowed_values, created_at, last_modified)
                VALUES (:id, :tenantKey, :displayName, :allowedValues::jsonb, NOW(), NOW())
                ON CONFLICT (tenant_key, id) DO UPDATE
                SET display_name = :displayName, allowed_values = :allowedValues::jsonb, last_modified = NOW()
                """,
            )
                .bind("id", attributeKey)
                .bind("tenantKey", command.tenantKey)
                .bind("displayName", command.displayName)
                .bind("allowedValues", allowedValuesJson)
                .execute()
        }

        return if (exists) InstallStatus.UPDATED else InstallStatus.INSTALLED
    }
}
