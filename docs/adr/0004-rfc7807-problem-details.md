# ADR 0004: RFC 7807 Problem Details Implementation

- **Status:** Accepted
- **Date:** 2026-05-27
- **Deciders:** Epistola team
- **Tags:** api, errors, rfc9457, rfc7807, serialization

## Amendment (2026-06-04) — RFC 9457 + contract type changes

The `epistola-contract` now cites **RFC 9457** (which obsoletes RFC 7807 with no
change to the wire format) and changed its generated error types. This ADR's
chosen design (Option B) is unaffected — references below to "RFC 7807" should be
read as RFC 9457. Concretely:

- The contract maps `ProblemDetail` / `ValidationProblemDetail` to Spring's native
  `org.springframework.http.ProblemDetail` (no generated DTO). This app already
  used the native type as its internal builder, so no change was needed there.
- The deprecated generated types `FieldError` and `ValidationErrorResponse` were
  removed. Field-level errors now use the contract's `ValidationError` (same
  `field` / `message` / `rejectedValue` shape). The editor draft-save route's
  pre-Problem-Details envelope (`code` / `message` / `errors[]`) is now a local
  `DraftValidationErrorResponse` instead of the contract type — wire shape
  unchanged.

**Note on Constraint 1's reasoning (revisited 2026-06-04).** The premise that
`ProblemDetailJacksonMixin` "does not register on the Jackson 3 `ObjectMapper`
because it uses `com.fasterxml` annotations" is imprecise: Jackson 3 keeps its
_annotations_ in `com.fasterxml.jackson.annotation` (only `core`/`databind` moved
to `tools.jackson`), and the mixin flattens `getProperties()` correctly when added
to a mapper in isolation. **However**, an attempt to drop `toProblemMap()` and rely
on the mixin via the application's primary/injected `ObjectMapper` (both Boot's
auto-configured `JsonProblemDetailsConfiguration` and an explicit
`JsonMapperBuilderCustomizer`) **failed** in `CollectEndpointSmokeIT` on
`spring-boot 4.0.6` — `code`/extension members stayed nested under `properties`.
So the mixin is not effectively wired onto the mapper these paths use, and the
`Map`-based flattening (Option B) is **retained** as the reliable approach. The
decision stands; only the stated mechanism was inaccurate.

## Context

The REST API under `/api/**` needs machine-readable error responses aligned with
[RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) (`application/problem+json`).
The `epistola-contract` v0.7.0 specifies the wire format: top-level `type`, `title`,
`status`, `detail`, `instance`, plus the `code` extension field and
domain-specific extensions.

Spring Framework 7 includes built-in support for RFC 7807 via
`ResponseEntityExceptionHandler` + `ProblemDetail`. However, two constraints
shaped our implementation choice:

1. **Jackson 3 (tools.jackson).** The project migrated to Jackson 3, whose
   annotations live under `tools.jackson.annotation.*`. Spring's
   `ProblemDetailJacksonMixin` uses `com.fasterxml.jackson.annotation.*`
   (Jackson 2) and therefore does not register correctly on the Jackson 3
   `ObjectMapper`. Without this mixin, `ProblemDetail.getProperties()` is
   serialized as a nested `"properties"` object rather than flattened to
   top-level keys.

2. **Pre-dispatch vs post-dispatch exceptions.** `@RestControllerAdvice` +
   `@ExceptionHandler` only fires once a controller method has been matched.
   Framework exceptions thrown before dispatch (`HttpRequestMethodNotSupportedException`,
   `HttpMediaTypeNotSupportedException`, `HttpMediaTypeNotAcceptableException`,
   `NoResourceFoundException`, `NoHandlerFoundException`) escape the advice and
   fall through to Spring's `DefaultHandlerExceptionResolver`, which produces
   plain HTML/text responses. A `HandlerExceptionResolver` is required to
   intercept these and produce `application/problem+json`.

### Decision drivers

- **Single source of truth for error taxonomy.** Problem type URIs, titles,
  codes, and extension fields must be defined in one place (`ApiProblemTypes`).
- **No duplication between resolver and @ExceptionHandler methods.** Both must
  share the same response-building path.
- **Jackson 3 compatibility.** The wire format must remain a flat `Map<String, Any?>`
  since the `ProblemDetailJacksonMixin` is unavailable.
- **Pre-release pragmatism.** Epistola is not yet in production; a clean design
  is preferred over backward compatibility with the pre-RFC error format.

## Considered options

### Option A — Spring-native `ResponseEntityExceptionHandler` + `ProblemDetail` + Jackson 3 mixin

Extend `ResponseEntityExceptionHandler`, override `handleExceptionInternal`
to enrich `ProblemDetail` with Epistola-specific extensions, and register a
custom `tools.jackson` mixin to flatten `getProperties()`.

#### A — Pros

- Minimal code: ~50 domain `@ExceptionHandler` methods collapse to near-zero via
  `ErrorResponse` / `ErrorResponseException`.
- Single centralized enrichment point (`handleExceptionInternal`).
- `ApiFrameworkExceptionResolver` can be dropped entirely for `/api/**`
  (the superclass handles pre-dispatch exceptions automatically).

#### A — Cons

- **Superclass `@ExceptionHandler` methods are `protected final` and defined on
  `ResponseEntityExceptionHandler` at the `Exception` catch-all level.** In
  Spring Framework 7.0.6, `handleException(Exception, WebRequest)` is the only
  `@ExceptionHandler` method; specific handlers are dispatched internally. When
  our subclass adds explicit `@ExceptionHandler` methods for framework
  exception subtypes (e.g. `HttpRequestMethodNotSupportedException`), Spring's
  `ExceptionHandlerMethodResolver` throws `IllegalStateException` — "Ambiguous
  @ExceptionHandler method mapped for [type]" — because both the superclass
  catch-all and our specific handler match the same exception. Removing our
  explicit handlers means losing custom extension properties (e.g. `method`,
  `supportedMethods` for 405).
