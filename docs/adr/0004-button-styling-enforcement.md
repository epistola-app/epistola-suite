# ADR 0004: Button Styling Enforcement

- **Status:** Proposed
- **Date:** 2026-06-02
- **Deciders:** Epistola team
- **Tags:** design-system, buttons, enforcement, stylelint

## Context

The design system (`modules/design-system/components.css`) provides a complete
button system with `ep-btn` as the base class and `ep-btn-primary`,
`ep-btn-secondary`, `ep-btn-outline`, `ep-btn-ghost`, `ep-btn-destructive`,
`ep-btn-warning` as variants, plus `ep-btn-sm`, `ep-btn-lg`, `ep-btn-icon`,
`ep-btn-full` for sizing/shape.

But the codebase has drift. Three files in `code-lists/` use the old bare
`btn` class convention — `btn btn-primary`, `btn btn-outline`, `btn btn-sm
btn-ghost` — which are **not defined by the design system**. These buttons
render unstyled. Additionally, the consumers dashboard defines its own
`.consumer-toolbar-btn` in an inline `<style>` block, bypassing the design
system entirely.

Stylelint is already configured with a `selector-disallowed-list` rule
blocking `.btn` / `.btn-*` in CSS files, but this only catches CSS sources,
**not** HTML/Thymeleaf templates where the real drift lives.

We need a plan that both **fixes the existing drift** and **prevents future
drift**, with enforcement that the whole team can rely on.

### Decision drivers

- **Consistency** — every button should look like a button, without the
  developer guessing which class convention applies.
- **Deterministic enforcement** — no silent regressions; CI must catch drift.
- **Low ceremony** — enforcement should not require manual review or
  checklist items.
- **Visible gap** — the template-level `btn` usage gap is invisible to our
  current tooling.

## Considered options

### Option A — Fix templates, rely on stylelint (current state + manual fix)

Fix the template files and trust that stylelint's CSS-level rule prevents
new `.btn` / `.btn-*` CSS selectors from appearing. Accept that template
class-attribute drift is only caught by manual review.

**Pros:**
- Zero new tooling.
- Fixes the immediate visual bug.

**Cons:**
- Does not prevent future template-level drift.
- Leaves the gap unaddressed — the same problem can recur in any new
  template.

### Option B — HTML-aware stylelint via `stylelint-processor-html`

Add an HTML processor so stylelint also checks `<style>` blocks inside
Thymeleaf templates, and extend rules to inspect `class` attributes for
`.btn` / `.btn-*`.

**Pros:**
- Uses existing stylelint pipeline.
- Catches inline `<style>` blocks (consumers dashboard pattern).

**Cons:**
- Does not catch class attributes on HTML elements (Thymeleaf `class="..."`).
- HTML processors are brittle with Thymeleaf attributes (`th:class`,
  `th:classappend`).
- Would miss JS template literals containing class strings (e.g.
  `code-lists/new.html` builds class strings in inline JS at line 168).

### Option C — Dedicated unit test that parses templates

A JUnit test (or Kotlin test) that scans all Thymeleaf templates under
`apps/epistola/src/main/resources/templates/`, extracts every `class="..."`
attribute, and asserts none contain bare `btn` / `btn-` patterns. Runs
as part of `unitTest`.

**Pros:**
- Catches all template-level class usage, including Thymeleaf-specific
  attribute syntax.
- Catches dynamically constructed class strings in inline JS (via a
  secondary regex pass or by limiting scope to server-side templates).
- Fast, deterministic, no Docker needed.
- Extensible to other class naming conventions later (e.g. enforce `ep-`
  prefix for all components).

**Cons:**
- Not a stylelint rule — lives outside the existing CSS pipeline.
- Needs maintenance if the template directory structure changes.
- Inline JS with string-concatenated classes would need heuristic
  coverage (acceptable for an initial pass; most cases are static
  `class="..."` attributes).

### Option D — Hybrid: stylelint for CSS files + dedicated test for templates

Stylelint keeps its `.btn` / `.btn-*` disallowed-list for CSS files. A new
unit test covers HTML templates. Each tool handles what it can see.

**Pros:**
- Both surfaces covered: CSS files by stylelint, HTML templates by test.
- Stylelint can grow additional rules (e.g. `--ep-*` token usage).
- Test can grow additional checks (e.g. required CSS imports per page).
- No single point of failure.

**Cons:**
- Two enforcement mechanisms to maintain.
- Slightly more onboarding surface for new contributors.

## Decision

**Option D — Hybrid enforcement.**

### Why D

- Stylelint already exists and runs in CI; extending it for CSS-level rules
  is already done. Keeping the CSS-level guard in place is zero marginal cost.
