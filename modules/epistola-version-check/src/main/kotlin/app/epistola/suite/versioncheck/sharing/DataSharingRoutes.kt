// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck.sharing

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * `/platform/sharing` — "Shared with Epistola": what this installation shares, what each check
 * sends and when it last ran. A platform page (no tenant), reachable from the tenant list; the
 * mediator enforces the platform role on both routes.
 */
@Configuration
class DataSharingRoutes(private val handler: DataSharingHandler) {
    @Bean
    fun dataSharingRouterFunction(): RouterFunction<ServerResponse> = router {
        "/platform/sharing".nest {
            GET("", handler::show)
            POST("/check", handler::check)
        }
    }
}

@Component
class DataSharingHandler {
    fun show(request: ServerRequest): ServerResponse = ServerResponse.ok().page("versioncheck/sharing") {
        "pageTitle" to "Shared with Epistola - Epistola"
        "overview" to GetDataSharingOverview().query()
    }

    fun check(request: ServerRequest): ServerResponse {
        RunDataSharingChecks().execute()
        return redirect("/platform/sharing?checked=true")
    }
}
