// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.embedding

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Install-wide (boot-time) configuration for embedding Epistola's UI in an
 * `<iframe>` on a trusted host page, and the `postMessage` bridge that goes
 * with it. See docs/embedding.md.
 *
 * Deliberately not a per-tenant [app.epistola.suite.features.FeatureToggleService]
 * entry: a `demo`-profile install can have multiple tenants, and embedding must
 * not vary per tenant — it is an environment-level decision, same idiom as
 * `epistola.demo.enabled`, but shaped as a typed properties bean (rather than a
 * bare `@Value` boolean) because it also carries the allowed-origin list and is
 * read from several unrelated places ([app.epistola.suite.config.SecurityConfig],
 * [app.epistola.suite.config.SessionConfig], [EmbeddingContextInterceptor]).
 */
@ConfigurationProperties(prefix = "epistola.embedding")
data class EmbeddingProperties(
    /**
     * Whether Epistola may be framed at all. Defaults to false everywhere except
     * the `demo` profile — every other deployment (self-hosted customer installs
     * in particular) keeps today's un-framable, `SameSite=Lax` behavior.
     */
    val enabled: Boolean = false,

    /**
     * Origins allowed to embed Epistola in an `<iframe>` (e.g. `https://epistola.app`).
     * Used verbatim as CSP `frame-ancestors` source expressions, and sent to the
     * browser bridge script so it knows which origin to `postMessage` to and
     * validate inbound messages against. Ignored when [enabled] is false.
     */
    val allowedParentOrigins: List<String> = emptyList(),
) {
    /**
     * The CSP `frame-ancestors` directive value: the allowed origins, or `'none'`
     * when embedding is off or no origin is configured — today's default,
     * unchanged. Pulled out as a pure property (rather than inlined in
     * [app.epistola.suite.config.SecurityConfig]'s Spring Security DSL lambda)
     * so the decision is directly unit-testable without booting a Spring context.
     */
    val cspFrameAncestors: String
        get() = if (enabled && allowedParentOrigins.isNotEmpty()) allowedParentOrigins.joinToString(" ") else "'none'"

    /**
     * Whether Spring Security's default `X-Frame-Options: DENY` should be
     * disabled. Only when embedding is genuinely on — `frame-ancestors` is
     * authoritative over `X-Frame-Options` in every modern browser and, unlike
     * XFO, supports an origin list, so leaving `DENY` in place otherwise avoids
     * two headers contradicting each other for no protective benefit.
     */
    val disableXFrameOptions: Boolean
        get() = enabled

    /**
     * Whether the session/CSRF cookies must switch to `SameSite=None; Secure`
     * to survive being set/read from inside a cross-origin iframe (ADR 0015).
     * Only ever forces `Secure` ON — never off — so an HTTPS deployment that
     * never asked for embedding keeps Spring Session's own default (mirroring
     * `HttpServletRequest.isSecure()`) untouched.
     */
    val cookiesNeedSameSiteNone: Boolean
        get() = enabled
}
