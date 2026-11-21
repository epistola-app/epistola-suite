// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Installation settings for interactive generation (contract ADR 0011), under
 * `epistola.generation.interactive`.
 *
 * @property publicBaseUrl The address people reach this installation at, such as
 *   `https://suite.example.org`. The form's link is built on it. Required: without it the feature
 *   stays off whatever the tenant toggle says, because a link built from a request's `Host` header
 *   would be handed out again by `getGenerationJobStatus`.
 * @property defaultExpiry How long a request waits for the person when the caller names no expiry.
 * @property maxExpiry The longest wait a caller may ask for; longer is refused. Never above the
 *   contract's 30 days, and never below [defaultExpiry].
 * @property linkLifetime How long a link to the form can be opened (epistola-contract#134): minutes,
 *   so a link that leaks is dead before it is found. A caller fetches a fresh one when it needs one.
 * @property sessionIdleTimeout How long a person's session on the form lasts without a request;
 *   every page view, preview or save renews it, never past the request's wait.
 * @property allowedOrigins Origins (`scheme://host[:port]`) a request from a caller **without** an
 *   application (a bearer token, the demo secret) may name as `returnUrl` or link. A caller with an
 *   application is held to that application's own list. This installation's own origin is always
 *   allowed.
 * @property previewConcurrency How many form previews this node renders at once, over all forms. A
 *   preview renders on the request thread, outside the job queue, so beyond this a preview is
 *   refused with 429 rather than competing with the queue's renders.
 */
@ConfigurationProperties(prefix = "epistola.generation.interactive")
data class InteractiveGenerationProperties(
    val publicBaseUrl: String? = null,
    val defaultExpiry: Duration = Duration.ofHours(24),
    val maxExpiry: Duration = Duration.ofDays(7),
    val linkLifetime: Duration = Duration.ofMinutes(15),
    val sessionIdleTimeout: Duration = Duration.ofMinutes(60),
    val allowedOrigins: List<String> = emptyList(),
    val previewConcurrency: Int = 4,
) {
    init {
        require(!maxExpiry.isNegative && maxExpiry.toMinutes() in 1..InteractiveGeneration.CONTRACT_MAX_EXPIRY_MINUTES.toLong()) {
            "epistola.generation.interactive.max-expiry must be between 1 minute and 30 days"
        }
        require(defaultExpiry.toMinutes() in 1..maxExpiry.toMinutes()) {
            "epistola.generation.interactive.default-expiry must be between 1 minute and max-expiry"
        }
        require(linkLifetime.toSeconds() in 60..3600) { "epistola.generation.interactive.link-lifetime must be between 1 minute and 1 hour" }
        require(sessionIdleTimeout.toMinutes() in 5..1440) { "epistola.generation.interactive.session-idle-timeout must be between 5 minutes and 24 hours" }
        require(previewConcurrency in 1..256) { "epistola.generation.interactive.preview-concurrency must be between 1 and 256" }
        publicBaseUrl?.let {
            require(Origins.of(it) != null) { "epistola.generation.interactive.public-base-url must be an absolute http(s) URL" }
        }
        allowedOrigins.forEach {
            require(Origins.normalise(it) != null) {
                "epistola.generation.interactive.allowed-origins holds origins only (scheme://host[:port]): $it"
            }
        }
    }

    /** Whether the installation can serve forms at all. */
    val configured: Boolean get() = publicBaseUrl != null

    /** [allowedOrigins], normalised for exact comparison. */
    val normalisedAllowedOrigins: Set<String> get() = allowedOrigins.mapNotNull(Origins::normalise).toSet()

    /** This installation's own origin, always allowed as a return address. */
    val ownOrigin: String? get() = publicBaseUrl?.let(Origins::of)
}
