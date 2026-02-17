---
name: contract-change
description: Make changes to REST API contracts and update the implementing controllers. Use when adding, modifying, or removing API endpoints, DTOs, or when the external epistola-contract needs updating.
---

Guide through making REST API contract changes.

**Input**: Description of the API change (new endpoint, modified DTO, etc.).

## Decision Points

Ask the user:
- Is this a new endpoint or a modification to an existing one?
- Does the OpenAPI spec (in the separate `epistola-contract` repo) need updating?
- Or can the change be handled within the existing generated interfaces?

## Architecture

The REST API follows a **code-generated contract-first** approach:

- **Contract artifact**: `app.epistola.contract:server-kotlin-springboot4` (version in `gradle/libs.versions.toml` as `epistola-contract`)
- **OpenAPI spec**: Lives in a **separate `epistola-contract` repository** (not in this repo)
- **Generated interfaces**: Controllers implement generated Spring interfaces (e.g., `TemplatesApi`, `VariantsApi`)
- **Controllers**: `modules/rest-api/src/main/kotlin/app/epistola/suite/api/v1/`
- **DTO mappers**: `modules/rest-api/src/main/kotlin/app/epistola/suite/api/v1/shared/`
- **Exception handler**: `modules/rest-api/src/main/kotlin/app/epistola/suite/api/v1/ApiExceptionHandler.kt`

**If the contract artifact needs updating**: The OpenAPI spec change must be made in the separate `epistola-contract` repository first. After updating the spec, publish a new version, then bump `epistola-contract` in `gradle/libs.versions.toml`.

## Controller Pattern

**Reference**: Existing controllers in `modules/rest-api/src/main/kotlin/app/epistola/suite/api/v1/`

```kotlin
@RestController
class EpistolaXxxApi(
    private val objectMapper: ObjectMapper,
) : GeneratedApiInterface {

    override fun someEndpoint(tenantId: String, ...): ResponseEntity<SomeDto> {
        // 1. Convert string IDs to domain types
        val id = SomeId.of(tenantId)

        // 2. Execute command or query
        val result = SomeCommand(...).execute()

        // 3. Map to DTO and return
        return ResponseEntity.ok(result.toDto())
    }
}
```

**Conventions**:
- Controllers only do: ID conversion, command/query dispatch, DTO mapping
- No business logic in controllers
- Use `.execute()` for commands, `.query()` for queries
- Media type: `application/vnd.epistola.v1+json`
- Path: `/api/v1/*` or `/v1/*`

**Response status codes**:
- `200 OK` — successful GET/PATCH/PUT
- `201 CREATED` — successful POST creating a resource
- `202 ACCEPTED` — async operations (e.g., document generation)
- `204 NO_CONTENT` — successful DELETE

## DTO Mappers

**Reference files** (all in `modules/rest-api/.../api/v1/shared/`):

| File | Responsibility |
|------|---------------|
| `DtoMappers.kt` | Core entity mappers: Tenant, Environment, DocumentTemplate, TemplateVariant, TemplateVersion |
| `ThemeDtoMappers.kt` | Theme mappers: Theme, DocumentStyles, PageSettings, Margins, BlockStylePresets |
| `DocumentDtoMappers.kt` | Document generation mappers: DocumentMetadata, GenerationRequest, GenerationJobResult |

### Domain → DTO (outbound)

```kotlin
internal fun DomainModel.toDto(): GeneratedDto = GeneratedDto(
    id = this.id.value,
    name = this.name,
    createdAt = this.createdAt,
)
```

### DTO → Domain (inbound / bidirectional)

**Reference**: `ThemeDtoMappers.kt` — contains bidirectional mapping for complex nested types:

```kotlin
internal fun DocumentStylesDto.toDomain(): DocumentStyles = DocumentStyles(
    fontFamily = this.fontFamily,
    fontSize = this.fontSize,
    // ...
)
```

Use bidirectional mappers when the API accepts complex objects in request bodies.

## Exception Handling

**Reference**: `ApiExceptionHandler.kt` — `modules/rest-api/.../api/v1/ApiExceptionHandler.kt`

Scoped to `@RestControllerAdvice(basePackages = ["app.epistola.suite.api.v1"])` — only handles exceptions from REST API controllers, NOT from UI handlers.

Returns structured `ApiErrorResponse` with `code`, `message`, and optional `details`.

**Two response patterns**:
1. Simple error: `ApiErrorResponse(code = "CONFLICT", message = "...")`
2. Validation error with details: `ApiErrorResponse(code = "VALIDATION_ERROR", message = "...", details = mapOf(...))`

Common mappings:

| Exception | HTTP Status |
|-----------|-------------|
| `ValidationException` | 400 BAD_REQUEST |
| `ThemeInUseException` | 409 CONFLICT |
| `DefaultVariantDeletionException` | 409 CONFLICT |
| `VersionStillActiveException` | 409 CONFLICT |
| `DataModelValidationException` | 422 UNPROCESSABLE_ENTITY |
| `NoMatchingVariantException` | 404 NOT_FOUND |

## Testing

**Note**: There are currently **no dedicated REST API integration tests**. The architecture test `UiRestApiSeparationTest` verifies the UI/API boundary.

When adding new endpoints, consider adding controller-level tests if the mapping logic is non-trivial.

```bash
./gradlew test --tests UiRestApiSeparationTest   # Verify UI/API separation
./gradlew ktlintFormat
./gradlew integrationTest
```

## Checklist

- [ ] Controller in `modules/rest-api/.../api/v1/`
- [ ] DTO mappers in `modules/rest-api/.../api/v1/shared/`
- [ ] Exception handler entries if new error cases
- [ ] `./gradlew test --tests UiRestApiSeparationTest`
- [ ] `./gradlew ktlintFormat`
- [ ] `./gradlew integrationTest`

## Gotchas

- REST API controllers are in `modules/rest-api/`, NOT in `apps/epistola/`
- UI handlers are in `apps/epistola/` and are **completely separate** from REST controllers
- **UI code (Thymeleaf/JavaScript) MUST NEVER call REST API endpoints** — the REST API is only for external systems
- Jackson 3 (`tools.jackson.*`) is used, not `com.fasterxml.jackson`
- If you need a UI endpoint, use the `htmx-form` skill instead
