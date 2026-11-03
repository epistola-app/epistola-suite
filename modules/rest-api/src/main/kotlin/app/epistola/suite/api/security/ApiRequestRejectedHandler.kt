// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.security

import app.epistola.suite.api.v1.ApiExceptionHandler
import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.writeProblemDetail
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Answers a request Spring Security's firewall refuses before any filter runs (a path with `;`,
 * `//` or an encoded slash) with a 400 `bad-request` problem under `/api`, as every other refusal
 * there is. Elsewhere it keeps Spring Security's default, a bare 400.
 *
 * Spring Security uses a [RequestRejectedHandler] bean for every filter chain. Rejections the
 * firewall makes later, when a header is read, reach [ApiExceptionHandler] instead.
 */
@Component
class ApiRequestRejectedHandler(
    private val objectMapper: ObjectMapper,
) : RequestRejectedHandler {

    private val log = LoggerFactory.getLogger(javaClass)
    private val fallback = HttpStatusRequestRejectedHandler()

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, ex: RequestRejectedException) {
        if (!request.requestURI.startsWith("/api/")) return fallback.handle(request, response, ex)
        log.warn("API request rejected by the firewall: {}", sanitizeForLog(ex.message.orEmpty()))
        writeProblemDetail(response, objectMapper, request, ApiProblemTypes.BAD_REQUEST, ApiExceptionHandler.REQUEST_REJECTED_DETAIL)
    }
}
