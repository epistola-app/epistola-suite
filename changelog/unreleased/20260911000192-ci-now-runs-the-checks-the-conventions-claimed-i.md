---
type: ci
scopes: [build]
audience: dev
title: CI now runs the checks the conventions claimed it ran.
---

`ktlintCheck` and `checkContractVersionAlignment` only hang off Gradle's `check` task, which CI stopped invoking when `gradle build` was dropped in May, so Kotlin style and a half-finished contract bump could reach `main` with every required check green. Both now ride the existing compile invocation, and `pnpm lint:css` — a script with a config that ran nowhere — joins the frontend checks. All three pass today, so this pins current behaviour rather than fixing a backlog.
