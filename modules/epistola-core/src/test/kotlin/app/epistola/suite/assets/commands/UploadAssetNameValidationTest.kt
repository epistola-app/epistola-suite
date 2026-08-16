// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Defense-in-depth server-side guard for issue #644 (stored XSS in the editor's
 * asset picker dialog): the raw uploaded filename becomes `UploadAsset.name`, which
 * the catalog/API surfaces back to any viewer verbatim. The client fix (rendering
 * via DOM properties/`textContent` instead of `innerHTML`) is the primary fix; this
 * command-level check rejects the payload shape at the source so it never reaches
 * storage, independent of how a future render path might treat it.
 *
 * Pure unit test — no Spring, no DB, same shape as `NameLengthValidationTest`.
 */
class UploadAssetNameValidationTest {

    private val tenantKey = TenantKey("testtenant")
    private val catalogKey = CatalogKey.DEFAULT
    private val mediaType = AssetMediaType.fromMimeType("image/png")

    private fun upload(name: String) = UploadAsset(
        tenantId = tenantKey,
        name = name,
        mediaType = mediaType,
        content = ByteArray(1),
        width = null,
        height = null,
        catalogKey = catalogKey,
    )

    @Test
    fun `rejects a name containing markup`() {
        val thrown = assertFailsWith<ValidationException> {
            upload("\"><img src=x onerror=window.__pwned=1>.png")
        }
        if (thrown.field != "name") {
            fail("Expected the 'name' field to be rejected, but '${thrown.field}' was rejected instead: ${thrown.message}")
        }
    }

    @Test
    fun `rejects a name containing a control character`() {
        // Built via Char(0)/Char(1) rather than a raw literal, to keep control bytes
        // out of the source file itself.
        val nulByte = Char(0)
        val withNul = assertFailsWith<ValidationException> { upload("evil$nulByte.png") }
        if (withNul.field != "name") fail("Expected 'name' rejection for a NUL byte, got '${withNul.field}': ${withNul.message}")

        val withNewline = assertFailsWith<ValidationException> { upload("evil\n.png") }
        if (withNewline.field != "name") fail("Expected 'name' rejection for a newline, got '${withNewline.field}': ${withNewline.message}")
    }

    @Test
    fun `accepts an ordinary filename`() {
        val asset = upload("logo (v2).png")
        assertEquals("logo (v2).png", asset.name)
    }
}
