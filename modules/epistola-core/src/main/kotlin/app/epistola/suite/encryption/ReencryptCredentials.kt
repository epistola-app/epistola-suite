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
 * Credential-owning domains contribute their columns through
 * [EncryptedCredentialContributor]. The command also covers `app_metadata` keys
 * named in [metadataKeys] (supplied by the caller so core stays decoupled).
 *
 * `SystemInternal`: an operational/maintenance action with no per-tenant
 * principal to authorize against.
 */
data class ReencryptCredentials(
    val metadataKeys: List<String> = emptyList(),
) : Command<ReencryptResult>,
    SystemInternal

data class ReencryptResult(
    val credentialsReencrypted: Map<String, Int>,
    val metadataReencrypted: Int,
)

@Component
class ReencryptCredentialsHandler(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
    private val appMetadata: AppMetadataService,
    private val objectMapper: ObjectMapper,
    contributors: List<EncryptedCredentialContributor>,
) : CommandHandler<ReencryptCredentials, ReencryptResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val credentialColumns = contributors.flatMap { it.columns() }.sortedBy { it.qualifiedName }

    override fun handle(command: ReencryptCredentials): ReencryptResult = jdbi.inTransaction<ReencryptResult, Exception> { handle ->
        val credentials = credentialColumns.associate { it.qualifiedName to reencryptColumn(handle, it) }
        val metadata = command.metadataKeys.count { reencryptMetadata(it) }
        ReencryptResult(credentials, metadata).also {
            logger.info(
                "Re-encrypted credentials under primary key '{}': {} credential column(s), {} metadata key(s)",
                cipher.primaryKeyId,
                it.credentialsReencrypted.values.sum(),
                it.metadataReencrypted,
            )
        }
    }

    private fun reencryptColumn(handle: Handle, definition: EncryptedCredentialColumn): Int {
        val selected = (definition.keyColumns + "${definition.column} AS credential").joinToString()
        val rows = handle.createQuery(
            "SELECT $selected FROM ${definition.table} WHERE ${definition.column} IS NOT NULL",
        ).map { rs, _ ->
            definition.keyColumns.associateWith(rs::getString) to rs.getString("credential")
        }.list()
        var count = 0
        rows.forEach { (keys, raw) ->
            val reencrypted = reencryptOrNull(raw) ?: return@forEach
            val where = definition.keyColumns.joinToString(" AND ") { "$it = :key_$it" }
            val update = handle.createUpdate(
                "UPDATE ${definition.table} SET ${definition.column} = :credential WHERE $where",
            ).bind("credential", reencrypted)
            keys.forEach { (key, value) -> update.bind("key_$key", value) }
            update.execute()
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
