package app.epistola.suite.tenants.commands

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.validation.validate
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.Margins
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageSettings
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

data class CreateTenant(
    val id: TenantId,
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
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateTenant, Tenant> {
    @Transactional
    override fun handle(command: CreateTenant): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
        // 1. Insert tenant with NULL default_theme_id
        handle.createUpdate(
            """
            INSERT INTO tenants (id, name, created_at)
            VALUES (:id, :name, NOW())
            """,
        )
            .bind("id", command.id)
            .bind("name", command.name)
            .execute()

        // 2. Create default "Tenant Default" theme with sensible defaults
        val themeId = ThemeId.generate()
        val documentStyles = DocumentStyles(
            fontFamily = "Helvetica, Arial, sans-serif",
            fontSize = "11pt",
            color = "#333333",
            lineHeight = "1.5",
        )
        val pageSettings = PageSettings(
            format = PageFormat.A4,
            margins = Margins(top = 20, right = 20, bottom = 20, left = 20),
        )

        handle.createUpdate(
            """
            INSERT INTO themes (id, tenant_id, name, description, document_styles, page_settings, created_at, last_modified)
            VALUES (:id, :tenantId, :name, :description, :documentStyles::jsonb, :pageSettings::jsonb, NOW(), NOW())
            """,
        )
            .bind("id", themeId)
            .bind("tenantId", command.id)
            .bind("name", TENANT_DEFAULT_THEME_NAME)
            .bind("description", "Default theme automatically created for this tenant")
            .bind("documentStyles", objectMapper.writeValueAsString(documentStyles))
            .bind("pageSettings", objectMapper.writeValueAsString(pageSettings))
            .execute()

        // 3. Update tenant's default_theme_id to point to the new theme
        handle.createQuery(
            """
            UPDATE tenants
            SET default_theme_id = :themeId
            WHERE id = :id
            RETURNING *
            """,
        )
            .bind("id", command.id)
            .bind("themeId", themeId)
            .mapTo<Tenant>()
            .one()
    }

    companion object {
        const val TENANT_DEFAULT_THEME_NAME = "Tenant Default"
    }
}
