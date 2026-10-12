// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.ImportAsset
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationException
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SvgContentPolicyTest {
    private val ns = """xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink""""
    private val pngDataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private val refused = mapOf(
        "doctype" to """<?xml version="1.0"?><!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd"><svg $ns/>""",
        "internal entity" to """<!DOCTYPE svg [<!ENTITY x "y">]><svg $ns><text>&x;</text></svg>""",
        "external entity" to """<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg $ns><text>&x;</text></svg>""",
        "script" to """<svg $ns><script>alert(1)</script></svg>""",
        "namespaced script" to """<svg $ns><h:script xmlns:h="http://www.w3.org/1999/xhtml">alert(1)</h:script></svg>""",
        "foreignObject" to """<svg $ns><foreignObject><div xmlns="http://www.w3.org/1999/xhtml">x</div></foreignObject></svg>""",
        "onload" to """<svg $ns onload="alert(1)"/>""",
        "onclick uppercase" to """<svg $ns><rect ONCLICK="alert(1)"/></svg>""",
        "image href http" to """<svg $ns><image href="http://example.com/a.png"/></svg>""",
        "image xlink:href http" to """<svg $ns><image xlink:href="https://example.com/a.png"/></svg>""",
        "image href file" to """<svg $ns><image href="file:///etc/hosts"/></svg>""",
        "image href absolute path" to """<svg $ns><image href="/tmp/a.png"/></svg>""",
        "image href relative" to """<svg $ns><image href="a.png"/></svg>""",
        "use href external" to """<svg $ns><use href="http://example.com/s.svg#a"/></svg>""",
        "a href javascript" to """<svg $ns><a href="javascript:alert(1)"><text>x</text></a></svg>""",
        "style @import" to """<svg $ns><style>@import url(http://example.com/a.css);</style></svg>""",
        "style @import string" to """<svg $ns><style>@import "http://example.com/a.css";</style></svg>""",
        "style @import split by comment" to """<svg $ns><style>@/**/import url(a.css);</style></svg>""",
        "style @import as char reference" to """<svg $ns><style>&#64;import url(a.css);</style></svg>""",
        "style @import in CDATA" to """<svg $ns><style><![CDATA[@import url(a.css);]]></style></svg>""",
        "style escaped" to """<svg $ns><style>@\69mport url(a.css);</style></svg>""",
        "style font-face url" to """<svg $ns><style>@font-face{font-family:X;src:url(http://example.com/f.ttf)}</style></svg>""",
        "style attribute url" to """<svg $ns><rect style="fill:url('http://example.com/p.svg#a')"/></svg>""",
        "fill attribute url" to """<svg $ns><rect fill="url(http://example.com/p.svg#a)"/></svg>""",
        "xml-stylesheet" to """<?xml-stylesheet href="http://example.com/a.css"?><svg $ns/>""",
        "not xml" to "not an svg",
    )

    private val accepted = mapOf(
        "plain shapes" to """<svg $ns width="10" height="10"><rect width="10" height="10" fill="red"/></svg>""",
        "xml declaration and comment" to """<?xml version="1.0" encoding="UTF-8"?><!-- logo --><svg $ns/>""",
        "fragment references" to
            """<svg $ns><defs><linearGradient id="g"/><path id="p" d="M0 0"/></defs><use href="#p"/><use xlink:href="#p"/><rect fill="url(#g)" style="fill: url( '#g' )"/></svg>""",
        "data image" to """<svg $ns><image href="$pngDataUri"/><image xlink:href="$pngDataUri"/></svg>""",
        "inline style" to """<svg $ns><style>/* brand */ .a { fill: #f00; font-family: "Helvetica"; } text { font-weight: bold !important; }</style><text class="a">x</text></svg>""",
        "text and title" to """<svg $ns><title>Logo</title><desc>On &amp; off</desc><text x="1" y="9">Hello</text></svg>""",
        "editor metadata" to
            """<svg $ns xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" inkscape:export-filename="C:\exports\logo.png"><rect width="1" height="1"/></svg>""",
    )

    @TestFactory
    fun `refuses SVGs that reach outside themselves or run code`(): List<DynamicTest> = refused.map { (name, svg) ->
        DynamicTest.dynamicTest(name) {
            assertNotNull(SvgContentPolicy.violation(svg.toByteArray()), "expected '$name' to be refused")
        }
    }

    @TestFactory
    fun `accepts self-contained SVGs`(): List<DynamicTest> = accepted.map { (name, svg) ->
        DynamicTest.dynamicTest(name) {
            assertNull(SvgContentPolicy.violation(svg.toByteArray()), "expected '$name' to be accepted")
        }
    }

    @TestFactory
    fun `every bundled catalog SVG passes`(): List<DynamicTest> {
        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val svgs = listOf("apps", "modules").map(repoRoot::resolve).flatMap { base ->
            Files.walk(base).use { paths ->
                paths.filter { "/catalogs/" in it.toString() && "/build/" !in it.toString() && it.extension == "svg" }.toList()
            }
        }
        check(svgs.isNotEmpty()) { "no bundled catalog SVGs found — the scan would pass vacuously" }
        return svgs.map { path ->
            DynamicTest.dynamicTest(repoRoot.relativize(path).toString()) {
                assertNull(SvgContentPolicy.violation(Files.readAllBytes(path)))
            }
        }
    }

    @Test
    fun `UploadAsset refuses an unsafe SVG as a file validation error`() {
        val error = assertFailsWith<ValidationException> {
            UploadAsset(
                tenantId = TenantKey.of("acme"),
                name = "logo.svg",
                mediaType = AssetMediaType.SVG,
                content = refused.getValue("image href http").toByteArray(),
                width = null,
                height = null,
                catalogKey = CatalogKey.of("default"),
            )
        }
        assertEquals("file", error.field)
    }

    @Test
    fun `ImportAsset refuses an unsafe SVG`() {
        assertFailsWith<ValidationException> {
            ImportAsset(
                tenantId = TenantId(TenantKey.of("acme")),
                catalogKey = CatalogKey.of("default"),
                id = AssetKey.of("logo"),
                name = "logo.svg",
                mediaType = AssetMediaType.SVG,
                content = refused.getValue("script").toByteArray(),
            )
        }
    }

    @Test
    fun `non-SVG media types are not parsed`() {
        SvgContentPolicy.requireSafe(AssetMediaType.PNG, "<svg onload='x'/>".toByteArray())
    }
}
