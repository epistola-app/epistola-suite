// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common.ids

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test

/**
 * `new` is where a theme, code-list or environment list page opens its create form, beside the
 * resource at `…/{key}`; a resource keyed `new` would be listed and never opened.
 */
class NewKeyRuleTest {

    private val tenant = TenantId(TenantKey.of("acme"))
    private val catalog = CatalogId("acme", CatalogKey.of("letters"), tenant)

    @Test
    fun `a new theme, code list or environment cannot be keyed new`() {
        assertRefused("Theme") { CreateTheme(ThemeId(ThemeKey.of("new"), catalog), "New", catalogVersion = null) }
        assertRefused("Code list") {
            CreateCodeList(
                CodeListId(CodeListKey.of("new"), catalog),
                displayName = "New",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry(code = "a", label = "A")),
                catalogVersion = null,
            )
        }
        assertRefused("Environment") { CreateEnvironment(EnvironmentId(EnvironmentKey.of("new"), tenant), "New") }
    }

    @Test
    fun `a key already stored as new still reads`() {
        // These three accepted the word before; installations and catalog exports may hold it.
        assertThat(ThemeKey.of("new").value).isEqualTo("new")
        assertThat(CodeListKey.of("new").value).isEqualTo("new")
        assertThat(EnvironmentKey.of("new").value).isEqualTo("new")
    }

    @Test
    fun `a key that only starts with new is fine`() {
        CreateTheme(ThemeId(ThemeKey.of("newsletter"), catalog), "Newsletter", catalogVersion = null)
        CreateEnvironment(EnvironmentId(EnvironmentKey.of("new-test"), tenant), "New test")
    }

    private fun assertRefused(kind: String, create: ThrowingCallable) {
        assertThatThrownBy(create)
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.field).isEqualTo("key") }
            .hasMessage("$kind ID 'new' is reserved and cannot be used")
    }
}
