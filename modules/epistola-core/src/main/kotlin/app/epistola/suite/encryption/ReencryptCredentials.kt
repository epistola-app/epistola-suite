// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.encryption

import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Re-encrypts every stored credential under the current primary key.
 *
 * Part of the key-rotation runbook (see `docs/encryption.md`): after a new
 * primary key is added, run this to migrate values off old keys (and to upgrade
 * any legacy plaintext). Idempotent — values already under the primary key are
 * left untouched. Once this reports nothing left to do and
 * [VerifyNoStaleKeyIds] confirms no old key id is referenced, the old key can be
 * retired.
 *
 * Covers the core-owned credential columns (`catalogs`, `code_lists`) plus any
 * `app_metadata` keys named in [metadataKeys] (e.g. the hub credentials key,
 * supplied by the caller so this module stays decoupled from the support tier).
 *
 * `SystemInternal`: an operational/maintenance action with no per-tenant
 * principal to authorize against.
 */
data class ReencryptCredentials(
    val metadataKeys: List<String> = emptyList(),
) : Command<ReencryptResult>,
    SystemInternal

data class ReencryptResult(
    val catalogsReencrypted: Int,
    val codeListsReencrypted: Int,
    val metadataReencrypted: Int,
)

@Component
class ReencryptCredentialsHandler(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
    private val appMetadata: AppMetadataService,
    private val objectMapper: ObjectMapper,
) : CommandHandler<ReencryptCredentials, ReencryptResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class CatalogCredRow(val id: String, val tenantKey: String, val credential: String)
    private data class CodeListCredRow(val slug: String, val tenantKey: String, val catalogKey: String, val credential: String)

    override fun handle(command: ReencryptCredentials): ReencryptResult = jdbi.inTransaction<ReencryptResult, Exception> { handle ->
        val catalogs = reencryptCatalogs(handle)
        val codeLists = reencryptCodeLists(handle)
        val metadata = command.metadataKeys.count { reencryptMetadata(it) }
        ReencryptResult(catalogs, codeLists, metadata).also {
            logger.info(
                "Re-encrypted credentials under primary key '{}': {} catalog(s), {} code list(s), {} metadata key(s)",
                cipher.primaryKeyId,
                it.catalogsReencrypted,
                it.codeListsReencrypted,
                it.metadataReencrypted,
            )
        }
    }

    private fun reencryptCatalogs(handle: Handle): Int {
        val rows = handle.createQuery(
            "SELECT id, tenant_key, source_auth_credential AS credential FROM catalogs WHERE source_auth_credential IS NOT NULL",
        ).mapTo<CatalogCredRow>().list()
        var count = 0
        for (row in rows) {
            val reencrypted = reencryptOrNull(row.credential) ?: continue
            handle.createUpdate(
                "UPDATE catalogs SET source_auth_credential = :cred WHERE id = :id AND tenant_key = :tenantKey",
            )
                .bind("cred", reencrypted)
                .bind("id", row.id)
                .bind("tenantKey", row.tenantKey)
                .execute()
            count++
        }
        return count
    }

    private fun reencryptCodeLists(handle: Handle): Int {
        val rows = handle.createQuery(
            "SELECT slug, tenant_key, catalog_key, credential FROM code_lists WHERE credential IS NOT NULL",
        ).mapTo<CodeListCredRow>().list()
        var count = 0
        for (row in rows) {
            val reencrypted = reencryptOrNull(row.credential) ?: continue
            handle.createUpdate(
                "UPDATE code_lists SET credential = :cred WHERE slug = :slug AND tenant_key = :tenantKey AND catalog_key = :catalogKey",
            )
                .bind("cred", reencrypted)
                .bind("slug", row.slug)
                .bind("tenantKey", row.tenantKey)
                .bind("catalogKey", row.catalogKey)
                .execute()
            count++
        }
        return count
    }

    private fun reencryptMetadata(key: String): Boolean {
        val node = appMetadata.get(key) ?: return false
        val envelope = objectMapper.treeToValue(node, String::class.java)
        val reencrypted = reencryptOrNull(envelope) ?: return false
        appMetadata.set(key, objectMapper.valueToTree(reencrypted))
        return true
    }

    /** Re-encrypts [raw] under the primary key, or null if it is already on the primary key. */
    private fun reencryptOrNull(raw: String): String? {
        if (cipher.keyIdOf(raw) == cipher.primaryKeyId) return null
        return cipher.encrypt(cipher.decrypt(raw))
    }
}
