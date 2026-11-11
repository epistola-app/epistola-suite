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
- **Only the contract's problem types.** A mapping answers with a type the contract registers
  (`x-problem-types`, generated as `KnownProblemSlugs`); anything else is the generic type of its
  status — a missing theme is `NOT_FOUND`, a theme in use `CONFLICT`, with members naming it.
  `ProblemTypeRegistryTest` enforces it. The UI's finer types (`catalog-version-released`, a
  validation code as the slug) are `UiProblemTypes`, never answered on `/api`.
- **A problem member names a key `…Key`**, as contract 2.0 bodies do; `…Id` only for a real id
  (`requestId`, `documentId`, `releaseId`, `versionId`). `ProblemMemberNamesTest` enforces it.
- **Every contract operation is answered.** One the suite does not support throws
  `ApiOperationNotImplementedException` (501), never an unmapped route (404);
  `ContractOperationCoverageTest` fails on a generated interface no controller implements.
- Paginate in SQL with an ordered `LIMIT`/`OFFSET`, never by slicing a fetched list: a `…Page` query
  built on `common/Paging.kt` answers the page and its total. `RestPagingInDatabaseTest` is the guard.
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
