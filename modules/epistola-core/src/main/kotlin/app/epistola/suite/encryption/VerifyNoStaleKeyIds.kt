package app.epistola.suite.encryption

import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Reports which encryption key ids are currently referenced by stored
 * credentials. Used to gate retiring an old key during rotation: only remove a
 * key from the keyset once [KeyIdUsageReport.allOnPrimary] is true (no value
 * still references the old key id, and no legacy plaintext remains).
 *
 * Scans the core-owned credential columns (`catalogs`, `code_lists`) plus any
 * `app_metadata` keys named in [metadataKeys].
 *
 * `SystemInternal`: an operational/maintenance read with no per-tenant principal.
 */
data class VerifyNoStaleKeyIds(
    val metadataKeys: List<String> = emptyList(),
) : Query<KeyIdUsageReport>,
    SystemInternal

data class KeyIdUsageReport(
    val primaryKeyId: String,
    /** Distinct key ids in use; [PLAINTEXT_MARKER] represents any unencrypted value. */
    val keyIdsInUse: Set<String>,
    val allOnPrimary: Boolean,
) {
    companion object {
        const val PLAINTEXT_MARKER = "<plaintext>"
    }
}

@Component
class VerifyNoStaleKeyIdsHandler(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
    private val appMetadata: AppMetadataService,
    private val objectMapper: ObjectMapper,
) : QueryHandler<VerifyNoStaleKeyIds, KeyIdUsageReport> {

    override fun handle(query: VerifyNoStaleKeyIds): KeyIdUsageReport {
        val raws = mutableListOf<String>()
        jdbi.useHandle<Exception> { handle ->
            raws += handle.createQuery("SELECT source_auth_credential FROM catalogs WHERE source_auth_credential IS NOT NULL")
                .mapTo(String::class.java).list()
            raws += handle.createQuery("SELECT credential FROM code_lists WHERE credential IS NOT NULL")
                .mapTo(String::class.java).list()
        }
        query.metadataKeys.forEach { key ->
            appMetadata.get(key)?.let { raws += objectMapper.treeToValue(it, String::class.java) }
        }

        val keyIds = raws.map { cipher.keyIdOf(it) ?: KeyIdUsageReport.PLAINTEXT_MARKER }.toSet()
        val allOnPrimary = keyIds.isEmpty() || keyIds == setOf(cipher.primaryKeyId)
        return KeyIdUsageReport(cipher.primaryKeyId, keyIds, allOnPrimary)
    }
}
