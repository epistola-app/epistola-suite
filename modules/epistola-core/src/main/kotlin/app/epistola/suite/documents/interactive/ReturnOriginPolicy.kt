// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Where a form may send the browser, and link to (contract ADR 0011, "Coming back"). A page on
 * Epistola's domain that forwards wherever a leaked key chooses would be an open redirect, so every
 * `returnUrl` and link must be on an allowed origin:
 *
 * - the submitting application's own `allowed_return_origins`, when the caller has an application
 *   (ADR 0054): where its frontend lives, so a leaked key can only send a browser back to its system;
 * - the installation's `epistola.generation.interactive.allowed-origins`, for a caller without one;
 * - always this installation's own origin, which the editor's "try it" flow returns to.
 */
@Component
class ReturnOriginPolicy(private val properties: InteractiveGenerationProperties) {

    /** Refuses the first URL of [interactive] that is not on an allowed origin, with `url-not-allowed`. */
    fun check(handle: Handle, tenantKey: TenantKey, application: ApplicationKey?, interactive: InteractiveGeneration) {
        val urls = interactive.urls()
        if (urls.isEmpty()) return
        val allowed = allowedOrigins(handle, tenantKey, application)
        urls.forEach { (field, url) ->
            val origin = Origins.of(url)
            if (origin == null || origin !in allowed) {
                throw ValidationException(field, "${origin ?: url} is not an allowed origin", ValidationCode.URL_NOT_ALLOWED)
            }
        }
    }

    private fun allowedOrigins(handle: Handle, tenantKey: TenantKey, application: ApplicationKey?): Set<String> {
        val configured = if (application == null) {
            properties.normalisedAllowedOrigins
        } else {
            handle.createQuery("SELECT allowed_return_origins FROM applications WHERE tenant_key = :tenantKey AND id = :id")
                .bind("tenantKey", tenantKey)
                .bind("id", application)
                .mapTo(Array<String>::class.java)
                .findOne()
                .orElse(emptyArray())
                .mapNotNull(Origins::normalise)
                .toSet()
        }
        return configured + listOfNotNull(properties.ownOrigin)
    }
}
