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
 * Adds embedding/postMessage-bridge context to shell pages, same idiom as
 * [app.epistola.suite.config.SiteBannerInterceptor]: `embeddingEnabled` /
 * `allowedParentOrigins`, install-wide from [EmbeddingProperties] directly —
 * the shell renders the bridge `<script>` and its config JSON island only
 * when embedding is on (see docs/embedding.md).
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
        if (modelAndView == null || modelAndView.viewName != "layout/shell") return

        modelAndView.addObject("embeddingEnabled", embeddingProperties.enabled)
        modelAndView.addObject("allowedParentOrigins", embeddingProperties.allowedParentOrigins)
    }
}
