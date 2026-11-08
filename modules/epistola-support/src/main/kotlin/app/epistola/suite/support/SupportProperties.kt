// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * The connection to the Epistola support service (`docs/support-protocol.md`). Off by default:
 * with [enabled] false nothing is registered and nothing is sent.
 *
 * @property enabled register this installation with the support service and use the services
 *   Epistola grants it.
 * @property endpoint the support service's issuer URL. Blank: found through the product domain's
 *   discovery document (ADR 0030), and `https://support.{product-domain}` when that names none.
 * @property allowHttp permit a plaintext `http:` support service. Local development only: the
 *   installation's client assertions, tokens and uploads cross this connection. An `http:`
 *   [endpoint] without it stops startup.
 * @property autoRegister let the scheduled check register this installation on its own, and
 *   register it again at once when a registration expires before it is activated. Off by default:
 *   nothing leaves the installation until an installation administrator presses "Register" under
 *   Platform → Epistola support.
 * @property checkInterval how often one node refreshes the status (and registers, with [autoRegister]).
 * @property connectTimeout connect timeout for every call to the support service.
 * @property readTimeout the deadline for one whole call to the support service, from sending the
 *   request to the last byte of the answer (not per read); a request body adds a second per 512 KiB.
 * @property services the support services this installation uses (`telemetry`, `requests`,
 *   `mirror`, `usage`) until an installation administrator saves a selection under Platform → Epistola
 *   support; from then on the saved selection wins and this is ignored. Empty by default: nothing
 *   is used until someone chooses. A name this version does not know is kept and logged, never an
 *   error, so a configuration that names a retired or a newer service still starts.
 * @property privacy what Epistola sees of the tenants and how much telemetry carries, until an
 *   installation administrator saves them under Platform → Epistola support ([Privacy]).
 */
@ConfigurationProperties(prefix = "epistola.support")
data class SupportProperties(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val allowHttp: Boolean = false,
    val autoRegister: Boolean = false,
    val checkInterval: Duration = Duration.ofMinutes(5),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    val services: List<String> = emptyList(),
    val privacy: Privacy = Privacy(),
) {
    init {
        require(endpoint.isBlank() || configuredIssuer != null) {
            "epistola.support.endpoint must be an absolute http(s) URL, such as https://support.epistola.app; got '$endpoint'"
        }
        require(allowHttp || configuredIssuer?.scheme != "http") {
            "epistola.support.endpoint '$endpoint' is plaintext http; use https, or set epistola.support.allow-http=true for a local support service"
        }
        require(!checkInterval.isNegative && !checkInterval.isZero) { "epistola.support.check-interval must be positive" }
    }

    /**
     * What Epistola sees of the tenants and how much telemetry carries (ADR 0052 §1–2), until an
     * installation administrator saves them under Platform → Epistola support. Helm
     * `support.privacy.tenantIdentity` / `support.privacy.telemetry`.
     *
     * @property tenantIdentity `confidential` (the default: every tenant as a reference) or `named`.
     * @property telemetry `pseudonymised` (the default: log messages without their values) or `full`.
     */
    data class Privacy(
        val tenantIdentity: String = "confidential",
        val telemetry: String = "pseudonymised",
    ) {
        init {
            require(TenantIdentity.parse(tenantIdentity) != null) { "epistola.support.privacy.tenant-identity must be confidential or named; got '$tenantIdentity'" }
            require(TelemetryDetail.parse(telemetry) != null) { "epistola.support.privacy.telemetry must be pseudonymised or full; got '$telemetry'" }
        }

        val tenantIdentityValue: TenantIdentity get() = TenantIdentity.parse(tenantIdentity)!!
        val telemetryValue: TelemetryDetail get() = TelemetryDetail.parse(telemetry)!!
    }

    /** [services], normalised: the selection in force until an administrator saves one. */
    val configuredServices: List<String> get() = SupportScope.normalize(services)

    /** Configured service names this version does not know; logged at startup, otherwise ignored. */
    val unknownServices: List<String> get() = configuredServices.filter { it !in SupportScope.NAMES }

    /** The configured issuer without a trailing slash; null when the support service is to be discovered. */
    val configuredIssuer: URI?
        get() = endpoint.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { value ->
            runCatching { URI(value) }.getOrNull()?.takeIf { (it.scheme == "https" || it.scheme == "http") && it.host != null && it.userInfo == null }
        }
}
