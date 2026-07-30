// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class UiNotFoundRoutes {
    @Bean
    fun uiNotFoundRenderRoute(): RouterFunction<ServerResponse> = router {
        GET(NOT_FOUND_RENDER_PATH) { request ->
            val originalPath = request.attribute(NOT_FOUND_ORIGINAL_PATH_ATTRIBUTE).orElse(null) as? String
                ?: return@GET ServerResponse.notFound().build()
            ServerResponse.ok().render("layout/shell", notFoundPageModel(originalPath))
        }
    }
}
