package app.epistola.editor

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.File
import kotlin.concurrent.thread

@Component
@ConditionalOnProperty("epistola.editor.dev-server.auto-start", havingValue = "true")
class EditorDevServer {
    private val log = LoggerFactory.getLogger(javaClass)
    private var process: Process? = null

    @PostConstruct
    fun start() {
        val editorDir = findEditorModuleDir() ?: run {
            log.warn("Could not find editor module directory, skipping Vite watch")
            return
        }

        // Start Vite watch asynchronously to not block Spring Boot startup
        thread(name = "vite-watch") {
            log.info("Starting Vite watch mode...")

            process = ProcessBuilder("npm", "run", "watch")
                .directory(editorDir)
                .inheritIO()
                .start()

            log.info("Vite watch mode started - editor will rebuild on file changes")
        }
    }

    @PreDestroy
    fun stop() {
        process?.let { proc ->
            log.info("Stopping Vite watch...")

            // Kill all descendant processes first (node spawned by npm)
            proc.toHandle().descendants().forEach { descendant ->
                descendant.destroyForcibly()
            }

            // Then kill the main process
            proc.destroyForcibly()

            // Wait for termination (max 5 seconds)
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

            log.info("Stopped Vite watch.")
        }
    }

    private fun findEditorModuleDir(): File? {
        val candidates = listOf(
            File("modules/editor"),
            File("../modules/editor"),
            File("../../modules/editor"),
        )
        return candidates.find { it.exists() && File(it, "package.json").exists() }
    }
}
