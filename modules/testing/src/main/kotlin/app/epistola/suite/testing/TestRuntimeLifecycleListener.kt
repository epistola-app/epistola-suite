package app.epistola.suite.testing

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * JUnit Platform hook that closes shared test infrastructure before the Gradle
 * test worker JVM falls back to unordered shutdown hooks.
 */
class TestRuntimeLifecycleListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        TestRuntimeLifecycle.shutdown()
    }
}
