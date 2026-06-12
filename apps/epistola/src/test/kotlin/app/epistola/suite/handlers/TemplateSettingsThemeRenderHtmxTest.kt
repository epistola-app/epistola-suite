package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

/**
 * Regression cover for the template-settings theme picker, which matches the
 * assigned theme with `template.themeKey == theme.id` (and the same for
 * `themeCatalogKey`) in `templates/detail/settings.html`. Those operands are
 * Kotlin `@JvmInline value class` keys (`ThemeKey`/`CatalogKey`); SpringEL
 * cannot read the mangled value-class getter, so it field-falls-back to the
 * raw underlying `String` and the comparison is String-vs-String. This test
 * renders the settings tab with a theme actually assigned and asserts the
 * matching option comes back `selected`, proving the comparison evaluates
 * (and is `true`) rather than silently never matching.
 */
@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TemplateSettingsThemeRenderHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `settings tab marks the assigned theme option selected`() = fixture {
        var tenantKey = ""
        var templateKey = ""

        given {
            val seed = withMediator {
                val tenant = createTenant("Settings Theme Render")
                val tenantId = TenantId(tenant.id)

                CreateTheme(
                    id = ThemeId(ThemeKey.of("brand-theme"), CatalogId.default(tenantId)),
                    name = "Brand Theme",
                ).execute()

                val tplKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(tplKey, CatalogId.default(tenantId))
                CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
                UpdateDocumentTemplate(
                    id = templateId,
                    themeId = ThemeKey.of("brand-theme"),
                    themeCatalogKey = CatalogKey.DEFAULT,
                ).execute()

                tenant.id.value to tplKey.value
            }
            tenantKey = seed.first
            templateKey = seed.second
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/$tenantKey/templates/default/$templateKey/settings",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The assigned theme's <option> must render selected — that only
            // happens if both value-class `==` comparisons evaluated true.
            val option = Regex("<option[^>]*default/brand-theme[^>]*>").find(body)?.value
            assertThat(option)
                .withFailMessage("expected an <option> for default/brand-theme; body was:\n%s", body)
                .isNotNull()
            assertThat(option).contains("selected")
        }
    }
}
