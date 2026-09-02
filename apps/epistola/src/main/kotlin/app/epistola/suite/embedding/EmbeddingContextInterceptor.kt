// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.embedding

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Adds embedding/postMessage-bridge context to Suite pages that host an
 * embeddable workspace, same idiom as
 * [app.epistola.suite.config.SiteBannerInterceptor]: `embeddingEnabled` /
 * `allowedParentOrigins`, install-wide from [EmbeddingProperties] directly —
 * their templates render the bridge `<script>` and config JSON island only
 * when embedding is on (see docs/embedding.md). The template editor is a
 * full-page Vite host rather than a `layout/shell` page, but it must receive
 * the same context: editor saves use the bridge to notify the Website.
 *
 * Does not derive the current page's resource identity — `embed-bridge.js`
 * parses that itself from `location.pathname` (the same URL convention
 * `resource-changed` detection already relies on), so there's nothing here
 * for the server to compute that the client can't derive on its own.
 */
@Component
class EmbeddingContextInterceptor(
    private val embeddingProperties: EmbeddingProperties,
) : HandlerInterceptor {

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        if (modelAndView == null || modelAndView.viewName !in setOf("layout/shell", "templates/editor")) return

        modelAndView.addObject("embeddingEnabled", embeddingProperties.enabled)
        modelAndView.addObject("allowedParentOrigins", embeddingProperties.allowedParentOrigins)
    }
}
