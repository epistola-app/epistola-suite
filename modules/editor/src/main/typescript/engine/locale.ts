/**
 * Editor-side locale defaults.
 *
 * The **actual** application default lives server-side: the Spring property
 * `epistola.i18n.default-locale` in `application.yaml` (bound through
 * `LocaleProperties`). Production hosts always resolve the variant → tenant →
 * app chain via `TenantLocaleResolver` and forward the result through
 * `mountEditor({ locale })`.
 *
 * The constant here only kicks in when the editor runs **without** a host
 * page wiring a locale through — vitest specs, standalone embeds, the
 * dev-time fallback in `templates/editor.html`'s Thymeleaf inline-JS literal,
 * and the belt-and-braces fallbacks downstream of `EditorEngine`. Keeping it
 * in a single tiny module so a future "change the editor's standalone-mode
 * locale" is one edit, not five.
 */
export const DEFAULT_LOCALE = 'en-US';
