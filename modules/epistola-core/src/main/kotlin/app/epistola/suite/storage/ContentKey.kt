package app.epistola.suite.storage

import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TenantKey

/**
 * Builds storage keys following S3-style path conventions.
 *
 * Pattern: `{type}/{tenantId}/{entityId}`
 */
object ContentKey {

    fun asset(tenantId: TenantKey, assetId: AssetKey): String = "assets/${tenantId.value}/${assetId.value}"

    fun document(tenantId: TenantKey, documentId: DocumentKey): String = "documents/${tenantId.value}/${documentId.value}"
}
