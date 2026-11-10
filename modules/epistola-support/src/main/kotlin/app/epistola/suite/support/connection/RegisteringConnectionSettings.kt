// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.support.SupportProperties
import java.net.URI

/**
 * What one [DefaultSupportConnection] (ADR 0053 §1, [SupportConnectionAuth.PRIVATE_KEY_JWT]: it
 * registers with the installation's own key) is configured with. Epistola support's comes from
 * `epistola.support.*` ([of]); another's from its row ([forEndpoint]).
 *
 * @property enabled false: the connection reads [SupportConnectionState.Disabled] and uses nothing.
 * @property issuer the support service's issuer; null: found through the product domain's discovery.
 * @property configuredServices the services used until an administrator saves a selection.
 * @property autoRegister the scheduled check registers on its own.
 * @property offReason what a call made while not [enabled] fails with.
 */
data class RegisteringConnectionSettings(
    val enabled: Boolean,
    val issuer: URI?,
    val configuredServices: List<String>,
    val autoRegister: Boolean,
    val offReason: String = "The support connection is off",
) {
    companion object {
        /** Epistola support's connection, as `epistola.support.*` configures it. */
        fun of(properties: SupportProperties) = RegisteringConnectionSettings(
            enabled = properties.enabled,
            issuer = properties.configuredIssuer,
            configuredServices = properties.configuredServices,
            autoRegister = properties.autoRegister,
            offReason = "The support connection is off (epistola.support.enabled=false)",
        )

        /**
         * A connection that registers at [endpoint], as an administrator added it: on, with no services
         * until one selects them, and registered when an administrator presses Register.
         */
        fun forEndpoint(endpoint: URI) = RegisteringConnectionSettings(enabled = true, issuer = endpoint, configuredServices = emptyList(), autoRegister = false)
    }
}
