// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("epistola.exchange")
data class ExchangeProperties(
    val enabled: Boolean = false,
    val discoveryUrl: String = "https://epistola.app/.well-known/epistola/exchange.json",
    /** Local-development escape hatch; production should use the discovery document. */
    val baseUrl: String? = null,
    val pollIntervalMs: Long = 5000,
)
