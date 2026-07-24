// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage.backfill

import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.storage.StoredContent
import org.springframework.stereotype.Component

/**
 * **Transitional — remove with the `content_store` drop (#742).**
 *
 * Reads a blob from the legacy shared `content_store` when it hasn't been migrated to
 * the new stores yet. This lets the app serve correctly *during* the one-time
 * [ContentBackfillRunner] window — including on nodes that didn't run the backfill —
 * so the backfill can run in the background instead of blocking startup:
 *
 * - **Documents:** an old document generated before #738 lives only in `content_store`
 *   until backfilled; the download path falls back here (there is no `content_hash`
 *   pointer for documents, so the fallback is keyed the legacy way).
 * - **Assets:** an asset whose `content_hash` is still NULL hasn't been migrated; the
 *   asset read path falls back here.
 *
 * Once the backfill has completed everywhere and `content_store` is dropped, every
 * document is in `document_content` and every asset has a `content_hash`, so both
 * fallbacks are dead code — deleted together with this class and the legacy
 * [ContentStore] by #742.
 */
@Component
class LegacyBlobFallback(
    private val legacyContentStore: ContentStore,
) {
    /** Legacy document bytes for [documentId], or null if not present in `content_store`. */
    fun documentContent(tenantId: TenantKey, documentId: DocumentKey): StoredContent? = legacyContentStore.get(ContentKey.document(tenantId, documentId))

    /** Legacy asset bytes for [assetId], or null if not present in `content_store`. */
    fun assetBytes(tenantId: TenantKey, assetId: AssetKey): ByteArray? = legacyContentStore.get(ContentKey.asset(tenantId, assetId))?.content?.readAllBytes()
}
