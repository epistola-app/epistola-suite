// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.classic.spi.StackTraceElementProxy

/**
 * Logback converter that collapses irrelevant framework frames in stack traces,
 * keeping only application-relevant frames for readability.
 *
 * Register as a conversion rule in logback-spring.xml:
 * ```xml
 * <conversionRule conversionWord="cleanEx" converterClass="app.epistola.suite.config.CleanStackTraceConverter"/>
 * ```
 */
class CleanStackTraceConverter : ThrowableProxyConverter() {

    companion object {
        /** Package prefixes — any class under these packages is treated as framework. */
        private val FRAMEWORK_PACKAGE_PREFIXES = listOf(
            "org.springframework.",
            "org.apache.catalina.",
            "org.apache.coyote.",
            "org.apache.tomcat.",
            "jakarta.servlet.",
            "java.lang.reflect.",
            "jdk.internal.",
            "sun.reflect.",
            "net.bytebuddy.",
            "org.hibernate.",
            "io.micrometer.",
        )

        /**
         * Specific framework classes (not packages). Required when the offender is
         * a bare class rather than a package member — `java.lang.VirtualThread` is
         * its own class, so a "java.lang.VirtualThread." prefix would never match
         * the className "java.lang.VirtualThread".
         */
        private val FRAMEWORK_CLASSES = setOf(
            "java.lang.VirtualThread",
        )
    }

    public override fun throwableProxyToString(tp: IThrowableProxy?): String {
        if (tp == null) return ""
        val sb = StringBuilder(256)
        renderProxy(sb, tp, isFirst = true)
        return sb.toString()
    }

    private fun renderProxy(sb: StringBuilder, tp: IThrowableProxy, isFirst: Boolean) {
        if (!isFirst) {
            sb.append("Caused by: ")
        }
        sb.append(tp.className).append(": ").append(tp.message).append('\n')
        renderFrames(sb, tp.stackTraceElementProxyArray)

        if (tp.cause != null) {
            renderProxy(sb, tp.cause, isFirst = false)
        }

        for (suppressed in tp.suppressed.orEmpty()) {
            sb.append("Suppressed: ")
            renderProxy(sb, suppressed, isFirst = false)
        }
    }

    private fun renderFrames(sb: StringBuilder, frames: Array<StackTraceElementProxy>?) {
        if (frames == null) return

        // If the entire trace lives in framework code, omitting frames would
        // produce just "... N framework frames omitted" and hide everything
        // useful. Fall back to a full render so the failure remains debuggable.
        val hasAppFrame = frames.any { !isFramework(it.stackTraceElement.className) }
        if (!hasAppFrame) {
            for (frame in frames) {
                sb.append("\tat ").append(frame.stackTraceElement).append('\n')
            }
            return
        }

        var omitted = 0
        for (frame in frames) {
            val className = frame.stackTraceElement.className
            if (isFramework(className)) {
                omitted++
            } else {
                if (omitted > 0) {
                    sb.append("\t... ").append(omitted).append(" framework frames omitted\n")
                    omitted = 0
                }
                sb.append("\tat ").append(frame.stackTraceElement).append('\n')
            }
        }
        if (omitted > 0) {
            sb.append("\t... ").append(omitted).append(" framework frames omitted\n")
        }
    }

    private fun isFramework(className: String): Boolean = className in FRAMEWORK_CLASSES ||
        FRAMEWORK_PACKAGE_PREFIXES.any { className.startsWith(it) }
}
