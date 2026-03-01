# Rendering Upgrades

This document describes the procedures for upgrading rendering-related dependencies
and modifying rendering behavior in Epistola Suite.

## Overview

Epistola uses a three-layer defense for deterministic PDF rendering:

1. **Versioned Rendering Defaults** — All rendering constants (font sizes, margins, spacing, borders)
   are centralized in `RenderingDefaults`. Each published template version records which defaults
   version was in effect. Old versions keep rendering with their original defaults forever.

2. **Theme Snapshot at Publish Time** — When a template version is published, the full resolved
   theme cascade is frozen as a `ResolvedThemeSnapshot` alongside the version. Published documents
   always render with the theme as it existed at publish time, regardless of subsequent theme edits.

3. **Visual Regression Tests** — Canonical templates are rendered with `RenderingDefaults.V1` and
   compared against stored text baselines. Any change in text content or layout positioning is
   detected automatically.

## Upgrading iText Core

iText Core is the PDF generation library. Version upgrades can change layout algorithms,
font metrics, or internal rendering behavior — even patch releases.

### Procedure

1. **Update the version** in `modules/generation/build.gradle.kts`:
   ```kotlin
   implementation("com.itextpdf:itext-core:X.Y.Z")
   ```

2. **Update the version constant** in `RenderingDefaults.kt`:
   ```kotlin
   private const val ITEXT_VERSION = "X.Y.Z"
   ```

3. **Run the regression tests** — they will likely fail:
   ```bash
   ./gradlew :modules:generation:test --tests "*.PdfRenderingRegressionTest"
   ```

4. **Review the diffs** — compare `.actual.txt` files against `.baseline.txt` files.
   Look for unexpected content changes, missing text, or reordered elements.

5. **If changes are acceptable**, regenerate baselines:
   ```bash
   ./gradlew :modules:generation:test -DupdateBaselines=true
   ```

6. **Run the full test suite** to verify nothing else broke:
   ```bash
   ./gradlew test
   ```

7. **Commit** with a clear message explaining the iText version change and any
   rendering differences observed.

### What the Regression Tests Catch

- Text content changes (missing or duplicated text)
- Layout order changes (text appearing in different spatial order)
- Heading/paragraph spacing changes (extracted text captures line breaks)
- Table cell content ordering changes

### What the Regression Tests Do NOT Catch

- Sub-point font metric shifts (e.g., slightly different kerning)
- Color or opacity changes
- Border rendering differences
- Image positioning changes

For pixel-level visual comparison, a future enhancement could convert PDF pages to
images and compare pixels. The current text extraction approach prioritizes speed and
stability over pixel-perfect detection.

## Changing Rendering Defaults

When you need to change a rendering constant (e.g., increase default heading size,
change table border width):

### Procedure

1. **Never modify an existing version** — `V1` values are frozen forever.

2. **Create a new version** in `RenderingDefaults.kt`:
   ```kotlin
   val V2 = RenderingDefaults(
       version = 2,
       // ... copy from V1 and modify as needed
   )
   ```

3. **Register it**:
   ```kotlin
   private val REGISTRY: Map<Int, RenderingDefaults> = mapOf(
       1 to V1,
       2 to V2,
   )
   ```

4. **Update CURRENT**:
   ```kotlin
   val CURRENT = V2
   ```

5. **Run all tests** — existing baselines test against `V1` so they should still pass.
   Add new baselines for `V2` if needed.

6. **After deployment**, newly published template versions will use `V2`. Previously
   published versions continue to use `V1` (or whatever version they were published with).

## Engine Version Tracking

Every generated PDF embeds an `EngineVersion` metadata field in the PDF info dictionary:
```
epistola-gen-1+itext-9.5.0
```

This allows tracing which rendering engine produced a given PDF. The format is:
```
epistola-gen-<rendering-defaults-version>+itext-<itext-version>
```

You can inspect this with any PDF metadata viewer, or programmatically with iText:
```kotlin
val reader = PdfReader(inputStream)
val document = PdfDocument(reader)
val engineVersion = document.documentInfo.getMoreInfo("EngineVersion")
```

## Architecture Reference

| Component | Location | Purpose |
|-----------|----------|---------|
| `RenderingDefaults` | `modules/generation/.../pdf/RenderingDefaults.kt` | Versioned rendering constants |
| `ResolvedThemeSnapshot` | `modules/epistola-core/.../themes/ResolvedThemeSnapshot.kt` | Frozen theme at publish time |
| `PdfContentExtractor` | `modules/generation/src/test/.../PdfContentExtractor.kt` | Text extraction for regression tests |
| `PdfRenderingRegressionTest` | `modules/generation/src/test/.../PdfRenderingRegressionTest.kt` | Canonical template baselines |
| Baselines | `modules/generation/src/test/resources/baselines/` | Stored expected output |
| Migration V16 | `apps/epistola/src/main/resources/db/migration/V16__*.sql` | Schema for snapshot columns |
