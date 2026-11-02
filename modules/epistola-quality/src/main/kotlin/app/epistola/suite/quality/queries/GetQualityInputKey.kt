// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.templateIn
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A variant's quality input key in one catalog version; [inputKey] null when that version does not hold it. */
data class QualityInputKey(
    val inputKey: String?,
)

/**
 * What a check of [variantId] reads in [catalogVersion] (its catalog's next version when null), as a
 * hash (ADR 0044; `quality_input_key` in SQL): the template's and the variant's content, what they
 * refer to as that version resolves it, its default theme and the tenant's language. A check's
 * findings are stored under it, and every version whose key for the variant is the same shows them.
 *
 * Asked before a check reads the variant, so a save while it runs leaves its findings under the
 * older key, shown as outdated, and never under the newer one, where they would claim to be current.
 */
data class GetQualityInputKey(
    val variantId: VariantId,
    override val catalogVersion: CatalogReleaseRef? = null,
) : Query<QualityInputKey>,
    ReleaseReadable,
    RequiresPermission {
    override val tenantKey: TenantKey get() = variantId.tenantKey
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetQualityInputKeyHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetQualityInputKey, QualityInputKey> {
    override fun handle(query: GetQualityInputKey): QualityInputKey = jdbi.withHandle<QualityInputKey, Exception> { handle ->
        val key = handle.createQuery(
            """
            SELECT q.input_key FROM quality_current_variants q
            WHERE q.tenant_key = :tenantKey AND q.catalog_id = ${catalogAt("tenantKey", "catalogKey")}
              AND q.template_resource_id = ${templateIn("tenantKey", "catalogKey", "templateKey")} AND q.variant_key = :variantKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bindCatalog("catalogKey", query.variantId.catalogId)
            .bind("templateKey", query.variantId.templateKey)
            .bind("variantKey", query.variantId.key.value)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
        QualityInputKey(key)
    }
}
