// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.desk.GetSupportRequestHandling
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SetSupportRequestHandling
import app.epistola.suite.support.ui.SupportPlatformPanel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import java.util.UUID

/**
 * Who handles new support requests (ADR 0053 §4), on the Epistola support page: the installation
 * administrator's to set, as the rest of the installation's support setup. The support desk shows it
 * without the form.
 */
@Component
class SupportRequestHandlingPanel : SupportPlatformPanel {
    override val fragment = "support-requests/handling :: handling"

    override fun model(): Any = GetSupportRequestHandling().query()
}

@Configuration
class SupportRequestHandlingRoutes {
    @Bean
    fun supportRequestHandlingRouterFunction(): RouterFunction<ServerResponse> = router {
        "/platform/support/request-handling".nest {
            POST("", ::save)
            POST("/reset", ::reset)
        }
    }

    private fun save(request: ServerRequest): ServerResponse {
        // Blank: this inbox. Anything else must name a connection; a value that does not is refused, not saved as "here".
        val raw = request.param("connection").orElse("").trim()
        val connection = if (raw.isEmpty()) null else runCatching { UUID.fromString(raw) }.getOrNull() ?: return ServerResponse.badRequest().build()
        SetSupportRequestHandling(connection).execute()
        return redirect(BACK)
    }

    private fun reset(request: ServerRequest): ServerResponse {
        ResetSupportRequestHandling().execute()
        return redirect(BACK)
    }

    private companion object {
        const val BACK = "/platform/support#support-request-handling"
    }
}
