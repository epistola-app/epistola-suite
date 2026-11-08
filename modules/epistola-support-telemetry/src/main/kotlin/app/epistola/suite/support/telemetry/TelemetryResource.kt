// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource

/**
 * Who is reporting: the OTLP resource attributes on every log and metric export to the support
 * service. The same for both signals, and built only from the suite's own identity — never from
 * the customer's `OTEL_*` environment, which belongs to their own observability.
 *
 * The installation id is read from `app_metadata` on first use and kept once it is known; until
 * then it is left out rather than guessed. The support service refuses a request whose resources do
 * not name the token's installation (`400 installation_mismatch`, ADR 0052 §4), so a missing id shows
 * up there instead of being filled in.
 */
class TelemetryResource(
    private val nodeId: String,
    private val environment: String,
    private val version: String,
    private val installationId: () -> String,
) {
    @Volatile private var knownInstallationId: String? = null

    /** The attributes, in OTLP semantic-convention names. */
    fun attributes(): Map<String, String> = buildMap {
        put(SERVICE_NAME, SERVICE)
        put(SERVICE_INSTANCE_ID, nodeId)
        put(SERVICE_VERSION, version)
        put(DEPLOYMENT_ENVIRONMENT, environment)
        installationId()?.let { put(INSTALLATION_ID, it) }
    }

    /** The attributes as an OpenTelemetry [Resource], on top of the SDK's own `telemetry.sdk.*`. */
    fun otelResource(): Resource {
        val attributes = Attributes.builder()
        attributes().forEach { (key, value) -> attributes.put(key, value) }
        return Resource.getDefault().merge(Resource.create(attributes.build()))
    }

    private fun installationId(): String? = knownInstallationId
        ?: runCatching { installationId.invoke() }.getOrNull()?.takeIf(String::isNotBlank)?.also { knownInstallationId = it }

    companion object {
        const val SERVICE = "epistola-suite"
        const val SERVICE_NAME = "service.name"
        const val SERVICE_INSTANCE_ID = "service.instance.id"
        const val SERVICE_VERSION = "service.version"
        const val DEPLOYMENT_ENVIRONMENT = "deployment.environment"
        const val INSTALLATION_ID = "epistola.installation.id"
    }
}
