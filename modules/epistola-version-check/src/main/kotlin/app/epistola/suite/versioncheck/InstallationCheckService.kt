// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.installation.InstallationService
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service

/**
 * Runs the installation check and caches its outcome. It sends the installation id, and the version
 * only when `epistola.sharing.suite-version` allows it. A failed attempt keeps the last successful
 * result, so the support badge can tell "could not be checked" from a real answer.
 */
@Service
class InstallationCheckService(
    private val metadata: AppMetadataService,
    private val installationService: InstallationService,
    private val sharing: SharingProperties,
    properties: InstallationCheckProperties,
    private val buildProperties: BuildProperties?,
    private val badge: ObjectProvider<SupportBadgeService>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = InstallationCheckClient(properties)
    val currentVersion: String get() = buildProperties?.version ?: "dev"

    /** The cached status; null when it was never run or cannot be read. */
    fun status(): InstallationCheckStatus? = try {
        metadata.getAs<InstallationCheckStatus>(STATUS_KEY)
    } catch (e: Exception) {
        log.debug("Ignoring unreadable cached installation-check status: {}", e.message)
        null
    }

    /** The URL and headers a check sends right now, given the sharing settings. */
    fun request(installationId: java.util.UUID = installationService.get().id): Pair<String, Map<String, String>> = client.request(installationId, currentVersion.takeIf { sharing.suiteVersion })

    /** Does nothing (returns null) when the installation id is not shared. */
    fun checkNow(): InstallationCheckStatus? {
        if (!sharing.installationId) return null
        val previous = status() ?: InstallationCheckStatus()
        val now = EpistolaClock.instant()
        val (url, headers) = request()

        val next = try {
            val (code, result) = client.fetch(url, headers)
            val sent = SentRequest(at = now, url = url, headers = headers, outcome = code.toString())
            if (result != null) {
                previous.copy(lastAttemptAt = now, lastSuccessAt = now, lastError = null, result = result, lastRequest = sent)
            } else {
                log.warn("Installation check answered HTTP {}", code)
                previous.copy(lastAttemptAt = now, lastError = "HTTP $code", lastRequest = sent)
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            log.warn("Installation check failed: {}", message)
            previous.copy(
                lastAttemptAt = now,
                lastError = message,
                lastRequest = SentRequest(at = now, url = url, headers = headers, outcome = message.take(200)),
            )
        }
        metadata.setAs(STATUS_KEY, next)
        badge.ifAvailable { it.invalidate() }
        return next
    }

    companion object {
        const val STATUS_KEY = "installation-check.status"
    }
}
