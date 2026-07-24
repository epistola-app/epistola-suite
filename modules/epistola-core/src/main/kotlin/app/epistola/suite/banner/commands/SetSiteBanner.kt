// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.banner.commands

import app.epistola.suite.banner.SiteBanner
import app.epistola.suite.banner.SiteBannerSeverity
import app.epistola.suite.banner.SiteBannerStore
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.validation.validate
import org.springframework.stereotype.Component

/**
 * Sets (upserts) the installation-wide site banner. Gated to the platform
 * [PlatformRole.TENANT_MANAGER]. Clearing is [ClearSiteBanner].
 */
data class SetSiteBanner(
    val message: String,
    val severity: SiteBannerSeverity,
    val enabled: Boolean,
) : Command<Unit>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER

    init {
        validate("message", message.isNotBlank()) { "Message is required" }
        validate("message", message.length <= MAX_MESSAGE_LENGTH) {
            "Message must be $MAX_MESSAGE_LENGTH characters or less"
        }
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 500
    }
}

@Component
class SetSiteBannerHandler(
    private val store: SiteBannerStore,
) : CommandHandler<SetSiteBanner, Unit> {
    override fun handle(command: SetSiteBanner) {
        store.set(SiteBanner(command.message.trim(), command.severity, command.enabled))
    }
}
