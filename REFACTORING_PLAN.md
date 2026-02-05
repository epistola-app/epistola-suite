# Flatten Document Generation Architecture - Refactoring Plan

## Context
Refactoring from 2-table structure (requests + items) to 1-table structure (requests only).
Each request now represents ONE document. Batch tracking via `batch_id` column.

**Goal:** Enable true horizontal scaling - each request can be claimed independently by any instance.

## Progress

### ✅ Completed

1. **Database Migration** - Updated `V5__document_generation.sql` in place
   - Changed to flattened structure from the start (no migration needed - project not in production)
   - Each request represents ONE document (not a container for N items)
   - Added `batch_id`, `template_id`, `variant_id`, `version_id`, `environment_id`, `data`, `filename`, `correlation_id`, `document_id` to requests table
   - Removed `document_generation_items` table entirely
   - Created `document_generation_batches` table for aggregated counts
   - Updated indexes and comments

2. **ID Types** - `EntityId.kt`
   - Added `BatchId` as UUIDv7 type
   - Deprecated `GenerationItemId` (marked for removal)

3. **Models**
   - Created `DocumentGenerationBatch.kt` - batch metadata and aggregation
   - Updated `DocumentGenerationRequest.kt` - now contains all fields from items

4. **DocumentGenerationExecutor Refactoring** - Simplified for flattened architecture
   - Removed item-level concurrency control (Semaphore, CompletableFuture)
   - Removed `fetchPendingItems()`, `processItem()`, `finalizeRequest()` methods
   - Updated `generateDocument()` to accept `DocumentGenerationRequest` instead of `DocumentGenerationItem`
   - Added `updateBatchProgress()` to atomically update batch counts
   - Simplified execution: generate → save → update batch (if applicable)
   - Concurrency now managed at JobPoller level

5. **Logging** (from previous commits)
   - Added comprehensive logging to AdaptiveBatchSizer and JobPoller
   - Created `docs/adaptive-batch-logging.md`

### 🚧 Remaining Work

#### Task #5: Update GenerateDocumentBatchHandler
**File:** `modules/epistola-core/src/main/kotlin/app/epistola/suite/documents/commands/GenerateDocumentBatchHandler.kt`

**Current logic:**
```kotlin
fun handle(command: GenerateDocumentBatch): DocumentGenerationRequest {
    // Create ONE request
    val requestId = GenerationRequestId.generate()

    jdbi.useTransaction { handle ->
        // Insert request
        handle.execute("INSERT INTO document_generation_requests ...")

        // Insert N items
        command.items.forEach { item ->
            handle.execute("INSERT INTO document_generation_items ...")
        }
    }

    return request
}
```

**New logic:**
```kotlin
fun handle(command: GenerateDocumentBatch): BatchId {
    val batchId = BatchId.generate()

    jdbi.useTransaction { handle ->
        // Create batch metadata
        handle.execute("""
            INSERT INTO document_generation_batches
            (id, tenant_id, total_count, ...)
            VALUES (:batchId, :tenantId, :totalCount, ...)
        """)

        // Create N requests (one per item)
        handle.prepareBatch("""
            INSERT INTO document_generation_requests
            (id, batch_id, tenant_id, template_id, variant_id, data, ...)
            VALUES (:id, :batchId, :tenantId, :templateId, :variantId, :data, ...)
        """).apply {
            command.items.forEach { item ->
                bind("id", GenerationRequestId.generate())
                bind("batchId", batchId)
                bind("tenantId", command.tenantId)
                bind("templateId", item.templateId)
                bind("variantId", item.variantId)
                bind("data", item.data)
                // ... all other fields
                add()
            }
        }.execute()
    }

    return batchId  // Return batch ID instead of single request
}
```

**Key change:** Return type changes from `DocumentGenerationRequest` to `BatchId`

#### Task #6: Update Query Handlers
**Files:**
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/documents/queries/GetGenerationJob.kt`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/documents/queries/ListGenerationJobs.kt`

**Changes needed:**

1. **GetGenerationJob** - currently returns `DocumentGenerationJob(request, items[])`
   - Option A: Return `DocumentGenerationJob(request, emptyList())` - single request, no items
   - Option B: Deprecate and create `GetBatch(batchId)` that aggregates requests

