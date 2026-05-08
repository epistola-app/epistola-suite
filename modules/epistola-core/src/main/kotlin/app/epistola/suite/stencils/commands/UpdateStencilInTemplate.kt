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
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.stencils.model.StencilContentReplacer
import app.epistola.suite.templates.validation.PlaceholderValidator
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Upgrades all instances of a stencil within a template variant's draft.
 *
 * Finds the variant's current draft, locates all stencil nodes matching the
 * given stencilId, replaces their content with the new version's content
 * (re-keyed with fresh IDs), preserves user-authored placeholder fills by
 * name, and saves the modified draft.
 *
 * Returns the result (count + dropped fills), or null if the draft doesn't
 * exist.
 */
data class UpdateStencilInTemplate(
    val variantId: VariantId,
    val stencilId: StencilId,
    val newVersion: Int,
) : Command<UpdateStencilInTemplateResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

/**
 * Result of an `UpdateStencilInTemplate` command.
 *
 * @property upgradedCount number of stencil instances replaced.
 * @property droppedFills per stencil-instance node id, the fills that could
 *   not be preserved because the new stencil version no longer declares the
 *   matching placeholder name. Empty when every fill survived.
 */
data class UpdateStencilInTemplateResult(
    val upgradedCount: Int,
    val droppedFills: Map<String, List<StencilContentReplacer.DroppedFill>> = emptyMap(),
)

@Component
class UpdateStencilInTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val placeholderValidator: PlaceholderValidator,
) : CommandHandler<UpdateStencilInTemplate, UpdateStencilInTemplateResult?> {
    override fun handle(command: UpdateStencilInTemplate): UpdateStencilInTemplateResult? = jdbi.inTransaction<UpdateStencilInTemplateResult?, Exception> { handle ->
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
            node.type == StencilNodeKeys.NODE_TYPE &&
                (node.props?.get(StencilNodeKeys.PROP_STENCIL_ID) as? String) == command.stencilId.key.value
        }

        if (stencilNodes.isEmpty()) return@inTransaction UpdateStencilInTemplateResult(upgradedCount = 0)

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
        val upgrade = StencilContentReplacer.upgradeStencilInstances(
            document = templateModel,
            stencilId = command.stencilId.key.value,
            newVersion = command.newVersion,
            newContent = newContent,
        )

        // 4b. Validate the upgraded document — recursion guard, placeholder scope, etc.
        placeholderValidator.validateAsTemplate(upgrade.document)

        // 5. Save the modified draft
        val upgradedJson = objectMapper.writeValueAsString(upgrade.document)
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

        UpdateStencilInTemplateResult(
            upgradedCount = stencilNodes.size,
            droppedFills = upgrade.droppedFills,
        )
    }
}
