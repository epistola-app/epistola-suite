// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.handlers.ShellModelInterceptor
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.function.support.RouterFunctionMapping
import org.springframework.web.servlet.handler.AbstractHandlerMapping
import org.springframework.web.servlet.mvc.Controller

/**
 * Handles unmatched HTML page navigations after annotated and functional routes, but before
 * Spring's static-resource fallback turns them into a framework exception.
 *
 * Order 4 is immediately after Spring MVC's functional [RouterFunctionMapping] (order 3).
 * Structured, fragment, API, probe, and static-resource requests deliberately fall through to
 * their existing handlers and error contracts.
 */
@Configuration
class UiNotFoundHandlerMappingConfiguration(
    private val versionInterceptor: VersionInterceptor,
    private val shellModelInterceptor: ShellModelInterceptor,
    private val siteBannerInterceptor: SiteBannerInterceptor,
) {
    @Bean
    fun uiNotFoundHandlerMapping(): HandlerMapping = UiNotFoundHandlerMapping(UiNotFoundController()).apply {
        order = ROUTER_FUNCTION_MAPPING_ORDER + 1
        setInterceptors(versionInterceptor, shellModelInterceptor, siteBannerInterceptor)
    }
}

private class UiNotFoundHandlerMapping(
    private val handler: Controller,
) : AbstractHandlerMapping() {
    override fun getHandlerInternal(request: HttpServletRequest): Any? = handler.takeIf { request.isUiPageNavigation() }
}

private class UiNotFoundController : Controller {
    override fun handleRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ModelAndView {
        if (request.isFullPageHtmx()) {
            response.setHeader("HX-Reswap", "innerHTML")
        }
        return ModelAndView(
            "layout/shell",
            notFoundPageModel(request.requestURI),
            HttpStatus.NOT_FOUND,
        )
    }
}

internal fun HttpServletRequest.isUiPageNavigation(): Boolean {
    if (method != HttpMethod.GET.name()) return false
    if (isExcludedNotFoundPath(requestURI)) return false
    if (isFragmentHtmx()) return false

    val accepted = getHeader("Accept")?.let {
        runCatching { MediaType.parseMediaTypes(it) }.getOrDefault(emptyList())
    }.orEmpty()
    return accepted.any {
        it.type.equals(MediaType.TEXT_HTML.type, ignoreCase = true) &&
            (it.subtype == "*" || it.subtype.equals(MediaType.TEXT_HTML.subtype, ignoreCase = true))
    }
}

private fun HttpServletRequest.isFragmentHtmx(): Boolean = getHeader("HX-Request") == "true" && !isFullPageHtmx()

internal fun HttpServletRequest.isFullPageHtmx(): Boolean = getHeader("HX-Request") == "true" &&
    (getHeader("HX-Boosted") == "true" || getHeader("HX-History-Restore-Request") == "true")

private fun isExcludedNotFoundPath(path: String): Boolean = path in EXCLUDED_EXACT_PATHS || EXCLUDED_PATH_PREFIXES.any(path::startsWith)

private const val ROUTER_FUNCTION_MAPPING_ORDER = 3

private val EXCLUDED_PATH_PREFIXES = listOf(
    "/api/",
    "/api-docs/",
    "/actuator/",
    "/errors/",
    "/css/",
    "/js/",
    "/fonts/",
    "/images/",
    "/design-system/",
    "/webjars/",
)

private val EXCLUDED_EXACT_PATHS = setOf(
    "/livez",
    "/readyz",
    "/error",
    "/errors",
    "/favicon.ico",
)
