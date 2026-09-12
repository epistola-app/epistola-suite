---
name: tests
description: Write a unit, integration, handler or Playwright UI test for the suite, and pick the right Gradle task. Use when adding or fixing tests, or when a test needs domain state set up.
---

[`docs/testing.md`](../../../docs/testing.md) is the guide and stays current — read it. This is the
short version plus the rules that fail the build.

## Which kind

| Testing                     | Kind               | Base class            | Task                                     |
| --------------------------- | ------------------ | --------------------- | ---------------------------------------- |
| Pure logic, no Spring       | Unit               | none                  | `:module:unitTest`                       |
| Commands, queries, DB       | Module integration | `IntegrationTestBase` | `:modules:epistola-core:integrationTest` |
| Routes, handlers, fragments | App integration    | `BaseIntegrationTest` | `:apps:epistola:integrationTest`         |
| Real browser behaviour      | UI                 | `BasePlaywrightTest`  | `:apps:epistola:uiTest`                  |

Prefer a `*HandlerHtmxTest` over a browser test when the assertion is about the server's response —
it is faster and deterministic. Reach for Playwright only when the behaviour lives in the browser.

## Rules that fail the build

- **Seed state through commands or the fixture DSL, never raw SQL.** `fixture {}`, `scenario {}`,
  `createTenant()` and dispatching the real command track schema and validation changes; an `INSERT`
  rots silently. SQL is justified only when no command can produce the state (an infrastructure
  table, or a specific historical timestamp) — say why in a comment.
- **UI tests**: navigate with `gotoAndReady(path)` (never bare `page.navigate`), await swaps with
  `page.htmxSettle()`, open dialogs with `page.openDialogByTrigger(...)`, and assert web-first.
  No `waitForTimeout`, no `:visible` pseudo, no blind `waitForSelector("…[open]")`, no
  `System.err.println` dumps. `UiTestHygieneTest` enforces this.
- **Time**: use `EpistolaClockExtension` / `testClock` / `EpistolaClock.withClock`, not wall-clock
  sleeps or JVM `now()`.
- A CSP violation in the browser console fails a UI test.

## Exemplars — copy these, don't copy this file

| For                  | Read                                                                                               |
| -------------------- | -------------------------------------------------------------------------------------------------- |
| Module integration   | `modules/epistola-core/src/test/kotlin/app/epistola/suite/catalog/commands/ImportTemplatesTest.kt` |
| Handler contract     | `apps/epistola/src/test/kotlin/app/epistola/suite/handlers/StencilHandlerHtmxTest.kt`              |
| Playwright UI        | `apps/epistola/src/test/kotlin/app/epistola/suite/ui/CodeListUiTest.kt`                            |
| The DSLs and helpers | `modules/testing/src/main/kotlin/app/epistola/suite/testing/`                                      |

## Verify

```bash
# Scope to the module you touched; a filter that matches nothing there is no longer an error.
./gradlew :modules:epistola-core:integrationTest --tests "*YourTest*"
./gradlew :apps:epistola:uiTest --tests "*YourUiTest*"
```

Integration and UI tests need Docker (Testcontainers). App-level tasks need `pnpm build` once first,
because the app packages the editor bundle. Before a PR: `./gradlew test uiTest`.
