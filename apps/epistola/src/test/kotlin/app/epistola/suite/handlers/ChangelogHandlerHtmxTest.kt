package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.changelog.ChangelogAudience
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Server-contract cover for the changelog dialog's filter bar (audience / type / scope): the dialog
 * renders the controls, a bare request defaults to the Users view, and each selection returns the partial
 * layout fragment with the chosen filters threaded through the version links.
 */
class ChangelogHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var renderer: ChangelogRenderer

    @Test
    fun `changelog dialog renders the filter bar and defaults to the Users view`() {
        val response = get("/changelog")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // Full dialog (content fragment) carries the dialog title and the filter rows.
        assertThat(response.body).contains("id=\"changelog-title\"")
        assertThat(response.body).contains("aria-label=\"Filter by audience\"").contains("aria-label=\"Filter by type\"")
        assertThat(response.body).contains(">Users<").contains(">Developers<").contains(">All<")
        // Default view = Users, so the version links carry audience=user across the version list.
        assertThat(paramCount(response.body, "audience", "user")).isGreaterThan(1)
    }

    @Test
    fun `selecting an audience returns the layout fragment with that view threaded through`() {
        val response = get("/changelog?audience=developer")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // Partial swap: the layout fragment has the version list but not the outer dialog header.
        assertThat(response.body).contains("changelog-layout")
        assertThat(response.body).doesNotContain("id=\"changelog-title\"")
        assertThat(paramCount(response.body, "audience", "developer")).isGreaterThan(1)
    }

    @Test
    fun `selecting a type threads the type filter through the version links`() {
        val type = renderer.availableTypes(ChangelogAudience.USER, includeUnreleased = true).first()
        val response = get("/changelog?type=$type")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).doesNotContain("id=\"changelog-title\"")
        // Version links carry the selected type, so it appears more than the lone type button.
        assertThat(paramCount(response.body, "type", type)).isGreaterThan(1)
    }

    @Test
    fun `selecting a scope threads the scope filter through the version links`() {
        val scope = renderer.availableScopes(ChangelogAudience.USER, includeUnreleased = true).first()
        val response = get("/changelog?scope=$scope")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("aria-label=\"Filter by scope\"")
        // Scopes only exist on Unreleased (commit-style) entries, so the filter narrows to that one version,
        // whose link carries the chosen scope.
        assertThat(paramCount(response.body, "scope", scope)).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `the dialog previews the unreleased section as Upcoming only when entries exist`() {
        val hasVisibleUnreleased = renderer.entries(ChangelogAudience.USER, includeUnreleased = true).any { !it.released }
        val response = get("/changelog")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        if (hasVisibleUnreleased) {
            assertThat(response.body).contains(">Upcoming<")
            // The [Unreleased] section carries a summary paragraph, rendered above its entries.
            assertThat(response.body).contains("changelog-summary")
        } else {
            assertThat(response.body).doesNotContain(">Upcoming<")
        }
    }

    @Test
    fun `an unknown audience falls back to the Users view`() {
        val response = get("/changelog?audience=bogus")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(paramCount(response.body, "audience", "user")).isGreaterThan(1)
    }

    /** Counts `name=value` occurrences across both the filter buttons and the version links. */
    private fun paramCount(body: String?, name: String, value: String): Int = Regex("$name=$value(?![A-Za-z0-9/-])").findAll(body ?: "").count()

    private fun get(url: String): ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)
}
