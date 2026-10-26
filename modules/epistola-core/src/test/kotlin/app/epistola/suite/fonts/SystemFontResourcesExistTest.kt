// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.fonts.model.sha256Hex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Drift guards for the fonts the system catalog ships, without booting Spring or a database: every
 * face it declares is on the classpath and is what its `contentHash` says, and the Liberation Sans
 * faces it carries are the renderer's own fallback files (`modules/generation`, `fonts/`), so the
 * two copies cannot drift apart.
 */
class SystemFontResourcesExistTest {

    private fun bytes(location: String): ByteArray? = javaClass.classLoader.getResourceAsStream(location)?.use { it.readBytes() }

    @Test
    fun `every face the system catalog declares is on the classpath and hashes to its declared hash`() {
        assertThat(SystemCatalogFaces.ALL).hasSize(36)

        val wrong = SystemCatalogFaces.ALL.mapNotNull { face ->
            when (val content = bytes(face.classpathLocation)) {
                null -> "${face.classpathLocation} is missing"
                else -> sha256Hex(content).takeIf { it != face.contentHash }?.let { "${face.classpathLocation} hashes to $it, not ${face.contentHash}" }
            }
        }

        assertThat(wrong).isEmpty()
    }

    @Test
    fun `the Liberation Sans faces are the renderer's fallback files`() {
        val liberation = SystemCatalogFaces.ALL.filter { it.slug == "liberation-sans" }
        assertThat(liberation).hasSize(4)

        liberation.forEach { face ->
            val fallback = "fonts/${face.classpathLocation.substringAfterLast('/')}"
            assertThat(bytes(fallback)).describedAs(fallback).isNotNull()
            assertThat(sha256Hex(bytes(fallback)!!)).describedAs(face.classpathLocation).isEqualTo(face.contentHash)
        }
    }
}
