// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Install-wide (boot-time) configuration for demo mode.
 *
 * [enabled] keeps the meaning it has always had — it is what every `@ConditionalOnProperty` in this
 * package gates on, and the `demo` *and* `local` profiles both set it. [sharedSecret] is narrower:
 * it only ever takes effect under the `demo` profile (see [DemoSecurityConfiguration] and
 * [DemoSharedSecretSafetyValidator]).
 *
 * Shaped as a typed properties bean rather than bare `@Value` reads for the same reason as
 * [app.epistola.suite.embedding.EmbeddingProperties]: it carries more than a flag and is read from
 * several unrelated places, and the derived decisions below are then unit-testable without a context.
 */
@ConfigurationProperties(prefix = "epistola.demo")
data class DemoProperties(
    /** Whether demo data and the demo login behaviour are active. */
    val enabled: Boolean = false,

    /**
     * A single credential that authenticates **every** endpoint under `/api` as a superuser, for the
     * demo website to call Epistola on a visitor's behalf. Supplied only through the environment
     * (`EPISTOLA_DEMO_SHAREDSECRET`), never committed. Blank — the default — means no such
     * credential exists.
     */
    val sharedSecret: String = "",
) {
    /** Whether a shared secret was supplied at all. Blank and unset are the same thing. */
    val sharedSecretConfigured: Boolean
        get() = sharedSecret.isNotBlank()

    companion object {
        /**
         * Minimum length for [sharedSecret]. It is a bearer credential with unlimited authority and
         * no rate limit in front of it, so a guessable one is not a lesser version of the feature —
         * it is a public API. 32 characters is `openssl rand -hex 16`; the docs suggest 32 bytes.
         */
        const val MIN_SHARED_SECRET_LENGTH = 32
    }
}
