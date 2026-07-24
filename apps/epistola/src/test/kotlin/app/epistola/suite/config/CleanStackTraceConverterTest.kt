// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import ch.qos.logback.classic.spi.ThrowableProxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit-tests the framework-frame omission filter. The converter exists to
 * shrink long stack traces by collapsing irrelevant framework frames, but it
 * must NEVER omit the entire trace — when every frame happens to live in
 * framework code, it has to show them all so the failure stays debuggable.
 */
class CleanStackTraceConverterTest {

    private val converter = CleanStackTraceConverter()

    @Test
    fun `mixed app and framework frames are summarised`() {
        val ex = makeMixedTrace()
        val rendered = converter.throwableProxyToString(ThrowableProxy(ex))
        // App frame is shown, surrounding framework frames are collapsed.
        assertThat(rendered).contains("at app.epistola.suite.feature.MyClass.doStuff")
        assertThat(rendered).contains("framework frames omitted")
    }

    @Test
    fun `pure framework trace is rendered in full instead of just an omission line`() {
        val ex = makeFrameworkOnlyTrace()
        val rendered = converter.throwableProxyToString(ThrowableProxy(ex))
        // The whole point: an all-framework trace shouldn't collapse to nothing.
        assertThat(rendered).doesNotContain("framework frames omitted")
        assertThat(rendered).contains("at org.springframework.web.servlet.DispatcherServlet")
        assertThat(rendered).contains("at java.lang.VirtualThread.run")
    }

    @Test
    fun `caused-by chain renders each cause`() {
        val root = RuntimeException("root cause").also { it.stackTrace = appOnlyFrames() }
        val wrapped = IllegalStateException("wrapper", root).also { it.stackTrace = appOnlyFrames() }
        val rendered = converter.throwableProxyToString(ThrowableProxy(wrapped))
        assertThat(rendered).contains("IllegalStateException: wrapper")
        assertThat(rendered).contains("Caused by: java.lang.RuntimeException: root cause")
    }

    private fun makeMixedTrace(): RuntimeException {
        val ex = RuntimeException("boom")
        ex.stackTrace = arrayOf(
            stackFrame("org.springframework.web.servlet.DispatcherServlet", "service"),
            stackFrame("org.springframework.web.servlet.DispatcherServlet", "doDispatch"),
            stackFrame("app.epistola.suite.feature.MyClass", "doStuff"),
            stackFrame("org.apache.catalina.core.ApplicationFilterChain", "doFilter"),
            stackFrame("java.lang.VirtualThread", "run"),
        )
        return ex
    }

    private fun makeFrameworkOnlyTrace(): RuntimeException {
        val ex = RuntimeException("Missing result context")
        ex.stackTrace = arrayOf(
            stackFrame("org.springframework.web.servlet.DispatcherServlet", "service"),
            stackFrame("org.springframework.web.servlet.FrameworkServlet", "processRequest"),
            stackFrame("jakarta.servlet.http.HttpServlet", "service"),
            stackFrame("org.apache.catalina.core.ApplicationFilterChain", "doFilter"),
            stackFrame("java.lang.VirtualThread", "run"),
        )
        return ex
    }

    private fun appOnlyFrames(): Array<StackTraceElement> = arrayOf(
        stackFrame("app.epistola.suite.feature.MyClass", "doStuff"),
    )

    private fun stackFrame(className: String, methodName: String): StackTraceElement = StackTraceElement(className, methodName, "Source.kt", 1)
}
