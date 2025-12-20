package app.epistola.editor

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "epistola.editor.dev-server")
data class EditorProperties(
    val autoStart: Boolean = false,
)
