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
        return metadata.getAs<VersionCheckStatus>(STATUS_KEY)
    }

    fun checkNow(): VersionCheckStatus {
        val previous = metadata.getAs<VersionCheckStatus>(STATUS_KEY)
        val now = EpistolaClock.instant()
        if (!properties.enabled) {
            return previous ?: VersionCheckStatus(checkedAt = now, currentVersion = currentVersion)
        }

        val status = try {
            val document = client.fetch(properties.wellKnownUrl, currentVersion)
            VersionCheckEvaluator.evaluate(document, currentVersion, now).copy(metadataAvailable = true)
        } catch (e: VersionMetadataUnavailableException) {
            val unavailable = VersionCheckStatus(
                checkedAt = now,
                currentVersion = currentVersion,
                metadataAvailable = false,
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
        metadata.setAs(STATUS_KEY, status)
        return status
    }

    companion object {
        const val STATUS_KEY = "version-check.status"
        const val PRODUCT_KEY = "epistola-suite"
    }
}