- **Pre-dispatch exceptions are NOT handled by `ResponseEntityExceptionHandler`.**
  Despite documentation suggesting otherwise, in Spring 7.0.6 the superclass
  relies on `@ExceptionHandler` resolution, which does not fire pre-dispatch.
  The `DefaultHandlerExceptionResolver` still handles these and produces
  non-JSON responses.
- Requires registering and maintaining a Jackson 3 mixin for `ProblemDetail`.

### Option B — `ProblemDetail` internal, `Map` wire format (chosen)

Use `ProblemDetail` as an intermediate builder object, then convert to
`Map<String, Any?>` for wire serialization. Explicit `@ExceptionHandler` methods
remain for both domain and post-dispatch framework exceptions. A pre-dispatch
`HandlerExceptionResolver` covers pre-dispatch framework exceptions. Both paths
delegate to the shared `problemDetail()` builder and `toProblemMap()` converter.

#### B — Pros

- **No Jackson 3 mixin dependency.** `ProblemDetail` is used as a builder;
  `toProblemMap()` produces the same `LinkedHashMap` structure the Jackson 3
  `ObjectMapper` already serializes correctly.
- **Pre-dispatch gap is covered.** `ApiHandlerExceptionResolver` produces the
  same `application/problem+json` format as `ApiExceptionHandler`.
- **No duplication.** Both paths call `problemDetail()` → `toProblemMap()`
  (resolver) or `problemDetail()` → `toProblemMap()` → `ResponseEntity`
  (handler). The problem type registry and builder are the single source of truth.
- **Explicit handlers are clear and auditable.** Each domain exception maps to a
  named `@ExceptionHandler` method visible in the codebase.

#### B — Cons

- **~50 handler methods remain.** Each is a thin 3–5 line method that delegates
  to `problemResponse()`. Total ~950 lines for `ApiExceptionHandler`.
- **Key ordering is guaranteed by `LinkedHashMap`** (which RFC 7807 clients
  should not depend on). Switching to a native `ProblemDetail` +
  `ProblemDetailJacksonMixin` (once a Jackson 3 mixin is available) would lose
  this guarantee.
- **Two entry points.** Pre-dispatch resolver + post-dispatch handlers are
  defined in separate classes, though they share the builder.

### Option C — Pure hand-rolled `Map<String, Any?>` (initial implementation)

Build error responses directly as `Map<String, Any?>` without `ProblemDetail`
at all. `ApiFrameworkExceptionResolver` duplicates framework exception handling
because `@RestControllerAdvice` cannot see pre-dispatch exceptions.

#### C — Pros

- Full control over key ordering and serialization.
- No Spring internals dependency.

#### C — Cons

- **Duplication.** Framework exceptions handled in both `ApiExceptionHandler`
  and `ApiFrameworkExceptionResolver` with different body-building code paths.
- **No standard type.** The codebase does not use Spring's `ProblemDetail`,
  missing out on content negotiation built into `ResponseEntityExceptionHandler`.
- **~930 lines of boilerplate** with no centralized enrichment point.

## Decision

**Option B was chosen.** `ProblemDetail` is used as an internal builder,
converted to `Map<String, Any?>` for wire serialization. Pre-dispatch framework
exceptions are handled by `ApiHandlerExceptionResolver` (a `HandlerExceptionResolver`
with `Ordered.HIGHEST_PRECEDENCE` restricted to `/api/**` paths). Post-dispatch
exceptions are handled by explicit `@ExceptionHandler` methods in
`ApiExceptionHandler`. Both paths share the `problemDetail()` builder and the
`ApiProblemTypes` registry.

### Why not Tier 2 (domain exceptions as `ErrorResponse`)?

Having each domain exception implement `ErrorResponse` / extend
`ErrorResponseException` would eliminate the ~50 individual `@ExceptionHandler`
methods. However, domain exceptions currently live in `epistola-core` with **zero
Spring dependency**. Introducing `ProblemDetail`, `HttpStatus`, and
`ErrorResponseException` into the core domain module would couple domain
entities to HTTP/Servlet concepts.

This coupling is deferred. It could be re-evaluated if the handler count becomes
a maintenance burden or if the editor module's client-side error handling needs
programmatic access to problem details (e.g. through a shared contract object
rather than HTTP-specific types).

## Consequences

- **`ApiFrameworkExceptionResolver` replaced** by `ApiHandlerExceptionResolver`.
  The new resolver delegates to `problemDetail()` / `writeProblemDetail()` for
  zero-duplication body building.
- **`problemDetail()` is the single builder.** Every error response path
  constructs a `ProblemDetail` (for type-safety and structure), then converts to
  `Map` for Jackson 3 serialization.
- **`ClientIdentityFilter` and `ApiKeyAuthenticationFilter` no longer default
  their `ObjectMapper` parameter.** A Spring-managed `ObjectMapper` bean must be
  passed explicitly.
- **`ProblemDetailJacksonMixin` dependency avoided.** If Jackson 3 support
  arrives in a future Spring Framework release, `toProblemMap()` can be removed
  and the mixin enabled — a pure addition with no wire-format change.
- **Post-dispatch framework handlers remain in `ApiExceptionHandler`** as a
  belt-and-suspenders measure: they catch the rare case where a framework
  exception fires after dispatch (e.g. `HttpMediaTypeNotSupportedException`
  from a late argument resolver failure). When both the resolver and handler
  are eligible, the resolver wins because it fires earlier in the chain.
