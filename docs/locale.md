<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# Locale

> **TL;DR** — Every render resolves one effective **BCP-47 locale** through a
> three-step chain: **variant attribute → tenant default → app default**. That
> locale drives the `$formatDate` / `$formatLocaleNumber` expression functions
> (month/weekday names, decimal & grouping separators). The **same chain feeds
> both the editor preview and the PDF renderer**, so what you see in the editor
> is what the document produces.

## The locale chain

`TenantLocaleResolver`
(`modules/epistola-core/.../i18n/TenantLocaleResolver.kt`) is the single seam
that resolves the effective locale. Resolution order, first non-blank wins:

| #   | Source                                                         | Where it's set                                                         | Scope                |
| --- | -------------------------------------------------------------- | ---------------------------------------------------------------------- | -------------------- |
| 1   | Variant attribute `system.locale` (preferred) or bare `locale` | The variant's attributes                                               | Per template variant |
| 2   | `tenants.default_locale`                                       | Tenant **Defaults** page (`/tenants/{id}/defaults`)                    | Per tenant           |
| 3   | `epistola.i18n.default-locale`                                 | `application.yaml` (bound via `LocaleProperties`), default **`en-US`** | Whole installation   |

```kotlin
// TenantLocaleResolver — the variant-aware chain
fun resolve(tenant: Tenant, variantAttributes: Map<String, String>): String =
    variantAttributes["system.locale"].nullIfBlank()
        ?: variantAttributes["locale"].nullIfBlank()
        ?: tenant.defaultLocale
        ?: properties.defaultLocale
```

The resolver also exposes `resolveLocale(...)`, which wraps the result in
`Locale.forLanguageTag(...)` for the Java formatters. Unparseable tags yield
`Locale.ROOT`, so the boundary stays string-typed and the conversion is local
to each call site.

> The resolver is deliberately **not** named `LocaleResolver`: the lowercase
> bean name `localeResolver` collides with Spring MVC's own
> `org.springframework.web.servlet.LocaleResolver`, which the framework looks up
> by that exact name.

### Why variants?

Language and brand splits are modelled as **variants**, so locale lives on the
variant. Set `system.locale = "nl-NL"` on a variant and that variant renders
Dutch month names, `1.234,56` grouping, etc., while other variants of the same
template stay on the tenant/app default. The catalog-qualified `system.locale`
key is the recommended form (per `AttributeValidation`); the bare `locale` key
is accepted as a fallback.

### Setting the tenant default

The **Defaults** page (`Settings → Defaults`, `DefaultsHandler` at
`/tenants/{tenantId}/defaults`) lets a tenant override the app default. The
submitted value is validated against the `system.bcp-47` code list — you pick
from a curated list rather than typing a free-form tag, so typos can't reach
the column. A `NULL` column means "use the app default".

## How locale reaches the editor (preview)

```
DocumentTemplateHandler.editor()
  → localeResolver.resolve(tenant, variantAttributes)      // the chain
  → templates/editor.html: window.EPISTOLA_LOCALE = "<resolved>"
  → mountEditor({ locale })  →  EditorEngine.locale
  → evaluateExpression(expr, data, locale)                 // inline chips
  → <ep-expression-dialog>.locale                          // dialog preview + format examples
```

`EditorEngine.locale` threads into every expression evaluation: the inline
expression chips (`ExpressionNodeView`), the inspector's condition/loop fields,
and the stencil parameter-binding dialogs. The expression dialog uses it both
for the **live preview** and for the **format-dropdown example labels** (so a
Dutch session sees `Decimal, grouped (1.234,50)` and `EEEE MMMM d yyyy
(maandag januari 15 2024)`).

`DEFAULT_LOCALE = 'en-US'` in `engine/locale.ts` is a fallback **only** for
contexts without a host page wiring a locale (vitest specs, standalone embeds).
Production always receives the server-resolved value.

## How locale reaches PDF generation

```
DocumentGenerationExecutor / preview handlers
  → localeResolver.resolveLocale(tenant, variant.attributes)   // the chain
  → GenerationService.renderPdf(..., locale)
  → DirectPdfRenderer.render(..., locale)
  → CompositeExpressionEvaluator.forLocale(locale)             // render-scoped
  → JsonataEvaluator(locale, timeZone)
```

`forLocale(locale, timeZone)` builds a render-scoped evaluator: it re-creates
only the `JsonataEvaluator` (the GraalJS/JavaScript engine is reused) and
returns `this` unchanged for the default English / `Europe/Amsterdam` path, so
the untouched case allocates nothing.

