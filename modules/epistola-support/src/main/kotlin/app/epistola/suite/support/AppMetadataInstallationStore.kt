package app.epistola.suite.support

import app.epistola.hub.client.port.InstallationCredentials
import app.epistola.hub.client.port.InstallationStore
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs

/**
 * Persists hub-issued credentials via the suite-wide [AppMetadataService]
 * under key [METADATA_KEY]. Using `app_metadata` (rather than a dedicated
 * table) keeps the support module SQL-free and lets a future
 * encryption-at-rest mechanism cover every metadata entry uniformly.
 *
 * Singleton-row semantics fall out of the key choice: a fixed key in a
 * `PRIMARY KEY (key)` table is already a singleton, no DB-level CHECK
 * needed.
 */
class AppMetadataInstallationStore(
    private val metadata: AppMetadataService,
) : InstallationStore {
    override fun load(): InstallationCredentials? = metadata.getAs<InstallationCredentials>(METADATA_KEY)

    override fun save(credentials: InstallationCredentials) {
        metadata.setAs(METADATA_KEY, credentials)
    }

    companion object {
        const val METADATA_KEY = "support.hub.credentials"
    }
}
