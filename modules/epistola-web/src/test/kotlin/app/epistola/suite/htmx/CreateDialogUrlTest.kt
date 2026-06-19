package app.epistola.suite.htmx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test for the server-side `create` param merge. Pure function, no Spring —
 * runs in the `unitTest` profile. The point of this test is the merge guarantee:
 * `create` is added without disturbing any other query parameter, so the feature
 * stays correct as more params (e.g. a list's `catalog` filter) appear.
 */
class CreateDialogUrlTest {

    private val fallback = "/tenants/acme/templates"

    @Test
    fun `falls back to list path with create when current URL is absent`() {
        assertThat(urlWithCreateParam(null, fallback)).isEqualTo("$fallback?create")
        assertThat(urlWithCreateParam("", fallback)).isEqualTo("$fallback?create")
        assertThat(urlWithCreateParam("   ", fallback)).isEqualTo("$fallback?create")
    }

    @Test
    fun `falls back when the client-supplied current URL is malformed`() {
        // HX-Current-URL is client-controlled; an unparseable value must not throw.
        assertThat(urlWithCreateParam("/a b c", fallback)).isEqualTo("$fallback?create")
        assertThat(urlWithCreateParam("/tenants/acme/templates?x=%", fallback)).isEqualTo("$fallback?create")
    }

    @Test
    fun `appends create when there is no existing query`() {
        assertThat(urlWithCreateParam("/tenants/acme/templates", fallback))
            .isEqualTo("/tenants/acme/templates?create")
    }

    @Test
    fun `preserves an existing param and appends create`() {
        assertThat(urlWithCreateParam("/tenants/acme/templates?catalog=invoices", fallback))
            .isEqualTo("/tenants/acme/templates?catalog=invoices&create")
    }

    @Test
    fun `preserves multiple existing params`() {
        assertThat(urlWithCreateParam("/tenants/acme/templates?catalog=invoices&sort=name", fallback))
            .isEqualTo("/tenants/acme/templates?catalog=invoices&sort=name&create")
    }

    @Test
    fun `is idempotent when create is already present`() {
        assertThat(urlWithCreateParam("/tenants/acme/templates?create", fallback))
            .isEqualTo("/tenants/acme/templates?create")
        assertThat(urlWithCreateParam("/tenants/acme/templates?catalog=invoices&create", fallback))
            .isEqualTo("/tenants/acme/templates?catalog=invoices&create")
    }

    @Test
    fun `strips scheme and host, keeping a path-relative URL`() {
        assertThat(urlWithCreateParam("https://app.epistola.test/tenants/acme/templates?catalog=invoices", fallback))
            .isEqualTo("/tenants/acme/templates?catalog=invoices&create")
    }

    @Test
    fun `supports an alternate dialog param like upload`() {
        // The upload forms (fonts, assets) use `upload` instead of `create`.
        assertThat(urlWithDialogParam(null, "/tenants/acme/assets", "upload"))
            .isEqualTo("/tenants/acme/assets?upload")
        assertThat(urlWithDialogParam("/tenants/acme/assets?catalog=brand", "/tenants/acme/assets", "upload"))
            .isEqualTo("/tenants/acme/assets?catalog=brand&upload")
        assertThat(urlWithDialogParam("/tenants/acme/assets?catalog=brand&upload", "/tenants/acme/assets", "upload"))
            .isEqualTo("/tenants/acme/assets?catalog=brand&upload")
    }
}