| Default           | Value              | Defined in                                 |
| ----------------- | ------------------ | ------------------------------------------ |
| Renderer locale   | `Locale.ENGLISH`   | `SystemParameters.DEFAULT_LOCALE`          |
| Renderer timezone | `Europe/Amsterdam` | `SystemParameters.DEFAULT_RENDER_TIMEZONE` |
| Editor locale     | `'en-US'`          | `engine/locale.ts` `DEFAULT_LOCALE`        |

## The locale-aware expression functions

Two custom JSONata functions exist on **both** sides — editor
(`engine/resolve-expression.ts`) and renderer (`JsonataEvaluator.kt`) — and both
take the resolved locale. The picture/pattern supplies the _shape_; the locale
supplies the _characters_.

### `$formatDate(value, pattern)`

Formats an ISO date/datetime string. Accepts plain dates (`2024-01-15`), local
datetimes (`2024-01-15T14:30:00`), UTC (`…Z`) and offset (`…+02:00`) datetimes.

| Token                               | Meaning                     | Locale-dependent?          |
| ----------------------------------- | --------------------------- | -------------------------- |
| `EEEE` / `EEE`                      | Weekday name (full / short) | **Yes**                    |
| `MMMM` / `MMM`                      | Month name (full / short)   | **Yes**                    |
| `yyyy` `MM` `dd` `d` `HH` `mm` `ss` | Numeric year/month/day/time | No — same in every culture |

Numeric tokens are locale-agnostic **by design** so the day-month order is
whatever the picture says, not the culture's — `dd-MM-yyyy` is always
`15-01-2024`. Only name tokens localize: `EEEE MMMM d yyyy` →
`Monday January 15 2024` (en-US) / `maandag januari 15 2024` (nl-NL).

> The **renderer** side accepts the full Java `DateTimeFormatter` pattern spec;
> the **editor** preview implements the token subset in the table above. A
> pattern using a token outside that subset renders correctly in the PDF but
> may show the literal token in the editor preview.

### `$formatLocaleNumber(value, picture)`

Locale-aware number formatting with a `DecimalFormat`-style picture.
Deliberately **separate** from JSONata's built-in `$formatNumber` (which is
locale-agnostic per the XPath 3 spec) — opt in to localized output with
`$formatLocaleNumber`.

| Picture char | Meaning                                             |
| ------------ | --------------------------------------------------- |
| `0` / `#`    | Digit (zero-padded / optional)                      |
| `,`          | Grouping separator → locale's grouping char         |
| `.`          | Decimal separator → locale's decimal char           |
| `;`          | Separator between positive and negative subpatterns |
| `%`          | Percent (×100, appends locale's percent sign)       |
| `‰`          | Per-mille (×1000)                                   |
| other        | Literal, emitted verbatim around the digit block    |

```
$formatLocaleNumber(1234.56, '#,##0.00')   → "1.234,56"  (nl-NL)  /  "1,234.56"  (en-US)
$formatLocaleNumber(0.21,    '0.0%')        → "21,0%"     (nl-NL)  /  "21.0%"     (en-US)
```

**Rounding is `HALF_EVEN`** (banker's rounding) on both sides — that's Java
`DecimalFormat`'s default, and the editor pins `Intl.NumberFormat`'s
`roundingMode: 'halfEven'` to match it. So `8.5` with `'#,##0'` renders `8` in
both the preview and the PDF (not `9`). Scientific notation and XSLT-only
options are out of scope — use the W3C `$formatNumber` for those.

## Editor ↔ PDF parity

The whole point of routing one resolved locale through both surfaces is that
the inline preview matches the generated document. Setting `system.locale =
"nl-NL"` on a variant yields **"04 april 2026"** and **`1.234,56`** in both the
editor chip and the PDF.

The one intentional difference is **time-of-day**: the editor previews
datetimes in **UTC**, while the renderer uses the configured render timezone
(`Europe/Amsterdam` by default). Date-only patterns are unaffected.

## See also

- [`docs/generation.md`](generation.md) — PDF render pipeline
- [`docs/attributes.md`](attributes.md) — variant attributes (`system.*`)
- [`docs/code-lists.md`](code-lists.md) — the `system.bcp-47` code list backing locale validation
- [`docs/fonts.md`](fonts.md) — the other cross-surface render capability
