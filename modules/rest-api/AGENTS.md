# modules/rest-api

The REST controllers implementing the **external** `epistola-contract` surface. This module owns no
OpenAPI spec: the contract package does, and the generated server interfaces arrive as an artifact
(`app.epistola.contract:server-kotlin-springboot4`, package `app.epistola.api.*`). They are not in
the working tree, so grep will not find them — if an endpoint is missing from the generated
interface, it needs a contract release first.

## Shape

- One controller per resource (`EpistolaThemeApi.kt`, `EpistolaTemplateApi.kt`, …), each implementing
  its generated interface, all mounted at `@RequestMapping("/api")`. There is no `/v1/` path segment.
- Controllers hold no business logic: dispatch with `.execute()` / `.query()` and map results with
  the mappers in `api/v1/shared/`.
- **Errors are RFC 9457 problem details.** Use `ProblemDetails.kt` — `problemDetail()`,
  `toValidationProblemDetail`, `ApiProblemTypes` — and register new mappings in
  `ApiExceptionMappings.kt`. There is no `ApiErrorResponse` type.
- Paginate in SQL with an ordered `LIMIT`/`OFFSET`, not in memory. Several endpoints still paginate
  in memory via `Pagination.paginate` `(unenforced)`; do not add more.
- This is a **GA surface**: breaking it needs a major release and a deprecation path.

## Bumping the contract

Three files move together, to the exact same released version:

| File                          | What                             |
| ----------------------------- | -------------------------------- |
| `gradle/libs.versions.toml`   | the `epistola-contract` version  |
| `modules/editor/package.json` | `@epistola.app/epistola-catalog` |
| `pnpm-lock.yaml`              | regenerate with `pnpm install`   |

`./gradlew checkContractVersionAlignment` fails on drift and CI runs it, so a half-finished bump
does not merge. Renovate keeps the Maven and npm packages in one group. The `contract-bump` skill
has the full walkthrough.

## Verify

```bash
pnpm install && ./gradlew checkContractVersionAlignment
./gradlew :apps:epistola:integrationTest --tests "*Api*IT*"
```

API integration tests live in `apps/epistola/src/test/kotlin/app/epistola/suite/api/v1/`.
`ApiExceptionMappingsConsistencyTest` checks the mappings registry against the handler list, and
`UiRestApiSeparationTest` checks that no UI code calls these endpoints.
