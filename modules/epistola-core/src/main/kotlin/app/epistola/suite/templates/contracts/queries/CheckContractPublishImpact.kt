// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.SchemaCompatibilityChecker
import app.epistola.suite.templates.contracts.TemplateVersionCompatibilityEvaluator
import app.epistola.suite.templates.contracts.commands.IncompatibleVersion
import app.epistola.suite.templates.contracts.model.ContractVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Previews the full impact of publishing the current draft contract.
 *
 * Loads the draft and latest published contracts, runs the structural schema diff,
 * then for each published/archived template version on the old contract, checks
 * whether it actually uses any of the affected fields.
 */
data class CheckContractPublishImpact(
    val templateId: TemplateId,
) : Query<ContractPublishImpact?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class ContractPublishImpact(
    val compatible: Boolean,
    val breakingChanges: List<SchemaCompatibilityChecker.BreakingChange>,
    val incompatibleVersions: List<IncompatibleVersion>,
    val compatibleVersionCount: Int,
)

@Component
class CheckContractPublishImpactHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<CheckContractPublishImpact, ContractPublishImpact?> {

    private val checker = SchemaCompatibilityChecker()
    private val evaluator = TemplateVersionCompatibilityEvaluator()

    override fun handle(query: CheckContractPublishImpact): ContractPublishImpact? {
        // Load draft and latest published contract
        val draft = jdbi.withHandle<ContractVersion?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                """,
            )
                .bind("tenantKey", query.templateId.tenantKey)
                .bind("catalogKey", query.templateId.catalogKey)
                .bind("templateKey", query.templateId.key)
                .mapTo<ContractVersion>()
                .findOne()
                .orElse(null)
        } ?: return null

        val previousPublished = jdbi.withHandle<ContractVersion?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'published'
                ORDER BY id DESC LIMIT 1
                """,
            )
                .bind("tenantKey", query.templateId.tenantKey)
                .bind("catalogKey", query.templateId.catalogKey)
                .bind("templateKey", query.templateId.key)
                .mapTo<ContractVersion>()
                .findOne()
                .orElse(null)
        }

        // Structural schema comparison
        val compatibilityResult = if (previousPublished != null) {
            checker.checkCompatibility(previousPublished.dataModel, draft.dataModel)
        } else {
            SchemaCompatibilityChecker.CompatibilityResult(compatible = true, breakingChanges = emptyList())
        }

        if (compatibilityResult.compatible || previousPublished == null) {
            return ContractPublishImpact(
                compatible = true,
                breakingChanges = emptyList(),
                incompatibleVersions = emptyList(),
                compatibleVersionCount = 0,
            )
        }

        // For breaking changes, check each published/archived version's actual field
        // usage. referenced_paths rides along so the whole impact is computed from this
        // one query — no per-version fan-out.
        val versionRows = jdbi.withHandle<List<Map<String, Any?>>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT tv.variant_key, tv.id as version_id, tv.referenced_paths,
                       COALESCE(
                           (SELECT jsonb_agg(ea.environment_key ORDER BY ea.environment_key)
                            FROM environment_activations ea
                            WHERE ea.tenant_key = tv.tenant_key AND ea.catalog_key = tv.catalog_key
                              AND ea.template_key = tv.template_key AND ea.variant_key = tv.variant_key
                              AND ea.version_key = tv.id),
                           '[]'::jsonb
                       ) as active_environments
                FROM template_versions tv
                WHERE tv.tenant_key = :tenantKey AND tv.catalog_key = :catalogKey
                  AND tv.template_key = :templateKey AND tv.contract_version = :oldVersion
                  AND tv.status IN ('published', 'archived')
                """,
            )
                .bind("tenantKey", query.templateId.tenantKey)
                .bind("catalogKey", query.templateId.catalogKey)
                .bind("templateKey", query.templateId.key)
                .bind("oldVersion", previousPublished.id.value)
                .mapToMap()
                .list()
        }

        val incompatibleVersions = mutableListOf<IncompatibleVersion>()
        var compatibleCount = 0

        for (row in versionRows) {
            val variantKey = VariantKey.of(row["variant_key"] as String)
            val versionKey = VersionKey.of(row["version_id"] as Int)
            val versionId = VersionId(versionKey, VariantId(variantKey, query.templateId))

            // Every row is on the old contract (tv.contract_version = :oldVersion), so
            // previousPublished.dataModel IS each version's old schema.
            val referencedPaths = objectMapper
                .readStringArrayColumn(
                    row["referenced_paths"]?.toString() ?: "[]",
                    "template_versions.referenced_paths for $versionId",
                )
                .toSet()
            val templateCompat = evaluator.evaluate(referencedPaths, previousPublished.dataModel, draft.dataModel)

            if (!templateCompat.compatible) {
                val envJson = row["active_environments"]?.toString() ?: "[]"
                val envList = objectMapper.readStringArrayColumn(envJson, "active environments for $versionId")
                incompatibleVersions.add(
                    IncompatibleVersion(
                        variantKey = variantKey,
                        versionId = versionKey,
                        activeEnvironments = envList,
                        incompatibilities = templateCompat.incompatibilities,
                    ),
                )
            } else {
                compatibleCount++
            }
        }

        return ContractPublishImpact(
            compatible = incompatibleVersions.isEmpty(),
            breakingChanges = compatibilityResult.breakingChanges,
            incompatibleVersions = incompatibleVersions,
            compatibleVersionCount = compatibleCount,
        )
    }
}
