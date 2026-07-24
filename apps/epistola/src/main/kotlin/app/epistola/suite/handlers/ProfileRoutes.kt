// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class ProfileRoutes(
    private val handler: ProfileHandler,
) {
    @Bean
    fun profileRouterFunction(): RouterFunction<ServerResponse> = router {
        GET("/profile", handler::profile)
    }
}
