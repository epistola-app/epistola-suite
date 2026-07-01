package app.epistola.suite.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.spi.FilterReply
import jakarta.servlet.ServletException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SpringAiMcpNoiseRewriterTest {

    private val filter = SpringAiMcpNoiseRewriter()
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var replacementLogger: Logger

    @BeforeEach
    fun attachAppender() {
        replacementLogger = LoggerFactory.getLogger("app.epistola.suite.mcp.SpringAiNoise") as Logger
        listAppender = ListAppender<ILoggingEvent>().apply { start() }
        replacementLogger.addAppender(listAppender)
    }

    @AfterEach
    fun detachAppender() {
        replacementLogger.detachAppender(listAppender)
    }

    @Test
    fun `denies and rewrites the upstream Missing result context error`() {
        val cause = makeMissingResultContext()
        val wrapper = ServletException("Request processing failed: ${cause.message}", cause)

        val reply = filter.decide(null, null, Level.ERROR, "any format", null, wrapper)

        assertThat(reply).isEqualTo(FilterReply.DENY)
        assertThat(listAppender.list).hasSize(1)
        listAppender.list.first().let { event ->
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.formattedMessage).contains("missing-result-context")
            assertThat(event.formattedMessage).contains("Missing result context")
            assertThat(event.throwableProxy).isNull() // crucial: no stack trace
        }
    }

    @Test
    fun `passes through unrelated IllegalStateException with same message but different originating frame`() {
        val different = IllegalStateException("Missing result context").also {
            it.stackTrace = arrayOf(
                stackFrame("app.epistola.suite.feature.MyService", "compute"),
                stackFrame("app.epistola.suite.feature.MyService", "process"),
            )
        }

        val reply = filter.decide(null, null, Level.ERROR, null, null, different)

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL)
        assertThat(listAppender.list).isEmpty()
    }

    @Test
    fun `passes through ERROR events with no throwable`() {
        val reply = filter.decide(null, null, Level.ERROR, "boom", null, null)

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL)
        assertThat(listAppender.list).isEmpty()
    }

    @Test
    fun `passes through INFO and WARN events even when they happen to match the patterns`() {
        // Defense in depth: even if some code path emitted the same throwable
        // at a non-ERROR level, we don't want to interfere — the filter is
        // strictly an ERROR-noise demoter.
        val cause = makeMissingResultContext()
        val wrapper = ServletException("Request processing failed: ${cause.message}", cause)

        assertThat(filter.decide(null, null, Level.WARN, null, null, wrapper))
            .isEqualTo(FilterReply.NEUTRAL)
        assertThat(filter.decide(null, null, Level.INFO, null, null, wrapper))
            .isEqualTo(FilterReply.NEUTRAL)
        assertThat(listAppender.list).isEmpty()
    }

    @Test
    fun `walks the cause chain - outer wrapper above ServletException is also matched`() {
        // Verifies the chain walk: if some upstream code wraps the ServletException
        // again, we still match.
        val cause = makeMissingResultContext()
        val inner = ServletException("Request processing failed: ${cause.message}", cause)
        val outer = RuntimeException("outer wrapper", inner)

        val reply = filter.decide(null, null, Level.ERROR, null, null, outer)

        assertThat(reply).isEqualTo(FilterReply.DENY)
        assertThat(listAppender.list).hasSize(1)
        assertThat(listAppender.list.first().formattedMessage).contains("missing-result-context")
    }

    private fun makeMissingResultContext(): IllegalStateException = IllegalStateException("Missing result context").also {
        it.stackTrace = arrayOf(
            stackFrame("org.springframework.util.Assert", "state"),
            stackFrame(
                "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter",
                "invokeHandlerMethod",
            ),
            stackFrame("org.springframework.web.servlet.DispatcherServlet", "doDispatch"),
        )
    }

    private fun stackFrame(className: String, methodName: String): StackTraceElement = StackTraceElement(className, methodName, "Source.java", 1)
}