- The HTML template parser test is the only reliable way to catch `class="btn
  btn-primary"` in Thymeleaf templates. No processor-based approach can
  reliably extract class attributes from Thymeleaf syntax (`th:class`,
  `th:classappend`, `class="…" th:classappend="…"`), let alone inline JS
  strings.
- The test is simple to write (~50 LOC) and runs in `<100ms` — it will never
  be a CI bottleneck.
- Having both guards means a new `.btn`-based CSS selector is caught by
  stylelint, while a new template using old class names is caught by the test.
  They complement rather than overlap.

### Rejected alternatives

**Option A** (manual-fix-only) was rejected because it doesn't prevent
recurrence — the whole point is to close the gap permanently.

**Option B** (HTML processor) was rejected because it cannot catch the most
common form of drift (class attributes on Thymeleaf elements), and Thymeleaf
attributes would likely confuse the processor.

**Option C** (test-only) was rejected only because stylelint's existing
CSS-level rule costs nothing to keep and provides defense in depth. If we
ever wanted to remove the stylelint rule, Option C alone would be sufficient.

### Near-term implementation plan

#### Phase 1 — Fix existing drift (do first)

1. **Fix `code-lists/` templates** — replace all `btn` / `btn-*` classes
   with `ep-btn` equivalents in:
   - `code-lists/list.html` (3 instances)
   - `code-lists/new.html` (4 instances, including inline JS)
   - `code-lists/detail.html` (4 instances)

   Mapping table for the replacements:

   | Old class | New class |
   |---|---|
   | `btn btn-primary` | `ep-btn ep-btn-primary` |
   | `btn btn-outline` | `ep-btn ep-btn-outline` |
   | `btn btn-destructive` | `ep-btn ep-btn-destructive` |
   | `btn btn-sm btn-outline` | `ep-btn ep-btn-outline ep-btn-sm` |
   | `btn btn-sm btn-ghost` | `ep-btn ep-btn-ghost ep-btn-sm` |
   | `btn btn-sm btn-ghost btn-ghost-destructive` | `ep-btn ep-btn-ghost ep-btn-destructive ep-btn-sm` |

2. **Consumers dashboard** — decide whether `consumer-toolbar-btn` should
   become an `ep-btn-ghost` (migrate) or stay intentionally custom
   (document and add a stylelint allowlist entry).

#### Phase 2 — Write enforcement test

3. **Add `ButtonClassComplianceTest`** (Kotlin, unitTest) that:
   - Recursively scans `apps/epistola/src/main/resources/templates/` for
     `.html` files.
   - Extracts all `class="..."` attribute values via regex.
   - Flags any value matching `\bbtn\b` or `\bbtn-` that is NOT
     `ep-btn` / `ep-btn-*`.
   - Reports file, line, and offending class string.
   - Optionally: also flags known misspellings or deprecated patterns.

4. **Verify the test fails** on the current codebase, then **verify it
   passes** after Phase 1 fixes.

#### Phase 3 — Stylelint augmentation (optional, for future)

5. Consider adding more stylelint rules for the design system:
   - `color-no-hex` / `color-named` — enforce `--ep-*` token usage.
   - `declaration-property-value-disallowed-list` — block hardcoded
     `border-radius` values that should use `--ep-radius-*` tokens.

## Consequences

### Positive

- Button styling becomes deterministic: if it uses `ep-btn`, it's styled;
  otherwise, CI catches it.
- The hybrid approach closes the enforcement gap on both CSS and HTML sides.
- The same test infrastructure can be extended later for other design system
  rules (e.g. enforce `ep-input` instead of bare `<input>` styling).
- Future refactors (e.g. button sizes or color tokens) can be done in the
  design system alone, with confidence that all usages follow the convention.

### Negative / accepted trade-offs

- The test is regex-based on class attributes — it may have false positives
  for class-like strings in non-class contexts (e.g. `data-class="btn"`).
  Mitigation: scope the regex to `class="..."` and `th:class="..."` patterns
  and accept minor false positives that are trivial to silence.
- The consumers dashboard's custom toolbar buttons are an intentional
  one-off — enforcement will flag them unless we add an exemption mechanism.
  We should decide whether to migrate or exempt before writing the test.
- Two enforcement mechanisms are marginally more to maintain, but each is
  simple in isolation.

## Implementation references

- Design system components: `modules/design-system/components.css` (lines
  13–155 for buttons)
- Existing stylelint rule: `stylelint.config.mjs` (line 4–10)
- Drift location 1: `apps/epistola/src/main/resources/templates/code-lists/list.html`
- Drift location 2: `apps/epistola/src/main/resources/templates/code-lists/new.html`
- Drift location 3: `apps/epistola/src/main/resources/templates/code-lists/detail.html`
- Custom inline button: `apps/epistola/src/main/resources/templates/consumers/dashboard.html` (lines 12–23)
