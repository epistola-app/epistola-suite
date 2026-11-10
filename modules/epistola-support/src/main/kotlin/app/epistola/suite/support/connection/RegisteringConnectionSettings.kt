// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.support.SupportProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

/**
 * What one [DefaultSupportConnection] (ADR 0053 §1, [SupportConnectionAuth.PRIVATE_KEY_JWT]: it
 * registers with the installation's own key) is configured with. Epistola support's comes from
 * `epistola.support.*` ([of]); another's from its row ([forEndpoint]).
 *
 * @property enabled false: the connection reads [SupportConnectionState.Disabled] and uses nothing.
 * @property issuer the support service's issuer: the connection's endpoint.
 * @property configuredServices the services used until an administrator saves a selection.
 * @property autoRegister the scheduled check registers on its own.
 * @property offReason what a call made while not [enabled] fails with.
 */
data class RegisteringConnectionSettings(
    val enabled: Boolean,
    val issuer: URI,
    val configuredServices: List<String>,
    val autoRegister: Boolean,
    val offReason: String = "The support connection is off",
) {
    companion object {
        /** The connection `epistola.support.*` configures, at [endpoint] ([SupportConnections.configuredEndpoint]). */
        fun of(properties: SupportProperties, endpoint: URI) = RegisteringConnectionSettings(
            enabled = properties.enabled,
            issuer = endpoint,
            configuredServices = properties.configuredServices,
            autoRegister = properties.autoRegister,
            offReason = "The support connection is off (epistola.support.enabled=false)",
        )

        /**
         * A connection that registers at [endpoint], as an administrator added it or an entry of
         * `epistola.support.connections` names it: on, with [services] until an administrator selects
         * others, and registered when an administrator presses Register.
         */
        fun forEndpoint(endpoint: URI, services: List<String> = emptyList()) = RegisteringConnectionSettings(enabled = true, issuer = endpoint, configuredServices = SupportScope.normalize(services), autoRegister = false)
    }
}

/** On: [RegisteringConnectionSettings.enabled], and the connection's row exists (an administrator may have removed it). */
internal fun DefaultSupportConnection.on(): Boolean = settings.enabled && store.exists()

/** Why a call made while not [on] fails. */
internal fun DefaultSupportConnection.offReason(): String = if (settings.enabled) "The support connection was removed" else settings.offReason

/** Epistola support's connection, configured by `epistola.support.*`: the [DefaultSupportConnection] bean every module injects. */
@Configuration(proxyBeanMethods = false)
class DefaultSupportConnectionConfiguration {
    @Bean
    fun defaultSupportConnection(
        properties: SupportProperties,
        store: SupportConnectionStore,
        client: SupportServiceClient,
        connections: SupportConnections,
        installations: InstallationService,
        installation: InstallationProperties,
        nodeIdentity: NodeIdentity,
        buildProperties: ObjectProvider<BuildProperties>,
        supportHttp: SupportHttp,
    ): DefaultSupportConnection = DefaultSupportConnection(
        properties,
        RegisteringConnectionSettings.of(properties, connections.configuredEndpoint()),
        store,
        client,
        installations,
        installation,
        nodeIdentity,
        buildProperties.ifAvailable,
        supportHttp,
    )
}
