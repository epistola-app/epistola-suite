// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.PrintWriter
import java.io.Writer

/**
 * Converts empty 404 responses from page handlers into the shared not-found page.
 *
 * Existing handlers can keep returning `ServerResponse.notFound().build()`. Only browser page
 * navigations are wrapped; API, structured, static-resource, download, and HTMX fragment
 * contracts remain unchanged.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UiNotFoundResponseFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI == NOT_FOUND_RENDER_PATH || !request.isUiPageNavigation()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val wrappedResponse = DeferredNotFoundResponse(response)
        filterChain.doFilter(request, wrappedResponse)

        if (
            wrappedResponse.status == HttpServletResponse.SC_NOT_FOUND &&
            !wrappedResponse.bodyWritten &&
            !wrappedResponse.isCommitted
        ) {
            wrappedResponse.reset()
            request.setAttribute(NOT_FOUND_ORIGINAL_PATH_ATTRIBUTE, request.requestURI)
            wrappedResponse.status = HttpServletResponse.SC_NOT_FOUND
            wrappedResponse.contentType = MediaType.TEXT_HTML_VALUE
            if (request.isFullPageHtmx()) {
                wrappedResponse.setHeader("HX-Reswap", "innerHTML")
            }
            request.getRequestDispatcher(NOT_FOUND_RENDER_PATH).include(request, wrappedResponse)
        }
    }
}

private class DeferredNotFoundResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
    var bodyWritten = false
        private set

    private var trackingOutputStream: ServletOutputStream? = null
    private var trackingWriter: PrintWriter? = null

    override fun sendError(status: Int) {
        if (status == HttpServletResponse.SC_NOT_FOUND) {
            setStatus(status)
        } else {
            super.sendError(status)
        }
    }

    override fun sendError(
        status: Int,
        message: String,
    ) {
        if (status == HttpServletResponse.SC_NOT_FOUND) {
            setStatus(status)
        } else {
            super.sendError(status, message)
        }
    }

    override fun getOutputStream(): ServletOutputStream {
        check(trackingWriter == null) { "getWriter() has already been called for this response" }
        return trackingOutputStream ?: TrackingServletOutputStream(super.getOutputStream()) {
            bodyWritten = true
        }.also { trackingOutputStream = it }
    }

    override fun getWriter(): PrintWriter {
        check(trackingOutputStream == null) { "getOutputStream() has already been called for this response" }
        return trackingWriter ?: PrintWriter(
            TrackingWriter(super.getWriter()) {
                bodyWritten = true
            },
        ).also { trackingWriter = it }
    }
}

private class TrackingServletOutputStream(
    private val delegate: ServletOutputStream,
    private val onWrite: () -> Unit,
) : ServletOutputStream() {
    override fun write(value: Int) {
        onWrite()
        delegate.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length > 0) onWrite()
        delegate.write(bytes, offset, length)
    }

    override fun isReady(): Boolean = delegate.isReady

    override fun setWriteListener(listener: WriteListener) = delegate.setWriteListener(listener)
}

private class TrackingWriter(
    private val delegate: Writer,
    private val onWrite: () -> Unit,
) : Writer() {
    override fun write(
        characters: CharArray,
        offset: Int,
        length: Int,
    ) {
        if (length > 0) onWrite()
        delegate.write(characters, offset, length)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

internal const val NOT_FOUND_RENDER_PATH = "/internal/not-found"
internal const val NOT_FOUND_ORIGINAL_PATH_ATTRIBUTE =
    "app.epistola.suite.config.UiNotFoundResponseFilter.originalPath"
