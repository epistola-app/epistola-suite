// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.stencils.model.StencilContentReplacer
import app.epistola.suite.templates.commands.versions.DraftVersionFactory
import app.epistola.suite.templates.validation.PlaceholderValidator
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Upgrades all instances of a stencil within a template variant's draft.
 *
 * Ensures the variant has an open draft (creating one seeded from the latest
 * published version when the template is published and has none), then locates
 * all stencil nodes matching the given stencilId, replaces their content with
 * the new version's content (re-keyed with fresh IDs), preserves user-authored
 * placeholder fills by name, and saves the modified draft. The live published
 * version is never touched — a published template is upgraded in a new draft
 * that the user publishes separately.
 *
 * Returns the result (count + dropped fills), or null if the variant doesn't
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
    val droppedBindings: Map<String, List<StencilContentReplacer.DroppedBinding>> = emptyMap(),
    val unboundRequired: Map<String, List<String>> = emptyMap(),
)

@Component
class UpdateStencilInTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val placeholderValidator: PlaceholderValidator,
    private val nodeParameterBindingValidator: app.epistola.suite.templates.validation.NodeParameterBindingValidator,
    private val draftVersionFactory: DraftVersionFactory,
) : CommandHandler<UpdateStencilInTemplate, UpdateStencilInTemplateResult?> {
    override fun handle(command: UpdateStencilInTemplate): UpdateStencilInTemplateResult? {
        requireCatalogEditable(command.variantId.tenantKey, command.variantId.catalogKey)
        return jdbi.inTransaction<UpdateStencilInTemplateResult?, Exception> { handle ->
            // 1. Ensure the variant has an open draft to upgrade into, atomically in
            //    this transaction. A published template has no draft, so this creates
            //    one seeded from the latest published version; the live published
            //    version stays untouched until the new draft is itself published.
            //    Idempotent — an existing draft is reused. Null = variant not found.
            val draft = draftVersionFactory.ensureDraft(handle, command.variantId)
                ?: return@inTransaction null

            val draftVersionId = draft.id.value
            val templateModel = draft.templateModel

            // 2. Count stencil instances before upgrade
            val stencilNodes = templateModel.nodes.values.filter { node ->
                node.type == StencilNodeKeys.NODE_TYPE &&
                    (node.props?.get(StencilNodeKeys.PROP_STENCIL_ID) as? String) == command.stencilId.key.value
            }

            if (stencilNodes.isEmpty()) return@inTransaction UpdateStencilInTemplateResult(upgradedCount = 0)

            // 3. Fetch the new stencil version's content + parameter schema
            val newStencilRow = handle.createQuery(
                """
            SELECT content::text AS content, parameter_schema::text AS parameter_schema
            FROM stencil_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND id = :versionId
            """,
            )
                .bind("tenantId", command.stencilId.tenantKey)
                .bind("catalogKey", command.stencilId.catalogKey)
                .bind("stencilId", command.stencilId.key)
                .bind("versionId", command.newVersion)
                .mapToMap()
                .findOne()
                .orElse(null)
                ?: throw ValidationException("newVersion", "Stencil version ${command.newVersion} not found")

            val newContent = objectMapper.readValue(
                newStencilRow["content"].toString(),
                app.epistola.template.model.TemplateDocument::class.java,
            )
            val newParameterSchema: tools.jackson.databind.JsonNode? = (newStencilRow["parameter_schema"] as? String)
                ?.let { objectMapper.readTree(it) }

            // 4. Upgrade all instances
            val upgrade = StencilContentReplacer.upgradeStencilInstances(
                document = templateModel,
                stencilId = command.stencilId.key.value,
                newVersion = command.newVersion,
                newContent = newContent,
                newParameterSchema = newParameterSchema,
            )

            // 4b. Validate the upgraded document — recursion guard, placeholder scope, etc.
            placeholderValidator.validateAsTemplate(upgrade.document)
            nodeParameterBindingValidator.validate(upgrade.document)

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
                droppedBindings = upgrade.droppedBindings,
                unboundRequired = upgrade.unboundRequired,
            )
        }
    }
}
