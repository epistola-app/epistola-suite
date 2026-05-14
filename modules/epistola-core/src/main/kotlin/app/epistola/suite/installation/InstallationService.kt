package app.epistola.suite.installation

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Reads (and on first call, writes) this Suite's [Installation] identity.
 * Single source of truth backed by [AppMetadataService] under key
 * [METADATA_KEY] — see [Installation] for why this is stable across pods.
 */
@Service
class InstallationService(
    private val metadata: AppMetadataService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Returns the persisted installation identity, creating one on first
     * call. Idempotent across restarts. Safe under concurrent pods because
     * the underlying `INSERT ... ON CONFLICT DO NOTHING` keeps the
     * earliest winner; this method re-reads after the write to converge
     * on the same value across racing callers.
     */
    @Transactional
    fun getOrCreate(): Installation {
        metadata.getAs<Installation>(METADATA_KEY)?.let { return it }
        val fresh = Installation(id = UUIDv7.generate(), createdAt = Instant.now(clock))
        metadata.setIfAbsent(METADATA_KEY, fresh)
        return metadata.getAs<Installation>(METADATA_KEY)
            ?: error("Failed to persist installation identity under key '$METADATA_KEY'")
    }

    companion object {
        const val METADATA_KEY = "installation"
    }
}
