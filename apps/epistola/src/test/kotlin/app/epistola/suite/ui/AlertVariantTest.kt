// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Holds the alert variants used in templates and the ones the design system defines together.
 *
 * A variant with no rule is not a small mistake: `.alert` supplies only layout, so the colour, the
 * border and the colour-blind-safe severity icon all come from the variant class. Eight templates
 * used `alert-danger`, which the design system has never defined — those alerts rendered as plain
 * text with nothing marking them as failures, and every one of them was on an error path, which is
 * why it went unnoticed until someone hit one.
 *
 * Sibling of `ExchangeStatusBadgeTest`, which does the same for badges.
 */
class AlertVariantTest {
    private val componentsCss: String by lazy {
        AlertVariantTest::class.java.getResource("/static/design-system/components.css")
            ?.readText()
            ?: error("components.css not found on the classpath — is the design-system copy task wired into processResources?")
    }

    @Test
    fun `every alert variant used in a template is defined by the design system`() {
        val used = templateRoots()
            .flatMap { root -> Files.walk(root).use { paths -> paths.filter { it.extension == "html" }.toList() } }
            .flatMap { file -> variantsIn(file.readText()).map { file to it } }
            .distinct()

        assertThat(used)
            .withFailMessage("no alert variants found in any template — has the template layout moved?")
            .isNotEmpty()

        assertThat(used).allSatisfy { (file, variant) ->
            assertThat(componentsCss)
                .withFailMessage(
                    "%s uses `%s`, which components.css does not define — it will render with no colour and no severity icon",
                    file.fileName,
                    variant,
                ).contains(".$variant {")
        }
    }

    /** Every module may contribute templates, so the sweep follows the source tree rather than one app. */
    private fun templateRoots(): List<Path> = sequenceOf("apps", "modules")
        .map(repositoryRoot()::resolve)
        .filter(Files::isDirectory)
        .flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { it.endsWith("src/main/resources/templates") }.toList().asSequence()
            }
        }.toList()

    /** Tests run with the module as the working directory, so the sweep starts from the checkout. */
    private fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        ?: error("could not find the repository root from ${Path.of("").toAbsolutePath()}")

    /**
     * Variants named in a `class` attribute that also carries the base `alert`.
     *
     * Scoped that tightly on purpose: `alert-title` is structural rather than a variant, and
     * `alert-triangle` is the name of an icon, so a looser match reports things that were never
     * meant to be styled as alerts.
     */
    private fun variantsIn(html: String): List<String> = CLASS_ATTRIBUTE.findAll(html)
        .map { it.groupValues[1].split(Regex("\\s+")) }
        .filter { "alert" in it }
        .flatMap { classes -> classes.filter { it.startsWith("alert-") && it != "alert-title" } }
        .distinct()
        .toList()

    private companion object {
        val CLASS_ATTRIBUTE = Regex("""class="([^"]*)"""")
    }
}
