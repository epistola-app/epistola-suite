package app.epistola.suite.versioncheck

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service

@Service
class VersionCheckService(
    private val client: VersionCheckClient,
    private val metadata: AppMetadataService,
    private val properties: VersionCheckProperties,
    private val buildProperties: BuildProperties?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val currentVersion: String get() = buildProperties?.version ?: "dev"

    fun status(): VersionCheckStatus? {
        if (!properties.enabled) return null
        return readStatus()
    }

    /**
     * Reads the cached status, tolerating a schema-drifted blob written by an older build. The
     * database is stable from RC1 onward (rows are never reset), so a stored [VersionCheckStatus]
     * whose shape no longer matches must degrade to "no banner" rather than break the page it is
     * read on (every tenant-home render).
     */
    private fun readStatus(): VersionCheckStatus? = try {
        metadata.getAs<VersionCheckStatus>(STATUS_KEY)
    } catch (e: Exception) {
        log.debug("Ignoring unreadable cached version-check status: {}", e.message)
        null
    }

    fun checkNow(): VersionCheckStatus {
        val previous = readStatus()
        val now = EpistolaClock.instant()
        if (!properties.enabled) {
            return previous ?: VersionCheckStatus(checkedAt = now, currentVersion = currentVersion)
        }

        val status = try {
            val document = client.fetch(properties.wellKnownUrl, currentVersion)
            VersionCheckEvaluator.evaluate(document, currentVersion, now, properties.deprecationWarningWindow)
        } catch (e: VersionMetadataUnavailableException) {
            // Metadata temporarily absent (404) — keep any previously known update, like the
            // generic-failure path below, so a transient blip doesn't hide the banner.
            val unavailable = (previous ?: VersionCheckStatus(currentVersion = currentVersion)).copy(
                checkedAt = now,
                currentVersion = currentVersion,
                metadataAvailable = false,
                lastError = null,
            )
            log.info("Version check metadata unavailable: {}", e.message)
            unavailable
        } catch (e: Exception) {
            val failed = (previous ?: VersionCheckStatus(currentVersion = currentVersion)).copy(
                checkedAt = now,
                currentVersion = currentVersion,
                metadataAvailable = false,
                lastError = e.message ?: e.javaClass.simpleName,
            )
            log.warn("Version check failed: {}", e.message)
            failed
        }
        if (status.updateAvailable) {
            log.info(
                "Epistola Suite update available: current={}, latest={}, channel={}",
                status.currentVersion,
                status.latestVersion,
                status.channel,
            )
        }
        metadata.setAs(STATUS_KEY, status)
        return status
    }

    companion object {
        const val STATUS_KEY = "version-check.status"
        const val PRODUCT_KEY = "epistola-suite"
    }
}