2. **Create GetBatch query:**
   ```kotlin
   data class GetBatch(val tenantId: TenantId, val batchId: BatchId)

   // Returns: DocumentGenerationBatch + List<DocumentGenerationRequest>
   ```

3. **ListGenerationJobs** - currently lists requests
   - Should list batches instead? Or list individual requests?
   - Decision needed: What does the UI show?

#### Task #7: Update Tests
**Files:**
- `modules/epistola-core/src/test/kotlin/app/epistola/suite/documents/**/*Test.kt`

**Changes needed:**
- Update test helpers that create requests/items
- Fix assertions expecting items
- Update command handler tests for new return types
- Fix query tests for new structure

**Key test files:**
- `DocumentGenerationIntegrationTest.kt`
- `GenerateDocumentBatchHandlerTest.kt`
- `DocumentQueriesTest.kt`

#### Task #8: Documentation
**File:** `docs/generation.md`

**Content needed:**
1. Architecture overview (flattened structure)
2. Why this design (horizontal scaling, distribution)
3. Batch tracking via batch_id
4. Performance characteristics
5. Example: Single document vs batch
6. Migration notes (V9)

## Key Decisions Needed

### 1. Return Type of GenerateDocumentBatch
**Options:**
- A) Return `BatchId` - caller tracks batch, not individual requests
- B) Return `List<GenerationRequestId>` - caller gets all request IDs
- C) Return `DocumentGenerationBatch` - includes metadata

**Recommendation:** Return `BatchId` (option A) - simpler, matches design

### 2. Query API
**Question:** Should queries work with batches or individual requests?

**For UI/API:**
- List all batches for a tenant → `ListBatches`
- Get batch details → `GetBatch(batchId)` returns batch + all requests
- Get single request → `GetGenerationRequest(requestId)` for single-doc jobs

### 3. Backward Compatibility
**DocumentGenerationItem class:**
- Option A: Delete entirely (breaking)
- Option B: Keep as deprecated, throw error if used
- Option C: Map from DocumentGenerationRequest

**Recommendation:** Option B - deprecate but keep for compilation

## Files Modified So Far

```
modules/epistola-core/src/main/resources/db/migration/
  └── V5__document_generation.sql (updated in place to flattened structure)

modules/epistola-core/src/main/kotlin/app/epistola/suite/
  └── common/ids/EntityId.kt (added BatchId)
  └── documents/
      ├── batch/DocumentGenerationExecutor.kt (simplified for flattened structure)
      └── model/
          ├── DocumentGenerationRequest.kt (updated with new fields)
          └── DocumentGenerationBatch.kt (new)

docs/
  └── adaptive-batch-logging.md (from previous work)

CHANGELOG.md (updated)
REFACTORING_PLAN.md (this file)
```

## Files To Modify Next

```
modules/epistola-core/src/main/kotlin/app/epistola/suite/documents/
  └── batch/DocumentGenerationExecutor.kt (simplify)
  └── commands/GenerateDocumentBatchHandler.kt (create N requests)
  └── queries/
      ├── GetGenerationJob.kt (update for new structure)
      ├── ListGenerationJobs.kt (list batches?)
      └── GetBatch.kt (new - get batch with all requests)

modules/epistola-core/src/test/kotlin/app/epistola/suite/documents/
  └── **/*Test.kt (fix all tests)

docs/
  └── generation.md (document new architecture)
```

## Testing Strategy

1. **Run migration on test database** - verify V9 migration works
2. **Fix compilation errors** - update all code using old structure
3. **Update tests incrementally** - one test file at a time
4. **Integration test** - end-to-end batch generation
5. **Performance test** - verify 10,000-doc batch distributes across instances

## Rollout Considerations

**Breaking changes:**
- Database schema (V9 migration - irreversible without backup)
- API return types (GenerateDocumentBatch returns BatchId not Request)
- Query responses (GetGenerationJob structure changes)

**Safe migration:**
1. Deploy code with V9 migration
2. Migration runs automatically on startup
3. Existing data migrated (items → requests)
4. Old items table dropped
5. New structure active

**No downtime needed** - migration is forward-only, data preserved.
