package app.epistola.suite.versioncheck

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "epistola.version-check")
data class VersionCheckProperties(
    val enabled: Boolean = true,
    val wellKnownUrl: String = "https://epistola.app/.well-known/epistola/releases.json",
    val intervalMs: Long = 86_400_000,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(5),
)
