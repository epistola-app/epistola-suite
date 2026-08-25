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
 * [app.epistola.suite.config.SiteBannerInterceptor]:
 *
 * - `embeddingEnabled` / `allowedParentOrigins`: install-wide, from [EmbeddingProperties]
 *   directly — the shell renders the bridge `<script>` and its config JSON island
 *   only when embedding is on (see docs/embedding.md).
 * - `currentResource`: the identity of the single resource the current page is
 *   showing (template/theme/stencil detail), or absent on list/dashboard pages.
 *   Derived from the request path rather than threaded through each detail
 *   handler — the URL shape is already the shared identity scheme
 *   (`/tenants/{tenantId}/{templates|themes|stencils}/{catalogKey}/{key}...`,
 *   confirmed identical to the REST API's), so one regex here covers every
 *   resource-detail route today and any sub-tab path added under it, without a
 *   per-handler edit.
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

        if (!embeddingProperties.enabled) return

        CURRENT_RESOURCE_PATTERN.find(request.requestURI)?.let { match ->
            val (tenantId, segment, catalogKey, key) = match.destructured
            modelAndView.addObject(
                "currentResource",
                mapOf(
                    "resourceType" to RESOURCE_TYPE_BY_SEGMENT.getValue(segment),
                    "tenantId" to tenantId,
                    "catalogKey" to catalogKey,
                    "key" to key,
                ),
            )
        }
    }

    private companion object {
        val RESOURCE_TYPE_BY_SEGMENT = mapOf(
            "templates" to "template",
            "themes" to "theme",
            "stencils" to "stencil",
        )
        val CURRENT_RESOURCE_PATTERN = Regex(
            "^/tenants/([^/]+)/(${RESOURCE_TYPE_BY_SEGMENT.keys.joinToString("|")})/([^/]+)/([^/]+)",
        )
    }
}
