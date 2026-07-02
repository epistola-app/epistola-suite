package app.epistola.suite.testing

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.slf4j.LoggerFactory

/**
 * JUnit Platform hook for the test worker JVM lifecycle: it warms up logging before any
 * test runs and closes shared test infrastructure before the JVM falls back to unordered
 * shutdown hooks.
 */
class TestRuntimeLifecycleListener : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession) {
        // Runs once, single-threaded, before any test class is instantiated. Finishing the
        // SLF4J -> Logback binding here means no parallel test can catch SLF4J half-initialized:
        // during that one-time window LoggerFactory hands back placeholder SubstituteLogger /
        // SubstituteLoggerFactory instances, which caused the intermittent parallel-only flakes
        // (ClassCastException casting to a Logback Logger, and log events silently dropped).
        LoggerFactory.getILoggerFactory()
    }

    override fun launcherSessionClosed(session: LauncherSession) {
        TestRuntimeLifecycle.shutdown()
    }
}
