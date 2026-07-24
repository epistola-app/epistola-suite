package app.epistola.generation.pdf

/**
 * Built-in template node types that the PDF renderer can dispatch.
 */
object SupportedNodeTypes {
    val all: Set<String> = setOf(
        "root",
        "text",
        "richTextVariable",
        "container",
        StencilNodeKeys.NODE_TYPE,
        PlaceholderNodeKeys.NODE_TYPE,
        "columns",
        "table",
        "conditional",
        "loop",
        "datalist",
        "datatable",
        "datatable-column",
        "image",
        "qrcode",
        "separator",
        "pagebreak",
        "pageheader",
        "pagefooter",
        "addressblock",
    )
}
