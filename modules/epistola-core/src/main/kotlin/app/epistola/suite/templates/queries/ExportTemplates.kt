package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.commands.ImportTemplateInput
import app.epistola.suite.templates.commands.ImportVariantInput
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// ── Query ───────────────────────────────────────────────────────────────────

data class ExportTemplates(
    val tenantId: TenantId,
) : Query<ExportResult>

data class ExportResult(
    val templates: List<ImportTemplateInput>,
)

// ── Handler ─────────────────────────────────────────────────────────────────

@Component
class ExportTemplatesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ExportTemplates, ExportResult> {

    override fun handle(query: ExportTemplates): ExportResult {
        val tenantKey = query.tenantId.key
        val templates = jdbi.withHandle<List<ImportTemplateInput>, Exception> { handle ->
            // 1. Get all templates for the tenant
            val templateRows = handle.createQuery(
                """
                SELECT id, name, data_model, data_examples
                FROM document_templates
                WHERE tenant_key = :tenantKey
                ORDER BY id
                """,
            )
                .bind("tenantKey", tenantKey)
                .mapToMap()
                .list()

            templateRows.map { row ->
                val templateId = row["id"] as String
                val templateName = row["name"] as String
                val dataModelJson = row["data_model"]?.toString()
                val dataExamplesJson = row["data_examples"]?.toString()

                // 2. Get all variants for this template
                val variantRows = handle.createQuery(
                    """
                    SELECT id, title, attributes, is_default
                    FROM template_variants
                    WHERE tenant_key = :tenantKey AND template_key = :templateId
                    ORDER BY is_default DESC, id
                    """,
                )
                    .bind("tenantKey", tenantKey)
                    .bind("templateId", templateId)
                    .mapToMap()
                    .list()

                // 3. For each variant, get the best template_model:
                //    prefer latest published version, fall back to draft
                data class VariantModel(
                    val templateModel: TemplateDocument,
                    val versionLabel: String,
                )

                val variantModels = mutableMapOf<String, VariantModel>()
                for (variant in variantRows) {
                    val variantId = variant["id"] as String

                    // Try latest published version first
                    val publishedRow = handle.createQuery(
                        """
                        SELECT id, template_model
                        FROM template_versions
                        WHERE tenant_key = :tenantKey
                          AND template_key = :templateId
                          AND variant_key = :variantId
                          AND status = 'published'
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                    )
                        .bind("tenantKey", tenantKey)
                        .bind("templateId", templateId)
                        .bind("variantId", variantId)
                        .mapToMap()
                        .findOne()

                    if (publishedRow.isPresent) {
                        val pr = publishedRow.get()
                        val versionId = pr["id"].toString()
                        val model = objectMapper.readValue(pr["template_model"].toString(), TemplateDocument::class.java)
                        variantModels[variantId] = VariantModel(model, versionId)
                    } else {
                        // Fall back to draft
                        val draftRow = handle.createQuery(
                            """
                            SELECT template_model
                            FROM template_versions
                            WHERE tenant_key = :tenantKey
                              AND template_key = :templateId
                              AND variant_key = :variantId
                              AND status = 'draft'
                            ORDER BY id DESC
                            LIMIT 1
                            """,
                        )
                            .bind("tenantKey", tenantKey)
                            .bind("templateId", templateId)
                            .bind("variantId", variantId)
                            .mapToMap()
                            .findOne()

                        if (draftRow.isPresent) {
                            val model = objectMapper.readValue(
                                draftRow.get()["template_model"].toString(),
                                TemplateDocument::class.java,
                            )
                            variantModels[variantId] = VariantModel(model, "draft")
                        }
                    }
                }

                // 4. Find the default variant's model to use as the top-level templateModel
                val defaultVariantId = variantRows.first { (it["is_default"] as Boolean) }["id"] as String
                val defaultModel = variantModels[defaultVariantId]
                    ?: error("Default variant '$defaultVariantId' for template '$templateId' has no version")

                // 5. Determine the version label: use default variant's version
                val versionLabel = defaultModel.versionLabel

                // 6. Build variant inputs — set templateModel to null when same as default
                val variants = variantRows.map { variant ->
                    val variantId = variant["id"] as String
                    val isDefault = variant["is_default"] as Boolean
                    val attributesJson = variant["attributes"]?.toString() ?: "{}"

                    @Suppress("UNCHECKED_CAST")
                    val attributes: Map<String, String> = objectMapper.readValue(
                        attributesJson,
                        Map::class.java,
                    ) as Map<String, String>

                    val variantModel = variantModels[variantId]?.templateModel
                    val perVariantModel = if (isDefault || variantModel == defaultModel.templateModel) {
                        null
                    } else {
                        variantModel
                    }

                    ImportVariantInput(
                        id = variantId,
                        title = variant["title"] as? String,
                        attributes = attributes,
                        templateModel = perVariantModel,
                        isDefault = isDefault,
                    )
                }

                // 7. Get environment activations for this template
                val publishTo = handle.createQuery(
                    """
                    SELECT DISTINCT ea.environment_key
                    FROM environment_activations ea
                    WHERE ea.tenant_key = :tenantKey
                      AND ea.template_key = :templateId
                    ORDER BY ea.environment_key
                    """,
                )
                    .bind("tenantKey", tenantKey)
                    .bind("templateId", templateId)
                    .mapTo(String::class.java)
                    .list()

                // 8. Parse data model and data examples
                val dataModel = dataModelJson?.let {
                    objectMapper.readValue(it, tools.jackson.databind.node.ObjectNode::class.java)
                }
                val dataExamples: List<app.epistola.suite.templates.model.DataExample> = if (dataExamplesJson != null) {
                    objectMapper.readValue(
                        dataExamplesJson,
                        objectMapper.typeFactory.constructCollectionType(
                            List::class.java,
                            app.epistola.suite.templates.model.DataExample::class.java,
                        ),
                    )
                } else {
                    emptyList()
                }

                ImportTemplateInput(
                    slug = templateId,
                    name = templateName,
                    version = versionLabel,
                    dataModel = dataModel,
                    dataExamples = dataExamples,
                    templateModel = defaultModel.templateModel,
                    variants = variants,
                    publishTo = publishTo,
                )
            }
        }

        return ExportResult(templates = templates)
    }
}
