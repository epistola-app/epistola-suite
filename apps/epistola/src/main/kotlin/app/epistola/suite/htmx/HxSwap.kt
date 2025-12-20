package app.epistola.suite.htmx

/**
 * HTMX swap modes for controlling how content is inserted into the DOM.
 *
 * @see <a href="https://htmx.org/attributes/hx-swap/">HTMX hx-swap Documentation</a>
 */
enum class HxSwap(val value: String) {
    /** Replace the inner HTML of the target element (default) */
    INNER_HTML("innerHTML"),

    /** Replace the entire target element with the response */
    OUTER_HTML("outerHTML"),

    /** Insert the response before the target element */
    BEFORE_BEGIN("beforebegin"),

    /** Insert the response before the first child of the target element */
    AFTER_BEGIN("afterbegin"),

    /** Insert the response after the last child of the target element */
    BEFORE_END("beforeend"),

    /** Insert the response after the target element */
    AFTER_END("afterend"),

    /** Delete the target element regardless of the response */
    DELETE("delete"),

    /** Does not append content from response (out of band items will still be processed) */
    NONE("none"),
}
