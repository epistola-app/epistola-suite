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

/**
 * Editor-side render-timezone default, mirroring the server's
 * `DEFAULT_RENDER_TIMEZONE` (`Europe/Amsterdam`) in the generation module. An
 * offset-bearing date-time is converted to this zone for the inline preview so
 * it matches the PDF renderer; a date-time with no offset is shown as-is
 * ("time is time"). Timezone has no per-tenant resolution yet — when it does,
 * the host forwards the resolved zone through `mountEditor` like `locale`.
 */
export const DEFAULT_RENDER_TIMEZONE = 'Europe/Amsterdam';
