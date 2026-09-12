---
name: contract-bump
description: Upgrade the epistola-contract dependency, or change a REST endpoint implementing it. Use when bumping the contract version, or adding/changing an /api endpoint, DTO or error response.
---

The REST surface is generated from the external **epistola-contract** package. The suite implements
its server interfaces; it does not own the OpenAPI spec. The contract also ships the catalog protocol
and the editor's component registry, which is why a bump spans backend and frontend at once.

## Bumping the contract version — all three, together

| File                          | What to change                                       |
| ----------------------------- | ---------------------------------------------------- |
| `gradle/libs.versions.toml`   | the `epistola-contract` version                      |
| `modules/editor/package.json` | `@epistola.app/epistola-catalog` to the same version |
| `pnpm-lock.yaml`              | regenerate with `pnpm install`                       |

They must match exactly. `./gradlew checkContractVersionAlignment` fails on drift, and CI runs it in
the compile job, so a half-finished bump does not merge.

## Changing an endpoint

- Controllers live in `modules/rest-api/src/main/kotlin/app/epistola/suite/api/v1/`, one per resource
  (`EpistolaThemeApi.kt`, `EpistolaTemplateApi.kt`, …), each implementing a generated interface from
  the contract artifact. The generated types are not in the working tree — they come from
  `app.epistola.contract:server-kotlin-springboot4` in the Gradle cache, under `app.epistola.api.*`.
  If an endpoint does not exist in the generated interface, it needs a contract release first.
- All controllers are mounted at `@RequestMapping("/api")`. There is no `/v1/` path segment.
- Controllers do no business logic: dispatch through `.execute()` / `.query()` and map the result
  with the mappers in `api/v1/shared/`.
- **Errors are RFC 9457 problem details.** Use `ProblemDetails.kt` (`problemDetail()`,
  `toValidationProblemDetail`, `ApiProblemTypes`) and register new mappings in
  `ApiExceptionMappings.kt`. There is no `ApiErrorResponse` type.
- Paginate in SQL (`LIMIT`/`OFFSET` with an ordered query), not in memory.
- The REST API is a GA surface: since 1.0.0 a breaking change to it needs a major release and a
  deprecation path, not a `feat!` in a minor.

## Steps

1. Decide whether the change needs a contract release. If so, release the contract first, then bump
   all three files here.
2. Implement or adjust the controller against the generated interface.
3. Map errors through `ProblemDetails.kt`; add a `ValidationCode` in core if the condition is new.
4. Add an API integration test under `apps/epistola/src/test/kotlin/app/epistola/suite/api/v1/`.
5. Consider the other surfaces — the web UI and MCP may need the same capability.

## Verify

```bash
pnpm install && ./gradlew checkContractVersionAlignment
./gradlew :apps:epistola:integrationTest --tests "*Api*IT*"
```

`ApiExceptionMappingsConsistencyTest` checks the mappings registry against the handler list, and
`UiRestApiSeparationTest` checks that no UI code calls these endpoints.
