// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every command that lets an author choose a resource key refuses the suite's reserved words
 * (`NewResourceKeys`) by calling `validateResourceKey` — so a new resource type, or a new way to
 * create one, cannot forget the rule. A create command is one named `Create…` or `Upload…` whose
 * constructor takes the new resource's id. Import commands are left alone: they bring keys chosen
 * elsewhere, which must keep reading.
 */
class NewResourceKeysGuardTest {
    private val command = Regex("""data class ((?:Create|Upload)\w+)\((.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
    private val resourceId = Regex("""\bval id: (TemplateId|VariantId|StencilId|ThemeId|AttributeId|CodeListId|EnvironmentId|AssetKey|FontId)\b""")

    @Test
    fun `every command creating a resource under a chosen key checks the reserved words`() {
        val creating = RepoSources.mainKotlinFiles().flatMap { path ->
            val source = RepoSources.stripComments(Files.readString(path))
            command.findAll(source)
                .filter { resourceId.containsMatchIn(it.groupValues[2]) }
                .map { Triple(it.groupValues[1], path, "validateResourceKey(" in source) }
                .toList()
        }
        val names = creating.map { it.first }
        assertTrue(names.containsAll(listOf("CreateTheme", "CreateDocumentTemplate", "UploadAsset")), "found only $names — the scan would pass vacuously")

        val offenders = creating.filterNot { it.third }.map { "${it.first} (${RepoSources.relativize(it.second)})" }
        assertEquals(emptyList(), offenders, "Call validateResourceKey(kind, id.key) in the command's init")
    }
}
