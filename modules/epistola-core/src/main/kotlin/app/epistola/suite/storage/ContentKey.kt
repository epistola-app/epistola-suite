package app.epistola.suite.storage

import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.TenantId

/**
 * Builds storage keys following S3-style path conventions.
 *
 * Pattern: `{type}/{tenantId}/{entityId}`
 */
object ContentKey {

    fun asset(tenantId: TenantId, assetId: AssetId): String = "assets/${tenantId.value}/${assetId.value}"

    fun document(tenantId: TenantId, documentId: DocumentId): String = "documents/${tenantId.value}/${documentId.value}"
}
