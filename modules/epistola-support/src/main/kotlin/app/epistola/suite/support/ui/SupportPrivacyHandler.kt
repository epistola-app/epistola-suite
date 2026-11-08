// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

import app.epistola.suite.htmx.htmx
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.privacy.GetSupportPrivacy
import app.epistola.suite.support.privacy.SetSupportPrivacy
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/** The privacy panel of `/platform/support` (ADR 0052 §1–2): what Epistola sees of the tenants, and how much telemetry carries. */
@Component
class SupportPrivacyHandler {
    fun show(request: ServerRequest): ServerResponse = panel(request, editing = false)

    fun edit(request: ServerRequest): ServerResponse = panel(request, editing = true)

    fun save(request: ServerRequest): ServerResponse {
        val form = request.params()
        val before = GetSupportPrivacy().query()
        val identity = form.getFirst("tenantIdentity")?.let(TenantIdentity::parse) ?: before.tenantIdentity
        val telemetry = form.getFirst("telemetry")?.let(TelemetryDetail::parse) ?: before.telemetry
        if (!before.fromConfiguration && identity == before.tenantIdentity && telemetry == before.telemetry) return panel(request, editing = false, notice = "Nothing changed.")
        SetSupportPrivacy(identity, telemetry).execute()
        return panel(request, editing = false, notice = "Saved.")
    }

    private fun panel(request: ServerRequest, editing: Boolean, notice: String? = null): ServerResponse = request.htmx {
        fragment("support/platform", "privacy") {
            "privacy" to GetSupportPrivacy().query()
            "privacyEditing" to editing
            "privacyNotice" to notice
        }
        onFullPage { redirect("/platform/support") }
    }
}

@Configuration
class SupportPrivacyRoutes(private val handler: SupportPrivacyHandler) {
    @Bean
    fun supportPrivacyRouterFunction(): RouterFunction<ServerResponse> = router {
        GET("/platform/support/privacy", handler::show)
        GET("/platform/support/privacy/edit", handler::edit)
        POST("/platform/support/privacy", handler::save)
    }
}
