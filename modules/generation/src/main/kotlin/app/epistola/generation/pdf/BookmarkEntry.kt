package app.epistola.generation.pdf

/**
 * A heading collected during rendering, used to build the PDF document
 * outline / bookmarks for accessibility (WCAG PDF2).
 *
 * @property level heading level (1-6)
 * @property title plain-text heading title shown in the outline
 * @property destinationName named destination anchored at the heading
 *   (registered during layout via `setDestination`), so the bookmark
 *   navigates to the heading's actual page.
 */
data class BookmarkEntry(
    val level: Int,
    val title: String,
    val destinationName: String,
)
