package app.epistola.suite.documents

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "epistola.batch-download")
data class BatchDownloadProperties(
    val maxPartSizeMb: Long = 250,
    val stagingParallelism: Int = 32,
)
