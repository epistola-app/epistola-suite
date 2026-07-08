package app.epistola.suite.versioncheck

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "epistola.version-check")
data class VersionCheckProperties(
    val enabled: Boolean = true,
    val wellKnownUrl: String = "https://epistola.app/.well-known/epistola/releases.json",
    /** UTC hour at which the daily version-check spread window starts. */
    val dailyWindowStartHour: Int = 8,
    /**
     * Width of the daily spread window in minutes. The actual minute is derived
     * deterministically from the installation id, so each installation keeps a
     * stable run time while installs are spread across this window.
     */
    val dailyWindowMinutes: Int = 60,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(5),
)
