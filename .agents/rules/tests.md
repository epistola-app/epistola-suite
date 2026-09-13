---
paths:
  - "**/src/test/**"
  - "**/*.test.ts"
---

# Tests

[`docs/testing.md`](../../docs/testing.md) is the guide and stays current. This card is the part
that fails the build.

| Testing                     | Base class            | Task                                     |
| --------------------------- | --------------------- | ---------------------------------------- |
| Pure logic, no Spring       | none                  | `:module:unitTest`                       |
| Commands, queries, DB       | `IntegrationTestBase` | `:modules:epistola-core:integrationTest` |
| Routes, handlers, fragments | `BaseIntegrationTest` | `:apps:epistola:integrationTest`         |
| Real browser behaviour      | `BasePlaywrightTest`  | `:apps:epistola:uiTest`                  |

Prefer a `*HandlerHtmxTest` over a browser test when the assertion is about the server's response:
faster and deterministic. Reach for Playwright only when the behaviour lives in the browser.

## Rules

- **Seed state through commands or the fixture DSL, never raw SQL.** `fixture {}`, `scenario {}`,
  `createTenant()` and dispatching the real command track schema and validation changes; an `INSERT`
  rots silently and then fails some unrelated change later. Compose commands to reach a lifecycle
  state (`CreateApiKey` then `RevokeApiKey`), and use the id a command returns rather than forcing
  one in. SQL is justified only when no command can produce the state — an infrastructure table with
  no command, or a specific historical timestamp the read path asserts against, since commands write
  `NOW()`. Add a one-line comment saying why, so it does not read as the default.
- **UI tests** navigate with `gotoAndReady(path)`, await swaps with `page.htmxSettle()`, open dialogs
  with `page.openDialogByTrigger(...)`, and assert web-first. Banned and build-failing via
  `UiTestHygieneTest`: `waitForTimeout`, the `:visible` pseudo, blind
  `waitForSelector("…[open]")`, bare `page.navigate`, and forensic `System.err.println` dumps. The
  philosophy is deterministic-only — no retry masking.
- **Time** comes from `EpistolaClockExtension` / `testClock` / `EpistolaClock.withClock`, never a
  wall-clock sleep or a JVM `now()`.
- Integration and UI tests need Docker (Testcontainers). App-level tasks need `pnpm build` once
  first.

## Verify

```bash
./gradlew :modules:epistola-core:integrationTest --tests "*YourTest*"
./gradlew :apps:epistola:unitTest --tests "*UiTestHygieneTest"
```

A `--tests` filter matching nothing in a module is no longer an error, so scoping to the module you
touched is safe.
