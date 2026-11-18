// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.productdomain.ProductDiscovery
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service

@Service
class VersionCheckService(
    private val client: VersionCheckClient,
    private val metadata: AppMetadataService,
    private val properties: VersionCheckProperties,
    private val productDiscovery: ProductDiscovery,
    private val buildProperties: BuildProperties?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val currentVersion: String get() = buildProperties?.version ?: "dev"

    /**
     * The status of the running version, evaluated now from the Suite's entry of the last successful
     * fetch. So an upgrade shows its own status at once (no stale "update to the version you run"),
     * a line's status follows the stored document between checks, and a failed fetch keeps the last
     * entry instead of relabelling an old verdict. The last run's outcome (time, availability, error)
     * comes from the stored run record. Until this build's first successful fetch there is no stored
     * entry, and the stored verdict is shown as before.
     */
    fun status(): VersionCheckStatus? {
        if (!properties.enabled) return null
        val run = readStatus()
        val releases = readReleases()
        val product = releases?.product ?: return run
        val now = EpistolaClock.instant()
        val evaluated = VersionCheckEvaluator.evaluate(
            product = product,
            generatedAt = releases.generatedAt,
            currentVersion = currentVersion,
            checkedAt = run?.checkedAt ?: releases.fetchedAt ?: now,
            now = now,
            deprecationWindow = properties.deprecationWarningWindow,
        )
        return evaluated.copy(
            metadataAvailable = run?.metadataAvailable ?: true,
            lastError = run?.lastError ?: evaluated.lastError,
        )
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
        log.debug("Ignoring unreadable cached version-check status reason=\"{}\"", e.message)
        null
    }

    private fun readReleases(): StoredReleases? = try {
        metadata.getAs<StoredReleases>(RELEASES_KEY)
    } catch (e: Exception) {
        log.debug("Ignoring unreadable stored releases entry reason=\"{}\"", e.message)
        null
    }

    /** This check for the "Shared with Epistola" page. It sends the same request every day. */
    fun summary(): CheckSummary {
        val status = readStatus()
        return CheckSummary(
            enabled = properties.enabled,
            request = if (properties.enabled) VersionCheckClient.request(endpoint(discover = false)) else null,
            lastRunAt = status?.checkedAt,
            lastResult = status?.let { it.lastError ?: if (it.metadataAvailable) "OK" else "release metadata unavailable" },
            lastRequest = null,
        )
    }

    fun checkNow(): VersionCheckStatus {
        val previous = readStatus()
        val now = EpistolaClock.instant()
        if (!properties.enabled) {
            return previous ?: VersionCheckStatus(checkedAt = now, currentVersion = currentVersion)
        }

        val status = try {
            val document = client.fetch(endpoint(discover = true))
            document.products[PRODUCT_KEY]?.let { product ->
                metadata.setAs(RELEASES_KEY, StoredReleases(fetchedAt = now, generatedAt = document.generatedAt, product = product))
            }
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
            log.info("Version check metadata unavailable reason=\"{}\"", e.message)
            unavailable
        } catch (e: Exception) {
            val failed = (previous ?: VersionCheckStatus(currentVersion = currentVersion)).copy(
                checkedAt = now,
                currentVersion = currentVersion,
                metadataAvailable = false,
                lastError = e.message ?: e.javaClass.simpleName,
            )
            log.warn("Version check failed reason=\"{}\"", e.message)
            failed
        }
        if (status.updateAvailable) {
            log.info(
                "Epistola Suite update available: current={}, latest={}, preRelease={}",
                status.currentVersion,
                status.latestVersion,
                status.preRelease,
            )
        }
        metadata.setAs(STATUS_KEY, status)
        return status
    }

    /** The operator's endpoint, else the discovered one; [discover] may fetch the discovery document. */
    private fun endpoint(discover: Boolean): String = properties.endpoint.ifBlank {
        (if (discover) productDiscovery.endpoints() else productDiscovery.cachedEndpoints()).versionCheck
    }

    companion object {
        const val STATUS_KEY = "version-check.status"

        /** The Suite's entry from the last successful fetch ([StoredReleases]). */
        const val RELEASES_KEY = "version-check.releases"
        const val PRODUCT_KEY = "epistola-suite"
    }
}
