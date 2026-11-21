// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.forms

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * The interactive generation form (contract ADR 0011): Suite's one page reachable without signing
 * in. The link a caller hands out opens it; everything after runs on the form session that opening
 * starts. See [InteractiveFormHandler] and `InteractiveFormSecurityConfig`.
 */
@Configuration
class InteractiveFormRoutes(private val handler: InteractiveFormHandler) {
    @Bean
    fun interactiveFormRouterFunction(): RouterFunction<ServerResponse> = router {
        "/forms/{requestId}".nest {
            GET("", handler::page)
            POST("/submit", handler::submit)
            POST("/cancel", handler::cancel)
            POST("/preview", handler::preview)
        }
    }
}
