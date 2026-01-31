import org.gradle.api.GradleException
import java.io.File

/**
 * Provides paths to mise-managed tool shims.
 *
 * mise creates standalone executable shims at ~/.local/share/mise/shims/
 * that automatically use the correct tool versions from .mise.toml.
 * These shims work without needing `mise exec` or PATH manipulation.
 */
object MiseShims {
    private val homeDir: String = System.getProperty("user.home")

    private val shimsDir: String by lazy {
        val miseDataDir = System.getenv("MISE_DATA_DIR") ?: "$homeDir/.local/share/mise"
        val shimsPath = "$miseDataDir/shims"
        if (!File(shimsPath).exists()) {
            throw GradleException(
                """
                mise shims not found at $shimsPath.

                Please ensure mise is installed and run:
                  mise install

                See https://mise.jdx.dev/ for installation instructions.
                """.trimIndent()
            )
        }
        shimsPath
    }

    val pnpm: String get() = "$shimsDir/pnpm"
    val node: String get() = "$shimsDir/node"
    val gradle: String get() = "$shimsDir/gradle"
}
