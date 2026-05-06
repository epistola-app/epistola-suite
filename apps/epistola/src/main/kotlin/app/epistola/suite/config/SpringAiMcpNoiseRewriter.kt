package app.epistola.suite.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import org.slf4j.Marker

/**
 * # Spring AI 2.0-Mx MCP cleanup-noise rewriter
 *
 * Demotes one specific upstream-bug ERROR log that fires after every
 * successful `/api/mcp` request to a single WARN line with no stack trace,
 * and lets every other ERROR through unchanged.
 *
 * ## What this matches
 *
 * `IllegalStateException("Missing result context")` thrown from
 * `RequestMappingHandlerAdapter.invokeHandlerMethod`.
 *
 * Mechanism: Spring AI's `WebMvcStreamableServerTransportProvider` writes the
 * SSE response via `ServerResponse.sse(...)`, and Spring Web's
 * `DefaultAsyncServerResponse.writeAsync` calls
 * `WebAsyncManager.startDeferredResultProcessing(deferredResult)` *without*
 * a `processingContext` varargs — `concurrentResultContext` stays as
 * `Object[0]`. After the SSE write completes, Tomcat fires its catch-all
 * `ErrorPage[errorCode=0, location=/error]` (the chunked stream isn't
 * terminated cleanly). The `/error` re-dispatch routes through
 * `BasicErrorController` (a `@RestController`) → `RequestMappingHandlerAdapter`,
 * where the assert `concurrentResultContext.length > 0` fails. Tomcat logs
 * the resulting `ServletException` wrapper at ERROR — even though the MCP
 * body has already landed at the client.
 *
 * The matching rule is tight: `IllegalStateException` whose message is
 * exactly `"Missing result context"` AND whose stack trace has
 * `RequestMappingHandlerAdapter.invokeHandlerMethod` within the first three
 * frames. The first frame is always `Assert.state`; the second-frame check
 * disambiguates this exception from any other "Missing result context"
 * string Spring may produce elsewhere.
 *
 * ## What this does NOT cover
 *
 * Earlier iterations also covered an `AuthorizationDeniedException` flavour
 * fired during the same `/error` include and during the SSE async re-dispatch.
 * Those errors stem from our app's own filter stack (`MediatorFilter`,
 * `SecurityFilter`, `ApiKeyAuthenticationFilter`) historically defaulting to
 * REQUEST-only and skipping ASYNC re-dispatches — we now make all three
 * filters run on async too, so those auth-denied flavours never fire.
 *
 * Only the `Missing result context` is genuinely upstream and requires
 * suppression here.
 *
 * ## TODO: remove when Spring AI 2.0 GAs
 *
 * Track:
 * - `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` — bug is in
 *   `WebMvcStreamableServerTransportProvider` not closing the chunked stream
 *   cleanly.
 * - `org.springframework:spring-webmvc` — bug is in
 *   `DefaultAsyncServerResponse.writeAsync` not seeding the
 *   `processingContext` varargs.
 *
 * Verify removal: delete this class + its test, drop the `<turboFilter>`
 * line from `logback-spring.xml`, restart the local app, and run
 * `scripts/mcp-smoke.sh`. The full-stack ERROR lines should remain absent;
 * if they're back, restore this filter and update its docstring with the
 * version that still has the bug.
 */
class SpringAiMcpNoiseRewriter : TurboFilter() {

    /**
     * Dedicated logger for the rewritten WARN line. Named separately so
     * operators can configure it independently of any source logger.
     */
    private val replacementLogger = LoggerFactory.getLogger("app.epistola.suite.mcp.SpringAiNoise")

    override fun decide(
        marker: Marker?,
        logger: Logger?,
        level: Level?,
        format: String?,
        params: Array<out Any?>?,
        t: Throwable?,
    ): FilterReply {
        // Only rewrite ERROR-level events with a throwable. INFO/WARN/DEBUG
        // and bare text logs go through untouched.
        if (level != Level.ERROR || t == null) return FilterReply.NEUTRAL

        if (!chainContainsMissingResultContext(t)) return FilterReply.NEUTRAL

        replacementLogger.warn(
            "Spring AI MCP async-cleanup quirk [missing-result-context] suppressed: {}",
            messageOfMissingResultContext(t),
        )
        return FilterReply.DENY
    }

    private fun chainContainsMissingResultContext(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is IllegalStateException &&
                current.message == "Missing result context" &&
                originatesAtRequestMappingHandlerAdapter(current)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * The assert is `Assert.state(...)` (frame 0) thrown from
     * `RequestMappingHandlerAdapter.invokeHandlerMethod` (frame 1). Match the
     * second frame so we don't misfire on hypothetical future
     * "Missing result context" errors from elsewhere.
     */
    private fun originatesAtRequestMappingHandlerAdapter(t: Throwable): Boolean = t.stackTrace.take(3).any {
        it.className == "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter" &&
            it.methodName == "invokeHandlerMethod"
    }

    private fun messageOfMissingResultContext(t: Throwable): String? {
        var current: Throwable? = t
        while (current != null) {
            if (current is IllegalStateException && current.message == "Missing result context") {
                return current.message
            }
            current = current.cause
        }
        return t.message
    }
}
