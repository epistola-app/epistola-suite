package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilContentReplacer
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Upgrades all instances of a stencil within a template variant's draft.
 *
 * Finds the variant's current draft, locates all stencil nodes matching
 * the given stencilId, replaces their content with the new version's content
 * (re-keyed with fresh IDs), and saves the modified draft.
 *
 * Returns the number of stencil instances upgraded, or null if the draft doesn't exist.
 */
data class UpdateStencilInTemplate(
    val variantId: VariantId,
    val stencilId: StencilId,
    val newVersion: Int,
) : Command<Int?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class UpdateStencilInTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateStencilInTemplate, Int?> {
    override fun handle(command: UpdateStencilInTemplate): Int? = jdbi.inTransaction<Int?, Exception> { handle ->
        // 1. Load the draft's template_model
        val draftRow = handle.createQuery(
            """
            SELECT id, template_model
            FROM template_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND template_key = :templateId
              AND variant_key = :variantId AND status = 'draft'
            """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("templateId", command.variantId.templateKey)
            .bind("variantId", command.variantId.key)
            .mapToMap()
            .findOne()
            .orElse(null) ?: return@inTransaction null

        val draftVersionId = draftRow["id"] as Int
        val templateModelJson = draftRow["template_model"].toString()
        val templateModel = objectMapper.readValue(
            templateModelJson,
            app.epistola.template.model.TemplateDocument::class.java,
        )

        // 2. Count stencil instances before upgrade
        val stencilNodes = templateModel.nodes.values.filter { node ->
            node.type == "stencil" &&
                (node.props?.get("stencilId") as? String) == command.stencilId.key.value
        }

        if (stencilNodes.isEmpty()) return@inTransaction 0

        // 3. Fetch the new stencil version's content
        val newStencilVersionId = StencilVersionId(VersionKey.of(command.newVersion), command.stencilId)
        val newStencilVersion = handle.createQuery(
            """
            SELECT content FROM stencil_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND id = :versionId
            """,
        )
            .bind("tenantId", command.stencilId.tenantKey)
            .bind("catalogKey", command.stencilId.catalogKey)
            .bind("stencilId", command.stencilId.key)
            .bind("versionId", command.newVersion)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
            ?: throw ValidationException("newVersion", "Stencil version ${command.newVersion} not found")

        val newContent = objectMapper.readValue(
            newStencilVersion,
            app.epistola.template.model.TemplateDocument::class.java,
        )

        // 4. Upgrade all instances
        val upgradedModel = StencilContentReplacer.upgradeStencilInstances(
            document = templateModel,
            stencilId = command.stencilId.key.value,
            newVersion = command.newVersion,
            newContent = newContent,
        )

        // 5. Save the modified draft
        val upgradedJson = objectMapper.writeValueAsString(upgradedModel)
        handle.createUpdate(
            """
            UPDATE template_versions SET template_model = :templateModel::jsonb
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND template_key = :templateId
              AND variant_key = :variantId AND id = :versionId AND status = 'draft'
            """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("templateId", command.variantId.templateKey)
            .bind("variantId", command.variantId.key)
            .bind("versionId", draftVersionId)
            .bind("templateModel", upgradedJson)
            .execute()

        stencilNodes.size
    }
}
