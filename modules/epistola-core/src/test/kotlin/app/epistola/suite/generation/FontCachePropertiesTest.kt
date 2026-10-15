// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation

import app.epistola.suite.templates.validation.NodeParameterSchemaProviderRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class FontCachePropertiesTest {

    private fun bind(vararg properties: Pair<String, String>): FontCacheProperties = Binder(MapConfigurationPropertySource(properties.toMap()))
        .bindOrCreate("epistola.generation.font-cache", FontCacheProperties::class.java)

    private fun rendererFor(properties: FontCacheProperties) = PdfRendererConfiguration().directPdfRenderer(NodeParameterSchemaProviderRegistry(emptyList()), properties)

    @Test
    fun `the renderer's font cache defaults to 256 MB and 64 faces`() {
        val programs = rendererFor(bind()).fontPrograms

        assertThat(programs.maxBytes).isEqualTo(256L * 1024 * 1024)
        assertThat(programs.maxFaces).isEqualTo(64)
    }

    @Test
    fun `the renderer's font cache takes the configured limits`() {
        val programs = rendererFor(
            bind(
                "epistola.generation.font-cache.max-size" to "64MB",
                "epistola.generation.font-cache.max-faces" to "8",
            ),
        ).fontPrograms

        assertThat(programs.maxBytes).isEqualTo(64L * 1024 * 1024)
        assertThat(programs.maxFaces).isEqualTo(8)
    }

    @Test
    fun `the environment variables the Helm chart renders bind`() {
        // Spring maps environment variable names only for the source named like the real environment.
        val environment = SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            mapOf<String, Any>(
                "EPISTOLA_GENERATION_FONTCACHE_MAXSIZE" to "512MB",
                "EPISTOLA_GENERATION_FONTCACHE_MAXFACES" to "0",
            ),
        )
        val properties = Binder(ConfigurationPropertySources.from(environment).filterNotNull())
            .bindOrCreate("epistola.generation.font-cache", FontCacheProperties::class.java)

        assertThat(properties.maxSize.toMegabytes()).isEqualTo(512)
        assertThat(properties.maxFaces).isZero()
    }
}
