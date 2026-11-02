// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.commands.SaveQualityProviderSettings
import app.epistola.suite.quality.queries.ListQualityProviders
import app.epistola.suite.quality.queries.QualityProviderView
import app.epistola.suite.quality.text.TextEgress
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * The tenant's quality settings: which providers check its templates (ADR 0047). A provider that sends
 * text somewhere is switched on here by a tenant administrator; the built-in rules are always on.
 */
@Component
class QualitySettingsHandler {
    fun settings(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.INTEGRATIONS_MANAGE)
        val list = ListQualityProviders(tenantId.key).query()
        return ServerResponse.ok().page("quality/settings") {
            "pageTitle" to "Quality settings - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "quality-settings"
            "stage" to KnownFeatures.metadata[KnownFeatures.QUALITY]?.stage
            "providers" to list.providers.map(::view)
            "hasSwitches" to list.hasSwitches
        }
    }

    fun save(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.INTEGRATIONS_MANAGE)
        val switchable = ListQualityProviders(tenantId.key).query().providers.filter { it.canSwitch }
        SaveQualityProviderSettings(
            tenantKey = tenantId.key,
            enabled = switchable.filter { request.param(fieldName(it)).isPresent }.map { it.id }.toSet(),
        ).execute()
        return redirect("/tenants/${tenantId.key}/quality/settings?saved=true")
    }

    /** One provider as the page shows it: what it is, where text goes, when it runs, and its switch or why there is none. */
    data class ProviderRow(
        val id: String,
        val field: String,
        val name: String,
        val destination: String,
        val runsOn: String,
        val canSwitch: Boolean,
        val enabled: Boolean,
        /** Why there is no switch, when there is none. */
        val note: String?,
    )

    private fun view(provider: QualityProviderView) = ProviderRow(
        id = provider.id.value,
        field = fieldName(provider),
        name = provider.name,
        destination = when (provider.egress) {
            TextEgress.IN_PROCESS -> "Text stays in this application."
            TextEgress.INSTALLATION -> "Template text is sent to a server your organisation runs. Expressions are replaced by placeholders; data is never sent."
            TextEgress.EXTERNAL -> "Template text is sent to a service outside your organisation. Expressions are replaced by placeholders; data is never sent."
        },
        runsOn = provider.triggers.sortedBy { it.ordinal }.joinToString(", ") { trigger ->
            when (trigger) {
                CheckTrigger.SAVE -> "after a save"
                CheckTrigger.EXPLICIT -> "on Check now"
                CheckTrigger.READY -> "when marked ready"
            }
        },
        canSwitch = provider.canSwitch,
        enabled = provider.enabled,
        note = when {
            !provider.switchable -> "Always on."
            !provider.configured -> "Not available: this installation has no server configured for it. An operator sets it up (see the quality documentation)."
            else -> null
        },
    )

    private fun fieldName(provider: QualityProviderView) = "provider-${provider.id.value}"
}
